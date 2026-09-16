package com.intermarche.pos.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One administered control of a barcode range: what the register checks when a
 * code of that range is scanned, how loudly it reacts, and what it says
 * (BO-03-06-39/40/45/46/47/48/49/67).
 *
 * <p>A control is what gives an administered {@link CouponField} a READER. A
 * position holding an expiry date changes nothing on its own; it starts
 * mattering the day a shop administers {@link Kind#EXPIRED} on the range. The
 * two halves are deliberately separate: the position says where the value is,
 * the control says what to do about it, and the same position can be read by
 * more than one control.
 *
 * <p>At most one control per {@link Kind} and per range. An absent control is
 * {@link AlertLevel#NONE}: silence, which is the only default that cannot stop
 * a lane.
 */
@Entity
@Table(name = "coupon_control")
public class CouponControl extends PanacheEntity {

    /**
     * The barcode range this control belongs to.
     */
    @ManyToOne(optional = false)
    @JoinColumn(name = "coupon_type_id", nullable = false)
    public CouponType couponType;

    /**
     * What the register checks.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    public Kind kind;

    /**
     * How the register reacts when the check finds something.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    public AlertLevel level = AlertLevel.NONE;

    /**
     * The message shown to the cashier, or null for the control's own wording.
     */
    @Column(name = "message", length = 120)
    public String message;

    /**
     * Returns the message to show, falling back on the control's own wording.
     *
     * @return the administered message, or the default of its kind
     */
    public String effectiveMessage() {
        if (message != null && !message.isBlank()) {
            return message;
        }
        return kind == null ? "" : kind.getDefaultMessage();
    }

    /**
     * Names what the register checks, and which administered position the
     * check reads.
     */
    public enum Kind {

        /**
         * The code is not usable yet: its start date is still ahead
         * (BO-03-06-67). Reads {@link CouponField.Role#DATE_START}.
         */
        NOT_YET_VALID("BON PAS ENCORE VALABLE"),

        /**
         * The code has expired: its end date is past (BO-03-06-47).
         * Reads {@link CouponField.Role#DATE_END}.
         */
        EXPIRED("BON EXPIRE"),

        /**
         * The code names another point of sale (BO-03-06-46).
         * Reads {@link CouponField.Role#STORE_NUMBER}.
         */
        OTHER_STORE("BON D'UN AUTRE MAGASIN"),

        /**
         * The control character of the point of sale number does not check out
         * (BO-03-06-27). Reads {@link CouponField.Role#STORE_CHECK_DIGIT}
         * against {@link CouponField.Role#STORE_NUMBER}.
         */
        STORE_CHECK("CLE MAGASIN INCORRECTE"),

        /**
         * The ticket does not reach the minimum total the code requires
         * (BO-03-06-28/29). Reads {@link CouponField.Role#MIN_TICKET_TOTAL}.
         */
        MIN_TICKET_TOTAL("TOTAL TICKET INSUFFISANT"),

        /**
         * The code has already been used on this point of sale
         * (BO-03-06-39/49). Reads {@link CouponField.Role#SEQUENCE_NUMBER}
         * when the range administers one, and the whole code otherwise.
         */
        DUPLICATE("BON DEJA UTILISE"),

        /**
         * No loyalty card is attached to the sale, and the code requires one
         * (BO-03-06-40). Reads the sale, not a position: the code says nothing
         * about the card, the RANGE says a card is needed.
         */
        FIDELITY_REQUIRED("CARTE FIDELITE REQUISE"),

        /**
         * The card attached to the sale is not the one the code was issued for
         * (BO-03-06-40). Reads {@link CouponField.Role#CARD_MATCH}: the code
         * carries a part of the card number, and the presented card must
         * contain it. Silent when the range administers no such position —
         * a code carrying nothing about its card cannot contradict one.
         */
        FIDELITY_MISMATCH("CARTE FIDELITE NON CONCORDANTE"),

        /**
         * The register's checkout island is not one the range is accepted on
         * (BO-03-06-07). Reads {@link CouponType#islandCodes} against the island
         * the register belongs to, not a position of the code.
         */
        OTHER_ISLAND("BON REFUSE SUR CET ILOT");

        /** What the cashier is told when the shop administered no wording. */
        private final String defaultMessage;

        /**
         * Builds a control kind over its default wording.
         *
         * @param defaultMessage the message shown when none is administered
         */
        Kind(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        /**
         * Returns the wording used when the shop administered none.
         *
         * @return the default message
         */
        public String getDefaultMessage() {
            return defaultMessage;
        }
    }
}
