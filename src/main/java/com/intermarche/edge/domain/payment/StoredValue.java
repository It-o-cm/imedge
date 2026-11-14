package com.intermarche.edge.domain.payment;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The register's STORED-VALUE REGISTRY: the single source of truth behind
 * every instrument that carries money across sales (phase: credit notes &
 * gift cards). A credit note (avoir) issued by a refund and a gift card sold
 * as a product are the SAME object — a numbered balance account: the note is
 * simply the degenerate case usually consumed in one payment, the card the
 * full case with partial redemptions and a residual balance.
 * <p>
 * DOCTRINE. The registry is AUTHORITATIVE: the scanned number carries no
 * amount (unlike the historical 50-prefixed voucher, replayable at will) —
 * every redemption asks the registry for the live balance, applies
 * {@code min(balance, remaining due)}, and the balance only DECREMENTS at
 * the FISCAL MOMENT of the redeeming sale (validateTicket): a payment
 * cancelled or a register crashing mid-sale never burns a cent of stored
 * value. Numbers are pure identifiers: {@code 297} + 12 digits for credit
 * notes, {@code 296} + 12 digits for gift cards (the digits are the registry
 * row id, zero-padded).
 */
@Entity
@Table(name = "stored_values")
public class StoredValue extends PanacheEntity {

    /** The credit-note number prefix (avoir). */
    public static final String CREDIT_NOTE_PREFIX = "297";

    /** The gift-card number prefix. */
    public static final String GIFT_CARD_PREFIX = "296";

    /**
     * The scannable identifier, unique: the prefix and 12 digits, or the code
     * composed from the range administered for issuing this kind, up to 38
     * characters (BO-03-06-30 to -38).
     */
    @Column(name = "number", length = 40, unique = true, nullable = false)
    public String number;

    /** The instrument kind. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 15, nullable = false)
    public Kind kind;

    /** The amount the instrument was issued with. */
    @Column(name = "initial_amount", precision = 10, scale = 2, nullable = false)
    public BigDecimal initialAmount;

    /** The remaining redeemable balance. */
    @Column(name = "balance", precision = 10, scale = 2, nullable = false)
    public BigDecimal balance;

    /** The lifecycle status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 12, nullable = false)
    public Status status = Status.ACTIVE;

    /** When the instrument was issued. */
    @Column(name = "issued_at", nullable = false)
    public LocalDateTime issuedAt;

    /** The refund that issued this credit note, or null (gift cards). */
    @Column(name = "issuing_refund_id")
    public Long issuingRefundId;

    /** The sale that issued this gift card, or null (credit notes). */
    @Column(name = "issuing_ticket_id")
    public Long issuingTicketId;

    /**
     * The register that issued the instrument (BO-03-06-62): the one whose
     * printer produced the barcode, kept on a copy another register adopted.
     */
    @Column(name = "issuing_terminal_id", length = 20)
    public String issuingTerminalId;

    /**
     * The number of the sale or refund that issued the instrument
     * (BO-03-06-62), as printed on its ticket.
     */
    @Column(name = "issuing_document_number", length = 40)
    public String issuingDocumentNumber;

    /** The last sale that redeemed against this balance, or null. */
    @Column(name = "last_redeemed_ticket_id")
    public Long lastRedeemedTicketId;

    /** When the balance reached zero, or null while redeemable. */
    @Column(name = "exhausted_at")
    public LocalDateTime exhaustedAt;

    /**
     * The end of the instrument's validity (LC-05-06-07), or null when it never
     * expires. Set on a credit note (avoir) at issue and printed on the AVOIR
     * ticket; the redemption side may later refuse an expired instrument.
     */
    @Column(name = "valid_until")
    public LocalDateTime validUntil;

    /** The instrument kinds of the registry. */
    public enum Kind {
        /** A refund-issued credit note (avoir). */
        CREDIT_NOTE,
        /** A sold gift card. */
        GIFT_CARD
    }

    /** The lifecycle states of an instrument. */
    public enum Status {
        /** Redeemable: the balance is positive. */
        ACTIVE,
        /** The balance reached zero; the number is dead. */
        EXHAUSTED
    }

    /**
     * Finds an instrument by its scanned number.
     *
     * @param number the scanned number
     * @return the instrument, or null when the registry never issued it
     */
    public static StoredValue findByNumber(String number) {
        return find("number", number).firstResult();
    }

    /**
     * Tells whether a scanned code is a registry number (either prefix).
     *
     * @param code the scanned code
     * @return true when the code matches the registry number shape
     */
    public static boolean isRegistryNumber(String code) {
        return code != null && code.matches("(" + CREDIT_NOTE_PREFIX + "|" + GIFT_CARD_PREFIX + ")\\d{12}$");
    }
}
