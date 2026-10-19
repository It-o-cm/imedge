package com.intermarche.edge.service.sync;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON payloads exchanged between a register and the store node (phase 5).
 * <p>
 * Every reference is a natural key (ticket/session/refund number, cashier
 * login, store code, EAN/PLU, line uid) — never a local database id, since
 * each register has its own database. Dates travel as ISO-8601 strings to
 * stay independent of the JSON date modules on both ends.
 * <p>
 * Evolution discipline: ADD fields, never rename or repurpose them — both
 * ends default missing fields to null (tolerant reader), so a register and
 * a store node one version apart keep synchronizing during a fleet rollout.
 */
public final class SyncPayloads {

    /**
     * Non-instantiable payload container.
     */
    private SyncPayloads() {}

    /**
     * A COUNTER TICKET pushed by a scale system to the store node (LC-06-01-02),
     * and served back to the register that picks it up.
     *
     * <p>It travels in ONE direction on the way in — scale to shop — and the
     * other on the way out, which is why the same shape serves both: what the
     * register integrates must be exactly what the counter weighed.
     */
    public static class BalanceTicketDto {
        /** The reference printed on the counter paper (upsert key). */
        public String reference;
        /** The counter that weighed it, for the operator's eyes. */
        public String counterLabel;
        /** The emission timestamp, ISO-8601. */
        public String emittedAt;
        /** The weighed lines, in the order the counter served them. */
        public List<BalanceTicketLineDto> lines = new ArrayList<>();
    }

    /**
     * One weighed line of a {@link BalanceTicketDto}.
     */
    public static class BalanceTicketLineDto {
        /** The article's EAN. */
        public String ean;
        /** The label as the counter printed it. */
        public String label;
        /** The weighed quantity in kilograms. */
        public BigDecimal quantity;
        /** The line total including tax, as the scale computed it. */
        public BigDecimal totalIncludingTax;
        /** The VAT rate as a fraction, or null to let the register resolve it. */
        public BigDecimal vatRate;
    }

    /**
     * A cash session, pushed at opening and again at closing (upsert by
     * session number).
     */
    public static class SessionDto {
        /** The session number (upsert key). */
        public String sessionNumber;
        /** The register identifier. */
        public String terminalId;
        /** The session status name. */
        public String status;
        /** The opening timestamp, ISO-8601. */
        public String openingDate;
        /** The closing timestamp, ISO-8601, or null. */
        public String closingDate;
        /** The login of the opening cashier. */
        public String openingCashierLogin;
        /** The login of the closing cashier, or null. */
        public String closingCashierLogin;
        /** The opening float. */
        public BigDecimal openingFloat;
        /** The counted amount, or null. */
        public BigDecimal countedAmount;
        /** The theoretical amount, or null. */
        public BigDecimal theoreticalAmount;
        /** The variance, or null. */
        public BigDecimal variance;
        /** The withdrawal, or null. */
        public BigDecimal withdrawnAmount;
        /** The denominations detail, or null. */
        public String countDetail;
        /**
         * The supervisor who validated an over-threshold discrepancy, or null
         * (EXP-82): the nominative trace of an opposable close, carried so the
         * store node's Z shows who arbitrated the écart.
         */
        public String varianceEndorsedBy;
        /**
         * The paper-tender declaration counted at closing, JSON, or null
         * (EXP-83): the per-tender declared quantities the node compares to the
         * theoretical.
         */
        public String paperDeclaration;
        /**
         * True when this was a floating-float evening WITHOUT a full count
         * (EXP-95): the node knows no variance was arbitrated that night.
         */
        public boolean floatingNoCount;
    }

    /**
     * A closed or cancelled ticket with its lines and payments (upsert by
     * ticket number).
     */
    public static class TicketDto {
        /**
         * The ticket as the selling register printed it, 42-column text
         * (LC-08-02-02), or null on a ticket closed before the field existed.
         */
        public String formattedContent;

        /** The ticket number (upsert key). */
        public String ticketNumber;
        /** The register identifier. */
        public String terminalId;
        /** The ticket status name. */
        public String status;
        /** The creation timestamp, ISO-8601. */
        public String creationDate;
        /** The closing timestamp, ISO-8601, or null. */
        public String closingDate;
        /** The store code. */
        public String storeCode;
        /** The login of the cashier. */
        public String cashierLogin;
        /** The session number, or null. */
        public String sessionNumber;
        /** The fidelity card, or null. */
        public String fidelityCard;
        /** The loyalty earn the sale displayed, or null (BO-03-03-31). */
        public BigDecimal fidelityEarnTotal;
        /** The balance read when the card was attached, or null. */
        public BigDecimal fidelityAvailableBalance;
        /** The projected balance after this sale's earn (imfid response B), or null. */
        public BigDecimal fidelityProjectedAfterEarn;
        /** The balance after the fidelity burn, from the ledger (imfid response B), or null. */
        public BigDecimal fidelityBalanceAfter;
        /** Whether the earn total is an anonymous projection (imfid response C). */
        public boolean fidelityAnonymousProjection;
        /** Whether the loyalty service was unreachable during the sale. */
        public boolean fidelityUnavailable;
        /** The advantage lines the sale displayed, empty when it displayed none. */
        public List<FidelityLineDto> fidelityLines = new ArrayList<>();
        /** The engine offer messages the sale displayed, empty when it displayed none (GB-01-07-05/06). */
        public List<MessageDto> messages = new ArrayList<>();
        /** The digital receipt key, or null. */
        public String digitalKey;
        /** The customer email, or null. */
        public String customerEmail;
        /** The line count. */
        public int itemCount;
        /** The total excluding tax. */
        public BigDecimal totalExcludingTax;
        /** The total including tax. */
        public BigDecimal totalIncludingTax;
        /** The VAT total. */
        public BigDecimal totalVat;
        /** The chained signature, or null. */
        public String signature;
        /** The previous signature, or null. */
        public String previousSignature;
        /** The perpetual grand total snapshot, or null. */
        public BigDecimal grandTotal;
        /** The valuation status name. */
        public String valuationStatus;
        /** The ticket lines. */
        /** When the cart was parked (BO-06-06-01) as an ISO local datetime, or null. */
        public String parkedAt;
        /** When the parked cart was recalled (BO-06-06-01), or null while it waits. */
        public String resumedAt;
        /** The register that recalled it (BO-06-06-01), or null while it waits. */
        public String resumedTerminalId;
        public List<LineDto> lines = new ArrayList<>();
        /** The registered payments. */
        public List<PaymentDto> payments = new ArrayList<>();
    }

    /**
     * One loyalty advantage line as the selling register displayed it
     * (BO-03-03-25).
     *
     * <p>It travels so a duplicata issued by the store node restates the very
     * lines the customer was shown; the node never recomputes them, and neither
     * does the register.
     */
    public static class FidelityLineDto {
        /** The stable code of the crediting rule, or null. */
        public String ruleCode;
        /** The printable label of the rule, or null. */
        public String label;
        /** The earn amount of the line, after caps, or null. */
        public BigDecimal amount;
        /** The advantage type code the line groups under, or null (BO-03-03-25). */
        public String advantageType;
        /** The advantage type label, printed verbatim, or null. */
        public String advantageTypeLabel;
        /** The order the advantage-type groups print in, or null. */
        public Integer advantageTypeOrder;
        /** The advantage category code, or null. */
        public String advantageCategory;
        /** The advantage category label, printed verbatim, or null. */
        public String advantageCategoryLabel;
    }

    /**
     * One engine offer message as the selling register froze it on the ticket
     * (GB-01-07-05/06).
     *
     * <p>It travels so a duplicata issued by the store node restates the very
     * text the customer was shown; the node never recomputes them, and neither
     * does the register.
     */
    public static class MessageDto {
        /** The stable message code, as the engine named it, or null. */
        public String code;
        /** The message text shown and printed. */
        public String text;
        /** Where the message prints: {@code "FOOTER"} — the default — or null read the same way. */
        public String printMode;
    }

    /**
     * A ticket line.
     */
    public static class LineDto {
        /** The 1-based line number. */
        public int lineNumber;
        /** The stable line uid, or null on legacy rows. */
        public String lineUid;
        /** The EAN snapshot, or null. */
        public String ean;
        /** The PLU snapshot, or null. */
        public String plu;
        /** The line label. */
        public String productLabel;
        /** The quantity. */
        public BigDecimal quantity;
        /** The unit price including tax. */
        public BigDecimal unitPrice;
        /** The VAT rate. */
        public BigDecimal vatRate;
        /** The price-modification label, or null. */
        public String modifierLabel;
        /** Structured gesture type (REMISE, DISCOUNT, FORCE_PRICE), or null. */
        public String modifierType;
        /**
         * Structured gesture value, or null. REMISE = UNIT discount in euros (line
         * effect = modifierValue * quantity); DISCOUNT = percent; FORCE_PRICE = new
         * unit price. The reliable line total stays unitPrice * quantity.
         */
        public BigDecimal modifierValue;
        /** The catalog unit price before modification, or null. */
        public BigDecimal originalUnitPrice;
        /** The line total including tax. */
        public BigDecimal totalPrice;
        /** True for deposit-return lines. */
        public boolean deposit;
        /** The nomenclature (family) code snapshotted at sale time (BO-04-01-11), or null. */
        public String familyCode;
        /** The nomenclature (family) label snapshotted at sale time (BO-04-01-11), or null. */
        public String familyLabel;
        /** True when the line was cancelled by the cashier and kept as a witness (BO-04-01-16). */
        public boolean cancelled;
        /** The article-cancellation timestamp as an ISO local datetime (BO-04-01-16), or null. */
        public String cancellationDate;
        /** The badge of the operator who cancelled the line (BO-04-01-16), or null. */
        public String cancelledBy;
        /** Code of the justification given for the cancellation (BO-06-06-04), or null. */
        public String cancellationReasonCode;
        /** Label of the justification given for the cancellation (BO-06-06-04), or null. */
        public String cancellationReasonLabel;
        /**
         * True when the line sold a money instrument (gift card) rather than goods
         * (R12): the store node keeps such a line out of the turnover and shows it
         * as an emission instead. Without it the net turnover is overstated by
         * everything the shop sells in gift cards.
         */
        public boolean moneyProduct;
        /**
         * True when the cancelled line was the LAST of the ticket at the moment of
         * the gesture (R10): the financial state counts a last-line cancellation —
         * a mis-scan corrected on the spot — apart from the endorsed cancellation
         * of any other line, which is the fraud indicator. Meaningless unless
         * {@link #cancelled} is true.
         */
        public boolean cancelledLast;
    }

    /**
     * A registered payment.
     */
    public static class PaymentDto {
        /** The 1-based registration order. */
        public int paymentIndex;
        /** The payment method key (discriminator value). */
        public String methodKey;
        /** The applied amount. */
        public BigDecimal amount;
        /** The tendered amount for cash payments, or null. */
        public BigDecimal tenderedAmount;
        /** The voucher label, or null. */
        public String voucherLabel;
        /** The voucher number, or null. */
        public String voucherNumber;
        /** The card authorization number (BO-04-01-08), or null. */
        public String authorizationNumber;
        /** True when the card payment was accepted in degraded mode (BO-04-01-47/49). */
        public boolean degradedMode;
        /** The real CB entry mode read off the terminal (SANS_CONTACT / CODE), or null (EXP-54). */
        public String cardEntryMode;
        /** The CMC7 magnetic line read off the cheque, or null. */
        public String magneticLine;
        /** The account charged by a customer-credit settlement (LC-07-09), or null. */
        public String creditAccountNumber;
        /** The account name as it stood at sale time, or null. */
        public String creditAccountName;
        /** True when a supervisor authorized the settlement over the ceiling. */
        public boolean creditOverLimit;
        /** The scheme a backup-monetics settlement reported (LC-07-07-08), or null. */
        public String backupMethodLabel;
        /** The transaction number the two backup QR codes were matched on, or null. */
        public String backupTransaction;
        /** True when a backup-monetics outcome was keyed in rather than scanned. */
        public boolean backupManual;
        /** The ISO code of the currency handed over (LC-07-14), or null. */
        public String currencyCode;
        /** The amount handed over in that currency, or null. */
        public BigDecimal currencyAmount;
        /** The euros-for-one-unit rate applied, or null. */
        public BigDecimal currencyRate;
        /**
         * The per-rate VAT detail of the recovered deposit, in the compact
         * {@code DepositVat} form, or null on every other payment
         * (LC-06-03-08): the store node accounts the recovery by rate, so
         * the witness travels with the settlement.
         */
        public String depositVatDetail;
        /** The code of the order whose deposit was recovered, or null. */
        public String depositOrderCode;
    }

    /**
     * A refund with its lines (upsert by refund number).
     */
    public static class RefundDto {
        /** The refund number (upsert key). */
        public String refundNumber;
        /** The register identifier. */
        public String terminalId;
        /** The refund status name. */
        public String status;
        /** The refund method name, or null. */
        public String refundMethod;
        /** The number of the refunded ticket. */
        public String originalTicketNumber;
        /** The session number, or null. */
        public String sessionNumber;
        /** The creation timestamp, ISO-8601. */
        public String creationDate;
        /** The refunded total including tax. */
        public BigDecimal totalAmount;
        /** The refunded total excluding tax, or null. */
        public BigDecimal totalExcludingTax;
        /** The refunded VAT total, or null. */
        public BigDecimal totalVat;
        /** The refunded lines. */
        /** The return reason chosen by the cashier (BO-06-06-05), or null. */
        public String returnReason;
        /** Code of that administered reason (BO-06-06-05), or null. */
        public String returnReasonCode;
        /** Code of the administered return type the gesture was made under (BO-06-06-05), or null. */
        public String returnTypeCode;
        /** Label of that return type (BO-06-06-05), or null. */
        public String returnTypeLabel;
        /** Badge of the operator who made the refund (BO-06-06-05), or null. */
        public String cashierBadgeId;
        /** Badge of the supervisor who endorsed it (BO-06-06-05), or null. */
        public String supervisorBadgeId;
        public List<RefundLineDto> lines = new ArrayList<>();
    }

    /**
     * A technical journal event (upsert by uid).
     */
    public static class EventDto {
        /** The stable event uid (upsert key). */
        public String eventUid;
        /** The register identifier. */
        public String terminalId;
        /** The event type name. */
        public String type;
        /** The event detail, or null. */
        public String detail;
        /** The badge of the operator the event is about, or null. */
        public String operatorBadgeId;
        /** The event timestamp, ISO-8601. */
        public String eventDate;
    }

    /**
     * A cash movement (upsert by uid); references its session and cashier by
     * natural key (BO-04-01-12/33/35/36/37/40/44).
     */
    public static class MovementDto {
        /** The stable movement uid (upsert key). */
        public String movementUid;
        /** The register identifier. */
        public String terminalId;
        /** The session number, or null. */
        public String sessionNumber;
        /** The login of the cashier, or null. */
        public String cashierLogin;
        /** The movement type name. */
        public String type;
        /** The movement amount. */
        public BigDecimal amount;
        /** The free-text reason, or null. */
        public String reason;
        /** The movement timestamp, ISO-8601. */
        public String movementDate;
        /** The badge of the endorsing manager, or null. */
        public String endorsedBy;
        /**
         * True when the movement is a cash withdrawal destined for the SAFE
         * (EXP-87): the store node accounts it into the coffre balance. False for
         * every other movement.
         */
        public boolean safeDeposit;
        /**
         * The order this movement is attached to, or null (EXP-85): a customer
         * down-payment (acompte) carries the order reference so the node imputes
         * it to that order and returns it as the already-paid down-payment when
         * the order is recalled by code.
         */
        public String orderReference;
    }

    /**
     * An account customer created at the register (upsert by account number,
     * LC-08-04-09). It references nothing: a business the store bills is a
     * first-class party, not a satellite of a sale.
     */
    public static class CustomerDto {
        /** The account number (upsert key). */
        public String accountNumber;
        /** The business name. */
        public String companyName;
        /** The contact's last name, or null. */
        public String lastName;
        /** The contact's first name, or null. */
        public String firstName;
        /** The street line, or null. */
        public String street;
        /** The postal code, or null. */
        public String postalCode;
        /** The town, or null. */
        public String city;
        /** The SIRET, or null. */
        public String siret;
        /** The intra-community VAT number, or null. */
        public String vatNumber;
        /** The telephone number, or null. */
        public String phone;
        /** The electronic address, or null. */
        public String email;
    }

    /**
     * A document issued at a register — invoice or delivery note
     * (upsert by document number, {@code BO-02-04-19}).
     *
     * <p>Carries the customer block AS PRINTED and not a reference to the
     * customer's file: the back office extracts these documents years later,
     * and what it must show is what the paper said that day. The ticket is
     * named by its number, the only identity the two nodes share.
     */
    public static class DocumentDto {
        /** The document number (upsert key). */
        public String documentNumber;
        /** The kind: FACTURE or BON_LIVRAISON. */
        public String documentType;
        /** The register that issued it. */
        public String terminalId;
        /** When it was issued, ISO-8601. */
        public String issueDate;
        /** The number of the ticket it was drawn from. */
        public String ticketNumber;
        /** The account number it is addressed to, or null. */
        public String customerAccountNumber;
        /** The business name as printed. */
        public String customerName;
        /** The contact as printed, or null. */
        public String customerContact;
        /** The SIRET as printed, or null. */
        public String customerSiret;
        /** The intra-community VAT number as printed, or null. */
        public String customerVatNumber;
        /** The fiscal identifier as printed, or null. */
        public String customerTaxId;
        /** The due date as printed, {@code yyyy-MM-dd}, or null (BO-02-04-08). */
        public String customerDueDate;
        /** The street line as printed, or null. */
        public String customerStreet;
        /** The postal code as printed, or null. */
        public String customerPostalCode;
        /** The town as printed, or null. */
        public String customerCity;
        /** The tax-excluded total as printed. */
        public BigDecimal totalExcludingTax;
        /** The tax-included total as printed. */
        public BigDecimal totalIncludingTax;
        /** The tax total as printed. */
        public BigDecimal totalVat;
        /** The eco-tax borne by the billed sale, or null when not extracted (BO-10-04-14). */
        public BigDecimal totalEcoTax;
        /** How many times it had been printed when it was pushed. */
        public int printCount;
    }

    /**
     * A refunded line.
     */
    public static class RefundLineDto {
        /** The stable uid of the refunded original line, or null on legacy rows. */
        public String originalLineUid;
        /** The refunded label. */
        public String productLabel;
        /** The refunded quantity. */
        public BigDecimal quantity;
        /** The refunded unit price. */
        public BigDecimal price;
        /** The VAT rate, or null. */
        public BigDecimal vatRate;
    }
}
