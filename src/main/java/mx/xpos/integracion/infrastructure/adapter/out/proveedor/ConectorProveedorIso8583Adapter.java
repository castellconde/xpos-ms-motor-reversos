package mx.xpos.integracion.infrastructure.adapter.out.proveedor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import mx.xpos.integracion.domain.model.CorrelacionRedIso;
import mx.xpos.integracion.domain.model.ProtocoloProveedor;
import mx.xpos.integracion.domain.model.ResultadoCanonico;
import mx.xpos.integracion.domain.model.TransaccionCanonica;
import mx.xpos.integracion.domain.port.out.ConectorProveedorPort;

/**
 * Adaptador hacia el Gateway ISO 8583 compartido (jPOS).
 * Construye tramas 0420/0421 garantizando la preservación de DE 90 (Original Data Elements:
 * MTI original + STAN DE 11 + Fecha transmisión DE 7 + Adquirente DE 32) entre pods (RT-07).
 */
@ApplicationScoped
public class ConectorProveedorIso8583Adapter implements ConectorProveedorPort {

    @Inject
    SimuladorProveedorControl simulador;

    @Override
    public ProtocoloProveedor protocoloSoportado() {
        return ProtocoloProveedor.ISO_8583;
    }

    @Override
    public ResultadoCanonico ejecutarTransaccion(TransaccionCanonica transaccion) {
        int numLlamada = simulador.registrarLlamada();
        long latencia = simulador.getLatenciaSimuladaMs();
        CorrelacionRedIso iso = transaccion.correlacionIso();
        String de90 = iso.mtiOriginal() + iso.stanOriginalDe11() + iso.fechaTransmisionDe7() + iso.adquirenteDe32();

        return switch (simulador.getModoActual()) {
            case ARRIBA_EXITOSO -> new ResultadoCanonico.Exito(
                    "ISO-39-00",
                    "RRN-" + iso.rrnDe37(),
                    "MTI 0430 aprobado (DE39=00) en canal jPOS con DE90=" + de90 + " (intento #" + numLlamada + ")",
                    latencia
            );
            case YA_REVERSADO_IDEMPOTENTE -> new ResultadoCanonico.YaAplicado(
                    "ISO-39-94",
                    "MTI 0430 DE39=94: Reverso ISO 8583 ya aplicado previamente para DE90=" + de90,
                    latencia
            );
            case APAGADO_SIN_CONEXION, ERROR_503_REINTENTABLE -> new ResultadoCanonico.ErrorReintentable(
                    "ISO-39-91",
                    "MTI 0430 DE39=91: Switch emisor inoperativo o canal QMUX desconectado",
                    latencia
            );
            case TIMEOUT_ESTADO_DESCONOCIDO -> new ResultadoCanonico.EstadoDesconocido(
                    "ISO-39-68",
                    "Timeout en QMUX jPOS sin respuesta MTI 0430 para DE90=" + de90,
                    Math.max(latencia, 3000L)
            );
            case ERROR_NEGOCIO_422 -> new ResultadoCanonico.ErrorNegocio(
                    "ISO-39-12",
                    "TRANSACCION_INVALIDA_DE39_12",
                    "Rechazo definitivo ISO 8583 (DE39=12) para DE90=" + de90,
                    latencia
            );
        };
    }
}
