package com.intermarche.edge.service.fiscal;

import com.intermarche.edge.domain.fiscal.SaftTransmission;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.VatBreakdown;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.service.ExternalRelayService;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.export.SaftExportService;
import com.intermarche.edge.service.sync.ThirdPartyFormats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The SAF-T (PT) AT THE FLOW ({@code BO-05-01-05}) and the real-time
 * communication to the tax administration ({@code BO-11-04-02}): every
 * Portuguese fiscal document a register pushes — FS, FT, NC, RC — is queued as
 * soon as the node consolidates it, and made available two ways.
 *
 * <p>PUSHED to every fiscal endpoint in service — a declared provider of
 * protocol SAFT, with its address and key — one row per document and endpoint,
 * queued in the ingestion's own transaction and sent by the fiscal scheduler,
 * ONCE: a document its register pushes again is never sent twice to the same
 * endpoint. The wire format follows the endpoint: the tax administration
 * itself (a provider of type Fiscal) receives the SOAP {@code RegisterInvoice}
 * message of its invoice web service, with the WS-Security header of the AT
 * drawn at the send ({@link AtWsSecurity}), for the invoices and credit notes only —
 * a receipt is no invoice; any other endpoint receives the document's SAF-T
 * (PT) file in a JSON envelope. A failed send is tried again after
 * {@code fiscal.transmission-retry-seconds} and relaunched by hand from the
 * SAF-T screen, nothing is ever dropped.
 *
 * <p>PULLED by the API {@code GET /api/fiscal/saft/documents?since=…}, which an
 * endpoint holding a SAFT provider's key reads at its own pace: the documents
 * issued since an instant, each with its SAF-T file.
 *
 * <p>The node states, it never issues: the file of a document is drawn by
 * {@link SaftExportService} from the document as the register numbered and
 * signed it. The activation {@code fiscal.saft-realtime} is read at every
 * consolidation and every tick.
 */
@ApplicationScoped
public class SaftStreamService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SaftStreamService.class);

    /** How many sends one tick makes at most. */
    static final int BATCH = 50;

    /** The read timeout of a send, in milliseconds. */
    static final long TIMEOUT_MILLIS = 5000L;

    /** The media type of the tax administration's SOAP web service. */
    static final String SOAP_MEDIA_TYPE = "text/xml; charset=UTF-8";

    /** The return code of the tax administration's answer. */
    private static final java.util.regex.Pattern SOAP_CODE =
            java.util.regex.Pattern.compile("<(?:\\w+:)?ReturnCode>([^<]*)<");

    /** The return message of the tax administration's answer. */
    private static final java.util.regex.Pattern SOAP_MESSAGE =
            java.util.regex.Pattern.compile("<(?:\\w+:)?ReturnMessage>([^<]*)<");

    /** The activation and the retry delay. */
    @Inject
    PosSettingsService posSettingsService;

    /** Draws the SAF-T file of a document. */
    @Inject
    SaftExportService saftExportService;

    /** The credentialed HTTP call to the endpoints. */
    @Inject
    ExternalRelayService externalRelayService;

    /** The WS-Security header of the tax administration's messages (BO-11-04-02). */
    @Inject
    AtWsSecurity atWsSecurity;

    /** Why a message to the tax administration could not be secured (BO-11-04-02). */
    static final String AT_CERTIFICATE_UNREADABLE =
            "Certificat de la clé publique de l'AT illisible : en-tête WS-Security impossible.";

    /**
     * Queues a document just consolidated for every fiscal endpoint in service
     * — called from the ingestion, in its transaction.
     *
     * <p>A document pushed again by its register is queued once per endpoint:
     * a row still waiting is rewritten with the document as it now stands, a row
     * already sent is left alone.
     *
     * @param document the consolidated document
     * @return how many rows were queued or rewritten
     */
    public int onDocument(FiscalDocument document) {
        LOGGER.info("Entering method onDocument");
        if (document == null || !posSettingsService.saftRealtime()) {
            LOGGER.info("Exiting method onDocument: nothing to queue");
            return 0;
        }
        List<ServiceProvider> endpoints = endpoints();
        if (endpoints.isEmpty()) {
            LOGGER.info("Exiting method onDocument: no endpoint");
            return 0;
        }
        int queued = 0;
        for (ServiceProvider endpoint : endpoints) {
            boolean administration = taxAdministration(endpoint);
            if (administration && document.documentType == FiscalDocument.Type.RC) {
                continue;
            }
            String uid = SaftTransmission.uidOf(endpoint.code, document.number);
            SaftTransmission row = SaftTransmission.findByUid(uid);
            if (row != null && row.status == SaftTransmission.Status.SENT) {
                continue;
            }
            if (row == null) {
                row = new SaftTransmission();
                row.uid = uid;
                row.providerCode = endpoint.code;
                row.documentNumber = document.number;
                row.createdAt = LocalDateTime.now();
            }
            row.documentType = document.documentType.name();
            row.issuedAt = document.issuedAt;
            row.payload = administration ? registerInvoiceOf(document) : fileOf(document);
            row.persist();
            queued++;
        }
        LOGGER.info("Exiting method onDocument with " + queued + " row(s)");
        return queued;
    }

    /**
     * The fiscal endpoints in service: the providers of protocol SAFT that can
     * be called.
     *
     * @return the endpoints
     */
    public List<ServiceProvider> endpoints() {
        List<ServiceProvider> endpoints = new ArrayList<>();
        for (ServiceProvider provider : ServiceProvider.listActiveOfProtocol(ServiceProvider.ProviderProtocol.SAFT)) {
            if (provider.isReachable()) {
                endpoints.add(provider);
            }
        }
        return endpoints;
    }

    /**
     * Tells whether an endpoint is the tax administration itself, which speaks
     * its invoice web service rather than the SAF-T envelope
     * ({@code BO-11-04-02}).
     *
     * @param endpoint the endpoint
     * @return true for a provider of type Fiscal
     */
    static boolean taxAdministration(ServiceProvider endpoint) {
        return endpoint.providerType == ServiceProvider.ProviderType.FISCAL;
    }

    /**
     * The RegisterInvoice message of one document ({@code BO-11-04-02}): its VAT
     * ventilation drawn from the sale lines it covers, or from the refund lines
     * of a credit note, and the issuer's tax number, that of the store of the
     * sale (the original sale of a refund) reduced to its digits.
     *
     * @param document the document
     * @return the SOAP envelope
     */
    String registerInvoiceOf(FiscalDocument document) {
        VatBreakdown breakdown = new VatBreakdown();
        Ticket sale = null;
        if (document.refundId != null) {
            Refund refund = Refund.findById(document.refundId);
            if (refund != null) {
                breakdown = ThirdPartyFormats.refundBreakdown(refund.lines);
                sale = refund.originalTicketId == null ? null : Ticket.findById(refund.originalTicketId);
            }
        } else if (document.ticketId != null) {
            sale = Ticket.findById(document.ticketId);
            breakdown = sale == null ? breakdown : ThirdPartyFormats.ticketBreakdown(document, sale.lines);
        }
        String issuer = sale == null || sale.store == null || sale.store.vatNumber == null
                ? "" : sale.store.vatNumber.replaceAll("\\D", "");
        return ThirdPartyFormats.registerInvoice(document, breakdown, issuer);
    }

    /**
     * The SAF-T file of one document.
     *
     * @param document the document
     * @return the XML file
     */
    public String fileOf(FiscalDocument document) {
        LocalDate day = document.issuedAt.toLocalDate();
        return saftExportService.exportDocuments(List.of(document), day, day, LocalDateTime.now());
    }

    /**
     * One tick of the scheduler: the sends due made, when SAF-T at the flow is on.
     *
     * @param now the instant of the tick
     * @return how many rows were sent
     */
    @Transactional
    @ActivateRequestContext
    public int tick(LocalDateTime now) {
        LOGGER.info("Entering method tick");
        if (!posSettingsService.saftRealtime()) {
            LOGGER.info("Exiting method tick: disabled");
            return 0;
        }
        int sent = 0;
        for (SaftTransmission row : SaftTransmission.due(now, BATCH)) {
            sent += send(row, now) ? 1 : 0;
        }
        LOGGER.info("Exiting method tick with " + sent);
        return sent;
    }

    /**
     * Sends one row to its endpoint: the RegisterInvoice message as it is to
     * the tax administration, the SAF-T file in its JSON envelope to the others.
     *
     * @param row the row
     * @param now the instant of the send
     * @return true when the endpoint took it
     */
    @Transactional
    public boolean send(SaftTransmission row, LocalDateTime now) {
        ServiceProvider endpoint = ServiceProvider.findByCode(row.providerCode);
        if (endpoint == null || !endpoint.isReachable()) {
            fail(row, "Point d'accès fiscal hors service ou non configuré.", now);
            return false;
        }
        ExternalRelayService.RelayResult result;
        if (taxAdministration(endpoint)) {
            // BO-11-04-02: the WS-Security header is drawn at the send, never stored.
            String message;
            try {
                message = atWsSecurity.secure(row.payload, now.atZone(java.time.ZoneId.systemDefault()).toInstant());
            } catch (java.security.GeneralSecurityException | IllegalArgumentException unreadable) {
                fail(row, AT_CERTIFICATE_UNREADABLE, now);
                return false;
            }
            result = externalRelayService.relay(Optional.of(endpoint.endpoint), "POST", "", message,
                    TIMEOUT_MILLIS, endpoint.authKey, SOAP_MEDIA_TYPE);
        } else {
            String body = FiscalFiles.object().put("documentNumber", row.documentNumber)
                    .put("documentType", row.documentType).put("issuedAt", row.issuedAt.toString())
                    .put("saft", row.payload).toString();
            result = externalRelayService.relay(Optional.of(endpoint.endpoint),
                    "POST", "/saft/documents", body, TIMEOUT_MILLIS, endpoint.authKey);
        }
        if (!result.reached() || result.status() < 200 || result.status() >= 300) {
            fail(row, "Envoi refusé ou impossible (statut " + result.status() + ").", now);
            return false;
        }
        row.status = SaftTransmission.Status.SENT;
        row.sentAt = now;
        row.lastError = null;
        row.acknowledgement = FiscalFiles.clip(taxAdministration(endpoint)
                ? soapAcknowledgement(result.body()) : FiscalFiles.acknowledgement(result.body()), 255);
        row.persist();
        LOGGER.infof("Document %s envoyé en SAF-T à %s", row.documentNumber, row.providerCode);
        return true;
    }

    /**
     * The acknowledgement the tax administration answered, as an operator
     * reads it: its return code and message rather than the SOAP envelope,
     * the answer as it came when it carries neither.
     *
     * @param body the answer's body, possibly null
     * @return the acknowledgement, null for no body
     */
    static String soapAcknowledgement(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        java.util.regex.Matcher code = SOAP_CODE.matcher(body);
        java.util.regex.Matcher message = SOAP_MESSAGE.matcher(body);
        String returned = code.find() ? code.group(1).trim() : null;
        String said = message.find() ? message.group(1).trim() : null;
        if (returned == null) {
            return said == null ? body.trim() : said;
        }
        return said == null ? returned : returned + " — " + said;
    }

    /**
     * Relaunches every failed send at once, from the SAF-T screen.
     *
     * @return how many rows were relaunched
     */
    @Transactional
    public int relaunch() {
        LOGGER.info("Entering method relaunch");
        int relaunched = 0;
        for (SaftTransmission row : SaftTransmission.<SaftTransmission>list("status", SaftTransmission.Status.FAILED)) {
            row.nextAttemptAt = null;
            row.persist();
            relaunched++;
        }
        LOGGER.info("Exiting method relaunch with " + relaunched);
        return relaunched;
    }

    /**
     * Records a failed send, tried again after the administered delay.
     *
     * @param row the row
     * @param error what failed
     * @param now the instant of the failure
     */
    private void fail(SaftTransmission row, String error, LocalDateTime now) {
        row.attempts++;
        row.status = SaftTransmission.Status.FAILED;
        row.lastError = error;
        row.nextAttemptAt = now.plusSeconds(posSettingsService.fiscalRetrySeconds());
        row.persist();
    }

    /**
     * The documents issued since an instant, each with its SAF-T file — what the
     * API serves ({@code BO-05-01-05}).
     *
     * @param since the instant, exclusive
     * @param limit how many at most
     * @return the documents and their files, oldest first
     */
    @Transactional
    public List<Object[]> documentsSince(LocalDateTime since, int limit) {
        LOGGER.info("Entering method documentsSince with since: " + since);
        List<Object[]> documents = new ArrayList<>();
        for (FiscalDocument document : FiscalDocument.<FiscalDocument>find(
                "issuedAt > ?1 order by issuedAt, id", since).page(0, limit).list()) {
            documents.add(new Object[] {document, fileOf(document)});
        }
        LOGGER.info("Exiting method documentsSince with " + documents.size() + " document(s)");
        return documents;
    }

    /**
     * Whether a key is the key of a fiscal endpoint in service.
     *
     * @param key the key presented, possibly null
     * @return true when an active SAFT provider carries it
     */
    public boolean authorized(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        for (ServiceProvider provider : ServiceProvider.listActiveOfProtocol(ServiceProvider.ProviderProtocol.SAFT)) {
            if (provider.hasCredential() && provider.authKey.equals(key.trim())) {
                return true;
            }
        }
        return false;
    }
}
