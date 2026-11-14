package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.service.sync.SyncPayloads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * States an integrated E-COMMERCE transaction on its Portuguese fiscal
 * document, so the SAF-T file of the store includes it (BO-07-01-06).
 *
 * <p>A sale of the lanes is stated by the register that made it; an order of
 * the e-commerce platform reaches no register, so its document is the store
 * node's to state, in one of two ways:
 * <ul>
 *   <li>the platform sent the document it issued itself — the node COPIES it
 *       as issued, marked as integrated from another application
 *       ({@code SourceBilling I});</li>
 *   <li>it sent none — the node ISSUES a simplified invoice in the series of
 *       the virtual register and the year, numbered after the last one,
 *       carrying the administered ATCUD code and SAF-T identifier, and signed
 *       SHA-1 with RSA, chained to the previous document of its series, like a
 *       register's.</li>
 * </ul>
 * Either way the document states the NIF the order carried, the final
 * consumer's when it carried none (BO-07-01-07, -08).
 */
@ApplicationScoped
public class EcommerceFiscalService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EcommerceFiscalService.class);

    /** The moment a document was recorded, as the signature states it. */
    private static final DateTimeFormatter ENTRY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** The date of a document as the QR code states it. */
    private static final DateTimeFormatter QR_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** The tax number a document states for a customer who gave none. */
    static final String FINAL_CONSUMER = "999999990";

    /** The administered ATCUD codes, SAF-T identifiers and certificate. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * The node's signing key, Base64 PKCS#8. Absent, a key is generated for the
     * life of the process, which suits a development node and nothing else.
     */
    @ConfigProperty(name = "edge.fiscal.pt.private-key")
    Optional<String> privateKey;

    /** The signing key, resolved on first use. */
    private PrivateKey signingKey;

    /**
     * The document stating an integrated order.
     *
     * @param invoice the document the platform issued, or null
     * @param ticket the ticket the order is booked as, its lines and totals set
     * @return the document, in the push shape the ingestion stores
     */
    public SyncPayloads.FiscalDocumentDto document(InvoiceDto invoice, SyncPayloads.TicketDto ticket) {
        LOGGER.info("Entering method document with ticket: " + ticket.ticketNumber);
        SyncPayloads.FiscalDocumentDto document = invoice != null && filled(invoice.number)
                ? copied(invoice, ticket) : issued(ticket);
        LOGGER.info("Exiting method document with " + document.number);
        return document;
    }

    /**
     * Copies the document the platform issued, as issued.
     *
     * @param invoice the platform's document
     * @param ticket the booked ticket
     * @return the document
     */
    SyncPayloads.FiscalDocumentDto copied(InvoiceDto invoice, SyncPayloads.TicketDto ticket) {
        SyncPayloads.FiscalDocumentDto document = base(ticket);
        String number = invoice.number.trim();
        document.documentType = "FT".equalsIgnoreCase(trimmed(invoice.type)) ? "FT" : "FS";
        document.number = number;
        int slash = number.lastIndexOf('/');
        document.series = slash < 0 ? ticket.terminalId : number.substring(number.lastIndexOf(' ') + 1, slash);
        document.rank = slash < 0 ? 0L : rank(number.substring(slash + 1));
        document.atcud = filled(invoice.atcud) ? invoice.atcud.trim() : "0-" + document.rank;
        document.issuedAt = filled(invoice.issuedAt) ? invoice.issuedAt.trim() : ticket.closingDate;
        document.hash = filled(invoice.hash) ? invoice.hash.trim() : "0";
        document.previousHash = "";
        document.sourceBilling = "I";
        document.qrPayload = qrPayload(document, ticket);
        return document;
    }

    /**
     * Issues a simplified invoice in the series of the virtual register.
     *
     * @param ticket the booked ticket
     * @return the document
     */
    SyncPayloads.FiscalDocumentDto issued(SyncPayloads.TicketDto ticket) {
        SyncPayloads.FiscalDocumentDto document = base(ticket);
        LocalDateTime issuedAt = LocalDateTime.parse(ticket.closingDate).withNano(0);
        document.documentType = "FS";
        document.series = ticket.terminalId + issuedAt.getYear();
        FiscalDocument previous = last(document.series);
        document.rank = previous == null ? 1L : previous.rank + 1L;
        document.number = "FS " + document.series + "/" + document.rank;
        document.atcud = posSettingsService.atcudCode("FS") + "-" + document.rank;
        document.saftDocumentId = posSettingsService.saftDocumentId("FS");
        document.issuedAt = issuedAt.toString();
        document.previousHash = previous == null ? "" : previous.hash;
        document.hash = sign(String.join(";", issuedAt.toLocalDate().toString(),
                issuedAt.format(ENTRY_DATE), document.number, amount(document.grossTotal),
                document.previousHash));
        document.qrPayload = qrPayload(document, ticket);
        return document;
    }

    /**
     * The fields every document of an order shares: register, customer, totals.
     *
     * @param ticket the booked ticket
     * @return the partly filled document
     */
    private static SyncPayloads.FiscalDocumentDto base(SyncPayloads.TicketDto ticket) {
        SyncPayloads.FiscalDocumentDto document = new SyncPayloads.FiscalDocumentDto();
        document.terminalId = ticket.terminalId;
        document.customerTaxId = ticket.customerTaxId;
        document.grossTotal = ticket.totalIncludingTax;
        document.taxTotal = ticket.totalVat;
        return document;
    }

    /**
     * The payload of the fiscal QR code (Portaria 195/2020) of a document.
     *
     * @param document the numbered document
     * @param ticket the ticket it states, for its VAT ventilation
     * @return the payload
     */
    String qrPayload(SyncPayloads.FiscalDocumentDto document, SyncPayloads.TicketDto ticket) {
        List<Store> stores = stores();
        String issuer = stores.isEmpty() ? "" : digits(stores.get(0).vatNumber);
        boolean finalConsumer = FiscalDocument.isFinalConsumer(document.customerTaxId);
        BigDecimal[] bases = new BigDecimal[4];
        BigDecimal[] taxes = new BigDecimal[4];
        for (SyncPayloads.LineDto line : ticket.lines) {
            BigDecimal rate = line.vatRate == null ? BigDecimal.ZERO : line.vatRate;
            BigDecimal net = line.totalPrice.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
            int rateClass = rateClass(rate);
            bases[rateClass] = add(bases[rateClass], net);
            taxes[rateClass] = add(taxes[rateClass], line.totalPrice.subtract(net));
        }
        StringBuilder qr = new StringBuilder();
        qr.append("A:").append(issuer)
                .append("*B:").append(finalConsumer ? FINAL_CONSUMER : FiscalDocument.numberOf(document.customerTaxId))
                .append("*C:").append(finalConsumer ? "PT" : FiscalDocument.countryOf(document.customerTaxId))
                .append("*D:").append(document.documentType)
                .append("*E:N*F:").append(LocalDateTime.parse(document.issuedAt).format(QR_DATE))
                .append("*G:").append(document.number)
                .append("*H:").append(document.atcud)
                .append("*I1:PT");
        if (bases[0] != null) {
            qr.append("*I2:").append(amount(bases[0]));
        }
        for (int rateClass = 1; rateClass < 4; rateClass++) {
            if (bases[rateClass] != null) {
                qr.append("*I").append(rateClass * 2 + 1).append(':').append(amount(bases[rateClass]))
                        .append("*I").append(rateClass * 2 + 2).append(':').append(amount(taxes[rateClass]));
            }
        }
        String certificate = posSettingsService.fiscalCertificateNumber();
        String number = certificate == null ? "" : certificate.trim();
        if (number.toUpperCase(Locale.ROOT).endsWith("/AT")) {
            number = number.substring(0, number.length() - 3).trim();
        }
        qr.append("*N:").append(amount(document.taxTotal))
                .append("*O:").append(amount(document.grossTotal))
                .append("*Q:").append(hashCharacters(document.hash))
                .append("*R:").append(number.isEmpty() ? "0" : number);
        return qr.toString();
    }

    /**
     * The four characters of a signature a document prints: the 1st, 11th,
     * 21st and 31st.
     *
     * @param hash the signature
     * @return the characters, fewer when the signature is shorter
     */
    static String hashCharacters(String hash) {
        StringBuilder characters = new StringBuilder();
        for (int position = 0; position < 31 && position < hash.length(); position += 10) {
            characters.append(hash.charAt(position));
        }
        return characters.toString();
    }

    /**
     * Signs the chained data of a document, SHA-1 with RSA.
     *
     * @param data the data
     * @return the signature, Base64
     */
    String sign(String data) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(signingKey());
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signature du document fiscal impossible", e);
        }
    }

    /**
     * The node's signing key: the configured one, or a key generated for the
     * life of the process.
     *
     * @return the key
     * @throws GeneralSecurityException when the configured key cannot be read
     */
    synchronized PrivateKey signingKey() throws GeneralSecurityException {
        if (signingKey == null) {
            if (privateKey.isPresent()) {
                signingKey = KeyFactory.getInstance("RSA").generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.get().trim())));
            } else {
                LOGGER.warn("Aucune clé de signature fiscale configurée (edge.fiscal.pt.private-key) : clé de développement générée");
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(1024);
                signingKey = generator.generateKeyPair().getPrivate();
            }
        }
        return signingKey;
    }

    /**
     * The last document of a series.
     *
     * @param series the series
     * @return the document, or null for a new series
     */
    FiscalDocument last(String series) {
        return FiscalDocument.lastOf(FiscalDocument.Type.FS, series);
    }

    /**
     * The stores of the node, the first one issuing.
     *
     * @return the stores
     */
    List<Store> stores() {
        return Store.listAll();
    }

    /**
     * Reads a rank, zero when it is not a number.
     *
     * @param text the text after the slash of a number
     * @return the rank
     */
    private static long rank(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    /**
     * The Portuguese rate class of a VAT rate.
     *
     * @param rate the rate, as a fraction
     * @return 0 exempt, 1 reduced, 2 intermediate, 3 normal
     */
    private static int rateClass(BigDecimal rate) {
        if (rate.signum() == 0) {
            return 0;
        }
        if (rate.compareTo(new BigDecimal("0.10")) < 0) {
            return 1;
        }
        return rate.compareTo(new BigDecimal("0.18")) < 0 ? 2 : 3;
    }

    /**
     * Adds an amount to a running total that may not have started.
     *
     * @param total the running total, or null
     * @param value the amount
     * @return the new total
     */
    private static BigDecimal add(BigDecimal total, BigDecimal value) {
        return total == null ? value : total.add(value);
    }

    /**
     * Whether a text carries something.
     *
     * @param text the text, or null
     * @return true when not blank
     */
    private static boolean filled(String text) {
        return text != null && !text.isBlank();
    }

    /**
     * A text trimmed, empty for null.
     *
     * @param text the text, or null
     * @return the trimmed text
     */
    private static String trimmed(String text) {
        return text == null ? "" : text.trim();
    }

    /**
     * Keeps only the digits of a tax number.
     *
     * @param taxId the tax number, or null
     * @return its digits
     */
    private static String digits(String taxId) {
        return taxId == null ? "" : taxId.replaceAll("[^0-9]", "");
    }

    /**
     * An amount with two decimals and a dot.
     *
     * @param value the amount
     * @return the text
     */
    private static String amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** The fiscal document the e-commerce platform issued itself, as it sends it. */
    public static class InvoiceDto {

        /** FS or FT, FS when absent. */
        public String type;

        /** The document number as issued, {@code TYPE SERIES/RANK}. */
        public String number;

        /** Its ATCUD. */
        public String atcud;

        /** Its signature. */
        public String hash;

        /** When it was issued, ISO-8601, or null for the order's date. */
        public String issuedAt;
    }
}
