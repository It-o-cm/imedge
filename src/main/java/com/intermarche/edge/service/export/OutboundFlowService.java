package com.intermarche.edge.service.export;

import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.sale.CustomerOrder;
import com.intermarche.edge.domain.store.Address;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sale.VatBreakdown;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The OUTGOING FLOWS of the node (BO-11-01-02 to 05): what accounting collects
 * from a point of sale, produced from the tickets the registers have already
 * pushed up.
 *
 * <p>One class holds both the engine and every builder, deliberately. The engine
 * is four decisions — is the flow activated at this echelon, where does the file
 * go, which period does it cover and what does it fold — and a builder is one
 * fold over that period. Split across eleven classes they would share nothing but
 * a signature, and the next flow would be added to whichever file its author
 * opened first.
 *
 * <p>ACTIVATION and DESTINATION are administered parameters and not deployment
 * properties, because the requirement asks for them "at the Country / Enseigne /
 * Point of Sale scale" — which is exactly what the echelon resolution of
 * {@link PosSettingsService} already gives. An empty destination disables every
 * flow: a file written where nobody collects it is worse than no file, since the
 * other side then waits for something that was produced and lost.
 *
 * <p>REGENERATION on demand (BO-11-03-01) is the same call as the scheduled one:
 * a flow is a pure function of a closed accounting day, so re-producing it
 * overwrites the file with the same content unless the data itself changed. There
 * is no separate "replay" path to drift from the real one.
 *
 * <p>A flow declares its SOURCE, its PERIOD and its SHAPE on
 * {@link OutboundFlow}, and the engine reads them: that is how a monthly fold
 * over tickets, a daily fold over invoices and an archive of three members live
 * under one {@code produce}. Adding a flow is one catalogue entry, one fold and
 * one activation key — never a branch in the engine.
 *
 * <p>What is NOT here, and is not a missing builder: Catalina, SAFT, NEXO and
 * the electronic labels are third-party protocols spoken at the flow or on
 * demand, each declaring its endpoint in the provider table rather than writing
 * a file.
 */
@ApplicationScoped
public class OutboundFlowService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(OutboundFlowService.class);

    /** The column separator of the flow files: the plain-file convention here. */
    private static final String SEPARATOR = ";";

    /** The shape of an instant in a flow row, to the second. */
    private static final java.time.format.DateTimeFormatter STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * The payment discriminator of a fidelity settlement.
     *
     * <p>Matched on the method KEY and not with {@code instanceof}: a payment read
     * lazily is a Hibernate proxy, whose class is not the entity's, while
     * {@code getMethodKey()} unwraps it.
     */
    private static final String FIDELITY_METHOD = "FIDELITY";

    /** The administered activation, destination and cut-over hour. */
    @Inject
    PosSettingsService posSettingsService;

    /** Resolves which accounting day a ticket is counted on. */
    @Inject
    AccountingDay accountingDay;

    /** Tells which vouchers a closed sale of the shop consumed (BO-11-01-18). */
    @Inject
    com.intermarche.edge.service.sync.VoucherBurnService voucherBurnService;

    /**
     * The last accounting day whose flows were produced by the loop, or null
     * while none has been.
     *
     * <p>Guards against producing twice: the loop examines the clock every
     * minute, and without this the flows would be rewritten sixty times an hour
     * for the rest of the day. Held in memory on purpose — after a restart the
     * node produces the day again, which is harmless because producing a flow is
     * idempotent, and a persisted marker would be one more row to migrate.
     */
    LocalDate lastProducedDay;

    /** Seconds between two examinations of the clock. */
    long tickSeconds = 60L;

    /** The examination loop, null until the node has started. */
    java.util.concurrent.ScheduledExecutorService executor;

    /**
     * Starts the loop that produces the flows of a finished accounting day.
     *
     * <p>Same shape as the period close: one daemon thread, one examination a
     * minute, and the work guarded by the day it last did. A flow cannot be
     * produced on a fixed hour, because the hour the day ENDS at is itself
     * administered (BO-11-01-05).
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Entering method onStart");
        executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbound-flows");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tickSafely, tickSeconds, tickSeconds,
                java.util.concurrent.TimeUnit.SECONDS);
        LOGGER.info("Exiting method onStart");
    }

    /**
     * Runs one examination, swallowing whatever it fails on.
     *
     * <p>A full disk or a database hiccup must not stop the node from trying
     * again a minute later, and must never propagate into the scheduler, which
     * would cancel the loop for the rest of the day.
     */
    void tickSafely() {
        try {
            tick();
        } catch (Exception failure) {
            LOGGER.warnf("Production des flux sortants impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Produces the flows of the accounting day that has just ENDED, once.
     *
     * <p>The day that ended is the one before the day the current instant belongs
     * to: as soon as the clock crosses the cut-over hour, the previous accounting
     * day is complete and can be emitted. Nothing happens while the current day
     * is still the one already produced.
     */
    void tick() {
        LocalDate today = accountingDay.of(LocalDateTime.now());
        LocalDate finished = today.minusDays(1);
        if (finished.equals(lastProducedDay)) {
            return;
        }
        try {
            // Through the bean's OWN client proxy, so @Transactional applies: the
            // loop calls this method on `this`, and a self-invocation goes past
            // every interceptor — the lazy ticket lines would then be read with
            // no session open. The admin route does not need this, being called
            // through the proxy already.
            List<Path> written = self().produceAll(finished);
            lastProducedDay = finished;
            if (!written.isEmpty()) {
                LOGGER.infof("Flux sortants de la journée comptable %s produits: %d fichier(s).",
                        finished, written.size());
            }
        } catch (IOException failure) {
            // NOT marked as produced: the next minute tries again, which is what
            // a transient write failure deserves. A permanent one logs every
            // minute, and that is the point — a flow nobody collects must be
            // noisy, not silent.
            LOGGER.warnf("Flux sortants de la journée comptable %s en échec (%s)",
                    finished, failure.getMessage());
        }
    }

    /**
     * Produces one flow for one accounting day and returns the file written, or
     * null when nothing was written.
     *
     * <p>Null covers the three refusals, and they are not failures: the flow is
     * not activated at this echelon, no destination is administered, or the day
     * holds no closed ticket. A day without a sale produces NO file rather than
     * an empty one — an empty file and a missing file say different things to the
     * receiving side, and only the second is honest about a shop that was shut.
     *
     * @param flow the flow to produce
     * @param day the accounting day it covers
     * @return the file written, or null when the flow was not produced
     * @throws IOException when the destination exists but cannot be written to
     */
    @Transactional
    public Path produce(OutboundFlow flow, LocalDate day) throws IOException {
        LOGGER.info("Entering method produce with flow: " + flow + ", day: " + day);
        if (!isActivated(flow)) {
            LOGGER.infof("Flux %s non activé sur cet échelon: rien produit.", flow.getStem());
            LOGGER.info("Exiting method produce");
            return null;
        }
        String directory = posSettingsService.exportDirectory();
        if (directory.isEmpty()) {
            LOGGER.warnf("Flux %s activé mais aucun répertoire de dépôt administré:"
                    + " rien produit.", flow.getStem());
            LOGGER.info("Exiting method produce");
            return null;
        }
        Map<String, List<String>> members = membersOf(flow, day);
        if (members.isEmpty()) {
            LOGGER.infof("Flux %s: rien à exporter sur la période du %s,"
                    + " aucun fichier produit.", flow.getStem(), day);
            LOGGER.info("Exiting method produce");
            return null;
        }
        Path target = Path.of(directory).resolve(fileNameOf(flow, day));
        Files.createDirectories(target.getParent());
        if (flow.isArchive()) {
            writeArchive(target, members);
        } else {
            // BO-11-01-12: a flow of several flat files writes each beside the
            // first, under its own name; a one-file flow writes its one member.
            for (Map.Entry<String, List<String>> member : members.entrySet()) {
                writeFile(target.resolveSibling(member.getKey()), member.getValue());
            }
        }
        LOGGER.infof("Flux %s produit: %s, %d membre(s).",
                flow.getStem(), target, members.size());
        LOGGER.info("Exiting method produce");
        return target;
    }

    /**
     * Produces EVERY activated flow for one accounting day — the scheduled
     * gesture, and the one a support user replays on demand (BO-11-03-01).
     *
     * @param day the accounting day to produce
     * @return the files written, in catalogue order, empty when none was
     * @throws IOException when a destination cannot be written to
     */
    @Transactional
    public List<Path> produceAll(LocalDate day) throws IOException {
        LOGGER.info("Entering method produceAll with day: " + day);
        List<Path> written = new ArrayList<>();
        for (OutboundFlow flow : OutboundFlow.values()) {
            Path file = produce(flow, day);
            if (file != null) {
                written.add(file);
            }
        }
        LOGGER.info("Exiting method produceAll");
        return written;
    }

    /**
     * Returns this bean through its CDI client proxy, or {@code this} when there
     * is no container — which is the case in a plain unit test.
     *
     * <p>Falling back on {@code this} rather than failing is deliberate: the
     * proxy buys the transaction, and a unit test that opens no database needs
     * neither. A null container, a bean not yet started and an outright failure
     * all land on the same answer.
     *
     * @return the proxied bean, or this instance
     */
    OutboundFlowService self() {
        try {
            OutboundFlowService proxy = io.quarkus.arc.Arc.container()
                    .instance(OutboundFlowService.class).get();
            return proxy == null ? this : proxy;
        } catch (Exception unavailable) {
            return this;
        }
    }

    /**
     * Tells whether a flow is activated at this echelon (BO-11-01-02).
     *
     * @param flow the flow
     * @return true when the administered activation key says yes
     */
    public boolean isActivated(OutboundFlow flow) {
        LOGGER.info("Entering method isActivated with flow: " + flow);
        boolean activated = switch (flow) {
            case REVENUE -> posSettingsService.exportRevenueEnabled();
            case CASH_BOOK -> posSettingsService.exportCashBookEnabled();
            case FAMILY_SALES -> posSettingsService.exportFamilySalesEnabled();
            case DAILY_TRANSACTIONS -> posSettingsService.exportTransactionsEnabled();
            case PERIOD_SUMMARY -> posSettingsService.exportPeriodSummaryEnabled();
            case CASHIER_PRODUCTIVITY -> posSettingsService.exportProductivityEnabled();
            case CUSTOMER_INVOICES -> posSettingsService.exportCustomerInvoicesEnabled();
            case ERP_INVOICES -> posSettingsService.exportErpInvoicesEnabled();
            case FIDELITY_VAT -> posSettingsService.exportFidelityVatEnabled();
            case ORDER_VAT -> posSettingsService.exportOrderVatEnabled();
            case PANELS -> posSettingsService.exportPanelsEnabled();
            case PLANEXA -> posSettingsService.planexaExportEnabled();
            case GS1_PAYMENTS -> posSettingsService.exportGs1PaymentsEnabled();
            case VIGNETTES -> posSettingsService.exportVignettesEnabled();
        };
        LOGGER.info("Exiting method isActivated");
        return activated;
    }

    /**
     * Reads the CLOSED tickets of one accounting day, in closing order.
     *
     * <p>Closed only: an open or abandoned ticket is not a sale, and the
     * accounting side must never see one. The bounds come from
     * {@link AccountingDay} so the cut-over hour applies to both flows at once,
     * as the requirement demands.
     *
     * @param day the accounting day
     * @return the closed tickets of that day, oldest first
     */
    List<Ticket> closedTicketsOf(LocalDate day) {
        LocalDateTime from = accountingDay.startOf(day);
        LocalDateTime to = accountingDay.endOf(day);
        return Ticket.<Ticket>find(
                "status = ?1 and closingDate >= ?2 and closingDate < ?3 order by closingDate",
                Ticket.TicketStatus.CLOSED, from, to).list();
    }

    /**
     * Reads the CLOSED tickets of one calendar month, in closing order.
     *
     * <p>The month is bounded by the accounting day of its first morning and the
     * accounting day of the next month's first morning, so the administered
     * cut-over hour applies to the month exactly as it applies to a day: a sale
     * rung at one in the morning on the first belongs to the month that is ending.
     *
     * @param day any day of the month
     * @return the closed tickets of that month, oldest first
     */
    List<Ticket> closedTicketsOfMonth(LocalDate day) {
        YearMonth month = YearMonth.from(day);
        LocalDateTime from = accountingDay.startOf(month.atDay(1));
        LocalDateTime to = accountingDay.startOf(month.plusMonths(1).atDay(1));
        return Ticket.<Ticket>find(
                "status = ?1 and closingDate >= ?2 and closingDate < ?3 order by closingDate",
                Ticket.TicketStatus.CLOSED, from, to).list();
    }

    /**
     * Reads the invoices and delivery notes ISSUED during one accounting day.
     *
     * <p>Scoped on the issue date, which is the document's own business date: an
     * invoice is dated when it is issued, and an audit timestamp would move it to
     * whichever day the row was last written.
     *
     * @param day the accounting day
     * @return the documents issued that day, oldest first
     */
    List<Invoice> invoicesOf(LocalDate day) {
        LocalDateTime from = accountingDay.startOf(day);
        LocalDateTime to = accountingDay.endOf(day);
        return Invoice.<Invoice>find("issueDate >= ?1 and issueDate < ?2 order by issueDate",
                from, to).list();
    }

    /**
     * Reads the customer ORDERS dated in one accounting day.
     *
     * @param day the accounting day
     * @return the orders of that day, oldest first
     */
    List<CustomerOrder> ordersOf(LocalDate day) {
        LocalDateTime from = accountingDay.startOf(day);
        LocalDateTime to = accountingDay.endOf(day);
        return CustomerOrder.<CustomerOrder>find(
                "orderDate >= ?1 and orderDate < ?2 order by orderDate", from, to).list();
    }

    /**
     * Builds the MEMBERS of a flow: the file name of each member and its rows,
     * header included — one entry for every flow but the panel archive.
     *
     * <p>An empty map means there is nothing to export over the period, which the
     * caller turns into "no file" rather than into an empty one. A flow is
     * addressed as a set of members and not as a file because one of them,
     * BO-11-01-19, is three files in an archive; making that the general shape
     * costs one map and spares the engine a second write path.
     *
     * <p>One switch, exhaustive over the catalogue and with NO default arm: a
     * default here would be a branch no test could reach, and the compiler
     * refusing an unlisted flow is a better guard than a runtime throw nobody
     * triggers. Each arm names the fold and nothing else; the period and the
     * emptiness refusal belong to the three loaders below.
     *
     * @param flow the flow being produced
     * @param day the accounting day, or any day of the month for a monthly flow
     * @return the members, keyed by file name, empty when the period holds nothing
     */
    Map<String, List<String>> membersOf(OutboundFlow flow, LocalDate day) {
        return switch (flow) {
            case REVENUE -> ticketFold(flow, day, this::revenueRows);
            case CASH_BOOK -> ticketFold(flow, day, this::cashBookRows);
            case FAMILY_SALES -> ticketFold(flow, day, this::familySalesRows);
            case DAILY_TRANSACTIONS -> ticketFold(flow, day, this::dailyTransactionRows);
            case PERIOD_SUMMARY -> ticketFold(flow, day, this::periodSummaryRows);
            case CASHIER_PRODUCTIVITY -> ticketFold(flow, day, this::cashierProductivityRows);
            case FIDELITY_VAT -> ticketFold(flow, day, this::fidelityVatRows);
            case CUSTOMER_INVOICES -> customerInvoiceMembers(day);
            case ERP_INVOICES -> invoiceFold(flow, day, this::erpInvoiceRows);
            case ORDER_VAT -> orderFold(flow, day, this::orderVatRows);
            case PANELS -> panelArchive(day);
            case PLANEXA -> ticketFold(flow, day, this::planexaRows);
            case GS1_PAYMENTS -> gs1Members(flow, day);
            case VIGNETTES -> vignetteMembers(flow, day);
        };
    }

    /**
     * Loads the closed tickets of the flow's period and folds them, or returns
     * nothing when the period holds none.
     *
     * @param flow the flow being produced, for its period and its file name
     * @param day the accounting day, or any day of the month for a monthly flow
     * @param fold the fold to apply to the tickets
     * @return the single member, or an empty map
     */
    private Map<String, List<String>> ticketFold(OutboundFlow flow, LocalDate day,
            java.util.function.BiFunction<LocalDate, List<Ticket>, List<String>> fold) {
        List<Ticket> tickets = flow.getPeriod() == OutboundFlow.Period.MONTH
                ? closedTicketsOfMonth(day)
                : closedTicketsOf(day);
        if (tickets.isEmpty()) {
            return Map.of();
        }
        return single(flow, day, fold.apply(day, tickets));
    }

    /**
     * Loads the documents issued during the day and folds them, or returns nothing
     * when the day issued none.
     *
     * @param flow the flow being produced, for its file name
     * @param day the accounting day
     * @param fold the fold to apply to the documents
     * @return the single member, or an empty map
     */
    private Map<String, List<String>> invoiceFold(OutboundFlow flow, LocalDate day,
            java.util.function.BiFunction<LocalDate, List<Invoice>, List<String>> fold) {
        List<Invoice> invoices = invoicesOf(day);
        if (invoices.isEmpty()) {
            return Map.of();
        }
        return single(flow, day, fold.apply(day, invoices));
    }

    /**
     * Loads the orders dated in the day and folds them, or returns nothing when
     * the day holds none.
     *
     * @param flow the flow being produced, for its file name
     * @param day the accounting day
     * @param fold the fold to apply to the orders
     * @return the single member, or an empty map
     */
    private Map<String, List<String>> orderFold(OutboundFlow flow, LocalDate day,
            java.util.function.BiFunction<LocalDate, List<CustomerOrder>, List<String>> fold) {
        List<CustomerOrder> orders = ordersOf(day);
        if (orders.isEmpty()) {
            return Map.of();
        }
        return single(flow, day, fold.apply(day, orders));
    }

    /**
     * Loads the closed tickets of the day and builds the three panel members, or
     * returns nothing when the day holds no ticket.
     *
     * @param day the accounting day
     * @return the three members, or an empty map
     */
    private Map<String, List<String>> panelArchive(LocalDate day) {
        List<Ticket> tickets = closedTicketsOf(day);
        if (tickets.isEmpty()) {
            return Map.of();
        }
        return panelMembers(day, tickets);
    }

    /**
     * Wraps the rows of a one-file flow as the single member of its map.
     *
     * @param flow the flow being produced
     * @param day the period it covers
     * @param rows its rows
     * @return a one-entry map keyed by the flow's file name
     */
    private Map<String, List<String>> single(OutboundFlow flow, LocalDate day,
            List<String> rows) {
        Map<String, List<String>> members = new LinkedHashMap<>();
        members.put(flow.fileName(day), rows);
        return members;
    }

    /**
     * Returns the name of the file a flow writes for one period.
     *
     * <p>The panel archive is the exception the RFP spells out: it is collected as
     * {@code MS.<point de vente>.<jour>.zip}, so the node's own PDV number enters
     * the name. A node that does not know its PDV falls back on the catalogue's
     * plain name rather than writing the word "null" into a file name the other
     * side matches on.
     *
     * @param flow the flow being produced
     * @param day the period it covers
     * @return the file name to write
     */
    private String fileNameOf(OutboundFlow flow, LocalDate day) {
        if (flow != OutboundFlow.PANELS) {
            return flow.fileName(day);
        }
        String pdv = posSettingsService.pdvNumber();
        if (pdv.isEmpty()) {
            return flow.fileName(day);
        }
        return flow.getStem() + "." + pdv + "." + julianDay(day) + ".zip";
    }

    /**
     * Writes one file, its rows separated by line breaks and closed by one.
     *
     * @param target the file to write
     * @param rows the rows to write
     * @throws IOException when the file cannot be written
     */
    private void writeFile(Path target, List<String> rows) throws IOException {
        // A member with no record is an EMPTY file, not one blank line: a flat
        // file reader counts lines, and a blank one is a malformed record.
        String content = rows.isEmpty() ? "" : String.join("\n", rows).concat("\n");
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes an archive holding every member of a flow.
     *
     * <p>No compression level is set and no timestamp is forced: the archive is
     * read by a loader, not kept, and a byte-identical archive for an identical
     * day is not something the requirement asks for.
     *
     * @param target the archive to write
     * @param members the members, keyed by their name inside the archive
     * @throws IOException when the archive cannot be written
     */
    private void writeArchive(Path target, Map<String, List<String>> members)
            throws IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(target))) {
            for (Map.Entry<String, List<String>> member : members.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(member.getKey()));
                zip.write(String.join("\n", member.getValue()).concat("\n")
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    /**
     * Returns the three-digit day of the year, which the panel file names use.
     *
     * @param day the day
     * @return the day of year, zero-padded to three digits
     */
    private String julianDay(LocalDate day) {
        return String.format("%03d", day.getDayOfYear());
    }

    /**
     * Builds the REVENUE rows (BO-11-01-03): one row per VAT rate of the day,
     * with the tax-included total, the excluding-tax total and the tax itself,
     * then one TOTAL row.
     *
     * <p>CANCELLED lines are left out. A cancelled line is kept on the ticket for
     * the audit trail, but it was not sold, and counting it would inflate the
     * revenue by exactly the amount the cashier took back.
     *
     * @param day the accounting day
     * @param tickets the closed tickets of that day
     * @return the rows, header first
     */
    private List<String> revenueRows(LocalDate day, List<Ticket> tickets) {
        VatBreakdown breakdown = new VatBreakdown();
        BigDecimal thirdParty = BigDecimal.ZERO;
        for (Ticket ticket : tickets) {
            for (TicketLine line : ticket.lines) {
                if (line.cancelled) {
                    continue;
                }
                // BO-02-03-43: a sale on behalf of a third party counts in the
                // takings, but its VAT is the third party's: it is stated on a
                // row of its own and kept out of the store's VAT.
                if (line.thirdPartySale) {
                    thirdParty = thirdParty.add(line.totalPrice == null ? BigDecimal.ZERO : line.totalPrice);
                    continue;
                }
                breakdown.add(line.vatRate, line.totalPrice, line.vatExempt, false);
            }
        }
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "JOURNEE", "TAUX_TVA", "TOTAL_TTC",
                "TOTAL_HT", "TVA", "NB_TICKETS"));
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            rows.add(String.join(SEPARATOR, day.toString(), bucket.exempt ? "EXO" : rate(bucket.rate),
                    amount(bucket.totalIncludingTax), amount(bucket.totalExcludingTax),
                    amount(bucket.vatAmount), String.valueOf(tickets.size())));
        }
        if (thirdParty.signum() != 0) {
            rows.add(String.join(SEPARATOR, day.toString(), "COMPTE_TIERS",
                    amount(thirdParty), amount(thirdParty), amount(BigDecimal.ZERO), String.valueOf(tickets.size())));
        }
        rows.add(String.join(SEPARATOR, day.toString(), "TOTAL",
                amount(breakdown.getTotalIncludingTax().add(thirdParty)),
                amount(breakdown.getTotalExcludingTax().add(thirdParty)),
                amount(breakdown.getTotalVat()), String.valueOf(tickets.size())));
        return rows;
    }

    /**
     * Builds the CASH-BOOK rows (BO-11-01-04): the amount settled by payment
     * method over the accounting day, with the number of settlements.
     *
     * <p>The settlements of an integrated transaction — an e-commerce order, a
     * mobility sale — are booked on rows of their own, keyed
     * {@code CHANNEL/MEANS} by the means the third-party system stated
     * (BO-07-01-04), so the cash book isolates them from the lanes' takings.
     *
     * <p>Keyed by the payment's own method key and not by a list written here, so
     * a tender put in service in the back office appears in the flow without this
     * class being touched — and a tender that was never used does not appear at
     * all, which is what a cash book says.
     *
     * @param day the accounting day
     * @param tickets the closed tickets of that day
     * @return the rows, header first
     */
    private List<String> cashBookRows(LocalDate day, List<Ticket> tickets) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            com.intermarche.edge.domain.sale.SalesChannel channel =
                    com.intermarche.edge.domain.sale.SalesChannel.of(ticket.salesChannel);
            for (TicketPayment payment : ticket.payments) {
                // BO-07-01-04: an integrated settlement is booked apart, under
                // its channel and the means the third-party system stated.
                String method = channel.isThirdParty()
                        ? channel.name() + "/" + (payment.tenderCode == null || payment.tenderCode.isBlank()
                                ? payment.getMethodKey() : payment.tenderCode)
                        : payment.getMethodKey();
                BigDecimal amount = payment.amount == null ? BigDecimal.ZERO : payment.amount;
                totals.merge(method, amount, BigDecimal::add);
                counts.merge(method, 1, Integer::sum);
            }
        }
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "JOURNEE", "MOYEN_PAIEMENT", "MONTANT", "NB_REGLEMENTS"));
        BigDecimal grandTotal = BigDecimal.ZERO;
        int settlements = 0;
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            rows.add(String.join(SEPARATOR, day.toString(), entry.getKey(),
                    amount(entry.getValue()), String.valueOf(counts.get(entry.getKey()))));
            grandTotal = grandTotal.add(entry.getValue());
            settlements += counts.get(entry.getKey());
        }
        rows.add(String.join(SEPARATOR, day.toString(), "TOTAL",
                amount(grandTotal), String.valueOf(settlements)));
        // BO-02-03-43: the VAT collected on behalf of third parties is not the
        // store's; the cash book states it and the takings net of it.
        BigDecimal thirdPartyVat = thirdPartyVat(tickets);
        if (thirdPartyVat.signum() != 0) {
            rows.add(String.join(SEPARATOR, day.toString(), "TVA_COMPTE_TIERS",
                    amount(thirdPartyVat.negate()), "0"));
            rows.add(String.join(SEPARATOR, day.toString(), "TOTAL_HORS_TVA_TIERS",
                    amount(grandTotal.subtract(thirdPartyVat)), String.valueOf(settlements)));
        }
        return rows;
    }

    /**
     * The VAT the day's tickets collected on behalf of third parties
     * (BO-02-03-43), ventilated per rate like any VAT.
     *
     * @param tickets the closed tickets of the day
     * @return the third parties' VAT, zero when none was sold
     */
    private BigDecimal thirdPartyVat(List<Ticket> tickets) {
        VatBreakdown breakdown = new VatBreakdown();
        for (Ticket ticket : tickets) {
            for (TicketLine line : ticket.lines) {
                if (line.thirdPartySale && !line.cancelled) {
                    breakdown.add(line.vatRate, line.totalPrice);
                }
            }
        }
        return breakdown.getTotalVat();
    }

    /**
     * Builds the FIDELITY/VAT rows (BO-11-01-13): the fidelity settlements of the
     * MONTH ventilated by VAT rate, then one TOTAL row.
     *
     * <p>A fidelity settlement is paid against a basket, not against a rate, so
     * the amount is spread over the ticket's rates PRO RATA of their tax-included
     * share — the only ventilation the data supports. The last rate of a ticket
     * absorbs the rounding residue, so the ventilation sums back exactly to what
     * was settled: a cent lost here is a cent the VAT declaration cannot justify.
     *
     * <p>A settlement on a ticket with no taxable line lands on the zero rate,
     * which is what a settlement with no base is, rather than being dropped.
     *
     * @param day any day of the month
     * @param tickets the closed tickets of that month
     * @return the rows, header first
     */
    private List<String> fidelityVatRows(LocalDate day, List<Ticket> tickets) {
        Map<BigDecimal, BigDecimal> settledByRate = new java.util.TreeMap<>();
        Map<BigDecimal, Integer> ticketsByRate = new java.util.HashMap<>();
        for (Ticket ticket : tickets) {
            BigDecimal settled = fidelitySettledOn(ticket);
            if (settled.signum() == 0) {
                continue;
            }
            for (Map.Entry<BigDecimal, BigDecimal> share
                    : ventilate(settled, ticket).entrySet()) {
                settledByRate.merge(share.getKey(), share.getValue(), BigDecimal::add);
                ticketsByRate.merge(share.getKey(), 1, Integer::sum);
            }
        }
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "MOIS", "TAUX_TVA", "REGLEMENT_TTC",
                "REGLEMENT_HT", "TVA", "NB_TICKETS"));
        String month = YearMonth.from(day).toString();
        BigDecimal totalIncludingTax = BigDecimal.ZERO;
        BigDecimal totalExcludingTax = BigDecimal.ZERO;
        int count = 0;
        for (Map.Entry<BigDecimal, BigDecimal> entry : settledByRate.entrySet()) {
            BigDecimal includingTax = entry.getValue();
            BigDecimal excludingTax = includingTax.divide(
                    BigDecimal.ONE.add(entry.getKey()), 2, java.math.RoundingMode.HALF_UP);
            rows.add(String.join(SEPARATOR, month, rate(entry.getKey()),
                    amount(includingTax), amount(excludingTax),
                    amount(includingTax.subtract(excludingTax)),
                    String.valueOf(ticketsByRate.get(entry.getKey()))));
            totalIncludingTax = totalIncludingTax.add(includingTax);
            totalExcludingTax = totalExcludingTax.add(excludingTax);
            count += ticketsByRate.get(entry.getKey());
        }
        rows.add(String.join(SEPARATOR, month, "TOTAL", amount(totalIncludingTax),
                amount(totalExcludingTax),
                amount(totalIncludingTax.subtract(totalExcludingTax)),
                String.valueOf(count)));
        return rows;
    }

    /**
     * Returns what was settled by FIDELITY on one ticket.
     *
     * @param ticket the closed ticket
     * @return the fidelity amount, zero when none was used
     */
    private BigDecimal fidelitySettledOn(Ticket ticket) {
        BigDecimal settled = BigDecimal.ZERO;
        for (TicketPayment payment : ticket.payments) {
            if (!FIDELITY_METHOD.equals(payment.getMethodKey()) || payment.amount == null) {
                continue;
            }
            settled = settled.add(payment.amount);
        }
        return settled;
    }

    /**
     * Spreads an amount over the VAT rates of a ticket, pro rata of each rate's
     * tax-included share, the last rate absorbing the residue.
     *
     * @param settled the amount to spread, never zero
     * @param ticket the ticket whose rates carry it
     * @return the amount of each rate, a single zero-rate entry when the ticket
     *     has no taxable base
     */
    private Map<BigDecimal, BigDecimal> ventilate(BigDecimal settled, Ticket ticket) {
        Map<BigDecimal, BigDecimal> baseByRate = new java.util.TreeMap<>();
        BigDecimal base = BigDecimal.ZERO;
        for (TicketLine line : ticket.lines) {
            if (line.cancelled || line.totalPrice == null) {
                continue;
            }
            BigDecimal lineRate = line.vatRate == null ? BigDecimal.ZERO : line.vatRate;
            baseByRate.merge(lineRate, line.totalPrice, BigDecimal::add);
            base = base.add(line.totalPrice);
        }
        Map<BigDecimal, BigDecimal> shares = new java.util.LinkedHashMap<>();
        if (base.signum() == 0) {
            shares.put(BigDecimal.ZERO, settled);
            return shares;
        }
        BigDecimal spread = BigDecimal.ZERO;
        int remaining = baseByRate.size();
        for (Map.Entry<BigDecimal, BigDecimal> entry : baseByRate.entrySet()) {
            remaining--;
            BigDecimal share = remaining == 0
                    ? settled.subtract(spread)
                    : settled.multiply(entry.getValue())
                            .divide(base, 2, java.math.RoundingMode.HALF_UP);
            shares.put(entry.getKey(), share);
            spread = spread.add(share);
        }
        return shares;
    }

    /**
     * Builds the FOUR customer-invoice files (BO-11-01-12) of the documents
     * issued during the day, or nothing when the day issued none.
     *
     * <ul>
     *   <li>{@code clifact.dat}: one {@code H} record per document — the
     *       customer identity, civility and coordinates, the due date, the
     *       totals and the eco-tax;</li>
     *   <li>{@code clilfact.dat}: one {@code L} record per billed line;</li>
     *   <li>{@code cliregl.dat}: one {@code R} record per settlement of the
     *       ticket the document states, the customer credit among them;</li>
     *   <li>{@code clitva.dat}: one {@code T} record per VAT rate of the billed
     *       lines, then one {@code E} eco-tax record where the store
     *       administers it.</li>
     * </ul>
     *
     * <p>The lines and settlements are read from the TICKET the document
     * states, because an invoice never copies them — it is derived from a
     * closed ticket and recomputing it is forbidden. A document whose ticket is
     * no longer attached still exports its header and one VAT record carrying
     * its own totals: accounting needs the document, and a missing header is a
     * missing receivable. Cancelled lines are left out: they were never billed.
     *
     * @param day the accounting day
     * @return the four members, keyed by file name, or an empty map
     */
    private Map<String, List<String>> customerInvoiceMembers(LocalDate day) {
        List<Invoice> invoices = invoicesOf(day);
        if (invoices.isEmpty()) {
            return Map.of();
        }
        boolean ecoTax = posSettingsService.invoiceExportEcoTax();
        List<String> headers = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> settlements = new ArrayList<>();
        List<String> vat = new ArrayList<>();
        for (Invoice invoice : invoices) {
            headers.add(invoiceHeader(invoice));
            VatBreakdown breakdown = new VatBreakdown();
            if (invoice.ticket != null) {
                for (TicketLine line : invoice.ticket.lines) {
                    if (line.cancelled) {
                        continue;
                    }
                    lines.add(invoiceLine(invoice, line));
                    breakdown.add(line.vatRate, line.totalPrice);
                }
                for (TicketPayment payment : invoice.ticket.payments) {
                    settlements.add(invoiceSettlement(invoice, payment));
                }
            }
            if (breakdown.getBuckets().isEmpty()) {
                vat.add(vatRecord(invoice.documentNumber, null, invoice.totalExcludingTax,
                        invoice.totalVat, invoice.totalIncludingTax));
            }
            for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
                vat.add(vatRecord(invoice.documentNumber, bucket.rate, bucket.totalExcludingTax,
                        bucket.vatAmount, bucket.totalIncludingTax));
            }
            if (ecoTax) {
                vat.add(new FlatRecord().text("E", 1).text(invoice.documentNumber, 20)
                        .amount(invoice.totalEcoTax, 13).line());
            }
        }
        Map<String, List<String>> members = new LinkedHashMap<>();
        members.put(OutboundFlow.CUSTOMER_INVOICES.fileName(day), headers);
        members.put(datFile("clilfact", day), lines);
        members.put(datFile("cliregl", day), settlements);
        members.put(datFile("clitva", day), vat);
        return members;
    }

    /**
     * Names one member of the customer-invoice flow: its stem, the day and the
     * flat-file extension, the same shape as the flow's own name.
     *
     * @param stem the member's stem
     * @param day the accounting day
     * @return the file name
     */
    private String datFile(String stem, LocalDate day) {
        return stem + "-" + day.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + ".dat";
    }

    /**
     * Builds the {@code H} record of one document (clifact.dat).
     *
     * @param invoice the document
     * @return the record
     */
    private String invoiceHeader(Invoice invoice) {
        String civility = invoice.customer == null ? "" : invoice.customer.civility;
        Address address = invoice.customerAddress;
        return new FlatRecord()
                .text("H", 1)
                .text(invoice.documentNumber, 20)
                .text(invoice.documentType == null ? "" : invoice.documentType.getPrefix(), 1)
                .instant(invoice.issueDate)
                .text(invoice.terminalId, 10)
                .text(invoice.ticketNumber, 30)
                .text(invoice.customerAccountNumber, 15)
                .text(civility, 10)
                .text(invoice.customerName, 40)
                .text(invoice.customerContact, 40)
                .text(address == null ? "" : address.streetLine1, 40)
                .text(address == null ? "" : address.streetLine2, 40)
                .text(address == null ? "" : address.postalCode, 10)
                .text(address == null ? "" : address.city, 30)
                .text(address == null ? "" : address.country, 3)
                .text(invoice.customerSiret, 14)
                .text(invoice.customerVatNumber, 15)
                .text(invoice.customerTaxId, 15)
                .date(invoice.customerDueDate)
                .amount(invoice.totalExcludingTax, 13)
                .amount(invoice.totalIncludingTax, 13)
                .amount(invoice.totalVat, 13)
                .amount(invoice.totalEcoTax, 13)
                .line();
    }

    /**
     * Builds the {@code L} record of one billed line (clilfact.dat).
     *
     * @param invoice the document
     * @param line the billed line
     * @return the record
     */
    private String invoiceLine(Invoice invoice, TicketLine line) {
        return new FlatRecord()
                .text("L", 1)
                .text(invoice.documentNumber, 20)
                .number(line.lineNumber, 5)
                .text(line.ean, 13)
                .text(line.plu, 10)
                .text(line.productLabel, 40)
                .text(line.familyCode, 10)
                .amount(line.quantity, 11)
                .amount(line.unitPrice, 11)
                .number(basisPoints(line.vatRate), 6)
                .amount(line.totalPrice, 13)
                .line();
    }

    /**
     * Builds the {@code R} record of one settlement (cliregl.dat): its rank,
     * its tender, the administered tender it was taken as, its amount and the
     * card authorisation when it is a card one.
     *
     * @param invoice the document
     * @param payment the settlement
     * @return the record
     */
    private String invoiceSettlement(Invoice invoice, TicketPayment payment) {
        String authorisation = payment instanceof com.intermarche.edge.domain.payment.CardPayment card
                ? card.authorizationNumber : null;
        return new FlatRecord()
                .text("R", 1)
                .text(invoice.documentNumber, 20)
                .number(payment.paymentIndex, 3)
                .text(payment.getMethodKey(), 12)
                .text(payment.tenderCode, 20)
                .amount(payment.amount, 13)
                .text(authorisation, 12)
                .line();
    }

    /**
     * Builds one {@code T} VAT record (clitva.dat).
     *
     * @param documentNumber the document number
     * @param rate the VAT rate as a fraction, or null when the document states none
     * @param excludingTax the taxable base
     * @param vatAmount the VAT
     * @param includingTax the tax-included amount
     * @return the record
     */
    private String vatRecord(String documentNumber, BigDecimal rate, BigDecimal excludingTax,
            BigDecimal vatAmount, BigDecimal includingTax) {
        return new FlatRecord()
                .text("T", 1)
                .text(documentNumber, 20)
                .number(basisPoints(rate), 6)
                .amount(excludingTax, 13)
                .amount(vatAmount, 13)
                .amount(includingTax, 13)
                .line();
    }

    /**
     * Reads the payment codes read at the tills during one accounting day
     * (BO-11-01-18).
     *
     * <p>Package-private so a test can serve readings without a database.
     *
     * @param day the accounting day
     * @return the readings of that day
     */
    List<com.intermarche.edge.domain.barcode.ScannedBarcode> scannedBarcodesOf(LocalDate day) {
        return com.intermarche.edge.domain.barcode.ScannedBarcode.listBetween(
                accountingDay.startOf(day), accountingDay.endOf(day));
    }

    /**
     * Builds the GS1 PAYMENTS flow (BO-11-01-18): one {@code SCAN} row per
     * payment code read at a till — GS1 or not, known or not — and one
     * {@code REGLEMENT} row per voucher settled on a closed ticket, then one
     * TOTAL row; or nothing when the day holds neither.
     *
     * <p>The BRULE column is the centralised burn: a code is burned once ANY
     * closed sale of the shop settled it, on whichever register and whichever
     * day, which is what the central voucher management reconciles on.
     *
     * @param flow the flow, for its file name
     * @param day the accounting day
     * @return the single member, or an empty map
     */
    private Map<String, List<String>> gs1Members(OutboundFlow flow, LocalDate day) {
        List<Ticket> tickets = closedTicketsOf(day);
        List<com.intermarche.edge.domain.barcode.ScannedBarcode> readings = scannedBarcodesOf(day);
        if (tickets.isEmpty() && readings.isEmpty()) {
            return Map.of();
        }
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        java.util.Set<String> gs1Codes = new java.util.HashSet<>();
        for (com.intermarche.edge.domain.barcode.ScannedBarcode reading : readings) {
            codes.add(reading.code);
            if (reading.gs1) {
                gs1Codes.add(reading.code);
            }
        }
        java.util.Set<String> burned = voucherBurnService.burnedAmong(codes);
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "JOURNEE", "SOURCE", "TERMINAL", "NUM_TICKET", "DATE_HEURE",
                "CODE", "GS1", "TYPE", "LIBELLE", "MONTANT", "CONNU", "BRULE"));
        int settled = 0;
        BigDecimal settledAmount = BigDecimal.ZERO;
        for (com.intermarche.edge.domain.barcode.ScannedBarcode reading : readings) {
            rows.add(String.join(SEPARATOR, day.toString(), "SCAN", text(reading.terminalId),
                    text(reading.ticketNumber), stamp(reading.scannedAt),
                    text(reading.code), reading.gs1 || isGs1(reading.code) ? "O" : "N", text(reading.typeCode),
                    text(reading.typeLabel), amount(reading.amount), reading.found ? "O" : "N",
                    burned.contains(reading.code) ? "O" : "N"));
        }
        for (Ticket ticket : tickets) {
            for (TicketPayment payment : ticket.payments) {
                if (!(payment instanceof com.intermarche.edge.domain.payment.VoucherPayment voucher)) {
                    continue;
                }
                rows.add(String.join(SEPARATOR, day.toString(), "REGLEMENT", text(ticket.terminalId),
                        text(ticket.ticketNumber), stamp(ticket.closingDate), text(voucher.voucherNumber),
                        gs1Codes.contains(voucher.voucherNumber) || isGs1(voucher.voucherNumber) ? "O" : "N",
                        text(voucher.tenderCode), text(voucher.voucherLabel), amount(voucher.amount), "O", "O"));
                settled++;
                settledAmount = settledAmount.add(nullSafe(voucher.amount));
            }
        }
        rows.add(String.join(SEPARATOR, day.toString(), "TOTAL", "", "", "", String.valueOf(readings.size()),
                "", "", "", amount(settledAmount), "", String.valueOf(settled)));
        return single(flow, day, rows);
    }

    /**
     * Renders an instant of a flow row to the second, or empty when absent:
     * always {@code yyyy-MM-ddTHH:mm:ss}, never the minute alone nor a fraction.
     *
     * @param value the instant, possibly null
     * @return the rendered instant
     */
    static String stamp(LocalDateTime value) {
        return value == null ? "" : value.format(STAMP);
    }

    /**
     * Tells whether a payment code is a GS1 payload: an Element String with
     * its application identifiers in parentheses or its FNC1 separators, a
     * symbology identifier (DataMatrix, QR, Databar, GS1-128) or a Digital
     * Link address.
     *
     * @param code the code as it was read, possibly null
     * @return true for a GS1 payload
     */
    static boolean isGs1(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String trimmed = code.trim();
        return trimmed.startsWith("(") || trimmed.indexOf('\u001d') >= 0 || trimmed.startsWith("]d2")
                || trimmed.startsWith("]Q3") || trimmed.startsWith("]e0") || trimmed.startsWith("]C1")
                || trimmed.contains("/01/");
    }

    /**
     * Reads the vignette declarations made at the tills during one accounting
     * day (BO-11-01-20).
     *
     * <p>Package-private so a test can serve declarations without a database.
     *
     * @param day the accounting day
     * @return the declarations, oldest first
     */
    List<com.intermarche.edge.domain.session.TechnicalEvent> vignetteDeclarationsOf(LocalDate day) {
        return com.intermarche.edge.domain.session.TechnicalEvent.list(
                "eventType = ?1 and eventDate >= ?2 and eventDate < ?3 order by eventDate, id",
                com.intermarche.edge.domain.session.TechnicalEvent.EventType.VIGNETTES_DECLARED,
                accountingDay.startOf(day), accountingDay.endOf(day));
    }

    /**
     * Builds the VIGNETTES flow (BO-11-01-20): per register and per shift, the
     * tickets, the vignettes the administered formula makes due, the vignettes
     * the cashier declared and the gap, then one TOTAL row; or nothing when the
     * day holds neither a sale nor a declaration.
     *
     * <p>A declaration made on a shift that rang no sale still makes its row: a
     * cashier who handed out vignettes without selling is precisely the gap the
     * flow is there to show. A sale rung on no shift — an e-commerce order —
     * makes no vignette due and no row.
     *
     * @param flow the flow, for its file name
     * @param day the accounting day
     * @return the single member, or an empty map
     */
    private Map<String, List<String>> vignetteMembers(OutboundFlow flow, LocalDate day) {
        List<Ticket> tickets = closedTicketsOf(day);
        List<com.intermarche.edge.domain.session.TechnicalEvent> declarations = vignetteDeclarationsOf(day);
        if (tickets.isEmpty() && declarations.isEmpty()) {
            return Map.of();
        }
        BigDecimal step = posSettingsService.vignetteAmountStep();
        int perStep = posSettingsService.vignettePerStep();
        java.util.TreeMap<String, long[]> cells = new java.util.TreeMap<>();
        Map<String, String> cashiers = new java.util.HashMap<>();
        for (Ticket ticket : tickets) {
            if (ticket.session == null) {
                // Vignettes are handed out at a till, on a shift: a sale rung on
                // no shift (an e-commerce order) makes none due.
                continue;
            }
            String key = text(ticket.terminalId) + SEPARATOR + text(ticket.session.sessionNumber);
            long[] cell = cells.computeIfAbsent(key, k -> new long[3]);
            cell[0]++;
            cell[1] += vignettesFor(ticket.totalIncludingTax, step, perStep);
            cashiers.putIfAbsent(key, cashierOf(ticket));
        }
        for (com.intermarche.edge.domain.session.TechnicalEvent declaration : declarations) {
            Map<String, String> detail = detailOf(declaration.detail);
            String key = text(declaration.terminalId) + SEPARATOR + text(detail.get("session"));
            cells.computeIfAbsent(key, k -> new long[3])[2] += parseCount(detail.get("count"));
        }
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "JOURNEE", "TERMINAL", "PRISE_DE_POSTE", "CAISSIERE",
                "NB_TICKETS", "VIGNETTES_THEORIQUES", "VIGNETTES_DECLAREES", "ECART"));
        long[] total = new long[3];
        for (Map.Entry<String, long[]> entry : cells.entrySet()) {
            long[] cell = entry.getValue();
            rows.add(String.join(SEPARATOR, day.toString(), entry.getKey(),
                    cashiers.getOrDefault(entry.getKey(), ""), String.valueOf(cell[0]),
                    String.valueOf(cell[1]), String.valueOf(cell[2]), String.valueOf(cell[2] - cell[1])));
            for (int index = 0; index < 3; index++) {
                total[index] += cell[index];
            }
        }
        rows.add(String.join(SEPARATOR, day.toString(), "TOTAL", "", "", String.valueOf(total[0]),
                String.valueOf(total[1]), String.valueOf(total[2]), String.valueOf(total[2] - total[1])));
        return single(flow, day, rows);
    }

    /**
     * The vignettes a sale makes due under the administered formula
     * (BO-11-01-20): the whole purchase steps of its tax-included total, times
     * the vignettes per step.
     *
     * @param total the tax-included total, possibly null
     * @param step the purchase step, zero meaning no vignette
     * @param perStep the vignettes per whole step
     * @return the vignettes due, never negative
     */
    static long vignettesFor(BigDecimal total, BigDecimal step, int perStep) {
        if (total == null || total.signum() <= 0 || step.signum() <= 0) {
            return 0L;
        }
        return total.divide(step, 0, java.math.RoundingMode.DOWN).longValue() * perStep;
    }

    /**
     * Reads the {@code key=value;key=value} detail of a declaration.
     *
     * @param detail the detail, possibly null
     * @return the keys and their values, empty when there are none
     */
    private Map<String, String> detailOf(String detail) {
        Map<String, String> values = new java.util.HashMap<>();
        if (detail == null) {
            return values;
        }
        for (String part : detail.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0) {
                values.put(part.substring(0, separator).trim(), part.substring(separator + 1).trim());
            }
        }
        return values;
    }

    /**
     * Reads a declared count, a missing or unreadable one counting as none.
     *
     * @param raw the count, possibly null
     * @return the count, zero when unreadable or negative
     */
    private long parseCount(String raw) {
        try {
            return Math.max(0L, Long.parseLong(raw));
        } catch (NumberFormatException unreadable) {
            return 0L;
        }
    }

    /**
     * Builds the ERP-INVOICE rows (BO-11-01-22): one row per document issued
     * during the day, with the customer, the totals and the eco-tax, then one
     * TOTAL row.
     *
     * <p>No line detail: the ERP books a document and follows an outstanding
     * balance, it does not re-price a basket. The duplicate flag travels, because
     * a reprint is not a second receivable and the receiving side must be able to
     * tell.
     *
     * @param day the accounting day
     * @param invoices the documents issued that day
     * @return the rows, header first
     */
    private List<String> erpInvoiceRows(LocalDate day, List<Invoice> invoices) {
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "JOURNEE", "NUM_DOCUMENT", "TYPE", "DATE_EMISSION",
                "TERMINAL", "NUM_TICKET", "COMPTE_CLIENT", "NOM_CLIENT", "SIRET",
                "TVA_INTRA", "NIF", "ECHEANCE", "TOTAL_HT", "TOTAL_TTC", "TVA",
                "ECOTAXE", "DUPLICATA"));
        BigDecimal totalExcludingTax = BigDecimal.ZERO;
        BigDecimal totalIncludingTax = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;
        BigDecimal totalEcoTax = BigDecimal.ZERO;
        for (Invoice invoice : invoices) {
            rows.add(String.join(SEPARATOR, day.toString(), text(invoice.documentNumber),
                    invoice.documentType == null ? "" : invoice.documentType.name(),
                    stamp(invoice.issueDate),
                    text(invoice.terminalId), text(invoice.ticketNumber),
                    text(invoice.customerAccountNumber), text(invoice.customerName),
                    text(invoice.customerSiret), text(invoice.customerVatNumber),
                    text(invoice.customerTaxId),
                    invoice.customerDueDate == null ? "" : invoice.customerDueDate.toString(),
                    amount(invoice.totalExcludingTax), amount(invoice.totalIncludingTax),
                    amount(invoice.totalVat), amount(invoice.totalEcoTax),
                    invoice.isDuplicate() ? "O" : "N"));
            totalExcludingTax = totalExcludingTax.add(nullSafe(invoice.totalExcludingTax));
            totalIncludingTax = totalIncludingTax.add(nullSafe(invoice.totalIncludingTax));
            totalVat = totalVat.add(nullSafe(invoice.totalVat));
            totalEcoTax = totalEcoTax.add(nullSafe(invoice.totalEcoTax));
        }
        rows.add(String.join(SEPARATOR, day.toString(), "TOTAL", "", "", "", "", "", "",
                "", "", "", "", amount(totalExcludingTax), amount(totalIncludingTax),
                amount(totalVat), amount(totalEcoTax), String.valueOf(invoices.size())));
        return rows;
    }

    /**
     * Builds the ORDER-VAT rows (BO-11-01-23): the day's orders ventilated by VAT
     * rate, then one TOTAL row.
     *
     * <p>An order with no line detail is counted whole, at the zero rate: its
     * total is a commitment the tax declaration must see, and inventing a rate for
     * it would be worse than stating it has none. The deposits themselves are NOT
     * here — a deposit is a settlement and is already in the cash book.
     *
     * @param day the accounting day
     * @param orders the orders dated that day
     * @return the rows, header first
     */
    private List<String> orderVatRows(LocalDate day, List<CustomerOrder> orders) {
        VatBreakdown breakdown = new VatBreakdown();
        Map<BigDecimal, Integer> counts = new java.util.TreeMap<>();
        for (CustomerOrder order : orders) {
            if (order.lines.isEmpty()) {
                breakdown.add(BigDecimal.ZERO, order.totalAmount);
                counts.merge(BigDecimal.ZERO, 1, Integer::sum);
                continue;
            }
            for (CustomerOrder.OrderLine line : order.lines) {
                BigDecimal lineRate = line.vatRate == null ? BigDecimal.ZERO : line.vatRate;
                breakdown.add(lineRate, line.totalPrice);
                counts.merge(lineRate.stripTrailingZeros(), 1, Integer::sum);
            }
        }
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "JOURNEE", "TAUX_TVA", "TOTAL_TTC", "TOTAL_HT",
                "TVA", "NB_LIGNES"));
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            Integer lines = counts.get(bucket.rate.stripTrailingZeros());
            rows.add(String.join(SEPARATOR, day.toString(), rate(bucket.rate),
                    amount(bucket.totalIncludingTax), amount(bucket.totalExcludingTax),
                    amount(bucket.vatAmount), String.valueOf(lines)));
        }
        rows.add(String.join(SEPARATOR, day.toString(), "TOTAL",
                amount(breakdown.getTotalIncludingTax()),
                amount(breakdown.getTotalExcludingTax()), amount(breakdown.getTotalVat()),
                String.valueOf(orders.size())));
        return rows;
    }

    /**
     * Builds the three PANEL members (BO-11-01-19), each under the file name the
     * RFP gives it, suffixed by the three-digit day of the year.
     *
     * <p>{@code Scannels} is the scan panel — what was rung, by article;
     * {@code Scvenmag} the store's own synthesis; {@code Tlogextr} the transaction
     * log extraction. The column contracts come from the panel provider's annex,
     * which this repository does not hold: what is guaranteed here is the three
     * members, their names and the archive that carries them.
     *
     * @param day the accounting day
     * @param tickets the closed tickets of that day
     * @return the three members, keyed by their name inside the archive
     */
    private Map<String, List<String>> panelMembers(LocalDate day, List<Ticket> tickets) {
        Map<String, BigDecimal> quantityByEan = new LinkedHashMap<>();
        Map<String, BigDecimal> revenueByEan = new LinkedHashMap<>();
        VatBreakdown breakdown = new VatBreakdown();
        int items = 0;
        for (Ticket ticket : tickets) {
            items += ticket.itemCount;
            for (TicketLine line : ticket.lines) {
                if (line.cancelled) {
                    continue;
                }
                String ean = text(line.ean);
                quantityByEan.merge(ean, nullSafe(line.quantity), BigDecimal::add);
                revenueByEan.merge(ean, nullSafe(line.totalPrice), BigDecimal::add);
                breakdown.add(line.vatRate, line.totalPrice);
            }
        }
        List<String> scanned = new ArrayList<>();
        scanned.add(String.join(SEPARATOR, "JOURNEE", "EAN", "QUANTITE", "CA_TTC"));
        for (Map.Entry<String, BigDecimal> entry : quantityByEan.entrySet()) {
            scanned.add(String.join(SEPARATOR, day.toString(), entry.getKey(),
                    quantity(entry.getValue()), amount(revenueByEan.get(entry.getKey()))));
        }
        List<String> store = new ArrayList<>();
        store.add(String.join(SEPARATOR, "JOURNEE", "NB_TICKETS", "NB_ARTICLES",
                "TOTAL_TTC", "TOTAL_HT", "TVA"));
        store.add(String.join(SEPARATOR, day.toString(), String.valueOf(tickets.size()),
                String.valueOf(items), amount(breakdown.getTotalIncludingTax()),
                amount(breakdown.getTotalExcludingTax()), amount(breakdown.getTotalVat())));
        List<String> log = new ArrayList<>();
        log.add(String.join(SEPARATOR, "JOURNEE", "NUM_TICKET", "DATE_HEURE", "TERMINAL",
                "NB_ARTICLES", "TOTAL_TTC"));
        for (Ticket ticket : tickets) {
            log.add(String.join(SEPARATOR, day.toString(), text(ticket.ticketNumber),
                    stamp(ticket.closingDate),
                    text(ticket.terminalId), String.valueOf(ticket.itemCount),
                    amount(ticket.totalIncludingTax)));
        }
        Map<String, List<String>> members = new LinkedHashMap<>();
        members.put("Scannels." + julianDay(day), scanned);
        members.put("Scvenmag." + julianDay(day), store);
        members.put("Tlogextr." + julianDay(day), log);
        return members;
    }

    /**
     * Returns a VAT rate in basis points, the form a positioned-field file carries
     * it in: {@code 0.0550} becomes {@code 550}.
     *
     * @param value the rate as a fraction, possibly null
     * @return the rate in basis points, zero for null
     */
    private long basisPoints(BigDecimal value) {
        if (value == null) {
            return 0L;
        }
        return value.movePointRight(4).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
    }

    /**
     * Returns an amount, or zero when it is absent.
     *
     * @param value the amount, possibly null
     * @return the amount, never null
     */
    private BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Reads the checkout points of the park, which is where the ISLAND of a
     * register comes from.
     *
     * <p>Package-private so a test can serve a park without a database: the query
     * is one line and is not what the folds are about.
     *
     * @return every checkout point of the park, in terminal order
     */
    List<CheckoutPoint> checkoutPoints() {
        return CheckoutPoint.listAllOrdered();
    }

    /**
     * Reads the codes of the islands excluded from the Planexa flow
     * (BO-03-10-06).
     *
     * <p>Package-private for the same reason as {@link #checkoutPoints()}.
     *
     * @return the excluded island codes, empty when none is
     */
    java.util.Set<String> planexaExcludedIslands() {
        java.util.Set<String> excluded = new java.util.HashSet<>();
        for (com.intermarche.edge.domain.store.CheckoutIsland island
                : com.intermarche.edge.domain.store.CheckoutIsland
                        .<com.intermarche.edge.domain.store.CheckoutIsland>list("planexaExcluded = true")) {
            excluded.add(island.code);
        }
        return excluded;
    }

    /**
     * Indexes the park by terminal, so the island of a ticket is one lookup.
     *
     * <p>A point without a terminal identifier is skipped rather than indexed
     * under null: it cannot be the register a ticket was rung on, and a null key
     * would make every ticket of an unknown terminal land on it.
     *
     * @return the island code of each known terminal, empty when the park is
     */
    private Map<String, String> islandByTerminal() {
        Map<String, String> islands = new LinkedHashMap<>();
        for (CheckoutPoint point : checkoutPoints()) {
            if (point.terminalId == null) {
                continue;
            }
            islands.put(point.terminalId, point.islandCode == null ? "" : point.islandCode);
        }
        return islands;
    }

    /**
     * Builds the PLANEXA rows (BO-03-10-06): one row per register and per hour
     * slot of the day — tickets, articles and takings — the base of the
     * hostesses' planning, then one TOTAL row.
     *
     * <p>The registers of an island EXCLUDED from Planexa are left out entirely:
     * a self-service bank or a drive needs no hostess, and counting its
     * transactions would plan hostesses for lanes nobody staffs.
     *
     * @param day the accounting day
     * @param tickets the closed tickets of that day
     * @return the rows, header first
     */
    private List<String> planexaRows(LocalDate day, List<Ticket> tickets) {
        Map<String, String> islands = islandByTerminal();
        java.util.Set<String> excluded = planexaExcludedIslands();
        java.util.TreeMap<String, long[]> counts = new java.util.TreeMap<>();
        java.util.TreeMap<String, BigDecimal> takings = new java.util.TreeMap<>();
        long ticketCount = 0;
        long itemCount = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Ticket ticket : tickets) {
            String terminal = text(ticket.terminalId);
            String island = islands.getOrDefault(terminal, "");
            if (excluded.contains(island)) {
                continue;
            }
            String slot = ticket.closingDate == null ? "" : String.format("%02d:00", ticket.closingDate.getHour());
            String key = terminal + SEPARATOR + island + SEPARATOR + slot;
            long[] cell = counts.computeIfAbsent(key, k -> new long[2]);
            cell[0]++;
            cell[1] += ticket.itemCount;
            BigDecimal amount = ticket.totalIncludingTax == null ? BigDecimal.ZERO : ticket.totalIncludingTax;
            takings.merge(key, amount, BigDecimal::add);
            ticketCount++;
            itemCount += ticket.itemCount;
            total = total.add(amount);
        }
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "JOURNEE", "TERMINAL", "CODE_ILOT", "TRANCHE",
                "NB_TICKETS", "NB_ARTICLES", "TOTAL_TTC"));
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            rows.add(String.join(SEPARATOR, day.toString(), entry.getKey(),
                    String.valueOf(entry.getValue()[0]), String.valueOf(entry.getValue()[1]),
                    amount(takings.get(entry.getKey()))));
        }
        rows.add(String.join(SEPARATOR, day.toString(), "TOTAL", "", "", String.valueOf(ticketCount),
                String.valueOf(itemCount), amount(total)));
        return rows;
    }

    /**
     * Builds the FAMILY-SALES rows (BO-11-01-08): one row per product family and
     * per checkout island, with the quantity, the VAT breakdown, the discount
     * granted on the lines and the number of tickets concerned, then one TOTAL
     * row.
     *
     * <p>CANCELLED lines are left out, as in the revenue flow: they were not sold.
     *
     * <p>The DISCOUNT column holds what the lines themselves carry — the gap
     * between the original unit price and the one charged. The UPSTREAM column
     * holds the immediate discounts the upstream refunds: the advantages the
     * promotion engine ALLOCATED to each line it valued, kept with the ticket
     * line by line, so each one is attributed to the family of the line that
     * carries it — never spread by a key of the node's invention. Which offers
     * the upstream refunds is administered by their label (all of them when
     * nothing is administered).
     *
     * @param day the accounting day
     * @param tickets the closed tickets of that day
     * @return the rows, header first
     */
    private List<String> familySalesRows(LocalDate day, List<Ticket> tickets) {
        Map<String, String> islands = islandByTerminal();
        Map<String, BigDecimal> upstream = upstreamDiscounts(tickets);
        Map<String, FamilyCell> cells = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            String island = islands.getOrDefault(ticket.terminalId, "");
            for (TicketLine line : ticket.lines) {
                if (line.cancelled) {
                    continue;
                }
                String familyCode = text(line.familyCode);
                FamilyCell cell = cells.computeIfAbsent(island + "|" + familyCode,
                        key -> new FamilyCell(island, familyCode));
                cell.add(line, text(ticket.ticketNumber), lineDiscount(line),
                        upstream.getOrDefault(ticket.id + "|" + line.lineUid, BigDecimal.ZERO));
            }
        }
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "JOURNEE", "CODE_ILOT", "CODE_FAMILLE",
                "LIBELLE_FAMILLE", "QUANTITE", "TOTAL_TTC", "TOTAL_HT", "TVA",
                "REMISES", "REMISES_AMONT", "NB_TICKETS"));
        BigDecimal totalIncludingTax = BigDecimal.ZERO;
        BigDecimal totalExcludingTax = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalUpstream = BigDecimal.ZERO;
        for (FamilyCell cell : cells.values()) {
            rows.add(String.join(SEPARATOR, day.toString(), cell.island, cell.familyCode,
                    cell.familyLabel, quantity(cell.quantity),
                    amount(cell.breakdown.getTotalIncludingTax()),
                    amount(cell.breakdown.getTotalExcludingTax()),
                    amount(cell.breakdown.getTotalVat()), amount(cell.discount),
                    amount(cell.upstream), String.valueOf(cell.tickets.size())));
            totalIncludingTax = totalIncludingTax.add(cell.breakdown.getTotalIncludingTax());
            totalExcludingTax = totalExcludingTax.add(cell.breakdown.getTotalExcludingTax());
            totalVat = totalVat.add(cell.breakdown.getTotalVat());
            totalDiscount = totalDiscount.add(cell.discount);
            totalUpstream = totalUpstream.add(cell.upstream);
        }
        rows.add(String.join(SEPARATOR, day.toString(), "TOTAL", "", "", "",
                amount(totalIncludingTax), amount(totalExcludingTax), amount(totalVat),
                amount(totalDiscount), amount(totalUpstream), String.valueOf(tickets.size())));
        return rows;
    }

    /**
     * Reads the engine valuations of the given tickets, line by line
     * (BO-11-01-08).
     *
     * <p>Package-private so a test can serve valuations without a database.
     *
     * @param ticketIds the ids of the tickets, never empty
     * @return their line valuations
     */
    List<com.intermarche.edge.domain.sale.TicketLineValuation> lineValuationsOf(List<Long> ticketIds) {
        return com.intermarche.edge.domain.sale.TicketLineValuation.list("ticket.id in ?1", ticketIds);
    }

    /**
     * Sums, line by line, the immediate discounts the upstream refunds
     * (BO-11-01-08): the advantage the engine allocated to each valued line,
     * when its offer is one the upstream refunds.
     *
     * @param tickets the closed tickets of the day
     * @return the upstream discount of each line, keyed by ticket id and line uid
     */
    private Map<String, BigDecimal> upstreamDiscounts(List<Ticket> tickets) {
        List<Long> ids = new ArrayList<>();
        for (Ticket ticket : tickets) {
            if (ticket.id != null) {
                ids.add(ticket.id);
            }
        }
        Map<String, BigDecimal> discounts = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return discounts;
        }
        List<String> prefixes = posSettingsService.exportUpstreamOffers();
        for (com.intermarche.edge.domain.sale.TicketLineValuation valuation : lineValuationsOf(ids)) {
            if (valuation.advantageAmount == null || valuation.advantageAmount.signum() == 0
                    || !refundedUpstream(valuation.offerLabel, prefixes)) {
                continue;
            }
            discounts.merge(valuation.ticket.id + "|" + valuation.lineUid,
                    valuation.advantageAmount.abs(), BigDecimal::add);
        }
        return discounts;
    }

    /**
     * Tells whether the upstream refunds an offer: every offer when no label is
     * administered, otherwise the offers whose label starts with one of them.
     *
     * @param offerLabel the offer label of the valuation, possibly null
     * @param prefixes the administered label prefixes
     * @return true when the upstream refunds the offer
     */
    private boolean refundedUpstream(String offerLabel, List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return true;
        }
        if (offerLabel == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (offerLabel.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the DAILY-TRANSACTIONS rows (BO-11-01-14): one row per ticket line,
     * with the ticket it belongs to, the register, the cashier and the price
     * detail.
     *
     * <p>Cancelled lines are PRESENT here, flagged in the last column, unlike in
     * every aggregated flow. This file is the detail the decisional re-aggregates
     * its own way, and a cancelled line removed from it can never be counted
     * again; a cancelled line left in it is one column away from being ignored.
     *
     * @param day the accounting day
     * @param tickets the closed tickets of that day
     * @return the rows, header first
     */
    private List<String> dailyTransactionRows(LocalDate day, List<Ticket> tickets) {
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "JOURNEE", "NUM_TICKET", "DATE_HEURE", "TERMINAL",
                "CAISSIERE", "NUM_LIGNE", "EAN", "PLU", "LIBELLE", "CODE_FAMILLE",
                "QUANTITE", "PRIX_UNITAIRE", "PRIX_ORIGINE", "REMISE", "TOTAL_TTC",
                "TAUX_TVA", "ANNULEE"));
        for (Ticket ticket : tickets) {
            String moment = stamp(ticket.closingDate);
            String cashier = cashierOf(ticket);
            for (TicketLine line : ticket.lines) {
                rows.add(String.join(SEPARATOR, day.toString(), text(ticket.ticketNumber),
                        moment, text(ticket.terminalId), cashier,
                        String.valueOf(line.lineNumber), text(line.ean), text(line.plu),
                        text(line.productLabel), text(line.familyCode),
                        quantity(line.quantity), amount(line.unitPrice),
                        amount(line.originalUnitPrice), amount(lineDiscount(line)),
                        amount(line.totalPrice), rate(line.vatRate),
                        line.cancelled ? "O" : "N"));
            }
        }
        return rows;
    }

    /**
     * Builds the PERIOD-SUMMARY rows (BO-11-01-17): per register, the ticket
     * counts and the ticket-number range of the accounting day, then one TOTAL
     * row.
     *
     * <p>The point of the flow is COMPLETENESS: the receiving side checks that no
     * ticket of the day is missing, which needs the first and last number of each
     * register, not only a count. NON-SALES are counted apart — a ticket with no
     * item is a drawer opening, and a receiving side that cannot tell the two
     * apart reads a day of drawer openings as a day of free sales.
     *
     * @param day the accounting day
     * @param tickets the closed tickets of that day
     * @return the rows, header first
     */
    private List<String> periodSummaryRows(LocalDate day, List<Ticket> tickets) {
        Map<String, RegisterCell> cells = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            String terminal = text(ticket.terminalId);
            cells.computeIfAbsent(terminal, RegisterCell::new).add(ticket);
        }
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "JOURNEE", "TERMINAL", "NB_TICKETS", "NB_VENTES",
                "NB_NON_VENTES", "PREMIER_TICKET", "DERNIER_TICKET", "TOTAL_TTC"));
        int sales = 0;
        int nonSales = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (RegisterCell cell : cells.values()) {
            rows.add(String.join(SEPARATOR, day.toString(), cell.terminal,
                    String.valueOf(cell.sales + cell.nonSales), String.valueOf(cell.sales),
                    String.valueOf(cell.nonSales), cell.firstTicket, cell.lastTicket,
                    amount(cell.total)));
            sales += cell.sales;
            nonSales += cell.nonSales;
            total = total.add(cell.total);
        }
        rows.add(String.join(SEPARATOR, day.toString(), "TOTAL",
                String.valueOf(sales + nonSales), String.valueOf(sales),
                String.valueOf(nonSales), "", "", amount(total)));
        return rows;
    }

    /**
     * LC-01-03-01: load only the operator's pause transitions on this register,
     * within the shift bounds, including an unfinished final pause.
     * @param terminal register identifier
     * @param badge operator badge, not the display login
     * @param from shift opening
     * @param to shift closing or last sale
     * @return ordered pause/resume journal entries
     */
    List<com.intermarche.edge.domain.session.TechnicalEvent> pauseEvents(
            String terminal, String badge, LocalDateTime from, LocalDateTime to) {
        return com.intermarche.edge.domain.session.TechnicalEvent.list(
                "terminalId = ?1 and operatorBadgeId = ?2 and eventDate >= ?3 and eventDate <= ?4"
                        + " and eventType in (?5, ?6) order by eventDate, id",
                terminal, badge, from, to,
                com.intermarche.edge.domain.session.TechnicalEvent.EventType.REGISTER_LOCKED,
                com.intermarche.edge.domain.session.TechnicalEvent.EventType.REGISTER_UNLOCKED);
    }

    /**
     * Builds the PRODUCTIVITY rows (BO-11-01-21): one row per cashier and per
     * SHIFT, with the tickets, the items, the revenue, the shift's duration and
     * the two indicators the requirement asks for — items an hour and average
     * basket.
     *
     * <p>The shift is the cash session, not the day: the same cashier on two
     * registers is two shifts, and a productivity figure averaged over both says
     * nothing about either. A shift still open at production time is measured up
     * to its last sale rather than skipped, because the day is finished even when
     * a session was left open.
     *
     * @param day the accounting day
     * @param tickets the closed tickets of that day
     * @return the rows, header first
     */
    private List<String> cashierProductivityRows(LocalDate day, List<Ticket> tickets) {
        Map<String, ShiftCell> cells = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            CashSession session = ticket.session;
            String shift = session == null ? "" : text(session.sessionNumber);
            String cashier = cashierOf(ticket);
            ShiftCell cell = cells.computeIfAbsent(shift + "|" + cashier + "|" + text(ticket.terminalId),
                    key -> new ShiftCell(shift, cashier, text(ticket.terminalId)));
            cell.add(ticket, session);
        }
        List<String> rows = new ArrayList<>();
        rows.add(String.join(SEPARATOR, "JOURNEE", "PRISE_DE_POSTE", "CAISSIERE", "TERMINAL",
                "NB_TICKETS", "NB_ARTICLES", "TOTAL_TTC", "DUREE_MINUTES",
                "ARTICLES_PAR_HEURE", "PANIER_MOYEN"));
        for (ShiftCell cell : cells.values()) {
            LocalDateTime end = cell.closedAt == null ? cell.lastSale : cell.closedAt;
            List<com.intermarche.edge.domain.session.TechnicalEvent> pauses = List.of();
            if (cell.openedAt != null && end != null && end.isAfter(cell.openedAt)
                    && cell.badge != null && !cell.badge.isBlank()) {
                pauses = pauseEvents(cell.terminal, cell.badge, cell.openedAt, end);
            }
            long minutes = cell.minutes(pauses);
            rows.add(String.join(SEPARATOR, day.toString(), cell.shift, cell.cashier,
                    cell.terminal, String.valueOf(cell.tickets), String.valueOf(cell.items),
                    amount(cell.total), String.valueOf(minutes),
                    amount(cell.itemsPerHour(minutes)), amount(cell.averageBasket())));
        }
        return rows;
    }

    /**
     * Returns the discount carried by a line: what the original unit price was
     * lowered by, times the quantity.
     *
     * <p>Zero whenever the line cannot state one — no original price, no price,
     * no quantity — and zero when the "discount" would be negative, which is a
     * price FORCED UP and not a discount. Each of those is its own refusal rather
     * than one guard, because they do not mean the same thing to a reader.
     *
     * @param line the line
     * @return the discount granted on the line, never null, never negative
     */
    private BigDecimal lineDiscount(TicketLine line) {
        if (line.originalUnitPrice == null || line.unitPrice == null || line.quantity == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal unitSaving = line.originalUnitPrice.subtract(line.unitPrice);
        if (unitSaving.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return unitSaving.multiply(line.quantity);
    }

    /**
     * Returns the cashier a ticket is counted on.
     *
     * <p>The login is the identifier the back office and the reports already use;
     * a ticket with no cashier is counted on {@code INCONNU} rather than dropped,
     * because a ticket that exists must appear in a flow meant to be complete.
     *
     * @param ticket the ticket
     * @return the cashier login, or {@code INCONNU}
     */
    private String cashierOf(Ticket ticket) {
        Employee cashier = ticket.cashier;
        if (cashier == null || cashier.loginName == null) {
            return "INCONNU";
        }
        return text(cashier.loginName);
    }

    /**
     * Renders a text cell: never null, and never carrying the separator.
     *
     * <p>A label holding a semicolon would silently shift every following column
     * of that row, so the separator is replaced by a space rather than escaped:
     * the receiving systems read a plain file, not a quoted CSV dialect.
     *
     * @param value the value, possibly null
     * @return the cell, empty for null
     */
    private String text(String value) {
        return value == null ? "" : value.replace(SEPARATOR, " ");
    }

    /**
     * Renders a VAT rate for the wire: the FRACTION as the referential stores it,
     * on four decimals.
     *
     * <p>Four and not two: a rate is {@code 0.0550} for five and a half percent,
     * and two decimals would send it as {@code 0.06} — a rate that does not exist,
     * on a file the receiving side computes tax with.
     *
     * @param value the rate as a fraction, possibly null
     * @return the rendered rate, {@code 0.0000} for null
     */
    private String rate(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        return safe.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Renders a quantity for the wire: three decimals, so a weight keeps its
     * grammes.
     *
     * @param value the quantity, possibly null
     * @return the rendered quantity, {@code 0.000} for null
     */
    private String quantity(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        return safe.setScale(3, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Renders an amount for the wire: two decimals, a dot, no grouping and no
     * locale.
     *
     * <p>No locale on purpose — the file is read by another system, and a decimal
     * comma would turn every amount into a second column the moment the server's
     * language changed.
     *
     * @param value the amount, possibly null
     * @return the rendered amount, {@code 0.00} for null
     */
    private String amount(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        return safe.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * One (island, family) cell of the FAMILY-SALES fold.
     *
     * <p>A cell rather than five parallel maps: the fold accumulates a VAT
     * breakdown, a quantity, a discount and a set of tickets at once, and five
     * maps keyed by the same string is the shape in which one of them eventually
     * stops being updated.
     */
    private static final class FamilyCell {

        /** The island of the registers the lines were rung on, empty when unknown. */
        private final String island;

        /** The family code, empty when the lines carry none. */
        private final String familyCode;

        /** The family label, taken from the first line that carries one. */
        private String familyLabel = "";

        /** The VAT fold of the cell, which yields its TTC, HT and VAT totals. */
        private final VatBreakdown breakdown = new VatBreakdown();

        /** The quantity sold in the cell. */
        private BigDecimal quantity = BigDecimal.ZERO;

        /** The discount granted on the lines of the cell. */
        private BigDecimal discount = BigDecimal.ZERO;

        /** The immediate discounts of the cell the upstream refunds (BO-11-01-08). */
        private BigDecimal upstream = BigDecimal.ZERO;

        /** The tickets the cell was sold on, each counted once. */
        private final java.util.Set<String> tickets = new java.util.LinkedHashSet<>();

        /**
         * Builds an empty cell.
         *
         * @param island the island code, empty when unknown
         * @param familyCode the family code, empty when the lines carry none
         */
        private FamilyCell(String island, String familyCode) {
            this.island = island;
            this.familyCode = familyCode;
        }

        /**
         * Folds one line into the cell.
         *
         * <p>The label is taken from the first line that has one and never
         * overwritten by a blank: two lines of one family may not both carry it,
         * and a cell that ends up labelled by the last line is labelled by
         * chance.
         *
         * @param line the line, already known not to be cancelled
         * @param ticketNumber the ticket the line belongs to
         * @param lineDiscount the discount the line carries
         * @param upstreamDiscount the discount on the line the upstream refunds
         */
        private void add(TicketLine line, String ticketNumber, BigDecimal lineDiscount,
                BigDecimal upstreamDiscount) {
            breakdown.add(line.vatRate, line.totalPrice);
            if (line.quantity != null) {
                quantity = quantity.add(line.quantity);
            }
            discount = discount.add(lineDiscount);
            upstream = upstream.add(upstreamDiscount);
            tickets.add(ticketNumber);
            if (familyLabel.isEmpty() && line.familyLabel != null) {
                familyLabel = line.familyLabel.replace(SEPARATOR, " ");
            }
        }
    }

    /**
     * One register's cell of the PERIOD-SUMMARY fold: its counts, its ticket
     * number range and its revenue.
     */
    private static final class RegisterCell {

        /** The register the cell counts, empty when the ticket carries none. */
        private final String terminal;

        /** The number of tickets holding at least one item. */
        private int sales;

        /** The number of tickets holding none — drawer openings and the like. */
        private int nonSales;

        /** The first ticket number seen on the register, empty while none has been. */
        private String firstTicket = "";

        /** The last ticket number seen on the register, empty while none has been. */
        private String lastTicket = "";

        /** The tax-included revenue of the register over the day. */
        private BigDecimal total = BigDecimal.ZERO;

        /**
         * Builds an empty cell.
         *
         * @param terminal the register identifier, empty when unknown
         */
        private RegisterCell(String terminal) {
            this.terminal = terminal;
        }

        /**
         * Folds one ticket into the cell.
         *
         * <p>The tickets arrive in closing order, so the first one seen is the
         * first of the day and each one seen is the last so far: the range needs
         * no sort, and sorting on the ticket NUMBER would be wrong anyway, a
         * number being a string whose format the node does not own.
         *
         * @param ticket the closed ticket
         */
        private void add(Ticket ticket) {
            if (ticket.itemCount > 0) {
                sales++;
            } else {
                nonSales++;
            }
            if (ticket.totalIncludingTax != null) {
                total = total.add(ticket.totalIncludingTax);
            }
            String number = ticket.ticketNumber == null ? ""
                    : ticket.ticketNumber.replace(SEPARATOR, " ");
            if (firstTicket.isEmpty()) {
                firstTicket = number;
            }
            lastTicket = number;
        }
    }

    /**
     * One (shift, cashier) cell of the PRODUCTIVITY fold: the tickets, the items,
     * the revenue and the bounds the duration is measured between.
     */
    private static final class ShiftCell {

        /** The cash session number — the shift — empty when the ticket has none. */
        private final String shift;

        /** The cashier login, or {@code INCONNU}. */
        private final String cashier;

        /** The register of the shift, empty when the ticket carries none. */
        private final String terminal;

        /** LC-01-03-01: journal identity, distinct from the exported cashier login. */
        private String badge;

        /** The number of tickets rung during the shift. */
        private int tickets;

        /** The number of items rung during the shift. */
        private int items;

        /** The tax-included revenue of the shift. */
        private BigDecimal total = BigDecimal.ZERO;

        /** When the shift opened, null when no session was attached. */
        private LocalDateTime openedAt;

        /** When the shift closed, null while it is still open. */
        private LocalDateTime closedAt;

        /** The last sale of the shift, which bounds a shift left open. */
        private LocalDateTime lastSale;

        /**
         * Builds an empty cell.
         *
         * @param shift the cash session number, empty when unknown
         * @param cashier the cashier login
         * @param terminal the register identifier, empty when unknown
         */
        private ShiftCell(String shift, String cashier, String terminal) {
            this.shift = shift;
            this.cashier = cashier;
            this.terminal = terminal;
        }

        /**
         * Folds one ticket, and its session, into the cell.
         *
         * @param ticket the closed ticket
         * @param session the session the ticket was rung in, possibly null
         */
        private void add(Ticket ticket, CashSession session) {
            badge = ticket.cashier == null ? null : ticket.cashier.badgeId;
            tickets++;
            items += ticket.itemCount;
            if (ticket.totalIncludingTax != null) {
                total = total.add(ticket.totalIncludingTax);
            }
            if (ticket.closingDate != null) {
                lastSale = ticket.closingDate;
            }
            if (session != null) {
                openedAt = session.openingDate;
                closedAt = session.closingDate;
            }
        }

        /**
         * Returns the duration of the shift in minutes.
         *
         * <p>Zero when the shift has no opening, when it has neither a closing nor
         * a sale to be bounded by, and when the bounds come out in the wrong
         * order — a clock set back during the shift, which must not produce a
         * negative duration the receiving side would divide by.
         *
         * @param pauses ordered pause transitions within this shift
     * @return the duration in whole minutes, never negative
         */
        private long minutes(List<com.intermarche.edge.domain.session.TechnicalEvent> pauses) {
            LocalDateTime end = closedAt == null ? lastSale : closedAt;
            if (openedAt == null || end == null) {
                return 0L;
            }
            long elapsed = java.time.Duration.between(openedAt, end).toMillis();
            // LC-01-03-01: a first resume means this cashier was absent before it.
            LocalDateTime pauseStart = !pauses.isEmpty()
                    && pauses.get(0).eventType == com.intermarche.edge.domain.session.TechnicalEvent.EventType.REGISTER_UNLOCKED
                    ? openedAt : null;
            long paused = 0L;
            for (var event : pauses) {
                if (event.eventType == com.intermarche.edge.domain.session.TechnicalEvent.EventType.REGISTER_LOCKED) {
                    if (pauseStart == null) pauseStart = event.eventDate;
                } else if (pauseStart != null) {
                    paused += java.time.Duration.between(pauseStart, event.eventDate).toMillis();
                    pauseStart = null;
                }
            }
            if (pauseStart != null) paused += java.time.Duration.between(pauseStart, end).toMillis();
            // Round once after subtracting all pauses, not once per interval.
            return Math.max(0L, elapsed - paused) / 60_000L;
        }

        /**
         * Returns the items rung an hour over the shift.
         *
         * @param minutes the duration of the shift, in minutes
         * @return the hourly rate, zero when the shift has no measurable duration
         */
        private BigDecimal itemsPerHour(long minutes) {
            if (minutes == 0L) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(items).multiply(BigDecimal.valueOf(60L))
                    .divide(BigDecimal.valueOf(minutes), 2, java.math.RoundingMode.HALF_UP);
        }

        /**
         * Returns the average basket of the shift.
         *
         * <p>No guard on a zero divisor: a cell exists only because a ticket was
         * folded into it, so the count is at least one. A guard here would be a
         * branch no test could ever reach.
         *
         * @return the revenue divided by the number of tickets
         */
        private BigDecimal averageBasket() {
            return total.divide(BigDecimal.valueOf(tickets), 2, java.math.RoundingMode.HALF_UP);
        }
    }
}
