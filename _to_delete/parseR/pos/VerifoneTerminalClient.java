package com.intermarche.pos.ui.hardware.terminal;

import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Integrated Verifone monetique client (LC-07-04-01) — SKELETON. The
 * register talks to a Verifone monetique client installed locally; this
 * class owns the exchange thread, the in-flight discipline and the outcome
 * mapping, and delegates the wire work to {@link VerifoneTransport} and
 * {@link VerifoneFrameCodec}.
 * <p>
 * WHAT WORKS NOW: selection via {@code pos.tpe.mode=verifone}, reachability
 * probe, single-transaction discipline, abandon anti-repeat (LC-07-07-02),
 * clean operator-facing failure when the exchange cannot run.
 * <p>
 * WHAT IS PENDING THE PROTOCOL SPECIFICATION (all marked TODO): the debit,
 * credit and abandon request layouts; the response tag dictionary beyond
 * {@link VerifoneTags}; the print frames and TNA handling (LC-07-04-10/12);
 * the session requests c18 with {D5Y} serial capture (LC-01-01-06), the
 * conditional c2g maintenance (LC-01-01-07) and the c2h logoff
 * (LC-01-02-09); the degraded-mode server forcing {VWAY}4 (LC-07-08-02).
 * Every exchange currently ends on the callback's {@code onError} with an
 * explicit message instead of guessing frames against a real terminal.
 */
public class VerifoneTerminalClient implements PaymentTerminalClient {


    private static final Logger LOGGER = Logger.getLogger(VerifoneTerminalClient.class);

    /** Bundle key of the message shown while the protocol is not implemented. */
    static final String MSG_NOT_IMPLEMENTED_KEY = "op.monetiqueVerifoneNonDisponible";

    /** Bundle key of the message shown when the monetique client is unreachable. */
    static final String MSG_UNREACHABLE_KEY = "op.clientMonetiqueInjoignable";

    /** The TCP transport to the local monetique client. */
    private final VerifoneTransport transport;

    /**
     * Single exchange thread: the terminal handles one transaction at a
     * time, and serializing here is what makes the abandon anti-repeat
     * (LC-07-07-02) structural rather than defensive.
     */
    private final ExecutorService exchanges =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "verifone-terminal");
                t.setDaemon(true);
                return t;
            });

    /** True while a transaction is in flight (busy discipline). */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /** True when an abandon was requested for the in-flight transaction. */
    private final AtomicBoolean abortRequested = new AtomicBoolean(false);

    /**
     * The localised operator messages (LC-13-06).
     *
     * <p>HANDED OVER AND NOT INJECTED: this client is built by
     * {@link TerminalClientProducer}, not by the container, so an injected field
     * would stay null. The producer is a bean and passes its own resolver.
     */
    private final com.intermarche.pos.ui.Messages i18n;

    /**
     * The administered monetique parameters (BO-03-09-31), or null when the
     * client was built without them.
     *
     * <p>HANDED OVER AND NOT INJECTED, for the reason {@link #i18n} is: this
     * class is built by {@link TerminalClientProducer} and not by the
     * container. Nullable, so a client built by a test or by an older caller
     * keeps behaving exactly as it did.
     */
    private final com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Creates the client over the given transport, without the administered
     * monetique parameters.
     *
     * @param transport the TCP transport to the local monetique client
     * @param i18n the resolver of the operator-facing messages
     */
    public VerifoneTerminalClient(VerifoneTransport transport,
            com.intermarche.pos.ui.Messages i18n) {
        this(transport, i18n, null);
    }

    /**
     * Creates the client over the given transport.
     *
     * @param transport the TCP transport to the local monetique client
     * @param i18n the resolver of the operator-facing messages
     * @param posSettingsService the administered monetique parameters, or null
     */
    public VerifoneTerminalClient(VerifoneTransport transport,
            com.intermarche.pos.ui.Messages i18n,
            com.intermarche.pos.service.PosSettingsService posSettingsService) {
        this.transport = transport;
        this.i18n = i18n;
        this.posSettingsService = posSettingsService;
    }

    /**
     * Starts a debit exchange on the terminal thread.
     *
     * @param amount the amount to debit
     * @param callback the decision receiver
     */
    @Override
    public void requestDebit(BigDecimal amount, TerminalTransactionCallback callback) {
        submitExchange("debit", amount, callback);
    }

    /**
     * Starts a credit (refund) exchange on the terminal thread
     * (LC-05-06-03).
     *
     * @param amount the amount to credit
     * @param callback the decision receiver
     */
    @Override
    public void requestCredit(BigDecimal amount, TerminalTransactionCallback callback) {
        submitExchange("credit", amount, callback);
    }

    /**
     * Requests the abandon of the in-flight transaction. Repeated presses
     * set the same flag once — the exchange thread emits at most one
     * abandon to the monetique client (LC-07-07-02).
     */
    @Override
    public void abort() {
        if (busy.get()) {
            abortRequested.set(true);
            // TODO(spec Verifone): send the abandon request to the monetique
            // client instead of only flagging the in-flight exchange.
        }
    }

    /**
     * Session-opening hook: c18 serial-number request and conditional c2g
     * maintenance (LC-01-01-06/07).
     */
    @Override
    public void onRegisterOpened() {
        // TODO(spec Verifone): send c18, capture {D5Y} into the technical
        // journal, then trigger c2g when the maintenance conditions hold.
        LOGGER.info("Verifone session hooks (c18/c2g) pending protocol specification");
    }

    /**
     * Session-closing hook: c2h logoff (LC-01-02-09).
     */
    @Override
    public void onRegisterClosed() {
        // TODO(spec Verifone): send the c2h logoff request.
        LOGGER.info("Verifone logoff (c2h) pending protocol specification");
    }

    /**
     * Returns the implementation name.
     *
     * @return "verifone"
     */
    @Override
    public String name() {
        return "verifone";
    }

    /**
     * Queues one exchange, enforcing the single-transaction discipline, and
     * maps its termination onto the callback.
     *
     * @param operation "debit" or "credit"
     * @param amount the transaction amount
     * @param callback the decision receiver
     */
    private void submitExchange(String operation, BigDecimal amount,
                                TerminalTransactionCallback callback) {
        if (!busy.compareAndSet(false, true)) {
            callback.onError(i18n.get("op.transactionMonetiqueDeja"));
            return;
        }
        abortRequested.set(false);
        exchanges.submit(() -> {
            try {
                if (!transport.isReachable()) {
                    callback.onError(i18n.get(MSG_UNREACHABLE_KEY));
                    return;
                }
                String response = transport.exchange(buildRequest(operation, amount));
                handleResponse(response, amount, callback);
            } catch (UnsupportedOperationException e) {
                LOGGER.warnf("Verifone exchange not implemented: %s", e.getMessage());
                callback.onError(i18n.get(MSG_NOT_IMPLEMENTED_KEY));
            } catch (Exception e) {
                LOGGER.errorf(e, "Verifone %s exchange failed", operation);
                callback.onError(i18n.get(MSG_UNREACHABLE_KEY));
            } finally {
                busy.set(false);
                abortRequested.set(false);
            }
        });
    }

    /**
     * Builds the request frame of an operation.
     *
     * <p>Package-visible so a test can read the frame on ITS OWN thread: the
     * administered reads below touch a static Panache finder, and a Mockito
     * static mock does not cross into the exchange thread.
     *
     * @param operation "debit" or "credit"
     * @param amount the transaction amount
     * @return the encoded request frame
     */
    String buildRequest(String operation, BigDecimal amount) {
        // TODO(spec Verifone): real request layout (message code, POS
        // number, amount format, currency, operation discriminator).
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("OPERATION-PLACEHOLDER", operation);
        tags.put("AMOUNT-PLACEHOLDER", amount.toPlainString());
        appendAdministeredTags(tags);
        return VerifoneFrameCodec.encode(tags);
    }

    /**
     * Adds the administered pairs the store states verbatim: the VIC product
     * codes of the tenders in service (BO-03-09-30) and the Label Point De
     * Vente when its activation key is on (BO-03-09-31).
     *
     * <p>VERBATIM, and that is the point. Neither the tag nor the value of
     * these pairs is the register's to compose: the acquirer states them, the
     * back office stores them as stated, and the pairs are read with the very
     * codec the wire uses — the {@code {TAG}value} syntax the requirement
     * itself writes these codes in. Nothing here invents a tag.
     *
     * <p>What this does NOT do is place them: WHERE in a request layout these
     * pairs belong is part of the layout above, which is still a TODO. They are
     * appended, which is the only honest thing to do with a pair whose position
     * nobody has specified yet.
     *
     * @param tags the request's tag map, in order
     */
    private void appendAdministeredTags(Map<String, String> tags) {
        for (String code : administeredVicProductCodes()) {
            tags.putAll(VerifoneFrameCodec.decode(code));
        }
        if (posSettingsService == null || !posSettingsService.terminalPdvLabelEnabled()) {
            return;
        }
        String label = posSettingsService.terminalPdvLabel();
        if (label == null || label.isBlank()) {
            // The activation is on and nothing is administered: nothing is
            // added. An empty label is not a label.
            return;
        }
        tags.putAll(VerifoneFrameCodec.decode(label.trim()));
    }

    /**
     * Reads the VIC product codes of the tenders in service (BO-03-09-30).
     *
     * <p>A database read on the exchange thread, guarded: a referential this
     * client cannot reach — no persistence context in a unit test, a database
     * hiccup at the worst moment — must not cost the customer their payment.
     * The codes are an addition to the request, never a condition of it.
     *
     * @return the administered codes, empty when none can be read
     */
    private java.util.List<String> administeredVicProductCodes() {
        try {
            return com.intermarche.pos.domain.payment.TenderDefinition.activeVicProductCodes();
        } catch (Exception e) {
            LOGGER.debugf("VIC product codes unavailable: %s", e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Maps a final response frame onto the callback: accepted, refused with
     * a TNA print frame, or the real payment mean via {D16}/{D46}.
     *
     * @param response the raw response frame
     * @param amount the transaction amount
     * @param callback the decision receiver
     */
    private void handleResponse(String response, BigDecimal amount,
                                TerminalTransactionCallback callback) {
        // TODO(spec Verifone): decision tag, TNA detection (LC-07-04-12),
        // print frames (LC-07-04-10/11), mean mapping {D16}/{D46}
        // (LC-07-07-04). Until then a response cannot be trusted:
        Map<String, String> tags = VerifoneFrameCodec.decode(response);
        TerminalOutcome outcome = TerminalOutcome.ofAmount(amount);
        outcome.rawResponse = response;
        outcome.meanCode = tags.getOrDefault(VerifoneTags.PAYMENT_MEAN,
                tags.get(VerifoneTags.PAYMENT_MEAN_FALLBACK));
        // EXP-54: trace the real card entry mode the terminal returns.
        outcome.entryMode = mapEntryMode(tags.get(VerifoneTags.ENTRY_MODE));
        callback.onError(i18n.get(MSG_NOT_IMPLEMENTED_KEY));
    }

    /**
     * Maps the terminal's raw entry-mode tag to the traced value (EXP-54): a
     * contactless tap and a chip-and-PIN transaction are the two the requirement
     * names; any other or absent value is not discriminated (null).
     *
     * @param raw the raw {@code D17} entry-mode tag, or null
     * @return {@code "SANS_CONTACT"}, {@code "CODE"}, or null
     */
    static String mapEntryMode(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase()) {
            case "C", "CTLS", "NFC", "SANS_CONTACT" -> "SANS_CONTACT";
            case "P", "PIN", "CHIP", "CODE" -> "CODE";
            default -> null;
        };
    }
}
