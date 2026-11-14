package com.intermarche.edge.service.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.deploy.UplinkedTicket;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PdvHistory;
import com.intermarche.edge.service.NodeEchelonService;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.ui.journal.JournalService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The JOURNAL UPLINK (BO-02-05-05): the closed tickets of a store node go up
 * to the central node, which holds the electronic journal of every point of
 * sale and regroups it by adhérent.
 *
 * <p>WHY THE STORES PUSH. The central node never calls a store: every
 * conversation between them is opened by the store — its report cycle, its
 * referential pull, its package downloads — on the central URL and with the
 * shared token it is configured with. The journal rides the same road: a store
 * connected to a central node asks it, at the cadence of its report cycle, the
 * watermark of what it already holds for its point of sale, and sends up the
 * tickets its registers closed or cancelled since, in batches.
 *
 * <p>ACKNOWLEDGEMENT AND REPLAY. The store keeps no state: what the central
 * node filed IS the acknowledgement, and the watermark it answers is the
 * latest modification it holds. A push that fails is simply replayed from the
 * same watermark at the next cycle; a ticket restated on the store (a
 * conversion to an invoice re-pushed by its register) carries a later
 * modification and goes up again. The central node files each ticket once,
 * by point of sale and number, and leaves a row untouched when the same
 * modification comes twice.
 *
 * <p>The store's own journal is unchanged: it still reads the store's tickets.
 */
@ApplicationScoped
public class JournalUplinkService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(JournalUplinkService.class);

    /** How many tickets one push carries. */
    static final int BATCH = 100;

    /** How many pushes one cycle sends at most. */
    static final int MAX_BATCHES = 10;

    /** How often the loop looks whether a push is due, in seconds. */
    static final int TICK_SECONDS = 10;

    /** The role of this node: register, store or central. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** The central node a store reports to; absent = none. */
    @ConfigProperty(name = "pos.sync.central-url")
    Optional<String> centralUrl;

    /** Shared token sent to the central node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** This node's point of sale. */
    @Inject
    NodeEchelonService nodeEchelonService;

    /** The cadence of the report cycle the push follows. */
    @Inject
    PosSettingsService posSettingsService;

    /** The materialized ticket detail of the store's journal. */
    @Inject
    JournalService journalService;

    /** The persistence session the tickets are read on. */
    @Inject
    EntityManager entityManager;

    /** The JSON mapper of the wire format. */
    @Inject
    ObjectMapper objectMapper;

    /** The HTTP client toward the central node. */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** When the last push was attempted, in epoch milliseconds; 0 = never. */
    long lastPush;

    /** The loop executor, null when this node pushes nothing. */
    private ScheduledExecutorService executor;

    /** One ticket as it travels up. */
    public static class UplinkTicket {

        /** The ticket number. */
        public String number;

        /** The register identifier. */
        public String terminal;

        /** The cashier badge, or null. */
        public String cashier;

        /** CLOSED or CANCELLED. */
        public String status;

        /** When the ticket was created, ISO, the date the store's journal shows. */
        public String date;

        /** The last modification on the store, ISO. */
        public String updatedAt;

        /** The tax-included total. */
        public BigDecimal total;

        /** The number of article lines. */
        public int itemCount;

        /** The materialized detail of the store's journal. */
        public JsonNode detail;

        /** The printed ticket without its embedded images and barcodes, or null. */
        public String printed;
    }

    /** One push: the point of sale and its tickets. */
    public static class Batch {

        /** The point of sale of the store. */
        public String pdvNumber;

        /** The tickets, oldest modification first. */
        public List<UplinkTicket> tickets = new ArrayList<>();
    }

    /** The central node's acknowledgement. */
    public static class Answer {

        /** How many tickets were filed or restated. */
        public int accepted;

        /** The latest modification held for the point of sale, ISO, or null. */
        public String mark;
    }

    /**
     * Starts the push loop on a store node connected to a central node.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (!relayed()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "journal-uplink");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tickSafely, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the push loop.
     */
    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Whether this node pushes its journal up.
     *
     * @return true for a store node with a central URL
     */
    boolean relayed() {
        return "store".equalsIgnoreCase(role) && centralUrl.isPresent() && !centralUrl.get().isBlank();
    }

    /**
     * Pushes when the report cadence is due, swallowing whatever fails: an
     * unreachable central node must not stop the next tick.
     */
    void tickSafely() {
        try {
            long now = System.currentTimeMillis();
            int cadence = QuarkusTransaction.requiringNew().call(posSettingsService::supervisionReportSeconds);
            if (now - lastPush < cadence * 1000L) {
                return;
            }
            lastPush = now;
            push();
        } catch (Exception failure) {
            LOGGER.warnf("Remontée du journal impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Sends up the tickets modified since the central node's watermark.
     *
     * @return how many tickets the central node filed or restated
     * @throws Exception when the central node cannot be reached or answers badly
     */
    public int push() throws Exception {
        String pdv = QuarkusTransaction.requiringNew().call(nodeEchelonService::currentPdv);
        if (pdv == null) {
            return 0;
        }
        LocalDateTime mark = stamp(fetchMark(pdv));
        int accepted = 0;
        for (int round = 0; round < MAX_BATCHES; round++) {
            LocalDateTime from = mark;
            Batch batch = new Batch();
            batch.pdvNumber = pdv;
            batch.tickets = QuarkusTransaction.requiringNew().call(() -> collect(from));
            if (batch.tickets.isEmpty()) {
                break;
            }
            Answer answer = post(batch);
            accepted += answer.accepted;
            LocalDateTime next = stamp(answer.mark);
            if (batch.tickets.size() < BATCH || next == null || from != null && !next.isAfter(from)) {
                break;
            }
            mark = next;
        }
        LOGGER.infof("Journal remonté au nœud central : %d ticket(s) pour le PDV %s", accepted, pdv);
        return accepted;
    }

    /**
     * Reads the store's finalized tickets modified at or after a watermark,
     * oldest first, one batch at most.
     *
     * @param mark the watermark, or null for the whole journal
     * @return the tickets ready to travel
     */
    List<UplinkTicket> collect(LocalDateTime mark) {
        String jpql = "select t from com.intermarche.edge.domain.sale.Ticket t where t.status in :statuses"
                + (mark == null ? "" : " and t.updatedAt >= :mark") + " order by t.updatedAt, t.id";
        TypedQuery<Ticket> query = entityManager.createQuery(jpql, Ticket.class)
                .setParameter("statuses", List.of(Ticket.TicketStatus.CLOSED, Ticket.TicketStatus.CANCELLED))
                .setMaxResults(BATCH);
        if (mark != null) {
            query.setParameter("mark", mark);
        }
        List<UplinkTicket> tickets = new ArrayList<>();
        for (Ticket ticket : query.getResultList()) {
            tickets.add(uplink(ticket));
        }
        return tickets;
    }

    /**
     * Turns a ticket into what travels up.
     *
     * @param ticket the store's ticket
     * @return the travelling ticket
     */
    UplinkTicket uplink(Ticket ticket) {
        UplinkTicket up = new UplinkTicket();
        up.number = ticket.ticketNumber;
        up.terminal = ticket.terminalId;
        up.cashier = ticket.cashier == null ? null : ticket.cashier.badgeId;
        up.status = ticket.status.name();
        up.date = ticket.creationDate.toString();
        up.updatedAt = ticket.updatedAt.toString();
        up.total = ticket.totalIncludingTax;
        up.itemCount = ticket.itemCount;
        up.detail = objectMapper.valueToTree(journalService.buildDetail(ticket.id));
        up.printed = ticket.formattedContent == null ? null
                : ticket.formattedContent.replaceAll("\\[\\[(?:IMAGE|BARCODE)[^\\]]*\\]\\]\\n?", "");
        return up;
    }

    /**
     * Asks the central node the watermark of this point of sale.
     *
     * @param pdv the point of sale
     * @return the watermark, ISO, or null when the central node holds nothing
     * @throws Exception when the central node cannot be reached or answers badly
     */
    String fetchMark(String pdv) throws Exception {
        HttpResponse<String> response = httpClient.send(request("/api/supervision/journal/mark?pdv="
                + URLEncoder.encode(pdv, StandardCharsets.UTF_8)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " du nœud central");
        }
        return objectMapper.readValue(response.body(), Answer.class).mark;
    }

    /**
     * Sends one batch to the central node.
     *
     * @param batch the batch
     * @return the central node's acknowledgement
     * @throws Exception when the central node cannot be reached or answers badly
     */
    Answer post(Batch batch) throws Exception {
        HttpResponse<String> response = httpClient.send(request("/api/supervision/journal")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(batch))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " du nœud central");
        }
        return objectMapper.readValue(response.body(), Answer.class);
    }

    /**
     * Builds a request to the central node, carrying the shared token.
     *
     * @param path the path and query
     * @return the prepared builder
     */
    private HttpRequest.Builder request(String path) {
        String base = centralUrl.get().trim();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path))
                .timeout(Duration.ofSeconds(30));
        String shared = token.orElse("");
        if (!shared.isBlank()) {
            builder.header("X-Sync-Token", shared);
        }
        return builder;
    }

    /**
     * The numbers a point of sale carried: the given one and, when the central
     * node knows the point of sale, its former numbers (BO-02-05-01).
     *
     * @param pdv the number the store reports under
     * @return the numbers, the given one first
     */
    public static Set<String> numbersOf(String pdv) {
        Set<String> numbers = new LinkedHashSet<>();
        numbers.add(pdv);
        Pdv holder = Pdv.findByNumber(pdv);
        if (holder != null) {
            for (PdvHistory change : PdvHistory.listForPdv(holder.id)) {
                if (change.kind == PdvHistory.Kind.NUMBER && change.oldValue != null) {
                    numbers.add(change.oldValue);
                }
            }
        }
        return numbers;
    }

    /**
     * Answers the watermark of a point of sale, on the central node.
     *
     * @param pdv the point of sale, possibly null
     * @return the answer, its mark null when nothing was received
     */
    @Transactional
    public Answer mark(String pdv) {
        Answer answer = new Answer();
        if (pdv == null || pdv.isBlank()) {
            return answer;
        }
        UplinkedTicket latest = UplinkedTicket.latestOf(numbersOf(pdv.trim()));
        answer.mark = latest == null ? null : latest.sourceUpdatedAt.toString();
        return answer;
    }

    /**
     * Files the tickets a store pushed, on the central node.
     *
     * @param batch the batch, possibly null
     * @return how many were filed or restated, and the new watermark
     */
    @Transactional
    public Answer receive(Batch batch) {
        LOGGER.info("Entering method receive");
        Answer answer = new Answer();
        if (batch == null || batch.pdvNumber == null || batch.pdvNumber.isBlank() || batch.tickets == null) {
            LOGGER.info("Exiting method receive: nothing to file");
            return answer;
        }
        String pdv = batch.pdvNumber.trim();
        Set<String> numbers = numbersOf(pdv);
        LocalDateTime now = LocalDateTime.now();
        for (UplinkTicket up : batch.tickets) {
            LocalDateTime updated = stamp(up.updatedAt);
            LocalDateTime date = stamp(up.date);
            if (up.number == null || updated == null || date == null) {
                continue;
            }
            UplinkedTicket row = UplinkedTicket.findFiled(numbers, up.number);
            if (row != null && updated.equals(row.sourceUpdatedAt)) {
                continue;
            }
            boolean created = row == null;
            if (created) {
                row = new UplinkedTicket();
                row.pdvNumber = pdv;
                row.ticketNumber = up.number;
            }
            row.terminalId = up.terminal;
            row.cashierBadge = up.cashier;
            row.status = up.status == null ? Ticket.TicketStatus.CLOSED.name() : up.status;
            row.totalIncludingTax = up.total == null ? BigDecimal.ZERO : up.total;
            row.kind = UplinkedTicket.kindOf(row.status, row.totalIncludingTax);
            row.ticketDate = date;
            row.itemCount = up.itemCount;
            row.sourceUpdatedAt = updated;
            row.receivedAt = now;
            row.detail = up.detail == null ? null : up.detail.toString();
            row.printed = up.printed;
            if (created) {
                row.persist();
            }
            answer.accepted++;
        }
        UplinkedTicket latest = UplinkedTicket.latestOf(numbers);
        answer.mark = latest == null ? null : latest.sourceUpdatedAt.toString();
        LOGGER.info("Exiting method receive");
        return answer;
    }

    /**
     * Reads an ISO date-time, an unreadable one counting as none.
     *
     * @param value the date-time, possibly null
     * @return the date-time, or null
     */
    static LocalDateTime stamp(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}
