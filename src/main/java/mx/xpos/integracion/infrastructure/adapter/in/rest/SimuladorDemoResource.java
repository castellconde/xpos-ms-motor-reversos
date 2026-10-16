package mx.xpos.integracion.infrastructure.adapter.in.rest;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import mx.xpos.integracion.application.service.MotorReversosService;
import mx.xpos.integracion.domain.port.in.ProcesarReversoUseCase;
import mx.xpos.integracion.domain.port.out.PublicadorEventosOutboxPort;
import mx.xpos.integracion.infrastructure.adapter.in.rest.dto.RespuestaReversoDto;
import mx.xpos.integracion.infrastructure.adapter.in.rest.dto.SolicitudControlSimuladorDto;
import mx.xpos.integracion.infrastructure.adapter.out.proveedor.SimuladorProveedorControl;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Endpoint de control del Proveedor Simulado y del estado del Consumidor Kafka / Circuit Breaker
 * para ejecutar el Guion de Demo de 6 pasos (Líneas 269-280).
 */
@Path("/api/v1/simulador")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "3. Control del Simulador y Demo (Op1 / Op3)", description = "Endpoints de control para apagar/prender el proveedor simulado, inyectar errores y drenar la cola pausada")
public class SimuladorDemoResource {

    @Inject
    SimuladorProveedorControl simuladorProveedor;

    @Inject
    MotorReversosService motorReversosService;

    @Inject
    ProcesarReversoUseCase procesarReversoUseCase;

    @Inject
    PublicadorEventosOutboxPort publicadorOutbox;

    @ConfigProperty(name = "xpos.despliegue.perfil", defaultValue = "Op3-Hibrido-AzureFlexiblePG")
    String perfilDespliegueActivo;

    public record EstadoPlataformaDemoDto(
            String perfilDespliegue,
            SimuladorProveedorControl.ModoProveedor modoProveedor,
            long latenciaProveedorMs,
            int llamadasTotalesAlProveedor,
            String estadoCircuitBreaker,
            boolean consumidorKafkaPausado,
            int mensajesRetenidosEnColaPausada
    ) {}

    @GET
    @Path("/estado")
    @RunOnVirtualThread
    @Operation(summary = "Consultar estado del proveedor simulado, Circuit Breaker, pausa de Kafka y perfil activo (Op1/Op3)")
    public EstadoPlataformaDemoDto consultarEstado() {
        return new EstadoPlataformaDemoDto(
                perfilDespliegueActivo,
                simuladorProveedor.getModoActual(),
                simuladorProveedor.getLatenciaSimuladaMs(),
                simuladorProveedor.getLlamadasRecibidas(),
                motorReversosService.obtenerEstadoCircuitBreaker(),
                publicadorOutbox.isConsumidorPrincipalPausado(),
                procesarReversoUseCase.contarMensajesRetenidosEnColaPausada()
        );
    }

    @POST
    @Path("/proveedor/modo")
    @RunOnVirtualThread
    @Operation(
            summary = "Cambiar el modo del proveedor simulado (Arriba, Apagado, Timeout, Error 422, etc.)",
            description = "Si el proveedor vuelve a ARRIBA_EXITOSO, permite probar la transición del Circuit Breaker y el drenado de mensajes pendientes."
    )
    public EstadoPlataformaDemoDto cambiarModoProveedor(@Valid SolicitudControlSimuladorDto dto) {
        simuladorProveedor.cambiarModo(dto.modo(), dto.latenciaMs());
        return consultarEstado();
    }

    @POST
    @Path("/cola-pausada/drenar")
    @RunOnVirtualThread
    @Operation(
            summary = "Reanudar consumidor y drenar los reversos acumulados durante la caída del proveedor (Guion Demo Paso 3)",
            description = "Transiciona el Circuit Breaker de OPEN a HALF_OPEN/CLOSED, reanuda el consumidor de Kafka y procesa todos los reversos acumulados sin duplicados."
    )
    public List<RespuestaReversoDto> drenarColaPausada() {
        return procesarReversoUseCase.drenarPendientesPausados().stream()
                .map(RespuestaReversoDto::desdeDominio)
                .toList();
    }
}
