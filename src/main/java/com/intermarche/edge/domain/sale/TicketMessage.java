package com.intermarche.edge.domain.sale;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One offer message AS THE SALE SHOWED IT, frozen on the ticket at closing
 * (GB-01-07-05/06).
 *
 * <p>The text is copied here rather than looked up again: the message the
 * customer's receipt prints is the one the seller was shown on the day, not
 * whatever the engine would say now. The code travels beside it so the message
 * can be traced back, and the placement so a reprint restates it where the
 * original put it. It carries no money — a message says something, it never
 * changes a total.
 */
@Embeddable
public class TicketMessage {

    /** The stable message code, as the engine named it, or null. */
    @Column(name = "code", length = 50)
    public String code;

    /** The message text shown and printed. */
    @Column(name = "text", length = 300)
    public String text;

    /**
     * Where the message prints: {@code "FOOTER"} — the default — in the ticket
     * footer, or null read the same way. A basket-level message has no line to
     * attach to, so the printer places it in the footer whatever the value.
     */
    @Column(name = "print_mode", length = 10)
    public String printMode;

    /**
     * Creates an empty message (JPA).
     */
    public TicketMessage() {
    }

    /**
     * Creates a message with its code, text and placement.
     *
     * @param code the message code, or null
     * @param text the message text
     * @param printMode the placement, or null (read as FOOTER)
     */
    public TicketMessage(String code, String text, String printMode) {
        this.code = code;
        this.text = text;
        this.printMode = printMode;
    }
}
