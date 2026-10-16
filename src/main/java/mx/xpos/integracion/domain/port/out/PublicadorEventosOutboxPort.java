package mx.xpos.integracion.domain.port.out;

import mx.xpos.integracion.domain.model.EscalonRetry;
import mx.xpos.integracion.domain.model.EstadoReverso;
import mx.xpos.integracion.domain.model.TransaccionCanonica;

import java.util.List;

/**
 * Puerto de salida para el patrón Transactional Outbox y control de pausa/reanudación
 * de consumidores Kafka cuando el Circuit Breaker se abre o se cierra (RT-02).
 */
public interface PublicadorEventosOutboxPort {

    void registrarEventoOutbox(
            TransaccionCanonica transaccion,
            EstadoReverso estado,
            EscalonRetry topicDestino,
            String motivo
    );

    void pausarConsumidorPrincipal(String motivo);

    void reanudarConsumidorPrincipal(String motivo);

    boolean isConsumidorPrincipalPausado();

    void retenerEnColaPrincipalPausada(TransaccionCanonica transaccion);

    List<TransaccionCanonica> extraerPendientesColaPausada();

    int contarPendientesEnColaPausada();

    List<RegistroOutbox> listarEventosOutbox();

    record RegistroOutbox(
            String id,
            String correlationId,
            String folio,
            String topicDestino,
            String estadoEvento,
            String motivo,
            String timestampIso
    ) {}
}
