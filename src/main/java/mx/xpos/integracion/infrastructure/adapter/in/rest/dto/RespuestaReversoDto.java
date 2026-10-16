package mx.xpos.integracion.infrastructure.adapter.in.rest.dto;

import mx.xpos.integracion.domain.model.EscalonRetry;
import mx.xpos.integracion.domain.model.EstadoReverso;
import mx.xpos.integracion.domain.model.ProtocoloProveedor;
import mx.xpos.integracion.domain.model.RegistroReverso;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO de respuesta de operación de reverso hacia la caja POS o consola de soporte.
 */
public record RespuestaReversoDto(
        String correlationId,
        String transaccionOriginalId,
        String reversoId,
        String cajaId,
        String folio,
        String proveedorId,
        ProtocoloProveedor protocolo,
        BigDecimal monto,
        String moneda,
        String tarjetaEnmascarada,
        EstadoReverso estadoActual,
        EscalonRetry escalonActual,
        int intentosTotales,
        String ultimoCodigoRespuesta,
        String ultimoDetalle,
        String stanIsoDe11,
        String rrnIsoDe37,
        Instant fechaActualizacion
) {
    public static RespuestaReversoDto desdeDominio(RegistroReverso r) {
        return new RespuestaReversoDto(
                r.correlationId(),
                r.transaccionOriginalId(),
                r.reversoId(),
                r.cajaId(),
                r.folio(),
                r.proveedorId(),
                r.protocolo(),
                r.monto(),
                r.moneda(),
                r.tarjetaEnmascarada(),
                r.estadoActual(),
                r.escalonActual(),
                r.intentosTotales(),
                r.ultimoCodigoRespuesta(),
                r.ultimoDetalle(),
                r.correlacionIso().stanOriginalDe11(),
                r.correlacionIso().rrnDe37(),
                r.fechaActualizacion()
        );
    }
}
