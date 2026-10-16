package mx.xpos.integracion.application.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import mx.xpos.integracion.domain.exception.DepuracionParticionNoPermitidaException;
import mx.xpos.integracion.domain.exception.EstadoInvalidoReversoException;
import mx.xpos.integracion.domain.exception.RecursoNoEncontradoException;
import mx.xpos.integracion.domain.model.EscalonRetry;
import mx.xpos.integracion.domain.model.EstadoParticionBitacora;
import mx.xpos.integracion.domain.model.EstadoReverso;
import mx.xpos.integracion.domain.model.EventoBitacora;
import mx.xpos.integracion.domain.model.ProtocoloProveedor;
import mx.xpos.integracion.domain.model.RegistroReverso;
import mx.xpos.integracion.domain.model.ResultadoCanonico;
import mx.xpos.integracion.domain.model.TipoOperacion;
import mx.xpos.integracion.domain.model.TransaccionCanonica;
import mx.xpos.integracion.domain.port.in.ProcesarReversoUseCase;
import mx.xpos.integracion.domain.port.in.SoporteOperativoUseCase;
import mx.xpos.integracion.domain.port.out.ConectorProveedorPort;
import mx.xpos.integracion.domain.port.out.PublicadorEventosOutboxPort;
import mx.xpos.integracion.domain.port.out.RepositorioBitacoraPort;
import mx.xpos.integracion.domain.port.out.RepositorioReversoPort;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Núcleo del Motor Genérico de Transacciones y Servicio de Reversos.
 * Implementa:
 * 1) Modelo de transacciones cortas en 3 fases (RT-03) para no retener conexiones DB durante I/O.
 * 2) Coordinación entre Circuit Breaker de Resilience4j y pausa/reanudación de Kafka (RT-02).
 * 3) Pattern Matching exhaustivo de Java 21 sobre la jerarquía sellada ResultadoCanonico.
 * 4) Bitácora append-only con cadena de hashes SHA-256 sin contención global (RT-04).
 * 5) Depuración condicionada de particiones (DETACH + DROP) verificando WORM y réplica.
 */
@ApplicationScoped
public class MotorReversosService implements ProcesarReversoUseCase, SoporteOperativoUseCase {

    private static final Logger LOG = Logger.getLogger(MotorReversosService.class);
    private static final DateTimeFormatter FORMATO_PARTICION_MENSUAL = DateTimeFormatter.ofPattern("'bitacora_auditoria_y'yyyy'm'MM");

    @Inject
    RepositorioReversoPort repositorioReverso;

    @Inject
    RepositorioBitacoraPort repositorioBitacora;

    @Inject
    PublicadorEventosOutboxPort publicadorOutbox;

    @Inject
    EnmascaradorPciService enmascaradorPci;

    @Inject
    Instance<ConectorProveedorPort> conectoresDisponibles;

    @ConfigProperty(name = "xpos.motor.reintentos-inmediatos.max", defaultValue = "2")
    int maxReintentosInmediatos;

    @ConfigProperty(name = "xpos.motor.circuit-breaker.sliding-window-size", defaultValue = "4")
    int cbSlidingWindowSize;

    @ConfigProperty(name = "xpos.motor.circuit-breaker.minimum-calls", defaultValue = "2")
    int cbMinimumNumberOfCalls;

    @ConfigProperty(name = "xpos.motor.circuit-breaker.wait-open-ms", defaultValue = "3000")
    long cbWaitDurationInOpenMs;

    private final Map<ProtocoloProveedor, ConectorProveedorPort> mapaConectores = new EnumMap<>(ProtocoloProveedor.class);
    private CircuitBreaker circuitBreakerProveedor;
    private Retry retryInmediato;

    @PostConstruct
    void inicializar() {
        for (ConectorProveedorPort conector : conectoresDisponibles) {
            mapaConectores.put(conector.protocoloSoportado(), conector);
        }
        configurarResilience4j();
    }

    private void configurarResilience4j() {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cbSlidingWindowSize)
                .minimumNumberOfCalls(cbMinimumNumberOfCalls)
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .waitDurationInOpenState(Duration.ofMillis(cbWaitDurationInOpenMs))
                // Solo las fallas técnicas (ErrorReintentable / EstadoDesconocido) cuentan como falla del circuito;
                // los errores de negocio (422) son respuestas válidas del autorizador y NO abren el circuito.
                .recordResult(resultado -> resultado instanceof ResultadoCanonico.ErrorReintentable
                        || resultado instanceof ResultadoCanonico.EstadoDesconocido)
                .build();

        this.circuitBreakerProveedor = CircuitBreaker.of("proveedor-pagos-cb", cbConfig);

        // Listener RT-02: Sincroniza el estado del Circuit Breaker con la pausa/reanudación de Kafka
        this.circuitBreakerProveedor.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State nuevoEstado = event.getStateTransition().getToState();
            LOG.infof("[RT-02 CircuitBreaker] Transición de estado: %s -> %s",
                    event.getStateTransition().getFromState(), nuevoEstado);
            switch (nuevoEstado) {
                case OPEN, FORCED_OPEN -> publicadorOutbox.pausarConsumidorPrincipal(
                        "Circuit Breaker en estado " + nuevoEstado + " para proteger al proveedor y evitar quemar retries");
                case HALF_OPEN, CLOSED -> publicadorOutbox.reanudarConsumidorPrincipal(
                        "Circuit Breaker en estado " + nuevoEstado + " listo para sondas o drenado normal");
                default -> { }
            }
        });

        RetryConfig retryConfig = RetryConfig.<ResultadoCanonico>custom()
                .maxAttempts(maxReintentosInmediatos)
                .waitDuration(Duration.ofMillis(25))
                // IMPORTANTE (Principio P1 / Línea 145): EstadoDesconocido NUNCA dispara reenvío ciego inmediato;
                // solo ErrorReintentable se reintenta de forma inmediata.
                .retryOnResult(res -> res instanceof ResultadoCanonico.ErrorReintentable)
                .build();

        this.retryInmediato = Retry.of("proveedor-pagos-retry-inmediato", retryConfig);
    }

    @Override
    public RegistroReverso procesarSolicitudReverso(TransaccionCanonica transaccion, EscalonRetry escalonOrigen) {
        // 1. Validación estricta de seguridad PCI-DSS antes de tocar DB o Kafka
        enmascaradorPci.validarOEnmascararTarjeta(transaccion.tarjetaTokenizadaOEnmascarada(), true);

        // 2. Verificación de Idempotencia Fuerte (Clave única: transaccionOriginalId + reversoId, RT-05)
        Optional<RegistroReverso> existenteOpt = repositorioReverso.buscarPorClaveIdempotencia(
                transaccion.transaccionOriginalId(), transaccion.reversoId());

        if (existenteOpt.isPresent() && existenteOpt.get().estadoActual().esTerminalExitoso()) {
            LOG.infof("[Idempotencia] Reverso [%s] para tx [%s] ya se encuentra en estado terminal [%s]. No se reprocesa.",
                    transaccion.reversoId(), transaccion.transaccionOriginalId(), existenteOpt.get().estadoActual());
            return existenteOpt.get();
        }

        // 3. Si el consumidor de Kafka está pausado por Circuit Breaker OPEN y el mensaje viene del topic de entrada,
        // se acumula en la cola retenida sin quemar escalones de retry ni enviar al proveedor caído (Guion Demo Paso 2)
        if (publicadorOutbox.isConsumidorPrincipalPausado() && escalonOrigen == EscalonRetry.INICIO) {
            RegistroReverso retenido = registrarEnFase1Recepcion(transaccion, existenteOpt, escalonOrigen,
                    "Retenido en cola principal: Consumidor Kafka PAUSADO por Circuit Breaker OPEN");
            publicadorOutbox.retenerEnColaPrincipalPausada(transaccion);
            return retenido;
        }

        // FASE TX-1 (Corta, < 5 ms): Registrar recepción / EN_PROCESO y liberar conexión antes de llamada de red (RT-03)
        RegistroReverso enProceso = registrarEnFase1Recepcion(transaccion, existenteOpt, escalonOrigen,
                "Iniciando procesamiento en escalón " + escalonOrigen.topicName());

        // FASE I/O (Sin conexión de base de datos retenida): Invocar al adaptador del proveedor con Resilience4j
        AtomicInteger intentosEnEstaEjecucion = new AtomicInteger(0);
        ResultadoCanonico resultadoCanonico;
        try {
            ConectorProveedorPort adaptador = obtenerAdaptador(transaccion.protocolo());
            resultadoCanonico = Retry.decorateSupplier(retryInmediato,
                    CircuitBreaker.decorateSupplier(circuitBreakerProveedor, () -> {
                        int intentoActual = enProceso.intentosTotales() + intentosEnEstaEjecucion.incrementAndGet();
                        ResultadoCanonico res = adaptador.ejecutarTransaccion(transaccion);
                        registrarIntentoIntermedioEnBitacora(transaccion, intentoActual, res);
                        return res;
                    })
            ).get();
        } catch (CallNotPermittedException cbOpenEx) {
            // El Circuit Breaker se abrió o ya estaba abierto: pausamos el consumidor y retenemos el mensaje sin quemar retries
            publicadorOutbox.pausarConsumidorPrincipal("CallNotPermittedException: Circuito abierto");
            publicadorOutbox.retenerEnColaPrincipalPausada(transaccion);
            return enProceso;
        }

        int totalIntentosAcumulados = enProceso.intentosTotales() + Math.max(1, intentosEnEstaEjecucion.get());

        // Si como consecuencia de esta llamada el Circuit Breaker acaba de abrirse (ej. alcanzó el umbral del 50%),
        // el mensaje también queda retenido en la cola pausada para drenarse cuando el proveedor regrese (Paso 2 -> Paso 3)
        if (circuitBreakerProveedor.getState() == CircuitBreaker.State.OPEN
                && (resultadoCanonico instanceof ResultadoCanonico.ErrorReintentable
                || resultadoCanonico instanceof ResultadoCanonico.EstadoDesconocido)) {
            publicadorOutbox.retenerEnColaPrincipalPausada(transaccion);
            return actualizarFase2(transaccion, enProceso, EstadoReverso.REINTENTO_DIFERIDO, escalonOrigen,
                    totalIntentosAcumulados, resultadoCanonico.codigoProveedor(),
                    resultadoCanonico.latenciaMs(),
                    "Circuito abierto tras falla del proveedor; consumidor pausado y mensaje retenido para drenado");
        }

        // FASE TX-2 (Corta, < 5 ms): Pattern Matching de Java 21 sobre la clase canónica y publicación vía Outbox
        return switch (resultadoCanonico) {
            case ResultadoCanonico.Exito exito -> actualizarFase2(
                    transaccion, enProceso, EstadoReverso.EXITOSO, escalonOrigen,
                    totalIntentosAcumulados, exito.codigoProveedor(), exito.latenciaMs(),
                    "Reverso aplicado exitosamente. Autorización: " + exito.autorizacionProveedor()
            );

            case ResultadoCanonico.YaAplicado yaAplicado -> actualizarFase2(
                    transaccion, enProceso, EstadoReverso.YA_APLICADO, escalonOrigen,
                    totalIntentosAcumulados, yaAplicado.codigoProveedor(), yaAplicado.latenciaMs(),
                    "El proveedor confirmó que la transacción ya estaba reversada previamente (idempotente)"
            );

            case ResultadoCanonico.ErrorNegocio errorNegocio -> {
                // Errores de negocio NO se reintentan: van directo a reverso.dlq para soporte (Guion Demo Paso 4)
                RegistroReverso enDlq = actualizarFase2(
                        transaccion, enProceso, EstadoReverso.DLQ_INTERVENCION_MANUAL, EscalonRetry.DLQ,
                        totalIntentosAcumulados, errorNegocio.codigoProveedor(), errorNegocio.latenciaMs(),
                        "Error de negocio no reintentable [" + errorNegocio.motivoRechazo() + "]: " + errorNegocio.detalle()
                );
                publicadorOutbox.registrarEventoOutbox(transaccion, EstadoReverso.DLQ_INTERVENCION_MANUAL,
                        EscalonRetry.DLQ, errorNegocio.detalle());
                yield enDlq;
            }

            case ResultadoCanonico.EstadoDesconocido desconocido -> manejarReintentoDiferidoODlq(
                    transaccion, enProceso, escalonOrigen, totalIntentosAcumulados,
                    desconocido.codigoProveedor(), desconocido.latenciaMs(),
                    EstadoReverso.ESTADO_DESCONOCIDO_CONSULTA,
                    "Estado desconocido (timeout post-envío): requiere consulta previa / reverso 0421 en escalón diferido. " + desconocido.detalle()
            );

            case ResultadoCanonico.ErrorReintentable reintentable -> manejarReintentoDiferidoODlq(
                    transaccion, enProceso, escalonOrigen, totalIntentosAcumulados,
                    reintentable.codigoProveedor(), reintentable.latenciaMs(),
                    EstadoReverso.REINTENTO_DIFERIDO,
                    "Agotados reintentos inmediatos: " + reintentable.detalle()
            );
        };
    }

    private RegistroReverso manejarReintentoDiferidoODlq(
            TransaccionCanonica transaccion,
            RegistroReverso enProceso,
            EscalonRetry escalonOrigen,
            int totalIntentosAcumulados,
            String codigoProveedor,
            long latenciaMs,
            EstadoReverso estadoEnEspera,
            String motivo
    ) {
        Optional<EscalonRetry> siguienteEscalonOpt = escalonOrigen.siguienteEscalonRetry();
        if (siguienteEscalonOpt.isPresent()) {
            EscalonRetry siguienteEscalon = siguienteEscalonOpt.get();
            RegistroReverso diferido = actualizarFase2(
                    transaccion, enProceso, estadoEnEspera, siguienteEscalon,
                    totalIntentosAcumulados, codigoProveedor, latenciaMs,
                    motivo + " -> Enviado a " + siguienteEscalon.topicName()
            );
            publicadorOutbox.registrarEventoOutbox(transaccion, estadoEnEspera, siguienteEscalon, motivo);
            return diferido;
        } else {
            // Se agotaron todos los escalones (.1m, .5m, .30m) -> Pasa a DLQ para intervención manual
            RegistroReverso enDlq = actualizarFase2(
                    transaccion, enProceso, EstadoReverso.DLQ_INTERVENCION_MANUAL, EscalonRetry.DLQ,
                    totalIntentosAcumulados, codigoProveedor, latenciaMs,
                    "Escalones diferidos agotados (.1m, .5m, .30m). Enviado a DLQ para intervención manual: " + motivo
            );
            publicadorOutbox.registrarEventoOutbox(transaccion, EstadoReverso.DLQ_INTERVENCION_MANUAL,
                    EscalonRetry.DLQ, motivo);
            return enDlq;
        }
    }

    @Override
    public List<RegistroReverso> drenarPendientesPausados() {
        // Si el circuito estaba en OPEN, transicionamos a HALF_OPEN (sonda) y luego a CLOSED al tener éxito
        if (circuitBreakerProveedor.getState() == CircuitBreaker.State.OPEN) {
            circuitBreakerProveedor.transitionToHalfOpenState();
        }
        publicadorOutbox.reanudarConsumidorPrincipal("Drenado de mensajes tras recuperación del proveedor");

        List<TransaccionCanonica> pendientes = publicadorOutbox.extraerPendientesColaPausada();
        List<RegistroReverso> procesados = new ArrayList<>(pendientes.size());
        for (TransaccionCanonica tx : pendientes) {
            RegistroReverso res = procesarSolicitudReverso(tx, EscalonRetry.INICIO);
            procesados.add(res);
        }
        return procesados;
    }

    @Override
    public int contarMensajesRetenidosEnColaPausada() {
        return publicadorOutbox.contarPendientesEnColaPausada();
    }

    @Override
    public ResultadoAuditoriaFolio consultarBitacoraConVerificacion(String folioOCorrelationId) {
        List<EventoBitacora> eventos = repositorioBitacora.buscarPorFolioOCorrelationId(folioOCorrelationId);
        if (eventos.isEmpty()) {
            throw new RecursoNoEncontradoException("Bitácora de Transacción", folioOCorrelationId);
        }
        boolean cadenaValida = true;
        String hashEsperadoAnterior = EventoBitacora.GENESIS_HASH;
        for (EventoBitacora ev : eventos) {
            if (!ev.verificarIntegridadEslabon(hashEsperadoAnterior)) {
                cadenaValida = false;
                break;
            }
            hashEsperadoAnterior = ev.currHashSha256();
        }
        return new ResultadoAuditoriaFolio(
                folioOCorrelationId,
                cadenaValida,
                eventos.size(),
                hashEsperadoAnterior,
                eventos
        );
    }

    @Override
    public List<RegistroReverso> listarReversosEnDlq() {
        return repositorioReverso.listarEnDlq();
    }

    @Override
    public RegistroReverso reprocesarDesdeDlq(String reversoId, String operadorSoporte, String motivo) {
        RegistroReverso actual = repositorioReverso.buscarPorReversoId(reversoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Reverso", reversoId));

        if (!actual.estadoActual().permiteReprocesoSoporte()) {
            throw new EstadoInvalidoReversoException(reversoId, actual.estadoActual().name());
        }

        TransaccionCanonica txReproceso = new TransaccionCanonica(
                actual.correlationId(),
                actual.transaccionOriginalId(),
                actual.reversoId(),
                actual.idempotencyKey(),
                "PAGO_TARJETA",
                TipoOperacion.REVERSO,
                actual.monto(),
                actual.moneda(),
                actual.cajaId(),
                actual.folio(),
                actual.proveedorId(),
                actual.protocolo(),
                actual.tarjetaEnmascarada(),
                actual.correlacionIso(),
                Map.of("reprocesadoPor", operadorSoporte, "motivoSoporte", motivo),
                actual.fechaCreacion()
        );

        insertarBitacoraConHash(txReproceso, EstadoReverso.EN_PROCESO, actual.intentosTotales() + 1,
                "SOPORTE-REPLAY", 0L,
                "Reproceso manual desde DLQ autorizado por [" + operadorSoporte + "]: " + motivo);

        return procesarSolicitudReverso(txReproceso, EscalonRetry.INICIO);
    }

    @Override
    public EstadoParticionBitacora registrarEstadoParticion(
            String nombreParticion,
            LocalDate fechaInicio,
            LocalDate fechaFin,
            boolean archivadoEnFrioWorm,
            boolean replicadoAReportes,
            long conteoFilasArchivadas,
            long conteoFilasReplicadas
    ) {
        long filasRealesEnParticion = repositorioBitacora.contarFilasEnParticion(nombreParticion);
        String hashCierre = repositorioBitacora.calcularHashCierreParticion(nombreParticion);
        String rutaWorm = archivadoEnFrioWorm
                ? "https://xposcoldstorage.blob.core.windows.net/bitacora-worm/" + nombreParticion + ".parquet"
                : null;

        EstadoParticionBitacora estado = new EstadoParticionBitacora(
                nombreParticion,
                fechaInicio,
                fechaFin,
                filasRealesEnParticion,
                conteoFilasArchivadas,
                conteoFilasReplicadas,
                hashCierre,
                rutaWorm,
                archivadoEnFrioWorm,
                replicadoAReportes,
                false,
                Instant.now()
        );
        return repositorioBitacora.guardarEstadoParticion(estado);
    }

    @Override
    public EstadoParticionBitacora depurarParticionVencida(String nombreParticion) {
        EstadoParticionBitacora particion = repositorioBitacora.buscarParticion(nombreParticion)
                .orElseThrow(() -> new RecursoNoEncontradoException("Partición de Bitácora", nombreParticion));

        if (!particion.archivadoEnFrioWorm()) {
            throw new DepuracionParticionNoPermitidaException(
                    nombreParticion,
                    "La partición NO ha sido exportada a Blob Storage Parquet inmutable (WORM)"
            );
        }
        if (!particion.replicadoAReportes()) {
            throw new DepuracionParticionNoPermitidaException(
                    nombreParticion,
                    "La partición NO ha sido confirmada por el proceso de extracción incremental a Reportes Comerciales"
            );
        }
        if (particion.conteoFilasParticion() != particion.conteoFilasArchivadas()
                || particion.conteoFilasParticion() != particion.conteoFilasReplicadas()) {
            throw new DepuracionParticionNoPermitidaException(
                    nombreParticion,
                    String.format("Discrepancia de conteo de filas: Partición=%d, ArchivoFrío=%d, RéplicaReportes=%d",
                            particion.conteoFilasParticion(), particion.conteoFilasArchivadas(), particion.conteoFilasReplicadas())
            );
        }
        if (!particion.cumpleCondicionesDepuracionSegura()) {
            throw new DepuracionParticionNoPermitidaException(
                    nombreParticion,
                    "Falta firma de cierre SHA-256 o manifiesto WORM válido"
            );
        }

        EstadoParticionBitacora depurada = new EstadoParticionBitacora(
                particion.nombreParticion(),
                particion.fechaInicioRango(),
                particion.fechaFinRango(),
                particion.conteoFilasParticion(),
                particion.conteoFilasArchivadas(),
                particion.conteoFilasReplicadas(),
                particion.hashCierreSha256(),
                particion.rutaBlobParquetWorm(),
                true,
                true,
                true,
                Instant.now()
        );
        LOG.infof("[Mantenimiento Bitácora] Ejecutado DETACH + DROP seguro de partición [%s] con hash de cierre [%s]",
                nombreParticion, particion.hashCierreSha256());
        return repositorioBitacora.guardarEstadoParticion(depurada);
    }

    @Override
    public List<EstadoParticionBitacora> listarParticiones() {
        return repositorioBitacora.listarParticiones();
    }

    public String obtenerEstadoCircuitBreaker() {
        return circuitBreakerProveedor.getState().name();
    }

    public void restablecerCircuitBreaker() {
        circuitBreakerProveedor.reset();
        publicadorOutbox.reanudarConsumidorPrincipal("Reset explícito del Circuit Breaker");
    }

    private RegistroReverso registrarEnFase1Recepcion(
            TransaccionCanonica tx,
            Optional<RegistroReverso> existenteOpt,
            EscalonRetry escalon,
            String detalleBitacora
    ) {
        Instant ahora = Instant.now();
        RegistroReverso registro;
        if (existenteOpt.isPresent()) {
            registro = existenteOpt.get().conTransicion(
                    EstadoReverso.EN_PROCESO,
                    escalon,
                    existenteOpt.get().intentosTotales(),
                    "EN_PROCESO",
                    detalleBitacora,
                    ahora
            );
        } else {
            registro = new RegistroReverso(
                    tx.transaccionOriginalId(),
                    tx.reversoId(),
                    tx.correlationId(),
                    tx.idempotencyKey(),
                    tx.cajaId(),
                    tx.folio(),
                    tx.proveedorId(),
                    tx.protocolo(),
                    tx.monto(),
                    tx.moneda(),
                    tx.tarjetaTokenizadaOEnmascarada(),
                    EstadoReverso.RECIBIDO,
                    escalon,
                    0,
                    "RECIBIDO",
                    detalleBitacora,
                    tx.correlacionIso(),
                    ahora,
                    ahora
            );
            insertarBitacoraConHash(tx, EstadoReverso.RECIBIDO, 0, "00-RECIBIDO", 0L, detalleBitacora);
        }
        return repositorioReverso.guardarOActualizar(registro);
    }

    private void registrarIntentoIntermedioEnBitacora(TransaccionCanonica tx, int intento, ResultadoCanonico res) {
        insertarBitacoraConHash(
                tx,
                EstadoReverso.EN_PROCESO,
                intento,
                res.codigoProveedor(),
                res.latenciaMs(),
                "Intento #" + intento + " -> Clase canónica [" + res.getClass().getSimpleName() + "]: " + res.detalle()
        );
    }

    private RegistroReverso actualizarFase2(
            TransaccionCanonica tx,
            RegistroReverso registroActual,
            EstadoReverso nuevoEstado,
            EscalonRetry escalon,
            int intentosTotales,
            String codigoRespuesta,
            long latenciaMs,
            String detalle
    ) {
        Instant ahora = Instant.now();
        String detalleSanitizado = enmascaradorPci.sanitizarTextoParaBitacora(detalle);
        RegistroReverso actualizado = registroActual.conTransicion(
                nuevoEstado, escalon, intentosTotales, codigoRespuesta, detalleSanitizado, ahora
        );
        repositorioReverso.guardarOActualizar(actualizado);
        insertarBitacoraConHash(tx, nuevoEstado, intentosTotales, codigoRespuesta, latenciaMs, detalleSanitizado);
        return actualizado;
    }

    private void insertarBitacoraConHash(
            TransaccionCanonica tx,
            EstadoReverso estado,
            int intento,
            String codigoRespuesta,
            long latenciaMs,
            String detalle
    ) {
        Instant ahora = Instant.now();
        String nombreParticion = FORMATO_PARTICION_MENSUAL.format(ahora.atZone(ZoneOffset.UTC));
        String prevHash = repositorioBitacora.obtenerUltimoHashPorCorrelationId(tx.correlationId())
                .orElse(EventoBitacora.GENESIS_HASH);

        EventoBitacora evento = EventoBitacora.crearConHashEncadenado(
                ahora,
                nombreParticion,
                tx,
                estado,
                intento,
                codigoRespuesta,
                latenciaMs,
                enmascaradorPci.sanitizarTextoParaBitacora(detalle),
                prevHash
        );
        repositorioBitacora.insertarEventoAppendOnly(evento);
    }

    private ConectorProveedorPort obtenerAdaptador(ProtocoloProveedor protocolo) {
        ConectorProveedorPort adaptador = mapaConectores.get(protocolo);
        if (adaptador == null) {
            throw new IllegalStateException("No existe adaptador registrado para el protocolo: " + protocolo);
        }
        return adaptador;
    }
}
