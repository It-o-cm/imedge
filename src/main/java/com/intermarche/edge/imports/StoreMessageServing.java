package com.intermarche.edge.imports;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WHAT THE NODE HAS ALREADY SERVED TO ONE REGISTER, and whether it must keep
 * serving it — the state half of {@link StoreMessageFeedResource}
 * ({@code LC-01-08-05}).
 *
 * <p>A register polls and shows ONE banner. Nothing in the wire says how long
 * the banner must stay, when the store took the notice down, or who read it:
 * those three questions are answered HERE, on the node, because it is the one
 * that knows the message's administered duration and the one that sees the
 * deactivation and the acknowledgements. The rule the feed follows:
 *
 * <ol>
 *   <li>a message served to a terminal is recorded with the moment of its
 *       FIRST service — the window does not restart on every poll, or the
 *       banner would never expire;</li>
 *   <li>once the administered display duration has elapsed, the node serves
 *       the BLANK message exactly once — the register clears its banner — and
 *       the message is CONSUMED for that terminal: serving it again would
 *       put back a notice the shop asked to stay twenty seconds;</li>
 *   <li>when the message stops applying (deactivated on the back office,
 *       period over, audience changed), the terminal that was shown it gets
 *       the same single blank answer, then nothing;</li>
 *   <li>an acknowledgement from the terminal consumes the message the same
 *       way and files who read it — the store knows WHO has not.</li>
 * </ol>
 *
 * <p>EPHEMERAL BY DESIGN, like the supervisor-call board: a store-node restart
 * forgets which terminal saw which message, and a still-active notice is
 * served once more. A message is a signal, not a document; the durable
 * witnesses are the {@code StoreMessageAck} rows the acknowledgements file.
 */
@ApplicationScoped
public class StoreMessageServing {

    /**
     * What one terminal currently sees: the message, the moment of its first
     * service and the duration its shop administered.
     */
    private static final class Entry {

        /** The served message's id. */
        final Long messageId;

        /** When it was FIRST served to this terminal. */
        final LocalDateTime servedAt;

        /** The administered display duration, zero for until replaced. */
        final int durationSeconds;

        /**
         * Builds the serving entry.
         *
         * @param messageId the served message's id
         * @param servedAt the first service moment
         * @param durationSeconds the administered display duration
         */
        Entry(Long messageId, LocalDateTime servedAt, int durationSeconds) {
            this.messageId = messageId;
            this.servedAt = servedAt;
            this.durationSeconds = durationSeconds;
        }
    }

    /** What each terminal currently sees, keyed by terminal id. */
    private final Map<String, Entry> serving = new ConcurrentHashMap<>();

    /** The messages a terminal has consumed: expired, taken down or acked. */
    private final Set<String> consumed = new CopyOnWriteArraySet<>();

    /**
     * Records the FIRST service of a message to a terminal, or keeps the
     * existing entry untouched when the same message is served again — the
     * display window is counted from the first service, never restarted.
     *
     * @param terminalId the register being served
     * @param messageId the message's id
     * @param durationSeconds the administered display duration, zero for
     *        until replaced
     * @param now the moment of the service
     */
    public void record(String terminalId, Long messageId, int durationSeconds,
                       LocalDateTime now) {
        if (terminalId == null || messageId == null) {
            return;
        }
        Entry existing = serving.get(terminalId);
        if (existing != null && messageId.equals(existing.messageId)) {
            return;
        }
        serving.put(terminalId, new Entry(messageId, now, durationSeconds));
    }

    /**
     * Whether a message has been CONSUMED for a terminal — expired, taken
     * down or acknowledged — and must not be served to it again.
     *
     * @param terminalId the register asking, possibly null
     * @param messageId the message considered
     * @return true when the terminal has consumed that message
     */
    public boolean isConsumed(String terminalId, Long messageId) {
        if (terminalId == null || messageId == null) {
            return false;
        }
        return consumed.contains(key(terminalId, messageId));
    }

    /**
     * Whether the message a terminal is being served AGAIN has exhausted its
     * administered display duration ({@code LC-01-08-05}): the blank answer
     * must go out and the message be consumed.
     *
     * <p>A duration of zero never expires — the banner stays until the store
     * replaces or takes down the notice, which is what the administration
     * screen promises.
     *
     * @param terminalId the register asking
     * @param now the moment of the question
     * @return true when the served message's display duration has elapsed
     */
    public boolean durationOver(String terminalId, LocalDateTime now) {
        Entry entry = terminalId == null ? null : serving.get(terminalId);
        if (entry == null || entry.durationSeconds <= 0) {
            return false;
        }
        return now.isAfter(entry.servedAt.plusSeconds(entry.durationSeconds));
    }

    /**
     * Consumes a terminal's current message and answers whether the terminal
     * must be told: the caller serves the single blank answer exactly when
     * this answers true, so an expiry, a take-down or an acknowledgement
     * clears the lane's banner ONCE and never serves the notice again.
     *
     * @param terminalId the register whose banner must be cleared
     * @return true when the terminal was being served a message
     */
    public boolean consume(String terminalId) {
        Entry entry = terminalId == null ? null : serving.remove(terminalId);
        if (entry == null) {
            return false;
        }
        consumed.add(key(terminalId, entry.messageId));
        return true;
    }

    /**
     * The consumed-set key of a terminal and a message.
     *
     * @param terminalId the register
     * @param messageId the message
     * @return the key
     */
    private String key(String terminalId, Long messageId) {
        return terminalId + "|" + messageId;
    }
}
