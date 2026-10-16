package mx.xpos.integracion.infrastructure.adapter.in.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import mx.xpos.integracion.infrastructure.adapter.out.proveedor.SimuladorProveedorControl;

/**
 * DTO para controlar el estado del proveedor simulado durante el Guion de Demo.
 */
public record SolicitudControlSimuladorDto(
        @NotNull(message = "modo es obligatorio")
        SimuladorProveedorControl.ModoProveedor modo,

        @Min(value = 0, message = "La latencia no puede ser negativa")
        @Max(value = 30000, message = "La latencia máxima simulada es 30000 ms")
        long latenciaMs
) {}
