package mx.xpos.integracion.infrastructure.adapter.in.rest;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import mx.xpos.integracion.domain.model.EstadoParticionBitacora;
import mx.xpos.integracion.domain.model.RegistroReverso;
import mx.xpos.integracion.domain.port.in.SoporteOperativoUseCase;
import mx.xpos.integracion.domain.port.out.PublicadorEventosOutboxPort;
import mx.xpos.integracion.infrastructure.adapter.in.rest.dto.RespuestaReversoDto;
import mx.xpos.integracion.infrastructure.adapter.in.rest.dto.SolicitudRegistroParticionDto;
import mx.xpos.integracion.infrastructure.adapter.in.rest.dto.SolicitudReprocesoDlqDto;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Endpoints de Soporte Operativo, Auditoría de Bitácora (con verificación de cadena de hashes SHA-256),
 * Reprocesamiento de DLQ y Job de Mantenimiento / Depuración de Particiones (DETACH + DROP).
 */
@Path("/api/v1/soporte")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "2. Soporte, Bitácora PCI y Particiones", description = "Consultas de bitácora con verificación criptográfica SHA-256, reproceso desde DLQ y depuración segura de particiones")
public class SoporteOperativoResource {

    @Inject
    SoporteOperativoUseCase soporteUseCase;

    @Inject
    PublicadorEventosOutboxPort publicadorOutbox;

    @GET
    @Path("/bitacora/{identificador}")
    @RunOnVirtualThread
    @Operation(
            summary = "Consultar bitácora por Folio, ReversoId o CorrelationId",
            description = "Devuelve todas las transiciones de estado append-only y verifica criptográficamente la cadena de hashes SHA-256 (prev_hash -> curr_hash)."
    )
    @APIResponse(responseCode = "200", description = "Bitácora encontrada y verificada")
    @APIResponse(responseCode = "404", description = "No existen registros para el identificador")
    public SoporteOperativoUseCase.ResultadoAuditoriaFolio consultarBitacora(
            @PathParam("identificador") String identificador
    ) {
        return soporteUseCase.consultarBitacoraConVerificacion(identificador);
    }

    @GET
    @Path("/dlq")
    @RunOnVirtualThread
    @Operation(summary = "Listar reversos en DLQ pendientes de intervención manual")
    public List<RespuestaReversoDto> listarDlq() {
        return soporteUseCase.listarReversosEnDlq().stream()
                .map(RespuestaReversoDto::desdeDominio)
                .toList();
    }

    @POST
    @Path("/dlq/{reversoId}/reprocesar")
    @RunOnVirtualThread
    @Operation(
            summary = "Reprocesar manualmente un reverso desde la DLQ (Guion Demo Paso 4)",
            description = "Permite a un operador con rol de soporte reenviar una transacción en DLQ una vez corregida la causa en el proveedor."
    )
    @APIResponse(responseCode = "200", description = "Reverso reprocesado")
    @APIResponse(responseCode = "409", description = "El reverso no está en estado DLQ")
    public RespuestaReversoDto reprocesarDesdeDlq(
            @PathParam("reversoId") String reversoId,
            @Valid SolicitudReprocesoDlqDto solicitud
    ) {
        RegistroReverso reprocesado = soporteUseCase.reprocesarDesdeDlq(
                reversoId, solicitud.operadorSoporte(), solicitud.motivo()
        );
        return RespuestaReversoDto.desdeDominio(reprocesado);
    }

    @POST
    @Path("/particiones")
    @RunOnVirtualThread
    @Operation(
            summary = "Registrar/actualizar estado de control de una partición de bitácora",
            description = "Registra el estado de archivado frío Parquet (WORM) y confirmación de réplica a reportes comerciales en control_particion_bitacora."
    )
    public EstadoParticionBitacora registrarEstadoParticion(@Valid SolicitudRegistroParticionDto dto) {
        return soporteUseCase.registrarEstadoParticion(
                dto.nombreParticion(),
                dto.fechaInicio(),
                dto.fechaFin(),
                dto.archivadoEnFrioWorm(),
                dto.replicadoAReportes(),
                dto.conteoFilasArchivadas(),
                dto.conteoFilasReplicadas()
        );
    }

    @POST
    @Path("/particiones/{nombreParticion}/depurar")
    @RunOnVirtualThread
    @Operation(
            summary = "Ejecutar job de depuración segura DETACH + DROP sobre una partición (Guion Demo Paso 5)",
            description = "Verifica que la partición esté archivada en Blob Storage WORM, replicada a reportes y con conteos y checksum SHA-256 idénticos antes de permitir el DETACH + DROP."
    )
    @APIResponse(responseCode = "200", description = "Partición depurada exitosamente (DETACH + DROP)")
    @APIResponse(responseCode = "412", description = "Depuración bloqueada por incumplimiento de condiciones de seguridad")
    public EstadoParticionBitacora depurarParticion(@PathParam("nombreParticion") String nombreParticion) {
        return soporteUseCase.depurarParticionVencida(nombreParticion);
    }

    @GET
    @Path("/particiones")
    @RunOnVirtualThread
    @Operation(summary = "Listar estado de todas las particiones en la tabla de control")
    public List<EstadoParticionBitacora> listarParticiones() {
        return soporteUseCase.listarParticiones();
    }

    @GET
    @Path("/outbox")
    @RunOnVirtualThread
    @Operation(summary = "Inspeccionar eventos publicados en la tabla Transactional Outbox")
    public List<PublicadorEventosOutboxPort.RegistroOutbox> listarOutbox() {
        return publicadorOutbox.listarEventosOutbox();
    }
}
