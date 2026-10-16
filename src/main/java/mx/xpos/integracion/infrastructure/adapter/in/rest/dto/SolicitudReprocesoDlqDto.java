package mx.xpos.integracion.infrastructure.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO para solicitar el reproceso controlado de un reverso desde la DLQ.
 */
public record SolicitudReprocesoDlqDto(
        @NotBlank(message = "operadorSoporte es obligatorio")
        @Pattern(regexp = "^[A-Za-z0-9._@-]{4,64}$", message = "Identificador de operador inválido")
        String operadorSoporte,

        @NotBlank(message = "motivo es obligatorio")
        @Size(min = 5, max = 250, message = "El motivo debe tener entre 5 y 250 caracteres")
        String motivo
) {}
