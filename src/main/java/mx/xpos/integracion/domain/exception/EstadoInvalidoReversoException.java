package mx.xpos.integracion.domain.exception;

/**
 * Excepción cuando se intenta reprocesar desde DLQ una transacción que no está en estado reprocesable.
 */
public final class EstadoInvalidoReversoException extends DominioException {

    public EstadoInvalidoReversoException(String reversoId, String estadoActual) {
        super("ERR-409-ESTADO-INVALIDO",
                "El reverso [" + reversoId + "] se encuentra en estado [" + estadoActual + "] y no admite reproceso manual desde DLQ");
    }
}
