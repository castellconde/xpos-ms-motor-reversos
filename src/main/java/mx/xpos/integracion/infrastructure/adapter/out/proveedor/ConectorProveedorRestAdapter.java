package mx.xpos.integracion.infrastructure.adapter.out.proveedor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import mx.xpos.integracion.domain.model.ProtocoloProveedor;
import mx.xpos.integracion.domain.model.ResultadoCanonico;
import mx.xpos.integracion.domain.model.TransaccionCanonica;
import mx.xpos.integracion.domain.port.out.ConectorProveedorPort;

/**
 * Adaptador de protocolo REST (Quarkus REST Client con OAuth/mTLS).
 * Conectado al motor de reglas del Sandbox Multiprotocolo para soportar tanto
 * modos globales (Chaos Testing) como reglas deterministas por sufijo de folio (-99, -68, -91, -94).
 */
@ApplicationScoped
public class ConectorProveedorRestAdapter implements ConectorProveedorPort {

    @Inject
    SimuladorProveedorControl simulador;

    @Override
    public ProtocoloProveedor protocoloSoportado() {
        return ProtocoloProveedor.REST;
    }

    @Override
    public ResultadoCanonico ejecutarTransaccion(TransaccionCanonica transaccion) {
        SimuladorProveedorControl.ModoProveedor modo = simulador.resolverModoParaFolio(transaccion.folio());
        simulador.registrarTramaProtocolo(
                "REST",
                transaccion.proveedorId(),
                "POST /v2/proveedores/" + transaccion.proveedorId() + "/reversos",
                transaccion.folio(),
                "JSON payload idempotencyKey=" + transaccion.idempotencyKey(),
                modo
        );
        int numLlamada = simulador.getLlamadasRecibidas();
        long latencia = simulador.getLatenciaSimuladaMs();

        return switch (modo) {
            case ARRIBA_EXITOSO -> new ResultadoCanonico.Exito(
                    "HTTP-200-OK",
                    "AUTH-REST-" + transaccion.folio() + "-" + numLlamada,
                    "Reverso aplicado exitosamente en autorizador REST para proveedor " + transaccion.proveedorId(),
                    latencia
            );
            case YA_REVERSADO_IDEMPOTENTE -> new ResultadoCanonico.YaAplicado(
                    "HTTP-200-ALREADY-REVERSED",
                    "El autorizador REST reporta que el reverso ya fue aplicado previamente",
                    latencia
            );
            case APAGADO_SIN_CONEXION -> new ResultadoCanonico.ErrorReintentable(
                    "HTTP-503-CONNECTION-REFUSED",
                    "Proveedor REST [" + transaccion.proveedorId() + "] fuera de línea / conexión rechazada",
                    latencia
            );
            case ERROR_503_REINTENTABLE -> new ResultadoCanonico.ErrorReintentable(
                    "HTTP-503-SERVICE-UNAVAILABLE",
                    "Error transitorio 503 en proveedor REST [" + transaccion.proveedorId() + "]",
                    latencia
            );
            case TIMEOUT_ESTADO_DESCONOCIDO -> new ResultadoCanonico.EstadoDesconocido(
                    "HTTP-504-READ-TIMEOUT",
                    "Read timeout después de enviar payload REST; se desconoce si el proveedor aplicó el cargo/reverso",
                    Math.max(latencia, 3000L)
            );
            case ERROR_NEGOCIO_422 -> new ResultadoCanonico.ErrorNegocio(
                    "HTTP-422-TRANSACCION-NO-REVERSABLE",
                    "VENTANA_EXPIRADA_O_FOLIO_INVALIDO",
                    "Rechazo definitivo de negocio (422): Folio [" + transaccion.folio() + "] excede política del emisor",
                    latencia
            );
        };
    }
}
