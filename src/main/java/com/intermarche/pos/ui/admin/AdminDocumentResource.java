package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.sale.DocumentType;
import com.intermarche.pos.domain.sale.Invoice;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The DOCUMENTS ISSUED AT THE REGISTERS back-office screen
 * ({@code /admin/documents}, {@code BO-02-04-19}): the invoices and delivery
 * notes the tills printed, consulted and extracted, filtered by fiscal
 * identifier among other things.
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

    /** The document list template. */
    @Inject
    @Location("admin-documents")
    Template adminDocuments;

    /**
     * Shows the documents issued at the registers, filtered.
     *
     * @param text a fragment of the document number, customer name or account
     * @param taxId a fragment of the fiscal identifier printed on the document
     * @param type {@code FACTURE}, {@code BON_LIVRAISON} or anything else for both
     * @param from the first day shown, ISO, or null for no lower bound
     * @param to the last day shown, ISO, or null for no upper bound
     * @return the list page
     */
    @GET
    @RolesAllowed("ADMIN")
    public TemplateInstance list(@QueryParam("text") String text,
                                 @QueryParam("taxId") String taxId,
                                 @QueryParam("type") String type,
                                 @QueryParam("from") String from,
                                 @QueryParam("to") String to) {
        LOGGER.info("Entering method list with text: " + text + ", taxId: " + taxId
                + ", type: " + type + ", from: " + from + ", to: " + to);
        List<Invoice> matches = search(text, taxId, type, from, to);
        LOGGER.info("Exiting method list with " + matches.size() + " document(s)");
        return adminDocuments.data("documents", matches)
                .data("text", text == null ? "" : text)
                .data("taxId", taxId == null ? "" : taxId)
                .data("type", type == null ? "" : type)
                .data("from", from == null ? "" : from)
                .data("to", to == null ? "" : to)
                .data("types", DocumentType.values());
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
    @RolesAllowed("ADMIN")
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
                        + "TOTAL_ECO_TAX;PRINT_COUNT\n");
        for (Invoice document : search(text, taxId, type, from, to)) {
            csv.append(cell(document.documentNumber)).append(';')
                    .append(cell(document.documentType == null ? null : document.documentType.name())).append(';')
                    .append(cell(document.issueDate == null ? null : document.issueDate.toString())).append(';')
                    .append(cell(document.terminalId)).append(';')
                    .append(cell(document.ticketNumber)).append(';')
                    .append(cell(document.customerAccountNumber)).append(';')
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
                    .append(document.printCount)
                    .append('\n');
        }
        LOGGER.info("Exiting method export");
        return Response.ok(csv.toString())
                .header("Content-Disposition",
                        "attachment; filename=\"documents-" + LocalDate.now() + ".csv\"")
                .build();
    }

    /**
     * Runs the search over the issued documents, most recent first.
     *
     * <p>The PERIOD is pushed down to the database and the three fragment
     * criteria are applied in memory. The period is what bounds the volume — a
     * shop's documents accumulate for years — while a fragment match on three
     * columns would make a query built by concatenation, which is how a screen
     * ends up with an injection.
     *
     * @param text a fragment of the document number, customer name or account
     * @param taxId a fragment of the fiscal identifier
     * @param type the kind filter
     * @param from the first day shown, ISO, or null
     * @param to the last day shown, ISO, or null
     * @return the matching documents, most recent first
     */
    List<Invoice> search(String text, String taxId, String type, String from, String to) {
        LocalDate first = date(from);
        LocalDate last = date(to);
        List<Invoice> page = bounded(first, last);
        String needle = lower(text);
        String fiscal = lower(taxId);
        List<Invoice> matches = new ArrayList<>();
        for (Invoice document : page) {
            if (needle != null && !matchesText(document, needle)) {
                continue;
            }
            if (fiscal != null && (document.customerTaxId == null
                    || !document.customerTaxId.toLowerCase().contains(fiscal))) {
                continue;
            }
            if (type != null && !type.isBlank()
                    && (document.documentType == null || !document.documentType.name().equals(type))) {
                continue;
            }
            matches.add(document);
        }
        return matches;
    }

    /**
     * Reads the documents of a period, most recent first.
     *
     * <p>Four queries and not one built by concatenation: each bound is either
     * there or not, and the four cases are written out so nothing is assembled
     * from user input.
     *
     * @param first the first day shown, or null
     * @param last the last day shown, or null
     * @return the documents of that period
     */
    private List<Invoice> bounded(LocalDate first, LocalDate last) {
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
     * Says whether a document's number, customer name or account carries a
     * fragment.
     *
     * @param document the document examined
     * @param needle the lower-case fragment, never null
     * @return true when one of the three carries it
     */
    private boolean matchesText(Invoice document, String needle) {
        return contains(document.documentNumber, needle)
                || contains(document.customerName, needle)
                || contains(document.customerAccountNumber, needle);
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
    private String amount(java.math.BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

}
