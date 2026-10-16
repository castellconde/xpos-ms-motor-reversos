package mx.xpos.integracion.domain.exception;

/**
 * Excepción lanzada cuando el job de mantenimiento intenta hacer DETACH + DROP de una partición
 * que aún no está archivada en frío (WORM), no está replicada a reportes o cuyos conteos no coinciden.
 */
public final class DepuracionParticionNoPermitidaException extends DominioException {

    public DepuracionParticionNoPermitidaException(String nombreParticion, String motivo) {
        super("ERR-412-DEPURACION-BLOQUEADA",
                "Depuración bloqueada por seguridad para la partición [" + nombreParticion + "]: " + motivo);
    }
}
