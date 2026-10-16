package mx.xpos.integracion.domain.port.out;

import mx.xpos.integracion.domain.model.ProtocoloProveedor;
import mx.xpos.integracion.domain.model.ResultadoCanonico;
import mx.xpos.integracion.domain.model.TransaccionCanonica;

/**
 * Puerto de salida (Hexagonal Architecture) hacia los adaptadores de protocolo
 * (REST, SOAP, ISO 8583). Los adaptadores solo traducen y clasifican la respuesta
 * en una de las 5 clases de ResultadoCanonico; nunca contienen lógica de reintentos.
 */
public interface ConectorProveedorPort {

    ProtocoloProveedor protocoloSoportado();

    ResultadoCanonico ejecutarTransaccion(TransaccionCanonica transaccion);
}
