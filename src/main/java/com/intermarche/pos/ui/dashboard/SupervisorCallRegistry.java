package com.intermarche.pos.ui.dashboard;

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
        /** The call reason. */
        public final String reason;
        /** The reception time, formatted HH:mm. */
        public final String time;

        /**
         * Creates a pending call.
         *
         * @param id the registry id
         * @param terminalId the calling register identifier
         * @param operator the calling operator display name
         * @param reason the call reason
         */
        public Call(long id, String terminalId, String operator, String reason) {
            this.id = id;
            this.terminalId = terminalId;
            this.operator = operator;
            this.reason = reason;
            this.time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        }
    }

    /** The id sequence. */
    private final AtomicLong sequence = new AtomicLong();

    /** The pending calls, oldest first. */
    private final CopyOnWriteArrayList<Call> pending = new CopyOnWriteArrayList<>();

    /**
     * Registers an incoming call.
     *
     * @param terminalId the calling register identifier
     * @param operator the calling operator display name
     * @param reason the call reason
     */
    public void add(String terminalId, String operator, String reason) {
        pending.add(new Call(sequence.incrementAndGet(), terminalId, operator, reason));
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
