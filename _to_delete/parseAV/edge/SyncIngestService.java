package com.intermarche.edge.service.sync.store;

import com.intermarche.edge.domain.barcode.BalanceTicket;
import com.intermarche.edge.domain.barcode.BalanceTicketLine;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.payment.VoucherPayment;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import com.intermarche.edge.service.sync.SyncPayloads;

/**
 * Store-node half of the synchronization: idempotent upserts of the payloads
 * pushed by the registers, resolving every natural key against the store
 * database (cashiers by login, store by code, products by EAN/PLU, sessions
 * and tickets by number, refunded lines by uid). An unresolvable mandatory
 * reference raises an {@link IllegalStateException}, turned into a retryable
 * failure by the ingestion endpoint — the missing entity usually arrives on
 * a later cycle (drain order pushes sessions before tickets before refunds).
 * <p>
 * Idempotency is achieved by WHOLESALE GRAPH REPLACEMENT: on a ticket or
 * refund upsert the child collections are cleared and rebuilt from the
 * payload (orphan removal deletes the old rows), so re-pushing the same
 * document any number of times converges to the same state — there is no
 * per-line diffing to get wrong. Product references on ingested lines are
 * best-effort decoration (resolved by PLU first, then EAN, possibly null):
 * the line's snapshot fields are authoritative, the store-side product link
 * only serves reporting joins like the top-sales query.
 */
@ApplicationScoped
public class SyncIngestService {

    private static final Logger LOGGER = Logger.getLogger(SyncIngestService.class);

    @Inject
    Instance<TicketPayment.Factory> factoryInstances;

    /**
     * The node's ticket renderer, used to answer a foreign-duplicata request
     * (LC-08-05-05) with exactly the text the selling register would have
     * printed.
     */
    @Inject
    com.intermarche.edge.service.TicketRenderService ticketRenderService;

    /** What the coin machines of the line hold ({@code BO-05-02-16}). */
    @Inject
    com.intermarche.edge.service.CoinMachineService coinMachineService;

    /** The store's safe, fed by the till's withdrawals toward it (EXP-87). */
    @Inject
    com.intermarche.edge.service.SafeService safeService;

    /**
     * The at-the-flow stream toward the subscribed third parties
     * ({@code BO-11-02-02/03/04}).
     */
    @Inject
    com.intermarche.edge.service.sync.TicketStreamService ticketStreamService;

    /** Payment factories indexed by their method key. */
    private final Map<String, TicketPayment.Factory> paymentFactories = new HashMap<>();

    /**
     * Indexes the discovered payment factories by their method key.
     */
    @PostConstruct
    public void init() {
        LOGGER.info("Entering method init");
        for (TicketPayment.Factory factory : factoryInstances) {
            paymentFactories.put(factory.getKey(), factory);
        }
        LOGGER.info("Exiting method init");
    }

    /**
     * Upserts a cash session by its number.
     *
     * @param dto the pushed session payload
     */
    @Transactional
    public void ingestSession(SyncPayloads.SessionDto dto) {
        LOGGER.info("Entering method ingestSession with dto: " + dto);
        CashSession session = CashSession.find("sessionNumber", dto.sessionNumber).firstResult();
        boolean created = false;
        if (session == null) {
            session = new CashSession();
            session.sessionNumber = dto.sessionNumber;
            created = true;
        }
        session.terminalId = dto.terminalId;
        session.status = CashSession.SessionStatus.valueOf(dto.status);
        session.openingDate = parse(dto.openingDate);
        session.closingDate = parse(dto.closingDate);
        session.openingCashier = requireEmployee(dto.openingCashierLogin);
        session.closingCashier = dto.closingCashierLogin != null ? requireEmployee(dto.closingCashierLogin) : null;
        session.openingFloat = dto.openingFloat;
        session.countedAmount = dto.countedAmount;
        session.theoreticalAmount = dto.theoreticalAmount;
        session.variance = dto.variance;
        session.withdrawnAmount = dto.withdrawnAmount;
        session.countDetail = dto.countDetail;
        session.persist();
        LOGGER.infof("Session %s %s (%s)", dto.sessionNumber, created ? "créée" : "mise à jour", dto.status);
        LOGGER.info("Exiting method ingestSession");
    }

    /**
     * Upserts a ticket and its graph by its number.
     *
     * @param dto the pushed ticket payload
     */
    @Transactional
    public void ingestTicket(SyncPayloads.TicketDto dto) {
        LOGGER.info("Entering method ingestTicket with dto: " + dto);
        Ticket ticket = Ticket.find("ticketNumber", dto.ticketNumber).firstResult();
        boolean created = false;
        if (ticket == null) {
            ticket = new Ticket();
            ticket.ticketNumber = dto.ticketNumber;
            created = true;
        }
        // BO-05-02-16 : ce que ce ticket avait déjà porté au monnayeur, lu AVANT
        // que ses règlements ne soient réécrits — l'ingestion est un upsert, et
        // un ticket repoussé ne doit pas créditer le monnayeur deux fois.
        java.math.BigDecimal previousCash = created
                ? java.math.BigDecimal.ZERO : coinMachineService.cashOf(ticket);
        ticket.terminalId = dto.terminalId;
        ticket.status = Ticket.TicketStatus.valueOf(dto.status);
        ticket.creationDate = parse(dto.creationDate);
        // BO-06-06-01: the parking and its recall, stored verbatim. A resumed
        // cart is an ordinary sale everywhere else; these three columns are
        // the only trace that it ever waited.
        ticket.parkedAt = parse(dto.parkedAt);
        ticket.resumedAt = parse(dto.resumedAt);
        ticket.resumedTerminalId = dto.resumedTerminalId;
        ticket.closingDate = parse(dto.closingDate);
        ticket.store = requireStore(dto.storeCode);
        ticket.cashier = requireEmployee(dto.cashierLogin);
        ticket.session = dto.sessionNumber != null
                ? CashSession.<CashSession>find("sessionNumber", dto.sessionNumber).firstResult()
                : null;
        ticket.fidelityCard = dto.fidelityCard;
        // BO-03-03-25/-29/-31: the loyalty zone as the selling register showed
        // it. Rewritten wholesale on every push, like the lines: the register
        // that made the sale is the only authority on what its paper said.
        ticket.fidelityEarnTotal = dto.fidelityEarnTotal;
        ticket.fidelityAvailableBalance = dto.fidelityAvailableBalance;
        ticket.fidelityUnavailable = dto.fidelityUnavailable;
        // BO-03-03-28/31: the projected/after balances and the anonymous-projection
        // flag frozen by the selling register. A register not yet updated pushes a
        // payload without them: the DTO defaults (null, null, false) then persist,
        // and the ticket reads exactly as before — backward-compatible on the wire.
        ticket.fidelityProjectedAfterEarn = dto.fidelityProjectedAfterEarn;
        ticket.fidelityBalanceAfter = dto.fidelityBalanceAfter;
        ticket.fidelityAnonymousProjection = dto.fidelityAnonymousProjection;
        if (ticket.fidelityLines == null) {
            ticket.fidelityLines = new java.util.ArrayList<>();
        }
        ticket.fidelityLines.clear();
        for (SyncPayloads.FidelityLineDto earnDto : dto.fidelityLines) {
            // BO-03-03-25: the advantage grouping travels with each line so a
            // duplicata regroups identically; a line from an un-updated register
            // carries null grouping and prints flat, as today.
            ticket.fidelityLines.add(new com.intermarche.edge.domain.sale.TicketFidelityLine(
                    earnDto.ruleCode, earnDto.label, earnDto.amount,
                    earnDto.advantageType, earnDto.advantageTypeLabel, earnDto.advantageTypeOrder,
                    earnDto.advantageCategory, earnDto.advantageCategoryLabel));
        }
        ticket.digitalKey = dto.digitalKey;
        ticket.customerEmail = dto.customerEmail;
        ticket.itemCount = dto.itemCount;
        ticket.totalExcludingTax = dto.totalExcludingTax;
        ticket.totalIncludingTax = dto.totalIncludingTax;
        ticket.totalVat = dto.totalVat;
        ticket.signature = dto.signature;
        // LC-08-02-02: the printed form travels with the sale, so the shop can put
        // the ticket at the disposal of third-party services exactly as the customer
        // read it. A push from a register that predates the field leaves it null
        // rather than blanking what a previous push already delivered.
        if (dto.formattedContent != null && !dto.formattedContent.isBlank()) {
            ticket.formattedContent = dto.formattedContent;
        }
        ticket.previousSignature = dto.previousSignature;
        ticket.grandTotal = dto.grandTotal;
        ticket.valuationStatus = Ticket.ValuationStatus.valueOf(dto.valuationStatus);

        ticket.lines.clear();
        for (SyncPayloads.LineDto lineDto : dto.lines) {
            TicketLine line = new TicketLine();
            line.lineNumber = lineDto.lineNumber;
            line.lineUid = lineDto.lineUid;
            line.ean = lineDto.ean;
            line.plu = lineDto.plu;
            if (lineDto.plu != null) {
                line.product = Product.findByPlu(lineDto.plu);
            } else if (lineDto.ean != null) {
                line.product = Product.findByEan(lineDto.ean);
            }
            line.productLabel = lineDto.productLabel;
            line.quantity = lineDto.quantity;
            line.unitPrice = lineDto.unitPrice;
            line.vatRate = lineDto.vatRate;
            line.modifierLabel = lineDto.modifierLabel;
            // A word this version does not know reads as no gesture rather than as a
            // failed ingest: the ticket is still consolidated, minus a modifier nobody
            // here can apply.
            line.modifierType = com.intermarche.edge.ui.PriceModType.of(lineDto.modifierType);
            line.modifierValue = lineDto.modifierValue;
            line.originalUnitPrice = lineDto.originalUnitPrice;
            line.totalPrice = lineDto.totalPrice;
            line.deposit = lineDto.deposit;
            // Nomenclature snapshot is authoritative: stored verbatim from the
            // payload, never re-derived from the store-side product link, so a
            // referential re-parenting on the consolidated node never rewrites
            // history (BO-04-01-11).
            line.familyCode = lineDto.familyCode;
            line.familyLabel = lineDto.familyLabel;
            // Article-cancellation witness is stored verbatim from the payload
            // (lot C4, BO-04-01-16): the consolidated node keeps the cancelled
            // line, marked, so the journal can find the ticket that bore it.
            line.cancelled = lineDto.cancelled;
            line.cancellationDate = parse(lineDto.cancellationDate);
            line.cancelledBy = lineDto.cancelledBy;
            line.cancellationReasonCode = lineDto.cancellationReasonCode;
            line.cancellationReasonLabel = lineDto.cancellationReasonLabel;
            // Money-instrument and last-line-cancellation flags are stored verbatim
            // from the payload: the financial states read them to keep gift cards
            // out of the turnover (R12) and to split last-line from endorsed
            // cancellations (R10). Never re-derived store-side.
            line.moneyProduct = lineDto.moneyProduct;
            line.cancelledLast = lineDto.cancelledLast;
            ticket.addLine(line);
        }

        ticket.payments.clear();
        for (SyncPayloads.PaymentDto paymentDto : dto.payments) {
            String factoryKey = paymentDto.voucherLabel != null ? "VOUCHER" : paymentDto.methodKey;
            TicketPayment.Factory factory = paymentFactories.get(factoryKey);
            if (factory == null) {
                throw new IllegalStateException("Mode de paiement inconnu: " + paymentDto.methodKey);
            }
            TicketPayment payment = factory.create(paymentDto.amount, paymentDto.tenderedAmount);
            if (payment instanceof VoucherPayment voucher) {
                voucher.voucherLabel = paymentDto.voucherLabel;
                voucher.voucherNumber = paymentDto.voucherNumber;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.CardPayment card) {
                card.authorizationNumber = paymentDto.authorizationNumber;
                card.degradedMode = paymentDto.degradedMode;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.ChequePayment cheque) {
                cheque.magneticLine = paymentDto.magneticLine;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.BackupPayment secours) {
                secours.methodLabel = paymentDto.backupMethodLabel;
                secours.transactionNumber = paymentDto.backupTransaction;
                secours.manual = paymentDto.backupManual;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.ForeignCurrencyPayment devise) {
                devise.currencyCode = paymentDto.currencyCode;
                devise.foreignAmount = paymentDto.currencyAmount;
                devise.exchangeRate = paymentDto.currencyRate;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.CreditPayment credit) {
                credit.accountNumber = paymentDto.creditAccountNumber;
                credit.accountName = paymentDto.creditAccountName;
                credit.overLimit = paymentDto.creditOverLimit;
            }
            payment.paymentIndex = paymentDto.paymentIndex;
            ticket.addPayment(payment);
        }

        ticket.persist();
        coinMachineService.collect(ticket, previousCash);
        // BO-11-02-02/03: mise en file de la diffusion au fil de l'eau DANS la
        // transaction d'ingestion. Un ticket consolidé mais non mis en file ne
        // serait jamais diffusé, alors qu'un événement en file dont la livraison
        // échoue est simplement réessayé.
        ticketStreamService.onTicket(ticket);
        LOGGER.infof("Ticket %s %s (%s)", dto.ticketNumber, created ? "créé" : "mis à jour", dto.status);
        LOGGER.info("Exiting method ingestTicket");
    }

    /**
     * Upserts a refund by its number; the refunded ticket and lines must
     * already be present (drain order guarantees it eventually).
     *
     * @param dto the pushed refund payload
     */
    @Transactional
    public void ingestRefund(SyncPayloads.RefundDto dto) {
        LOGGER.info("Entering method ingestRefund with dto: " + dto);
        Ticket original = Ticket.find("ticketNumber", dto.originalTicketNumber).firstResult();
        if (original == null) {
            throw new IllegalStateException("Ticket d'origine absent: " + dto.originalTicketNumber);
        }

        Refund refund = Refund.find("refundNumber", dto.refundNumber).firstResult();
        boolean created = false;
        if (refund == null) {
            refund = new Refund();
            refund.refundNumber = dto.refundNumber;
            created = true;
        }
        refund.terminalId = dto.terminalId;
        refund.status = Refund.RefundStatus.valueOf(dto.status);
        refund.refundMethod = dto.refundMethod != null ? Refund.RefundMethod.valueOf(dto.refundMethod) : null;
        refund.originalTicketId = original.id;
        refund.session = dto.sessionNumber != null
                ? CashSession.<CashSession>find("sessionNumber", dto.sessionNumber).firstResult()
                : null;
        refund.creationDate = parse(dto.creationDate);
        refund.totalAmount = dto.totalAmount;
        refund.totalExcludingTax = dto.totalExcludingTax;
        refund.totalVat = dto.totalVat;
        // BO-06-06-05: who refunded, who endorsed it, under which return type
        // and for which reason. Stored verbatim from the payload: the node
        // states what the register recorded and re-derives nothing.
        refund.returnReason = dto.returnReason;
        refund.returnReasonCode = dto.returnReasonCode;
        refund.returnTypeCode = dto.returnTypeCode;
        refund.returnTypeLabel = dto.returnTypeLabel;
        refund.cashierBadgeId = dto.cashierBadgeId;
        refund.supervisorBadgeId = dto.supervisorBadgeId;

        refund.lines.clear();
        for (SyncPayloads.RefundLineDto lineDto : dto.lines) {
            RefundLine line = new RefundLine();
            TicketLine originalLine = (lineDto.originalLineUid != null)
                    ? original.lines.stream()
                        .filter(l -> lineDto.originalLineUid.equals(l.lineUid))
                        .findFirst().orElse(null)
                    : null;
            if (originalLine == null) {
                throw new IllegalStateException("Ligne d'origine absente: " + lineDto.originalLineUid);
            }
            line.originalLineId = originalLine.id;
            line.productLabel = lineDto.productLabel;
            line.quantity = lineDto.quantity;
            line.price = lineDto.price;
            line.vatRate = lineDto.vatRate;
            refund.lines.add(line);
        }

        refund.persist();
        // BO-11-02-02: le retour est l'un des types de ticket que l'exigence
        // nomme, et il part sur le même mécanisme que la vente.
        ticketStreamService.onRefund(refund);
        LOGGER.infof("Remboursement %s %s", dto.refundNumber, created ? "créé" : "mis à jour");
        LOGGER.info("Exiting method ingestRefund");
    }

    /**
     * Renders the duplicata of a ticket the shop holds, for a register that does not
     * hold it (LC-08-05-05).
     *
     * <p>The store node runs its own renderer, which applies the register's
     * rendering rules to the ingested ticket, so what comes back reads exactly
     * like the paper the customer got at the other till. Nothing is written: the
     * print counter and its journal entry belong to the register that made the
     * sale, not to the node that keeps a copy.
     *
     * @param ticketNumber the number of the ticket asked for
     * @return the rendered duplicata, or null when the shop holds no such ticket
     */
    public String renderTicketDuplicate(String ticketNumber) {
        LOGGER.info("Entering method renderTicketDuplicate with ticketNumber: " + ticketNumber);
        if (ticketNumber == null || ticketNumber.isBlank()) {
            LOGGER.info("Exiting method renderTicketDuplicate");
            return null;
        }
        com.intermarche.edge.domain.sale.Ticket ticket =
                com.intermarche.edge.domain.sale.Ticket
                        .find("ticketNumber", ticketNumber.trim()).firstResult();
        if (ticket == null) {
            LOGGER.info("Exiting method renderTicketDuplicate");
            return null;
        }
        LOGGER.info("Exiting method renderTicketDuplicate");
        return ticketRenderService.renderTicket(ticket, true, Math.max(1, ticket.printCount));
    }

    /**
     * Upserts an account customer created at a register, by its account number
     * (LC-08-04-09).
     *
     * <p>The number is the upsert key and not the register's local id: the same
     * customer pushed twice, or pushed again after an edit, must land on one row
     * here. The payload references nothing, so this ingestion never parks.
     *
     * @param dto the pushed customer payload
     */
    @Transactional
    public void ingestCustomer(SyncPayloads.CustomerDto dto) {
        LOGGER.info("Entering method ingestCustomer with dto: " + dto);
        com.intermarche.edge.domain.payment.AccountCustomer customer =
                com.intermarche.edge.domain.payment.AccountCustomer
                        .find("accountNumber", dto.accountNumber).firstResult();
        boolean created = false;
        if (customer == null) {
            customer = new com.intermarche.edge.domain.payment.AccountCustomer();
            customer.accountNumber = dto.accountNumber;
            created = true;
        }
        customer.companyName = dto.companyName;
        customer.lastName = dto.lastName;
        customer.firstName = dto.firstName;
        if (customer.address == null) {
            customer.address = new com.intermarche.edge.domain.store.Address();
        }
        customer.address.streetLine1 = dto.street;
        customer.address.postalCode = dto.postalCode;
        customer.address.city = dto.city;
        customer.siret = dto.siret;
        customer.vatNumber = dto.vatNumber;
        customer.phone = dto.phone;
        customer.email = dto.email;
        customer.persist();
        LOGGER.infof("Client en compte %s %s", dto.accountNumber, created ? "créé" : "mis à jour");
        LOGGER.info("Exiting method ingestCustomer");
    }

    /**
     * Upserts a document issued at a register, by its document number
     * ({@code BO-02-04-19}).
     *
     * <p>The customer block is stored AS PUSHED and never re-read from the
     * customer's file here: the document is what was printed that day, and the
     * back office extracts it years later, long after the customer may have
     * moved or been renamed.
     *
     * <p>The ticket is resolved by number and the document is attached to it
     * when it is there. It is NOT required to be: the drain pushes tickets
     * before documents, but a register that issued a document for a ticket the
     * node never received — an old sale, a purge upstream — must still be able
     * to declare it. The number is kept whatever happens, which is what the
     * screens read.
     *
     * @param dto the pushed document payload
     */
    @Transactional
    public void ingestDocument(SyncPayloads.DocumentDto dto) {
        LOGGER.info("Entering method ingestDocument with dto: " + dto);
        com.intermarche.edge.domain.sale.Invoice document =
                com.intermarche.edge.domain.sale.Invoice
                        .find("documentNumber", dto.documentNumber).firstResult();
        boolean created = false;
        if (document == null) {
            document = new com.intermarche.edge.domain.sale.Invoice();
            document.documentNumber = dto.documentNumber;
            created = true;
        }
        document.documentType = dto.documentType == null
                ? com.intermarche.edge.domain.sale.DocumentType.FACTURE
                : com.intermarche.edge.domain.sale.DocumentType.valueOf(dto.documentType);
        document.terminalId = dto.terminalId;
        document.issueDate = parse(dto.issueDate);
        document.ticketNumber = dto.ticketNumber;
        document.ticket = dto.ticketNumber == null
                ? null : Ticket.<Ticket>find("ticketNumber", dto.ticketNumber).firstResult();
        document.customerAccountNumber = dto.customerAccountNumber;
        document.customer = dto.customerAccountNumber == null ? null
                : com.intermarche.edge.domain.payment.AccountCustomer
                        .<com.intermarche.edge.domain.payment.AccountCustomer>find(
                                "accountNumber", dto.customerAccountNumber).firstResult();
        document.customerName = dto.customerName;
        document.customerContact = dto.customerContact;
        document.customerSiret = dto.customerSiret;
        document.customerVatNumber = dto.customerVatNumber;
        document.customerTaxId = dto.customerTaxId;
        document.customerDueDate = parseDay(dto.customerDueDate);
        if (document.customerAddress == null) {
            document.customerAddress = new com.intermarche.edge.domain.store.Address();
        }
        document.customerAddress.streetLine1 = dto.customerStreet;
        document.customerAddress.postalCode = dto.customerPostalCode;
        document.customerAddress.city = dto.customerCity;
        document.totalExcludingTax = dto.totalExcludingTax == null
                ? java.math.BigDecimal.ZERO : dto.totalExcludingTax;
        document.totalIncludingTax = dto.totalIncludingTax == null
                ? java.math.BigDecimal.ZERO : dto.totalIncludingTax;
        document.totalVat = dto.totalVat == null ? java.math.BigDecimal.ZERO : dto.totalVat;
        // BO-10-04-14 : une caisse qui n'extrait pas l'éco-taxe envoie null, ce qui
        // vaut zéro et non « inconnu » — la colonne du document reste obligatoire.
        document.totalEcoTax = dto.totalEcoTax == null
                ? java.math.BigDecimal.ZERO : dto.totalEcoTax;
        document.printCount = dto.printCount;
        document.persist();
        LOGGER.infof("Document %s %s", dto.documentNumber, created ? "créé" : "mis à jour");
        LOGGER.info("Exiting method ingestDocument");
    }

    /**
     * Reads an ISO DAY pushed as text, an unreadable one counting as none.
     *
     * <p>Beside {@code parse}, which reads a moment: a due date is a day, and
     * reading it with the date-time parser would reject every value the
     * register sends ({@code BO-02-04-08}).
     *
     * @param text the day as pushed, possibly null or unreadable
     * @return the day, or null when nothing readable was sent
     */
    private static java.time.LocalDate parseDay(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(text.trim());
        } catch (java.time.format.DateTimeParseException unreadable) {
            return null;
        }
    }

    /**
     * Upserts a technical journal event by its uid.
     *
     * @param dto the pushed event payload
     */
    @Transactional
    public void ingestEvent(SyncPayloads.EventDto dto) {
        LOGGER.info("Entering method ingestEvent with dto: " + dto);
        TechnicalEvent event = TechnicalEvent.find("eventUid", dto.eventUid).firstResult();
        if (event == null) {
            event = new TechnicalEvent();
            event.eventUid = dto.eventUid;
        }
        event.terminalId = dto.terminalId;
        event.eventType = TechnicalEvent.EventType.valueOf(dto.type);
        event.detail = dto.detail;
        event.operatorBadgeId = dto.operatorBadgeId;
        // A register that pushes an event without a date is pushing an event
        // that happened NOW: the column is not nullable and the catalogue
        // accepts the payload, so the node stamps it on arrival rather than
        // refusing the push — the same reading the balance ticket's emittedAt
        // already applies to its own date.
        event.eventDate = dto.eventDate != null
                ? parse(dto.eventDate) : java.time.LocalDateTime.now();
        event.persist();
        LOGGER.info("Exiting method ingestEvent");
    }

    /**
     * Upserts a cash movement by its uid; the referenced session, when named,
     * is resolved by number (a movement whose session has not yet arrived is
     * tolerated with a null link rather than parked — the movement is a
     * first-class witness in its own right).
     *
     * @param dto the pushed movement payload
     */
    /**
     * Ingests a counter ticket pushed by a scale system (LC-06-01-02).
     *
     * <p>Upsert by reference, like every other ingestion here: a scale that
     * retries after a timeout must not create a second ticket. A ticket ALREADY
     * CONSUMED is left untouched — the sale has happened, and letting a late
     * retry resurrect it would hand the same goods to a second customer.
     *
     * @param dto the counter ticket payload
     */
    @Transactional
    public void ingestBalanceTicket(SyncPayloads.BalanceTicketDto dto) {
        LOGGER.info("Entering method ingestBalanceTicket with dto: " + dto);
        BalanceTicket ticket = BalanceTicket.findByReference(dto.reference);
        boolean created = false;
        if (ticket == null) {
            ticket = new BalanceTicket();
            ticket.reference = dto.reference;
            created = true;
        } else if (ticket.isConsumed()) {
            LOGGER.infof("Ticket balance %s déjà consommé : poussée ignorée", dto.reference);
            LOGGER.info("Exiting method ingestBalanceTicket");
            return;
        }
        ticket.counterLabel = dto.counterLabel;
        ticket.emittedAt = dto.emittedAt != null ? parse(dto.emittedAt) : java.time.LocalDateTime.now();
        ticket.lines.clear();
        if (dto.lines != null) {
            for (SyncPayloads.BalanceTicketLineDto lineDto : dto.lines) {
                BalanceTicketLine line = new BalanceTicketLine();
                line.balanceTicket = ticket;
                line.ean = lineDto.ean;
                line.label = lineDto.label;
                line.quantity = lineDto.quantity;
                line.totalIncludingTax = lineDto.totalIncludingTax;
                line.vatRate = lineDto.vatRate;
                ticket.lines.add(line);
            }
        }
        ticket.persist();
        LOGGER.infof("Ticket balance %s %s (%d ligne(s))", dto.reference,
                created ? "créé" : "mis à jour", ticket.lines.size());
        LOGGER.info("Exiting method ingestBalanceTicket");
    }

    /**
     * Serves a counter ticket to the register picking it up, AND consumes it in
     * the same transaction (LC-06-01-02).
     *
     * <p>Reading and consuming are ONE act on purpose. The shop is the only
     * place that can tell a first pick-up from a second, and it can only tell it
     * if no window exists between answering and marking: two registers scanning
     * the same paper at the same instant would otherwise both be served.
     *
     * @param reference the reference scanned at the till
     * @param terminalId the register picking it up
     * @return the ticket as served, or null when the shop holds no such reference
     *         or has already served it
     */
    @Transactional
    public SyncPayloads.BalanceTicketDto consumeBalanceTicket(String reference, String terminalId) {
        LOGGER.info("Entering method consumeBalanceTicket with reference: " + reference + ", terminalId: " + terminalId);
        BalanceTicket ticket = BalanceTicket.findByReference(reference);
        if (ticket == null || ticket.isConsumed()) {
            LOGGER.info("Exiting method consumeBalanceTicket");
            return null;
        }
        ticket.consumedAt = java.time.LocalDateTime.now();
        ticket.consumedByTerminal = terminalId;
        ticket.persist();
        SyncPayloads.BalanceTicketDto dto = new SyncPayloads.BalanceTicketDto();
        dto.reference = ticket.reference;
        dto.counterLabel = ticket.counterLabel;
        dto.emittedAt = ticket.emittedAt != null ? ticket.emittedAt.toString() : null;
        for (BalanceTicketLine line : ticket.lines) {
            SyncPayloads.BalanceTicketLineDto lineDto = new SyncPayloads.BalanceTicketLineDto();
            lineDto.ean = line.ean;
            lineDto.label = line.label;
            lineDto.quantity = line.quantity;
            lineDto.totalIncludingTax = line.totalIncludingTax;
            lineDto.vatRate = line.vatRate;
            dto.lines.add(lineDto);
        }
        LOGGER.infof("Ticket balance %s servi à %s (%d ligne(s))", reference, terminalId, dto.lines.size());
        LOGGER.info("Exiting method consumeBalanceTicket");
        return dto;
    }

    /**
     * Upserts a cash movement by its push identifier.
     *
     * <p>Keyed on the register's own {@code movementUid} and not on a tuple of
     * fields: a movement repushed after a link failure must land on the same row,
     * and two identical withdrawals of the same amount in the same minute are two
     * movements, not one.
     *
     * @param dto the movement payload
     */
    @Transactional
    public void ingestMovement(SyncPayloads.MovementDto dto) {
        LOGGER.info("Entering method ingestMovement with dto: " + dto);
        CashMovement movement = CashMovement.find("movementUid", dto.movementUid).firstResult();
        boolean created = false;
        if (movement == null) {
            movement = new CashMovement();
            movement.movementUid = dto.movementUid;
            created = true;
        }
        movement.terminalId = dto.terminalId;
        movement.session = dto.sessionNumber != null
                ? CashSession.<CashSession>find("sessionNumber", dto.sessionNumber).firstResult()
                : null;
        movement.cashier = dto.cashierLogin != null ? requireEmployee(dto.cashierLogin) : null;
        movement.type = CashMovement.MovementType.valueOf(dto.type);
        movement.amount = dto.amount;
        movement.reason = dto.reason;
        movement.movementDate = parse(dto.movementDate);
        movement.endorsedBy = dto.endorsedBy;
        movement.safeDeposit = dto.safeDeposit;
        movement.orderReference = dto.orderReference;
        movement.persist();
        feedSafe(created, movement);
        LOGGER.infof("Mouvement %s %s (%s)", dto.movementUid, created ? "créé" : "mis à jour", dto.type);
        LOGGER.info("Exiting method ingestMovement");
    }

    /**
     * Feeds the store safe from a till WITHDRAWAL — the « raccord » that gives a
     * till prélèvement a destination instead of leaving it a cash movement with
     * none (EXP-87).
     *
     * <p>The raccord keys off the {@code CashMovement.safeDeposit} flag the register
     * now sets and pushes on the shared {@code SyncPayloads.MovementDto} wire (EXP-87):
     * a withdrawal so flagged is money leaving the drawer toward the safe, whereas a
     * plain prélèvement carries the flag false and stays a bare drawer movement. It is
     * recorded as a safe {@code POUCH} credit (« Remise au coffre »), PENDING, in
     * the open accounting period, carrying the movement's cashier as its author
     * and the movement uid as its reference. It is fed ONCE, on the movement's
     * creation only, so a re-push never doubles it, and only when a period is open
     * ({@link com.intermarche.edge.service.SafeService#record} returns null and the
     * safe is left untouched otherwise). Every unflagged movement (an ordinary
     * prélèvement, apport, dépense, acompte, déclaration) does not transfer to the
     * safe and is left as-is.
     *
     * @param created whether the cash movement was just created
     * @param movement the persisted cash movement
     */
    private void feedSafe(boolean created, CashMovement movement) {
        if (!created || !movement.safeDeposit) {
            return;
        }
        String label = movement.reason == null || movement.reason.isBlank()
                ? "Prélèvement caisse " + movement.terminalId : movement.reason;
        safeService.record(com.intermarche.edge.domain.safe.SafeMovementType.POUCH, movement.amount, label,
                movement.movementDate == null ? null : movement.movementDate.toLocalDate(),
                movement.cashier, null, movement.movementUid, null);
    }

    // --------------------------------------------------
    // Natural key resolution
    // --------------------------------------------------

    /**
     * Resolves an employee by login, active or not (historical documents).
     *
     * @param login the employee login
     * @return the employee
     * @throws IllegalStateException when the login is unknown on this node
     */
    private Employee requireEmployee(String login) {
        Employee employee = login != null
                ? Employee.<Employee>find("loginName", login.toLowerCase()).firstResult()
                : null;
        if (employee == null) {
            throw new IllegalStateException("Employé inconnu sur le nœud magasin: " + login);
        }
        return employee;
    }

    /**
     * Resolves the store by code, falling back to the single local store.
     *
     * @param code the store code, or null
     * @return the store
     * @throws IllegalStateException when no store exists on this node
     */
    private Store requireStore(String code) {
        Store store = code != null ? Store.<Store>find("code", code).firstResult() : null;
        if (store == null) {
            store = Store.findAll().firstResult();
        }
        if (store == null) {
            throw new IllegalStateException("Aucun magasin sur le nœud magasin");
        }
        return store;
    }

    /**
     * Parses an ISO-8601 timestamp, tolerating null.
     *
     * @param value the ISO string, or null
     * @return the timestamp, or null
     */
    private LocalDateTime parse(String value) {
        return value != null ? LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
    }
}
