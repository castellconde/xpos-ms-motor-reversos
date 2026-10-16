package mx.xpos.integracion.domain.model;

import java.util.Objects;

/**
 * Bloque inmutable de correlación de red ISO 8583 (DE 90 - Original Data Elements)
 * requerido para garantizar que reintentos de reverso (0420/0421) desde cualquier pod
 * reconstruyan exactamente los campos originales esperados por el switch autorizador (RT-07).
 */
public record CorrelacionRedIso(
        String mtiOriginal,
        String stanOriginalDe11,
        String fechaTransmisionDe7,
        String rrnDe37,
        String adquirenteDe32
) {
    public CorrelacionRedIso {
        Objects.requireNonNull(mtiOriginal, "mtiOriginal no puede ser nulo");
        Objects.requireNonNull(stanOriginalDe11, "stanOriginalDe11 no puede ser nulo");
        Objects.requireNonNull(fechaTransmisionDe7, "fechaTransmisionDe7 no puede ser nulo");
        Objects.requireNonNull(rrnDe37, "rrnDe37 no puede ser nulo");
        Objects.requireNonNull(adquirenteDe32, "adquirenteDe32 no puede ser nulo");
    }

    public static CorrelacionRedIso porDefecto(String folio) {
        String sufijo = folio.replaceAll("\\D", "");
        String stan = String.format("%06d", sufijo.isEmpty() ? 1 : Integer.parseInt(sufijo.substring(Math.max(0, sufijo.length() - 6))));
        return new CorrelacionRedIso("0200", stan, "1016165500", "628916" + stan, "400001");
    }
}
