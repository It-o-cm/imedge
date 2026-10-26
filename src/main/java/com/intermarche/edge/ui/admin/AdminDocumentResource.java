package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.sale.DocumentType;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The DOCUMENTS ISSUED AT THE REGISTERS back-office screen
 * ({@code /admin/documents}, {@code BO-02-04-19}): every document the tills
 * printed for a customer — the invoices and delivery notes, and the Portuguese
 * fiscal documents told apart by their own type, the simplified invoice (FS),
 * the invoice (FT), the credit note (NC) and the receipt (RC) — consulted one by
 * one and extracted, filtered by fiscal identifier (NIF), type, period and text.
 *
 * <p>READ ONLY, and deliberately so. A document is an archive: it says what was
 * printed and handed to a customer on a given day, and the one thing a back
 * office must never be able to do is change it afterwards. Reprinting it is not
 * this screen's job either — that is the register's duplicate, which counts its
 * prints.
 *
 * <p>The customer block shown here is the one the DOCUMENT carries, never the
 * one the customer's file carries today: a customer who has since moved, been
 * renamed or been blocked does not rewrite the papers already issued in their
 * name.
 *
 * <p>Access reserved to ADMIN, like every referential screen.
 */
@Path("/admin/documents")
public class AdminDocumentResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminDocumentResource.class);

    /** The name of each Portuguese document type, as the tax administration writes it. */
    static final Map<FiscalDocument.Type, String> FISCAL_TITLES = Map.of(
            FiscalDocument.Type.FS, "FATURA SIMPLIFICADA",
            FiscalDocument.Type.FT, "FATURA",
            FiscalDocument.Type.NC, "NOTA DE CRÉDITO",
            FiscalDocument.Type.RC, "RECIBO");

    /** The document list template. */
    @Inject
    @Location("admin-documents")
    Template adminDocuments;

    /** The document detail template. */
    @Inject
    @Location("admin-document")
    Template adminDocument;

    /**
     * One kind the type filter offers: its code and its printed name.
     *
     * @param code the code posted by the filter
     * @param title the printed name of the document
     */
    public record Kind(String code, String title) {
    }

    /**
     * One issued document as the list and the extraction render it, whatever
     * its family: an invoice or delivery note of the commercial chain, or a
     * Portuguese fiscal document.
     */
    public static class DocumentRow {

        /** The document number as printed. */
        public String number;

        /** The type code: FACTURE, BON_LIVRAISON, FS, FT, NC or RC. */
        public String type;

        /** The printed name of the type. */
        public String typeTitle;

        /** When it was issued. */
        public LocalDateTime issuedAt;

        /** The register that issued it. */
        public String terminalId;

        /** The number of the ticket it states, or null. */
        public String ticketNumber;

        /** The customer's account number, or null. */
        public String customerAccount;

        /** The customer's name as printed, or null. */
        public String customerName;

        /** The customer's fiscal identifier (NIF) as printed, or null. */
        public String customerTaxId;

        /** The customer's SIRET as printed, or null. */
        public String customerSiret;

        /** The customer's intra-community VAT number as printed, or null. */
        public String customerVatNumber;

        /** The customer's due date as printed, or null. */
        public LocalDate customerDueDate;

        /** The total excluding tax. */
        public BigDecimal totalExcludingTax;

        /** The total including tax. */
        public BigDecimal totalIncludingTax;

        /** The VAT total. */
        public BigDecimal totalVat;

        /** The eco-participation total, or null when the document states none. */
        public BigDecimal totalEcoTax;

        /** How many times it was printed, or null when the register does not count it. */
        public Integer printCount;

        /** The ATCUD of a Portuguese document, or null. */
        public String atcud;

        /** The number of the document it refers to, or null. */
        public String sourceNumber;

        /** Whether the document was cancelled. */
        public boolean cancelled;

        /** The four characters of the signature of a Portuguese document, or null. */
        public String hashCharacters;

        /** The fiscal QR payload of a Portuguese document, or null. */
        public String qrPayload;

        /** The database id of the ticket it states, or null. */
        public Long ticketId;

        /**
         * Builds the row of an invoice or a delivery note.
         *
         * @param invoice the document
         * @return the row
         */
        static DocumentRow of(Invoice invoice) {
            DocumentRow row = new DocumentRow();
            row.number = invoice.documentNumber;
            row.type = invoice.documentType == null ? null : invoice.documentType.name();
            row.typeTitle = invoice.documentType == null ? null : invoice.documentType.getTitle();
            row.issuedAt = invoice.issueDate;
            row.terminalId = invoice.terminalId;
            row.ticketNumber = invoice.ticketNumber;
            row.customerAccount = invoice.customerAccountNumber;
            row.customerName = invoice.customerName;
            row.customerTaxId = invoice.customerTaxId;
            row.customerSiret = invoice.customerSiret;
            row.customerVatNumber = invoice.customerVatNumber;
            row.customerDueDate = invoice.customerDueDate;
            row.totalExcludingTax = invoice.totalExcludingTax;
            row.totalIncludingTax = invoice.totalIncludingTax;
            row.totalVat = invoice.totalVat;
            row.totalEcoTax = invoice.totalEcoTax;
            row.printCount = invoice.printCount;
            row.ticketId = invoice.ticket == null ? null : invoice.ticket.id;
            return row;
        }

        /**
         * Builds the row of a Portuguese fiscal document.
         *
         * @param document the document
         * @param ticketNumber the number of the ticket it states, or null
         * @return the row
         */
        static DocumentRow of(FiscalDocument document, String ticketNumber) {
            DocumentRow row = new DocumentRow();
            row.number = document.number;
            row.type = document.documentType == null ? null : document.documentType.name();
            row.typeTitle = document.documentType == null ? null : FISCAL_TITLES.get(document.documentType);
            row.issuedAt = document.issuedAt;
            row.terminalId = document.terminalId;
            row.ticketNumber = ticketNumber;
            row.customerName = document.customerName;
            row.customerTaxId = document.customerTaxId;
            row.totalIncludingTax = document.grossTotal;
            row.totalVat = document.taxTotal;
            row.totalExcludingTax = document.grossTotal == null || document.taxTotal == null
                    ? null : document.grossTotal.subtract(document.taxTotal);
            row.atcud = document.atcud;
            row.sourceNumber = document.sourceNumber;
            row.cancelled = document.cancelled;
            row.hashCharacters = document.hash == null ? null : document.hashCharacters();
            row.qrPayload = document.qrPayload;
            row.ticketId = document.ticketId;
            return row;
        }

        /**
         * Tells whether this row is a Portuguese fiscal document.
         *
         * @return true for FS, FT, NC and RC
         */
        public boolean isFiscal() {
            return atcud != null;
        }
    }

    /**
     * Shows the documents issued at the registers, filtered.
     *
     * @param text a fragment of the document number, customer name or account
     * @param taxId a fragment of the fiscal identifier printed on the document
     * @param type a type code ({@link #kinds()}), anything else for all of them
     * @param from the first day shown, ISO, or null for no lower bound
     * @param to the last day shown, ISO, or null for no upper bound
     * @return the list page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.DOCUMENT)
    public TemplateInstance list(@QueryParam("text") String text,
                                 @QueryParam("taxId") String taxId,
                                 @QueryParam("type") String type,
                                 @QueryParam("from") String from,
                                 @QueryParam("to") String to) {
        LOGGER.info("Entering method list with text: " + text + ", taxId: " + taxId
                + ", type: " + type + ", from: " + from + ", to: " + to);
        List<DocumentRow> matches = search(text, taxId, type, from, to);
        LOGGER.info("Exiting method list with " + matches.size() + " document(s)");
        return adminDocuments.data("documents", matches)
                .data("text", text == null ? "" : text)
                .data("taxId", taxId == null ? "" : taxId)
                .data("type", type == null ? "" : type)
                .data("from", from == null ? "" : from)
                .data("to", to == null ? "" : to)
                .data("types", kinds());
    }

    /**
     * Shows ONE document as it was issued ({@code BO-02-04-19}): its identity,
     * its customer block, its totals, the lines of the sale it states and, for
     * a Portuguese document, its ATCUD, its signature and its QR payload.
     *
     * @param number the document number
     * @return the detail page; {@code document} is null when the number is unknown
     */
    @GET
    @Path("/view")
    @Authenticated
    @FeatureAccess(value = Feature.DOCUMENT)
    public TemplateInstance view(@QueryParam("number") String number) {
        LOGGER.info("Entering method view with number: " + number);
        DocumentRow row = find(number);
        Ticket ticket = row == null || row.ticketId == null ? null : Ticket.findById(row.ticketId);
        List<TicketLine> lines = new ArrayList<>();
        if (ticket != null && ticket.lines != null) {
            FiscalDocument fiscal = row.isFiscal() ? FiscalDocument.findByNumber(row.number) : null;
            for (TicketLine line : ticket.lines) {
                if (!line.cancelled && (fiscal == null || fiscal.covers(line))) {
                    lines.add(line);
                }
            }
        }
        LOGGER.info("Exiting method view");
        return adminDocument.data("document", row)
                .data("lines", lines)
                .data("printed", ticket == null ? null : readable(ticket.formattedContent));
    }

    /**
     * The printed ticket as a reader sees it on screen: the printer's image,
     * barcode and QR markup carried inside the stored printout is dropped,
     * the QR payload being shown in its own block of the page.
     *
     * @param content the printout as stored, possibly null
     * @return the printout without its markup, or null when none was kept
     */
    static String readable(String content) {
        return content == null ? null
                : content.replaceAll("\\[\\[(?:IMAGE|BARCODE|QR)[^\\]]*\\]\\]\\n?", "");
    }

    /**
     * Extracts the filtered documents as CSV ({@code BO-02-04-19}).
     *
     * @param text a fragment of the document number, customer name or account
     * @param taxId a fragment of the fiscal identifier
     * @param type the kind filter
     * @param from the first day extracted, ISO, or null
     * @param to the last day extracted, ISO, or null
     * @return the CSV file, named after the day it was drawn
     */
    @GET
    @Path("/export")
    @Authenticated
    @FeatureAccess(value = Feature.DOCUMENT)
    @Produces("text/csv; charset=UTF-8")
    public Response export(@QueryParam("text") String text,
                           @QueryParam("taxId") String taxId,
                           @QueryParam("type") String type,
                           @QueryParam("from") String from,
                           @QueryParam("to") String to) {
        LOGGER.info("Entering method export");
        StringBuilder csv = new StringBuilder(
                "DOCUMENT_NUMBER;DOCUMENT_TYPE;ISSUE_DATE;TERMINAL_ID;TICKET_NUMBER;"
                        + "CUSTOMER_ACCOUNT;CUSTOMER_NAME;CUSTOMER_SIRET;CUSTOMER_VAT_NUMBER;"
                        + "CUSTOMER_TAX_ID;CUSTOMER_DUE_DATE;TOTAL_HT;TOTAL_TTC;TOTAL_VAT;"
                        + "TOTAL_ECO_TAX;PRINT_COUNT;ATCUD;SOURCE_DOCUMENT;CANCELLED\n");
        for (DocumentRow document : search(text, taxId, type, from, to)) {
            csv.append(cell(document.number)).append(';')
                    .append(cell(document.type)).append(';')
                    .append(cell(document.issuedAt == null ? null : document.issuedAt.toString())).append(';')
                    .append(cell(document.terminalId)).append(';')
                    .append(cell(document.ticketNumber)).append(';')
                    .append(cell(document.customerAccount)).append(';')
                    .append(cell(document.customerName)).append(';')
                    .append(cell(document.customerSiret)).append(';')
                    .append(cell(document.customerVatNumber)).append(';')
                    .append(cell(document.customerTaxId)).append(';')
                    .append(cell(document.customerDueDate == null
                            ? null : document.customerDueDate.toString())).append(';')
                    .append(amount(document.totalExcludingTax)).append(';')
                    .append(amount(document.totalIncludingTax)).append(';')
                    .append(amount(document.totalVat)).append(';')
                    .append(amount(document.totalEcoTax)).append(';')
                    .append(document.printCount == null ? "" : String.valueOf(document.printCount)).append(';')
                    .append(cell(document.atcud)).append(';')
                    .append(cell(document.sourceNumber)).append(';')
                    .append(document.cancelled ? "1" : "0")
                    .append('\n');
        }
        LOGGER.info("Exiting method export");
        return Response.ok(csv.toString())
                .header("Content-Disposition",
                        "attachment; filename=\"documents-" + LocalDate.now() + ".csv\"")
                .build();
    }

    /**
     * The kinds the type filter offers: the commercial documents, then the four
     * Portuguese ones.
     *
     * @return the kinds, in that order
     */
    static List<Kind> kinds() {
        List<Kind> kinds = new ArrayList<>();
        for (DocumentType type : DocumentType.values()) {
            kinds.add(new Kind(type.name(), type.getTitle()));
        }
        for (FiscalDocument.Type type : FiscalDocument.Type.values()) {
            kinds.add(new Kind(type.name(), type.name() + " — " + FISCAL_TITLES.get(type)));
        }
        return kinds;
    }

    /**
     * Finds one document by its number, in either family.
     *
     * @param number the document number, possibly null
     * @return its row, or null when no document carries that number
     */
    DocumentRow find(String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        Invoice invoice = Invoice.find("documentNumber", number.trim()).firstResult();
        if (invoice != null) {
            return DocumentRow.of(invoice);
        }
        FiscalDocument fiscal = FiscalDocument.findByNumber(number.trim());
        return fiscal == null ? null : DocumentRow.of(fiscal, ticketNumberOf(fiscal));
    }

    /**
     * Runs the search over the issued documents of both families, most recent
     * first.
     *
     * <p>The PERIOD is pushed down to the database and the fragment criteria are
     * applied in memory. The period is what bounds the volume — a shop's
     * documents accumulate for years — while a fragment match on three columns
     * would make a query built by concatenation, which is how a screen ends up
     * with an injection.
     *
     * @param text a fragment of the document number, customer name or account
     * @param taxId a fragment of the fiscal identifier
     * @param type the kind filter
     * @param from the first day shown, ISO, or null
     * @param to the last day shown, ISO, or null
     * @return the matching documents, most recent first
     */
    List<DocumentRow> search(String text, String taxId, String type, String from, String to) {
        LocalDate first = date(from);
        LocalDate last = date(to);
        List<DocumentRow> page = new ArrayList<>();
        for (Invoice invoice : boundedInvoices(first, last)) {
            page.add(DocumentRow.of(invoice));
        }
        for (FiscalDocument document : boundedFiscal(first, last)) {
            page.add(DocumentRow.of(document, ticketNumberOf(document)));
        }
        page.sort(Comparator.comparing((DocumentRow row) -> row.issuedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        String needle = lower(text);
        String fiscal = lower(taxId);
        List<DocumentRow> matches = new ArrayList<>();
        for (DocumentRow document : page) {
            if (needle != null && !matchesText(document, needle)) {
                continue;
            }
            if (fiscal != null && (document.customerTaxId == null
                    || !document.customerTaxId.toLowerCase().contains(fiscal))) {
                continue;
            }
            if (type != null && !type.isBlank() && !type.equals(document.type)) {
                continue;
            }
            matches.add(document);
        }
        return matches;
    }

    /**
     * Reads the number of the ticket a fiscal document states.
     *
     * @param document the document
     * @return the ticket number, or null when it states no ticket held here
     */
    private String ticketNumberOf(FiscalDocument document) {
        if (document.ticketId == null) {
            return null;
        }
        Ticket ticket = Ticket.findById(document.ticketId);
        return ticket == null ? null : ticket.ticketNumber;
    }

    /**
     * Reads the invoices and delivery notes of a period, most recent first.
     *
     * <p>Four queries and not one built by concatenation: each bound is either
     * there or not, and the four cases are written out so nothing is assembled
     * from user input.
     *
     * @param first the first day shown, or null
     * @param last the last day shown, or null
     * @return the documents of that period
     */
    private List<Invoice> boundedInvoices(LocalDate first, LocalDate last) {
        if (first != null && last != null) {
            return Invoice.list("issueDate >= ?1 and issueDate < ?2 order by issueDate desc",
                    first.atStartOfDay(), last.plusDays(1).atStartOfDay());
        }
        if (first != null) {
            return Invoice.list("issueDate >= ?1 order by issueDate desc", first.atStartOfDay());
        }
        if (last != null) {
            return Invoice.list("issueDate < ?1 order by issueDate desc",
                    last.plusDays(1).atStartOfDay());
        }
        return Invoice.list("order by issueDate desc");
    }

    /**
     * Reads the Portuguese fiscal documents of a period, most recent first,
     * by the same four written-out cases.
     *
     * @param first the first day shown, or null
     * @param last the last day shown, or null
     * @return the documents of that period
     */
    private List<FiscalDocument> boundedFiscal(LocalDate first, LocalDate last) {
        if (first != null && last != null) {
            return FiscalDocument.list("issuedAt >= ?1 and issuedAt < ?2 order by issuedAt desc",
                    first.atStartOfDay(), last.plusDays(1).atStartOfDay());
        }
        if (first != null) {
            return FiscalDocument.list("issuedAt >= ?1 order by issuedAt desc", first.atStartOfDay());
        }
        if (last != null) {
            return FiscalDocument.list("issuedAt < ?1 order by issuedAt desc",
                    last.plusDays(1).atStartOfDay());
        }
        return FiscalDocument.list("order by issuedAt desc");
    }

    /**
     * Says whether a document's number, customer name or account carries a
     * fragment.
     *
     * @param document the document examined
     * @param needle the lower-case fragment, never null
     * @return true when one of the three carries it
     */
    private boolean matchesText(DocumentRow document, String needle) {
        return contains(document.number, needle)
                || contains(document.customerName, needle)
                || contains(document.customerAccount, needle);
    }

    /**
     * Null-safe lower-case fragment match.
     *
     * @param value the value examined, possibly null
     * @param needle the lower-case fragment
     * @return true when the value carries the fragment
     */
    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    /**
     * Lower-cases a criterion, a blank one counting as absent.
     *
     * @param value the criterion, possibly null or blank
     * @return the lower-case fragment, or null when nothing was asked
     */
    private String lower(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase();
    }

    /**
     * Reads an ISO day, an unreadable one counting as no bound.
     *
     * @param value the criterion, possibly null or unreadable
     * @return the day, or null
     */
    private LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException notADate) {
            return null;
        }
    }

    /**
     * Renders one CSV cell, the separator neutralized.
     *
     * @param value the value, possibly null
     * @return the cell, empty for null
     */
    private String cell(String value) {
        return value == null ? "" : value.replace(';', ',').replace('\n', ' ');
    }

    /**
     * Renders an amount for the extraction.
     *
     * @param value the amount, possibly null
     * @return the plain rendering, empty for null
     */
    private String amount(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
