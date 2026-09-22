package com.intermarche.pos.service.sync.register;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.RefundLine;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.payment.TicketPayment;
import com.intermarche.pos.domain.payment.VoucherPayment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import com.intermarche.pos.service.sync.SyncEndpoints;
import com.intermarche.pos.service.sync.SyncPayloads;

/**
 * Transactional half of the store synchronization: enqueues outbox rows in
 * the caller's transaction, prepares the JSON payload of an outbox item
 * (loading the entity graph inside a transaction) and records each push
 * outcome. The HTTP half lives in {@link SyncPushService}.
 * <p>
 * Two patterns worth naming. Enqueueing joins the CALLER'S transaction: the
 * outbox row commits or rolls back with the business event itself, which is
 * the whole no-loss guarantee — there is no window where a ticket exists
 * without its outbox row. And {@code prepare} serializes the entity graph
 * to JSON INSIDE a transaction precisely so the push loop never touches a
 * lazy entity outside one: a transaction is never held across network I/O,
 * the HTTP push works on a detached string.
 */
@ApplicationScoped
public class SyncOutboxService {

    private static final Logger LOGGER = Logger.getLogger(SyncOutboxService.class);

    /** Where the store node lives; shared with the referential pull loop. */
    @Inject
    SyncEndpoints endpoints;

    @Inject
    ObjectMapper objectMapper;

    /** The back-office parameters governing what a document extraction carries. */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * A payload ready to push: the target path suffix and the JSON body.
     */
    public static class PreparedItem {
        /** The store-node path suffix (e.g. "ticket"). */
        public final String pathSuffix;
        /** The serialized JSON body. */
        public final String json;

        /**
         * Creates a prepared item.
         *
         * @param pathSuffix the store-node path suffix
         * @param json the serialized JSON body
         */
        public PreparedItem(String pathSuffix, String json) {
            this.pathSuffix = pathSuffix;
            this.json = json;
        }
    }

    /**
     * Indicates whether the synchronization is enabled on this node.
     *
     * @return true when a store URL is configured
     */
    public boolean isEnabled() {
        LOGGER.info("Entering method isEnabled");
        LOGGER.info("Exiting method isEnabled");
        return endpoints.hasStoreUrl();
    }

    /**
     * Returns the configured store node URL without a trailing slash.
     *
     * @return the store URL, or an empty string when disabled
     */
    public String getStoreUrl() {
        LOGGER.info("Entering method getStoreUrl");
        LOGGER.info("Exiting method getStoreUrl");
        return endpoints.storeUrl();
    }

    /**
     * Enqueues an entity for synchronization, joining the caller's
     * transaction; no-op when the synchronization is disabled.
     *
     * @param type the entity kind
     * @param entityId the local database id of the entity
     */
    @Transactional
    public void enqueue(SyncOutbox.EntityType type, Long entityId) {
        LOGGER.info("Entering method enqueue with type: " + type + ", entityId: " + entityId);
        if (!isEnabled() || entityId == null) { LOGGER.info("Exiting method enqueue"); return; }
        SyncOutbox row = new SyncOutbox();
        row.entityType = type;
        row.entityId = entityId;
        row.createdAt = LocalDateTime.now();
        row.persist();
        LOGGER.info("Exiting method enqueue");
    }

    /**
     * Returns the next batch of outbox rows in drain order (sessions, then
     * tickets, then refunds; oldest first within a kind).
     *
     * @param batchSize the maximum number of rows
     * @return the ids of the rows to process
     */
    @Transactional
    public List<Long> nextBatchIds(int batchSize) {
        LOGGER.info("Entering method nextBatchIds with batchSize: " + batchSize);
        LOGGER.info("Exiting method nextBatchIds");
        return SyncOutbox.<SyncOutbox>find("order by entityType, id")
                .page(0, batchSize)
                .list()
                .stream().map(row -> row.id).toList();
    }

    /**
     * Prepares the payload of an outbox row, loading the entity graph inside
     * the transaction.
     *
     * @param outboxId the outbox row id
     * @return the prepared item, or null when the row or its entity vanished
     */
    @Transactional
    public PreparedItem prepare(Long outboxId) {
        LOGGER.info("Entering method prepare with outboxId: " + outboxId);
        SyncOutbox row = SyncOutbox.findById(outboxId);
        if (row == null) { LOGGER.info("Exiting method prepare"); return null; }
        try {
            LOGGER.info("Exiting method prepare");
            return switch (row.entityType) {
                case SESSION -> {
                    CashSession session = CashSession.findById(row.entityId);
                    yield session == null ? null
                            : new PreparedItem("session", objectMapper.writeValueAsString(toDto(session)));
                }
                case MOVEMENT -> {
                    CashMovement movement = CashMovement.findById(row.entityId);
                    yield movement == null ? null
                            : new PreparedItem("movement", objectMapper.writeValueAsString(toDto(movement)));
                }
                case TICKET -> {
                    Ticket ticket = Ticket.findById(row.entityId);
                    yield ticket == null ? null
                            : new PreparedItem("ticket", objectMapper.writeValueAsString(toDto(ticket)));
                }
                case REFUND -> {
                    Refund refund = Refund.findById(row.entityId);
                    yield refund == null ? null
                            : new PreparedItem("refund", objectMapper.writeValueAsString(toDto(refund)));
                }
                case EVENT -> {
                    TechnicalEvent event = TechnicalEvent.findById(row.entityId);
                    yield event == null ? null
                            : new PreparedItem("event", objectMapper.writeValueAsString(toDto(event)));
                }
                case CUSTOMER -> {
                    com.intermarche.pos.domain.payment.AccountCustomer customer =
                            com.intermarche.pos.domain.payment.AccountCustomer.findById(row.entityId);
                    yield customer == null ? null
                            : new PreparedItem("customer", objectMapper.writeValueAsString(toDto(customer)));
                }
                case DOCUMENT -> {
                    com.intermarche.pos.domain.sale.Invoice document =
                            com.intermarche.pos.domain.sale.Invoice.findById(row.entityId);
                    yield document == null ? null
                            : new PreparedItem("document", objectMapper.writeValueAsString(toDto(document)));
                }
            };
        } catch (Exception e) {
            LOGGER.errorf("Préparation sync impossible (outbox %d): %s", outboxId, e.getMessage());
            LOGGER.info("Exiting method prepare");
            return null;
        }
    }

    /**
     * Deletes an acknowledged outbox row.
     *
     * @param outboxId the outbox row id
     */
    @Transactional
    public void markSuccess(Long outboxId) {
        LOGGER.info("Entering method markSuccess with outboxId: " + outboxId);
        SyncOutbox.deleteById(outboxId);
        LOGGER.info("Exiting method markSuccess");
    }

    /**
     * Records a failed push attempt on an outbox row.
     *
     * @param outboxId the outbox row id
     * @param error a short error description
     */
    @Transactional
    public void markFailure(Long outboxId, String error) {
        LOGGER.info("Entering method markFailure with outboxId: " + outboxId + ", error: " + error);
        SyncOutbox row = SyncOutbox.findById(outboxId);
        if (row == null) { LOGGER.info("Exiting method markFailure"); return; }
        row.attempts++;
        row.lastError = error != null && error.length() > 255 ? error.substring(0, 255) : error;
        row.persist();
        LOGGER.info("Exiting method markFailure");
    }

    /**
     * Deletes an outbox row whose entity vanished (nothing left to push).
     *
     * @param outboxId the outbox row id
     */
    @Transactional
    public void markGone(Long outboxId) {
        LOGGER.info("Entering method markGone with outboxId: " + outboxId);
        SyncOutbox.deleteById(outboxId);
        LOGGER.info("Exiting method markGone");
    }

    /**
     * The push backlog of one entity kind: how many rows still await
     * acknowledgement, the worst {@code attempts} count among them (a poison
     * item keeps climbing) and the most recent recorded error. This is the
     * read-only projection the sync supervision screen renders per code
     * (BO-08-04-03); an empty table means "fully synchronized".
     *
     * @param type the entity kind
     * @param count the number of rows awaiting push
     * @param maxAttempts the highest failed-attempt count among those rows
     * @param lastError the last recorded push error, or null when none failed
     */
    public record BacklogRow(String type, long count, int maxAttempts, String lastError) {
    }

    /**
     * Returns the push backlog grouped by entity kind, in drain order, keeping
     * only the kinds that have at least one row awaiting push (empty kinds are
     * skipped). For each kept kind it reports the row count, the worst attempt
     * count and the latest non-null error — enough for a supervisor to see a
     * stuck item without a query (BO-08-04-03). An empty list means the node is
     * fully synchronized.
     *
     * @return the per-kind backlog rows, in drain order, never null
     */
    @Transactional
    public List<BacklogRow> backlog() {
        LOGGER.info("Entering method backlog");
        List<BacklogRow> rows = new java.util.ArrayList<>();
        for (SyncOutbox.EntityType type : SyncOutbox.EntityType.values()) {
            List<SyncOutbox> items = SyncOutbox.list("entityType", type);
            if (items.isEmpty()) {
                continue;
            }
            int maxAttempts = 0;
            String lastError = null;
            for (SyncOutbox item : items) {
                if (item.attempts > maxAttempts) {
                    maxAttempts = item.attempts;
                }
                if (item.lastError != null) {
                    lastError = item.lastError;
                }
            }
            rows.add(new BacklogRow(type.name(), items.size(), maxAttempts, lastError));
        }
        LOGGER.info("Exiting method backlog");
        return rows;
    }

    // --------------------------------------------------
    // Entity to DTO mapping (natural keys only)
    // --------------------------------------------------

    /**
     * Maps a cash session to its payload.
     *
     * @param session the session entity
     * @return the payload
     */
    private SyncPayloads.SessionDto toDto(CashSession session) {
        SyncPayloads.SessionDto dto = new SyncPayloads.SessionDto();
        dto.sessionNumber = session.sessionNumber;
        dto.terminalId = session.terminalId;
        dto.status = session.status.name();
        dto.openingDate = iso(session.openingDate);
        dto.closingDate = iso(session.closingDate);
        dto.openingCashierLogin = session.openingCashier != null ? session.openingCashier.loginName : null;
        dto.closingCashierLogin = session.closingCashier != null ? session.closingCashier.loginName : null;
        dto.openingFloat = session.openingFloat;
        dto.countedAmount = session.countedAmount;
        dto.theoreticalAmount = session.theoreticalAmount;
        dto.variance = session.variance;
        dto.withdrawnAmount = session.withdrawnAmount;
        dto.countDetail = session.countDetail;
        return dto;
    }

    /**
     * Maps a cash movement to its payload; session and cashier are referenced
     * by natural key.
     *
     * @param movement the movement entity
     * @return the payload
     */
    private SyncPayloads.MovementDto toDto(CashMovement movement) {
        SyncPayloads.MovementDto dto = new SyncPayloads.MovementDto();
        dto.movementUid = movement.movementUid;
        dto.terminalId = movement.terminalId;
        dto.sessionNumber = movement.session != null ? movement.session.sessionNumber : null;
        dto.cashierLogin = movement.cashier != null ? movement.cashier.loginName : null;
        dto.type = movement.type.name();
        dto.amount = movement.amount;
        dto.reason = movement.reason;
        dto.movementDate = iso(movement.movementDate);
        dto.endorsedBy = movement.endorsedBy;
        return dto;
    }

    /**
     * Maps a ticket and its graph to its payload.
     *
     * @param ticket the ticket entity
     * @return the payload
     */
    private SyncPayloads.TicketDto toDto(Ticket ticket) {
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.formattedContent = ticket.formattedContent;
        dto.ticketNumber = ticket.ticketNumber;
        dto.terminalId = ticket.terminalId;
        dto.status = ticket.status.name();
        dto.creationDate = iso(ticket.creationDate);
        dto.closingDate = iso(ticket.closingDate);
        dto.storeCode = ticket.store != null ? ticket.store.code : null;
        dto.cashierLogin = ticket.cashier != null ? ticket.cashier.loginName : null;
        dto.sessionNumber = ticket.session != null ? ticket.session.sessionNumber : null;
        dto.fidelityCard = ticket.fidelityCard;
        // BO-03-03-25/-29/-31: what the programme said about this sale travels
        // with it. The node prints duplicata of sales made on other registers,
        // and a duplicata that dropped the loyalty zone would not be the same
        // document as the original.
        dto.fidelityEarnTotal = ticket.fidelityEarnTotal;
        dto.fidelityAvailableBalance = ticket.fidelityAvailableBalance;
        dto.fidelityUnavailable = ticket.fidelityUnavailable;
        for (com.intermarche.pos.domain.sale.TicketFidelityLine earnLine : ticket.fidelityLines) {
            SyncPayloads.FidelityLineDto earnDto = new SyncPayloads.FidelityLineDto();
            earnDto.ruleCode = earnLine.ruleCode;
            earnDto.label = earnLine.label;
            earnDto.amount = earnLine.amount;
            dto.fidelityLines.add(earnDto);
        }
        dto.digitalKey = ticket.digitalKey;
        dto.customerEmail = ticket.customerEmail;
        dto.itemCount = ticket.itemCount;
        dto.totalExcludingTax = ticket.totalExcludingTax;
        dto.totalIncludingTax = ticket.totalIncludingTax;
        dto.totalVat = ticket.totalVat;
        dto.signature = ticket.signature;
        dto.previousSignature = ticket.previousSignature;
        dto.grandTotal = ticket.grandTotal;
        dto.valuationStatus = ticket.valuationStatus.name();
        for (TicketLine line : ticket.lines) {
            SyncPayloads.LineDto lineDto = new SyncPayloads.LineDto();
            lineDto.lineNumber = line.lineNumber;
            lineDto.lineUid = line.lineUid;
            lineDto.ean = line.ean;
            lineDto.plu = line.plu;
            lineDto.productLabel = line.productLabel;
            lineDto.quantity = line.quantity;
            lineDto.unitPrice = line.unitPrice;
            lineDto.vatRate = line.vatRate;
            lineDto.modifierLabel = line.modifierLabel;
            // The wire keeps the WORD: a node of another version must be able to read
            // a gesture it does not know without the payload failing to parse.
            lineDto.modifierType = line.modifierType == null ? null : line.modifierType.name();
            lineDto.modifierValue = line.modifierValue;
            lineDto.originalUnitPrice = line.originalUnitPrice;
            lineDto.totalPrice = line.totalPrice;
            lineDto.deposit = line.deposit;
            lineDto.familyCode = line.familyCode;
            lineDto.familyLabel = line.familyLabel;
            // Article-cancellation witness (lot C4, BO-04-01-16): a cancelled
            // line IS carried to the consolidated node, marked, so the journal
            // can search for tickets bearing an annulation article.
            lineDto.cancelled = line.cancelled;
            lineDto.cancellationDate = iso(line.cancellationDate);
            lineDto.cancelledBy = line.cancelledBy;
            dto.lines.add(lineDto);
        }
        for (TicketPayment payment : ticket.payments) {
            SyncPayloads.PaymentDto paymentDto = new SyncPayloads.PaymentDto();
            paymentDto.paymentIndex = payment.paymentIndex;
            paymentDto.methodKey = payment.getMethodKey();
            paymentDto.amount = payment.amount;
            if (payment instanceof CashPayment cash) {
                paymentDto.tenderedAmount = cash.tenderedAmount;
            }
            if (payment instanceof VoucherPayment voucher) {
                paymentDto.voucherLabel = voucher.voucherLabel;
                paymentDto.voucherNumber = voucher.voucherNumber;
            }
            if (payment instanceof com.intermarche.pos.domain.payment.CardPayment card) {
                paymentDto.authorizationNumber = card.authorizationNumber;
                paymentDto.degradedMode = card.degradedMode;
            }
            if (payment instanceof com.intermarche.pos.domain.payment.ChequePayment cheque) {
                paymentDto.magneticLine = cheque.magneticLine;
            }
            if (payment instanceof com.intermarche.pos.domain.payment.BackupPayment secours) {
                paymentDto.backupMethodLabel = secours.methodLabel;
                paymentDto.backupTransaction = secours.transactionNumber;
                paymentDto.backupManual = secours.manual;
            }
            if (payment instanceof com.intermarche.pos.domain.payment.ForeignCurrencyPayment devise) {
                paymentDto.currencyCode = devise.currencyCode;
                paymentDto.currencyAmount = devise.foreignAmount;
                paymentDto.currencyRate = devise.exchangeRate;
            }
            if (payment instanceof com.intermarche.pos.domain.payment.CreditPayment credit) {
                paymentDto.creditAccountNumber = credit.accountNumber;
                paymentDto.creditAccountName = credit.accountName;
                paymentDto.creditOverLimit = credit.overLimit;
            }
            dto.payments.add(paymentDto);
        }
        return dto;
    }

    /**
     * Maps a refund and its lines to its payload; original lines are
     * referenced by their stable uid.
     *
     * @param refund the refund entity
     * @return the payload
     */
    private SyncPayloads.RefundDto toDto(Refund refund) {
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        dto.refundNumber = refund.refundNumber;
        dto.terminalId = refund.terminalId;
        dto.status = refund.status.name();
        dto.refundMethod = refund.refundMethod != null ? refund.refundMethod.name() : null;
        Ticket original = Ticket.findById(refund.originalTicketId);
        dto.originalTicketNumber = original != null ? original.ticketNumber : null;
        dto.sessionNumber = refund.session != null ? refund.session.sessionNumber : null;
        dto.creationDate = iso(refund.creationDate);
        dto.totalAmount = refund.totalAmount;
        dto.totalExcludingTax = refund.totalExcludingTax;
        dto.totalVat = refund.totalVat;
        for (RefundLine line : refund.lines) {
            SyncPayloads.RefundLineDto lineDto = new SyncPayloads.RefundLineDto();
            TicketLine originalLine = TicketLine.findById(line.originalLineId);
            lineDto.originalLineUid = originalLine != null ? originalLine.lineUid : null;
            lineDto.productLabel = line.productLabel;
            lineDto.quantity = line.quantity;
            lineDto.price = line.price;
            lineDto.vatRate = line.vatRate;
            dto.lines.add(lineDto);
        }
        return dto;
    }

    /**
     * Builds the payload of an account customer created at the register
     * (LC-08-04-09).
     *
     * @param customer the customer to declare
     * @return the payload
     */
    private SyncPayloads.CustomerDto toDto(com.intermarche.pos.domain.payment.AccountCustomer customer) {
        SyncPayloads.CustomerDto dto = new SyncPayloads.CustomerDto();
        dto.accountNumber = customer.accountNumber;
        dto.companyName = customer.companyName;
        dto.lastName = customer.lastName;
        dto.firstName = customer.firstName;
        if (customer.address != null) {
            dto.street = customer.address.streetLine1;
            dto.postalCode = customer.address.postalCode;
            dto.city = customer.address.city;
        }
        dto.siret = customer.siret;
        dto.vatNumber = customer.vatNumber;
        dto.phone = customer.phone;
        dto.email = customer.email;
        return dto;
    }

    /**
     * Builds the payload of a document issued at the register
     * ({@code BO-02-04-19}).
     *
     * <p>The customer block travels AS PRINTED, read off the document and not
     * off the customer's file: the document is the archive, the file is the
     * present state, and the two part company the day the customer moves.
     *
     * @param document the issued document
     * @return the payload
     */
    private SyncPayloads.DocumentDto toDto(com.intermarche.pos.domain.sale.Invoice document) {
        SyncPayloads.DocumentDto dto = new SyncPayloads.DocumentDto();
        dto.documentNumber = document.documentNumber;
        dto.documentType = document.documentType == null ? null : document.documentType.name();
        dto.terminalId = document.terminalId;
        dto.issueDate = document.issueDate == null ? null : document.issueDate.toString();
        dto.ticketNumber = document.ticketNumber;
        dto.customerAccountNumber = document.customerAccountNumber;
        dto.customerName = document.customerName;
        dto.customerContact = document.customerContact;
        dto.customerSiret = document.customerSiret;
        dto.customerVatNumber = document.customerVatNumber;
        dto.customerTaxId = document.customerTaxId;
        // BO-10-04-19 : la date d'échéance ne remonte dans l'extraction que si le
        // magasin l'a demandée — elle n'est en place qu'en France aujourd'hui.
        dto.customerDueDate = document.customerDueDate == null
                || !posSettingsService.invoiceExportDueDate()
                ? null : document.customerDueDate.toString();
        if (document.customerAddress != null) {
            dto.customerStreet = document.customerAddress.streetLine1;
            dto.customerPostalCode = document.customerAddress.postalCode;
            dto.customerCity = document.customerAddress.city;
        }
        dto.totalExcludingTax = document.totalExcludingTax;
        dto.totalIncludingTax = document.totalIncludingTax;
        dto.totalVat = document.totalVat;
        // BO-10-04-14 : l'éco-taxe ne remonte que si le magasin l'a demandée.
        dto.totalEcoTax = posSettingsService.invoiceExportEcoTax()
                ? document.totalEcoTax : null;
        dto.printCount = document.printCount;
        return dto;
    }

    /**
     * Maps a technical journal event to its payload.
     *
     * @param event the event entity
     * @return the payload
     */
    private SyncPayloads.EventDto toDto(TechnicalEvent event) {
        SyncPayloads.EventDto dto = new SyncPayloads.EventDto();
        dto.eventUid = event.eventUid;
        dto.terminalId = event.terminalId;
        dto.type = event.eventType.name();
        dto.detail = event.detail;
        dto.operatorBadgeId = event.operatorBadgeId;
        dto.eventDate = iso(event.eventDate);
        return dto;
    }

    /**
     * Formats a timestamp as ISO-8601, tolerating null.
     *
     * @param dateTime the timestamp, or null
     * @return the ISO string, or null
     */
    private String iso(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
    }
}
