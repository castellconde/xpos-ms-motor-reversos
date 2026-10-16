package mx.xpos.integracion.domain.exception;

/**
 * Excepción cuando un folio, reversoId o partición solicitada no existe.
 */
public final class RecursoNoEncontradoException extends DominioException {

    public RecursoNoEncontradoException(String recurso, String identificador) {
        super("ERR-404-NO-ENCONTRADO", "El recurso solicitado no existe: " + recurso + " [" + identificador + "]");
    }
}
