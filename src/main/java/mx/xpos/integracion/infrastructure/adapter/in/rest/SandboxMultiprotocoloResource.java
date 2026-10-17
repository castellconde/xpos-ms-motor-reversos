package mx.xpos.integracion.infrastructure.adapter.in.rest;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import mx.xpos.integracion.infrastructure.adapter.out.proveedor.SimuladorProveedorControl;
import mx.xpos.integracion.infrastructure.adapter.out.proveedor.SimuladorProveedorControl.ModoProveedor;
import mx.xpos.integracion.infrastructure.adapter.out.proveedor.SimuladorProveedorControl.ReglaDeterministaFolio;
import mx.xpos.integracion.infrastructure.adapter.out.proveedor.SimuladorProveedorControl.TramaRecibidaSandbox;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sandbox Simulador Multiprotocolo Embebido en el Piloto Local (ISO 8583, SOAP y REST)
 * para independizar el gobierno de versiones de XPOS de los ambientes de proveedores externos.
 */
@Path("/api/v1/sandbox")
@Tag(name = "4. Sandbox Multiprotocolo (ISO 8583, SOAP, REST) - Gobierno de Versiones XPOS",
     description = "Emuladores nativos ISO 8583 (0800/0200/0420/0421), SOAP 1.1/1.2 (XML Envelope/Fault) y REST con reglas deterministas por folio")
public class SandboxMultiprotocoloResource {

    private static final Pattern TAG_FOLIO_XML = Pattern.compile("<(?:\\w+:)?Folio>([^<]+)</(?:\\w+:)?Folio>");
    private static final Pattern TAG_PROVEEDOR_XML = Pattern.compile("<(?:\\w+:)?ProveedorId>([^<]+)</(?:\\w+:)?ProveedorId>");

    @Inject
    SimuladorProveedorControl simulador;

    public record TramaIso8583SandboxDto(
            String mti,
            String proveedorId,
            String folio,
            String de11Stan,
            String de37Rrn,
            String de70NetworkManagementCode,
            String de90OriginalDataElements
    ) {}

    public record RespuestaIso8583SandboxDto(
            String mtiRespuesta,
            String de11Stan,
            String de37Rrn,
            String de39ResponseCode,
            String de90Eco,
            String tramaAsciiRespuesta,
            String descripcion
    ) {}

    @POST
    @Path("/iso8583/trama")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(
            summary = "Emulador Switch ISO 8583 (MTI 0800 Echo/Sign-On, 0200 Venta, 0420/0421 Reverso con DE 90)",
            description = "Valida DE 90 en reversos 0420/0421, certifica idempotencia (DE39=94 en repeticiones 0421) y evalúa reglas deterministas por folio (-99, -68, -91)."
    )
    public RespuestaIso8583SandboxDto procesarTramaIso8583(TramaIso8583SandboxDto req) {
        String mti = req.mti() != null ? req.mti() : "0420";
        String stan = req.de11Stan() != null ? req.de11Stan() : "000001";
        String rrn = req.de37Rrn() != null ? req.de37Rrn() : "628916" + stan;
        String folio = req.folio() != null ? req.folio() : "FOL-ISO-" + stan;

        if ("0800".equals(mti)) {
            simulador.registrarTramaProtocolo("ISO_8583", "PROSA-ISO", "0800", folio, "DE70=" + req.de70NetworkManagementCode(), ModoProveedor.ARRIBA_EXITOSO);
            return new RespuestaIso8583SandboxDto(
                    "0810", stan, rrn, "00", null,
                    "0810|DE11=" + stan + "|DE39=00|DE70=" + req.de70NetworkManagementCode(),
                    "Sign-On / Echo Test 0810 exitoso"
            );
        }

        if (("0420".equals(mti) || "0421".equals(mti))
                && (req.de90OriginalDataElements() == null || req.de90OriginalDataElements().length() < 20)) {
            simulador.registrarTramaProtocolo("ISO_8583", req.proveedorId(), mti, folio, "DE90_INVALIDO", ModoProveedor.ERROR_NEGOCIO_422);
            return new RespuestaIso8583SandboxDto(
                    "0430", stan, rrn, "12", req.de90OriginalDataElements(),
                    "0430|DE11=" + stan + "|DE39=12|ERR=DE90_MISSING",
                    "Rechazo en Switch ISO 8583: DE 90 (Original Data Elements) ausente o truncado"
            );
        }

        ModoProveedor modo = simulador.resolverModoParaFolio(folio);
        if (modo == ModoProveedor.ARRIBA_EXITOSO && simulador.marcarYVerificarYaReversadoEnSandbox(folio)) {
            modo = ModoProveedor.YA_REVERSADO_IDEMPOTENTE;
        }
        simulador.registrarTramaProtocolo("ISO_8583", req.proveedorId(), mti, folio, "DE90=" + req.de90OriginalDataElements(), modo);

        String de39 = switch (modo) {
            case ARRIBA_EXITOSO -> "00";
            case YA_REVERSADO_IDEMPOTENTE -> "94";
            case APAGADO_SIN_CONEXION, ERROR_503_REINTENTABLE -> "91";
            case TIMEOUT_ESTADO_DESCONOCIDO -> "68";
            case ERROR_NEGOCIO_422 -> "12";
        };
        String mtiResp = "0200".equals(mti) ? "0210" : "0430";
        return new RespuestaIso8583SandboxDto(
                mtiResp, stan, rrn, de39, req.de90OriginalDataElements(),
                mtiResp + "|DE11=" + stan + "|DE37=" + rrn + "|DE39=" + de39 + "|DE90=" + req.de90OriginalDataElements(),
                "Respuesta ISO 8583 emitida por Sandbox con modo " + modo
        );
    }

    @POST
    @Path("/soap/autorizador")
    @Consumes({MediaType.TEXT_XML, MediaType.APPLICATION_XML})
    @Produces(MediaType.TEXT_XML)
    @RunOnVirtualThread
    @Operation(
            summary = "Emulador Autorizador SOAP 1.1/1.2 (XML Envelope + WS-Security + SOAPFault)",
            description = "Recibe un SOAP Envelope XML y devuelve ReversoSoapResponse o soapenv:Fault según el modo o el sufijo del folio."
    )
    public Response procesarSoapEnvelope(String xmlEnvelope) {
        String folio = extraerTag(TAG_FOLIO_XML, xmlEnvelope, "FOL-SOAP-0001");
        String proveedorId = extraerTag(TAG_PROVEEDOR_XML, xmlEnvelope, "BBVA-SOAP");
        ModoProveedor modo = simulador.resolverModoParaFolio(folio);
        simulador.registrarTramaProtocolo("SOAP", proveedorId, "SOAP_ENVELOPE", folio, "WS-Security XML", modo);

        return switch (modo) {
            case ARRIBA_EXITOSO, YA_REVERSADO_IDEMPOTENTE -> {
                String codigo = modo == ModoProveedor.ARRIBA_EXITOSO ? "SOAP-00-OK" : "SOAP-94-ALREADY-REVERSED";
                String xmlOk = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xpos="http://xpos.mx/pagos/soap">
                           <soapenv:Body>
                              <xpos:ReversoSoapResponse>
                                 <xpos:Folio>%s</xpos:Folio>
                                 <xpos:CodigoRespuesta>%s</xpos:CodigoRespuesta>
                              </xpos:ReversoSoapResponse>
                           </soapenv:Body>
                        </soapenv:Envelope>
                        """.formatted(folio, codigo);
                yield Response.ok(xmlOk).build();
            }
            case APAGADO_SIN_CONEXION, ERROR_503_REINTENTABLE -> Response.status(503)
                    .entity(soapFault("soap:Server", "SOAP-FAULT-SERVER-91", "Switch SOAP temporalmente fuera de servicio"))
                    .build();
            case TIMEOUT_ESTADO_DESCONOCIDO -> Response.status(504)
                    .entity(soapFault("soap:Server", "SOAP-READ-TIMEOUT-68", "Read timeout en autorizador SOAP"))
                    .build();
            case ERROR_NEGOCIO_422 -> Response.status(422)
                    .entity(soapFault("soap:Client", "SOAP-FAULT-CLIENT-12", "Rechazo definitivo de negocio para folio " + folio))
                    .build();
        };
    }

    @POST
    @Path("/rest/v2/proveedores/{proveedorId}/reversos")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(summary = "Emulador Proveedor REST / JSON con códigos HTTP reales (200, 422, 503, 504)")
    public Response procesarRestSandbox(
            @PathParam("proveedorId") String proveedorId,
            Map<String, Object> payload
    ) {
        String folio = String.valueOf(payload.getOrDefault("folio", "FOL-REST-0001"));
        ModoProveedor modo = simulador.resolverModoParaFolio(folio);
        simulador.registrarTramaProtocolo("REST", proveedorId, "POST /reversos", folio, "REST JSON", modo);

        return switch (modo) {
            case ARRIBA_EXITOSO -> Response.status(200)
                    .entity(Map.of("folio", folio, "codigoRespuesta", "HTTP-200-OK", "claseCanonica", "Exito"))
                    .build();
            case YA_REVERSADO_IDEMPOTENTE -> Response.status(200)
                    .entity(Map.of("folio", folio, "codigoRespuesta", "HTTP-200-ALREADY-REVERSED", "claseCanonica", "YaAplicado"))
                    .build();
            case APAGADO_SIN_CONEXION, ERROR_503_REINTENTABLE -> Response.status(503)
                    .entity(Map.of("folio", folio, "codigoRespuesta", "HTTP-503-SERVICE-UNAVAILABLE", "claseCanonica", "ErrorReintentable"))
                    .build();
            case TIMEOUT_ESTADO_DESCONOCIDO -> Response.status(504)
                    .entity(Map.of("folio", folio, "codigoRespuesta", "HTTP-504-READ-TIMEOUT", "claseCanonica", "EstadoDesconocido"))
                    .build();
            case ERROR_NEGOCIO_422 -> Response.status(422)
                    .entity(Map.of("folio", folio, "codigoRespuesta", "HTTP-422-UNPROCESSABLE-ENTITY", "claseCanonica", "ErrorNegocio"))
                    .build();
        };
    }

    @GET
    @Path("/reglas")
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(summary = "Listar reglas deterministas por sufijo de folio para Gobierno de Versiones XPOS")
    public List<ReglaDeterministaFolio> listarReglas() {
        return simulador.listarReglasDeterministas();
    }

    @POST
    @Path("/reglas")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(summary = "Agregar o sobrescribir una regla determinista por sufijo de folio")
    public ReglaDeterministaFolio agregarRegla(ReglaDeterministaFolio regla) {
        return simulador.registrarRegla(regla);
    }

    @GET
    @Path("/tramas")
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(summary = "Inspeccionar bitácora de todas las tramas ISO 8583, Envelopes SOAP y llamadas REST recibidas en el Sandbox")
    public List<TramaRecibidaSandbox> listarTramasRecibidas() {
        return simulador.listarTramasRecibidas();
    }

    private String extraerTag(Pattern p, String xml, String def) {
        if (xml == null) {
            return def;
        }
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : def;
    }

    private String soapFault(String faultCode, String codigoProveedor, String detalle) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                   <soapenv:Body>
                      <soapenv:Fault>
                         <faultcode>%s</faultcode>
                         <faultstring>%s</faultstring>
                         <detail><CodigoErrorProveedor>%s</CodigoErrorProveedor></detail>
                      </soapenv:Fault>
                   </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(faultCode, detalle, codigoProveedor);
    }
}
