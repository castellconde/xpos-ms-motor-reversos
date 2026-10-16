package mx.xpos.integracion.infrastructure.adapter.in.rest;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import mx.xpos.integracion.domain.exception.DepuracionParticionNoPermitidaException;
import mx.xpos.integracion.domain.exception.DominioException;
import mx.xpos.integracion.domain.exception.EstadoInvalidoReversoException;
import mx.xpos.integracion.domain.exception.RecursoNoEncontradoException;
import mx.xpos.integracion.domain.exception.ValidacionSeguridadException;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Manejador centralizado de errores (Secure Error Handling - Hack23 ISMS Policy).
 * - Registra eventos de seguridad con identificador de trazabilidad.
 * - NUNCA expone stack traces ni detalles internos de infraestructura al cliente.
 * - Utiliza Pattern Matching de Java 21 sobre la jerarquía sellada DominioException.
 */
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    public record ErrorApiResponse(
            String errorId,
            String codigoError,
            String mensaje,
            List<String> detallesValidacion,
            Instant timestamp
    ) {}

    @Override
    public Response toResponse(Throwable exception) {
        String errorId = UUID.randomUUID().toString();

        if (exception instanceof DominioException domEx) {
            return switch (domEx) {
                case ValidacionSeguridadException secEx -> {
                    LOG.warnf("[ALERTA SEGURIDAD %s] Código=%s Motivo=%s", errorId, secEx.getCodigoError(), secEx.getMessage());
                    int status = secEx.getCodigoError().startsWith("SEC-MTLS") ? 403 : 400;
                    yield construirRespuesta(status, errorId, secEx.getCodigoError(), secEx.getMessage(), List.of());
                }
                case RecursoNoEncontradoException notFoundEx -> construirRespuesta(
                        404, errorId, notFoundEx.getCodigoError(), notFoundEx.getMessage(), List.of()
                );
                case EstadoInvalidoReversoException conflictEx -> construirRespuesta(
                        409, errorId, conflictEx.getCodigoError(), conflictEx.getMessage(), List.of()
                );
                case DepuracionParticionNoPermitidaException preloadEx -> {
                    LOG.warnf("[SALVAGUARDA BITÁCORA %s] %s", errorId, preloadEx.getMessage());
                    yield construirRespuesta(
                            412, errorId, preloadEx.getCodigoError(), preloadEx.getMessage(), List.of()
                    );
                }
            };
        }

        if (exception instanceof ConstraintViolationException cve) {
            List<String> violaciones = cve.getConstraintViolations().stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .toList();
            return construirRespuesta(
                    400, errorId, "ERR-400-VALIDACION-ENTRADA",
                    "La solicitud no cumple con las reglas de validación de entrada", violaciones
            );
        }

        LOG.errorf(exception, "[ERROR INTERNO %s] Excepción no controlada", errorId);
        return construirRespuesta(
                500, errorId, "ERR-500-INTERNO",
                "Ocurrió un error interno procesando la operación. Consulte a soporte con el folio de error.",
                List.of()
        );
    }

    private Response construirRespuesta(
            int httpStatus,
            String errorId,
            String codigoError,
            String mensaje,
            List<String> detalles
    ) {
        return Response.status(httpStatus)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorApiResponse(errorId, codigoError, mensaje, detalles, Instant.now()))
                .build();
    }
}
