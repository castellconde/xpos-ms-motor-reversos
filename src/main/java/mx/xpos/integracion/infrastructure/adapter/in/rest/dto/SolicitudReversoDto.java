package mx.xpos.integracion.infrastructure.adapter.in.rest.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import mx.xpos.integracion.domain.model.ProtocoloProveedor;

import java.math.BigDecimal;

/**
 * DTO de entrada con validación por lista blanca (Allowlist Validation) conforme a la política ISMS.
 */
public record SolicitudReversoDto(
        @NotBlank(message = "transaccionOriginalId es obligatorio")
        @Pattern(regexp = "^[A-Za-z0-9_-]{4,64}$", message = "transaccionOriginalId contiene caracteres no permitidos")
        String transaccionOriginalId,

        @NotBlank(message = "reversoId es obligatorio")
        @Pattern(regexp = "^[A-Za-z0-9_-]{4,64}$", message = "reversoId contiene caracteres no permitidos")
        String reversoId,

        @NotBlank(message = "cajaId es obligatorio")
        @Pattern(regexp = "^POS-[0-9]{4,6}$", message = "cajaId debe tener formato POS-XXXX")
        String cajaId,

        @NotBlank(message = "folio es obligatorio")
        @Pattern(regexp = "^FOL-[A-Za-z0-9_-]{4,32}$", message = "folio debe tener formato FOL-XXXX")
        String folio,

        @NotBlank(message = "proveedorId es obligatorio")
        @Pattern(regexp = "^[A-Z0-9_-]{3,32}$", message = "proveedorId inválido")
        String proveedorId,

        @NotNull(message = "protocolo es obligatorio (REST, SOAP, ISO_8583)")
        ProtocoloProveedor protocolo,

        @NotNull(message = "monto es obligatorio")
        @DecimalMin(value = "0.01", message = "El monto mínimo es 0.01")
        @DecimalMax(value = "999999.99", message = "El monto excede el límite permitido por operación")
        BigDecimal monto,

        @NotBlank(message = "moneda es obligatoria")
        @Pattern(regexp = "^(MXN|USD)$", message = "Moneda solo permite MXN o USD")
        String moneda,

        @NotBlank(message = "tarjetaTokenizadaOEnmascarada es obligatoria")
        String tarjetaTokenizadaOEnmascarada
) {}
