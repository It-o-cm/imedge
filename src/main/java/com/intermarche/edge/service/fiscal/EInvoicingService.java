package com.intermarche.edge.service.fiscal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.fiscal.EInvoice;
import com.intermarche.edge.domain.sale.DocumentType;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sale.VatBreakdown;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.domain.store.Address;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.service.ExternalRelayService;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * ELECTRONIC INVOICING ({@code BO-05-01-02}, article 289 bis of the French tax
 * code): every invoice the registers issue to a professional customer subject to
 * VAT in France is converted into a STRUCTURED invoice and deposited on the
 * dematerialisation platform, which carries it to the buyer and to the public
 * invoicing portal; its life cycle comes back status by status.
 *
 * <p>THE STRUCTURED FORM is the UN/CEFACT Cross Industry Invoice, profile
 * EN 16931 — one of the three formats of the reform's base — built from the
 * invoice as the register issued it: the seller and the buyer with their SIREN
 * and VAT numbers, the lines with their net price and VAT category, the VAT
 * breakdown by rate, the totals, the due date, and the category of the
 * operation as a note. It is not a PDF; the PDF stays what the register prints.
 *
 * <p>WHO IS CONCERNED: an invoice (not a delivery note) whose customer carries a
 * SIRET or a French VAT number. A consumer's invoice and an invoice to an
 * operator established abroad are not e-invoiced: they are e-reported
 * ({@link EReportingService}).
 *
 * <p>THE PLATFORM is the service provider whose code is administered in
 * {@code fiscal.pdp-provider}: its address and its key. A deposit that fails —
 * platform down, not configured, refusing the call — is tried again after
 * {@code fiscal.transmission-retry-seconds}; nothing is ever dropped. The
 * statuses are pulled by the scheduler and may also be pushed by the platform to
 * {@code POST /api/fiscal/einvoicing/status}. Every setting is read at the tick.
 */
@ApplicationScoped
public class EInvoicingService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EInvoicingService.class);

    /** How many invoices one tick handles at most per step. */
    static final int BATCH = 50;

    /** How far back the scan looks for invoices not yet e-invoiced, in days. */
    static final int SCAN_DAYS = 31;

    /** The read timeout of a call to the platform, in milliseconds. */
    static final long TIMEOUT_MILLIS = 5000L;

    /** The refusal stated while no platform can be called. */
    static final String NO_PLATFORM = "Plateforme de dématérialisation non configurée ou injoignable.";

    /** The day as the structured invoice writes it. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** The activation, the platform, the mention and the delay. */
    @Inject
    PosSettingsService posSettingsService;

    /** The credentialed HTTP call to the platform. */
    @Inject
    ExternalRelayService externalRelayService;

    /** Reads the platform's answers. */
    @Inject
    ObjectMapper objectMapper;

    /**
     * Whether an invoice is e-invoiced: an invoice to a customer subject to VAT
     * in France.
     *
     * @param invoice the invoice, possibly null
     * @return true when its customer carries a SIRET or a French VAT number
     */
    public static boolean eligible(Invoice invoice) {
        if (invoice == null || invoice.documentType != DocumentType.FACTURE) {
            return false;
        }
        return digits(invoice.customerSiret).length() >= 9 || frenchVat(invoice.customerVatNumber);
    }

    /**
     * Whether a VAT number is French.
     *
     * @param vatNumber the number, possibly null
     * @return true when it starts with FR
     */
    static boolean frenchVat(String vatNumber) {
        return vatNumber != null && vatNumber.trim().toUpperCase(Locale.ROOT).startsWith("FR");
    }

    /**
     * One tick of the scheduler: the new invoices queued, the deposits due
     * made, the statuses due refreshed — when e-invoicing is on.
     *
     * @param now the instant of the tick
     * @return how many invoices moved
     */
    @Transactional
    @ActivateRequestContext
    public int tick(LocalDateTime now) {
        LOGGER.info("Entering method tick");
        if (!posSettingsService.einvoicingEnabled()) {
            LOGGER.info("Exiting method tick: disabled");
            return 0;
        }
        int moved = scan(now);
        for (EInvoice invoice : EInvoice.dueForDeposit(now, BATCH)) {
            moved += deposit(invoice, now) ? 1 : 0;
        }
        for (EInvoice invoice : EInvoice.dueForRefresh(now, BATCH)) {
            moved += refresh(invoice, now) ? 1 : 0;
        }
        LOGGER.info("Exiting method tick with " + moved);
        return moved;
    }

    /**
     * Queues the recent invoices concerned that are not queued yet.
     *
     * @param now the instant of the scan
     * @return how many were queued
     */
    @Transactional
    public int scan(LocalDateTime now) {
        LOGGER.info("Entering method scan");
        int queued = 0;
        for (Invoice invoice : Invoice.<Invoice>find("documentType = ?1 and issueDate >= ?2 "
                        + "and ((customerSiret is not null and customerSiret <> '') or upper(customerVatNumber) like 'FR%') "
                        + "and documentNumber not in (select e.invoiceNumber from EInvoice e) order by issueDate",
                DocumentType.FACTURE, now.minusDays(SCAN_DAYS)).page(0, BATCH).list()) {
            if (queue(invoice, now) != null) {
                queued++;
            }
        }
        LOGGER.info("Exiting method scan with " + queued);
        return queued;
    }

    /**
     * Queues one invoice: its structured form built and frozen.
     *
     * @param invoice the invoice
     * @param now the instant it is queued
     * @return the electronic invoice, or null when the invoice is not concerned
     *         or already queued
     */
    @Transactional
    public EInvoice queue(Invoice invoice, LocalDateTime now) {
        if (!eligible(invoice) || EInvoice.findByNumber(invoice.documentNumber) != null) {
            return null;
        }
        EInvoice electronic = new EInvoice();
        electronic.invoiceNumber = invoice.documentNumber;
        electronic.invoiceId = invoice.id;
        electronic.issuedAt = invoice.issueDate == null ? now : invoice.issueDate;
        electronic.buyerName = FiscalFiles.clip(invoice.customerName, 120);
        electronic.buyerSiren = siren(invoice.customerSiret);
        electronic.totalIncludingTax = invoice.totalIncludingTax == null ? BigDecimal.ZERO : invoice.totalIncludingTax;
        electronic.payload = structured(invoice);
        electronic.createdAt = now;
        electronic.move(EInvoice.Status.PENDING, now);
        electronic.persist();
        LOGGER.infof("Facture %s mise en file pour la facturation électronique", invoice.documentNumber);
        return electronic;
    }

    /**
     * Deposits one electronic invoice on the platform.
     *
     * @param invoice the electronic invoice
     * @param now the instant of the deposit
     * @return true when the platform took it
     */
    @Transactional
    public boolean deposit(EInvoice invoice, LocalDateTime now) {
        LOGGER.info("Entering method deposit with " + invoice.invoiceNumber);
        ServiceProvider platform = platform();
        if (platform == null) {
            fail(invoice, NO_PLATFORM, now);
            LOGGER.info("Exiting method deposit: no platform");
            return false;
        }
        String body = FiscalFiles.object().put("number", invoice.invoiceNumber).put("format", "UN/CEFACT CII")
                .put("profile", "EN16931").put("buyerSiren", invoice.buyerSiren)
                .put("issueDate", invoice.issuedAt.toLocalDate().toString())
                .put("totalIncludingTax", new BigDecimal(FiscalFiles.money(invoice.totalIncludingTax)))
                .put("document", invoice.payload).toString();
        ExternalRelayService.RelayResult result = externalRelayService.relay(Optional.of(platform.endpoint),
                "POST", "/invoices", body, TIMEOUT_MILLIS, platform.authKey);
        if (!result.reached() || result.status() < 200 || result.status() >= 300) {
            fail(invoice, "Dépôt refusé ou impossible (statut " + result.status() + ").", now);
            LOGGER.info("Exiting method deposit: refused");
            return false;
        }
        JsonNode answer = read(result.body());
        invoice.platformId = text(answer, "id", invoice.invoiceNumber);
        EInvoice.Status stated = EInvoice.Status.of(text(answer, "status", null));
        invoice.submittedAt = now;
        invoice.lastError = null;
        invoice.attempts = 0;
        invoice.nextAttemptAt = now.plusSeconds(posSettingsService.fiscalRetrySeconds());
        invoice.move(stated == null || stated.getCode() < 200 ? EInvoice.Status.DEPOSITED : stated, now);
        invoice.persist();
        LOGGER.info("Exiting method deposit: deposited");
        return true;
    }

    /**
     * Reads the status of a deposited invoice on the platform.
     *
     * @param invoice the electronic invoice
     * @param now the instant of the refresh
     * @return true when its status changed
     */
    @Transactional
    public boolean refresh(EInvoice invoice, LocalDateTime now) {
        LOGGER.info("Entering method refresh with " + invoice.invoiceNumber);
        ServiceProvider platform = platform();
        invoice.nextAttemptAt = now.plusSeconds(posSettingsService.fiscalRetrySeconds());
        if (platform == null || invoice.platformId == null) {
            invoice.persist();
            LOGGER.info("Exiting method refresh: nothing to ask");
            return false;
        }
        ExternalRelayService.RelayResult result = externalRelayService.relay(Optional.of(platform.endpoint),
                "GET", "/invoices/" + invoice.platformId, null, TIMEOUT_MILLIS, platform.authKey);
        if (!result.reached() || result.status() < 200 || result.status() >= 300) {
            invoice.persist();
            LOGGER.info("Exiting method refresh: no answer");
            return false;
        }
        JsonNode answer = read(result.body());
        boolean changed = apply(invoice, text(answer, "status", null), text(answer, "reason", null), now);
        LOGGER.info("Exiting method refresh");
        return changed;
    }

    /**
     * Applies a status the platform pushed ({@code POST /api/fiscal/einvoicing/status}).
     *
     * @param reference the platform's identifier or the invoice number
     * @param status the status, by code or name
     * @param reason the reason the platform gives, or null
     * @return the refusal, or null when the status was applied
     */
    @Transactional
    public String receive(String reference, String status, String reason) {
        LOGGER.info("Entering method receive with reference: " + reference + ", status: " + status);
        EInvoice invoice = reference == null ? null : EInvoice.findByPlatformId(reference);
        if (invoice == null && reference != null) {
            invoice = EInvoice.findByNumber(reference);
        }
        if (invoice == null) {
            LOGGER.info("Exiting method receive: unknown invoice");
            return "Facture électronique inconnue.";
        }
        if (EInvoice.Status.of(status) == null) {
            LOGGER.info("Exiting method receive: unknown status");
            return "Statut inconnu.";
        }
        apply(invoice, status, reason, LocalDateTime.now());
        LOGGER.info("Exiting method receive");
        return null;
    }

    /**
     * Moves an invoice to the status the platform states.
     *
     * @param invoice the electronic invoice
     * @param status the status, by code or name, possibly null
     * @param reason the reason the platform gives, or null
     * @param now the instant it changed
     * @return true when the status changed
     */
    boolean apply(EInvoice invoice, String status, String reason, LocalDateTime now) {
        EInvoice.Status next = EInvoice.Status.of(status);
        if (next == null || next == invoice.status) {
            invoice.persist();
            return false;
        }
        invoice.move(next, now);
        invoice.lastError = FiscalFiles.clip(reason, 255);
        invoice.persist();
        LOGGER.infof("Facture électronique %s : %s", invoice.invoiceNumber, next.getLabel());
        return true;
    }

    /**
     * Records a failed deposit, tried again after the administered delay.
     *
     * @param invoice the electronic invoice
     * @param error what failed
     * @param now the instant of the failure
     */
    private void fail(EInvoice invoice, String error, LocalDateTime now) {
        invoice.attempts++;
        invoice.lastError = error;
        invoice.nextAttemptAt = now.plusSeconds(posSettingsService.fiscalRetrySeconds());
        if (invoice.status != EInvoice.Status.FAILED) {
            invoice.move(EInvoice.Status.FAILED, now);
        }
        invoice.persist();
    }

    /**
     * The platform the invoices are deposited on.
     *
     * @return the administered provider when it can be called, or null
     */
    public ServiceProvider platform() {
        ServiceProvider provider = ServiceProvider.findByCode(posSettingsService.pdpProviderCode());
        return provider == null || !provider.isReachable() ? null : provider;
    }

    /**
     * Reads a JSON answer, an unreadable one reading as empty.
     *
     * @param body the answer
     * @return the tree, never null
     */
    public JsonNode read(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception unreadable) {
            return objectMapper.createObjectNode();
        }
    }

    /**
     * Reads a text field of an answer.
     *
     * @param node the answer
     * @param field the field
     * @param fallback the value when the field is absent or blank
     * @return the text
     */
    public static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }

    /**
     * Builds the structured invoice: UN/CEFACT CII, profile EN 16931.
     *
     * @param invoice the invoice as the register issued it
     * @return the XML document
     */
    public String structured(Invoice invoice) {
        List<Store> stores = Store.listAll();
        Store store = stores.isEmpty() ? null : stores.get(0);
        List<TicketLine> lines = new ArrayList<>();
        if (invoice.ticket != null) {
            for (TicketLine line : invoice.ticket.lines) {
                if (!line.cancelled && line.totalPrice != null) {
                    lines.add(line);
                }
            }
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rsm:CrossIndustryInvoice xmlns:rsm=\"urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100\""
                + " xmlns:ram=\"urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100\""
                + " xmlns:udt=\"urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100\">\n");
        xml.append("  <rsm:ExchangedDocumentContext>\n    <ram:GuidelineSpecifiedDocumentContextParameter>\n");
        tag(xml, 6, "ram:ID", "urn:cen.eu:en16931:2017");
        xml.append("    </ram:GuidelineSpecifiedDocumentContextParameter>\n  </rsm:ExchangedDocumentContext>\n");
        LocalDate issued = invoice.issueDate == null ? LocalDate.now() : invoice.issueDate.toLocalDate();
        xml.append("  <rsm:ExchangedDocument>\n");
        tag(xml, 4, "ram:ID", invoice.documentNumber);
        tag(xml, 4, "ram:TypeCode", "380");
        date(xml, 4, "ram:IssueDateTime", issued);
        String mention = posSettingsService.einvoicingMention();
        if (!mention.isEmpty()) {
            xml.append("    <ram:IncludedNote>\n");
            tag(xml, 6, "ram:Content", mention);
            tag(xml, 6, "ram:SubjectCode", "AAI");
            xml.append("    </ram:IncludedNote>\n");
        }
        xml.append("  </rsm:ExchangedDocument>\n  <rsm:SupplyChainTradeTransaction>\n");
        VatBreakdown breakdown = new VatBreakdown();
        int number = 0;
        for (TicketLine line : lines) {
            number++;
            BigDecimal rate = line.vatRate == null ? BigDecimal.ZERO : line.vatRate;
            breakdown.add(rate, line.vatNumber, line.totalPrice);
            BigDecimal net = line.totalPrice.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
            BigDecimal quantity = line.quantity == null || line.quantity.signum() == 0 ? BigDecimal.ONE : line.quantity;
            lineItem(xml, number, line.ean, line.productLabel, quantity, net, rate);
        }
        if (lines.isEmpty()) {
            BigDecimal net = invoice.totalExcludingTax == null ? BigDecimal.ZERO : invoice.totalExcludingTax;
            BigDecimal vat = invoice.totalVat == null ? BigDecimal.ZERO : invoice.totalVat;
            BigDecimal rate = net.signum() == 0 ? BigDecimal.ZERO : vat.divide(net, 3, RoundingMode.HALF_UP);
            breakdown.add(rate, net.add(vat));
            lineItem(xml, 1, null, "Ventes du ticket " + invoice.ticketNumber, BigDecimal.ONE, net, rate);
        }
        xml.append("    <ram:ApplicableHeaderTradeAgreement>\n");
        if (invoice.customerAccountNumber != null) {
            tag(xml, 6, "ram:BuyerReference", invoice.customerAccountNumber);
        }
        party(xml, "ram:SellerTradeParty", store == null ? null : firstFilled(store.legalName, store.name),
                store == null ? null : store.siret, store == null ? null : store.vatNumber,
                store == null ? null : store.address);
        party(xml, "ram:BuyerTradeParty", invoice.customerName, invoice.customerSiret, invoice.customerVatNumber,
                invoice.customerAddress);
        if (invoice.orderNumber != null && !invoice.orderNumber.isBlank()) {
            xml.append("      <ram:BuyerOrderReferencedDocument>\n");
            tag(xml, 8, "ram:IssuerAssignedID", invoice.orderNumber);
            xml.append("      </ram:BuyerOrderReferencedDocument>\n");
        }
        xml.append("    </ram:ApplicableHeaderTradeAgreement>\n");
        xml.append("    <ram:ApplicableHeaderTradeDelivery>\n      <ram:ActualDeliverySupplyChainEvent>\n");
        date(xml, 8, "ram:OccurrenceDateTime", issued);
        xml.append("      </ram:ActualDeliverySupplyChainEvent>\n    </ram:ApplicableHeaderTradeDelivery>\n");
        xml.append("    <ram:ApplicableHeaderTradeSettlement>\n");
        tag(xml, 6, "ram:InvoiceCurrencyCode", "EUR");
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            net = net.add(bucket.totalExcludingTax);
            vat = vat.add(bucket.vatAmount);
            xml.append("      <ram:ApplicableTradeTax>\n");
            tag(xml, 8, "ram:CalculatedAmount", FiscalFiles.money(bucket.vatAmount));
            tag(xml, 8, "ram:TypeCode", "VAT");
            if (bucket.rate.signum() == 0) {
                tag(xml, 8, "ram:ExemptionReason", "Exonération de TVA");
            }
            tag(xml, 8, "ram:BasisAmount", FiscalFiles.money(bucket.totalExcludingTax));
            tag(xml, 8, "ram:CategoryCode", category(bucket.rate));
            tag(xml, 8, "ram:RateApplicablePercent", percent(bucket.rate));
            xml.append("      </ram:ApplicableTradeTax>\n");
        }
        if (invoice.customerDueDate != null) {
            xml.append("      <ram:SpecifiedTradePaymentTerms>\n");
            date(xml, 8, "ram:DueDateDateTime", invoice.customerDueDate);
            xml.append("      </ram:SpecifiedTradePaymentTerms>\n");
        }
        BigDecimal gross = net.add(vat);
        xml.append("      <ram:SpecifiedTradeSettlementHeaderMonetarySummation>\n");
        tag(xml, 8, "ram:LineTotalAmount", FiscalFiles.money(net));
        tag(xml, 8, "ram:TaxBasisTotalAmount", FiscalFiles.money(net));
        xml.append("        <ram:TaxTotalAmount currencyID=\"EUR\">").append(FiscalFiles.money(vat))
                .append("</ram:TaxTotalAmount>\n");
        tag(xml, 8, "ram:GrandTotalAmount", FiscalFiles.money(gross));
        boolean deferred = invoice.customerDueDate != null;
        tag(xml, 8, "ram:TotalPrepaidAmount", FiscalFiles.money(deferred ? BigDecimal.ZERO : gross));
        tag(xml, 8, "ram:DuePayableAmount", FiscalFiles.money(deferred ? gross : BigDecimal.ZERO));
        xml.append("      </ram:SpecifiedTradeSettlementHeaderMonetarySummation>\n");
        xml.append("    </ram:ApplicableHeaderTradeSettlement>\n");
        xml.append("  </rsm:SupplyChainTradeTransaction>\n</rsm:CrossIndustryInvoice>\n");
        return xml.toString();
    }

    /**
     * Writes one line of the structured invoice.
     *
     * @param xml the buffer
     * @param number the line number
     * @param ean the product's EAN, or null
     * @param label the product's name
     * @param quantity the quantity billed
     * @param net the net line amount
     * @param rate the VAT rate, as a fraction
     */
    private void lineItem(StringBuilder xml, int number, String ean, String label, BigDecimal quantity,
            BigDecimal net, BigDecimal rate) {
        xml.append("    <ram:IncludedSupplyChainTradeLineItem>\n      <ram:AssociatedDocumentLineDocument>\n");
        tag(xml, 8, "ram:LineID", String.valueOf(number));
        xml.append("      </ram:AssociatedDocumentLineDocument>\n      <ram:SpecifiedTradeProduct>\n");
        if (ean != null && !ean.isBlank()) {
            xml.append("        <ram:GlobalID schemeID=\"0160\">").append(escape(ean)).append("</ram:GlobalID>\n");
        }
        tag(xml, 8, "ram:Name", label == null ? "Article" : label);
        xml.append("      </ram:SpecifiedTradeProduct>\n      <ram:SpecifiedLineTradeAgreement>\n");
        xml.append("        <ram:NetPriceProductTradePrice>\n");
        tag(xml, 10, "ram:ChargeAmount", net.divide(quantity.abs(), 4, RoundingMode.HALF_UP).abs().toPlainString());
        xml.append("        </ram:NetPriceProductTradePrice>\n      </ram:SpecifiedLineTradeAgreement>\n");
        xml.append("      <ram:SpecifiedLineTradeDelivery>\n        <ram:BilledQuantity unitCode=\"C62\">")
                .append(quantity.stripTrailingZeros().toPlainString()).append("</ram:BilledQuantity>\n");
        xml.append("      </ram:SpecifiedLineTradeDelivery>\n      <ram:SpecifiedLineTradeSettlement>\n");
        xml.append("        <ram:ApplicableTradeTax>\n");
        tag(xml, 10, "ram:TypeCode", "VAT");
        tag(xml, 10, "ram:CategoryCode", category(rate));
        tag(xml, 10, "ram:RateApplicablePercent", percent(rate));
        xml.append("        </ram:ApplicableTradeTax>\n");
        xml.append("        <ram:SpecifiedTradeSettlementLineMonetarySummation>\n");
        tag(xml, 10, "ram:LineTotalAmount", FiscalFiles.money(net));
        xml.append("        </ram:SpecifiedTradeSettlementLineMonetarySummation>\n");
        xml.append("      </ram:SpecifiedLineTradeSettlement>\n    </ram:IncludedSupplyChainTradeLineItem>\n");
    }

    /**
     * Writes a party: its name, SIREN, address and VAT number.
     *
     * @param xml the buffer
     * @param element the party's element
     * @param name its name, possibly null
     * @param siret its SIRET, possibly null
     * @param vatNumber its VAT number, possibly null
     * @param address its address, possibly null
     */
    private void party(StringBuilder xml, String element, String name, String siret, String vatNumber,
            Address address) {
        xml.append("      <").append(element).append(">\n");
        tag(xml, 8, "ram:Name", name == null ? "" : name);
        String siren = siren(siret);
        if (!siren.isEmpty()) {
            xml.append("        <ram:SpecifiedLegalOrganization>\n          <ram:ID schemeID=\"0002\">")
                    .append(siren).append("</ram:ID>\n        </ram:SpecifiedLegalOrganization>\n");
        }
        xml.append("        <ram:PostalTradeAddress>\n");
        if (address != null) {
            optional(xml, 10, "ram:PostcodeCode", address.postalCode);
            optional(xml, 10, "ram:LineOne", address.streetLine1);
            optional(xml, 10, "ram:CityName", address.city);
        }
        tag(xml, 10, "ram:CountryID", address == null || address.country == null || address.country.isBlank()
                ? "FR" : address.country.trim().toUpperCase(Locale.ROOT));
        xml.append("        </ram:PostalTradeAddress>\n");
        if (vatNumber != null && !vatNumber.isBlank()) {
            xml.append("        <ram:SpecifiedTaxRegistration>\n          <ram:ID schemeID=\"VA\">")
                    .append(escape(vatNumber.replace(" ", ""))).append("</ram:ID>\n        </ram:SpecifiedTaxRegistration>\n");
        }
        xml.append("      </").append(element).append(">\n");
    }

    /**
     * Writes an element when its value is filled.
     *
     * @param xml the buffer
     * @param indent the indentation
     * @param name the element
     * @param value the value, possibly null or blank
     */
    private static void optional(StringBuilder xml, int indent, String name, String value) {
        if (value != null && !value.isBlank()) {
            tag(xml, indent, name, value);
        }
    }

    /**
     * Writes a date element in the format 102 (yyyymmdd).
     *
     * @param xml the buffer
     * @param indent the indentation
     * @param name the element
     * @param day the day
     */
    private static void date(StringBuilder xml, int indent, String name, LocalDate day) {
        String pad = " ".repeat(indent);
        xml.append(pad).append('<').append(name).append(">\n").append(pad)
                .append("  <udt:DateTimeString format=\"102\">").append(day.format(DAY))
                .append("</udt:DateTimeString>\n").append(pad).append("</").append(name).append(">\n");
    }

    /**
     * Writes one element, its value escaped.
     *
     * @param xml the buffer
     * @param indent the indentation
     * @param name the element
     * @param value the value
     */
    private static void tag(StringBuilder xml, int indent, String name, String value) {
        xml.append(" ".repeat(indent)).append('<').append(name).append('>').append(escape(value))
                .append("</").append(name).append(">\n");
    }

    /**
     * Escapes a value for XML.
     *
     * @param value the value, possibly null
     * @return the escaped value, empty for null
     */
    static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * The VAT category of a rate: standard, or exempt at zero.
     *
     * @param rate the rate, as a fraction
     * @return {@code S} or {@code E}
     */
    static String category(BigDecimal rate) {
        return rate.signum() == 0 ? "E" : "S";
    }

    /**
     * A rate written as a percentage.
     *
     * @param rate the rate, as a fraction
     * @return the percentage, such as 20 or 5.5
     */
    static String percent(BigDecimal rate) {
        return rate.movePointRight(2).stripTrailingZeros().toPlainString();
    }

    /**
     * The SIREN of a SIRET: its nine first digits.
     *
     * @param siret the SIRET, possibly null
     * @return the SIREN, empty when fewer than nine digits are stated
     */
    static String siren(String siret) {
        String digits = digits(siret);
        return digits.length() < 9 ? "" : digits.substring(0, 9);
    }

    /**
     * The digits of a number.
     *
     * @param value the number, possibly null
     * @return its digits, empty when none
     */
    static String digits(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    /**
     * The first value that is filled.
     *
     * @param value the preferred value, possibly null or blank
     * @param fallback the value used otherwise
     * @return the value, or the fallback
     */
    static String firstFilled(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
