package mx.xpos.integracion.domain.model;

/**
 * Máquina de estados del motor genérico de transacciones y servicio de reversos.
 */
public enum EstadoReverso {
    RECIBIDO,
    EN_PROCESO,
    EXITOSO,
    YA_APLICADO,
    REINTENTO_DIFERIDO,
    ESTADO_DESCONOCIDO_CONSULTA,
    RECHAZADO_NEGOCIO,
    DLQ_INTERVENCION_MANUAL;

    public boolean esTerminalExitoso() {
        return this == EXITOSO || this == YA_APLICADO;
    }

    public boolean permiteReprocesoSoporte() {
        return this == DLQ_INTERVENCION_MANUAL || this == RECHAZADO_NEGOCIO;
    }
}
