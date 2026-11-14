package com.intermarche.edge.service.export;

import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Draws the PORTUGUESE SAF-T export of the store (BO-10-06-03): the audit file
 * of the period, in the structure of the official schema, from the fiscal
 * documents the registers issued and pushed with their sales and returns.
 *
 * <p>THE NODE STATES, IT NEVER ISSUES. Every document of the file is the one
 * the register numbered, signed and printed — its number, ATCUD, chained
 * signature and totals are copied, never recomputed. What the node adds is
 * the file around them: the header naming the company and the software's
 * certification number administered in {@code fiscal.certificate-number}, the
 * customers, the products and the tax table the documents refer to.
 *
 * <p>The receipts (RC) are the payments section of the schema
 * (BO-05-01-04, BO-05-01-05); the simplified invoices, invoices and credit
 * notes are the sales section. The numeric document identifiers administered
 * per type ({@code fiscal.saft-id-fs}, {@code fiscal.saft-id-nc}, BO-10-06-01
 * and -02) have no element of their own in the schema: they are stated in
 * the header comment, as {@code FS=1;NC=2}.
 */
@ApplicationScoped
public class SaftExportService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SaftExportService.class);

    /** The version of the schema the file follows. */
    public static final String AUDIT_FILE_VERSION = "1.04_01";

    /** The tax number standing for a customer who gave none. */
    static final String FINAL_CONSUMER = "999999990";

    /** The customer identifier of the final consumer. */
    static final String FINAL_CONSUMER_ID = "Consumidor final";

    /** What the schema states for an unknown address part. */
    private static final String UNKNOWN = "Desconhecido";

    /** The product code of a line whose article carries no code. */
    static final String NO_PRODUCT_CODE = "DIVERSOS";

    /** A date as the schema writes it. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** A timestamp as the schema writes it. */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** The certification number and the document identifiers. */
    @Inject
    PosSettingsService posSettingsService;

    /** The tax number of the software producer. */
    @ConfigProperty(name = "edge.saft.product-company-tax-id", defaultValue = "999999990")
    String productCompanyTaxId;

    /** The software's name, as certified. */
    @ConfigProperty(name = "edge.saft.product-id", defaultValue = "impos/Intermarche")
    String productId;

    /** The software's version. */
    @ConfigProperty(name = "edge.saft.product-version", defaultValue = "1.0")
    String productVersion;

    /**
     * One line of a document, as the file states it.
     *
     * @param code the product code
     * @param description the product description
     * @param quantity the quantity, positive
     * @param gross the line amount tax included, positive
     * @param rate the VAT rate, as a fraction
     * @param vatNumber the number of the regime the line was taxed under, or null
     */
    record SaftLine(String code, String description, BigDecimal quantity, BigDecimal gross, BigDecimal rate,
                    Integer vatNumber) {
    }

    /**
     * One document of the file with its lines.
     *
     * @param document the fiscal document
     * @param lines its lines
     */
    record SaftInvoice(FiscalDocument document, List<SaftLine> lines) {
    }

    /**
     * Draws the audit file of a period.
     *
     * @param from the first day of the period
     * @param to the last day of the period, inclusive
     * @param created the instant the file is drawn
     * @return the XML file, UTF-8 text
     */
    public String export(LocalDate from, LocalDate to, LocalDateTime created) {
        LOGGER.info("Entering method export with from: " + from + ", to: " + to);
        String xml = exportDocuments(
                FiscalDocument.issuedBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay()), from, to, created);
        LOGGER.info("Exiting method export");
        return xml;
    }

    /**
     * Draws the audit file of a given set of documents — a period, or the one
     * document transmitted at the flow (BO-05-01-05).
     *
     * @param documents the documents, in the order the file states them
     * @param from the first day the file covers
     * @param to the last day the file covers, inclusive
     * @param created the instant the file is drawn
     * @return the XML file, UTF-8 text
     */
    public String exportDocuments(List<FiscalDocument> documents, LocalDate from, LocalDate to,
            LocalDateTime created) {
        LOGGER.info("Entering method exportDocuments with " + documents.size() + " document(s)");
        List<SaftInvoice> invoices = new ArrayList<>();
        List<FiscalDocument> receipts = new ArrayList<>();
        for (FiscalDocument document : documents) {
            if (document.documentType == FiscalDocument.Type.RC) {
                receipts.add(document);
            } else {
                invoices.add(new SaftInvoice(document, linesOf(document)));
            }
        }
        List<Store> stores = Store.listAll();
        Store store = stores.isEmpty() ? null : stores.get(0);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<AuditFile xmlns=\"urn:OECD:StandardAuditFile-Tax:PT_1.04_01\">\n");
        header(xml, store, from, to, created, invoices);
        List<SaftInvoice> referenced = new ArrayList<>(invoices);
        for (FiscalDocument receipt : receipts) {
            referenced.add(new SaftInvoice(receipt, List.of()));
        }
        masterFiles(xml, referenced);
        xml.append("  <SourceDocuments>\n");
        salesInvoices(xml, invoices);
        if (!receipts.isEmpty()) {
            payments(xml, receipts);
        }
        xml.append("  </SourceDocuments>\n");
        xml.append("</AuditFile>\n");
        LOGGER.info("Exiting method exportDocuments with " + invoices.size() + " invoice(s) and "
                + receipts.size() + " receipt(s)");
        return xml.toString();
    }

    /**
     * Writes the payments section: the receipts (RC) issued against the
     * invoices (BO-05-01-04, BO-05-01-05).
     *
     * @param xml the file buffer
     * @param receipts the receipts of the file, at least one
     */
    private void payments(StringBuilder xml, List<FiscalDocument> receipts) {
        BigDecimal credit = BigDecimal.ZERO;
        for (FiscalDocument receipt : receipts) {
            if (!receipt.cancelled) {
                credit = credit.add(receipt.grossTotal.abs());
            }
        }
        xml.append("    <Payments>\n");
        element(xml, 6, "NumberOfEntries", String.valueOf(receipts.size()));
        element(xml, 6, "TotalDebit", amount(BigDecimal.ZERO));
        element(xml, 6, "TotalCredit", amount(credit));
        for (FiscalDocument receipt : receipts) {
            String issued = receipt.issuedAt.format(DATE_TIME);
            String day = receipt.issuedAt.toLocalDate().format(DATE);
            String gross = amount(receipt.grossTotal.abs());
            FiscalDocument source = receipt.sourceNumber == null ? null
                    : FiscalDocument.findByNumber(receipt.sourceNumber);
            xml.append("      <Payment>\n");
            element(xml, 8, "PaymentRefNo", receipt.number);
            element(xml, 8, "ATCUD", receipt.atcud);
            element(xml, 8, "Period", String.valueOf(receipt.issuedAt.getMonthValue()));
            element(xml, 8, "TransactionDate", day);
            element(xml, 8, "PaymentType", "RG");
            xml.append("        <DocumentStatus>\n");
            element(xml, 10, "PaymentStatus", receipt.cancelled ? "A" : "N");
            element(xml, 10, "PaymentStatusDate", issued);
            element(xml, 10, "SourceID", receipt.terminalId);
            element(xml, 10, "SourcePayment", "P");
            xml.append("        </DocumentStatus>\n");
            xml.append("        <PaymentMethod>\n");
            element(xml, 10, "PaymentMechanism", "NU");
            element(xml, 10, "PaymentAmount", gross);
            element(xml, 10, "PaymentDate", day);
            xml.append("        </PaymentMethod>\n");
            element(xml, 8, "SourceID", receipt.terminalId);
            element(xml, 8, "SystemEntryDate", issued);
            element(xml, 8, "CustomerID", customerId(receipt));
            xml.append("        <Line>\n");
            element(xml, 10, "LineNumber", "1");
            xml.append("          <SourceDocumentID>\n");
            element(xml, 12, "OriginatingON", receipt.sourceNumber == null ? receipt.number : receipt.sourceNumber);
            element(xml, 12, "InvoiceDate", source == null ? day : source.issuedAt.toLocalDate().format(DATE));
            xml.append("          </SourceDocumentID>\n");
            element(xml, 10, "CreditAmount", gross);
            xml.append("        </Line>\n");
            xml.append("        <DocumentTotals>\n");
            element(xml, 10, "TaxPayable", amount(BigDecimal.ZERO));
            element(xml, 10, "NetTotal", gross);
            element(xml, 10, "GrossTotal", gross);
            xml.append("        </DocumentTotals>\n");
            xml.append("      </Payment>\n");
        }
        xml.append("    </Payments>\n");
    }

    /**
     * The name of the file of a period, after the company's tax number.
     *
     * @param from the first day of the period
     * @param to the last day of the period
     * @return the file name
     */
    public String fileName(LocalDate from, LocalDate to) {
        List<Store> stores = Store.listAll();
        String taxId = stores.isEmpty() ? "" : digits(stores.get(0).vatNumber);
        return "SAFT-PT_" + taxId + "_" + from + "_" + to + ".xml";
    }

    /**
     * The lines a document states: the lines of its return for a credit note
     * issued after a return, the lines of its sale it covers otherwise.
     *
     * @param document the fiscal document
     * @return its lines, empty when its sale or return is not on this node
     */
    List<SaftLine> linesOf(FiscalDocument document) {
        List<SaftLine> lines = new ArrayList<>();
        if (document.refundId != null) {
            Refund refund = Refund.findById(document.refundId);
            if (refund != null) {
                for (RefundLine line : refund.lines) {
                    TicketLine original = TicketLine.findById(line.originalLineId);
                    BigDecimal quantity = line.quantity.abs();
                    lines.add(new SaftLine(original == null ? NO_PRODUCT_CODE : productCode(original),
                            line.productLabel, quantity, line.price.multiply(quantity).abs(), line.vatRate,
                            original == null ? null : original.vatNumber));
                }
            }
            return lines;
        }
        Ticket ticket = document.ticketId == null ? null : Ticket.findById(document.ticketId);
        if (ticket != null) {
            for (TicketLine line : ticket.lines) {
                if (document.covers(line)) {
                    lines.add(new SaftLine(productCode(line), line.productLabel, line.quantity.abs(),
                            line.totalPrice.abs(), line.vatRate, line.vatNumber));
                }
            }
        }
        return lines;
    }

    /**
     * The product code of a sold line: its EAN, its PLU, or the generic code.
     *
     * @param line the sold line
     * @return the code, never blank
     */
    static String productCode(TicketLine line) {
        if (line.ean != null && !line.ean.isBlank()) {
            return line.ean;
        }
        if (line.plu != null && !line.plu.isBlank()) {
            return line.plu;
        }
        return NO_PRODUCT_CODE;
    }

    /**
     * Writes the header: the company, the period, the software and its
     * certification.
     *
     * @param xml the file buffer
     * @param store the store, or null when none is administered
     * @param from the first day of the period
     * @param to the last day of the period
     * @param created the instant the file is drawn
     * @param invoices the documents of the file
     */
    private void header(StringBuilder xml, Store store, LocalDate from, LocalDate to,
            LocalDateTime created, List<SaftInvoice> invoices) {
        String taxId = store == null ? "" : digits(store.vatNumber);
        xml.append("  <Header>\n");
        element(xml, 4, "AuditFileVersion", AUDIT_FILE_VERSION);
        element(xml, 4, "CompanyID", taxId);
        element(xml, 4, "TaxRegistrationNumber", taxId);
        element(xml, 4, "TaxAccountingBasis", "F");
        element(xml, 4, "CompanyName", store == null ? "" : firstFilled(store.legalName, store.name));
        element(xml, 4, "BusinessName", store == null ? "" : text(store.name));
        xml.append("    <CompanyAddress>\n");
        boolean addressed = store != null && store.address != null;
        element(xml, 6, "AddressDetail", addressed ? firstFilled(store.address.streetLine1, UNKNOWN) : UNKNOWN);
        element(xml, 6, "City", addressed ? firstFilled(store.address.city, UNKNOWN) : UNKNOWN);
        element(xml, 6, "PostalCode", addressed ? firstFilled(store.address.postalCode, UNKNOWN) : UNKNOWN);
        element(xml, 6, "Country", "PT");
        xml.append("    </CompanyAddress>\n");
        element(xml, 4, "FiscalYear", String.valueOf(from.getYear()));
        element(xml, 4, "StartDate", from.format(DATE));
        element(xml, 4, "EndDate", to.format(DATE));
        element(xml, 4, "CurrencyCode", "EUR");
        element(xml, 4, "DateCreated", created.toLocalDate().format(DATE));
        element(xml, 4, "TaxEntity", "Global");
        element(xml, 4, "ProductCompanyTaxID", productCompanyTaxId);
        String certificate = digits(posSettingsService.fiscalCertificateNumber());
        element(xml, 4, "SoftwareCertificateNumber", certificate.isEmpty() ? "0" : certificate);
        element(xml, 4, "ProductID", productId);
        element(xml, 4, "ProductVersion", productVersion);
        String identifiers = documentIdentifiers(invoices);
        if (!identifiers.isEmpty()) {
            element(xml, 4, "HeaderComment", identifiers);
        }
        xml.append("  </Header>\n");
    }

    /**
     * The numeric identifier of each document type of the file, as the
     * documents carry it (BO-10-06-01, BO-10-06-02): {@code FS=1;NC=2}.
     *
     * @param invoices the documents of the file
     * @return the identifiers, empty when no document carries one
     */
    static String documentIdentifiers(List<SaftInvoice> invoices) {
        Map<String, Integer> identifiers = new TreeMap<>();
        for (SaftInvoice invoice : invoices) {
            if (invoice.document().saftDocumentId != null) {
                identifiers.putIfAbsent(invoice.document().documentType.name(), invoice.document().saftDocumentId);
            }
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> entry : identifiers.entrySet()) {
            if (text.length() > 0) {
                text.append(';');
            }
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.toString();
    }

    /**
     * Writes the master files the documents refer to: customers, products and
     * the tax table.
     *
     * @param xml the file buffer
     * @param invoices the documents of the file
     */
    private void masterFiles(StringBuilder xml, List<SaftInvoice> invoices) {
        Map<String, FiscalDocument> customers = new LinkedHashMap<>();
        Map<String, String> products = new LinkedHashMap<>();
        Map<BigDecimal, String> rates = new TreeMap<>();
        for (SaftInvoice invoice : invoices) {
            customers.putIfAbsent(customerId(invoice.document()), invoice.document());
            for (SaftLine line : invoice.lines()) {
                products.putIfAbsent(line.code(), text(line.description()));
                rates.putIfAbsent(rateOf(line), taxCode(rateOf(line)));
            }
        }
        xml.append("  <MasterFiles>\n");
        for (Map.Entry<String, FiscalDocument> customer : customers.entrySet()) {
            customer(xml, customer.getKey(), customer.getValue());
        }
        for (Map.Entry<String, String> product : products.entrySet()) {
            xml.append("    <Product>\n");
            element(xml, 6, "ProductType", "P");
            element(xml, 6, "ProductCode", product.getKey());
            element(xml, 6, "ProductDescription", product.getValue());
            element(xml, 6, "ProductNumberCode", product.getKey());
            xml.append("    </Product>\n");
        }
        xml.append("    <TaxTable>\n");
        for (Map.Entry<BigDecimal, String> rate : rates.entrySet()) {
            xml.append("      <TaxTableEntry>\n");
            element(xml, 8, "TaxType", "IVA");
            element(xml, 8, "TaxCountryRegion", "PT");
            element(xml, 8, "TaxCode", rate.getValue());
            element(xml, 8, "Description", "IVA " + percentage(rate.getKey()) + "%");
            element(xml, 8, "TaxPercentage", percentage(rate.getKey()));
            xml.append("      </TaxTableEntry>\n");
        }
        xml.append("    </TaxTable>\n");
        xml.append("  </MasterFiles>\n");
    }

    /**
     * Writes one customer: the final consumer, or the customer a document
     * names, with what the document states of them.
     *
     * @param xml the file buffer
     * @param id the customer identifier
     * @param document the first document naming them
     */
    private void customer(StringBuilder xml, String id, FiscalDocument document) {
        boolean finalConsumer = FINAL_CONSUMER_ID.equals(id);
        xml.append("    <Customer>\n");
        element(xml, 6, "CustomerID", id);
        element(xml, 6, "AccountID", UNKNOWN);
        element(xml, 6, "CustomerTaxID", finalConsumer ? FINAL_CONSUMER : id);
        element(xml, 6, "CompanyName", finalConsumer ? FINAL_CONSUMER_ID : firstFilled(document.customerName, UNKNOWN));
        xml.append("      <BillingAddress>\n");
        element(xml, 8, "AddressDetail", finalConsumer ? UNKNOWN : firstFilled(document.customerAddress, UNKNOWN));
        element(xml, 8, "City", UNKNOWN);
        element(xml, 8, "PostalCode", UNKNOWN);
        element(xml, 8, "Country", finalConsumer ? UNKNOWN : FiscalDocument.countryOf(document.customerTaxId));
        xml.append("      </BillingAddress>\n");
        element(xml, 6, "SelfBillingIndicator", "0");
        xml.append("    </Customer>\n");
    }

    /**
     * Writes the sales section: the totals, then every document with its lines.
     *
     * @param xml the file buffer
     * @param invoices the documents of the file
     */
    private void salesInvoices(StringBuilder xml, List<SaftInvoice> invoices) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (SaftInvoice invoice : invoices) {
            FiscalDocument document = invoice.document();
            if (document.cancelled) {
                continue;
            }
            BigDecimal net = net(document);
            if (document.documentType == FiscalDocument.Type.NC) {
                debit = debit.add(net);
            } else {
                credit = credit.add(net);
            }
        }
        xml.append("    <SalesInvoices>\n");
        element(xml, 6, "NumberOfEntries", String.valueOf(invoices.size()));
        element(xml, 6, "TotalDebit", amount(debit));
        element(xml, 6, "TotalCredit", amount(credit));
        for (SaftInvoice invoice : invoices) {
            invoice(xml, invoice);
        }
        xml.append("    </SalesInvoices>\n");
    }

    /**
     * Writes one document.
     *
     * @param xml the file buffer
     * @param invoice the document and its lines
     */
    private void invoice(StringBuilder xml, SaftInvoice invoice) {
        FiscalDocument document = invoice.document();
        boolean creditNote = document.documentType == FiscalDocument.Type.NC;
        String issued = document.issuedAt.format(DATE_TIME);
        xml.append("      <Invoice>\n");
        element(xml, 8, "InvoiceNo", document.number);
        element(xml, 8, "ATCUD", document.atcud);
        xml.append("        <DocumentStatus>\n");
        element(xml, 10, "InvoiceStatus", document.cancelled ? "A" : "N");
        element(xml, 10, "InvoiceStatusDate", issued);
        element(xml, 10, "SourceID", document.terminalId);
        // BO-07-01-06: a document integrated from the e-commerce platform is
        // stated as such; every other one was produced by this software.
        element(xml, 10, "SourceBilling", document.sourceBilling == null ? "P" : document.sourceBilling);
        xml.append("        </DocumentStatus>\n");
        element(xml, 8, "Hash", document.hash);
        element(xml, 8, "HashControl", "1");
        element(xml, 8, "Period", String.valueOf(document.issuedAt.getMonthValue()));
        element(xml, 8, "InvoiceDate", document.issuedAt.toLocalDate().format(DATE));
        element(xml, 8, "InvoiceType", document.documentType.name());
        xml.append("        <SpecialRegimes>\n");
        element(xml, 10, "SelfBillingIndicator", "0");
        element(xml, 10, "CashVATSchemeIndicator", "0");
        element(xml, 10, "ThirdPartiesBillingIndicator", "0");
        xml.append("        </SpecialRegimes>\n");
        element(xml, 8, "SourceID", document.terminalId);
        element(xml, 8, "SystemEntryDate", issued);
        element(xml, 8, "CustomerID", customerId(document));
        int number = 0;
        for (SaftLine line : invoice.lines()) {
            number++;
            BigDecimal rate = rateOf(line);
            BigDecimal net = line.gross().divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
            xml.append("        <Line>\n");
            element(xml, 10, "LineNumber", String.valueOf(number));
            element(xml, 10, "ProductCode", line.code());
            element(xml, 10, "ProductDescription", text(line.description()));
            element(xml, 10, "Quantity", line.quantity().stripTrailingZeros().toPlainString());
            element(xml, 10, "UnitOfMeasure", "UN");
            element(xml, 10, "UnitPrice", unitPrice(net, line.quantity()));
            element(xml, 10, "TaxPointDate", document.issuedAt.toLocalDate().format(DATE));
            if (creditNote && document.sourceNumber != null) {
                xml.append("          <References>\n");
                element(xml, 12, "Reference", document.sourceNumber);
                xml.append("          </References>\n");
            }
            element(xml, 10, "Description", text(line.description()));
            element(xml, 10, creditNote ? "DebitAmount" : "CreditAmount", amount(net));
            xml.append("          <Tax>\n");
            element(xml, 12, "TaxType", "IVA");
            element(xml, 12, "TaxCountryRegion", "PT");
            element(xml, 12, "TaxCode", taxCode(rate));
            element(xml, 12, "TaxPercentage", percentage(rate));
            xml.append("          </Tax>\n");
            if (rate.signum() == 0) {
                // BO-02-02-01: the exemption the REGIME of the line declares —
                // its code and its legal ground — rather than one generic code
                // for every zero rate; the generic pair remains for a regime
                // that declares nothing.
                com.intermarche.edge.domain.catalog.VatRate regime =
                        com.intermarche.edge.domain.catalog.VatRate.resolve(rate, line.vatNumber());
                element(xml, 10, "TaxExemptionReason", exemptionReason(regime));
                element(xml, 10, "TaxExemptionCode", exemptionCode(regime));
            }
            xml.append("        </Line>\n");
        }
        xml.append("        <DocumentTotals>\n");
        element(xml, 10, "TaxPayable", amount(document.taxTotal.abs()));
        element(xml, 10, "NetTotal", amount(net(document)));
        element(xml, 10, "GrossTotal", amount(document.grossTotal.abs()));
        xml.append("        </DocumentTotals>\n");
        xml.append("      </Invoice>\n");
    }

    /**
     * The customer identifier a document states: the final consumer, or the
     * customer's tax number without its country prefix.
     *
     * @param document the fiscal document
     * @return the identifier
     */
    static String customerId(FiscalDocument document) {
        return FiscalDocument.isFinalConsumer(document.customerTaxId)
                ? FINAL_CONSUMER_ID : FiscalDocument.numberOf(document.customerTaxId);
    }

    /**
     * The net total of a document: its gross total less its tax.
     *
     * @param document the fiscal document
     * @return the net total, positive
     */
    private static BigDecimal net(FiscalDocument document) {
        return document.grossTotal.abs().subtract(document.taxTotal.abs());
    }

    /**
     * The VAT rate of a line, zero when it carries none.
     *
     * @param line the line
     * @return the rate as a fraction, without trailing zeros
     */
    private static BigDecimal rateOf(SaftLine line) {
        return line.rate() == null ? BigDecimal.ZERO : line.rate().stripTrailingZeros();
    }

    /**
     * The legal ground of the exemption a regime declares (BO-02-02-01).
     *
     * @param regime the regime of the line, possibly null
     * @return its legal mention, or the generic "Isento" when it states none
     */
    static String exemptionReason(com.intermarche.edge.domain.catalog.VatRate regime) {
        return regime == null || regime.legalMention == null || regime.legalMention.isBlank()
                ? "Isento" : regime.legalMention.trim();
    }

    /**
     * The administration's code of the exemption a regime declares
     * (BO-02-02-01).
     *
     * @param regime the regime of the line, possibly null
     * @return its exemption code, or the generic M99 when it states none
     */
    static String exemptionCode(com.intermarche.edge.domain.catalog.VatRate regime) {
        return regime == null || regime.exemptionCode == null || regime.exemptionCode.isBlank()
                ? "M99" : regime.exemptionCode.trim();
    }

    /**
     * The Portuguese tax code of a rate: normal, intermediate, reduced or exempt.
     *
     * @param rate the rate as a fraction
     * @return NOR, INT, RED or ISE
     */
    static String taxCode(BigDecimal rate) {
        if (rate.signum() == 0) {
            return "ISE";
        }
        if (rate.compareTo(new BigDecimal("0.16")) >= 0) {
            return "NOR";
        }
        return rate.compareTo(new BigDecimal("0.09")) >= 0 ? "INT" : "RED";
    }

    /**
     * A rate written as a percentage, without trailing zeros.
     *
     * @param rate the rate as a fraction
     * @return the percentage, such as 23 or 6.5
     */
    private static String percentage(BigDecimal rate) {
        return rate.movePointRight(2).stripTrailingZeros().toPlainString();
    }

    /**
     * An amount as the schema writes it: two decimals, a point.
     *
     * @param value the amount
     * @return the written amount
     */
    private static String amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The net unit price of a line.
     *
     * @param net the net line amount
     * @param quantity the quantity
     * @return the unit price with four decimals, the net amount for a zero quantity
     */
    private static String unitPrice(BigDecimal net, BigDecimal quantity) {
        if (quantity.signum() == 0) {
            return net.setScale(4, RoundingMode.HALF_UP).toPlainString();
        }
        return net.divide(quantity, 4, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Writes one element, its value escaped.
     *
     * @param xml the file buffer
     * @param indent the indentation, in spaces
     * @param name the element name
     * @param value the value, possibly null
     */
    private static void element(StringBuilder xml, int indent, String name, String value) {
        xml.append(" ".repeat(indent)).append('<').append(name).append('>')
                .append(escape(value)).append("</").append(name).append(">\n");
    }

    /**
     * Escapes a value for the XML file.
     *
     * @param value the value, possibly null
     * @return the escaped value, empty for null
     */
    static String escape(String value) {
        return text(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * A text, empty for null.
     *
     * @param value the text, possibly null
     * @return the text, never null
     */
    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * The first value that is filled.
     *
     * @param value the preferred value, possibly null or blank
     * @param fallback the value used otherwise
     * @return the value, or the fallback
     */
    private static String firstFilled(String value, String fallback) {
        return value == null || value.isBlank() ? text(fallback) : value;
    }

    /**
     * The digits of a number, a country prefix and separators dropped.
     *
     * @param value the number as administered, possibly null
     * @return its digits, empty when none
     */
    static String digits(String value) {
        return text(value).replaceAll("[^0-9]", "");
    }
}
