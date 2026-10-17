package mx.xpos.integracion;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import mx.xpos.integracion.application.service.MotorReversosService;
import mx.xpos.integracion.infrastructure.adapter.out.messaging.PublicadorOutboxKafkaAdapter;
import mx.xpos.integracion.infrastructure.adapter.out.persistence.RepositorioBitacoraAdapter;
import mx.xpos.integracion.infrastructure.adapter.out.persistence.RepositorioReversoAdapter;
import mx.xpos.integracion.infrastructure.adapter.out.proveedor.SimuladorProveedorControl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Suite de pruebas integrales que valida los 6 pasos del Guion de Demo del Piloto Local
 * y los controles de seguridad PCI-DSS (P2) y mTLS RFC 8705 (P3 / RT-01).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PilotoGuionDemoEndToEndTest {

    @Inject
    SimuladorProveedorControl simuladorProveedor;

    @Inject
    MotorReversosService motorReversosService;

    @Inject
    RepositorioReversoAdapter repositorioReverso;

    @Inject
    RepositorioBitacoraAdapter repositorioBitacora;

    @Inject
    PublicadorOutboxKafkaAdapter publicadorOutbox;

    @BeforeEach
    void prepararAmbienteLimpio() {
        simuladorProveedor.reiniciarContadores();
        repositorioReverso.limpiarTodoParaPruebas();
        repositorioBitacora.limpiarTodoParaPruebas();
        publicadorOutbox.limpiarTodoParaPruebas();
        motorReversosService.restablecerCircuitBreaker();
    }

    @Test
    @Order(1)
    @DisplayName("Paso 1 del Guion: Proveedor arriba -> Reverso exitoso, idempotente y con bitácora SHA-256 íntegra")
    void paso1ProveedorArribaReversoExitosoYBitacoraIntegra() {
        String payload = """
                {
                  "transaccionOriginalId": "TXN-ORIG-1001",
                  "reversoId": "REV-1001",
                  "cajaId": "POS-0101",
                  "folio": "FOL-2026101601",
                  "proveedorId": "PROSA-ISO",
                  "protocolo": "ISO_8583",
                  "monto": 450.75,
                  "moneda": "MXN",
                  "tarjetaTokenizadaOEnmascarada": "411111******1111"
                }
                """;

        // 1. Primera solicitud: sale exitosa (HTTP 200)
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-Client-Cert", "Hash=thumbprint-caja-0101;Subject=\"CN=POS-0101\"")
                .header("X-JWT-Confirmation-Thumbprint", "thumbprint-caja-0101")
                .header("Idempotency-Key", "IDEM-1001")
                .body(payload)
                .when()
                .post("/api/v1/pos/reversos")
                .then()
                .statusCode(200)
                .body("estadoActual", equalTo("EXITOSO"))
                .body("intentosTotales", equalTo(1))
                .body("ultimoCodigoRespuesta", equalTo("ISO-39-00"))
                .body("stanIsoDe11", notNullValue());

        int llamadasTrasPrimerEnvio = simuladorProveedor.getLlamadasRecibidas();

        // 2. Reenvío idempotente de la misma caja: devuelve el mismo éxito sin volver a llamar al proveedor
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-Client-Cert", "Hash=thumbprint-caja-0101;Subject=\"CN=POS-0101\"")
                .header("X-JWT-Confirmation-Thumbprint", "thumbprint-caja-0101")
                .header("Idempotency-Key", "IDEM-1001")
                .body(payload)
                .when()
                .post("/api/v1/pos/reversos")
                .then()
                .statusCode(200)
                .body("estadoActual", equalTo("EXITOSO"))
                .body("intentosTotales", equalTo(1));

        org.junit.jupiter.api.Assertions.assertEquals(
                llamadasTrasPrimerEnvio,
                simuladorProveedor.getLlamadasRecibidas(),
                "Un reintento con la misma clave de idempotencia NO debe invocar de nuevo al proveedor"
        );

        // 3. Verificar bitácora append-only y cadena de hashes SHA-256 (RT-04)
        given()
                .when()
                .get("/api/v1/soporte/bitacora/FOL-2026101601")
                .then()
                .statusCode(200)
                .body("cadenaHashIntegra", is(true))
                .body("totalEventos", equalTo(3))
                .body("eventos[0].estadoTransicion", equalTo("RECIBIDO"))
                .body("eventos[2].estadoTransicion", equalTo("EXITOSO"));
    }

    @Test
    @Order(2)
    @DisplayName("Pasos 2 y 3 del Guion: Proveedor apagado abre circuito y pausa Kafka; al volver drena todo sin duplicados")
    void pasos2Y3ProveedorApagadoPausaConsumidorYDrenaAlRecuperarse() {
        // 1. Apagar el proveedor simulado
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "modo": "APAGADO_SIN_CONEXION",
                          "latenciaMs": 10
                        }
                        """)
                .when()
                .post("/api/v1/simulador/proveedor/modo")
                .then()
                .statusCode(200)
                .body("modoProveedor", equalTo("APAGADO_SIN_CONEXION"));

        // 2. Enviar primer reverso: agota los 2 reintentos inmediatos, abre el Circuit Breaker y pausa el consumidor
        String rev1 = """
                {
                  "transaccionOriginalId": "TXN-ORIG-2001",
                  "reversoId": "REV-2001",
                  "cajaId": "POS-0201",
                  "folio": "FOL-2026101602",
                  "proveedorId": "BANORTE-REST",
                  "protocolo": "REST",
                  "monto": 120.00,
                  "moneda": "MXN",
                  "tarjetaTokenizadaOEnmascarada": "TOK-890123456789"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(rev1)
                .when()
                .post("/api/v1/pos/reversos")
                .then()
                .statusCode(202);

        // Verificar que el Circuit Breaker está OPEN y el consumidor de Kafka está PAUSADO (RT-02)
        given()
                .when()
                .get("/api/v1/simulador/estado")
                .then()
                .statusCode(200)
                .body("estadoCircuitBreaker", equalTo("OPEN"))
                .body("consumidorKafkaPausado", is(true))
                .body("mensajesRetenidosEnColaPausada", equalTo(1));

        int llamadasConCircuitoAbierto = simuladorProveedor.getLlamadasRecibidas();

        // 3. Enviar segundo reverso mientras el circuito está abierto: se acumula en la cola sin tocar al proveedor
        String rev2 = """
                {
                  "transaccionOriginalId": "TXN-ORIG-2002",
                  "reversoId": "REV-2002",
                  "cajaId": "POS-0202",
                  "folio": "FOL-2026101603",
                  "proveedorId": "BANORTE-REST",
                  "protocolo": "REST",
                  "monto": 310.50,
                  "moneda": "MXN",
                  "tarjetaTokenizadaOEnmascarada": "TOK-990123456789"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(rev2)
                .when()
                .post("/api/v1/pos/reversos")
                .then()
                .statusCode(202);

        org.junit.jupiter.api.Assertions.assertEquals(
                llamadasConCircuitoAbierto,
                simuladorProveedor.getLlamadasRecibidas(),
                "Con el consumidor pausado por Circuit Breaker OPEN, NO se debe golpear al proveedor caído"
        );

        given()
                .when()
                .get("/api/v1/simulador/estado")
                .then()
                .statusCode(200)
                .body("mensajesRetenidosEnColaPausada", equalTo(2));

        // 4. Paso 3 del Guion: El proveedor regresa a ARRIBA_EXITOSO y se drena la cola pausada
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "modo": "ARRIBA_EXITOSO",
                          "latenciaMs": 10
                        }
                        """)
                .when()
                .post("/api/v1/simulador/proveedor/modo")
                .then()
                .statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .when()
                .post("/api/v1/simulador/cola-pausada/drenar")
                .then()
                .statusCode(200)
                .body("size()", equalTo(2))
                .body("[0].estadoActual", equalTo("EXITOSO"))
                .body("[1].estadoActual", equalTo("EXITOSO"));

        // Verificar que la cola quedó en 0, el consumidor reanudado y el Circuit Breaker CLOSED
        given()
                .when()
                .get("/api/v1/simulador/estado")
                .then()
                .statusCode(200)
                .body("estadoCircuitBreaker", equalTo("CLOSED"))
                .body("consumidorKafkaPausado", is(false))
                .body("mensajesRetenidosEnColaPausada", equalTo(0));
    }

    @Test
    @Order(3)
    @DisplayName("Paso 4 del Guion: Error de negocio 422 va directo a DLQ sin reintentos y se reprocesa desde soporte")
    void paso4ErrorNegocioVaDirectoADlqYSeReprocesaDesdeSoporte() {
        // 1. Configurar proveedor para devolver Error de Negocio 422
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "modo": "ERROR_NEGOCIO_422",
                          "latenciaMs": 8
                        }
                        """)
                .when()
                .post("/api/v1/simulador/proveedor/modo")
                .then()
                .statusCode(200);

        // 2. Enviar reverso: debe ir directo a DLQ en 1 solo intento (sin reintentar errores de negocio)
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "transaccionOriginalId": "TXN-ORIG-4001",
                          "reversoId": "REV-4001",
                          "cajaId": "POS-0401",
                          "folio": "FOL-2026101604",
                          "proveedorId": "BBVA-SOAP",
                          "protocolo": "SOAP",
                          "monto": 890.00,
                          "moneda": "MXN",
                          "tarjetaTokenizadaOEnmascarada": "520000******9999"
                        }
                        """)
                .when()
                .post("/api/v1/pos/reversos")
                .then()
                .statusCode(202)
                .body("estadoActual", equalTo("DLQ_INTERVENCION_MANUAL"))
                .body("escalonActual", equalTo("DLQ"))
                .body("intentosTotales", equalTo(1));

        // Verificar que aparece en el listado de DLQ de soporte
        given()
                .when()
                .get("/api/v1/soporte/dlq")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].reversoId", equalTo("REV-4001"));

        // 3. Corregida la condición en el proveedor, soporte reprocesa el reverso desde la DLQ
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "modo": "ARRIBA_EXITOSO",
                          "latenciaMs": 8
                        }
                        """)
                .when()
                .post("/api/v1/simulador/proveedor/modo")
                .then()
                .statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "operadorSoporte": "roberto.soporte@xpos.mx",
                          "motivo": "Ventana de reverso autorizada por el emisor; reprocesando desde DLQ"
                        }
                        """)
                .when()
                .post("/api/v1/soporte/dlq/REV-4001/reprocesar")
                .then()
                .statusCode(200)
                .body("estadoActual", equalTo("EXITOSO"))
                .body("intentosTotales", equalTo(2));

        // Verificar que la cadena de hashes SHA-256 sigue íntegra tras el reproceso manual
        given()
                .when()
                .get("/api/v1/soporte/bitacora/FOL-2026101604")
                .then()
                .statusCode(200)
                .body("cadenaHashIntegra", is(true))
                .body("totalEventos", greaterThanOrEqualTo(5));
    }

    @Test
    @Order(4)
    @DisplayName("Paso 5 del Guion: Job de depuración DETACH + DROP bloquea si falta réplica y ejecuta cuando cumple")
    void paso5DepuracionCondicionadaDeParticionBitacora() {
        String nombreParticionActual = DateTimeFormatter.ofPattern("'bitacora_auditoria_y'yyyy'm'MM")
                .format(LocalDate.now(ZoneOffset.UTC));

        // Generar una transacción para que la partición tenga 3 eventos en bitácora
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "transaccionOriginalId": "TXN-ORIG-5001",
                          "reversoId": "REV-5001",
                          "cajaId": "POS-0501",
                          "folio": "FOL-2026101605",
                          "proveedorId": "PROSA-ISO",
                          "protocolo": "REST",
                          "monto": 50.00,
                          "moneda": "MXN",
                          "tarjetaTokenizadaOEnmascarada": "411111******2222"
                        }
                        """)
                .when()
                .post("/api/v1/pos/reversos")
                .then()
                .statusCode(200);

        // 1. Registrar la partición como archivada en frío, pero SIN replicar aún a reportes comerciales
        given()
                .contentType(ContentType.JSON)
                .body(String.format("""
                        {
                          "nombreParticion": "%s",
                          "fechaInicio": "2026-10-01",
                          "fechaFin": "2026-10-31",
                          "archivadoEnFrioWorm": true,
                          "replicadoAReportes": false,
                          "conteoFilasArchivadas": 3,
                          "conteoFilasReplicadas": 0
                        }
                        """, nombreParticionActual))
                .when()
                .post("/api/v1/soporte/particiones")
                .then()
                .statusCode(200);

        // Intentar depurar debe ser BLOQUEADO (HTTP 412) porque replicadoAReportes es false
        given()
                .contentType(ContentType.JSON)
                .when()
                .post("/api/v1/soporte/particiones/" + nombreParticionActual + "/depurar")
                .then()
                .statusCode(412)
                .body("codigoError", equalTo("ERR-412-DEPURACION-BLOQUEADA"));

        // 2. Confirmar réplica a reportes con conteo coincidente (3 filas en partición, archivo frío y réplica)
        given()
                .contentType(ContentType.JSON)
                .body(String.format("""
                        {
                          "nombreParticion": "%s",
                          "fechaInicio": "2026-10-01",
                          "fechaFin": "2026-10-31",
                          "archivadoEnFrioWorm": true,
                          "replicadoAReportes": true,
                          "conteoFilasArchivadas": 3,
                          "conteoFilasReplicadas": 3
                        }
                        """, nombreParticionActual))
                .when()
                .post("/api/v1/soporte/particiones")
                .then()
                .statusCode(200);

        // Ahora el job de mantenimiento ejecuta DETACH + DROP exitosamente (HTTP 200)
        given()
                .contentType(ContentType.JSON)
                .when()
                .post("/api/v1/soporte/particiones/" + nombreParticionActual + "/depurar")
                .then()
                .statusCode(200)
                .body("depurada", is(true))
                .body("hashCierreSha256", notNullValue())
                .body("rutaBlobParquetWorm", notNullValue());
    }

    @Test
    @Order(5)
    @DisplayName("Seguridad PCI-DSS (P2) y mTLS RFC 8705 (P3 / RT-01): Rechazo de PAN en claro y de certificado suplantado")
    void seguridadRechazaPanEnClaroYCertificadoMtlsDiscrepante() {
        // 1. Rechazar PAN en claro (Principio P2 PCI-DSS)
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "transaccionOriginalId": "TXN-SEC-01",
                          "reversoId": "REV-SEC-01",
                          "cajaId": "POS-0999",
                          "folio": "FOL-SEC001",
                          "proveedorId": "PROSA-ISO",
                          "protocolo": "REST",
                          "monto": 100.00,
                          "moneda": "MXN",
                          "tarjetaTokenizadaOEnmascarada": "4111222233334444"
                        }
                        """)
                .when()
                .post("/api/v1/pos/reversos")
                .then()
                .statusCode(400)
                .body("codigoError", equalTo("SEC-PCI-002"));

        // 2. Rechazar token cuyo thumbprint RFC 8705 no coincide con el certificado XFCC presentado (RT-01)
        given()
                .contentType(ContentType.JSON)
                .header("X-Forwarded-Client-Cert", "Hash=thumbprint-caja-intrusa;Subject=\"CN=POS-9999\"")
                .header("X-JWT-Confirmation-Thumbprint", "thumbprint-caja-legitima-0001")
                .body("""
                        {
                          "transaccionOriginalId": "TXN-SEC-02",
                          "reversoId": "REV-SEC-02",
                          "cajaId": "POS-0999",
                          "folio": "FOL-SEC002",
                          "proveedorId": "PROSA-ISO",
                          "protocolo": "REST",
                          "monto": 100.00,
                          "moneda": "MXN",
                          "tarjetaTokenizadaOEnmascarada": "411111******4444"
                        }
                        """)
                .when()
                .post("/api/v1/pos/reversos")
                .then()
                .statusCode(403)
                .body("codigoError", equalTo("SEC-MTLS-8705-002"));
    }

    @Test
    @Order(6)
    @DisplayName("Sandbox Multiprotocolo (Gobierno Versiones XPOS): Certifica ISO 8583 (0800/0420/0421), SOAP XML y REST con reglas deterministas (-99, -68, -94)")
    void paso6SandboxMultiprotocoloGobiernoVersionesIsoSoapRest() {
        // 1. Emulador ISO 8583: Echo 0800 -> 0810 DE39=00
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "mti": "0800",
                          "proveedorId": "PROSA-ISO",
                          "folio": "FOL-ECHO-01",
                          "de11Stan": "000015",
                          "de70NetworkManagementCode": "301"
                        }
                        """)
                .when()
                .post("/api/v1/sandbox/iso8583/trama")
                .then()
                .statusCode(200)
                .body("mtiRespuesta", equalTo("0810"))
                .body("de39ResponseCode", equalTo("00"));

        // 2. Emulador SOAP 1.1/1.2 directo: Folio terminado en -99 dispara SOAPFault Client (HTTP 422)
        given()
                .contentType("text/xml")
                .body("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xpos="http://xpos.mx/pagos/soap">
                           <soapenv:Body>
                              <xpos:ReversoSoapRequest>
                                 <xpos:ProveedorId>BBVA-SOAP</xpos:ProveedorId>
                                 <xpos:Folio>FOL-GOV-99</xpos:Folio>
                              </xpos:ReversoSoapRequest>
                           </soapenv:Body>
                        </soapenv:Envelope>
                        """)
                .when()
                .post("/api/v1/sandbox/soap/autorizador")
                .then()
                .statusCode(422);

        // 3. Reglas deterministas en el Motor de Reversos SIN cambiar el modo global (ideal para CI/CD paralelo):
        // 3.1 Folio terminado en -94 -> Simula "Ya Reversado" en autorizador ISO 8583 -> Estado YA_APLICADO (HTTP 200)
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "transaccionOriginalId": "TXN-GOV-94",
                          "reversoId": "REV-GOV-94",
                          "cajaId": "POS-0601",
                          "folio": "FOL-CERT-94",
                          "proveedorId": "PROSA-ISO",
                          "protocolo": "ISO_8583",
                          "monto": 150.00,
                          "moneda": "MXN",
                          "tarjetaTokenizadaOEnmascarada": "411111******1111"
                        }
                        """)
                .when()
                .post("/api/v1/pos/reversos")
                .then()
                .statusCode(200)
                .body("estadoActual", equalTo("YA_APLICADO"))
                .body("ultimoCodigoRespuesta", equalTo("ISO-39-94"));

        // 3.2 Folio terminado en -68 -> Simula Read Timeout post-envío -> ESTADO_DESCONOCIDO_CONSULTA en escalón RETRY_1M
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "transaccionOriginalId": "TXN-GOV-68",
                          "reversoId": "REV-GOV-68",
                          "cajaId": "POS-0602",
                          "folio": "FOL-CERT-68",
                          "proveedorId": "BANORTE-REST",
                          "protocolo": "REST",
                          "monto": 275.00,
                          "moneda": "MXN",
                          "tarjetaTokenizadaOEnmascarada": "TOK-6800001111"
                        }
                        """)
                .when()
                .post("/api/v1/pos/reversos")
                .then()
                .statusCode(202)
                .body("estadoActual", equalTo("ESTADO_DESCONOCIDO_CONSULTA"))
                .body("escalonActual", equalTo("RETRY_1M"))
                .body("intentosTotales", equalTo(1));

        // 4. Verificar que la bitácora del Sandbox registró todas las tramas multiprotocolo
        given()
                .when()
                .get("/api/v1/sandbox/tramas")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(4));
    }
}
