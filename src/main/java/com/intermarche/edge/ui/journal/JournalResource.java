package com.intermarche.edge.ui.journal;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.payment.PaymentTypes;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.TechnicalEvent;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The electronic journal back office (BO-04-01, lot 4): a multi-criteria
 * search over the consolidated documents, split into a transactional tab
 * (tickets) and a functional tab (technical events) as BO-04-01-52 requires,
 * a third tab for the cash movements (BO-04-01-12/33/35/36/37/40/44, a distinct
 * consolidated entity that is neither a ticket nor a technical event), a ticket
 * detail view reachable from the list, and a CSV export that reproduces the
 * transactional list to the character (BO-04-01-50).
 * <p>
 * Reserved to MANAGER and ADMIN — the journal is a supervision surface, like
 * the dashboard, not a public LAN page. It reads this node's own database, so
 * it is the STORE journal when opened on the consolidated node. It produces
 * nothing: every endpoint is a GET, there is no state to mutate.
 * <p>
 * Rendered inside the back-office gabarit ({@code admin-layout}), the same
 * chrome as the parameters and supervision screens — one surface, one
 * navigation.
 */
@Path("/admin/journal")
public class JournalResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(JournalResource.class);

    /** The journal search page template (both tabs). */
    @Inject
    @Location("journal")
    Template journal;

    /** The ticket detail page template. */
    @Inject
    @Location("journal-detail")
    Template journalDetail;

    /** The training-mode sales tab template (LC-13-05-06). */
    @Inject
    @Location("journal-training")
    Template journalTraining;

    /** The tickets-on-hold tab template (LC-04-01-03). */
    @Inject
    @Location("journal-parked")
    Template journalParked;

    /** The barcodes printed at the tills tab (BO-03-06-62). */
    @Inject
    @Location("journal-barcodes-issued")
    Template journalBarcodesIssued;

    /** The barcodes read at the tills tab (BO-03-06-63). */
    @Inject
    @Location("journal-barcodes-scanned")
    Template journalBarcodesScanned;

    /** The training-mode sale detail template (LC-13-05-06). */
    @Inject
    @Location("journal-training-detail")
    Template journalTrainingDetail;

    /** The duplicate of an invoice or delivery note reprinted from here (BO-04-01-53). */
    @Inject
    @Location("journal-duplicate")
    Template journalDuplicate;

    @Inject
    JournalService journalService;

    /** The back office's language, which the CSV export's headings follow. */
    @Inject
    com.intermarche.edge.ui.admin.BackOfficeMessages i18n;

    /** Dynamic per-key counters over the already synchronized event journal (LC-13-05-01). */
    @Inject jakarta.persistence.EntityManager keyUsageEntityManager;

    /**
     * Shows the transactional journal: the ticket search form and its results.
     *
     * @param uriInfo the request URI carrying the search criteria
     * @return the transactional tab page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance transactional(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method transactional with uriInfo: " + uriInfo);
        JournalCriteria criteria = JournalCriteria.fromParams(uriInfo.getQueryParameters());
        JournalPage<JournalRow> tickets = journalService.search(criteria);
        LOGGER.info("Exiting method transactional");
        return page("transactional", criteria, uriInfo, tickets, emptyPage(), emptyPage());
    }

    /**
     * Shows the functional journal: the technical-event search form and its
     * results.
     *
     * @param uriInfo the request URI carrying the search criteria
     * @return the functional tab page
     */
    @GET
    @Path("/functional")
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance functional(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method functional with uriInfo: " + uriInfo);
        JournalCriteria criteria = JournalCriteria.fromParams(uriInfo.getQueryParameters());
        JournalPage<JournalEventRow> events = journalService.searchEvents(criteria);
        LOGGER.info("Exiting method functional");
        return page("functional", criteria, uriInfo, emptyPage(), events, emptyPage());
    }

    /**
     * Shows the movements journal: the cash-movement search form and its results
     * (BO-04-01-12/33/35/36/37/40/44). Cash movements are a distinct consolidated
     * entity, so they get their own tab rather than being grafted onto the
     * transactional search (whose every clause presumes a ticket) or the
     * functional one (which reads technical events).
     *
     * @param uriInfo the request URI carrying the search criteria
     * @return the movements tab page
     */
    @GET
    @Path("/movements")
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance movements(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method movements with uriInfo: " + uriInfo);
        JournalCriteria criteria = JournalCriteria.fromParams(uriInfo.getQueryParameters());
        JournalPage<JournalMovementRow> movements = journalService.searchMovements(criteria);
        LOGGER.info("Exiting method movements");
        return page("movements", criteria, uriInfo, emptyPage(), emptyPage(), movements);
    }

    /**
     * Shows the training-mode sales of a period (LC-13-05-06): a tab of their
     * own, read from the dedicated table no revenue or accounting flow reads.
     *
     * @param from the first day, {@code yyyy-MM-dd}, today when absent or unreadable
     * @param to the last day, {@code yyyy-MM-dd}, the first day when absent or unreadable
     * @return the training tab page
     */
    @GET
    @Path("/training")
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance training(@QueryParam("from") String from, @QueryParam("to") String to) {
        LOGGER.info("Entering method training with from: " + from + ", to: " + to);
        java.time.LocalDate first = day(from, java.time.LocalDate.now());
        java.time.LocalDate last = day(to, first);
        if (last.isBefore(first)) {
            last = first;
        }
        List<com.intermarche.edge.domain.sale.TrainingTicket> rows =
                com.intermarche.edge.domain.sale.TrainingTicket.listBetween(first.atStartOfDay(),
                        last.plusDays(1).atStartOfDay());
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        List<java.util.Map<String, Object>> views = new java.util.ArrayList<>();
        for (com.intermarche.edge.domain.sale.TrainingTicket row : rows) {
            total = total.add(row.totalIncludingTax == null ? java.math.BigDecimal.ZERO : row.totalIncludingTax);
            views.add(trainingView(row));
        }
        LOGGER.info("Exiting method training");
        return journalTraining.data("from", first.toString()).data("to", last.toString())
                .data("rows", views).data("total", euros(total));
    }

    /**
     * Shows the signed traces of the tickets put on hold, resumed or purged over a
     * period (LC-04-01-03), with the state of each register's chain checked over
     * all its records.
     *
     * @param from the first day, {@code yyyy-MM-dd}, today when absent or unreadable
     * @param to the last day, {@code yyyy-MM-dd}, the first day when absent or unreadable
     * @return the tickets-on-hold tab page
     */
    @GET
    @Path("/parked")
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance parked(@QueryParam("from") String from, @QueryParam("to") String to) {
        LOGGER.info("Entering method parked with from: " + from + ", to: " + to);
        java.time.LocalDate first = day(from, java.time.LocalDate.now());
        java.time.LocalDate last = day(to, first);
        if (last.isBefore(first)) {
            last = first;
        }
        List<com.intermarche.edge.domain.sale.ParkedTicketRecord> rows =
                com.intermarche.edge.domain.sale.ParkedTicketRecord.listBetween(first.atStartOfDay(),
                        last.plusDays(1).atStartOfDay());
        java.util.Map<String, String> chains = new java.util.TreeMap<>();
        List<java.util.Map<String, Object>> views = new java.util.ArrayList<>();
        for (com.intermarche.edge.domain.sale.ParkedTicketRecord row : rows) {
            chains.computeIfAbsent(String.valueOf(row.terminalId), JournalResource::chainState);
            views.add(parkedView(row));
        }
        List<java.util.Map<String, String>> chainViews = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> chain : chains.entrySet()) {
            chainViews.add(java.util.Map.of("terminalId", chain.getKey(), "state", chain.getValue(),
                    "intact", String.valueOf(chain.getValue().startsWith("INTÈGRE"))));
        }
        LOGGER.info("Exiting method parked");
        return journalParked.data("from", first.toString()).data("to", last.toString())
                .data("rows", views).data("chains", chainViews);
    }

    /**
     * Shows the vouchers, credit notes and gift cards the tills printed over a
     * period (BO-03-06-62): register, transaction, amount, date of printing,
     * end of validity, and what is left on them.
     *
     * @param from the first day, {@code yyyy-MM-dd}, today when absent or unreadable
     * @param to the last day, {@code yyyy-MM-dd}, the first day when absent or unreadable
     * @return the printed-barcodes tab page
     */
    @GET
    @Path("/barcodes-issued")
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance barcodesIssued(@QueryParam("from") String from, @QueryParam("to") String to) {
        LOGGER.info("Entering method barcodesIssued with from: " + from + ", to: " + to);
        java.time.LocalDate first = day(from, java.time.LocalDate.now());
        java.time.LocalDate last = day(to, first);
        if (last.isBefore(first)) {
            last = first;
        }
        List<java.util.Map<String, Object>> views = new java.util.ArrayList<>();
        for (com.intermarche.edge.domain.barcode.StoredValueRecord row
                : com.intermarche.edge.domain.barcode.StoredValueRecord.listIssuedBetween(
                        first.atStartOfDay(), last.plusDays(1).atStartOfDay())) {
            java.util.Map<String, Object> view = new java.util.HashMap<>();
            view.put("terminalId", row.issuingTerminalId);
            view.put("documentNumber", row.issuingDocumentNumber == null ? "" : row.issuingDocumentNumber);
            view.put("number", row.number);
            view.put("kind", "GIFT_CARD".equals(row.kind) ? "Carte cadeau" : "Avoir");
            view.put("amount", euros(row.initialAmount));
            view.put("balance", euros(row.balance));
            view.put("issuedAt", stamp(row.issuedAt));
            view.put("validUntil", row.validUntil == null ? "—" : row.validUntil.toLocalDate()
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            view.put("at", row.issuedAt);
            views.add(view);
        }
        // BO-03-06-62: the deposit slips (acomptes) the tills printed, whose
        // barcode is the order's reference; a deposit has no balance of its own.
        for (com.intermarche.edge.domain.session.CashMovement deposit
                : com.intermarche.edge.domain.session.CashMovement.<com.intermarche.edge.domain.session.CashMovement>list(
                        "type = ?1 and movementDate >= ?2 and movementDate < ?3",
                        com.intermarche.edge.domain.session.CashMovement.MovementType.CUSTOMER_DEPOSIT,
                        first.atStartOfDay(), last.plusDays(1).atStartOfDay())) {
            java.util.Map<String, Object> view = new java.util.HashMap<>();
            view.put("terminalId", deposit.terminalId);
            view.put("documentNumber", deposit.movementUid == null ? "" : deposit.movementUid);
            view.put("number", deposit.orderReference == null ? "—" : deposit.orderReference);
            view.put("kind", "Acompte");
            view.put("amount", euros(deposit.amount));
            view.put("balance", "");
            view.put("issuedAt", stamp(deposit.movementDate));
            view.put("validUntil", "—");
            view.put("at", deposit.movementDate);
            views.add(view);
        }
        views.sort(java.util.Comparator.comparing(
                (java.util.Map<String, Object> view) -> (java.time.LocalDateTime) view.get("at"),
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        LOGGER.info("Exiting method barcodesIssued");
        return journalBarcodesIssued.data("from", first.toString()).data("to", last.toString()).data("rows", views);
    }

    /**
     * Shows the payment barcodes read at the tills over a period
     * (BO-03-06-63): register, transaction, amount, end of validity, date of
     * use and whether the system knew the code.
     *
     * @param from the first day, {@code yyyy-MM-dd}, today when absent or unreadable
     * @param to the last day, {@code yyyy-MM-dd}, the first day when absent or unreadable
     * @return the read-barcodes tab page
     */
    @GET
    @Path("/barcodes-scanned")
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance barcodesScanned(@QueryParam("from") String from, @QueryParam("to") String to) {
        LOGGER.info("Entering method barcodesScanned with from: " + from + ", to: " + to);
        java.time.LocalDate first = day(from, java.time.LocalDate.now());
        java.time.LocalDate last = day(to, first);
        if (last.isBefore(first)) {
            last = first;
        }
        List<java.util.Map<String, Object>> views = new java.util.ArrayList<>();
        for (com.intermarche.edge.domain.barcode.ScannedBarcode row
                : com.intermarche.edge.domain.barcode.ScannedBarcode.listBetween(
                        first.atStartOfDay(), last.plusDays(1).atStartOfDay())) {
            java.util.Map<String, Object> view = new java.util.HashMap<>();
            view.put("terminalId", row.terminalId);
            view.put("ticketNumber", row.ticketNumber == null ? "" : row.ticketNumber);
            view.put("code", row.code);
            view.put("type", row.typeLabel == null ? "" : row.typeLabel);
            view.put("amount", row.amount == null ? "—" : euros(row.amount));
            view.put("validUntil", row.validUntil == null ? "—" : row.validUntil.toLocalDate()
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            view.put("scannedAt", stamp(row.scannedAt));
            view.put("found", row.found);
            views.add(view);
        }
        LOGGER.info("Exiting method barcodesScanned");
        return journalBarcodesScanned.data("from", first.toString()).data("to", last.toString()).data("rows", views);
    }

    /**
     * An instant as the journal shows it.
     *
     * @param instant the instant, or null
     * @return {@code dd/MM/yyyy HH:mm:ss}, empty for null
     */
    private static String stamp(java.time.LocalDateTime instant) {
        return instant == null ? ""
                : instant.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
    }

    /**
     * The state of a register's chain of tickets-on-hold records (LC-04-01-03).
     *
     * @param terminalId the register
     * @return "INTÈGRE" with the number of records, or where it breaks
     */
    static String chainState(String terminalId) {
        List<com.intermarche.edge.domain.sale.ParkedTicketRecord> chain =
                com.intermarche.edge.domain.sale.ParkedTicketRecord.listChain(terminalId);
        com.intermarche.edge.domain.sale.ParkedTicketRecord broken =
                com.intermarche.edge.domain.sale.ParkedTicketRecord.firstBroken(chain);
        if (broken == null) {
            return "INTÈGRE (" + chain.size() + " enregistrement(s))";
        }
        return "ROMPUE à l'enregistrement " + broken.sourceId + " (ticket " + broken.ticketNumber + ")";
    }

    /**
     * A record of a ticket on hold as the journal shows it.
     *
     * @param row the record
     * @return its date, register, ticket, event, operator, count, total, content and signature
     */
    private static java.util.Map<String, Object> parkedView(com.intermarche.edge.domain.sale.ParkedTicketRecord row) {
        java.util.Map<String, Object> view = new java.util.HashMap<>();
        view.put("eventDate", row.eventDate == null ? ""
                : row.eventDate.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));
        view.put("terminalId", row.terminalId == null ? "" : row.terminalId);
        view.put("ticketNumber", row.ticketNumber == null ? "" : row.ticketNumber);
        view.put("event", row.event == null ? "" : switch (row.event) {
            case PARKED -> "MISE EN ATTENTE";
            case RESUMED -> "REPRISE";
            case PURGED -> "PURGE À LA CLÔTURE";
        });
        view.put("operator", row.operator == null ? "" : row.operator);
        view.put("itemCount", row.itemCount);
        view.put("total", euros(row.totalAmount == null ? java.math.BigDecimal.ZERO : row.totalAmount));
        view.put("content", row.content == null ? "" : row.content);
        view.put("signature", row.signature == null ? "" : row.signature.substring(0, Math.min(16, row.signature.length())));
        return view;
    }

    /**
     * Shows one training-mode sale: its receipt as the register printed it
     * (LC-13-05-06).
     *
     * @param id the training sale's id on this node
     * @return the detail page; {@code training} is null when the id is unknown
     */
    @GET
    @Path("/training/{id}")
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance trainingDetail(@PathParam("id") Long id) {
        LOGGER.info("Entering method trainingDetail with id: " + id);
        com.intermarche.edge.domain.sale.TrainingTicket training =
                com.intermarche.edge.domain.sale.TrainingTicket.findById(id);
        LOGGER.info("Exiting method trainingDetail");
        return journalTrainingDetail.data("training", training == null ? null : trainingView(training));
    }

    /**
     * A training sale as the journal shows it: its figures written the French way.
     *
     * @param training the training sale
     * @return its id, date, register, cashier, article count, total and receipt
     */
    private static java.util.Map<String, Object> trainingView(com.intermarche.edge.domain.sale.TrainingTicket training) {
        java.util.Map<String, Object> view = new java.util.HashMap<>();
        view.put("id", training.id);
        view.put("issuedAt", training.issuedAt == null ? ""
                : training.issuedAt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        view.put("terminalId", training.terminalId == null ? "" : training.terminalId);
        view.put("cashierLogin", training.cashierLogin == null ? "" : training.cashierLogin);
        view.put("itemCount", training.itemCount);
        view.put("total", euros(training.totalIncludingTax == null
                ? java.math.BigDecimal.ZERO : training.totalIncludingTax));
        // The printer's logo token is no text: the journal shows the receipt without it.
        view.put("content", training.content == null ? ""
                : training.content.replaceAll("\\[\\[IMAGE[^\\]]*\\]\\]\\n?", ""));
        return view;
    }

    /**
     * Writes an amount the French way.
     *
     * @param amount the amount
     * @return two decimals, a decimal comma
     */
    private static String euros(java.math.BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /**
     * Reads a day of the training tab's period.
     *
     * @param value the day as posted, possibly null or unreadable
     * @param fallback the day to use instead
     * @return the day
     */
    private static java.time.LocalDate day(String value, java.time.LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return java.time.LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return fallback;
        }
    }

    /**
     * Builds an empty result page of the service's page size, for the two tabs
     * a request does not search.
     *
     * @param <T> the row type of the empty page
     * @return the empty page
     */
    private <T> JournalPage<T> emptyPage() {
        return new JournalPage<>(List.of(), 1, JournalService.PAGE_SIZE, 0);
    }

    /**
     * Shows the detail of one consolidated ticket.
     *
     * @param id the ticket database id
     * @return the detail page; {@code ticket} is null when the id is unknown
     */
    @GET
    @Path("/ticket/{id}")
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(@PathParam("id") Long id) {
        LOGGER.info("Entering method detail with id: " + id);
        JournalTicketDetail ticket = journalService.buildDetail(id);
        LOGGER.info("Exiting method detail");
        return journalDetail.data("ticket", ticket);
    }

    /**
     * Reprints an invoice or a delivery note issued at a register as a
     * DUPLICATE (BO-04-01-53): the page states « DUPLICATA » and prints.
     *
     * @param id the document's id on this node
     * @return the printable duplicate; {@code duplicate} is null when the id is unknown
     */
    @GET
    @Path("/document/{id}/duplicate")
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance duplicate(@PathParam("id") Long id) {
        LOGGER.info("Entering method duplicate with id: " + id);
        JournalService.Duplicate duplicate = journalService.duplicate(id);
        LOGGER.info("Exiting method duplicate");
        return journalDuplicate.data("duplicate", duplicate);
    }

    /**
     * Exports the transactional list as a CSV attachment, running the same
     * search as the screen (BO-04-01-50, export à l'identique).
     *
     * @param uriInfo the request URI carrying the search criteria
     * @return the CSV attachment response
     */
    @GET
    @Path("/export")
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces("text/csv")
    public Response export(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method export with uriInfo: " + uriInfo);
        JournalCriteria criteria = JournalCriteria.fromParams(uriInfo.getQueryParameters());
        String csv = journalService.exportCsv(criteria, i18n::text);
        LOGGER.info("Exiting method export");
        return Response.ok(csv)
                .header("Content-Disposition", "attachment; filename=\"journal.csv\"")
                .build();
    }

    /**
     * Assembles the search page with the active tab, the criteria echoed back
     * to keep the form filled, the result page, the option catalogs and the
     * query string the pager rebuilds its links from.
     *
     * @param tab the active tab ("transactional", "functional" or "movements")
     * @param criteria the parsed criteria to echo
     * @param uriInfo the request URI, read for the pager base query
     * @param tickets the transactional result page (empty off the transactional tab)
     * @param events the functional result page (empty off the functional tab)
     * @param movements the movements result page (empty off the movements tab)
     * @return the wired page instance
     */
    private TemplateInstance page(String tab, JournalCriteria criteria, UriInfo uriInfo,
                                  JournalPage<JournalRow> tickets,
                                  JournalPage<JournalEventRow> events,
                                  JournalPage<JournalMovementRow> movements) {
        return journal.data("tab", tab)
                .data("criteria", criteria)
                .data("tickets", tickets)
                .data("events", events)
                .data("movements", movements)
                .data("baseQuery", baseQuery(uriInfo))
                .data("paymentKeys", PaymentTypes.keys())
                .data("ticketFlags", JournalCriteria.Flag.values())
                .data("journalEvents", JournalEvent.values())
                .data("sorts", JournalSort.values())
                .data("eventTypes", TechnicalEvent.EventType.values())
                .data("movementTypes", CashMovement.MovementType.values());
    }

    /**
     * Rebuilds the current query string without its page parameter, so the
     * pager can append its own page number while keeping every search criterion
     * the user submitted. The returned string is empty or ends with an
     * ampersand, so a caller only has to append {@code page=n}.
     *
     * @param uriInfo the request URI carrying the query parameters
     * @return the re-encoded query string prefix
     */
    String baseQuery(UriInfo uriInfo) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : uriInfo.getQueryParameters().entrySet()) {
            if ("page".equals(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                query.append(encode(entry.getKey())).append('=')
                        .append(encode(value)).append('&');
            }
        }
        return query.toString();
    }

    /**
     * URL-encodes one query-string token.
     *
     * @param value the raw token
     * @return the encoded token
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    /**
     * Exports per-key, register and operator counts for third-party statistics.
     * @return the consolidated counters as CSV (LC-13-05-01)
     */
    @GET
    @Path("/key-usage.csv")
    @Authenticated
    @FeatureAccess(value = Feature.JOURNAL)
    @Produces("text/csv; charset=utf-8")
    @jakarta.transaction.Transactional
    public Response keyUsage() {
        List<Object[]> rows = keyUsageEntityManager.createQuery(
                "select e.detail, e.terminalId, e.operatorBadgeId, count(e) from TechnicalEvent e "
                        + "where e.eventType = :type group by e.detail, e.terminalId, e.operatorBadgeId "
                        + "order by e.detail, e.terminalId, e.operatorBadgeId", Object[].class)
                .setParameter("type", TechnicalEvent.EventType.TOUCH_USED).getResultList();
        StringBuilder csv = new StringBuilder("key;register;operator;count\n");
        for (Object[] row : rows) {
            csv.append(keyUsageCell(row[0])).append(';').append(keyUsageCell(row[1])).append(';')
                    .append(keyUsageCell(row[2])).append(';').append(row[3]).append('\n');
        }
        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=\"key-usage.csv\"").build();
    }

    /**
     * Quotes CSV identifiers without turning separators or quotes into columns.
     * @param value the identifier, possibly absent for a locked operator
     * @return the quoted cell (LC-13-05-01)
     */
    private static String keyUsageCell(Object value) {
        return "\"" + java.util.Objects.toString(value, "").replace("\"", "\"\"") + "\"";
    }

}
