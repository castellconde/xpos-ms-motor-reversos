package mx.xpos.integracion.domain.port.in;

import mx.xpos.integracion.domain.model.EstadoParticionBitacora;
import mx.xpos.integracion.domain.model.EventoBitacora;
import mx.xpos.integracion.domain.model.RegistroReverso;

import java.time.LocalDate;
import java.util.List;

/**
 * Puerto de entrada para operaciones de soporte, auditoría PCI-DSS y mantenimiento de particiones.
 */
public interface SoporteOperativoUseCase {

    /**
     * Consulta la bitácora de auditoría por folio o correlationId y verifica la cadena SHA-256.
     */
    ResultadoAuditoriaFolio consultarBitacoraConVerificacion(String folioOCorrelationId);

    /**
     * Lista todos los reversos actualmente en DLQ_INTERVENCION_MANUAL.
     */
    List<RegistroReverso> listarReversosEnDlq();

    /**
     * Reprocesa manualmente un reverso desde la DLQ (Guion de Demo Paso 4).
     */
    RegistroReverso reprocesarDesdeDlq(String reversoId, String operadorSoporte, String motivo);

    /**
     * Prepara/registra una partición histórica para pruebas o cierre mensual.
     */
    EstadoParticionBitacora registrarEstadoParticion(
            String nombreParticion,
            LocalDate fechaInicio,
            LocalDate fechaFin,
            boolean archivadoEnFrioWorm,
            boolean replicadoAReportes,
            long conteoFilasArchivadas,
            long conteoFilasReplicadas
    );

    /**
     * Ejecuta el job de depuración segura (DETACH + DROP) solo si la partición cumple
     * todas las condiciones de archivado WORM, réplica comercial y checksum (Guion de Demo Paso 5).
     */
    EstadoParticionBitacora depurarParticionVencida(String nombreParticion);

    /**
     * Lista el estado de todas las particiones de la bitácora.
     */
    List<EstadoParticionBitacora> listarParticiones();

    record ResultadoAuditoriaFolio(
            String identificadorConsultado,
            boolean cadenaHashIntegra,
            int totalEventos,
            String hashRaizFinal,
            List<EventoBitacora> eventos
    ) {}
}
