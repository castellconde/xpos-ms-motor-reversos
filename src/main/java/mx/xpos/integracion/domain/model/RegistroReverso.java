package mx.xpos.integracion.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Representa el estado actual e idempotencia de un reverso (tabla reverso_transaccion).
 * Separada de la bitácora particionada para garantizar unicidad global (RT-05).
 */
public record RegistroReverso(
        String transaccionOriginalId,
        String reversoId,
        String correlationId,
        String idempotencyKey,
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
        CorrelacionRedIso correlacionIso,
        Instant fechaCreacion,
        Instant fechaActualizacion
) {
    public RegistroReverso conTransicion(
            EstadoReverso nuevoEstado,
            EscalonRetry nuevoEscalon,
            int nuevosIntentos,
            String codigoRespuesta,
            String detalle,
            Instant instante
    ) {
        return new RegistroReverso(
                transaccionOriginalId,
                reversoId,
                correlationId,
                idempotencyKey,
                cajaId,
                folio,
                proveedorId,
                protocolo,
                monto,
                moneda,
                tarjetaEnmascarada,
                nuevoEstado,
                nuevoEscalon,
                nuevosIntentos,
                codigoRespuesta,
                detalle,
                correlacionIso,
                fechaCreacion,
                instante
        );
    }
}
