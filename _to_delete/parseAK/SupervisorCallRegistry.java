package com.intermarche.edge.ui.dashboard;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory registry of the pending supervisor calls on the store node
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
     * A pending supervisor call.
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
     * Returns the pending calls, oldest first.
     *
     * @return the pending calls
     */
    public List<Call> getPending() {
        return List.copyOf(pending);
    }

    /**
     * Acknowledges a call, removing it from the pending list.
     *
     * @param id the registry id of the call
     */
    public void acknowledge(long id) {
        pending.removeIf(call -> call.id == id);
    }
}
