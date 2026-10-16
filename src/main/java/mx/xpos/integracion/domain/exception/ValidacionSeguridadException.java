package mx.xpos.integracion.domain.exception;

/**
 * Excepción lanzada ante violaciones de seguridad (ej. PAN en claro detectado,
 * certificado mTLS XFCC ausente o discrepancia con el claim RFC 8705 cnf.x5t#S256).
 */
public final class ValidacionSeguridadException extends DominioException {

    public ValidacionSeguridadException(String codigoError, String message) {
        super(codigoError, message);
    }
}
