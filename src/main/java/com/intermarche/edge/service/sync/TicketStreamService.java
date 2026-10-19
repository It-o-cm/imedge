package com.intermarche.edge.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.domain.sync.StreamEvent;
import com.intermarche.edge.service.ExternalRelayService;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * The AT-THE-FLOW stream: every sale, refund and card settlement the node
 * consolidates, pushed to the third parties that subscribed to it
 * (BO-11-02-02/03/04), with the continuous supervision and the recovery the
 * requirement asks for (BO-11-02-05).
 *
 * <p>Three requirements, ONE mechanism, because they differ only in who reads:
 * BO-11-02-02 is the tickets for third-party applications, BO-11-02-03 the
 * monetics slips, BO-11-02-04 the transaction data a video or single-queue system
 * consumes. Writing three pipes would have given three places to lose an event.
 *
 * <p>WHO receives what is not written here: the subscribers are the declared
 * providers (BO-03-11), so connecting a new video supplier is a row in the back
 * office. A provider receives the kinds its TYPE justifies — a monetics collector
 * gets slips and no baskets, a floor system gets tickets and refunds and no card
 * authorisations — and only when it is reachable, since posting to a provider with
 * no address or no credential is a guaranteed failure that would do nothing but
 * grow the backlog.
 *
 * <p>Enqueue happens INSIDE the ingestion transaction and delivery outside it.
 * That ordering is the robustness of the whole thing: a ticket consolidated but
 * not enqueued would never be streamed, while an enqueued event whose delivery
 * fails is simply tried again. The payload is frozen at enqueue time, so the
 * third party is told what happened then and no lazy relation is read while an
 * external host is thinking.
 *
 * <p>Nothing is ever dropped. There is no attempt ceiling and no discard: a
 * subscriber that is down for a day comes back to its whole backlog, in the order
 * the shop lived it. What the operator sees meanwhile is the healthcheck —
 * pending count, worst attempt count and last error per subscriber — and a manual
 * relaunch, which is the "alert and robust recovery" of BO-11-02-05.
 */
@ApplicationScoped
public class TicketStreamService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TicketStreamService.class);

    /** The path the stream posts to, appended to the provider's address. */
    private static final String STREAM_PATH = "/events";

    /** How many events one delivery pass takes at most. */
    private static final int BATCH_SIZE = 50;

    /** The read timeout of one delivery, in milliseconds. */
    private static final long TIMEOUT_MILLIS = 3000L;

    /** The settlement discriminator of a card payment. */
    private static final String CARD_METHOD = "CARD";

    /** The administered activation, cadence and image choice. */
    @Inject
    PosSettingsService posSettingsService;

    /** The credentialed forwarder toward the subscribers. */
    @Inject
    ExternalRelayService externalRelayService;

    /** Serializes the published payloads. */
    @Inject
    ObjectMapper objectMapper;

    /** The delivery loop, null until the node has started. */
    ScheduledExecutorService executor;

    /**
     * Enqueues a TICKET event for every subscriber of that kind.
     *
     * <p>Called from the ingestion, in its transaction. Every status travels —
     * sale, parked cart, cancelled cart — because BO-11-02-02 names them one by
     * one; the school-mode marking it also names is not pushed by the register
     * today, so a training ticket travels as its status and not as a mode. That is
     * a named gap, not a silent one.
     *
     * @param ticket the consolidated ticket
     */
    public void onTicket(Ticket ticket) {
        LOGGER.info("Entering method onTicket");
        if (!isEnabled() || ticket == null || ticket.ticketNumber == null) {
            LOGGER.info("Exiting method onTicket");
            return;
        }
        enqueue(StreamEvent.Kind.TICKET, ticket.ticketNumber, ticketEvent(ticket));
        enqueueMonetics(ticket);
        LOGGER.info("Exiting method onTicket");
    }

    /**
     * Enqueues a REFUND event for every subscriber of that kind.
     *
     * @param refund the consolidated refund
     */
    public void onRefund(Refund refund) {
        LOGGER.info("Entering method onRefund");
        if (!isEnabled() || refund == null || refund.refundNumber == null) {
            LOGGER.info("Exiting method onRefund");
            return;
        }
        enqueue(StreamEvent.Kind.REFUND, refund.refundNumber, refundEvent(refund));
        LOGGER.info("Exiting method onRefund");
    }

    /**
     * Enqueues the MONETICS slip of a ticket, when it holds a card settlement.
     *
     * <p>No card, no event: a monetics collector reconciles authorisations, and a
     * cash sale has none to reconcile.
     *
     * @param ticket the consolidated ticket
     */
    private void enqueueMonetics(Ticket ticket) {
        StreamPayloads.MoneticsEvent slip = moneticsEvent(ticket);
        if (slip.cards.isEmpty()) {
            return;
        }
        enqueue(StreamEvent.Kind.MONETICS, ticket.ticketNumber, slip);
    }

    /**
     * Freezes a payload and files it once per subscriber of that kind.
     *
     * <p>An existing PENDING row of the same identity is rewritten rather than
     * doubled: a register re-pushing one ticket twenty times must leave one event
     * carrying the latest state, not twenty the subscriber has to de-duplicate. A
     * row already delivered is left alone and a new one is filed beside it, which
     * is correct — the ticket changed after being streamed.
     *
     * @param kind what the event carries
     * @param reference the ticket or refund number
     * @param payload the payload object to freeze
     */
    private void enqueue(StreamEvent.Kind kind, String reference, Object payload) {
        List<ServiceProvider> subscribers = subscribers(kind);
        if (subscribers.isEmpty()) {
            return;
        }
        String body = serialize(payload);
        if (body == null) {
            return;
        }
        for (ServiceProvider subscriber : subscribers) {
            String uid = StreamEvent.uidOf(subscriber.code, kind, reference);
            StreamEvent existing = StreamEvent.findByUid(uid);
            if (existing != null && existing.deliveredAt == null) {
                existing.payload = body;
                existing.attempts = 0;
                existing.lastError = null;
                existing.createdAt = LocalDateTime.now();
                continue;
            }
            if (existing != null) {
                existing.delete();
            }
            StreamEvent event = new StreamEvent();
            event.eventUid = uid;
            event.providerCode = subscriber.code;
            event.kind = kind;
            event.reference = reference;
            event.payload = body;
            event.createdAt = LocalDateTime.now();
            event.persist();
        }
    }

    /**
     * Returns the declared providers that receive one kind of event.
     *
     * <p>A provider must be REACHABLE — in service, addressed and credentialed —
     * because posting to one that is not is a certain failure, and a certain
     * failure filed once per sale is a backlog that only ever grows.
     *
     * @param kind what the event carries
     * @return the subscribers, empty when nobody is listening
     */
    List<ServiceProvider> subscribers(StreamEvent.Kind kind) {
        List<ServiceProvider.ProviderType> types = kind == StreamEvent.Kind.MONETICS
                ? List.of(ServiceProvider.ProviderType.MONETIQUE)
                : List.of(ServiceProvider.ProviderType.EXPLOITATION,
                        ServiceProvider.ProviderType.DECISIONNEL);
        List<ServiceProvider> subscribers = new ArrayList<>();
        for (ServiceProvider.ProviderType type : types) {
            for (ServiceProvider candidate : ServiceProvider.listActiveOfType(type)) {
                if (candidate.isReachable()) {
                    subscribers.add(candidate);
                }
            }
        }
        return subscribers;
    }

    /**
     * Starts the delivery loop.
     *
     * <p>Same shape as the other loops of this node: one daemon thread, a fixed
     * delay, and nothing thrown back into the scheduler.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Entering method onStart");
        long seconds = getDeliverySeconds();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ticket-stream");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tickSafely, seconds, seconds, TimeUnit.SECONDS);
        LOGGER.info("Exiting method onStart");
    }

    /**
     * Runs one delivery pass, swallowing whatever it fails on.
     *
     * <p>A subscriber answering nonsense must not cancel the loop for the rest of
     * the day, which is what an exception reaching the scheduler would do.
     */
    void tickSafely() {
        try {
            if (isEnabled()) {
                self().deliverPending();
            }
        } catch (Exception failure) {
            LOGGER.warnf("Diffusion au fil de l'eau impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Delivers the next batch of pending events and returns how many were
     * acknowledged.
     *
     * <p>Oldest first, and a failure does NOT stop the pass: one unreachable
     * subscriber must not hold up the others, so each event counts its own attempt
     * and the pass carries on. The acknowledged rows are deleted — the event has
     * arrived, and keeping it would make the backlog a ledger nobody reads.
     *
     * @return the number of events delivered
     */
    @Transactional
    public int deliverPending() {
        LOGGER.info("Entering method deliverPending");
        int delivered = 0;
        for (StreamEvent event : StreamEvent.nextPending(BATCH_SIZE)) {
            ServiceProvider subscriber = ServiceProvider.findByCode(event.providerCode);
            if (subscriber == null || !subscriber.isReachable()) {
                // Not an attempt: the subscriber was taken out of service or
                // un-addressed in the back office. Counting it would inflate the
                // attempt figure the operator reads as "the other side is failing".
                continue;
            }
            ExternalRelayService.RelayResult result = externalRelayService.relay(
                    Optional.of(subscriber.endpoint), "POST", STREAM_PATH, event.payload,
                    TIMEOUT_MILLIS, subscriber.authKey);
            if (result.reached() && result.status() >= 200 && result.status() < 300) {
                event.delete();
                delivered++;
                continue;
            }
            event.attempts++;
            event.lastError = result.reached()
                    ? "HTTP " + result.status()
                    : "injoignable";
        }
        LOGGER.infof("Diffusion au fil de l'eau: %d événement(s) livré(s), %d en attente.",
                delivered, StreamEvent.countPending());
        LOGGER.info("Exiting method deliverPending");
        return delivered;
    }

    /**
     * One subscriber's line of the healthcheck (BO-11-02-05).
     *
     * @param providerCode the subscriber's code
     * @param label its operator-facing label
     * @param pending how many events await delivery
     * @param maxAttempts the worst attempt count among them
     * @param lastError the last delivery error, or null
     * @param reachable whether the subscriber is currently reachable
     */
    public record StreamRow(String providerCode, String label, long pending, int maxAttempts,
            String lastError, boolean reachable) {
    }

    /**
     * Builds the healthcheck of the stream: one row per subscriber that has
     * something waiting (BO-11-02-05).
     *
     * <p>A subscriber that is caught up produces NO row, like the outbox backlog
     * above it: a screen full of zeroes is a screen nobody checks.
     *
     * @return the rows, by subscriber code, empty when the stream is caught up
     */
    public List<StreamRow> health() {
        LOGGER.info("Entering method health");
        List<StreamRow> rows = new ArrayList<>();
        String currentCode = null;
        long pending = 0;
        int maxAttempts = 0;
        String lastError = null;
        for (StreamEvent event : StreamEvent.listPending()) {
            if (currentCode != null && !currentCode.equals(event.providerCode)) {
                rows.add(rowOf(currentCode, pending, maxAttempts, lastError));
                pending = 0;
                maxAttempts = 0;
                lastError = null;
            }
            currentCode = event.providerCode;
            pending++;
            if (event.attempts > maxAttempts) {
                maxAttempts = event.attempts;
            }
            if (event.lastError != null) {
                lastError = event.lastError;
            }
        }
        if (currentCode != null) {
            rows.add(rowOf(currentCode, pending, maxAttempts, lastError));
        }
        LOGGER.info("Exiting method health");
        return rows;
    }

    /**
     * Builds one healthcheck row, resolving the subscriber's label and state.
     *
     * @param providerCode the subscriber's code
     * @param pending how many events await delivery
     * @param maxAttempts the worst attempt count
     * @param lastError the last error, or null
     * @return the row
     */
    private StreamRow rowOf(String providerCode, long pending, int maxAttempts,
            String lastError) {
        ServiceProvider subscriber = ServiceProvider.findByCode(providerCode);
        String label = subscriber == null || subscriber.label == null
                ? providerCode : subscriber.label;
        boolean reachable = subscriber != null && subscriber.isReachable();
        return new StreamRow(providerCode, label, pending, maxAttempts, lastError, reachable);
    }

    /**
     * Tells whether the stream is activated at this echelon.
     *
     * @return true when the administered key says yes
     */
    public boolean isEnabled() {
        return posSettingsService.streamEnabled();
    }

    /**
     * Returns the delivery cadence, in seconds.
     *
     * @return the administered cadence
     */
    public long getDeliverySeconds() {
        return posSettingsService.streamDeliverySeconds();
    }

    /**
     * Returns this bean through its CDI client proxy, or {@code this} when there
     * is no container — a plain unit test.
     *
     * @return the proxied bean, or this instance
     */
    TicketStreamService self() {
        try {
            TicketStreamService proxy = io.quarkus.arc.Arc.container()
                    .instance(TicketStreamService.class).get();
            return proxy == null ? this : proxy;
        } catch (Exception unavailable) {
            return this;
        }
    }

    /**
     * Serializes a payload, or returns null when it cannot be serialized.
     *
     * <p>Null rather than a throw: a payload this version cannot write is a bug to
     * fix, not a reason to refuse a sale the register has already made and the
     * node has already consolidated.
     *
     * @param payload the payload object
     * @return the JSON body, or null on failure
     */
    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception failure) {
            LOGGER.errorf("Charge du flux au fil de l'eau non sérialisable: %s",
                    failure.getMessage());
            return null;
        }
    }

    /**
     * Builds the published body of a ticket.
     *
     * @param ticket the consolidated ticket
     * @return the payload
     */
    private StreamPayloads.TicketEvent ticketEvent(Ticket ticket) {
        StreamPayloads.TicketEvent event = new StreamPayloads.TicketEvent();
        event.ticketNumber = ticket.ticketNumber;
        event.terminalId = ticket.terminalId;
        event.pdvNumber = posSettingsService.pdvNumber();
        event.status = ticket.status == null ? null : ticket.status.name();
        event.creationDate = stamp(ticket.creationDate);
        event.closingDate = stamp(ticket.closingDate);
        event.cashierLogin = ticket.cashier == null ? null : ticket.cashier.loginName;
        event.sessionNumber = ticket.session == null ? null : ticket.session.sessionNumber;
        event.itemCount = ticket.itemCount;
        event.totalExcludingTax = ticket.totalExcludingTax;
        event.totalIncludingTax = ticket.totalIncludingTax;
        event.totalVat = ticket.totalVat;
        event.fidelityCard = ticket.fidelityCard;
        event.signature = ticket.signature;
        applyImage(event, ticket.formattedContent);
        for (TicketLine line : ticket.lines) {
            StreamPayloads.LineEvent lineEvent = new StreamPayloads.LineEvent();
            lineEvent.lineNumber = line.lineNumber;
            lineEvent.ean = line.ean;
            lineEvent.plu = line.plu;
            lineEvent.label = line.productLabel;
            lineEvent.familyCode = line.familyCode;
            lineEvent.quantity = line.quantity;
            lineEvent.unitPrice = line.unitPrice;
            lineEvent.vatRate = line.vatRate;
            lineEvent.totalPrice = line.totalPrice;
            lineEvent.cancelled = line.cancelled;
            event.lines.add(lineEvent);
        }
        for (TicketPayment payment : ticket.payments) {
            StreamPayloads.PaymentEvent paymentEvent = new StreamPayloads.PaymentEvent();
            paymentEvent.paymentIndex = payment.paymentIndex;
            paymentEvent.method = payment.getMethodKey();
            paymentEvent.amount = payment.amount;
            event.payments.add(paymentEvent);
        }
        return event;
    }

    /**
     * Attaches the printed image to a ticket event, plain or compressed as
     * administered (BO-11-02-02, "compressée ou non").
     *
     * <p>A ticket pushed by a register that carries no printed form attaches
     * nothing, and says so by leaving the encoding null: a reader must be able to
     * tell "no image" from "an image I failed to decode".
     *
     * @param event the event being built
     * @param formattedContent the printed form, possibly null or blank
     */
    private void applyImage(StreamPayloads.TicketEvent event, String formattedContent) {
        if (formattedContent == null || formattedContent.isBlank()) {
            return;
        }
        if (!posSettingsService.streamCompressImage()) {
            event.image = formattedContent;
            event.imageEncoding = StreamPayloads.IMAGE_PLAIN;
            return;
        }
        String compressed = gzipBase64(formattedContent);
        if (compressed == null) {
            // Compression failed on a string we hold in memory, which should not
            // happen; sending it plain is better than sending no image at all, and
            // the encoding tells the reader what it got.
            event.image = formattedContent;
            event.imageEncoding = StreamPayloads.IMAGE_PLAIN;
            return;
        }
        event.image = compressed;
        event.imageEncoding = StreamPayloads.IMAGE_GZIP_BASE64;
    }

    /**
     * Gzips a text then Base64-encodes it, or returns null on failure.
     *
     * @param text the text to compress
     * @return the encoded text, or null
     */
    private String gzipBase64(String text) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception failure) {
            LOGGER.warnf("Compression de l'image ticket impossible (%s)", failure.getMessage());
            return null;
        }
    }

    /**
     * Builds the published body of a refund.
     *
     * @param refund the consolidated refund
     * @return the payload
     */
    private StreamPayloads.RefundEvent refundEvent(Refund refund) {
        StreamPayloads.RefundEvent event = new StreamPayloads.RefundEvent();
        event.refundNumber = refund.refundNumber;
        event.terminalId = refund.terminalId;
        event.pdvNumber = posSettingsService.pdvNumber();
        event.status = refund.status == null ? null : refund.status.name();
        event.method = refund.refundMethod == null ? null : refund.refundMethod.name();
        event.creationDate = stamp(refund.creationDate);
        event.totalAmount = refund.totalAmount;
        event.totalVat = refund.totalVat;
        event.sessionNumber = refund.session == null ? null : refund.session.sessionNumber;
        return event;
    }

    /**
     * Builds the published monetics slip of a ticket, card settlements only.
     *
     * @param ticket the consolidated ticket
     * @return the payload, its card list empty when the sale carried no card
     */
    private StreamPayloads.MoneticsEvent moneticsEvent(Ticket ticket) {
        StreamPayloads.MoneticsEvent event = new StreamPayloads.MoneticsEvent();
        event.ticketNumber = ticket.ticketNumber;
        event.terminalId = ticket.terminalId;
        event.pdvNumber = posSettingsService.pdvNumber();
        event.closingDate = stamp(ticket.closingDate);
        for (TicketPayment payment : ticket.payments) {
            if (!CARD_METHOD.equals(payment.getMethodKey())) {
                continue;
            }
            StreamPayloads.CardEvent card = new StreamPayloads.CardEvent();
            card.paymentIndex = payment.paymentIndex;
            card.amount = payment.amount;
            if (payment instanceof CardPayment cardPayment) {
                card.authorizationNumber = cardPayment.authorizationNumber;
                card.degradedMode = cardPayment.degradedMode;
            }
            event.cards.add(card);
        }
        return event;
    }

    /**
     * Renders an instant for the wire, ISO-8601, or null when absent.
     *
     * @param value the instant, possibly null
     * @return the rendered instant, or null
     */
    private String stamp(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
