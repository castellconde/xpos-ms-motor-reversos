package mx.xpos.integracion.domain.port.in;

import mx.xpos.integracion.domain.model.EscalonRetry;
import mx.xpos.integracion.domain.model.RegistroReverso;
import mx.xpos.integracion.domain.model.TransaccionCanonica;

import java.util.List;

/**
 * Puerto de entrada (Use Case) del núcleo genérico de transacciones y reversos.
 */
public interface ProcesarReversoUseCase {

    /**
     * Encola o procesa una solicitud de reverso recibida desde la Fachada POS o desde Kafka.
     */
    RegistroReverso procesarSolicitudReverso(TransaccionCanonica transaccion, EscalonRetry escalonOrigen);

    /**
     * Drena los mensajes retenidos en la cola principal cuando el Circuit Breaker se recupera
     * y el consumidor de Kafka reanuda sus particiones (Guion de Demo Paso 3).
     */
    List<RegistroReverso> drenarPendientesPausados();

    /**
     * Devuelve la cantidad de mensajes actualmente acumulados en el topic principal mientras el consumidor está pausado.
     */
    int contarMensajesRetenidosEnColaPausada();
}
