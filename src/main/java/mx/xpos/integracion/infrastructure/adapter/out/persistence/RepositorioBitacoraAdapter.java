package mx.xpos.integracion.infrastructure.adapter.out.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import mx.xpos.integracion.domain.model.EstadoParticionBitacora;
import mx.xpos.integracion.domain.model.EventoBitacora;
import mx.xpos.integracion.domain.port.out.RepositorioBitacoraPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptador de persistencia para la bitácora append-only particionada (bitacora_auditoria)
 * y la tabla de control de particiones (control_particion_bitacora).
 * Cumple la política: solo operaciones de INSERT sobre los eventos de bitácora (fillfactor 100).
 */
@ApplicationScoped
public class RepositorioBitacoraAdapter implements RepositorioBitacoraPort {

    private final AtomicLong secuenciaId = new AtomicLong(1000L);
    private final List<EventoBitacora> eventosAppendOnly = new CopyOnWriteArrayList<>();
    private final Map<String, EstadoParticionBitacora> controlParticiones = new ConcurrentHashMap<>();

    @Override
    public EventoBitacora insertarEventoAppendOnly(EventoBitacora evento) {
        long nuevoId = secuenciaId.incrementAndGet();
        EventoBitacora persistido = new EventoBitacora(
                nuevoId,
                evento.fechaEvento(),
                evento.nombreParticion(),
                evento.correlationId(),
                evento.transaccionOriginalId(),
                evento.reversoId(),
                evento.cajaId(),
                evento.folio(),
                evento.tipoTransaccion(),
                evento.proveedorId(),
                evento.protocolo(),
                evento.estadoTransicion(),
                evento.numeroIntento(),
                evento.codigoRespuesta(),
                evento.latenciaMs(),
                evento.tarjetaEnmascarada(),
                evento.detalleEnmascarado(),
                evento.prevHashSha256(),
                evento.currHashSha256()
        );
        eventosAppendOnly.add(persistido);
        return persistido;
    }

    @Override
    public Optional<String> obtenerUltimoHashPorCorrelationId(String correlationId) {
        String ultimoHash = null;
        for (EventoBitacora ev : eventosAppendOnly) {
            if (ev.correlationId().equals(correlationId)) {
                ultimoHash = ev.currHashSha256();
            }
        }
        return Optional.ofNullable(ultimoHash);
    }

    @Override
    public List<EventoBitacora> buscarPorFolioOCorrelationId(String identificador) {
        return eventosAppendOnly.stream()
                .filter(e -> e.folio().equalsIgnoreCase(identificador)
                        || e.correlationId().equalsIgnoreCase(identificador)
                        || e.reversoId().equalsIgnoreCase(identificador))
                .sorted(Comparator.comparing(EventoBitacora::id))
                .toList();
    }

    @Override
    public long contarFilasEnParticion(String nombreParticion) {
        return eventosAppendOnly.stream()
                .filter(e -> e.nombreParticion().equalsIgnoreCase(nombreParticion))
                .count();
    }

    @Override
    public String calcularHashCierreParticion(String nombreParticion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<EventoBitacora> deParticion = eventosAppendOnly.stream()
                    .filter(e -> e.nombreParticion().equalsIgnoreCase(nombreParticion))
                    .sorted(Comparator.comparing(EventoBitacora::id))
                    .toList();

            if (deParticion.isEmpty()) {
                digest.update(("PARTICION-VACIA-SELLADA|" + nombreParticion).getBytes(StandardCharsets.UTF_8));
            } else {
                for (EventoBitacora ev : deParticion) {
                    digest.update(ev.currHashSha256().getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    @Override
    public EstadoParticionBitacora guardarEstadoParticion(EstadoParticionBitacora estadoParticion) {
        controlParticiones.put(estadoParticion.nombreParticion(), estadoParticion);
        return estadoParticion;
    }

    @Override
    public Optional<EstadoParticionBitacora> buscarParticion(String nombreParticion) {
        return Optional.ofNullable(controlParticiones.get(nombreParticion));
    }

    @Override
    public List<EstadoParticionBitacora> listarParticiones() {
        return new ArrayList<>(controlParticiones.values());
    }

    public void limpiarTodoParaPruebas() {
        eventosAppendOnly.clear();
        controlParticiones.clear();
    }
}
