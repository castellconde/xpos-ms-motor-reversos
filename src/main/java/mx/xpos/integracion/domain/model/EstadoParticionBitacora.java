package mx.xpos.integracion.domain.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Representa el registro de control de una partición de bitácora (tabla control_particion_bitacora).
 * El job de depuración SOLO puede ejecutar DETACH + DROP cuando:
 * 1) archivadoEnFrioWorm == true
 * 2) replicadoAReportes == true
 * 3) conteoFilasParticion == conteoFilasArchivadas && conteoFilasParticion == conteoFilasReplicadas
 * 4) hashCierreSha256 coincide con el manifiesto frío.
 */
public record EstadoParticionBitacora(
        String nombreParticion,
        LocalDate fechaInicioRango,
        LocalDate fechaFinRango,
        long conteoFilasParticion,
        long conteoFilasArchivadas,
        long conteoFilasReplicadas,
        String hashCierreSha256,
        String rutaBlobParquetWorm,
        boolean archivadoEnFrioWorm,
        boolean replicadoAReportes,
        boolean depurada,
        Instant fechaUltimaVerificacion
) {
    public boolean cumpleCondicionesDepuracionSegura() {
        return !depurada
                && archivadoEnFrioWorm
                && replicadoAReportes
                && conteoFilasParticion == conteoFilasArchivadas
                && conteoFilasParticion == conteoFilasReplicadas
                && hashCierreSha256 != null
                && !hashCierreSha256.isBlank()
                && rutaBlobParquetWorm != null
                && !rutaBlobParquetWorm.isBlank();
    }
}
