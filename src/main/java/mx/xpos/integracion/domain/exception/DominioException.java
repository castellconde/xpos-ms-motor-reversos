package mx.xpos.integracion.domain.exception;

/**
 * Jerarquía sellada de excepciones de dominio y seguridad de la plataforma POS.
 */
public abstract sealed class DominioException extends RuntimeException permits
        ValidacionSeguridadException,
        RecursoNoEncontradoException,
        EstadoInvalidoReversoException,
        DepuracionParticionNoPermitidaException {

    private final String codigoError;

    public DominioException(String codigoError, String message) {
        super(message);
        this.codigoError = codigoError;
    }

    public String getCodigoError() {
        return codigoError;
    }
}
