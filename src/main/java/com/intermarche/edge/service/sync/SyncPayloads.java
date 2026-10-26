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

        /**
         * The customer language of the ticket (LC-13-06-09, -14), or null when
         * the sale ran in the register's language.
         */
        public String customerLanguage;

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
        /** The pot balances frozen at closing (BO-10-02-33), or null. */
        public String fidelityPotBalances;
        /** The loyalty messages frozen on the ticket, or null (BO-03-03-26, BO-03-03-27). */
        public String fidelityMessages;
        /** The advantage lines the sale displayed, empty when it displayed none. */
        public List<FidelityLineDto> fidelityLines = new ArrayList<>();
        /** The engine offer messages the sale displayed, empty when it displayed none (GB-01-07-05/06). */
        public List<MessageDto> messages = new ArrayList<>();
        /** The digital receipt key, or null. */
        public String digitalKey;
        /** The customer email, or null. */
        public String customerEmail;
        /** Fiscal identifier selected on the ordinary sale (LC-08-06-02/-03). */
        public String customerTaxId;
        /** The sales channel of an integrated transaction (BO-07-01-02), null for the store's lanes. */
        public String salesChannel;
        /** True on a self-scanning basket loaded at the till (BO-04-01-41). */
        public boolean selfScanning;
        /** True when the hostess decided the basket's control (BO-04-01-42). */
        public boolean selfScanControlled;
        /** True when the system decided the basket's control (BO-04-01-43). */
        public boolean selfScanVerified;
        /** True on a sale made while the store node was unreachable (BO-04-01-14). */
        public boolean offline;
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
        /** When the cashier went to payment (BO-06-06-02), or null. */
        public String paymentStartedAt;
        /** The per-line valuation trace: the promotions each line took (BO-06-08-02). */
        public List<ValuationDto> valuations = new ArrayList<>();
        public List<LineDto> lines = new ArrayList<>();
        /** The registered payments. */
        public List<PaymentDto> payments = new ArrayList<>();
        /** The Portuguese documents stating the sale (BO-10-06-03), empty elsewhere. */
        public List<FiscalDocumentDto> fiscalDocuments = new ArrayList<>();
    }

    /**
     * One promotion trace of a ticket line (BO-06-08-02): which offer valued
     * the line and what it took off, as the register's valuation recorded it.
     */
    public static class ValuationDto {
        /** The line the trace belongs to. */
        public String lineUid;
        /** The register's own total of the line. */
        public BigDecimal localTotal;
        /** The total the engine valued the line at. */
        public BigDecimal valuedTotal;
        /** The offers the line took part in, by their identifiers. */
        public String offerLabel;
        /** The advantage the line received. */
        public String advantageLabel;
        /** The advantage's amount on the line. */
        public BigDecimal advantageAmount;
        /** How the advantage is printed. */
        public String advantagePrintMode;
        /** The correction applied to the line. */
        public String correctionLabel;
        /** The correction's amount on the line. */
        public BigDecimal correctionAmount;
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
        /** LC-02-03-04: principal owning an automatically registered companion. */
        public String linkedParentUid;
        /** Companion EAN captured on the line. */
        public String linkedArticleEan;
        /** Last quantity whose companion chain was recorded. */
        public BigDecimal linkedRecordedQuantity;
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
        /** The number of the VAT regime of the line, or null (BO-02-02-01). */
        public Integer vatNumber;
        /** The price-modification label, or null. */
        public String modifierLabel;
        /** Structured gesture type (REMISE, DISCOUNT, FORCE_PRICE), or null. */
        public String modifierType;
        /**
         * Structured gesture value as typed, read by {@link #modifierType}, or
         * null: REMISE = a UNIT discount in euros (line effect = value times
         * quantity), DISCOUNT = a percentage, FORCE_PRICE = the new UNIT price. The
         * reliable line total is always {@code unitPrice * quantity}
         * ({@link #totalPrice}), never derived from this value.
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
        /** Code of the return type a return line was rung under (BO-03-13-12), or null. */
        public String returnTypeCode;
        /** Label of the return type a return line was rung under (BO-03-13-12), or null. */
        public String returnTypeLabel;
        /** True when the line is a return rung inside a sale (BO-03-13-11, BO-03-13-12). */
        public boolean returnLine;
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
        /** True when the article was rung by a scan, false when keyed (BO-04-01-14, BO-06-04-05). */
        public boolean scanned;
        /** True when the line's price was keyed (BO-04-01-14). */
        public boolean priceEntered;
        /** True when the article is a bulky one carried out (BO-04-01-14). */
        public boolean carriedOut;
        /** The tare deducted from the weighing, in kilograms, or null (BO-04-01-14 « déduction »). */
        public java.math.BigDecimal appliedTareKg;
        /** True when the line is exempt of VAT, distinct from a zero rate (BO-02-03-26/27). */
        public boolean vatExempt;
        /** True when the article was sold on behalf of a third party (BO-02-03-41 to -43). */
        public boolean thirdPartySale;
        /** True when the article sold is a service and not goods (BO-05-01-03). */
        public boolean service;
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
        /** LC-07-07-03: selected server, independent of the resulting tender. */
        public String moneticServer;
        /** The CMC7 magnetic line read off the cheque, or null. */
        public String magneticLine;
        /** The account number keyed for the cheque (BO-03-02-24), or null. */
        public String chequeAccountNumber;
        /** The cheque-guarantee authorization number (BO-03-02-24), or null. */
        public String chequeGuaranteeAuthorization;
        /** The cheque-guarantee provider that authorized the cheque (BO-03-02-24), or null. */
        public String chequeGuaranteeProvider;
        /** The administered tender the settlement was taken as (BO-03-02-01/-44), or null. */
        public String tenderCode;
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
        /** LC-07-07-03: actual server used for card credit. */
        public String moneticServer;
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
        /** The Portuguese credit notes stating the return (BO-10-06-03), empty elsewhere. */
        public List<FiscalDocumentDto> fiscalDocuments = new ArrayList<>();
    }

    /**
     * A Portuguese fiscal document as the register issued it (BO-10-06-03):
     * carried with its sale or its return so the store node holds what its
     * SAF-T export states. Upserted by number; never renumbered by the node.
     */
    public static class FiscalDocumentDto {
        /** The document type name: FS, FT, NC or RC. */
        public String documentType;
        /** The series: the register and the year. */
        public String series;
        /** The rank in the series. */
        public long rank;
        /** The document number (upsert key). */
        public String number;
        /** The ATCUD. */
        public String atcud;
        /** The numeric SAF-T identifier of the type (BO-10-06-01/02), or null. */
        public Integer saftDocumentId;
        /** The issuing register. */
        public String terminalId;
        /** The issuing timestamp, ISO-8601. */
        public String issuedAt;
        /** The part of the sale stated, 0 for the whole sale. */
        public int part;
        /** The uids of the lines stated, or null for every line. */
        public String lineUids;
        /** The number of the document referred to, or null. */
        public String sourceNumber;
        /** The customer's tax number, or null. */
        public String customerTaxId;
        /** The customer's name, or null. */
        public String customerName;
        /** The customer's address, or null. */
        public String customerAddress;
        /** The gross total. */
        public BigDecimal grossTotal;
        /** The tax total. */
        public BigDecimal taxTotal;
        /** The chained signature. */
        public String hash;
        /** The previous document's signature. */
        public String previousHash;
        /** The QR payload. */
        public String qrPayload;
        /** Whether a credit note cancelled it. */
        public boolean cancelled;
        /** The SAF-T origin of the document (BO-07-01-06): null when the software produced it, I when integrated. */
        public String sourceBilling;
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
        /** The number of the ticket the event is about, or null (BO-04-01-19/34). */
        public String ticketNumber;
        /** The amount the event bears, or null (BO-04-01-19). */
        public BigDecimal amount;
        /** The searchable code the event carries, or null (BO-04-01-19/34). */
        public String code;
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
        /**
         * The tender the movement was made in, or null for cash (BO-05-02-09):
         * the back office validates each cashier's movements tender by tender.
         */
        public String paymentMethod;
        /**
         * The tender a transfer moved the amount to, or null for any other
         * movement (BO-05-02-09, LC-12-10).
         */
        public String transferTo;
        /** The denominations counted, as the register froze them, or null (BO-05-02-09). */
        public String denominationDetail;
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
        /**
         * The fiscal identifier (NIF) the hostess typed, or null when the
         * register states none (BO-02-04-13).
         */
        public String taxId;
        /**
         * Where the customer came from ({@code AccountCustomer.Origin} name):
         * a customer opened at a till to carry one document is VOLATILE
         * (BO-06-03-04), or null from a register of an older version.
         */
        public String origin;
    }

    /**
     * A ticket a register printed (LC-08-02-02), upsert by terminal and the
     * register's id.
     */
    public static class PrintoutDto {
        /** The register's id of the printed ticket (upsert key with the terminal). */
        public Long sourceId;
        /** The register that printed it. */
        public String terminalId;
        /** The register function that produced the paper. */
        public String source;
        /** CUSTOMER, STORE or TRAINING. */
        public String audience;
        /** When it was printed, ISO-8601. */
        public String issuedAt;
        /** The ticket as printed, 42-column text. */
        public String content;
    }

    /**
     * The signed trace of a ticket put on hold, resumed or purged (LC-04-01-03),
     * upsert by terminal and the register's id.
     */
    public static class ParkedTicketDto {
        /** The register's id of the record (upsert key with the terminal). */
        public Long sourceId;
        /** The register the record belongs to. */
        public String terminalId;
        /** The number of the ticket on hold. */
        public String ticketNumber;
        /** What happened to it: PARKED, RESUMED or PURGED. */
        public String event;
        /** When it happened, ISO-8601. */
        public String eventDate;
        /** Who did it. */
        public String operator;
        /** The number of articles the ticket carried. */
        public int itemCount;
        /** The total the ticket carried, TTC. */
        public java.math.BigDecimal totalAmount;
        /** The ticket's lines at that moment. */
        public String content;
        /** The signature of the previous record of the register. */
        public String previousSignature;
        /** The signature of the record. */
        public String signature;
    }

    /**
     * An instrument a register printed, as that register knows it
     * (BO-03-06-62, -48), upsert by the issuing register and the number.
     */
    public static class StoredValueDto {
        /** The register that printed the instrument (upsert key with the number). */
        public String issuingTerminalId;
        /** The register reporting. */
        public String terminalId;
        /** The printed number. */
        public String number;
        /** CREDIT_NOTE or GIFT_CARD. */
        public String kind;
        /** The amount it was issued with. */
        public java.math.BigDecimal initialAmount;
        /** Its balance. */
        public java.math.BigDecimal balance;
        /** ACTIVE or EXHAUSTED. */
        public String status;
        /** When it was printed, ISO-8601. */
        public String issuedAt;
        /** Its end of validity, ISO-8601, or null. */
        public String validUntil;
        /** When its balance reached zero, ISO-8601, or null. */
        public String exhaustedAt;
        /** The sale or refund that printed it. */
        public String issuingDocumentNumber;
        /**
         * The point of sale the instrument was printed in, set by the store
         * node that holds it and carried to the central node, where the
         * instruments of every shop meet (BO-03-06-48); null from a register.
         */
        public String pdvNumber;
    }

    /**
     * A payment barcode read at a till (BO-03-06-63), upsert by terminal and
     * the register's id.
     */
    public static class ScannedBarcodeDto {
        /** The register's id of the reading (upsert key with the terminal). */
        public Long sourceId;
        /** The register the code was read on. */
        public String terminalId;
        /** The code read. */
        public String code;
        /** The range that recognised it, or null. */
        public String typeCode;
        /** That range's label, or null. */
        public String typeLabel;
        /** The sale it was presented to, or null. */
        public String ticketNumber;
        /** What it was worth, or null. */
        public java.math.BigDecimal amount;
        /** Its end of validity, ISO-8601, or null. */
        public String validUntil;
        /** When it was read, ISO-8601. */
        public String scannedAt;
        /** Whether the system knew it. */
        public boolean found;
        /** Whether it was read as a GS1 payload (BO-11-01-18). */
        public boolean gs1;
    }

    /**
     * A sale made in training mode (LC-13-05-06), upsert by terminal and the
     * register's id.
     */
    public static class TrainingTicketDto {
        /** The register's id of the training sale (upsert key with the terminal). */
        public Long sourceId;
        /** The register the training sale was made on. */
        public String terminalId;
        /** The login of the operator who trained, or null. */
        public String cashierLogin;
        /** When the training sale ended, ISO-8601. */
        public String issuedAt;
        /** The number of articles sold. */
        public int itemCount;
        /** The total, TTC. */
        public java.math.BigDecimal totalIncludingTax;
        /** The training receipt as printed. */
        public String content;
        /** When the training cart was parked (BO-06-06-01), ISO-8601, or null. */
        public String parkedAt;
        /** Who parked it, or null. */
        public String parkedBy;
        /** When it was recalled, ISO-8601, or null. */
        public String resumedAt;
        /** The register that recalled it, or null. */
        public String resumedTerminalId;
        /** Who recalled it, or null. */
        public String resumedBy;
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
        /** The customer's order number the document states, or null (BO-03-03-02). */
        public String orderNumber;
        /** The administered document it was drawn as, or null (BO-03-04-09). */
        public String documentCode;
        /** The name printed at the top of the document, or null (BO-03-03-02). */
        public String documentTitle;
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
