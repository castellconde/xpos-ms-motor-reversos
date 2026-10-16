package mx.xpos.integracion.domain.model;

import java.util.Optional;

/**
 * Escalones de espera diferida en Kafka para reintentos asincrónicos y DLQ.
 */
public enum EscalonRetry {
    INICIO("pagos.reverso.solicitado", 0),
    RETRY_1M("reverso.retry.1m", 1),
    RETRY_5M("reverso.retry.5m", 2),
    RETRY_30M("reverso.retry.30m", 3),
    DLQ("reverso.dlq", 4);

    private final String topicName;
    private final int nivel;

    EscalonRetry(String topicName, int nivel) {
        this.topicName = topicName;
        this.nivel = nivel;
    }

    public String topicName() {
        return topicName;
    }

    public int nivel() {
        return nivel;
    }

    public Optional<EscalonRetry> siguienteEscalonRetry() {
        return switch (this) {
            case INICIO -> Optional.of(RETRY_1M);
            case RETRY_1M -> Optional.of(RETRY_5M);
            case RETRY_5M -> Optional.of(RETRY_30M);
            case RETRY_30M, DLQ -> Optional.empty();
        };
    }
}
