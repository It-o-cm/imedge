package com.intermarche.edge.service.fiscal;

import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * The WS-SECURITY HEADER of the tax administration's invoice web service
 * ({@code BO-11-04-02}), as the Autoridade Tributária publishes it for the
 * communication of invoices by web service (Portal das Finanças): a
 * {@code UsernameToken} whose
 * <ul>
 *   <li>{@code Username} is the communication subuser, {@code NIF/number}, in
 *       clear;</li>
 *   <li>{@code Password} is the subuser's password encrypted with a random
 *       AES-128 session key Ks ({@code AES/ECB/PKCS5Padding}), in Base64;</li>
 *   <li>{@code Nonce} is Ks encrypted with the RSA public key of the AT
 *       ({@code RSA/ECB/PKCS1Padding}), in Base64;</li>
 *   <li>{@code Created} is the UTC instant of the message encrypted with Ks, in
 *       Base64.</li>
 * </ul>
 *
 * <p>The header is drawn AT EACH SEND, never stored: a fresh session key and a
 * fresh instant per message, and the password never rests in a queued payload.
 * The subuser, its password and the certificate of the AT's public key are
 * administered parameters ({@code fiscal.at-subuser}, {@code fiscal.at-password},
 * {@code fiscal.at-public-certificate}); when one of them is absent the message
 * leaves as before, without the header, and the SAF-T screen says so.
 */
@ApplicationScoped
public class AtWsSecurity {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AtWsSecurity.class);

    /** The namespace of the WS-Security elements the AT's service reads. */
    static final String NAMESPACE = "http://schemas.xmlsoap.org/ws/2002/12/secext";

    /** The body opening tag the header is put before. */
    static final String BODY = "<S:Body>";

    /** The size of the session key, in bytes (AES-128). */
    static final int SESSION_KEY_BYTES = 16;

    /** The cipher of the password and of the instant under the session key. */
    static final String SESSION_CIPHER = "AES/ECB/PKCS5Padding";

    /** The cipher of the session key under the AT's public key. */
    static final String KEY_CIPHER = "RSA/ECB/PKCS1Padding";

    /** The instant of a message, in UTC to the millisecond. */
    static final DateTimeFormatter CREATED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Where the header stands, as the SAF-T screen states it. */
    public enum Status {
        /** The three parameters are set and the certificate is read: every message carries the header. */
        ACTIVE,
        /** A parameter is missing: the messages leave without the header, as before. */
        ABSENT,
        /** The certificate cannot be read: the sends to the AT fail until it is corrected. */
        UNREADABLE
    }

    /** The subuser, its password and the certificate. */
    @Inject
    PosSettingsService posSettingsService;

    /** The source of the session keys. */
    SecureRandom random = new SecureRandom();

    /**
     * Where the header stands with the parameters administered now.
     *
     * @return the status
     */
    public Status status() {
        if (!credentials()) {
            return Status.ABSENT;
        }
        try {
            publicKey(posSettingsService.atPublicCertificate());
            return Status.ACTIVE;
        } catch (GeneralSecurityException | IllegalArgumentException unreadable) {
            return Status.UNREADABLE;
        }
    }

    /**
     * Whether the subuser, its password and the certificate are all set.
     *
     * @return true when none is empty
     */
    private boolean credentials() {
        return !posSettingsService.atSubuser().isEmpty() && !posSettingsService.atPassword().isEmpty()
                && !posSettingsService.atPublicCertificate().isEmpty();
    }

    /**
     * Puts the WS-Security header in a RegisterInvoice envelope, before its
     * body; the envelope as it is when a parameter is absent or it has no body.
     *
     * @param envelope the SOAP envelope, without header
     * @param now the instant of the send
     * @return the envelope to send
     * @throws GeneralSecurityException when the certificate's key cannot encrypt
     *         the session key, or the certificate cannot be read
     */
    public String secure(String envelope, Instant now) throws GeneralSecurityException {
        int body = envelope.indexOf(BODY);
        if (!credentials() || body < 0) {
            LOGGER.warn("Message RegisterInvoice envoyé sans en-tête WS-Security : certificat ou identifiants de l'AT absents");
            return envelope;
        }
        PublicKey key = publicKey(posSettingsService.atPublicCertificate());
        byte[] sessionKey = new byte[SESSION_KEY_BYTES];
        random.nextBytes(sessionKey);
        String header = "<S:Header><wsse:Security xmlns:wsse=\"" + NAMESPACE + "\"><wsse:UsernameToken>"
                + "<wsse:Username>" + EInvoicingService.escape(posSettingsService.atSubuser()) + "</wsse:Username>"
                + "<wsse:Password>" + encrypt(sessionKey, posSettingsService.atPassword()) + "</wsse:Password>"
                + "<wsse:Nonce>" + wrap(key, sessionKey) + "</wsse:Nonce>"
                + "<wsse:Created>" + encrypt(sessionKey, CREATED.format(now)) + "</wsse:Created>"
                + "</wsse:UsernameToken></wsse:Security></S:Header>";
        return envelope.substring(0, body) + header + envelope.substring(body);
    }

    /**
     * Reads the RSA public key of the AT from its certificate, in PEM — the
     * Base64 between the armour lines, line breaks or not.
     *
     * @param pem the certificate
     * @return the public key
     * @throws GeneralSecurityException when the text is no certificate or its
     *         key is not an RSA key
     */
    static PublicKey publicKey(String pem) throws GeneralSecurityException {
        String base64 = pem.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "");
        byte[] der = Base64.getMimeDecoder().decode(base64);
        PublicKey key = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der)).getPublicKey();
        if (!"RSA".equals(key.getAlgorithm())) {
            throw new InvalidKeyException("La clé du certificat de l'AT n'est pas une clé RSA");
        }
        return key;
    }

    /**
     * Encrypts a text with the session key ({@code AES/ECB/PKCS5Padding}).
     *
     * @param sessionKey the session key
     * @param text the text
     * @return the cipher text, in Base64
     * @throws GeneralSecurityException when the cipher is unavailable
     */
    static String encrypt(byte[] sessionKey, String text) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(SESSION_CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sessionKey, "AES"));
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Encrypts the session key with the AT's public key
     * ({@code RSA/ECB/PKCS1Padding}).
     *
     * @param key the AT's public key
     * @param sessionKey the session key
     * @return the cipher text, in Base64
     * @throws GeneralSecurityException when the key cannot encrypt
     */
    static String wrap(PublicKey key, byte[] sessionKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(KEY_CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return Base64.getEncoder().encodeToString(cipher.doFinal(sessionKey));
    }
}
