package com.intermarche.edge.ui.fiscal;

import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.ui.admin.AdminForms;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The TRANSACTIONS EXPORT screen ({@code /admin/fiscal/transactions},
 * {@code BO-05-01-06}): every ticket and invoice the point of sale issued, over
 * the years kept online, found by NIF, dates, document type, customer number
 * or name, ticket number and the totals with and without VAT; the total of each
 * document and of every customer; and the extraction in CSV, spreadsheet and
 * PDF, the PDF being what is printed.
 */
@Path("/admin/fiscal/transactions")
public class TransactionExportResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TransactionExportResource.class);

    /** How many documents the screen shows; the extraction carries them all. */
    static final int SHOWN = 500;

    /** The column titles of the extraction. */
    static final List<String> HEADER = List.of("TYPE", "NUMERO", "DATE", "CAISSE", "TICKET", "N_CLIENT",
            "NOM_CLIENT", "NIF", "TOTAL_HT", "TVA", "TOTAL_TTC");

    /** How a day reads on the screen and in the PDF title. */
    static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** The width of each column of the PDF, in characters. */
    static final int[] WIDTHS = {6, 22, 19, 6, 22, 16, 22, 15, 11, 10, 11};

    /** The screen. */
    @Inject
    @Location("admin-fiscal-transactions")
    Template adminFiscalTransactions;

    /** The search. */
    @Inject
    TransactionExportService transactionExportService;

    /** The years kept online. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Shows the documents found and their totals.
     *
     * @param from the first day, ISO, blank for the first day of the month
     * @param to the last day, ISO, blank for today
     * @param type the document type, blank for all
     * @param nif the customer's tax number
     * @param customerNumber the customer's number
     * @param customerName a fragment of the customer's name
     * @param ticketNumber the ticket number
     * @param htMin the smallest total tax excluded
     * @param htMax the largest total tax excluded
     * @param ttcMin the smallest total tax included
     * @param ttcMax the largest total tax included
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.FISCAL_TRANSACTIONS)
    public TemplateInstance page(@QueryParam("from") String from, @QueryParam("to") String to,
                                 @QueryParam("type") String type, @QueryParam("nif") String nif,
                                 @QueryParam("customerNumber") String customerNumber,
                                 @QueryParam("customerName") String customerName,
                                 @QueryParam("ticketNumber") String ticketNumber,
                                 @QueryParam("htMin") String htMin, @QueryParam("htMax") String htMax,
                                 @QueryParam("ttcMin") String ttcMin, @QueryParam("ttcMax") String ttcMax) {
        LOGGER.info("Entering method page");
        TransactionExportService.Criteria criteria = criteria(from, to, type, nif, customerNumber, customerName,
                ticketNumber, htMin, htMax, ttcMin, ttcMax);
        TransactionExportService.Result result = transactionExportService.search(criteria);
        LOGGER.info("Exiting method page with " + result.rows.size() + " document(s)");
        return adminFiscalTransactions.data("result", result)
                .data("rows", result.rows.subList(0, Math.min(SHOWN, result.rows.size())))
                .data("shown", Math.min(SHOWN, result.rows.size()))
                .data("criteria", criteria)
                .data("types", TransactionExportService.TYPES)
                .data("query", query(criteria, htMin, htMax, ttcMin, ttcMax))
                .data("years", posSettingsService.fiscalOnlineRetentionYears())
                .data("oldest", LocalDate.now().minusYears(posSettingsService.fiscalOnlineRetentionYears()).format(DAY))
                .data("invalidAmount", invalid(htMin, htMax, ttcMin, ttcMax))
                .data("htMin", text(htMin)).data("htMax", text(htMax))
                .data("ttcMin", text(ttcMin)).data("ttcMax", text(ttcMax));
    }

    /**
     * Extracts the documents found, with their totals by customer, in CSV,
     * spreadsheet or PDF.
     *
     * @param format {@code csv}, {@code xlsx} or {@code pdf}
     * @param from the first day
     * @param to the last day
     * @param type the document type
     * @param nif the customer's tax number
     * @param customerNumber the customer's number
     * @param customerName a fragment of the customer's name
     * @param ticketNumber the ticket number
     * @param htMin the smallest total tax excluded
     * @param htMax the largest total tax excluded
     * @param ttcMin the smallest total tax included
     * @param ttcMax the largest total tax included
     * @return the file
     */
    @GET
    @Path("/export")
    @Authenticated
    @FeatureAccess(value = Feature.FISCAL_TRANSACTIONS)
    public Response export(@QueryParam("format") String format, @QueryParam("from") String from,
                           @QueryParam("to") String to, @QueryParam("type") String type,
                           @QueryParam("nif") String nif, @QueryParam("customerNumber") String customerNumber,
                           @QueryParam("customerName") String customerName,
                           @QueryParam("ticketNumber") String ticketNumber,
                           @QueryParam("htMin") String htMin, @QueryParam("htMax") String htMax,
                           @QueryParam("ttcMin") String ttcMin, @QueryParam("ttcMax") String ttcMax) {
        LOGGER.info("Entering method export with format: " + format);
        TransactionExportService.Criteria criteria = criteria(from, to, type, nif, customerNumber, customerName,
                ticketNumber, htMin, htMax, ttcMin, ttcMax);
        TransactionExportService.Result result = transactionExportService.search(criteria);
        List<List<Object>> rows = new ArrayList<>();
        for (TransactionExportService.Row row : result.rows) {
            rows.add(java.util.Arrays.asList(row.type, row.number, row.getIssued(), row.terminalId, row.ticketNumber,
                    row.customerNumber, row.customerName, row.nif, scaled(row.totalExcludingTax),
                    scaled(row.totalVat), scaled(row.totalIncludingTax)));
        }
        rows.add(java.util.Arrays.asList("TOTAL", "", "", "", "", "", "", "", scaled(result.totalExcludingTax),
                scaled(result.totalVat), scaled(result.totalIncludingTax)));
        for (TransactionExportService.CustomerTotal total : result.customers) {
            rows.add(java.util.Arrays.asList("CLIENT", total.count + " doc.", "", "", "", total.customer, total.name,
                    "", scaled(total.totalExcludingTax), scaled(total.totalVat), scaled(total.totalIncludingTax)));
        }
        String name = "transactions_" + criteria.from() + "_" + criteria.to();
        String chosen = format == null ? "csv" : format.trim().toLowerCase(Locale.ROOT);
        LOGGER.info("Exiting method export with " + result.rows.size() + " document(s)");
        return switch (chosen) {
            case "xlsx" -> file(TableExportWriter.xlsx("Transactions", HEADER, rows),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", name + ".xlsx");
            case "pdf" -> file(TableExportWriter.pdf("Export des transactions du " + criteria.from().format(DAY)
                    + " au " + criteria.to().format(DAY), HEADER, rows, WIDTHS), "application/pdf", name + ".pdf");
            default -> file(TableExportWriter.csv(HEADER, rows), "text/csv; charset=UTF-8", name + ".csv");
        };
    }

    /**
     * Reads the filters typed in the form.
     *
     * @param from the first day
     * @param to the last day
     * @param type the document type
     * @param nif the customer's tax number
     * @param customerNumber the customer's number
     * @param customerName a fragment of the customer's name
     * @param ticketNumber the ticket number
     * @param htMin the smallest total tax excluded
     * @param htMax the largest total tax excluded
     * @param ttcMin the smallest total tax included
     * @param ttcMax the largest total tax included
     * @return the filters, the days defaulting to the month so far
     */
    static TransactionExportService.Criteria criteria(String from, String to, String type, String nif,
            String customerNumber, String customerName, String ticketNumber, String htMin, String htMax,
            String ttcMin, String ttcMax) {
        LocalDate today = LocalDate.now();
        LocalDate first = AdminForms.day(from) == null ? today.withDayOfMonth(1) : AdminForms.day(from);
        LocalDate last = AdminForms.day(to) == null ? today : AdminForms.day(to);
        String kind = text(type) == null ? null : text(type).toUpperCase(Locale.ROOT);
        return new TransactionExportService.Criteria(first, last,
                kind != null && TransactionExportService.TYPES.contains(kind) ? kind : null, text(nif), text(customerNumber),
                text(customerName), text(ticketNumber), AdminForms.amount(htMin), AdminForms.amount(htMax),
                AdminForms.amount(ttcMin), AdminForms.amount(ttcMax));
    }

    /**
     * The query string that repeats a search, for the extraction links.
     *
     * @param criteria the filters read
     * @param htMin the smallest total tax excluded, as typed
     * @param htMax the largest total tax excluded, as typed
     * @param ttcMin the smallest total tax included, as typed
     * @param ttcMax the largest total tax included, as typed
     * @return the query string, without its question mark
     */
    static String query(TransactionExportService.Criteria criteria, String htMin, String htMax, String ttcMin,
            String ttcMax) {
        return "from=" + criteria.from() + "&to=" + criteria.to()
                + param("type", criteria.type())
                + param("nif", criteria.nif()) + param("customerNumber", criteria.customerNumber())
                + param("customerName", criteria.customerName()) + param("ticketNumber", criteria.ticketNumber())
                + param("htMin", text(htMin)) + param("htMax", text(htMax))
                + param("ttcMin", text(ttcMin)) + param("ttcMax", text(ttcMax));
    }

    /**
     * One parameter of the query string, encoded.
     *
     * @param name the parameter
     * @param value the value, null leaving it out
     * @return the parameter preceded by its ampersand, empty for null
     */
    static String param(String name, String value) {
        return value == null ? "" : "&" + name + "="
                + java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Whether one of the typed amounts is no amount: the search then ignores
     * it, and the screen says so instead of silently widening the result.
     *
     * @param amounts the amounts as typed
     * @return true when a non-blank one does not read as a number
     */
    static boolean invalid(String... amounts) {
        for (String amount : amounts) {
            if (text(amount) != null && AdminForms.amount(amount) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * A typed value, blank counting as absent.
     *
     * @param value the value, possibly null
     * @return the trimmed value, or null
     */
    static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * An amount as the extraction states it: a number with two decimals.
     *
     * @param value the amount
     * @return the amount, scaled
     */
    static BigDecimal scaled(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Builds a download.
     *
     * @param bytes the file
     * @param type its media type
     * @param name its name
     * @return the response
     */
    static Response file(byte[] bytes, String type, String name) {
        return Response.ok(bytes, type).header("Content-Disposition", "attachment; filename=\"" + name + "\"").build();
    }
}
