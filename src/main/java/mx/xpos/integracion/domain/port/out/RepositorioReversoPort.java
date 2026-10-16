package mx.xpos.integracion.domain.port.out;

import mx.xpos.integracion.domain.model.RegistroReverso;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida para persistencia de estado e idempotencia (tabla reverso_transaccion).
 */
public interface RepositorioReversoPort {

    Optional<RegistroReverso> buscarPorClaveIdempotencia(String transaccionOriginalId, String reversoId);

    Optional<RegistroReverso> buscarPorReversoId(String reversoId);

    Optional<RegistroReverso> buscarPorFolio(String folio);

    RegistroReverso guardarOActualizar(RegistroReverso registro);

    List<RegistroReverso> listarEnDlq();
}
