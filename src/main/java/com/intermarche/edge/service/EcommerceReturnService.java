package com.intermarche.edge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.sale.EcommerceOrder;
import com.intermarche.edge.domain.sale.EcommerceReturn;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.sale.SalesChannel;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.setting.PosList;
import com.intermarche.edge.domain.setting.PosListItem;
import com.intermarche.edge.domain.sync.FidEvent;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RECORDS THE RETURNS of the e-commerce platform at the point of sale
 * (DG-01-03-01 to -08): the platform states the articles its customer
 * returned, the reason and the return type; the node checks them under the
 * store's rules, gets each line's amount from the engine's stateless return
 * valuation — fed with the original order's closing snapshot the platform
 * kept and sends back, or with each line's price source — or, for a line
 * priced from its order without snapshot, from the booked ticket, and books
 * the refund on the e-commerce virtual register. The node keeps no basket.
 *
 * <ul>
 *   <li>the reason must be one the store administers: its
 *       {@code RETURN_REASON} list, else the {@code return.reasons} setting,
 *       exactly as the register's return does (DG-01-03-02, -05);</li>
 *   <li>the return type must be one the channel allows for a whole-ticket or
 *       an article return (BO-10-01-04, -05);</li>
 *   <li>a return naming its order is booked as a {@link Refund} of the
 *       order's ticket, through the very ingestion a register's refund takes,
 *       so the stream publishes it and the loyalty programme is told
 *       ({@code TICKET_RETURN}); a return naming none, where the store accepts
 *       returns without origin, is booked as a return ticket of the virtual
 *       register;</li>
 *   <li>idempotent by return reference: a return already recorded is
 *       answered again, never refunded twice.</li>
 * </ul>
 */
@ApplicationScoped
public class EcommerceReturnService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EcommerceReturnService.class);

    /** The price sources a returned line may name. */
    static final List<String> SOURCES = List.of("ORDER", "DATE", "PUSHED", "CURRENT");

    /** The return type scope of a whole-ticket return (BO-10-01-04). */
    static final String SCOPE_TICKET = "TICKET";

    /** The return type scope of an article return (BO-10-01-05). */
    static final String SCOPE_ARTICLE = "ARTICLE";

    /** The longest refund number the refund table holds. */
    static final int NUMBER_LENGTH = 30;

    /** The store's return and channel rules. */
    @Inject
    PosSettingsService posSettingsService;

    /** The engine's return valuation. */
    @Inject
    EcommerceBasketService ecommerceBasketService;

    /** Books the refund exactly as a register's push would be. */
    @Inject
    SyncIngestService syncIngestService;

    /** Queues the loyalty return event. */
    @Inject
    FidEventOutboxService fidEventOutboxService;

    /** The payment factories, whose keys are the tenders the encashment knows. */
    @Inject
    Instance<TicketPayment.Factory> paymentFactories;

    /** Reads and writes the bodies and the kept report. */
    @Inject
    ObjectMapper objectMapper;

    /** The clock the return is dated by. */
    Clock clock = Clock.systemDefaultZone();

    /**
     * What the node answers the platform.
     *
     * @param status the HTTP status
     * @param body the return report, or the refusal in words
     */
    public record Outcome(int status, Object body) {
    }

    /**
     * A reason of the administered list.
     *
     * @param code its code
     * @param label its label
     */
    record Reason(String code, String label) {
    }

    /**
     * One returned line, priced.
     *
     * @param request the line as the platform stated it
     * @param original the ticket line it returns, or null without origin
     * @param unitPrice the reference unit price, TTC
     * @param amount the refunded amount, TTC, positive
     * @param vatRate the VAT rate
     */
    record Priced(ReturnLineDto request, TicketLine original, BigDecimal unitPrice, BigDecimal amount,
                  BigDecimal vatRate) {
    }

    /**
     * Records one return of the platform and answers its report.
     *
     * @param request the return, already read
     * @param payload the return as the platform sent it, for the archive
     * @return 200 with the report, 400 on a refused return, 404 on an unknown
     *     order or ticket, the engine's refusal when it cannot value the return
     */
    @Transactional
    public Outcome record(ReturnDto request, String payload) {
        LOGGER.info("Entering method record with reference: " + request.returnReference);
        String invalid = invalid(request);
        if (invalid != null) {
            LOGGER.info("Exiting method record: invalid");
            return new Outcome(400, invalid);
        }
        EcommerceReturn known = existing(request.returnReference.trim());
        if (known != null) {
            LOGGER.info("Exiting method record: already received");
            return new Outcome(200, readReport(known));
        }
        Reason reason = reason(request.reasonCode);
        if (reason == null) {
            LOGGER.info("Exiting method record: reason off the list");
            return new Outcome(400, "Motif de retour hors liste : " + String.valueOf(request.reasonCode).trim());
        }
        Ticket original = null;
        EcommerceOrder order = null;
        if (request.orderReference != null && !request.orderReference.isBlank()) {
            order = integration(request.orderReference.trim());
            original = order == null ? null : ticket(order.ticketNumber);
        } else if (request.ticketNumber != null && !request.ticketNumber.isBlank()) {
            Ticket named = ticket(request.ticketNumber.trim());
            original = named != null && terminal().equals(named.terminalId) ? named : null;
        }
        boolean named = request.orderReference != null && !request.orderReference.isBlank()
                || request.ticketNumber != null && !request.ticketNumber.isBlank();
        if (named && original == null) {
            LOGGER.info("Exiting method record: unknown origin");
            return new Outcome(404, "Commande ou ticket d'origine inconnu");
        }
        if (original == null && !posSettingsService.returnWithoutOriginAllowed()) {
            LOGGER.info("Exiting method record: no origin");
            return new Outcome(400, "Retour sans commande d'origine non autorisé");
        }
        Map<String, TicketLine> matched = new HashMap<>();
        boolean snapshot = request.orderSnapshot != null && request.orderSnapshot.isObject();
        String mismatch = original != null ? match(request, original, matched) : snapshot ? null : noOrderSource(request);
        if (mismatch != null) {
            LOGGER.info("Exiting method record: lines refused");
            return new Outcome(400, mismatch);
        }
        String scope = original != null && whole(request, original, matched) ? SCOPE_TICKET : SCOPE_ARTICLE;
        if (!typeAllowed(request.returnType, scope)) {
            LOGGER.info("Exiting method record: return type refused");
            return new Outcome(400, "Type de retour non autorisé : " + String.valueOf(request.returnType).trim());
        }
        List<Priced> priced = new ArrayList<>();
        ArrayNode takenBack = objectMapper.createArrayNode();
        Outcome refused = price(request, original, order, matched, scope, priced, takenBack);
        if (refused != null) {
            LOGGER.info("Exiting method record: not valued");
            return refused;
        }
        ReturnReport report = book(request, original, order, priced, reason, scope);
        cancel(report, order, takenBack);
        save(row(request, report, payload));
        LOGGER.info("Exiting method record: booked as " + report.refundNumber);
        return new Outcome(200, report);
    }

    /**
     * Declares to the engine the CANCELLATION of the advantages the return
     * takes back (spec §5.4), under the return reference and the point of
     * sale the order's SALE was declared for; a declaration the engine does
     * not take is reported, never undoes the refund.
     *
     * @param report the report, completed in place
     * @param order the order returned, or null
     * @param takenBack the advantages the engine's return valuation took back
     */
    void cancel(ReturnReport report, EcommerceOrder order, ArrayNode takenBack) {
        if (takenBack.isEmpty()) {
            return;
        }
        String storeCode = order != null && order.pdvNumber != null && !order.pdvNumber.isBlank()
                ? order.pdvNumber.trim() : posSettingsService.pdvNumber();
        int status = ecommerceBasketService.declareCancellation(report.returnReference, storeCode,
                LocalDateTime.now(clock).toString(), takenBack);
        report.consumptionCancelled = status >= 200 && status < 300 && status != 204;
        if (status < 200 || status >= 300) {
            report.warnings.add("Annulation de consommation des offres non déclarée (HTTP " + status + ")");
        }
    }

    /**
     * Answers again the report of a return the node recorded.
     *
     * @param reference the platform's return reference
     * @return the report, or null when the node never recorded it
     */
    public ReturnReport reportOf(String reference) {
        EcommerceReturn known = existing(reference);
        return known == null ? null : readReport(known);
    }

    /**
     * Why a return cannot be read as sent: no reference or one that cannot
     * name a refund, no line, a line without article, a quantity that is not
     * strictly positive, a price source the engine does not know.
     *
     * @param request the return
     * @return the reason, or null when readable
     */
    String invalid(ReturnDto request) {
        if (request.returnReference == null
                || !request.returnReference.trim().matches(EcommerceIntegrationService.REFERENCE_PATTERN)) {
            return "Référence de retour illisible";
        }
        if (request.lines == null || request.lines.isEmpty() || request.lines.contains(null)) {
            return "Retour sans ligne";
        }
        for (ReturnLineDto line : request.lines) {
            if (line.ean == null || line.ean.isBlank() || line.quantity == null || line.quantity.signum() <= 0) {
                return "Ligne de retour illisible : article et quantité positive attendus";
            }
            line.priceSource = line.priceSource == null || line.priceSource.isBlank() ? "ORDER"
                    : line.priceSource.trim().toUpperCase(Locale.ROOT);
            if (!SOURCES.contains(line.priceSource)) {
                return "Source de prix inconnue : " + line.priceSource;
            }
        }
        return null;
    }

    /**
     * The administered reason a code names (DG-01-03-02, -05): the
     * {@code RETURN_REASON} list when the store distributes one, the
     * {@code return.reasons} setting otherwise, matched on the code or the
     * label. An empty list asks for no reason, and a blank code then passes.
     *
     * @param code the reason code the platform sent, possibly null
     * @return the reason; an empty one when none is asked; null when the code
     *     is off the list
     */
    Reason reason(String code) {
        String typed = code == null ? "" : code.trim();
        List<Reason> reasons = reasons();
        if (reasons.isEmpty() && typed.isEmpty()) {
            return new Reason(null, null);
        }
        for (Reason reason : reasons) {
            if (reason.code().equalsIgnoreCase(typed) || reason.label().equalsIgnoreCase(typed)) {
                return reason;
            }
        }
        return null;
    }

    /**
     * The administered return reasons, as the register reads them.
     *
     * @return the reasons, possibly empty
     */
    List<Reason> reasons() {
        List<Reason> reasons = new ArrayList<>();
        List<PosList> lists = reasonLists();
        if (!lists.isEmpty()) {
            for (PosList list : lists) {
                for (PosListItem item : list.items) {
                    if (item.active) {
                        reasons.add(new Reason(item.code, item.label));
                    }
                }
            }
            return reasons;
        }
        for (String reason : posSettingsService.returnReasons()) {
            reasons.add(new Reason(reason, reason));
        }
        return reasons;
    }

    /**
     * Refuses a line priced from its order on a return that names none.
     *
     * @param request the return
     * @return the refusal, or null
     */
    private static String noOrderSource(ReturnDto request) {
        for (ReturnLineDto line : request.lines) {
            if ("ORDER".equals(line.priceSource)) {
                return "Prix de la commande demandé sans commande d'origine : " + line.ean.trim();
            }
        }
        return null;
    }

    /**
     * Matches each returned line to the ticket line it returns — by its line
     * identity when given, else the first line of the same article with
     * quantity left — and refuses a quantity beyond what was sold and not yet
     * returned.
     *
     * @param request the return
     * @param original the ticket returned
     * @param matched the match by returned line, filled in place (key: rank)
     * @return the refusal, or null when every line matches
     */
    String match(ReturnDto request, Ticket original, Map<String, TicketLine> matched) {
        Map<Long, BigDecimal> left = new HashMap<>();
        int rank = 0;
        for (ReturnLineDto line : request.lines) {
            TicketLine found = null;
            for (TicketLine candidate : original.lines) {
                boolean same = line.lineUid != null && !line.lineUid.isBlank()
                        ? line.lineUid.trim().equals(candidate.lineUid)
                        : line.ean.trim().equals(candidate.ean);
                if (same && !candidate.cancelled && !candidate.returnLine
                        && left.computeIfAbsent(candidate.id, id -> returnable(candidate)).compareTo(line.quantity) >= 0) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                return "Article absent de la commande ou déjà retourné : " + line.ean.trim();
            }
            left.put(found.id, left.get(found.id).subtract(line.quantity));
            matched.put(String.valueOf(rank++), found);
        }
        return null;
    }

    /**
     * What remains returnable on a ticket line: its quantity minus what the
     * refunds already took back.
     *
     * @param line the ticket line
     * @return the returnable quantity
     */
    private BigDecimal returnable(TicketLine line) {
        BigDecimal returned = BigDecimal.ZERO;
        for (RefundLine refunded : refundedLines(line.id)) {
            returned = returned.add(refunded.quantity);
        }
        return line.quantity.subtract(returned);
    }

    /**
     * Whether a return takes back every line of its ticket in full.
     *
     * @param request the return
     * @param original the ticket returned
     * @param matched the match by returned line
     * @return true for a whole-ticket return
     */
    private boolean whole(ReturnDto request, Ticket original, Map<String, TicketLine> matched) {
        Map<Long, BigDecimal> returned = new HashMap<>();
        for (int rank = 0; rank < request.lines.size(); rank++) {
            returned.merge(matched.get(String.valueOf(rank)).id, request.lines.get(rank).quantity, BigDecimal::add);
        }
        for (TicketLine line : original.lines) {
            if (!line.cancelled && !line.returnLine
                    && returned.getOrDefault(line.id, BigDecimal.ZERO).compareTo(line.quantity) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the channel allows a return type for a whole-ticket or an
     * article return (BO-10-01-04, -05): an empty list allows every type.
     *
     * @param type the type the platform named, possibly null
     * @param scope TICKET or ARTICLE
     * @return true when allowed
     */
    boolean typeAllowed(String type, String scope) {
        String administered = SCOPE_TICKET.equals(scope) ? posSettingsService.ecommerceReturnTicketTypes()
                : posSettingsService.ecommerceReturnArticleTypes();
        if (administered.isBlank()) {
            return true;
        }
        String chosen = type == null ? "" : type.trim();
        for (String allowed : administered.split(";")) {
            if (!allowed.isBlank() && allowed.trim().equalsIgnoreCase(chosen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prices every returned line (DG-01-03-01, -03, -04, -06, -07, -08): the
     * engine values the return statelessly, from the original order's closing
     * snapshot the platform sends with it or from the price source of each
     * line; a line priced from its order when the platform sends no snapshot
     * takes the price actually paid on the booked ticket.
     *
     * @param request the return
     * @param original the ticket returned, or null
     * @param order the order returned, or null
     * @param matched the match by returned line
     * @param scope TICKET or ARTICLE
     * @param priced the priced lines, filled in place in the return's order
     * @param takenBack the advantages the engine took back, filled in place
     * @return null when every line is priced, the refusal otherwise
     */
    Outcome price(ReturnDto request, Ticket original, EcommerceOrder order, Map<String, TicketLine> matched,
                  String scope, List<Priced> priced, ArrayNode takenBack) {
        boolean snapshot = request.orderSnapshot != null && request.orderSnapshot.isObject();
        ObjectNode valuation = objectMapper.createObjectNode();
        if (order != null) {
            valuation.put("orderReference", order.reference);
        }
        if (snapshot) {
            // The original order's snapshot travels with the request: the engine
            // keeps no basket, so it values the return from what the platform kept.
            valuation.set("snapshot", request.orderSnapshot);
        }
        boolean allOrder = true;
        for (ReturnLineDto line : request.lines) {
            allOrder = allOrder && "ORDER".equals(line.priceSource);
        }
        valuation.put("mode", snapshot && allOrder ? (SCOPE_TICKET.equals(scope) ? "FULL" : "PARTIAL") : "LINES");
        ArrayNode lines = valuation.putArray("lines");
        List<Integer> asked = new ArrayList<>();
        for (int rank = 0; rank < request.lines.size(); rank++) {
            ReturnLineDto line = request.lines.get(rank);
            TicketLine found = matched.get(String.valueOf(rank));
            priced.add(null);
            if (!snapshot && "ORDER".equals(line.priceSource)) {
                BigDecimal unit = found.getEffectiveUnitPrice();
                priced.set(rank, new Priced(line, found, unit.setScale(2, RoundingMode.HALF_UP),
                        unit.multiply(line.quantity).setScale(2, RoundingMode.HALF_UP), rateOf(found)));
                continue;
            }
            asked.add(rank);
            lines.add(engineLine(line, found));
        }
        if (asked.isEmpty()) {
            return null;
        }
        // Successive partial returns of the same order: the engine keeps no basket, so it must be
        // told the quantities already taken back by earlier returns, keyed by the snapshot line.
        // Without this the kept-basket re-valuation and the remaining-to-return guard behave as if
        // nothing had been returned before. Only meaningful against a snapshot (FULL/PARTIAL), where
        // the engine owns the order's origin lines; LINES mode has no origin to subtract from.
        if (snapshot) {
            ObjectNode previousReturns = previousReturns(matched);
            if (previousReturns != null) {
                valuation.set("previousReturns", previousReturns);
            }
        }
        EcommerceBasketService.Outcome valued = ecommerceBasketService.valueReturn(valuation.toString(), null);
        if (valued.status() < 200 || valued.status() >= 300) {
            return new Outcome(valued.status() >= 500 ? 502 : valued.status(),
                    "Valorisation du retour refusée par le moteur (HTTP " + valued.status() + ") : " + valued.body());
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(valued.body());
        } catch (Exception e) {
            root = objectMapper.createObjectNode();
        }
        JsonNode answer = root.path("lines");
        if (answer.size() < asked.size()) {
            return new Outcome(502, "Valorisation du retour illisible");
        }
        for (JsonNode advantage : root.path("advantagesTakenBack")) {
            takenBack.add(advantage);
        }
        Map<String, Deque<JsonNode>> byLine = byLine(answer);
        for (int index = 0; index < asked.size(); index++) {
            int rank = asked.get(index);
            Deque<JsonNode> same = byLine.get(lines.get(index).path("lineId").asText(""));
            JsonNode value = same != null && !same.isEmpty() ? same.poll() : answer.get(index);
            ReturnLineDto line = request.lines.get(rank);
            TicketLine found = matched.get(String.valueOf(rank));
            BigDecimal amount = first(value, "amount", "amountIncludingTax", "refundAmount");
            BigDecimal unit = first(value, "referencePrice", "unitPrice", "pricePerUnitInclTax");
            BigDecimal rate = first(value, "vatRate");
            BigDecimal refunded = (amount == null ? BigDecimal.ZERO : amount.abs()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal reference = unit != null ? unit.abs().setScale(2, RoundingMode.HALF_UP)
                    : refunded.divide(line.quantity, 2, RoundingMode.HALF_UP);
            BigDecimal vat = rate != null ? rate : line.vatRate != null ? line.vatRate : rateOf(found);
            priced.set(rank, new Priced(line, found, reference, refunded, vat));
        }
        return null;
    }

    /**
     * The engine's valued lines by the order line they value: a full return
     * answers every line of the order in the order's own sequence, not in the
     * return's, so a line is matched on its identifier before its rank.
     *
     * @param answer the engine's valued lines
     * @return the lines naming an order line, by identifier, in answer order
     */
    private static Map<String, Deque<JsonNode>> byLine(JsonNode answer) {
        Map<String, Deque<JsonNode>> byLine = new HashMap<>();
        for (JsonNode value : answer) {
            String lineId = value.path("lineId").asText("");
            if (!lineId.isEmpty()) {
                byLine.computeIfAbsent(lineId, id -> new ArrayDeque<>()).add(value);
            }
        }
        return byLine;
    }

    /**
     * The VAT rate of a ticket line, zero when there is none.
     *
     * @param line the ticket line, or null
     * @return the rate
     */
    private static BigDecimal rateOf(TicketLine line) {
        return line == null || line.vatRate == null ? BigDecimal.ZERO : line.vatRate;
    }

    /**
     * The quantities earlier returns already took back, keyed by the snapshot line the engine
     * values against (the ticket line's {@code lineUid}), so a successive partial return is valued
     * on the remaining basket and refused when it exceeds what is left. The current return's own
     * lines are excluded: they are not yet booked as {@link RefundLine} when this runs, and the
     * engine adds them itself. Distinct matched ticket lines are counted once.
     *
     * @param matched the match by returned line
     * @return the {@code previousReturns} node, or null when no earlier return exists
     */
    private ObjectNode previousReturns(Map<String, TicketLine> matched) {
        Map<String, BigDecimal> returned = new HashMap<>();
        Set<Long> counted = new HashSet<>();
        for (TicketLine found : matched.values()) {
            if (found.lineUid == null || found.lineUid.isBlank() || !counted.add(found.id)) {
                continue;
            }
            BigDecimal prior = BigDecimal.ZERO;
            for (RefundLine refunded : refundedLines(found.id)) {
                prior = prior.add(refunded.quantity);
            }
            if (prior.signum() > 0) {
                returned.merge(found.lineUid, prior, BigDecimal::add);
            }
        }
        if (returned.isEmpty()) {
            return null;
        }
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode map = node.putObject("returned");
        for (Map.Entry<String, BigDecimal> entry : returned.entrySet()) {
            map.put(entry.getKey(), entry.getValue());
        }
        return node;
    }

    /**
     * The engine's line of a returned line.
     *
     * @param line the returned line
     * @param found the ticket line it returns, or null
     * @return the request line
     */
    private ObjectNode engineLine(ReturnLineDto line, TicketLine found) {
        ObjectNode node = objectMapper.createObjectNode();
        if (found != null) {
            node.put("lineId", found.lineUid);
        } else if (line.lineUid != null && !line.lineUid.isBlank()) {
            node.put("lineId", line.lineUid.trim());
        }
        node.put("produceEan", line.ean.trim());
        node.put("quantity", line.quantity);
        node.put("priceSource", line.priceSource);
        if (line.priceDate != null && !line.priceDate.isBlank()) {
            node.put("priceDate", line.priceDate.trim());
        }
        if (line.unitPrice != null && line.vatRate != null) {
            node.put("pricePerUnitInclTax", line.unitPrice);
            node.put("pricePerUnitExclTax", line.unitPrice.divide(BigDecimal.ONE.add(line.vatRate), 4, RoundingMode.HALF_UP));
            node.put("vatRate", line.vatRate);
        }
        return node;
    }

    /**
     * Reads the first numeric field among several names.
     *
     * @param node the object
     * @param names the field names, in order of preference
     * @return the number, or null when none is numeric
     */
    private static BigDecimal first(JsonNode node, String... names) {
        for (String name : names) {
            BigDecimal value = EcommerceSnapshot.decimal(node.path(name));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Books the return at the point of sale and composes its report.
     *
     * @param request the return
     * @param original the ticket returned, or null
     * @param order the order returned, or null
     * @param priced the priced lines
     * @param reason the administered reason
     * @param scope TICKET or ARTICLE
     * @return the report
     */
    private ReturnReport book(ReturnDto request, Ticket original, EcommerceOrder order, List<Priced> priced,
                              Reason reason, String scope) {
        ReturnReport report = new ReturnReport();
        report.returnReference = request.returnReference.trim();
        report.status = ReturnReport.RECORDED;
        report.orderReference = order == null ? null : order.reference;
        report.originalTicketNumber = original == null ? null : original.ticketNumber;
        report.reasonCode = reason.code();
        report.reasonLabel = reason.label();
        report.returnType = request.returnType == null ? null : request.returnType.trim();
        report.returnScope = scope;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        int number = 0;
        for (Priced line : priced) {
            number++;
            ReturnReportLine row = new ReturnReportLine();
            row.lineNumber = number;
            row.ean = line.request().ean.trim();
            row.lineUid = line.original() == null ? null : line.original().lineUid;
            row.quantity = line.request().quantity;
            row.priceSource = line.request().priceSource;
            row.unitPrice = line.unitPrice();
            row.amount = line.amount();
            report.lines.add(row);
            total = total.add(line.amount());
            vat = vat.add(line.amount().subtract(line.amount().divide(BigDecimal.ONE.add(line.vatRate()), 2,
                    RoundingMode.HALF_UP)));
        }
        report.total = total;
        refunds(request, report);
        String number30 = number(terminal() + "-R-" + report.returnReference);
        report.refundNumber = number30;
        if (original != null) {
            syncIngestService.ingestRefund(refund(report, original, priced, total, vat));
            report.fidelityQueued = returnEvent(report, original, priced);
        } else {
            syncIngestService.ingestTicket(returnTicket(report, priced, total, vat));
        }
        return report;
    }

    /**
     * Reads the refunds the platform states, each under the tender the
     * encashment knows it by, the default tender otherwise; none stated reads
     * as the whole return refunded under the default tender. A total that
     * differs from the return's is reported, not refused.
     *
     * @param request the return
     * @param report the report, completed in place
     */
    private void refunds(ReturnDto request, ReturnReport report) {
        Set<String> known = knownTenders();
        String fallback = posSettingsService.ecommerceDefaultTender().trim().toUpperCase(Locale.ROOT);
        BigDecimal stated = BigDecimal.ZERO;
        for (RefundDto refund : request.refunds == null ? List.<RefundDto>of() : request.refunds) {
            if (refund == null || refund.amount == null || refund.amount.signum() <= 0) {
                continue;
            }
            String method = refund.method == null ? "" : refund.method.trim().toUpperCase(Locale.ROOT);
            report.refunds.add(new ReturnRefund(method, known.contains(method) ? method : fallback, refund.amount));
            stated = stated.add(refund.amount);
        }
        if (report.refunds.isEmpty()) {
            report.refunds.add(new ReturnRefund("", fallback, report.total));
            stated = report.total;
        }
        if (stated.compareTo(report.total) != 0) {
            report.warnings.add("Remboursements annoncés " + stated + " différents du montant du retour " + report.total);
        }
    }

    /**
     * The refund a return naming its ticket is booked as.
     *
     * @param report the report
     * @param original the ticket returned
     * @param priced the priced lines
     * @param total the refunded total, TTC
     * @param vat the refunded VAT
     * @return the refund, in the register's push shape
     */
    private SyncPayloads.RefundDto refund(ReturnReport report, Ticket original, List<Priced> priced,
                                          BigDecimal total, BigDecimal vat) {
        SyncPayloads.RefundDto refund = new SyncPayloads.RefundDto();
        refund.refundNumber = report.refundNumber;
        refund.terminalId = original.terminalId;
        refund.status = Refund.RefundStatus.CLOSED.name();
        refund.refundMethod = refundMethod(report.refunds.get(0).integratedAs());
        refund.originalTicketNumber = original.ticketNumber;
        refund.creationDate = LocalDateTime.now(clock).toString();
        refund.totalAmount = total;
        refund.totalVat = vat;
        refund.totalExcludingTax = total.subtract(vat);
        refund.returnReason = report.reasonLabel;
        refund.returnReasonCode = report.reasonCode;
        refund.returnTypeCode = report.returnScope;
        refund.returnTypeLabel = report.returnType;
        for (Priced line : priced) {
            SyncPayloads.RefundLineDto row = new SyncPayloads.RefundLineDto();
            row.originalLineUid = line.original().lineUid;
            row.productLabel = line.original().productLabel == null ? line.request().ean.trim()
                    : line.original().productLabel;
            row.quantity = line.request().quantity;
            row.price = line.amount().divide(line.request().quantity, 4, RoundingMode.HALF_UP);
            row.vatRate = line.vatRate();
            refund.lines.add(row);
        }
        return refund;
    }

    /**
     * The refund method a tender names, CARD when it names none of them.
     *
     * @param tender the tender
     * @return the refund method name
     */
    static String refundMethod(String tender) {
        for (Refund.RefundMethod method : Refund.RefundMethod.values()) {
            if (method.name().equals(tender)) {
                return method.name();
            }
        }
        return Refund.RefundMethod.CARD.name();
    }

    /**
     * The return ticket a return naming no order is booked as: its lines
     * taken back, negative, and its refunds paid out, negative.
     *
     * @param report the report
     * @param priced the priced lines
     * @param total the refunded total, TTC
     * @param vat the refunded VAT
     * @return the ticket, in the register's push shape
     */
    private SyncPayloads.TicketDto returnTicket(ReturnReport report, List<Priced> priced, BigDecimal total,
                                                BigDecimal vat) {
        SyncPayloads.TicketDto ticket = new SyncPayloads.TicketDto();
        String date = LocalDateTime.now(clock).toString();
        ticket.ticketNumber = report.refundNumber;
        ticket.terminalId = terminal();
        ticket.status = Ticket.TicketStatus.CLOSED.name();
        ticket.creationDate = date;
        ticket.closingDate = date;
        ticket.cashierLogin = posSettingsService.ecommerceOperator();
        ticket.salesChannel = SalesChannel.ECOMMERCE.name();
        ticket.valuationStatus = "NOT_VALUATED";
        int number = 0;
        for (Priced line : priced) {
            number++;
            SyncPayloads.LineDto row = new SyncPayloads.LineDto();
            row.lineNumber = number;
            row.lineUid = java.util.UUID.randomUUID().toString();
            row.ean = line.request().ean.trim();
            row.productLabel = label(row.ean);
            row.quantity = line.request().quantity.negate();
            row.unitPrice = line.unitPrice();
            row.vatRate = line.vatRate();
            row.totalPrice = line.amount().negate();
            row.returnLine = true;
            row.returnTypeCode = report.returnScope;
            row.returnTypeLabel = report.returnType;
            ticket.lines.add(row);
        }
        int index = 0;
        for (ReturnRefund refund : report.refunds) {
            SyncPayloads.PaymentDto payment = new SyncPayloads.PaymentDto();
            payment.paymentIndex = index++;
            payment.methodKey = refund.integratedAs();
            payment.amount = refund.amount().negate();
            payment.tenderedAmount = payment.amount;
            payment.tenderCode = refund.method().isEmpty() ? null : refund.method();
            ticket.payments.add(payment);
        }
        ticket.itemCount = ticket.lines.size();
        ticket.totalIncludingTax = total.negate();
        ticket.totalVat = vat.negate();
        ticket.totalExcludingTax = total.subtract(vat).negate();
        return ticket;
    }

    /**
     * Queues the loyalty return event of a return on a card-bearing ticket,
     * in the shape the register sends (the origin's line identities, the
     * quantities taken back): the programme takes back the earn the
     * returned lines brought.
     *
     * @param report the report, warned when the event cannot be composed
     * @param original the ticket returned
     * @param priced the priced lines
     * @return true when the event was queued
     */
    private boolean returnEvent(ReturnReport report, Ticket original, List<Priced> priced) {
        if (original.fidelityCard == null || original.fidelityCard.isBlank()) {
            return false;
        }
        LocalDate day = LocalDate.now(clock);
        String origin = day.getYear() + "-" + original.ticketNumber;
        ObjectNode event = objectMapper.createObjectNode()
                .put("returnTicketRef", origin + "-R" + report.returnReference)
                .put("originTicketRef", origin)
                .put("fiscalDate", day.toString());
        ArrayNode lines = event.putArray("lines");
        for (Priced line : priced) {
            lines.addObject().put("lineId", line.original().lineUid).put("quantity", line.request().quantity);
        }
        fidEventOutboxService.enqueue(FidEvent.EventType.TICKET_RETURN, event.toString());
        return true;
    }

    /**
     * The row kept for a recorded return.
     *
     * @param request the return
     * @param report its report
     * @param payload the return as sent
     * @return the row to persist
     */
    private EcommerceReturn row(ReturnDto request, ReturnReport report, String payload) {
        EcommerceReturn row = new EcommerceReturn();
        row.reference = report.returnReference;
        row.receivedAt = LocalDateTime.now(clock);
        row.orderReference = report.orderReference;
        row.originalTicketNumber = report.originalTicketNumber;
        row.refundNumber = report.refundNumber;
        row.totalAmount = report.total;
        row.reasonCode = report.reasonCode;
        row.reasonLabel = report.reasonLabel;
        row.returnType = report.returnType;
        row.message = "Retour enregistré";
        row.report = writeReport(report);
        row.originalPayload = posSettingsService.ecommerceArchive() ? payload : null;
        return row;
    }

    /**
     * Writes a report as JSON.
     *
     * @param report the report
     * @return the JSON text, or null when it cannot be written
     */
    private String writeReport(ReturnReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads back the report of a recorded return, marked as a repeated
     * answer; a minimal one rebuilt from the row when it cannot be read.
     *
     * @param known the row
     * @return the report
     */
    private ReturnReport readReport(EcommerceReturn known) {
        ReturnReport report;
        try {
            report = objectMapper.readValue(known.report, ReturnReport.class);
        } catch (Exception e) {
            report = new ReturnReport();
            report.returnReference = known.reference;
            report.status = ReturnReport.RECORDED;
            report.refundNumber = known.refundNumber;
            report.total = known.totalAmount;
        }
        report.alreadyReceived = true;
        return report;
    }

    /**
     * A number fitting the refund and ticket tables, its end kept.
     *
     * @param number the full number
     * @return the number, at most 30 characters
     */
    static String number(String number) {
        return number.length() > NUMBER_LENGTH ? number.substring(number.length() - NUMBER_LENGTH) : number;
    }

    /**
     * The administered e-commerce virtual register, "ECOM" when none is.
     *
     * @return the terminal identifier
     */
    private String terminal() {
        String terminal = posSettingsService.ecommerceTerminal();
        return terminal.isEmpty() ? "ECOM" : terminal;
    }

    /**
     * The tenders the encashment can book.
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
     * The label of an article, its EAN when the referential does not know it.
     *
     * @param ean the EAN
     * @return the label
     */
    String label(String ean) {
        com.intermarche.edge.domain.catalog.Product product = com.intermarche.edge.domain.catalog.Product.findByEan(ean);
        return product == null || product.name == null ? ean : product.name;
    }

    /**
     * The return recorded under a reference.
     *
     * @param reference the reference
     * @return the row, or null
     */
    EcommerceReturn existing(String reference) {
        return EcommerceReturn.findByReference(reference);
    }

    /**
     * The integration row of an order reference.
     *
     * @param reference the reference
     * @return the row, or null
     */
    EcommerceOrder integration(String reference) {
        return EcommerceOrder.findByReference(reference);
    }

    /**
     * The ticket a number names.
     *
     * @param number the number, possibly null
     * @return the ticket, or null
     */
    Ticket ticket(String number) {
        return number == null ? null : Ticket.<Ticket>find("ticketNumber", number).firstResult();
    }

    /**
     * The refund lines already taken back on a ticket line.
     *
     * @param lineId the ticket line's id
     * @return the refund lines
     */
    List<RefundLine> refundedLines(Long lineId) {
        return RefundLine.list("originalLineId", lineId);
    }

    /**
     * The active return-reason lists the store distributes.
     *
     * @return the lists, possibly empty
     */
    List<PosList> reasonLists() {
        return PosList.findActiveByKind(PosList.Kind.RETURN_REASON);
    }

    /**
     * Persists a return row.
     *
     * @param row the row
     */
    void save(EcommerceReturn row) {
        row.persist();
    }

    /** A return of the e-commerce platform. */
    public static class ReturnDto {

        /** The platform's return reference, the idempotency key. */
        public String returnReference;

        /** The order returned, or null. */
        public String orderReference;

        /** The ticket returned, when the platform names it rather than its order, or null. */
        public String ticketNumber;

        /** The return type, a label of the channel's lists (BO-10-01-04, -05). */
        public String returnType;

        /** The reason code of the administered list (DG-01-03-02, -05). */
        public String reasonCode;

        /** The returned lines, never empty. */
        public List<ReturnLineDto> lines = new ArrayList<>();

        /** The refunds the platform made, possibly empty. */
        public List<RefundDto> refunds = new ArrayList<>();

        /**
         * The original order's closing snapshot — lines, prices, applied
         * discounts — as the platform kept it from the close, or null
         * (DG-01-03-01, -03): the node keeps no basket.
         */
        public com.fasterxml.jackson.databind.JsonNode orderSnapshot;
    }

    /** One returned line. */
    public static class ReturnLineDto {

        /** The article EAN. */
        public String ean;

        /** The identity of the order line returned, or null. */
        public String lineUid;

        /** The quantity returned, strictly positive. */
        public BigDecimal quantity;

        /** ORDER, DATE, PUSHED or CURRENT (DG-01-03-04, -06, -07, -08); ORDER when absent. */
        public String priceSource;

        /** The date the price is looked up at, for DATE, ISO-8601. */
        public String priceDate;

        /** The pushed unit price, TTC, for PUSHED. */
        public BigDecimal unitPrice;

        /** The pushed VAT rate, for PUSHED. */
        public BigDecimal vatRate;
    }

    /** One refund the platform made. */
    public static class RefundDto {

        /** The platform's refund means (CARD, PAYPAL...). */
        public String method;

        /** The amount, TTC. */
        public BigDecimal amount;
    }

    /** The report of a return answered to the platform. */
    public static class ReturnReport {

        /** The return was recorded at the point of sale. */
        public static final String RECORDED = "RECORDED";

        /** The platform's return reference. */
        public String returnReference;

        /** RECORDED. */
        public String status;

        /** The order returned, or null. */
        public String orderReference;

        /** The ticket returned, or null for a return without origin. */
        public String originalTicketNumber;

        /** The refund (or return ticket) the return was booked as. */
        public String refundNumber;

        /** The refunded total, TTC. */
        public BigDecimal total;

        /** The reason's code, or null. */
        public String reasonCode;

        /** The reason's label, or null. */
        public String reasonLabel;

        /** The return type named. */
        public String returnType;

        /** TICKET for a whole-ticket return, ARTICLE otherwise. */
        public String returnScope;

        /** Whether the loyalty programme was told. */
        public boolean fidelityQueued;

        /** Whether the engine took the CANCELLATION of the advantages taken back. */
        public boolean consumptionCancelled;

        /** Whether this answer repeats the one of an earlier call. */
        public boolean alreadyReceived;

        /** The priced lines. */
        public List<ReturnReportLine> lines = new ArrayList<>();

        /** The refunds, as booked. */
        public List<ReturnRefund> refunds = new ArrayList<>();

        /** What the recording noted without refusing. */
        public List<String> warnings = new ArrayList<>();
    }

    /** One priced line of a return report. */
    public static class ReturnReportLine {

        /** The rank of the line. */
        public int lineNumber;

        /** The EAN returned. */
        public String ean;

        /** The identity of the ticket line returned, or null. */
        public String lineUid;

        /** The quantity returned. */
        public BigDecimal quantity;

        /** The price source applied. */
        public String priceSource;

        /** The reference unit price, TTC. */
        public BigDecimal unitPrice;

        /** The refunded amount, TTC. */
        public BigDecimal amount;
    }

    /**
     * One refund of a return report.
     *
     * @param method the platform's means, empty when it stated none
     * @param integratedAs the tender it was booked under
     * @param amount the amount, TTC
     */
    public record ReturnRefund(String method, String integratedAs, BigDecimal amount) {
    }
}
