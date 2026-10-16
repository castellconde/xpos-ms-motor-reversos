package mx.xpos.integracion.infrastructure.adapter.out.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import mx.xpos.integracion.domain.model.EstadoReverso;
import mx.xpos.integracion.domain.model.RegistroReverso;
import mx.xpos.integracion.domain.port.out.RepositorioReversoPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adaptador de persistencia para la tabla reverso_transaccion (estado actual + idempotencia fuerte).
 * Garantiza unicidad global por clave compuesta (transaccion_original_id + reverso_id)
 * independientemente de la partición de fecha de la bitácora (RT-05).
 */
@ApplicationScoped
public class RepositorioReversoAdapter implements RepositorioReversoPort {

    private final Map<String, RegistroReverso> porClaveIdempotencia = new ConcurrentHashMap<>();
    private final Map<String, RegistroReverso> porReversoId = new ConcurrentHashMap<>();
    private final Map<String, RegistroReverso> porFolio = new ConcurrentHashMap<>();

    @Override
    public Optional<RegistroReverso> buscarPorClaveIdempotencia(String transaccionOriginalId, String reversoId) {
        return Optional.ofNullable(porClaveIdempotencia.get(claveCompuesta(transaccionOriginalId, reversoId)));
    }

    @Override
    public Optional<RegistroReverso> buscarPorReversoId(String reversoId) {
        return Optional.ofNullable(porReversoId.get(reversoId));
    }

    @Override
    public Optional<RegistroReverso> buscarPorFolio(String folio) {
        return Optional.ofNullable(porFolio.get(folio));
    }

    @Override
    public RegistroReverso guardarOActualizar(RegistroReverso registro) {
        porClaveIdempotencia.put(claveCompuesta(registro.transaccionOriginalId(), registro.reversoId()), registro);
        porReversoId.put(registro.reversoId(), registro);
        porFolio.put(registro.folio(), registro);
        return registro;
    }

    @Override
    public List<RegistroReverso> listarEnDlq() {
        return porReversoId.values().stream()
                .filter(r -> r.estadoActual() == EstadoReverso.DLQ_INTERVENCION_MANUAL)
                .toList();
    }

    public void limpiarTodoParaPruebas() {
        porClaveIdempotencia.clear();
        porReversoId.clear();
        porFolio.clear();
    }

    private String claveCompuesta(String transaccionOriginalId, String reversoId) {
        return transaccionOriginalId + "::" + reversoId;
    }
}
