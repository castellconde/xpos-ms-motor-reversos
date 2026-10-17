package mx.xpos.integracion.infrastructure.adapter.out.proveedor;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Controlador del Sandbox Multiprotocolo (ISO 8583, SOAP, REST) para el Piloto Local
 * y para el Gobierno de Versiones de XPOS (independencia de ambientes externos de proveedores).
 * Soporta tanto cambio de modo global (Chaos Testing) como Reglas Deterministas por sufijo de folio:
 * - Folios terminados en "-99" -> ERROR_NEGOCIO_422 (DE39=12 / SOAPFault Client / HTTP 422)
 * - Folios terminados en "-68" -> TIMEOUT_ESTADO_DESCONOCIDO (DE39=68 / Read Timeout / HTTP 504)
 * - Folios terminados en "-91" -> ERROR_503_REINTENTABLE (DE39=91 / SOAPFault Server / HTTP 503)
 * - Folios terminados en "-94" -> YA_REVERSADO_IDEMPOTENTE (DE39=94 / HTTP 200 Already Reversed)
 */
@ApplicationScoped
public class SimuladorProveedorControl {

    public enum ModoProveedor {
        ARRIBA_EXITOSO,
        APAGADO_SIN_CONEXION,
        TIMEOUT_ESTADO_DESCONOCIDO,
        ERROR_503_REINTENTABLE,
        ERROR_NEGOCIO_422,
        YA_REVERSADO_IDEMPOTENTE
    }

    public record ReglaDeterministaFolio(
            String reglaId,
            String sufijoFolio,
            ModoProveedor modoRespuesta,
            long latenciaMs,
            String descripcionGobiernoVersion
    ) {}

    public record TramaRecibidaSandbox(
            int secuencia,
            String protocolo,
            String proveedorId,
            String mtiOOperacion,
            String folio,
            String detalleProtocolo,
            ModoProveedor modoAplicado
    ) {}

    private final AtomicReference<ModoProveedor> modoActual = new AtomicReference<>(ModoProveedor.ARRIBA_EXITOSO);
    private final AtomicLong latenciaSimuladaMs = new AtomicLong(12L);
    private final AtomicInteger llamadasRecibidas = new AtomicInteger(0);
    private final Map<String, ReglaDeterministaFolio> reglasPorSufijo = new ConcurrentHashMap<>();
    private final Set<String> foliosIsoReversadosEnSandbox = ConcurrentHashMap.newKeySet();
    private final List<TramaRecibidaSandbox> bitacoraTramasSandbox = new CopyOnWriteArrayList<>();

    @PostConstruct
    void init() {
        cargarReglasPorDefectoGobiernoXpos();
    }

    public void cargarReglasPorDefectoGobiernoXpos() {
        reglasPorSufijo.clear();
        registrarRegla(new ReglaDeterministaFolio(
                "GOV-XPOS-99", "-99", ModoProveedor.ERROR_NEGOCIO_422, 10L,
                "Certificación Gobierno XPOS: Folios terminados en -99 simulan rechazo de negocio (DE39=12 / SOAPFault Client / HTTP 422)"
        ));
        registrarRegla(new ReglaDeterministaFolio(
                "GOV-XPOS-68", "-68", ModoProveedor.TIMEOUT_ESTADO_DESCONOCIDO, 3000L,
                "Certificación Gobierno XPOS: Folios terminados en -68 simulan timeout post-envío / Estado Desconocido (DE39=68 / HTTP 504)"
        ));
        registrarRegla(new ReglaDeterministaFolio(
                "GOV-XPOS-91", "-91", ModoProveedor.ERROR_503_REINTENTABLE, 15L,
                "Certificación Gobierno XPOS: Folios terminados en -91 simulan falla técnica reintentable (DE39=91 / HTTP 503)"
        ));
        registrarRegla(new ReglaDeterministaFolio(
                "GOV-XPOS-94", "-94", ModoProveedor.YA_REVERSADO_IDEMPOTENTE, 10L,
                "Certificación Gobierno XPOS: Folios terminados en -94 simulan operación ya reversada en autorizador (DE39=94)"
        ));
    }

    public ModoProveedor getModoActual() {
        return modoActual.get();
    }

    /**
     * Evalúa el modo efectivo para una transacción: si el modo global es ARRIBA_EXITOSO,
     * verifica si el folio coincide con alguna regla determinista de gobierno de versiones (-99, -68, -91, -94).
     */
    public ModoProveedor resolverModoParaFolio(String folio) {
        ModoProveedor global = modoActual.get();
        if (global != ModoProveedor.ARRIBA_EXITOSO) {
            return global;
        }
        if (folio != null) {
            for (ReglaDeterministaFolio regla : reglasPorSufijo.values()) {
                if (folio.endsWith(regla.sufijoFolio())) {
                    return regla.modoRespuesta();
                }
            }
        }
        return ModoProveedor.ARRIBA_EXITOSO;
    }

    public void registrarTramaProtocolo(
            String protocolo,
            String proveedorId,
            String mtiOOperacion,
            String folio,
            String detalleProtocolo,
            ModoProveedor modoAplicado
    ) {
        int sec = llamadasRecibidas.incrementAndGet();
        bitacoraTramasSandbox.add(new TramaRecibidaSandbox(
                sec, protocolo, proveedorId, mtiOOperacion, folio, detalleProtocolo, modoAplicado
        ));
    }

    public ReglaDeterministaFolio registrarRegla(ReglaDeterministaFolio regla) {
        reglasPorSufijo.put(regla.reglaId(), regla);
        return regla;
    }

    public List<ReglaDeterministaFolio> listarReglasDeterministas() {
        return List.copyOf(reglasPorSufijo.values());
    }

    public List<TramaRecibidaSandbox> listarTramasRecibidas() {
        return List.copyOf(bitacoraTramasSandbox);
    }

    public boolean marcarYVerificarYaReversadoEnSandbox(String claveUnica) {
        return !foliosIsoReversadosEnSandbox.add(claveUnica);
    }

    public void cambiarModo(ModoProveedor nuevoModo, long latenciaMs) {
        this.modoActual.set(nuevoModo);
        this.latenciaSimuladaMs.set(Math.max(0L, latenciaMs));
    }

    public long getLatenciaSimuladaMs() {
        return latenciaSimuladaMs.get();
    }

    public int registrarLlamada() {
        return llamadasRecibidas.incrementAndGet();
    }

    public int getLlamadasRecibidas() {
        return llamadasRecibidas.get();
    }

    public void reiniciarContadores() {
        this.llamadasRecibidas.set(0);
        this.modoActual.set(ModoProveedor.ARRIBA_EXITOSO);
        this.latenciaSimuladaMs.set(12L);
        this.foliosIsoReversadosEnSandbox.clear();
        this.bitacoraTramasSandbox.clear();
        cargarReglasPorDefectoGobiernoXpos();
    }
}
