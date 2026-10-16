package mx.xpos.integracion.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Evento inmutable de la bitácora de auditoría append-only (tabla bitacora_auditoria).
 * Incluye hash encadenado SHA-256 (prev_hash -> curr_hash) por agregado (correlationId/folio)
 * para evidenciar cualquier alteración sin bloquear globalmente las inserciones (RT-04).
 */
public record EventoBitacora(
        Long id,
        Instant fechaEvento,
        String nombreParticion,
        String correlationId,
        String transaccionOriginalId,
        String reversoId,
        String cajaId,
        String folio,
        String tipoTransaccion,
        String proveedorId,
        ProtocoloProveedor protocolo,
        EstadoReverso estadoTransicion,
        int numeroIntento,
        String codigoRespuesta,
        long latenciaMs,
        String tarjetaEnmascarada,
        String detalleEnmascarado,
        String prevHashSha256,
        String currHashSha256
) {
    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    public static EventoBitacora crearConHashEncadenado(
            Instant fechaEvento,
            String nombreParticion,
            TransaccionCanonica tx,
            EstadoReverso estadoTransicion,
            int numeroIntento,
            String codigoRespuesta,
            long latenciaMs,
            String detalleEnmascarado,
            String prevHash
    ) {
        String hashAnterior = (prevHash == null || prevHash.isBlank()) ? GENESIS_HASH : prevHash;
        String currHash = calcularHashSha256(
                hashAnterior,
                fechaEvento,
                tx.correlationId(),
                tx.folio(),
                estadoTransicion.name(),
                numeroIntento,
                codigoRespuesta,
                tx.tarjetaTokenizadaOEnmascarada()
        );
        return new EventoBitacora(
                null,
                fechaEvento,
                nombreParticion,
                tx.correlationId(),
                tx.transaccionOriginalId(),
                tx.reversoId(),
                tx.cajaId(),
                tx.folio(),
                tx.tipoTransaccion(),
                tx.proveedorId(),
                tx.protocolo(),
                estadoTransicion,
                numeroIntento,
                codigoRespuesta,
                latenciaMs,
                tx.tarjetaTokenizadaOEnmascarada(),
                detalleEnmascarado,
                hashAnterior,
                currHash
        );
    }

    public static String calcularHashSha256(
            String prevHash,
            Instant fechaEvento,
            String correlationId,
            String folio,
            String estado,
            int numeroIntento,
            String codigoRespuesta,
            String tarjetaEnmascarada
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.join("|",
                    Objects.toString(prevHash, GENESIS_HASH),
                    String.valueOf(fechaEvento.toEpochMilli()),
                    Objects.toString(correlationId, ""),
                    Objects.toString(folio, ""),
                    Objects.toString(estado, ""),
                    String.valueOf(numeroIntento),
                    Objects.toString(codigoRespuesta, ""),
                    Objects.toString(tarjetaEnmascarada, "")
            );
            byte[] hashBytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo SHA-256 no disponible en el entorno JVM", e);
        }
    }

    public boolean verificarIntegridadEslabon(String hashAnteriorEsperado) {
        if (!Objects.equals(this.prevHashSha256, hashAnteriorEsperado)) {
            return false;
        }
        String recalculado = calcularHashSha256(
                this.prevHashSha256,
                this.fechaEvento,
                this.correlationId,
                this.folio,
                this.estadoTransicion.name(),
                this.numeroIntento,
                this.codigoRespuesta,
                this.tarjetaEnmascarada
        );
        return Objects.equals(this.currHashSha256, recalculado);
    }
}
