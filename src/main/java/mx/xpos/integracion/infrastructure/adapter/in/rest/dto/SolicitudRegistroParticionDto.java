package mx.xpos.integracion.infrastructure.adapter.in.rest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * DTO para registrar el estado de una partición en la tabla de control antes de ejecutar el job de depuración.
 */
public record SolicitudRegistroParticionDto(
        @NotBlank(message = "nombreParticion es obligatorio")
        @Pattern(regexp = "^bitacora_auditoria_y[0-9]{4}m[0-9]{2}$", message = "Nombre de partición debe seguir formato bitacora_auditoria_yYYYYmMM")
        String nombreParticion,

        @NotNull(message = "fechaInicio es obligatoria")
        LocalDate fechaInicio,

        @NotNull(message = "fechaFin es obligatoria")
        LocalDate fechaFin,

        boolean archivadoEnFrioWorm,
        boolean replicadoAReportes,

        @Min(0)
        long conteoFilasArchivadas,

        @Min(0)
        long conteoFilasReplicadas
) {}
