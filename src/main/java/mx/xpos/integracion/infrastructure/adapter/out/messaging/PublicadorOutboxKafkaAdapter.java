package mx.xpos.integracion.infrastructure.adapter.out.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import mx.xpos.integracion.domain.model.EscalonRetry;
import mx.xpos.integracion.domain.model.EstadoReverso;
import mx.xpos.integracion.domain.model.TransaccionCanonica;
import mx.xpos.integracion.domain.port.out.PublicadorEventosOutboxPort;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adaptador de salida para el patrón Transactional Outbox (outbox_eventos) y el controlador
 * de pausa/reanudación de particiones del consumidor de Kafka (pagos.reverso.solicitado).
 */
@ApplicationScoped
public class PublicadorOutboxKafkaAdapter implements PublicadorEventosOutboxPort {

    private static final Logger LOG = Logger.getLogger(PublicadorOutboxKafkaAdapter.class);

    private final AtomicBoolean consumidorPrincipalPausado = new AtomicBoolean(false);
    private final Queue<TransaccionCanonica> colaPrincipalRetenida = new ConcurrentLinkedQueue<>();
    private final List<RegistroOutbox> eventosOutbox = new CopyOnWriteArrayList<>();

    @Override
    public void registrarEventoOutbox(
            TransaccionCanonica transaccion,
            EstadoReverso estado,
            EscalonRetry topicDestino,
            String motivo
    ) {
        RegistroOutbox registro = new RegistroOutbox(
                UUID.randomUUID().toString(),
                transaccion.correlationId(),
                transaccion.folio(),
                topicDestino.topicName(),
                estado.name(),
                motivo,
                Instant.now().toString()
        );
        eventosOutbox.add(registro);
        LOG.infof("[Transactional Outbox] Evento registrado para topic [%s] folio [%s] estado [%s]",
                topicDestino.topicName(), transaccion.folio(), estado);
    }

    @Override
    public void pausarConsumidorPrincipal(String motivo) {
        if (consumidorPrincipalPausado.compareAndSet(false, true)) {
            LOG.warnf("[Kafka Consumer Pause] Particiones de 'pagos.reverso.solicitado' PAUSADAS. Motivo: %s", motivo);
        }
    }

    @Override
    public void reanudarConsumidorPrincipal(String motivo) {
        if (consumidorPrincipalPausado.compareAndSet(true, false)) {
            LOG.infof("[Kafka Consumer Resume] Particiones de 'pagos.reverso.solicitado' REANUDADAS. Motivo: %s", motivo);
        }
    }

    @Override
    public boolean isConsumidorPrincipalPausado() {
        return consumidorPrincipalPausado.get();
    }

    @Override
    public void retenerEnColaPrincipalPausada(TransaccionCanonica transaccion) {
        boolean yaEnCola = colaPrincipalRetenida.stream()
                .anyMatch(t -> t.transaccionOriginalId().equals(transaccion.transaccionOriginalId())
                        && t.reversoId().equals(transaccion.reversoId()));
        if (!yaEnCola) {
            colaPrincipalRetenida.offer(transaccion);
        }
    }

    @Override
    public List<TransaccionCanonica> extraerPendientesColaPausada() {
        List<TransaccionCanonica> drenados = new ArrayList<>();
        TransaccionCanonica item;
        while ((item = colaPrincipalRetenida.poll()) != null) {
            drenados.add(item);
        }
        return drenados;
    }

    @Override
    public int contarPendientesEnColaPausada() {
        return colaPrincipalRetenida.size();
    }

    @Override
    public List<RegistroOutbox> listarEventosOutbox() {
        return List.copyOf(eventosOutbox);
    }

    public void limpiarTodoParaPruebas() {
        consumidorPrincipalPausado.set(false);
        colaPrincipalRetenida.clear();
        eventosOutbox.clear();
    }
}
