package mx.xpos.integracion.domain.port.out;

import mx.xpos.integracion.domain.model.EstadoParticionBitacora;
import mx.xpos.integracion.domain.model.EventoBitacora;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida para la bitácora de auditoría append-only particionada (tabla bitacora_auditoria)
 * y su tabla de control de particiones (control_particion_bitacora).
 * El usuario de aplicación solo realiza INSERT sobre la bitácora.
 */
public interface RepositorioBitacoraPort {

    EventoBitacora insertarEventoAppendOnly(EventoBitacora evento);

    Optional<String> obtenerUltimoHashPorCorrelationId(String correlationId);

    List<EventoBitacora> buscarPorFolioOCorrelationId(String identificador);

    long contarFilasEnParticion(String nombreParticion);

    String calcularHashCierreParticion(String nombreParticion);

    EstadoParticionBitacora guardarEstadoParticion(EstadoParticionBitacora estadoParticion);

    Optional<EstadoParticionBitacora> buscarParticion(String nombreParticion);

    List<EstadoParticionBitacora> listarParticiones();
}
