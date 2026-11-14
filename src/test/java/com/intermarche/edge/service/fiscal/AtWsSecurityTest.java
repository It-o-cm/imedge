package com.intermarche.edge.service.fiscal;

import com.intermarche.edge.service.PosSettingsService;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AtWsSecurity} (BO-11-04-02): the header is drawn with a
 * key pair generated in the test, then decrypted with its private key — the
 * Nonce unwraps to the session key, the Password and the Created instant
 * decrypt under it —; each missing parameter leaves the message as it was; an
 * unreadable certificate or a key that is not RSA is refused.
 */
class AtWsSecurityTest {

    /** The envelope of the tests, as {@code ThirdPartyFormats} writes one. */
    private static final String ENVELOPE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<S:Envelope xmlns:S=\"http://schemas.xmlsoap.org/soap/envelope/\"><S:Body><ns2:RegisterInvoiceElem/>"
            + "</S:Body></S:Envelope>";

    /** The instant of the send. */
    private static final Instant NOW = Instant.parse("2026-10-26T08:30:00.123Z");

    /** The algorithm identifier of SHA-256 with RSA, DER. */
    private static final byte[] SHA256_RSA = der(0x30, new byte[] {0x06, 0x09, 0x2A, (byte) 0x86, 0x48,
            (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B}, new byte[] {0x05, 0x00});

    /** The algorithm identifier of ECDSA with SHA-256, DER. */
    private static final byte[] SHA256_ECDSA = der(0x30, new byte[] {0x06, 0x08, 0x2A, (byte) 0x86, 0x48,
            (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02});

    /**
     * Encodes one DER element.
     *
     * @param tag the tag
     * @param parts the content, concatenated
     * @return the element
     */
    private static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            content.writeBytes(part);
        }
        byte[] body = content.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (body.length < 128) {
            out.write(body.length);
        } else if (body.length < 256) {
            out.write(0x81);
            out.write(body.length);
        } else {
            out.write(0x82);
            out.write(body.length >> 8);
            out.write(body.length & 0xFF);
        }
        out.writeBytes(body);
        return out.toByteArray();
    }

    /**
     * Builds a self-signed X.509 certificate of a key pair, in PEM.
     *
     * @param pair the key pair
     * @param signatureAlgorithm the JCA name of the signature
     * @param algorithmId the DER identifier of the signature
     * @return the certificate
     * @throws GeneralSecurityException when the signature fails
     */
    private static String certificate(KeyPair pair, String signatureAlgorithm, byte[] algorithmId)
            throws GeneralSecurityException {
        byte[] name = der(0x30, der(0x31, der(0x30, new byte[] {0x06, 0x03, 0x55, 0x04, 0x03},
                der(0x0C, "AT test".getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = der(0x30, der(0x17, "250101000000Z".getBytes(StandardCharsets.US_ASCII)),
                der(0x17, "350101000000Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] tbs = der(0x30, der(0xA0, der(0x02, new byte[] {2})), der(0x02, new byte[] {1}), algorithmId, name,
                validity, name, pair.getPublic().getEncoded());
        Signature signature = Signature.getInstance(signatureAlgorithm);
        signature.initSign(pair.getPrivate());
        signature.update(tbs);
        byte[] signed = signature.sign();
        byte[] bits = new byte[signed.length + 1];
        System.arraycopy(signed, 0, bits, 1, signed.length);
        byte[] certificate = der(0x30, tbs, algorithmId, der(0x03, bits));
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder().encodeToString(certificate)
                + "\n-----END CERTIFICATE-----";
    }

    /**
     * Generates an RSA key pair, the AT's in the tests.
     *
     * @return the key pair
     * @throws GeneralSecurityException never
     */
    private static KeyPair rsa() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /**
     * Builds the service over the given parameters.
     *
     * @param subuser the subuser
     * @param password its password
     * @param certificate the certificate
     * @return the service
     */
    private static AtWsSecurity security(String subuser, String password, String certificate) {
        AtWsSecurity security = new AtWsSecurity();
        security.posSettingsService = mock(PosSettingsService.class);
        when(security.posSettingsService.atSubuser()).thenReturn(subuser);
        when(security.posSettingsService.atPassword()).thenReturn(password);
        when(security.posSettingsService.atPublicCertificate()).thenReturn(certificate);
        return security;
    }

    /**
     * Reads one element of the header.
     *
     * @param message the secured message
     * @param name the element, without prefix
     * @return its text
     */
    private static String element(String message, String name) {
        Matcher matcher = Pattern.compile("<wsse:" + name + ">([^<]*)</wsse:" + name + ">").matcher(message);
        assertTrue(matcher.find(), name);
        return matcher.group(1);
    }

    /**
     * Decrypts a header value under the session key.
     *
     * @param sessionKey the session key
     * @param value the Base64 cipher text
     * @return the clear text
     * @throws GeneralSecurityException when it does not decrypt
     */
    private static String decrypt(byte[] sessionKey, String value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sessionKey, "AES"));
        return new String(cipher.doFinal(Base64.getDecoder().decode(value)), StandardCharsets.UTF_8);
    }

    /**
     * Unwraps the Nonce with the AT's private key.
     *
     * @param key the private key
     * @param nonce the Base64 Nonce
     * @return the session key
     * @throws GeneralSecurityException when it does not decrypt
     */
    private static byte[] unwrap(PrivateKey key, String nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(Base64.getDecoder().decode(nonce));
    }

    /**
     * The header sits before the body with the subuser in clear; its Nonce
     * unwraps with the AT's private key to a 16-byte session key under which
     * the Password and the Created instant, in UTC to the millisecond, decrypt;
     * each send draws a fresh session key.
     *
     * @throws Exception when a cryptographic step fails
     */
    @Test
    void theHeaderRoundTripsWithTheAtKeyPair() throws Exception {
        KeyPair pair = rsa();
        AtWsSecurity security = security("599999993/37", "dev-only-pass", certificate(pair, "SHA256withRSA", SHA256_RSA));
        assertEquals(AtWsSecurity.Status.ACTIVE, security.status());
        String message = security.secure(ENVELOPE, NOW);
        assertTrue(message.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><S:Envelope "
                + "xmlns:S=\"http://schemas.xmlsoap.org/soap/envelope/\"><S:Header><wsse:Security "
                + "xmlns:wsse=\"http://schemas.xmlsoap.org/ws/2002/12/secext\"><wsse:UsernameToken>"
                + "<wsse:Username>599999993/37</wsse:Username><wsse:Password>"));
        assertTrue(message.endsWith("</wsse:Created></wsse:UsernameToken></wsse:Security></S:Header>"
                + "<S:Body><ns2:RegisterInvoiceElem/></S:Body></S:Envelope>"));
        byte[] sessionKey = unwrap(pair.getPrivate(), element(message, "Nonce"));
        assertEquals(16, sessionKey.length);
        assertEquals("dev-only-pass", decrypt(sessionKey, element(message, "Password")));
        assertEquals("2026-10-26T08:30:00.123Z", decrypt(sessionKey, element(message, "Created")));
        String again = security.secure(ENVELOPE, NOW);
        byte[] otherKey = unwrap(pair.getPrivate(), element(again, "Nonce"));
        assertNotEquals(Base64.getEncoder().encodeToString(sessionKey), Base64.getEncoder().encodeToString(otherKey));
        assertArrayEquals("dev-only-pass".getBytes(StandardCharsets.UTF_8),
                decrypt(otherKey, element(again, "Password")).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A certificate pasted on one line, without its line breaks, still reads;
     * a subuser carrying a special character is escaped.
     *
     * @throws Exception when a cryptographic step fails
     */
    @Test
    void aCertificateOnOneLineReadsAndTheSubuserIsEscaped() throws Exception {
        KeyPair pair = rsa();
        String oneLine = certificate(pair, "SHA256withRSA", SHA256_RSA).replace("\r\n", "").replace("\n", "");
        AtWsSecurity security = security("599999993/<1>", "p", oneLine);
        assertEquals(AtWsSecurity.Status.ACTIVE, security.status());
        assertTrue(security.secure(ENVELOPE, NOW).contains("<wsse:Username>599999993/&lt;1&gt;</wsse:Username>"));
    }

    /**
     * Each missing parameter — the subuser, its password, the certificate —
     * leaves the message as it was and reads absent; an envelope without body
     * is left as it was too.
     *
     * @throws Exception when a cryptographic step fails
     */
    @Test
    void aMissingParameterLeavesTheMessageAsItWas() throws Exception {
        String certificate = certificate(rsa(), "SHA256withRSA", SHA256_RSA);
        AtWsSecurity noSubuser = security("", "p", certificate);
        AtWsSecurity noPassword = security("599999993/37", "", certificate);
        AtWsSecurity noCertificate = security("599999993/37", "p", "");
        assertEquals(AtWsSecurity.Status.ABSENT, noSubuser.status());
        assertEquals(AtWsSecurity.Status.ABSENT, noPassword.status());
        assertEquals(AtWsSecurity.Status.ABSENT, noCertificate.status());
        assertEquals(ENVELOPE, noSubuser.secure(ENVELOPE, NOW));
        assertEquals(ENVELOPE, noPassword.secure(ENVELOPE, NOW));
        assertEquals(ENVELOPE, noCertificate.secure(ENVELOPE, NOW));
        assertEquals("<S:Envelope/>", security("599999993/37", "p", certificate).secure("<S:Envelope/>", NOW));
    }

    /**
     * A text that is no certificate, and a certificate whose key is not an RSA
     * key, read unreadable and refuse to secure a message.
     *
     * @throws Exception when a cryptographic step fails
     */
    @Test
    void anUnusableCertificateIsRefused() throws Exception {
        AtWsSecurity garbage = security("599999993/37", "p", "-----BEGIN CERTIFICATE-----\nnot a certificate\n"
                + "-----END CERTIFICATE-----");
        assertEquals(AtWsSecurity.Status.UNREADABLE, garbage.status());
        assertThrows(GeneralSecurityException.class, () -> garbage.secure(ENVELOPE, NOW));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        AtWsSecurity elliptic = security("599999993/37", "p",
                certificate(generator.generateKeyPair(), "SHA256withECDSA", SHA256_ECDSA));
        assertEquals(AtWsSecurity.Status.UNREADABLE, elliptic.status());
        assertThrows(java.security.InvalidKeyException.class, () -> elliptic.secure(ENVELOPE, NOW));
    }
}
