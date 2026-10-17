package mx.xpos.integracion.infrastructure.adapter.out.proveedor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import mx.xpos.integracion.domain.model.ProtocoloProveedor;
import mx.xpos.integracion.domain.model.ResultadoCanonico;
import mx.xpos.integracion.domain.model.TransaccionCanonica;
import mx.xpos.integracion.domain.port.out.ConectorProveedorPort;

/**
 * Adaptador de protocolo SOAP (Quarkus CXF + WS-Security).
 * Construye el SOAP Envelope con cabecera WS-Security y clasifica la respuesta o SOAPFault
 * en una de las 5 clases de ResultadoCanonico.
 */
@ApplicationScoped
public class ConectorProveedorSoapAdapter implements ConectorProveedorPort {

    @Inject
    SimuladorProveedorControl simulador;

    @Override
    public ProtocoloProveedor protocoloSoportado() {
        return ProtocoloProveedor.SOAP;
    }

    @Override
    public ResultadoCanonico ejecutarTransaccion(TransaccionCanonica transaccion) {
        SimuladorProveedorControl.ModoProveedor modo = simulador.resolverModoParaFolio(transaccion.folio());
        String soapEnvelopeResumen = "<soapenv:Envelope><soapenv:Header><wsse:Security/></soapenv:Header><soapenv:Body><ReversoSoapRequest folio=\""
                + transaccion.folio() + "\"/></soapenv:Body></soapenv:Envelope>";
        simulador.registrarTramaProtocolo(
                "SOAP",
                transaccion.proveedorId(),
                "SOAPAction:ReversoSoapRequest",
                transaccion.folio(),
                soapEnvelopeResumen,
                modo
        );
        int numLlamada = simulador.getLlamadasRecibidas();
        long latencia = simulador.getLatenciaSimuladaMs();

        return switch (modo) {
            case ARRIBA_EXITOSO -> new ResultadoCanonico.Exito(
                    "SOAP-00",
                    "AUTH-SOAP-" + transaccion.folio() + "-" + numLlamada,
                    "Envelope SOAP procesado exitosamente con WS-Security",
                    latencia
            );
            case YA_REVERSADO_IDEMPOTENTE -> new ResultadoCanonico.YaAplicado(
                    "SOAP-94-DUPLICATE-REVERSAL",
                    "Transacción SOAP ya reversada previamente",
                    latencia
            );
            case APAGADO_SIN_CONEXION, ERROR_503_REINTENTABLE -> new ResultadoCanonico.ErrorReintentable(
                    "SOAP-FAULT-SERVER-UNAVAILABLE",
                    "Endpoint SOAP no disponible temporalmente (soap:Server Fault)",
                    latencia
            );
            case TIMEOUT_ESTADO_DESCONOCIDO -> new ResultadoCanonico.EstadoDesconocido(
                    "SOAP-READ-TIMEOUT",
                    "Timeout esperando SOAP Response después de transmitir el request",
                    Math.max(latencia, 3000L)
            );
            case ERROR_NEGOCIO_422 -> new ResultadoCanonico.ErrorNegocio(
                    "SOAP-FAULT-CLIENT-12",
                    "TRANSACCION_ORIGINAL_INVALIDA",
                    "Rechazo de negocio en servicio SOAP (soap:Client Fault)",
                    latencia
            );
        };
    }
}
