package mx.xpos.integracion.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Modelo canónico de transacción del motor genérico en ARO.
 * Garantiza inmutabilidad (Java 21 record) y ausencia de datos de tarjeta en claro (P2 PCI-DSS).
 */
public record TransaccionCanonica(
        String correlationId,
        String transaccionOriginalId,
        String reversoId,
        String idempotencyKey,
        String tipoTransaccion,
        TipoOperacion operacion,
        BigDecimal monto,
        String moneda,
        String cajaId,
        String folio,
        String proveedorId,
        ProtocoloProveedor protocolo,
        String tarjetaTokenizadaOEnmascarada,
        CorrelacionRedIso correlacionIso,
        Map<String, String> metadatos,
        Instant fechaCreacion
) {
    public TransaccionCanonica {
        Objects.requireNonNull(correlationId, "correlationId es obligatorio");
        Objects.requireNonNull(transaccionOriginalId, "transaccionOriginalId es obligatorio");
        Objects.requireNonNull(reversoId, "reversoId es obligatorio");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey es obligatorio");
        Objects.requireNonNull(tipoTransaccion, "tipoTransaccion es obligatorio");
        Objects.requireNonNull(operacion, "operacion es obligatoria");
        Objects.requireNonNull(monto, "monto es obligatorio");
        Objects.requireNonNull(moneda, "moneda es obligatoria");
        Objects.requireNonNull(cajaId, "cajaId es obligatorio");
        Objects.requireNonNull(folio, "folio es obligatorio");
        Objects.requireNonNull(proveedorId, "proveedorId es obligatorio");
        Objects.requireNonNull(protocolo, "protocolo es obligatorio");
        Objects.requireNonNull(tarjetaTokenizadaOEnmascarada, "tarjetaTokenizadaOEnmascarada es obligatoria");
        if (monto.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("El monto de la transacción canónica debe ser mayor a cero");
        }
        correlacionIso = correlacionIso != null ? correlacionIso : CorrelacionRedIso.porDefecto(folio);
        metadatos = metadatos != null ? Map.copyOf(metadatos) : Map.of();
        fechaCreacion = fechaCreacion != null ? fechaCreacion : Instant.now();
    }
}
