package mx.xpos.integracion.domain.model;

/**
 * Resultado canónico devuelto por cualquier adaptador de proveedor externo.
 * Implementado como sealed interface de Java 21 LTS para garantizar exhaustividad
 * en pattern matching switch dentro del núcleo del motor de transacciones.
 */
public sealed interface ResultadoCanonico permits
        ResultadoCanonico.Exito,
        ResultadoCanonico.YaAplicado,
        ResultadoCanonico.ErrorReintentable,
        ResultadoCanonico.EstadoDesconocido,
        ResultadoCanonico.ErrorNegocio {

    String codigoProveedor();
    String detalle();
    long latenciaMs();

    /**
     * Clase 1: Éxito confirmado por el proveedor.
     */
    record Exito(
            String codigoProveedor,
            String autorizacionProveedor,
            String detalle,
            long latenciaMs
    ) implements ResultadoCanonico {}

    /**
     * Clase 2: La operación (ej. reverso) ya había sido aplicada previamente;
     * cuenta como éxito idempotente sin reprocesar.
     */
    record YaAplicado(
            String codigoProveedor,
            String detalle,
            long latenciaMs
    ) implements ResultadoCanonico {}

    /**
     * Clase 3: Error técnico/transitorio (5xx, falla de socket antes de enviar, rate limit).
     * Dispara reintento inmediato o diferido.
     */
    record ErrorReintentable(
            String codigoProveedor,
            String detalle,
            long latenciaMs
    ) implements ResultadoCanonico {}

    /**
     * Clase 4: Estado desconocido (timeout de lectura después de haber transmitido el request).
     * NUNCA dispara reenvío ciego; exige consulta previa o reverso idempotente (0420/0421).
     */
    record EstadoDesconocido(
            String codigoProveedor,
            String detalle,
            long latenciaMs
    ) implements ResultadoCanonico {}

    /**
     * Clase 5: Error definitivo de negocio o formato rechazado por el autorizador.
     * No se reintenta automáticamente; pasa directo a DLQ para revisión de soporte.
     */
    record ErrorNegocio(
            String codigoProveedor,
            String motivoRechazo,
            String detalle,
            long latenciaMs
    ) implements ResultadoCanonico {}
}
