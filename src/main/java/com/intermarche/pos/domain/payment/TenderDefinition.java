package com.intermarche.pos.domain.payment;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.List;

/**
 * An administered TENDER: the back-office row that puts one payment method in
 * service and states the rules the register applies to it
 * (BO-03-02-02/03/04/05/10/12/13/14/15/16/17/18/19/21/22/23/25/30).
 *
 * <p>The doctrine, stated once so no reader has to guess it: the register knows
 * HOW to settle a sale in cash, by card, by cheque, on account, on the fidelity
 * purse, in a foreign currency, on a meal voucher, on a gift voucher and through
 * the backup terminal — that knowledge is code, and it stays code. What the back
 * office decides is WHICH of those are in service in this store and UNDER WHAT
 * RULES. A row here is therefore never a new way to settle; it is a way already
 * built, switched on and bounded. A tender of a genuinely new nature — a new
 * protocol, a new settlement conversation — remains a delivery.
 *
 * <p>{@link #code} is deliberately the JPA discriminator of the matching
 * {@link TicketPayment} subclass ({@code CASH}, {@code CARD}, {@code TR}…), which
 * is the key already flowing through the session reports, the sync payloads and
 * {@link TicketPayment#getMethodKey()}. Joining on anything else would have
 * created a second vocabulary for the same nine things.
 *
 * <p>Every ceiling carries its own {@link ControlLevel} rather than a single
 * global one, because the questionnaire asks for exactly that: a meal voucher
 * over 25 € may be a hard stop while a cash payment over its ceiling is a mere
 * warning. A ceiling left null is not administered and never fires, whatever its
 * control level says.
 */
@Entity
@Table(name = "tender_definition")
public class TenderDefinition extends PanacheEntity {

    /**
     * The shortest functional identifier the referential accepts (BO-03-02-04):
     * three digits, so the plan is never capped at ninety-nine tenders.
     */
    public static final int MIN_FUNCTIONAL_ID_LENGTH = 3;

    /**
     * The settlement key, equal to the JPA discriminator of the matching
     * {@link TicketPayment} subclass — CASH, CARD, CHEQUE, CREDIT, FIDELITY,
     * DEVISE, TR, VOUCHER, SECOURS, ARRONDI.
     */
    @Column(name = "code", unique = true, nullable = false, length = 20)
    public String code;

    /**
     * The functional identifier the store administers, on at least
     * {@link #MIN_FUNCTIONAL_ID_LENGTH} digits (BO-03-02-04).
     */
    @Column(name = "functional_id", unique = true, nullable = false, length = 10)
    public String functionalId;

    /** The label shown on the payment screen and on the reports. */
    @Column(name = "label", nullable = false, length = 60)
    public String label;

    /** Whether the tender is in service; deactivated, never deleted (BO-03-02-03). */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** The order the tenders are offered in; lower comes first. */
    @Column(name = "display_order", nullable = false)
    public int displayOrder = 100;

    /** The largest amount one settlement may carry, or null when unbounded (BO-03-02-10). */
    @Column(name = "max_amount", precision = 19, scale = 4)
    public BigDecimal maxAmount;

    /** What the register does when {@link #maxAmount} is exceeded (BO-03-02-10). */
    @Enumerated(EnumType.STRING)
    @Column(name = "max_amount_control", nullable = false, length = 16)
    public ControlLevel maxAmountControl = ControlLevel.NONE;

    /** A second ceiling, administered independently of the first (BO-03-02-13). */
    @Column(name = "second_max_amount", precision = 19, scale = 4)
    public BigDecimal secondMaxAmount;

    /** What the register does when {@link #secondMaxAmount} is exceeded (BO-03-02-13). */
    @Enumerated(EnumType.STRING)
    @Column(name = "second_max_amount_control", nullable = false, length = 16)
    public ControlLevel secondMaxAmountControl = ControlLevel.NONE;

    /** The smallest amount one settlement may carry, or null when unbounded (BO-03-02-14). */
    @Column(name = "min_amount", precision = 19, scale = 4)
    public BigDecimal minAmount;

    /** What the register does when {@link #minAmount} is not reached (BO-03-02-14). */
    @Enumerated(EnumType.STRING)
    @Column(name = "min_amount_control", nullable = false, length = 16)
    public ControlLevel minAmountControl = ControlLevel.NONE;

    /**
     * How many settlements of this tender one sale may carry, or null when
     * unbounded — two meal vouchers in France, for instance (BO-03-02-12).
     */
    @Column(name = "max_count")
    public Integer maxCount;

    /** What the register does when {@link #maxCount} is reached (BO-03-02-12). */
    @Enumerated(EnumType.STRING)
    @Column(name = "max_count_control", nullable = false, length = 16)
    public ControlLevel maxCountControl = ControlLevel.NONE;

    /** The largest change this tender may give back, or null when unbounded (BO-03-02-11). */
    @Column(name = "max_change_amount", precision = 19, scale = 4)
    public BigDecimal maxChangeAmount;

    /** What the register does when {@link #maxChangeAmount} is exceeded (BO-03-02-11). */
    @Enumerated(EnumType.STRING)
    @Column(name = "max_change_control", nullable = false, length = 16)
    public ControlLevel maxChangeControl = ControlLevel.NONE;

    /** Whether the tender may be used to refund a customer (BO-03-02-15). */
    @Column(name = "refund_allowed", nullable = false)
    public boolean refundAllowed = false;

    /** Whether an overpayment on this tender gives change back (BO-03-02-16). */
    @Column(name = "change_allowed", nullable = false)
    public boolean changeAllowed = false;

    /**
     * The tender the change is given in, as a {@link #code}, or null to give it
     * back in this same tender (BO-03-02-16 — "rendu avoir autorisé avec un avoir
     * et espèce").
     */
    @Column(name = "change_tender_code", length = 20)
    public String changeTenderCode;

    /** Whether the cashier's holding of this tender is declared automatically (BO-03-02-17). */
    @Column(name = "cashier_declaration", nullable = false)
    public boolean cashierDeclaration = false;

    /** Whether this tender is withdrawn from the drawer automatically (BO-03-02-18). */
    @Column(name = "automatic_withdrawal", nullable = false)
    public boolean automaticWithdrawal = false;

    /** When the cash drawer opens for this tender (BO-03-02-19). */
    @Enumerated(EnumType.STRING)
    @Column(name = "drawer_opening", nullable = false, length = 20)
    public DrawerOpening drawerOpening = DrawerOpening.NEVER;

    /** Whether cash movements — floats and expenses — may use this tender (BO-03-02-20). */
    @Column(name = "movement_allowed", nullable = false)
    public boolean movementAllowed = false;

    /** Whether the takings of this tender are deposited at the bank (BO-03-02-21). */
    @Column(name = "bank_deposit", nullable = false)
    public boolean bankDeposit = false;

    /** Whether this tender may make up the opening float (BO-03-02-22). */
    @Column(name = "float_allowed", nullable = false)
    public boolean floatAllowed = false;

    /** Whether the remaining due is pre-filled when this tender is picked (BO-03-02-23). */
    @Column(name = "defaults_to_total", nullable = false)
    public boolean defaultsToTotal = true;

    /** Whether the withdrawal report details this tender rather than totalling it (BO-03-02-25). */
    @Column(name = "withdrawal_report_detail", nullable = false)
    public boolean withdrawalReportDetail = true;

    /** Whether the use of this tender is reported to the fidelity programme (BO-03-02-30). */
    @Column(name = "fidelity_reported", nullable = false)
    public boolean fidelityReported = false;

    /**
     * Whether an amount may be transferred OUT of this tender ({@code BO-05-02-17}).
     *
     * <p>Source and destination are administered SEPARATELY because they are not
     * the same permission: a discount voucher may be corrected into cash — the
     * shop took a voucher and recorded cash — while cash may never be corrected
     * into a voucher, which would invent a voucher nobody handed over.
     */
    @Column(name = "transfer_source", nullable = false)
    public boolean transferSource = true;

    /** Whether an amount may be transferred INTO this tender ({@code BO-05-02-17}). */
    @Column(name = "transfer_destination", nullable = false)
    public boolean transferDestination = true;


    /**
     * Tells whether one settlement of the given amount goes over the first
     * administered ceiling (BO-03-02-10).
     *
     * @param amount the amount of the settlement, or null
     * @return true when a ceiling is administered and the amount exceeds it
     */
    public boolean exceedsMaxAmount(BigDecimal amount) {
        return exceeds(amount, maxAmount);
    }

    /**
     * Tells whether one settlement of the given amount goes over the second
     * administered ceiling (BO-03-02-13).
     *
     * @param amount the amount of the settlement, or null
     * @return true when a second ceiling is administered and the amount exceeds it
     */
    public boolean exceedsSecondMaxAmount(BigDecimal amount) {
        return exceeds(amount, secondMaxAmount);
    }

    /**
     * Tells whether the change to give back goes over the administered change
     * ceiling (BO-03-02-11).
     *
     * @param change the change to give back, or null
     * @return true when a change ceiling is administered and the change exceeds it
     */
    public boolean exceedsMaxChange(BigDecimal change) {
        return exceeds(change, maxChangeAmount);
    }

    /**
     * Tells whether one settlement of the given amount falls under the
     * administered floor (BO-03-02-14).
     *
     * @param amount the amount of the settlement, or null
     * @return true when a floor is administered and the amount falls short of it
     */
    public boolean belowMinAmount(BigDecimal amount) {
        if (amount == null || minAmount == null) {
            return false;
        }
        return amount.compareTo(minAmount) < 0;
    }

    /**
     * Tells whether registering one more settlement of this tender would go over
     * the administered count (BO-03-02-12).
     *
     * @param alreadyRegistered how many settlements of this tender the sale carries
     * @return true when a count is administered and one more would exceed it
     */
    public boolean exceedsMaxCount(int alreadyRegistered) {
        if (maxCount == null) {
            return false;
        }
        return alreadyRegistered + 1 > maxCount;
    }

    /**
     * Tells whether the drawer must open for this tender in the given situation
     * (BO-03-02-19).
     *
     * <p>The three administered moments are evaluated against the situation the
     * caller describes, so one call site serves all of them: a caller that has
     * just registered the settlement passes {@code receiptPrinted} false, and the
     * one printing the receipt passes it true.
     *
     * @param changeDue true when change is owed to the customer
     * @param receiptPrinted true when the customer receipt has just been printed
     * @return true when the drawer opens at this moment
     */
    public boolean opensDrawer(boolean changeDue, boolean receiptPrinted) {
        if (drawerOpening == null) {
            return false;
        }
        switch (drawerOpening) {
            case AFTER_PAYMENT:
                return !receiptPrinted;
            case IF_CHANGE_DUE:
                return changeDue && !receiptPrinted;
            case AFTER_RECEIPT:
                return receiptPrinted;
            default:
                return false;
        }
    }

    /**
     * Returns the tender the change of this one is given in (BO-03-02-16).
     *
     * @return the administered change tender code, or this tender's own code when
     *         none is administered
     */
    public String changeTender() {
        if (changeTenderCode == null || changeTenderCode.isBlank()) {
            return code;
        }
        return changeTenderCode.trim();
    }

    /**
     * Compares an amount against an administered bound, an absent bound never
     * firing.
     *
     * @param amount the amount to test, or null
     * @param bound the administered bound, or null when unbounded
     * @return true when both are present and the amount is strictly above the bound
     */
    private boolean exceeds(BigDecimal amount, BigDecimal bound) {
        if (amount == null || bound == null) {
            return false;
        }
        return amount.compareTo(bound) > 0;
    }


    /**
     * Returns every administered tender, active or not, in administered order.
     *
     * @return the whole referential, empty when nothing is administered
     */
    public static List<TenderDefinition> listAllOrdered() {
        return list("order by displayOrder, code");
    }

    /**
     * Returns the administered tender of the given settlement key.
     *
     * @param code the settlement key, or null
     * @return the tender, or null when the key is administered by no row
     */
    public static TenderDefinition findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim()).firstResult();
    }

    /**
     * Tells whether the given settlement key is in service.
     *
     * <p>A key NO row administers is in service, deliberately: a store that has
     * not filled the referential in keeps every tender the register knows, which
     * is what makes the whole thing installable on an existing shop without a
     * migration day.
     *
     * @param code the settlement key, or null
     * @return true unless an administered row says the tender is out of service
     */
    public static boolean isActive(String code) {
        TenderDefinition tender = findByCode(code);
        return tender == null || tender.active;
    }

    /**
     * What the register does when an administered bound is not respected
     * (BO-03-02-10/11/12/13/14).
     *
     * <p>The constants are declared from the most permissive to the strictest,
     * and that order IS the severity scale: a caller weighing several violated
     * rules against each other compares declaration order, so a constant may
     * never be inserted anywhere but at its place on that scale.
     */
    public enum ControlLevel {

        /** No control: the bound is recorded but never opposed to the cashier. */
        NONE("Aucun"),

        /** The cashier is told, and nothing else happens. */
        INFO("Information"),

        /** The cashier is warned and must acknowledge. */
        WARNING("Avertissement"),

        /** A supervisor must authorize the settlement. */
        SUPERVISOR("Bloquant superviseur"),

        /** The settlement is refused outright. */
        BLOCKING("Bloquant");

        /** The label shown on the administration screen. */
        private final String label;

        /**
         * Builds a control level with its administration label.
         *
         * @param label the label shown on the administration screen
         */
        ControlLevel(String label) {
            this.label = label;
        }

        /**
         * Returns the label shown on the administration screen.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }

        /**
         * Tells whether this level stops the settlement from being registered as
         * it stands.
         *
         * @return true for the two blocking levels
         */
        public boolean blocks() {
            return this == SUPERVISOR || this == BLOCKING;
        }

        /**
         * Tells whether this level lets a supervisor pass the settlement through.
         *
         * @return true for the supervisor level only
         */
        public boolean allowsOverride() {
            return this == SUPERVISOR;
        }

        /**
         * Tells whether this level shows the cashier nothing at all.
         *
         * @return true for the silent level only
         */
        public boolean isSilent() {
            return this == NONE;
        }
    }

    /**
     * When the cash drawer opens for a tender (BO-03-02-19).
     */
    public enum DrawerOpening {

        /** The drawer never opens for this tender. */
        NEVER("Jamais"),

        /** The drawer opens as soon as the settlement is registered. */
        AFTER_PAYMENT("Après le paiement"),

        /** The drawer opens only when change is owed to the customer. */
        IF_CHANGE_DUE("Si rendu dû"),

        /** The drawer opens only once the customer receipt is printed. */
        AFTER_RECEIPT("Après impression du reçu");

        /** The label shown on the administration screen. */
        private final String label;

        /**
         * Builds a drawer-opening moment with its administration label.
         *
         * @param label the label shown on the administration screen
         */
        DrawerOpening(String label) {
            this.label = label;
        }

        /**
         * Returns the label shown on the administration screen.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }
}
