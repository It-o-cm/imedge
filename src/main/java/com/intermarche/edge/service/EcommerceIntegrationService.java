package com.intermarche.edge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.EcommerceOrder;
import com.intermarche.edge.domain.sale.SalesChannel;
import com.intermarche.edge.domain.sale.TaxIdRules;
import com.intermarche.edge.domain.sale.ThirdPartySystem;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sync.FidEvent;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.store.CheckoutIsland;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.imports.OrderRecallResource;
import com.intermarche.edge.service.sync.SyncPayloads;
import com.intermarche.edge.service.sync.register.FidEventOutboxService;
import com.intermarche.edge.service.sync.store.SyncIngestService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * INTEGRATES the validated orders of the e-commerce platform into the store's
 * encashment (BO-10-01-01 to -14): the order is booked as a transaction of the
 * store — a closed {@code Ticket} of the e-commerce virtual register — so the
 * point of sale's revenue, journal and outbound flows count it like any sale,
 * and the platform is answered, order by order, with an INTEGRATION REPORT.
 *
 * <p>The administered rules of the channel apply on the way:
 * <ul>
 *   <li>activation of the channel on the point of sale (BO-10-01-01);</li>
 *   <li>the price of each article, from the point of sale's base or from the
 *       order (BO-10-01-07);</li>
 *   <li>an article forbidden to sale accepted or rejecting the whole order
 *       (BO-10-01-14);</li>
 *   <li>an article the referential does not know carried on the generic EAN
 *       and marked, never blocking the order (BO-10-01-06);</li>
 *   <li>a payment means the encashment does not know integrated under the
 *       default tender (BO-10-01-09);</li>
 *   <li>the virtual register attached to the administered island
 *       (BO-10-01-08);</li>
 *   <li>the original order archived beside its report (BO-10-01-11);</li>
 *   <li>the THIRD-PARTY SYSTEM that deposited it, recognised by its token,
 *       and the channel — e-commerce or mobility — its sales are booked
 *       under (BO-07-01-01);</li>
 *   <li>the point of sale of ORIGIN the order names, whose revenue it counts
 *       in, the channel carried by the ticket (BO-07-01-02);</li>
 *   <li>the loyalty earn of the order's card queued for the programme
 *       (BO-07-01-03);</li>
 *   <li>the platform's payment means kept on each settlement, so the cash
 *       book isolates the e-commerce payments (BO-07-01-04);</li>
 *   <li>the Portuguese fiscal document stating the order, for the SAF-T
 *       file (BO-07-01-06);</li>
 *   <li>the customer's NIF, checked like the register's, the final
 *       consumer's 999999990 when none is sent (BO-07-01-07, -08).</li>
 * </ul>
 *
 * <p>IDEMPOTENT BY REFERENCE: an order the node already answered is answered
 * again with the report it got, never booked twice — the platform retries a
 * call whose answer it lost.
 */
@ApplicationScoped
public class EcommerceIntegrationService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EcommerceIntegrationService.class);

    /** The shape a platform reference must have: it ends up in a ticket number. */
    public static final String REFERENCE_PATTERN = "[A-Za-z0-9_-]{1,24}";

    /** The longest ticket number the ticket table holds. */
    static final int TICKET_NUMBER_LENGTH = 30;

    /** The NIF of the final consumer, when neither the order nor the store states one. */
    static final String FINAL_CONSUMER = "999999990";

    /** The virtual register of the mobility sales when none is administered. */
    static final String MOBILITY_TERMINAL = "MOBI";

    /** The administered rules of the channel. */
    @Inject
    PosSettingsService posSettingsService;

    /** Books the transaction exactly as a register's push would be. */
    @Inject
    SyncIngestService syncIngestService;

    /** Writes the report kept beside the order. */
    @Inject
    ObjectMapper objectMapper;

    /** The payment factories, whose keys are the tenders the encashment knows. */
    @Inject
    Instance<TicketPayment.Factory> paymentFactories;

    /** States the order on its Portuguese fiscal document (BO-07-01-06). */
    @Inject
    EcommerceFiscalService ecommerceFiscalService;

    /** Queues the loyalty earn of the order for the programme (BO-07-01-03). */
    @Inject
    FidEventOutboxService fidEventOutboxService;

    /** Closes the engine basket an order was built in (DG-01-01-06). */
    @Inject
    EcommerceBasketService ecommerceBasketService;

    /** Keeps an order paid short as a basket the till recalls (DG-03-01-05). */
    @Inject
    CustomerOrderService customerOrderService;

    /** The clock the reception is dated by. */
    Clock clock = Clock.systemDefaultZone();

    /**
     * Integrates one order and answers its report.
     *
     * @param order the order the platform sent, already read
     * @param payload the order as the platform sent it, for the archive
     * @return the integration report
     */
    @Transactional
    public Report integrate(OrderDto order, String payload) {
        return integrate(order, payload, null);
    }

    /**
     * Integrates one order deposited by a declared third-party system and
     * answers its report (BO-07-01-01).
     *
     * @param order the order the system sent, already read
     * @param payload the order as the system sent it, for the archive
     * @param systemCode the code of the system its token named, or null when
     *                   it presented the shared synchronization token
     * @return the integration report
     */
    @Transactional
    public Report integrate(OrderDto order, String payload, String systemCode) {
        LOGGER.info("Entering method integrate with reference: " + order.reference);
        ThirdPartySystem system = systemCode == null ? null : system(systemCode);
        Report report = new Report();
        report.reference = order.reference;
        EcommerceOrder known = existing(order.reference);
        if (known != null) {
            report = readReport(known);
            report.alreadyReceived = true;
            LOGGER.info("Exiting method integrate (already received)");
            return report;
        }
        SalesChannel channel = channel(order, system);
        report.channel = channel.name();
        report.systemCode = system == null ? null : system.code;
        String closed = closedChannel(channel, system);
        if (closed != null) {
            report.status = Report.DISABLED;
            report.message = closed;
            LOGGER.info("Exiting method integrate (channel disabled)");
            return report;
        }
        EcommerceSnapshot snapshot = null;
        if (order.basket != null && order.basket.isObject()) {
            snapshot = valueBasket(order, report, systemCode);
            if (snapshot == null) {
                report.status = Report.REJECTED;
                save(record(order, report, payload, null));
                LOGGER.info("Exiting method integrate (basket not valued)");
                return report;
            }
        }
        List<SyncPayloads.LineDto> lines = snapshot == null ? lines(order, report) : snapshotLines(snapshot, report);
        BigDecimal total = total(lines);
        List<SyncPayloads.PaymentDto> payments = payments(order, report, total);
        report.pdvNumber = pdv(order, system);
        report.customerTaxId = taxId(order, report);
        String refusal = refusal(report, payments);
        if (refusal != null) {
            report.status = Report.REJECTED;
            report.message = refusal;
            save(record(order, report, payload, null));
            LOGGER.info("Exiting method integrate (rejected)");
            return report;
        }
        BigDecimal due = total.subtract(paid(payments));
        if (due.signum() > 0) {
            shortPaid(order, report, lines, total, due);
            save(record(order, report, payload, null));
            LOGGER.info("Exiting method integrate (paid short: " + report.status + ")");
            return report;
        }
        String terminal = terminal(channel, system);
        SyncPayloads.TicketDto ticket = ticket(order, terminal, lines, payments);
        if (snapshot != null) {
            ticket.valuationStatus = "VALUATED";
            ticket.valuations = snapshot.valuations;
        }
        ticket.salesChannel = channel.name();
        ticket.storeCode = report.pdvNumber;
        ticket.customerTaxId = report.customerTaxId;
        state(order, ticket, report);
        attachToIsland(terminal, report, channel);
        syncIngestService.ingestTicket(ticket);
        report.fidelityQueued = channel == SalesChannel.ECOMMERCE && earn(ticket, report, snapshot);
        report.status = Report.INTEGRATED;
        report.ticketNumber = ticket.ticketNumber;
        report.total = ticket.totalIncludingTax;
        report.message = "Commande intégrée";
        if (snapshot != null) {
            declare(order, report, snapshot);
        }
        save(record(order, report, payload, ticket.ticketNumber));
        // The snapshot goes back to the platform, whose basket it is, and is
        // not kept with the stored report: the platform sends it with a return.
        report.snapshot = snapshot == null ? null : Map.of("request", snapshot.request, "evaluation", snapshot.evaluation);
        if (system != null) {
            system.lastCallAt = LocalDateTime.now(clock);
            system.orderCount++;
        }
        LOGGER.info("Exiting method integrate (integrated as " + ticket.ticketNumber + ")");
        return report;
    }

    /**
     * The channel an order is booked under (BO-07-01-01): the declared
     * system's, else the one the order names, else the e-commerce one — the
     * store's own lanes are never a third party's channel.
     *
     * @param order the order
     * @param system the system that deposited it, or null
     * @return the channel, a third party's
     */
    static SalesChannel channel(OrderDto order, ThirdPartySystem system) {
        SalesChannel channel = system != null ? system.channel : SalesChannel.fromName(order.channel);
        return channel == null || !channel.isThirdParty() ? SalesChannel.ECOMMERCE : channel;
    }

    /**
     * Why the integration activity is not open for an order (BO-07-01-01):
     * a deactivated system, the e-commerce channel not opened on the point
     * of sale, or a mobility sale no declared system deposited.
     *
     * @param channel the channel of the order
     * @param system the system that deposited it, or null
     * @return the reason, in French, or null when the activity is open
     */
    String closedChannel(SalesChannel channel, ThirdPartySystem system) {
        if (system != null && !system.active) {
            return "Système tiers désactivé";
        }
        if (channel == SalesChannel.ECOMMERCE) {
            return posSettingsService.ecommerceEnabled() ? null : "E-commerce non activé sur ce point de vente";
        }
        return system == null ? "Aucun système tiers de mobilité déclaré" : null;
    }

    /**
     * The point of sale of origin an order counts for (BO-07-01-02): the one
     * the order names, else the one its system is administered for.
     *
     * @param order the order
     * @param system the system that deposited it, or null
     * @return the point of sale's code, or null for this node's store
     */
    static String pdv(OrderDto order, ThirdPartySystem system) {
        if (order.pdvNumber != null && !order.pdvNumber.isBlank()) {
            return order.pdvNumber.trim();
        }
        if (system != null && system.pdvNumber != null && !system.pdvNumber.isBlank()) {
            return system.pdvNumber.trim();
        }
        return null;
    }

    /**
     * The customer's NIF the transaction states (BO-07-01-07, -08): the one
     * the order sent, upper-cased and without spaces, or the store's default
     * NIF — the final consumer's 999999990 when the store administers none —
     * when it sent none.
     *
     * @param order the order
     * @param report the report, told whether the default applied
     * @return the NIF, never blank
     */
    String taxId(OrderDto order, Report report) {
        String sent = order.customerTaxId == null ? ""
                : order.customerTaxId.replace(" ", "").trim().toUpperCase(Locale.ROOT);
        if (!sent.isEmpty()) {
            return sent;
        }
        report.customerTaxIdDefaulted = true;
        String administered = posSettingsService.defaultTaxId();
        return administered == null || administered.isBlank() ? FINAL_CONSUMER : administered.trim();
    }

    /**
     * States the order on its Portuguese fiscal document, where the store
     * issues them (BO-07-01-06). A document that cannot be stated is reported
     * and does not stop the order.
     *
     * @param order the order, possibly carrying the platform's own document
     * @param ticket the ticket the order is booked as
     * @param report the report, completed in place
     */
    private void state(OrderDto order, SyncPayloads.TicketDto ticket, Report report) {
        if (!posSettingsService.ptDocuments()) {
            return;
        }
        try {
            SyncPayloads.FiscalDocumentDto document = ecommerceFiscalService.document(order.invoice, ticket);
            ticket.fiscalDocuments.add(document);
            report.fiscalDocument = document.number;
        } catch (Exception failure) {
            LOGGER.warnf("Document fiscal de la commande %s non émis : %s", order.reference, failure.getMessage());
            report.warnings.add("Document fiscal non émis");
        }
    }

    /**
     * Queues the loyalty earn of an order carrying a card (BO-07-01-03): the
     * ticket-closed event the register sends for a sale, composed from the
     * booked lines — the basket as the programme values it, the Web channel —
     * and drained to the programme by the outbox.
     *
     * @param ticket the booked ticket
     * @param report the report, warned when the event cannot be composed
     * @param snapshot the closing snapshot of the order's basket, or null
     * @return true when the event was queued
     */
    private boolean earn(SyncPayloads.TicketDto ticket, Report report, EcommerceSnapshot snapshot) {
        if (ticket.fidelityCard == null || ticket.fidelityCard.isBlank()
                || !posSettingsService.ecommerceFidelityEarn()) {
            return false;
        }
        try {
            fidEventOutboxService.enqueue(FidEvent.EventType.TICKET_CLOSED,
                    objectMapper.writeValueAsString(fidelityEvent(ticket, snapshot)));
            return true;
        } catch (Exception failure) {
            LOGGER.warnf("Cagnottage de la commande %s impossible : %s", ticket.ticketNumber, failure.getMessage());
            report.warnings.add("Cagnottage fidélité impossible");
            return false;
        }
    }

    /**
     * The ticket-closed event of an order, in the shape the register sends:
     * the basket the engine froze at close and its evaluation when the order
     * was closed from a basket (DG-01-01-06), else the booked lines with no
     * offer.
     *
     * @param ticket the booked ticket
     * @param snapshot the closing snapshot of the order's basket, or null
     * @return the event, as a tree of maps and lists
     */
    Map<String, Object> fidelityEvent(SyncPayloads.TicketDto ticket, EcommerceSnapshot snapshot) {
        String card = ticket.fidelityCard.trim();
        String date = ticket.closingDate.substring(0, 10);
        String channel = posSettingsService.ecommercePromotionChannel();
        String promotionChannel = channel == null || channel.isBlank() ? "WEB" : channel;
        List<Map<String, Object>> items = new ArrayList<>();
        for (SyncPayloads.LineDto line : ticket.lines) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("lineId", line.lineUid);
            item.put("produceEan", line.ean);
            item.put("quantity", line.quantity);
            item.put("pricePerUnitInclTax", line.unitPrice);
            item.put("pricePerUnitExclTax", line.unitPrice.divide(BigDecimal.ONE.add(line.vatRate), 4, RoundingMode.HALF_UP));
            item.put("vatRate", line.vatRate);
            items.add(item);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("customerCode", card);
        request.put("storeCode", ticket.storeCode);
        request.put("createdAt", ticket.creationDate);
        request.put("promotionChannel", promotionChannel);
        request.put("cardNumber", card);
        request.put("items", items);
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("amountIncludingTax", ticket.totalIncludingTax);
        total.put("amountExcludingTax", ticket.totalExcludingTax);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("offers", List.of());
        response.put("advantages", List.of());
        response.put("totalPrice", total);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ticketRef", date.substring(0, 4) + "-" + ticket.ticketNumber);
        event.put("card", card);
        event.put("fiscalDate", date);
        event.put("valuationRequest", snapshot == null ? request : snapshot.request);
        event.put("valuationResponse", snapshot == null ? response : snapshot.evaluation);
        event.put("displayedEarn", List.of());
        event.put("channel", promotionChannel);
        return event;
    }

    /**
     * Values the final basket an order carries (DG-01-01-06): the engine's
     * stateless valuation of the basket declared closed, its card promotions
     * read again, read into the closing snapshot the order is booked from.
     *
     * @param order the order, carrying the platform's final basket
     * @param report the report, completed in place
     * @param systemCode the declared system calling, or null
     * @return the closing snapshot, or null when the engine could not value
     *     the basket (the reason in the report's message)
     */
    private EcommerceSnapshot valueBasket(OrderDto order, Report report, String systemCode) {
        report.basketId = order.basketId == null || order.basketId.isBlank() ? null : order.basketId.trim();
        EcommerceBasketService.Valued valued = ecommerceBasketService.valueFinal(
                ((com.fasterxml.jackson.databind.node.ObjectNode) order.basket).deepCopy(), systemCode);
        if (valued.warning() != null) {
            report.warnings.add("Promotions carte indisponibles");
        }
        if (valued.status() != 200) {
            report.message = "Valorisation du panier refusée par le moteur (HTTP " + valued.status() + ")";
            return null;
        }
        EcommerceSnapshot snapshot = EcommerceSnapshot.of(valued.basket(), valued.evaluation(), objectMapper);
        if (snapshot == null) {
            report.message = "Valorisation du panier illisible";
            return null;
        }
        report.valuated = true;
        report.consumedCoupons.addAll(snapshot.consumedCoupons);
        return snapshot;
    }

    /**
     * Declares the SALE consumption of the order's basket to the engine
     * (DG-01-01-06), once it is booked; a declaration the engine does not take
     * is reported, never undoes the sale.
     *
     * @param order the order
     * @param report the report, completed in place
     * @param snapshot the closing snapshot
     */
    private void declare(OrderDto order, Report report, EcommerceSnapshot snapshot) {
        String finalizedAt = order.orderDate == null || order.orderDate.isBlank()
                ? LocalDateTime.now(clock).toString() : order.orderDate.trim();
        String storeCode = report.pdvNumber != null ? report.pdvNumber : posSettingsService.pdvNumber();
        String evaluation;
        try {
            evaluation = objectMapper.writeValueAsString(snapshot.evaluation);
        } catch (Exception e) {
            evaluation = "{}";
        }
        int status = ecommerceBasketService.declareSale(order.reference.trim(), storeCode, finalizedAt, evaluation);
        report.consumptionDeclared = status >= 200 && status < 300 && status != 204;
        if (status < 200 || status >= 300) {
            report.warnings.add("Consommation des offres non déclarée (HTTP " + status + ")");
        }
    }

    /**
     * Reports the lines of a closing snapshot under the channel's article
     * rules (BO-10-01-06, -14): the engine's price is kept, the unknown and
     * forbidden articles are counted.
     *
     * @param snapshot the closing snapshot
     * @param report the report, completed in place
     * @return the ticket lines to book
     */
    private List<SyncPayloads.LineDto> snapshotLines(EcommerceSnapshot snapshot, Report report) {
        for (SyncPayloads.LineDto line : snapshot.lines) {
            Product product = line.ean.isEmpty() ? null : product(line.ean);
            ReportLine outcome = new ReportLine();
            outcome.lineNumber = line.lineNumber;
            outcome.ean = line.ean;
            outcome.label = line.productLabel;
            outcome.unitPrice = line.unitPrice;
            if (product == null) {
                outcome.outcome = ReportLine.UNKNOWN;
                report.unknownLines++;
            } else {
                outcome.outcome = product.forbiddenToSale ? ReportLine.FORBIDDEN : ReportLine.OK;
                report.forbiddenLines += product.forbiddenToSale ? 1 : 0;
            }
            report.lines.add(outcome);
        }
        return snapshot.lines;
    }

    /**
     * What the stated payments add up to.
     *
     * @param payments the payments to book
     * @return their total, TTC
     */
    private static BigDecimal paid(List<SyncPayloads.PaymentDto> payments) {
        BigDecimal paid = BigDecimal.ZERO;
        for (SyncPayloads.PaymentDto payment : payments) {
            paid = paid.add(payment.amount);
        }
        return paid;
    }

    /**
     * Decides the fate of an order paid short (DG-03-01-05): it is never booked
     * as a closed sale. Under RECALL it is kept as an e-commerce basket the
     * till recalls by its reference, the platform's payments restated there
     * and the remainder collected at the till; under REJECT it is refused.
     *
     * @param order the order
     * @param report the report, completed in place
     * @param lines the lines read
     * @param total the total of the lines, TTC
     * @param due what the payments leave unpaid, TTC
     */
    private void shortPaid(OrderDto order, Report report, List<SyncPayloads.LineDto> lines,
                           BigDecimal total, BigDecimal due) {
        String amount = due.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
        report.total = total;
        report.remaining = due;
        if ("REJECT".equals(posSettingsService.ecommercePartialPayment())) {
            report.status = Report.REJECTED;
            report.message = "Commande partiellement réglée refusée : reste dû " + amount;
            return;
        }
        OrderRecallResource.OrderPushDto push = new OrderRecallResource.OrderPushDto();
        push.code = order.reference.trim();
        push.kind = com.intermarche.edge.domain.sale.OrderKind.ECOMMERCE.name();
        push.totalAmount = total;
        push.label = "Commande web " + push.code;
        push.customerNumber = order.customerNumber;
        for (SyncPayloads.LineDto line : lines) {
            OrderRecallResource.OrderPushLineDto pushed = new OrderRecallResource.OrderPushLineDto();
            pushed.ean = line.ean;
            pushed.label = line.productLabel;
            pushed.quantity = line.quantity;
            pushed.unitPrice = line.unitPrice;
            pushed.vatRate = line.vatRate;
            push.lines.add(pushed);
        }
        push.payments = new ArrayList<>();
        for (ReportPayment payment : report.payments) {
            OrderRecallResource.OrderPushPaymentDto pushed = new OrderRecallResource.OrderPushPaymentDto();
            pushed.tender = payment.integratedAs;
            pushed.amount = payment.amount;
            push.payments.add(pushed);
        }
        OrderRecallResource.store(customerOrderService, push);
        report.status = Report.PENDING_PAYMENT;
        report.recallCode = push.code;
        report.message = "Commande en attente de règlement en caisse : reste dû " + amount;
    }

    /**
     * Answers the report of an order the node already received.
     *
     * @param reference the platform reference
     * @return the report, or null when the node never received the order
     */
    public Report reportOf(String reference) {
        LOGGER.info("Entering method reportOf with reference: " + reference);
        EcommerceOrder known = existing(reference);
        LOGGER.info("Exiting method reportOf");
        return known == null ? null : readReport(known);
    }

    /**
     * The ticket an integrated order was booked as, for a register taking back
     * an e-commerce sale (BO-10-01-04, -05): only a ticket of the e-commerce
     * virtual register is served.
     *
     * @param ticketNumber the ticket number the customer presents
     * @return the ticket's view, or null when the node holds no such
     *         e-commerce ticket
     */
    public TicketView ticketOf(String ticketNumber) {
        LOGGER.info("Entering method ticketOf with ticketNumber: " + ticketNumber);
        Ticket ticket = bookedTicket(ticketNumber);
        if (ticket == null || !terminal().equals(ticket.terminalId)) {
            LOGGER.info("Exiting method ticketOf (no e-commerce ticket)");
            return null;
        }
        TicketView view = new TicketView();
        view.ticketNumber = ticket.ticketNumber;
        view.terminalId = ticket.terminalId;
        view.creationDate = ticket.creationDate == null ? null : ticket.creationDate.toString();
        view.closingDate = ticket.closingDate == null ? null : ticket.closingDate.toString();
        view.totalIncludingTax = ticket.totalIncludingTax;
        view.totalExcludingTax = ticket.totalExcludingTax;
        view.totalVat = ticket.totalVat;
        view.customerTaxId = ticket.customerTaxId;
        for (TicketLine line : ticket.lines) {
            TicketViewLine row = new TicketViewLine();
            row.lineNumber = line.lineNumber;
            row.lineUid = line.lineUid;
            row.ean = line.ean;
            row.label = line.productLabel;
            row.quantity = line.quantity;
            row.unitPrice = line.unitPrice;
            row.vatRate = line.vatRate;
            row.totalPrice = line.totalPrice;
            view.lines.add(row);
        }
        LOGGER.info("Exiting method ticketOf");
        return view;
    }

    /**
     * Why an order whose lines and payments were read cannot be booked: a
     * forbidden article under a REJECT rule, an operator or a store the node
     * does not know, a payment means no tender can carry.
     *
     * @param report the report, its lines filled
     * @param payments the payments read, null when no tender can carry one
     * @return the reason, in French, or null when the order can be booked
     */
    private String refusal(Report report, List<SyncPayloads.PaymentDto> payments) {
        // BO-07-01-07: a Portuguese NIF the store checks must be well formed.
        if (!TaxIdRules.acceptable(report.customerTaxId, posSettingsService.taxIdPortugueseCheck())) {
            return "NIF client invalide";
        }
        // BO-07-01-02: the point of sale of origin must be one this node holds.
        if (report.pdvNumber != null && !storeCodeKnown(report.pdvNumber)) {
            return "Point de vente d'origine inconnu du nœud magasin";
        }
        if (report.forbiddenLines > 0
                && "REJECT".equals(posSettingsService.ecommerceForbiddenItems())) {
            return "Article en vente interdite";
        }
        if (payments == null) {
            return "Moyen de paiement par défaut inconnu de l'encaissement";
        }
        if (!operatorKnown(posSettingsService.ecommerceOperator())) {
            return "Opérateur d'intégration inconnu";
        }
        return storeKnown() ? null : "Aucun point de vente sur le nœud magasin";
    }

    /**
     * Reads the order's lines under the channel's rules (BO-10-01-06, -07,
     * -14), filling the report line by line.
     *
     * @param order the order
     * @param report the report, completed in place
     * @return the ticket lines to book
     */
    private List<SyncPayloads.LineDto> lines(OrderDto order, Report report) {
        String genericEan = posSettingsService.ecommerceUnknownItemEan();
        boolean registerPrice = registerPrice();
        List<SyncPayloads.LineDto> lines = new ArrayList<>();
        int number = 0;
        for (OrderLineDto line : order.lines) {
            number++;
            String ean = line.ean == null ? "" : line.ean.trim();
            Product product = ean.isEmpty() ? null : product(ean);
            ReportLine outcome = new ReportLine();
            outcome.lineNumber = number;
            outcome.ean = ean;
            outcome.label = line.label;
            SyncPayloads.LineDto booked = new SyncPayloads.LineDto();
            booked.lineNumber = number;
            booked.lineUid = UUID.randomUUID().toString();
            booked.ean = ean;
            booked.productLabel = line.label == null ? ean : line.label;
            BigDecimal unitPrice = line.unitPrice == null ? BigDecimal.ZERO : line.unitPrice;
            BigDecimal vatRate = line.vatRate == null ? BigDecimal.ZERO : line.vatRate;
            if (product == null) {
                outcome.outcome = ReportLine.UNKNOWN;
                report.unknownLines++;
                if (!genericEan.isEmpty()) {
                    booked.ean = genericEan;
                    booked.productLabel = booked.productLabel + " [ART. INCONNU " + ean + "]";
                    outcome.carriedOn = genericEan;
                }
            } else {
                outcome.outcome = product.forbiddenToSale ? ReportLine.FORBIDDEN : ReportLine.OK;
                report.forbiddenLines += product.forbiddenToSale ? 1 : 0;
                Price price = registerPrice ? price(product) : null;
                if (price != null) {
                    unitPrice = price.priceIncludingTax;
                    vatRate = price.vatRate() == null ? vatRate : price.vatRate();
                }
            }
            BigDecimal quantity = line.quantity == null ? BigDecimal.ONE : line.quantity;
            booked.quantity = quantity;
            booked.unitPrice = unitPrice;
            booked.vatRate = vatRate;
            booked.totalPrice = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            outcome.unitPrice = unitPrice;
            report.lines.add(outcome);
            lines.add(booked);
        }
        return lines;
    }

    /**
     * Whether the articles take the point of sale's price (BO-10-01-07): POS
     * says yes, TRANSACTION says no, anything else defers to the global rule of
     * the recalled documents.
     *
     * @return true when the point of sale's current price applies
     */
    private boolean registerPrice() {
        String source = posSettingsService.ecommercePriceSource();
        if ("POS".equals(source)) {
            return true;
        }
        return !"TRANSACTION".equals(source) && posSettingsService.orderRecallPriceFromRegister();
    }

    /**
     * Reads the order's payments (BO-10-01-09): a means the encashment knows is
     * booked as such, any other under the default tender; an order stating no
     * payment is booked as paid in full under the default tender.
     *
     * @param order the order
     * @param report the report, completed in place
     * @param total the total of the lines, TTC
     * @return the payments to book, or null when a payment needs the default
     *         tender and the encashment does not know it either
     */
    private List<SyncPayloads.PaymentDto> payments(OrderDto order, Report report, BigDecimal total) {
        Set<String> known = knownTenders();
        String fallback = posSettingsService.ecommerceDefaultTender().toUpperCase(Locale.ROOT);
        List<OrderPaymentDto> stated = new ArrayList<>();
        for (OrderPaymentDto payment : order.payments) {
            if (payment != null && payment.amount != null && payment.amount.signum() > 0) {
                stated.add(payment);
            }
        }
        if (stated.isEmpty()) {
            OrderPaymentDto whole = new OrderPaymentDto();
            whole.amount = total;
            stated.add(whole);
        }
        List<SyncPayloads.PaymentDto> payments = new ArrayList<>();
        for (OrderPaymentDto payment : stated) {
            String method = payment.method == null ? "" : payment.method.trim().toUpperCase(Locale.ROOT);
            String tender = known.contains(method) ? method : fallback;
            if (!known.contains(tender)) {
                return null;
            }
            ReportPayment outcome = new ReportPayment();
            outcome.method = method;
            outcome.integratedAs = tender;
            outcome.amount = payment.amount;
            report.payments.add(outcome);
            SyncPayloads.PaymentDto booked = new SyncPayloads.PaymentDto();
            booked.paymentIndex = payments.size();
            booked.methodKey = tender;
            booked.amount = payment.amount;
            booked.tenderedAmount = payment.amount;
            // BO-07-01-04: the platform's own means rides the settlement, so
            // the cash book isolates the e-commerce payments by their means.
            booked.tenderCode = method.isEmpty() ? null : method;
            payments.add(booked);
        }
        return payments;
    }

    /**
     * The tenders the encashment can book: the keys of the payment factories.
     *
     * @return the settlement keys
     */
    private Set<String> knownTenders() {
        Set<String> keys = new HashSet<>();
        for (TicketPayment.Factory factory : paymentFactories) {
            keys.add(factory.getKey());
        }
        return keys;
    }

    /**
     * Builds the closed ticket the order is booked as, on the virtual register.
     *
     * @param order the order
     * @param terminal the virtual register
     * @param lines the lines to book
     * @param payments the payments to book
     * @return the ticket, in the register's push shape
     */
    private SyncPayloads.TicketDto ticket(OrderDto order, String terminal,
                                          List<SyncPayloads.LineDto> lines,
                                          List<SyncPayloads.PaymentDto> payments) {
        SyncPayloads.TicketDto ticket = new SyncPayloads.TicketDto();
        String number = terminal + "-" + order.reference.trim();
        ticket.ticketNumber = number.length() > TICKET_NUMBER_LENGTH
                ? number.substring(number.length() - TICKET_NUMBER_LENGTH) : number;
        ticket.terminalId = terminal;
        ticket.status = "CLOSED";
        String date = order.orderDate == null || order.orderDate.isBlank()
                ? LocalDateTime.now(clock).toString() : order.orderDate.trim();
        ticket.creationDate = date;
        ticket.closingDate = date;
        ticket.cashierLogin = posSettingsService.ecommerceOperator();
        ticket.fidelityCard = order.customerNumber;
        ticket.customerEmail = order.customerEmail;
        ticket.valuationStatus = "NOT_VALUATED";
        ticket.lines = lines;
        ticket.payments = payments;
        ticket.itemCount = articleCount(lines);
        BigDecimal vat = BigDecimal.ZERO;
        for (SyncPayloads.LineDto line : lines) {
            BigDecimal net = line.totalPrice.divide(BigDecimal.ONE.add(line.vatRate), 2, RoundingMode.HALF_UP);
            vat = vat.add(line.totalPrice.subtract(net));
        }
        ticket.totalIncludingTax = total(lines);
        ticket.totalVat = vat;
        ticket.totalExcludingTax = ticket.totalIncludingTax.subtract(vat);
        return ticket;
    }

    /**
     * The number of ARTICLES the booked lines hold, not the number of lines:
     * the units of a piece line, one for a weighed article or a line whose
     * quantity is not a whole number; a returned line counts for none.
     *
     * @param lines the booked lines
     * @return the article count
     */
    int articleCount(List<SyncPayloads.LineDto> lines) {
        int count = 0;
        for (SyncPayloads.LineDto line : lines) {
            if (line.returnLine) {
                continue;
            }
            BigDecimal quantity = line.quantity == null ? BigDecimal.ONE : line.quantity;
            Product product = line.ean == null || line.ean.isEmpty() ? null : product(line.ean);
            boolean weighed = product != null && product.productType == com.intermarche.edge.domain.catalog.ProductType.WEIGHT;
            boolean whole = quantity.signum() > 0 && quantity.stripTrailingZeros().scale() <= 0;
            count += weighed || !whole ? 1 : quantity.intValue();
        }
        return count;
    }

    /**
     * The total of the booked lines.
     *
     * @param lines the lines
     * @return the total, TTC
     */
    private static BigDecimal total(List<SyncPayloads.LineDto> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (SyncPayloads.LineDto line : lines) {
            total = total.add(line.totalPrice);
        }
        return total;
    }

    /**
     * The administered virtual register, "ECOM" when none is administered.
     *
     * @return the terminal identifier
     */
    private String terminal() {
        String terminal = posSettingsService.ecommerceTerminal();
        return terminal.isEmpty() ? "ECOM" : terminal;
    }

    /**
     * The virtual register an order is booked on (BO-07-01-01): the one its
     * system is administered with, else the e-commerce one for the e-commerce
     * channel and "MOBI" for the mobility one.
     *
     * @param channel the channel of the order
     * @param system the system that deposited it, or null
     * @return the terminal identifier
     */
    private String terminal(SalesChannel channel, ThirdPartySystem system) {
        if (system != null && system.terminalId != null && !system.terminalId.isBlank()) {
            return system.terminalId.trim();
        }
        return channel == SalesChannel.ECOMMERCE ? terminal() : MOBILITY_TERMINAL;
    }

    /**
     * Attaches the virtual register to the administered island (BO-10-01-08),
     * declaring it as a checkout point the first time, so the island's revenue
     * and its rules take the e-commerce transactions in. An island the node does
     * not know is reported and not attached.
     *
     * @param terminal the virtual register
     * @param report the report, completed in place
     * @param channel the channel of the order: only the e-commerce one is
     *                attached to the administered island
     */
    private void attachToIsland(String terminal, Report report, SalesChannel channel) {
        String island = channel == SalesChannel.ECOMMERCE ? posSettingsService.ecommerceIsland() : "";
        String islandCode = island.isEmpty() ? null : island;
        if (islandCode != null && !islandKnown(islandCode)) {
            report.warnings.add("Îlot inconnu : " + islandCode);
            islandCode = null;
        }
        CheckoutPoint point = point(terminal);
        if (point == null) {
            point = new CheckoutPoint();
            point.terminalId = terminal;
            point.label = "Caisse virtuelle " + channel.getLabel().toLowerCase(Locale.ROOT);
            point.islandCode = islandCode;
            savePoint(point);
            return;
        }
        if (islandCode != null) {
            point.islandCode = islandCode;
        }
    }

    /**
     * The row kept for the order: its outcome, its report and, where the store
     * archives them, the order as the platform sent it (BO-10-01-11).
     *
     * @param order the order
     * @param report the report
     * @param payload the order as sent
     * @param ticketNumber the ticket booked, or null
     * @return the row to persist
     */
    private EcommerceOrder record(OrderDto order, Report report, String payload, String ticketNumber) {
        EcommerceOrder row = new EcommerceOrder();
        row.reference = order.reference.trim();
        row.receivedAt = LocalDateTime.now(clock);
        row.status = Report.INTEGRATED.equals(report.status) ? EcommerceOrder.Status.INTEGRATED
                : Report.PENDING_PAYMENT.equals(report.status) ? EcommerceOrder.Status.PENDING_PAYMENT
                : EcommerceOrder.Status.REJECTED;
        row.ticketNumber = ticketNumber;
        row.totalAmount = report.total;
        row.unknownLines = report.unknownLines;
        row.forbiddenLines = report.forbiddenLines;
        row.message = report.message;
        row.report = writeReport(report);
        row.originalPayload = posSettingsService.ecommerceArchive() ? payload : null;
        row.channel = SalesChannel.fromName(report.channel);
        row.systemCode = report.systemCode;
        row.pdvNumber = report.pdvNumber;
        row.customerTaxId = report.customerTaxId;
        row.fidelityQueued = report.fidelityQueued;
        row.fiscalDocument = report.fiscalDocument;
        return row;
    }

    /**
     * Writes a report as JSON.
     *
     * @param report the report
     * @return the JSON text, or null when it cannot be written
     */
    private String writeReport(Report report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            LOGGER.warnf("Compte rendu e-commerce %s non sérialisé : %s", report.reference, e.getMessage());
            return null;
        }
    }

    /**
     * Reads back the report kept beside an order, a minimal one rebuilt from
     * the row when it cannot be read.
     *
     * @param order the order row
     * @return the report
     */
    private Report readReport(EcommerceOrder order) {
        try {
            return objectMapper.readValue(order.report, Report.class);
        } catch (Exception e) {
            Report report = new Report();
            report.reference = order.reference;
            report.status = order.status.name();
            report.ticketNumber = order.ticketNumber;
            report.total = order.totalAmount;
            report.message = order.message;
            return report;
        }
    }

    /**
     * The order the node already received under a reference.
     *
     * @param reference the reference
     * @return the row, or null
     */
    EcommerceOrder existing(String reference) {
        return EcommerceOrder.findByReference(reference);
    }

    /**
     * The ticket a number names on this node.
     *
     * @param ticketNumber the number, possibly null
     * @return the ticket, or null
     */
    Ticket bookedTicket(String ticketNumber) {
        return ticketNumber == null ? null
                : Ticket.<Ticket>find("ticketNumber", ticketNumber.trim()).firstResult();
    }

    /**
     * The article of the referential an EAN names.
     *
     * @param ean the EAN
     * @return the article, or null when unknown
     */
    Product product(String ean) {
        return Product.findByEan(ean);
    }

    /**
     * The current price of an article.
     *
     * @param product the article
     * @return the price, or null when it has none
     */
    Price price(Product product) {
        return Price.findCurrentPrice(product.id);
    }

    /**
     * Whether the node knows an employee by login.
     *
     * @param login the login
     * @return true when known
     */
    boolean operatorKnown(String login) {
        return !login.isEmpty()
                && Employee.find("loginName", login.toLowerCase(Locale.ROOT)).firstResult() != null;
    }

    /**
     * Whether the node holds a point of sale to book on.
     *
     * @return true when one exists
     */
    boolean storeKnown() {
        return Store.findAll().firstResult() != null;
    }

    /**
     * The declared system of a code, read in the integration's transaction so
     * its counters are written with the order.
     *
     * @param code the system's code
     * @return the system, or null when unknown
     */
    ThirdPartySystem system(String code) {
        return ThirdPartySystem.findByCode(code);
    }

    /**
     * Whether the node holds a point of sale under a code (BO-07-01-02).
     *
     * @param code the point of sale's code
     * @return true when known
     */
    boolean storeCodeKnown(String code) {
        return Store.find("code", code).firstResult() != null;
    }

    /**
     * Whether the node knows an island.
     *
     * @param code the island code
     * @return true when known
     */
    boolean islandKnown(String code) {
        return CheckoutIsland.findByCode(code) != null;
    }

    /**
     * The checkout point of a register.
     *
     * @param terminal the register
     * @return the point, or null when undeclared
     */
    CheckoutPoint point(String terminal) {
        return CheckoutPoint.findByTerminal(terminal);
    }

    /**
     * Persists a new checkout point.
     *
     * @param point the point
     */
    void savePoint(CheckoutPoint point) {
        point.persist();
    }

    /**
     * Persists an order row.
     *
     * @param order the row
     */
    void save(EcommerceOrder order) {
        order.persist();
    }

    /** An e-commerce ticket as a register takes it back (BO-10-01-04, -05). */
    public static class TicketView {

        /** The ticket number. */
        public String ticketNumber;

        /** The virtual register it was booked on. */
        public String terminalId;

        /** When it was opened, ISO-8601, or null. */
        public String creationDate;

        /** When it was closed, ISO-8601, or null. */
        public String closingDate;

        /** Its total, TTC. */
        public BigDecimal totalIncludingTax;

        /** Its total, HT. */
        public BigDecimal totalExcludingTax;

        /** Its VAT. */
        public BigDecimal totalVat;

        /** The customer's NIF it states (BO-07-01-07). */
        public String customerTaxId;

        /** Its lines. */
        public List<TicketViewLine> lines = new ArrayList<>();
    }

    /** One line of an e-commerce ticket as a register takes it back. */
    public static class TicketViewLine {

        /** The rank of the line. */
        public int lineNumber;

        /** The stable identity of the line. */
        public String lineUid;

        /** The EAN booked. */
        public String ean;

        /** The label booked. */
        public String label;

        /** The quantity. */
        public BigDecimal quantity;

        /** The unit price, TTC. */
        public BigDecimal unitPrice;

        /** The VAT rate. */
        public BigDecimal vatRate;

        /** The line total, TTC. */
        public BigDecimal totalPrice;
    }

    /** A validated order of the e-commerce platform. */
    public static class OrderDto {

        /** The platform's order reference, the idempotency key. */
        public String reference;

        /** When the order was validated, ISO-8601, or null for now. */
        public String orderDate;

        /** The loyalty card of the customer, or null. */
        public String customerNumber;

        /** The customer's e-mail, or null. */
        public String customerEmail;

        /** The channel the order comes from, ECOMMERCE or MOBILITY, ECOMMERCE when absent (BO-07-01-01). */
        public String channel;

        /** The point of sale of origin, or null for this node's store (BO-07-01-02). */
        public String pdvNumber;

        /** The customer's NIF, or null for the default one (BO-07-01-07, -08). */
        public String customerTaxId;

        /** The fiscal document the platform issued itself, or null (BO-07-01-06). */
        public EcommerceFiscalService.InvoiceDto invoice;

        /** The platform's identifier of the basket the order was built in, or null (DG-01-01-06). */
        public String basketId;

        /**
         * The platform's FINAL basket, or null (DG-01-01-06): the node has the
         * engine value it closed, books the order from that valuation and
         * declares its consumption; the basket is the platform's, never kept.
         */
        public com.fasterxml.jackson.databind.JsonNode basket;

        /** The article lines, never empty. */
        public List<OrderLineDto> lines = new ArrayList<>();

        /** The payments, possibly empty. */
        public List<OrderPaymentDto> payments = new ArrayList<>();
    }

    /** One article line of an order. */
    public static class OrderLineDto {

        /** The article EAN of the platform's referential. */
        public String ean;

        /** The article label. */
        public String label;

        /** The quantity, one when absent. */
        public BigDecimal quantity;

        /** The unit price of the order, TTC. */
        public BigDecimal unitPrice;

        /** The VAT rate (0.20 for 20 %). */
        public BigDecimal vatRate;
    }

    /** One payment of an order. */
    public static class OrderPaymentDto {

        /** The platform's payment means (CARD, PAYPAL...). */
        public String method;

        /** The amount, TTC. */
        public BigDecimal amount;
    }

    /** The integration report answered to the platform (BO-10-01-03). */
    public static class Report {

        /** The order was booked. */
        public static final String INTEGRATED = "INTEGRATED";

        /** The order was refused; nothing was booked. */
        public static final String REJECTED = "REJECTED";

        /** The channel is not open on this point of sale; nothing was kept. */
        public static final String DISABLED = "DISABLED";

        /** Paid short: kept as a basket the till recalls and settles (DG-03-01-05). */
        public static final String PENDING_PAYMENT = "PENDING_PAYMENT";

        /** The platform reference. */
        public String reference;

        /** INTEGRATED, REJECTED or DISABLED. */
        public String status;

        /** The ticket the order was booked as, or null. */
        public String ticketNumber;

        /** The total booked, TTC, or null. */
        public BigDecimal total;

        /** The outcome in words. */
        public String message;

        /** Whether this answer repeats the one of an earlier call. */
        public boolean alreadyReceived;

        /** How many lines carried an unknown article. */
        public int unknownLines;

        /** How many lines carried an article forbidden to sale. */
        public int forbiddenLines;

        /** The outcome of each line. */
        public List<ReportLine> lines = new ArrayList<>();

        /** The outcome of each payment. */
        public List<ReportPayment> payments = new ArrayList<>();

        /** What the integration noted without refusing. */
        public List<String> warnings = new ArrayList<>();

        /** The channel the order was booked under (BO-07-01-01). */
        public String channel;

        /** The third-party system that deposited it, or null (BO-07-01-01). */
        public String systemCode;

        /** The point of sale of origin, or null for this node's (BO-07-01-02). */
        public String pdvNumber;

        /** The customer's NIF the transaction states (BO-07-01-07). */
        public String customerTaxId;

        /** Whether the default NIF applied, the order sending none (BO-07-01-08). */
        public boolean customerTaxIdDefaulted;

        /** Whether the loyalty earn was queued (BO-07-01-03). */
        public boolean fidelityQueued;

        /** The fiscal document stating the order, or null (BO-07-01-06). */
        public String fiscalDocument;

        /** The platform's basket identifier, or null (DG-01-01-06). */
        public String basketId;

        /** Whether the order was booked valued by the engine (DG-01-01-06). */
        public boolean valuated;

        /** Whether the engine took the SALE consumption of the basket (DG-01-01-06). */
        public boolean consumptionDeclared;

        /**
         * The closing snapshot — final basket and evaluation — handed back to
         * the platform on the integrating call only, never stored (DG-01-03).
         */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public Object snapshot;

        /** The coupon codes the basket's close consumed (DG-01-01-06). */
        public List<String> consumedCoupons = new ArrayList<>();

        /** The code the till recalls an order paid short by, or null (DG-03-01-05). */
        public String recallCode;

        /** What remains due at the till on an order paid short, or null (DG-03-01-05). */
        public BigDecimal remaining;
    }

    /** The outcome of one line of an order. */
    public static class ReportLine {

        /** The article is known and booked. */
        public static final String OK = "OK";

        /** The article is unknown; booked, carried on the generic EAN when one is administered. */
        public static final String UNKNOWN = "UNKNOWN";

        /** The article is forbidden to sale. */
        public static final String FORBIDDEN = "FORBIDDEN";

        /** The rank of the line in the order. */
        public int lineNumber;

        /** The EAN the order stated. */
        public String ean;

        /** The label the order stated. */
        public String label;

        /** OK, UNKNOWN or FORBIDDEN. */
        public String outcome;

        /** The generic EAN the line was carried on, or null. */
        public String carriedOn;

        /** The unit price booked, TTC. */
        public BigDecimal unitPrice;
    }

    /** The outcome of one payment of an order. */
    public static class ReportPayment {

        /** The means the order stated, empty when it stated none. */
        public String method;

        /** The tender it was booked under. */
        public String integratedAs;

        /** The amount, TTC. */
        public BigDecimal amount;
    }
}
