package com.intermarche.edge.ui.dashboard;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory registry of the supervisor calls on the store node
 * (phase 5 lot 2). Registers push calls over HTTP; the dashboard shows them
 * as a banner until a manager acknowledges. Ephemeral by design: a call is
 * a real-time signal, not a document — a store-node restart drops the
 * pending list (the register-side journal keeps the trace).
 * <p>
 * No deduplication on purpose: the same register calling twice shows two
 * banners — two calls are two needs, and collapsing them would hide an
 * escalation. The list is unbounded but self-limiting in practice (every
 * banner begs to be acknowledged); it never touches the database.
 */
@ApplicationScoped
public class SupervisorCallRegistry {

    /**
     * What has become of a call ({@code BO-04-02-03}, {@code -04},
     * {@code -05}).
     *
     * <p>A call is SETTLED the moment somebody has decided what happens to
     * it, wherever that decision was taken: at the till by a supervisor who
     * was standing there, or at the back office by a manager reading the
     * banner. A settled call carries the decision in writing, because the
     * requirement asks for the list to say WHY a demand no longer expects
     * anything — and whether it still shows at all is the shop's choice.
     */
    public enum CallStatus {

        /** Nobody has decided yet: the banner begs to be acknowledged. */
        PENDING("en attente"),

        /** A supervisor settled it AT THE TILL (BO-04-02-03). */
        HANDLED_AT_TILL("déjà traitée en caisse"),

        /** The back office sent it back to the till (BO-04-02-04). */
        TO_HANDLE_AT_TILL("à traiter en caisse"),

        /** The back office refused it (BO-04-02-05). */
        REFUSED("refusée sur le BackOffice"),

        /** The back office authorised it (BO-04-02-02). */
        AUTHORISED("autorisée sur le BackOffice");

        /** What the dashboard calls this status. */
        private final String label;

        /**
         * Builds a status with its dashboard label.
         *
         * @param label what the dashboard calls it
         */
        CallStatus(String label) {
            this.label = label;
        }

        /**
         * What the dashboard calls this status.
         *
         * @return the label, never null
         */
        public String getLabel() {
            return label;
        }
    }

    /**
     * A supervisor call, pending or settled.
     */
    public static class Call {
        /** The registry id used for acknowledgement. */
        public final long id;
        /** The calling register identifier. */
        public final String terminalId;
        /** The display name of the calling operator. */
        public final String operator;
        /** The call reason (an administered service reason, EXP-84). */
        public final String reason;
        /** The free message the cashier typed, or an empty string (EXP-19). */
        public final String message;
        /** The reception time, formatted HH:mm. */
        public final String time;
        /**
         * The priority the administered type carries ({@code BO-04-02-07}),
         * zero when the reason matches no administered type — a call from a
         * register still reading the semicolon setting, or a free reason.
         */
        public final int severity;
        /**
         * What has become of the call ({@code BO-04-02-03/-04/-05}), never
         * null. Mutable, and the only mutable field of this object: a call
         * is one event whose OUTCOME changes, not two events.
         */
        public volatile CallStatus status = CallStatus.PENDING;
        /** Why the back office refused it, or an empty string. */
        public volatile String refusalReason = "";

        /**
         * Creates a pending call carrying no priority — the shape every call
         * had before the service-request types were administered.
         *
         * @param id the registry id
         * @param terminalId the calling register identifier
         * @param operator the calling operator display name
         * @param reason the call reason
         * @param message the cashier's free message, or null (EXP-19)
         */
        public Call(long id, String terminalId, String operator, String reason, String message) {
            this(id, terminalId, operator, reason, message, 0);
        }

        /**
         * Creates a pending call carrying the priority of its administered
         * type ({@code BO-04-02-07}).
         *
         * @param id the registry id
         * @param terminalId the calling register identifier
         * @param operator the calling operator display name
         * @param reason the call reason
         * @param message the cashier's free message, or null (EXP-19)
         * @param severity the priority of the administered type, 0 when none
         */
        public Call(long id, String terminalId, String operator, String reason, String message,
                    int severity) {
            this.id = id;
            this.terminalId = terminalId;
            this.operator = operator;
            this.reason = reason;
            this.message = message == null ? "" : message;
            this.time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            this.severity = severity;
        }
    }

    /** The id sequence. */
    private final AtomicLong sequence = new AtomicLong();

    /** The pending calls, oldest first. */
    private final CopyOnWriteArrayList<Call> pending = new CopyOnWriteArrayList<>();

    /**
     * Registers an incoming call carrying the cashier's free message (EXP-19)
     * alongside its administered reason (EXP-84).
     *
     * @param terminalId the calling register identifier
     * @param operator the calling operator display name
     * @param reason the call reason
     * @param message the cashier's free message, or null
     */
    public void add(String terminalId, String operator, String reason, String message) {
        add(terminalId, operator, reason, message, 0);
    }

    /**
     * Registers an incoming call carrying the PRIORITY of its administered
     * type ({@code BO-04-02-07}), which the back office shows beside it.
     *
     * @param terminalId the calling register identifier
     * @param operator the calling operator display name
     * @param reason the call reason
     * @param message the cashier's free message, or null
     * @param severity the priority of the administered type, 0 when none
     */
    public void add(String terminalId, String operator, String reason, String message,
                    int severity) {
        pending.add(new Call(sequence.incrementAndGet(), terminalId, operator, reason, message,
                severity));
    }

    /**
     * Returns the calls the dashboard shows, oldest first: the pending ones
     * and the settled ones a shop chose to keep displayed.
     *
     * @return the calls on the board
     */
    public List<Call> getPending() {
        return List.copyOf(pending);
    }

    /**
     * Finds the call a registry id names, pending or settled.
     *
     * <p>BEFORE the acknowledgement, so the board can tell the CALLING LANE
     * its request was read (LC-01-07-04): the acknowledge itself removes the
     * call, and the terminal it names has to be read before it is gone.
     *
     * @param id the registry id of the call
     * @return the call, or null when no call carries that id
     */
    public Call find(long id) {
        for (Call call : pending) {
            if (call.id == id) {
                return call;
            }
        }
        return null;
    }

    /**
     * Cancels the PENDING requests a register withdraws from its own lane
     * (LC-01-07-05): they disappear from the back office, and the register
     * lowers its own indicator in the same gesture.
     *
     * <p>Only the PENDING ones: a request the back office has already
     * acknowledged or settled has been read, and a cashier cancelling the
     * banner would not un-read it. Unknown terminal, blank name, nothing
     * pending — all answer zero, which is the truth.
     *
     * @param terminalId the register withdrawing its requests
     * @return how many pending requests were removed
     */
    public int cancelPendingFor(String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return 0;
        }
        String typed = terminalId.trim();
        java.util.List<Call> snapshot = List.copyOf(pending);
        int removed = 0;
        for (Call call : snapshot) {
            if (call.status == CallStatus.PENDING && typed.equalsIgnoreCase(call.terminalId)) {
                pending.remove(call);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Acknowledges a call, removing it from the list.
     *
     * <p>An acknowledgement is not an outcome: it is a manager saying the
     * banner has been read. The call leaves the board whatever the shop's
     * retention choice, because nothing else would ever take it off.
     *
     * @param id the registry id of the call
     */
    public void acknowledge(long id) {
        pending.removeIf(call -> call.id == id);
    }

    /**
     * Settles a call at the back office ({@code BO-04-02-04},
     * {@code BO-04-02-05}): it is marked, and kept on the board or taken off
     * it as the shop administered.
     *
     * @param id the registry id of the call
     * @param status what became of it
     * @param refusalReason the refusal wording, or null
     * @param kept whether a settled call stays displayed
     * @return the settled call, or null when no call carries that id
     */
    public Call settle(long id, CallStatus status, String refusalReason, boolean kept) {
        Call settled = null;
        for (Call call : pending) {
            if (call.id == id) {
                settled = call;
                break;
            }
        }
        if (settled == null) {
            return null;
        }
        settled.status = status;
        settled.refusalReason = refusalReason == null ? "" : refusalReason;
        if (!kept) {
            pending.remove(settled);
        }
        return settled;
    }

    /**
     * Marks every call a register still has open as SETTLED AT THE TILL
     * ({@code BO-04-02-03}): a supervisor standing at the lane granted what
     * was asked, so the back office has nothing left to do.
     *
     * <p>Every PENDING call of that register and not only the last: a
     * cashier who called twice before the supervisor arrived made one need,
     * and leaving the first banner up would send a manager to a lane where
     * there is nobody to help.
     *
     * @param terminalId the register whose calls were settled
     * @param kept whether a settled call stays displayed
     * @return how many calls were settled
     */
    public int settleAtTill(String terminalId, boolean kept) {
        if (terminalId == null || terminalId.isBlank()) {
            return 0;
        }
        String typed = terminalId.trim();
        int settled = 0;
        for (Call call : List.copyOf(pending)) {
            if (call.status == CallStatus.PENDING && typed.equalsIgnoreCase(call.terminalId)) {
                call.status = CallStatus.HANDLED_AT_TILL;
                if (!kept) {
                    pending.remove(call);
                }
                settled++;
            }
        }
        return settled;
    }
}
