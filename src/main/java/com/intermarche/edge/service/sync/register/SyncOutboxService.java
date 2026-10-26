package com.intermarche.edge.service.sync.register;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.payment.CashPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.payment.VoucherPayment;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.sync.SyncOutbox;
import com.intermarche.edge.service.sync.SyncEndpoints;
import com.intermarche.edge.service.sync.SyncPayloads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
    com.intermarche.edge.service.PosSettingsService posSettingsService;

    /** This register's identifier, the reporter of the instruments it pushes. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

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
     * <p>An entity queued twice in the batch travels ONCE: a payload is the
     * entity as it stands when it is prepared, after this read, so its last
     * row carries every change the earlier ones were queued for, and the
     * earlier rows are dropped here (BO-05-01-05, BO-11-04-02: a sale queued at
     * its closing and again once its fiscal documents are sealed reaches the
     * store node once). Only rows this read saw are dropped, all committed with
     * their changes, so nothing queued later is lost.
     *
     * @param batchSize the maximum number of rows
     * @return the ids of the rows to process
     */
    @Transactional
    public List<Long> nextBatchIds(int batchSize) {
        LOGGER.info("Entering method nextBatchIds with batchSize: " + batchSize);
        List<SyncOutbox> rows = SyncOutbox.<SyncOutbox>find("order by entityType, id")
                .page(0, batchSize)
                .list();
        java.util.Map<String, SyncOutbox> latest = new java.util.HashMap<>();
        for (SyncOutbox row : rows) {
            SyncOutbox superseded = latest.put(row.entityType + ":" + row.entityId, row);
            if (superseded != null) {
                superseded.delete();
            }
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (SyncOutbox row : rows) {
            if (latest.get(row.entityType + ":" + row.entityId) == row) {
                ids.add(row.id);
            }
        }
        LOGGER.info("Exiting method nextBatchIds");
        return ids;
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
                    com.intermarche.edge.domain.payment.AccountCustomer customer =
                            com.intermarche.edge.domain.payment.AccountCustomer.findById(row.entityId);
                    yield customer == null ? null
                            : new PreparedItem("customer", objectMapper.writeValueAsString(toDto(customer)));
                }
                case PRINTOUT -> {
                    com.intermarche.edge.domain.sale.PrintedTicket printout =
                            com.intermarche.edge.domain.sale.PrintedTicket.findById(row.entityId);
                    yield printout == null ? null
                            : new PreparedItem("printout", objectMapper.writeValueAsString(toDto(printout)));
                }
                case TRAINING -> {
                    com.intermarche.edge.domain.sale.TrainingTicket training =
                            com.intermarche.edge.domain.sale.TrainingTicket.findById(row.entityId);
                    yield training == null ? null
                            : new PreparedItem("training", objectMapper.writeValueAsString(toDto(training)));
                }
                case PARKING -> {
                    com.intermarche.edge.domain.sale.ParkedTicketRecord parking =
                            com.intermarche.edge.domain.sale.ParkedTicketRecord.findById(row.entityId);
                    yield parking == null ? null
                            : new PreparedItem("parking", objectMapper.writeValueAsString(toDto(parking)));
                }
                // BO-03-06-62/63: the instruments and the barcode readings are a
                // register's to push; a store node receives them and never queues
                // them, so these two arms only ever run on a register.
                case STORED_VALUE -> {
                    com.intermarche.edge.domain.payment.StoredValue instrument =
                            com.intermarche.edge.domain.payment.StoredValue.findById(row.entityId);
                    yield instrument == null ? null
                            : new PreparedItem("stored-value", objectMapper.writeValueAsString(toDto(instrument)));
                }
                case SCANNED_BARCODE -> {
                    com.intermarche.edge.domain.barcode.ScannedBarcode reading =
                            com.intermarche.edge.domain.barcode.ScannedBarcode.findById(row.entityId);
                    yield reading == null ? null
                            : new PreparedItem("scanned-barcode", objectMapper.writeValueAsString(toDto(reading)));
                }
                case DOCUMENT -> {
                    com.intermarche.edge.domain.sale.Invoice document =
                            com.intermarche.edge.domain.sale.Invoice.findById(row.entityId);
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
        // EXP-82/83/95: the opposable-close facts ride the wire so the store node's
        // consolidated Z is faithful — who arbitrated the écart, the paper-tender
        // declaration, and whether it was a floating no-count evening.
        dto.varianceEndorsedBy = session.varianceEndorsedBy;
        dto.paperDeclaration = session.paperDeclaration;
        dto.floatingNoCount = session.floatingNoCount;
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
        // EXP-87/85: carry the safe-deposit flag and the acompte's order reference
        // over the wire so the store node can account the coffre and impute the
        // down-payment to its order.
        dto.safeDeposit = movement.safeDeposit;
        dto.orderReference = movement.orderReference;
        // BO-05-02-09: the tender, the transfer's destination and the counted
        // denominations, so the back office validates each movement by tender.
        dto.paymentMethod = movement.paymentMethod;
        dto.transferTo = movement.transferTo;
        dto.denominationDetail = movement.denominationDetail;
        return dto;
    }

    /**
     * Maps the valuation trace of a ticket (BO-06-08-02): which offer valued
     * each line and what it took off.
     *
     * @param ticket the ticket entity
     * @return the traces, empty for a ticket never valued
     */
    private List<SyncPayloads.ValuationDto> valuationDtos(Ticket ticket) {
        List<SyncPayloads.ValuationDto> dtos = new java.util.ArrayList<>();
        if (ticket.id == null) {
            return dtos;
        }
        for (com.intermarche.edge.domain.sale.TicketLineValuation trace
                : com.intermarche.edge.domain.sale.TicketLineValuation
                        .<com.intermarche.edge.domain.sale.TicketLineValuation>list("ticket.id", ticket.id)) {
            SyncPayloads.ValuationDto dto = new SyncPayloads.ValuationDto();
            dto.lineUid = trace.lineUid;
            dto.localTotal = trace.localTotal;
            dto.valuedTotal = trace.valuedTotal;
            dto.offerLabel = trace.offerLabel;
            dto.advantageLabel = trace.advantageLabel;
            dto.advantageAmount = trace.advantageAmount;
            dto.advantagePrintMode = trace.advantagePrintMode;
            dto.correctionLabel = trace.correctionLabel;
            dto.correctionAmount = trace.correctionAmount;
            dtos.add(dto);
        }
        return dtos;
    }

    /**
     * Maps a ticket and its graph to its payload.
     *
     * @param ticket the ticket entity
     * @return the payload
     */
    private SyncPayloads.TicketDto toDto(Ticket ticket) {
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.fiscalDocuments = fiscalDtos(com.intermarche.edge.domain.sale.FiscalDocument
                .<com.intermarche.edge.domain.sale.FiscalDocument>list("ticketId", ticket.id));
        dto.formattedContent = ticket.formattedContent;
        dto.customerLanguage = ticket.customerLanguage;
        dto.ticketNumber = ticket.ticketNumber;
        dto.terminalId = ticket.terminalId;
        dto.status = ticket.status.name();
        dto.creationDate = iso(ticket.creationDate);
        dto.parkedAt = iso(ticket.parkedAt);
        dto.resumedAt = iso(ticket.resumedAt);
        dto.resumedTerminalId = ticket.resumedTerminalId;
        dto.paymentStartedAt = iso(ticket.paymentStartedAt);
        dto.valuations = valuationDtos(ticket);
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
        dto.fidelityProjectedAfterEarn = ticket.fidelityProjectedAfterEarn;
        dto.fidelityBalanceAfter = ticket.fidelityBalanceAfter;
        dto.fidelityAnonymousProjection = ticket.fidelityAnonymousProjection;
        dto.fidelityUnavailable = ticket.fidelityUnavailable;
        dto.fidelityPotBalances = ticket.fidelityPotBalances;
        dto.fidelityMessages = ticket.fidelityMessages;
        for (com.intermarche.edge.domain.sale.TicketFidelityLine earnLine : ticket.fidelityLines) {
            SyncPayloads.FidelityLineDto earnDto = new SyncPayloads.FidelityLineDto();
            earnDto.ruleCode = earnLine.ruleCode;
            earnDto.label = earnLine.label;
            earnDto.amount = earnLine.amount;
            earnDto.advantageType = earnLine.advantageType;
            earnDto.advantageTypeLabel = earnLine.advantageTypeLabel;
            earnDto.advantageTypeOrder = earnLine.advantageTypeOrder;
            earnDto.advantageCategory = earnLine.advantageCategory;
            earnDto.advantageCategoryLabel = earnLine.advantageCategoryLabel;
            dto.fidelityLines.add(earnDto);
        }
        // GB-01-07-05/06: the offer messages the sale showed, frozen at closing.
        // The node prints duplicata of sales made on other registers, and a
        // duplicata that dropped them would not be the same document either.
        for (com.intermarche.edge.domain.sale.TicketMessage message : ticket.messages) {
            SyncPayloads.MessageDto messageDto = new SyncPayloads.MessageDto();
            messageDto.code = message.code;
            messageDto.text = message.text;
            messageDto.printMode = message.printMode;
            dto.messages.add(messageDto);
        }
        dto.digitalKey = ticket.digitalKey;
        dto.customerEmail = ticket.customerEmail;
        dto.customerTaxId = ticket.customerTaxId;
        dto.itemCount = ticket.itemCount;
        dto.selfScanning = ticket.selfScanning;
        dto.selfScanControlled = ticket.selfScanControlled;
        dto.selfScanVerified = ticket.selfScanVerified;
        dto.offline = ticket.offline;
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
            lineDto.linkedParentUid = line.linkedParentUid;
            lineDto.linkedArticleEan = line.linkedArticleEan;
            lineDto.linkedRecordedQuantity = line.linkedRecordedQuantity;
            lineDto.ean = line.ean;
            lineDto.plu = line.plu;
            lineDto.productLabel = line.productLabel;
            lineDto.quantity = line.quantity;
            lineDto.unitPrice = line.unitPrice;
            lineDto.vatRate = line.vatRate;
            lineDto.vatNumber = line.vatNumber;
            lineDto.vatExempt = line.vatExempt;
            lineDto.thirdPartySale = line.thirdPartySale;
            lineDto.service = line.service;
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
            lineDto.cancellationReasonCode = line.cancellationReasonCode;
            lineDto.cancellationReasonLabel = line.cancellationReasonLabel;
            // BO-03-13-12: the return type a return line was rung under.
            lineDto.returnTypeCode = line.returnTypeCode;
            lineDto.returnTypeLabel = line.returnTypeLabel;
            // BO-03-13-11/-12: the node tells a return line from a deposit voucher
            // and files it under its return type only if it knows it is one.
            lineDto.returnLine = line.returnLine;
            // Money-instrument flag (R12) and last-line-cancellation flag (R10):
            // both are needed by the store node's financial states — the first to
            // keep gift cards out of the turnover, the second to tell a corrected
            // mis-scan from the endorsed cancellation that is the fraud indicator.
            lineDto.moneyProduct = line.moneyProduct;
            lineDto.cancelledLast = line.cancelledLast;
            lineDto.scanned = line.scanned;
            lineDto.priceEntered = line.priceEntered;
            lineDto.carriedOut = line.carriedOut;
            lineDto.appliedTareKg = line.appliedTareKg;
            dto.lines.add(lineDto);
        }
        for (TicketPayment payment : ticket.payments) {
            SyncPayloads.PaymentDto paymentDto = new SyncPayloads.PaymentDto();
            paymentDto.paymentIndex = payment.paymentIndex;
            paymentDto.methodKey = payment.getMethodKey();
            paymentDto.moneticServer = payment.moneticServer;
            paymentDto.tenderCode = payment.tenderCode;
            paymentDto.amount = payment.amount;
            if (payment instanceof CashPayment cash) {
                paymentDto.tenderedAmount = cash.tenderedAmount;
            }
            if (payment instanceof VoucherPayment voucher) {
                paymentDto.voucherLabel = voucher.voucherLabel;
                paymentDto.voucherNumber = voucher.voucherNumber;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.CardPayment card) {
                paymentDto.authorizationNumber = card.authorizationNumber;
                paymentDto.degradedMode = card.degradedMode;
                // EXP-54: the real CB entry mode (SANS_CONTACT / CODE) rides the
                // wire so the store node can surface it in the BO card states.
                paymentDto.cardEntryMode = card.cardEntryMode;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.ChequePayment cheque) {
                paymentDto.magneticLine = cheque.magneticLine;
                paymentDto.chequeAccountNumber = cheque.accountNumber;
                paymentDto.chequeGuaranteeAuthorization = cheque.guaranteeAuthorization;
                paymentDto.chequeGuaranteeProvider = cheque.guaranteeProvider;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.BackupPayment secours) {
                paymentDto.backupMethodLabel = secours.methodLabel;
                paymentDto.backupTransaction = secours.transactionNumber;
                paymentDto.backupManual = secours.manual;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.ForeignCurrencyPayment devise) {
                paymentDto.currencyCode = devise.currencyCode;
                paymentDto.currencyAmount = devise.foreignAmount;
                paymentDto.currencyRate = devise.exchangeRate;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.CreditPayment credit) {
                paymentDto.creditAccountNumber = credit.accountNumber;
                paymentDto.creditAccountName = credit.accountName;
                paymentDto.creditOverLimit = credit.overLimit;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.DepositPayment acompte) {
                // LC-06-03-08: the deposit recovery rides with its per-rate VAT
                // detail and its order code, so the store node accounts it by
                // rate against the order it settles.
                paymentDto.depositVatDetail = acompte.vatDetail;
                paymentDto.depositOrderCode = acompte.orderCode;
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
        dto.fiscalDocuments = fiscalDtos(com.intermarche.edge.domain.sale.FiscalDocument
                .<com.intermarche.edge.domain.sale.FiscalDocument>list("refundId", refund.id));
        dto.refundNumber = refund.refundNumber;
        dto.terminalId = refund.terminalId;
        dto.status = refund.status.name();
        dto.refundMethod = refund.refundMethod != null ? refund.refundMethod.name() : null;
        dto.moneticServer = refund.moneticServer;
        Ticket original = Ticket.findById(refund.originalTicketId);
        dto.originalTicketNumber = original != null ? original.ticketNumber : null;
        dto.sessionNumber = refund.session != null ? refund.session.sessionNumber : null;
        dto.creationDate = iso(refund.creationDate);
        dto.totalAmount = refund.totalAmount;
        dto.totalExcludingTax = refund.totalExcludingTax;
        dto.totalVat = refund.totalVat;
        dto.returnReason = refund.returnReason;
        dto.returnReasonCode = refund.returnReasonCode;
        dto.returnTypeCode = refund.returnTypeCode;
        dto.returnTypeLabel = refund.returnTypeLabel;
        dto.cashierBadgeId = refund.cashierBadgeId;
        dto.supervisorBadgeId = refund.supervisorBadgeId;
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
    private SyncPayloads.CustomerDto toDto(com.intermarche.edge.domain.payment.AccountCustomer customer) {
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
        dto.taxId = customer.taxId;
        dto.origin = customer.origin == null ? null : customer.origin.name();
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
    private SyncPayloads.DocumentDto toDto(com.intermarche.edge.domain.sale.Invoice document) {
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
        dto.orderNumber = document.orderNumber;
        dto.documentCode = document.documentCode;
        dto.documentTitle = document.documentTitle;
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
        dto.ticketNumber = event.ticketNumber;
        dto.amount = event.amount;
        dto.code = event.code;
        return dto;
    }

    /**
     * Maps the Portuguese documents of a sale or a return to their payloads
     * (BO-10-06-03).
     *
     * @param documents the documents, empty when none was issued
     * @return the payloads, in the order given
     */
    private java.util.List<SyncPayloads.FiscalDocumentDto> fiscalDtos(
            java.util.List<com.intermarche.edge.domain.sale.FiscalDocument> documents) {
        java.util.List<SyncPayloads.FiscalDocumentDto> dtos = new java.util.ArrayList<>();
        for (com.intermarche.edge.domain.sale.FiscalDocument document : documents) {
            SyncPayloads.FiscalDocumentDto dto = new SyncPayloads.FiscalDocumentDto();
            dto.documentType = document.documentType.name();
            dto.series = document.series;
            dto.rank = document.rank;
            dto.number = document.number;
            dto.atcud = document.atcud;
            dto.saftDocumentId = document.saftDocumentId;
            dto.terminalId = document.terminalId;
            dto.issuedAt = iso(document.issuedAt);
            dto.part = document.part;
            dto.lineUids = document.lineUids;
            dto.sourceNumber = document.sourceNumber;
            dto.customerTaxId = document.customerTaxId;
            dto.customerName = document.customerName;
            dto.customerAddress = document.customerAddress;
            dto.grossTotal = document.grossTotal;
            dto.taxTotal = document.taxTotal;
            dto.hash = document.hash;
            dto.previousHash = document.previousHash;
            dto.qrPayload = document.qrPayload;
            dto.cancelled = document.cancelled;
            dtos.add(dto);
        }
        return dtos;
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

    /**
     * Maps a printed ticket to its push payload (LC-08-02-02).
     *
     * @param printout the printed ticket
     * @return the payload, keyed by the register's id
     */
    private SyncPayloads.PrintoutDto toDto(com.intermarche.edge.domain.sale.PrintedTicket printout) {
        SyncPayloads.PrintoutDto dto = new SyncPayloads.PrintoutDto();
        dto.sourceId = printout.id;
        dto.terminalId = printout.terminalId;
        dto.source = printout.source;
        dto.audience = printout.audience == null ? null : printout.audience.name();
        dto.issuedAt = printout.issuedAt == null ? null : printout.issuedAt.toString();
        dto.content = printout.content;
        return dto;
    }

    /**
     * Maps a training sale to its push payload (LC-13-05-06).
     *
     * @param training the training sale
     * @return the payload, keyed by the register's id
     */
    private SyncPayloads.TrainingTicketDto toDto(com.intermarche.edge.domain.sale.TrainingTicket training) {
        SyncPayloads.TrainingTicketDto dto = new SyncPayloads.TrainingTicketDto();
        dto.sourceId = training.id;
        dto.terminalId = training.terminalId;
        dto.cashierLogin = training.cashierLogin;
        dto.issuedAt = training.issuedAt == null ? null : training.issuedAt.toString();
        dto.itemCount = training.itemCount;
        dto.totalIncludingTax = training.totalIncludingTax;
        dto.content = training.content;
        dto.parkedAt = training.parkedAt == null ? null : training.parkedAt.toString();
        dto.parkedBy = training.parkedBy;
        dto.resumedAt = training.resumedAt == null ? null : training.resumedAt.toString();
        dto.resumedTerminalId = training.resumedTerminalId;
        dto.resumedBy = training.resumedBy;
        return dto;
    }

    /**
     * Maps the signed trace of a ticket on hold to its push payload (LC-04-01-03).
     *
     * @param parking the record
     * @return the payload, keyed by the register's id
     */
    private SyncPayloads.ParkedTicketDto toDto(com.intermarche.edge.domain.sale.ParkedTicketRecord parking) {
        SyncPayloads.ParkedTicketDto dto = new SyncPayloads.ParkedTicketDto();
        dto.sourceId = parking.id;
        dto.terminalId = parking.terminalId;
        dto.ticketNumber = parking.ticketNumber;
        dto.event = parking.event == null ? null : parking.event.name();
        dto.eventDate = parking.eventDate == null ? null : parking.eventDate.toString();
        dto.operator = parking.operator;
        dto.itemCount = parking.itemCount;
        dto.totalAmount = parking.totalAmount;
        dto.content = parking.content;
        dto.previousSignature = parking.previousSignature;
        dto.signature = parking.signature;
        return dto;
    }

    /**
     * Maps an instrument this register knows to its push payload
     * (BO-03-06-62, -48): under its issuer's key, whoever reports it.
     *
     * @param instrument the instrument
     * @return the payload
     */
    private SyncPayloads.StoredValueDto toDto(com.intermarche.edge.domain.payment.StoredValue instrument) {
        SyncPayloads.StoredValueDto dto = new SyncPayloads.StoredValueDto();
        dto.terminalId = terminalId;
        dto.issuingTerminalId = instrument.issuingTerminalId == null ? terminalId : instrument.issuingTerminalId;
        dto.number = instrument.number;
        dto.kind = instrument.kind == null ? null : instrument.kind.name();
        dto.initialAmount = instrument.initialAmount;
        dto.balance = instrument.balance;
        dto.status = instrument.status == null ? null : instrument.status.name();
        dto.issuedAt = instrument.issuedAt == null ? null : instrument.issuedAt.toString();
        dto.validUntil = instrument.validUntil == null ? null : instrument.validUntil.toString();
        dto.exhaustedAt = instrument.exhaustedAt == null ? null : instrument.exhaustedAt.toString();
        dto.issuingDocumentNumber = instrument.issuingDocumentNumber;
        return dto;
    }

    /**
     * Maps a payment barcode read at this till to its push payload (BO-03-06-63).
     *
     * @param reading the reading
     * @return the payload, keyed by the register's id
     */
    private SyncPayloads.ScannedBarcodeDto toDto(com.intermarche.edge.domain.barcode.ScannedBarcode reading) {
        SyncPayloads.ScannedBarcodeDto dto = new SyncPayloads.ScannedBarcodeDto();
        dto.sourceId = reading.id;
        dto.terminalId = reading.terminalId;
        dto.code = reading.code;
        dto.typeCode = reading.typeCode;
        dto.typeLabel = reading.typeLabel;
        dto.ticketNumber = reading.ticketNumber;
        dto.amount = reading.amount;
        dto.validUntil = reading.validUntil == null ? null : reading.validUntil.toString();
        dto.scannedAt = reading.scannedAt == null ? null : reading.scannedAt.toString();
        dto.found = reading.found;
        dto.gs1 = reading.gs1;
        return dto;
    }
}
