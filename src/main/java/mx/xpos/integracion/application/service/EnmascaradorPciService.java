package mx.xpos.integracion.application.service;

import jakarta.enterprise.context.ApplicationScoped;
import mx.xpos.integracion.domain.exception.ValidacionSeguridadException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Servicio transversal de seguridad PCI-DSS (Principio P2) y validación mTLS RFC 8705 (Principio P3 / RT-01).
 * Garantiza que ningún PAN en claro llegue a Kafka, PostgreSQL o logs, y valida cabeceras XFCC.
 */
@ApplicationScoped
public class EnmascaradorPciService {

    // Detecta secuencias continuas de 13 a 19 dígitos candidatas a PAN sin enmascarar
    private static final Pattern PATRON_PAN_CLARO = Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)");
    // Allowlist de formato de tarjeta ya tokenizada (TOK-...) o enmascarada (411111******1111)
    private static final Pattern PATRON_TARJETA_SEGURA = Pattern.compile("^(TOK-[A-Za-z0-9_-]{8,64}|\\d{6}\\*{4,9}\\d{4})$");

    /**
     * Valida o enmascara un identificador de tarjeta cumpliendo PCI-DSS Req 3.3 / 3.4.
     * Si recibe un PAN en claro y rechazoEstrictoPanClaro es true, lanza excepción de seguridad;
     * de lo contrario lo enmascara en formato BIN(6) + ****** + LAST4(4).
     */
    public String validarOEnmascararTarjeta(String valorTarjeta, boolean rechazarPanEnClaro) {
        if (valorTarjeta == null || valorTarjeta.isBlank()) {
            throw new ValidacionSeguridadException("SEC-PCI-001", "El identificador de tarjeta tokenizada/enmascarada es obligatorio");
        }
        String limpio = valorTarjeta.trim();
        if (PATRON_TARJETA_SEGURA.matcher(limpio).matches()) {
            return limpio;
        }
        String soloDigitos = limpio.replaceAll("[\\s-]", "");
        if (PATRON_PAN_CLARO.matcher(soloDigitos).matches()) {
            if (rechazarPanEnClaro) {
                throw new ValidacionSeguridadException(
                        "SEC-PCI-002",
                        "Violación P2 PCI-DSS: Se detectó un PAN en claro en la solicitud. Debe enviarse tokenizado o enmascarado desde el POS"
                );
            }
            return soloDigitos.substring(0, 6) + "******" + soloDigitos.substring(soloDigitos.length() - 4);
        }
        throw new ValidacionSeguridadException(
                "SEC-PCI-003",
                "Formato de tarjeta inválido. Use token (TOK-...) o máscara PCI (6 dígitos BIN + * + 4 dígitos finales)"
        );
    }

    /**
     * Sanitiza cualquier texto de bitácora o detalle de error reemplazando posibles PANs accidentales.
     */
    public String sanitizarTextoParaBitacora(String texto) {
        if (texto == null) {
            return "";
        }
        return PATRON_PAN_CLARO.matcher(texto).replaceAll(match -> {
            String pan = match.group();
            return pan.substring(0, 6) + "******" + pan.substring(pan.length() - 4);
        });
    }

    /**
     * Verifica la ligadura criptográfica RFC 8705 entre la cabecera X-Forwarded-Client-Cert (XFCC)
     * inyectada por el WAF (RT-01) y el thumbprint esperado del certificado de la caja POS.
     */
    public void validarIdentidadCertificadoPos(String cajaId, String xfccHeader, String jwtThumbprintClaim) {
        if (xfccHeader == null || xfccHeader.isBlank()) {
            throw new ValidacionSeguridadException(
                    "SEC-MTLS-8705-001",
                    "Acceso denegado: Cabecera X-Forwarded-Client-Cert (mTLS de caja) ausente"
            );
        }
        if (jwtThumbprintClaim != null && !jwtThumbprintClaim.isBlank()) {
            if (!xfccHeader.contains(jwtThumbprintClaim)) {
                throw new ValidacionSeguridadException(
                        "SEC-MTLS-8705-002",
                        "Violación RFC 8705: El certificado mTLS presentado por la caja [" + cajaId
                                + "] no coincide con el claim cnf.x5t#S256 del token JWT"
                );
            }
        }
    }

    public String calcularThumbprintSha256Base64Url(String certificadoPemOSimulado) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(certificadoPemOSimulado.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
