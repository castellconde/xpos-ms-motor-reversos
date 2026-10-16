package mx.xpos.integracion.infrastructure.adapter.out.proveedor;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Controlador del proveedor externo simulado para el Guion de Demo de 6 pasos (Línea 269).
 * Permite simular:
 * - Proveedor arriba (ARRIBA_EXITOSO)
 * - Proveedor apagado / sin conexión (APAGADO_SIN_CONEXION)
 * - Timeout post-envío / estado desconocido (TIMEOUT_ESTADO_DESCONOCIDO)
 * - Error técnico 503 reintentable (ERROR_503_REINTENTABLE)
 * - Error de negocio 422 no reintentable (ERROR_NEGOCIO_422)
 * - Transacción ya reversada previamente en el autorizador (YA_REVERSADO_IDEMPOTENTE)
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

    private final AtomicReference<ModoProveedor> modoActual = new AtomicReference<>(ModoProveedor.ARRIBA_EXITOSO);
    private final AtomicLong latenciaSimuladaMs = new AtomicLong(12L);
    private final AtomicInteger llamadasRecibidas = new AtomicInteger(0);

    public ModoProveedor getModoActual() {
        return modoActual.get();
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
    }
}
