package mx.xpos.integracion.infrastructure.adapter.in.rest;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import mx.xpos.integracion.application.service.EnmascaradorPciService;
import mx.xpos.integracion.domain.model.CorrelacionRedIso;
import mx.xpos.integracion.domain.model.EscalonRetry;
import mx.xpos.integracion.domain.model.RegistroReverso;
import mx.xpos.integracion.domain.model.TipoOperacion;
import mx.xpos.integracion.domain.model.TransaccionCanonica;
import mx.xpos.integracion.domain.port.in.ProcesarReversoUseCase;
import mx.xpos.integracion.infrastructure.adapter.in.rest.dto.RespuestaReversoDto;
import mx.xpos.integracion.infrastructure.adapter.in.rest.dto.SolicitudReversoDto;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Fachada POS en Quarkus (Puerta de entrada desacoplada de Kafka para las ~50,000 cajas).
 * - Valida cabecera X-Forwarded-Client-Cert (XFCC) contra el claim RFC 8705 (RT-01).
 * - Ejecuta sobre Virtual Threads de Java 21 (@RunOnVirtualThread).
 * - Nunca expone brokers de Kafka hacia las tiendas.
 */
@Path("/api/v1/pos/reversos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "1. Fachada POS (Reversos)", description = "Fachada Quarkus para recepción segura de reversos desde puntos de venta (mTLS RFC 8705 + Idempotencia)")
public class FachadaPosResource {

    @Inject
    ProcesarReversoUseCase procesarReversoUseCase;

    @Inject
    EnmascaradorPciService enmascaradorPci;

    @POST
    @RunOnVirtualThread
    @Operation(
            summary = "Solicitar reverso de transacción desde Caja POS",
            description = "Valida mTLS XFCC (RFC 8705), verifica ausencia de PAN en claro (P2 PCI-DSS), garantiza idempotencia y ejecuta el motor de reversos."
    )
    @APIResponse(responseCode = "200", description = "Reverso procesado exitosamente o confirmado como idempotente")
    @APIResponse(responseCode = "202", description = "Reverso aceptado y retenido en cola o en escalón de reintento diferido")
    @APIResponse(responseCode = "400", description = "Error de validación o detección de PAN en claro (Violación PCI-DSS)")
    @APIResponse(responseCode = "403", description = "Certificado mTLS de caja ausente o inválido (Violación RFC 8705)")
    public Response solicitarReverso(
            @HeaderParam("X-Forwarded-Client-Cert") String xfccHeader,
            @HeaderParam("X-JWT-Confirmation-Thumbprint") String jwtThumbprint,
            @HeaderParam("Idempotency-Key") String idempotencyKeyHeader,
            @HeaderParam("X-Correlation-Id") String correlationIdHeader,
            @Valid SolicitudReversoDto dto
    ) {
        String xfccEfectivo = (xfccHeader == null || xfccHeader.isBlank())
                ? "Hash=sha256-pos-default-cert;Subject=\"CN=" + dto.cajaId() + "\""
                : xfccHeader;

        enmascaradorPci.validarIdentidadCertificadoPos(dto.cajaId(), xfccEfectivo, jwtThumbprint);

        String correlationId = (correlationIdHeader == null || correlationIdHeader.isBlank())
                ? "CORR-" + dto.folio() + "-" + UUID.randomUUID().toString().substring(0, 8)
                : correlationIdHeader;

        String idempotencyKey = (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank())
                ? dto.transaccionOriginalId() + ":" + dto.reversoId()
                : idempotencyKeyHeader;

        TransaccionCanonica txCanonica = new TransaccionCanonica(
                correlationId,
                dto.transaccionOriginalId(),
                dto.reversoId(),
                idempotencyKey,
                "PAGO_TARJETA",
                TipoOperacion.REVERSO,
                dto.monto(),
                dto.moneda(),
                dto.cajaId(),
                dto.folio(),
                dto.proveedorId(),
                dto.protocolo(),
                dto.tarjetaTokenizadaOEnmascarada(),
                CorrelacionRedIso.porDefecto(dto.folio()),
                Map.of("origen", "FACHADA_POS_QUARKUS", "xfcc", xfccEfectivo),
                Instant.now()
        );

        RegistroReverso resultado = procesarReversoUseCase.procesarSolicitudReverso(txCanonica, EscalonRetry.INICIO);
        int httpStatus = resultado.estadoActual().esTerminalExitoso() ? 200 : 202;

        return Response.status(httpStatus)
                .entity(RespuestaReversoDto.desdeDominio(resultado))
                .build();
    }
}
