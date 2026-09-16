package com.intermarche.pos.service.sync;

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
        public List<LineDto> lines = new ArrayList<>();
        /** The registered payments. */
        public List<PaymentDto> payments = new ArrayList<>();
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
        /** Structured gesture value as typed, or null. */
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
