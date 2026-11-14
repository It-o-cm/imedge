package com.intermarche.edge.ui.journal;

/**
 * The EVENTS the transactional journal extracts tickets by (BO-04-01-14):
 * the list the search form offers, each followed — but for the training mode
 * and the offline sale — by a range of amounts bearing on the event itself.
 * <p>
 * Most events are read on the consolidated ticket, its lines or its
 * payments; two have another source: the training sales live in their own
 * table, and a « pas de vente » is a drawer opened outside any sale, which no
 * ticket documents but the electronic journal does.
 */
public enum JournalEvent {

    /** A cancelled ticket; the amount is the ticket's. */
    CANCELLED(true),
    /** A tare deducted from a weighed article; the amount is the line's. */
    DEDUCTION(true),
    /** A remise or a discount, on the ticket or a line; the amount is the reduction. */
    DISCOUNT(true),
    /** A return given back on the ticket; the amount is the refund's. */
    RETURN(true),
    /** A negative transaction; the amount is the ticket's. */
    NEGATIVE(true),
    /** A forced price; the amount is the forced line's. */
    FORCED_PRICE(true),
    /** A price keyed by the operator; the amount is the line's. */
    PRICE_ENTERED(true),
    /** An article rung by a scan; the amount is the line's. */
    SCANNED(true),
    /** A bulky article carried out without the belt; the amount is the line's. */
    CARRIED_OUT(true),
    /** A drawer opened outside any sale; the amount is the event's when it bears one. */
    NO_SALE(true),
    /** A store voucher taken in payment; the amount is the voucher's. */
    STORE_VOUCHER(true),
    /** A vendor (manufacturer) coupon taken in payment; the amount is the coupon's. */
    VENDOR_COUPON(true),
    /** A VAT-exempt article; the amount is the line's. */
    VAT_EXEMPT(true),
    /** A training-mode sale: no amount range. */
    TRAINING(false),
    /** A sale made while the register had lost the store node: no amount range. */
    OFFLINE(false);

    /** Whether the event is followed by a range of amounts. */
    private final boolean amountRange;

    /**
     * Declares an event.
     *
     * @param amountRange whether a range of amounts follows it
     */
    JournalEvent(boolean amountRange) {
        this.amountRange = amountRange;
    }

    /**
     * Whether the event is followed by a range of amounts (BO-04-01-14: all
     * but the training mode and the offline sale).
     *
     * @return true when an amount range applies
     */
    public boolean isAmountRange() {
        return amountRange;
    }

    /**
     * Reads an event name, ignoring an unknown or blank one.
     *
     * @param raw the posted name, possibly null
     * @return the event, or null
     */
    public static JournalEvent of(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (JournalEvent event : values()) {
            if (event.name().equals(raw.trim())) {
                return event;
            }
        }
        return null;
    }
}
