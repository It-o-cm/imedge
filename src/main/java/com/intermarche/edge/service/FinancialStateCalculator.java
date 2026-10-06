package com.intermarche.edge.service;

import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.payment.ForeignCurrencyPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.session.FinancialState;
import com.intermarche.edge.domain.session.FinancialStateLine;
import com.intermarche.edge.domain.session.FinancialStateLine.StateSection;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the four financial contents of a {@link FinancialState} over a set of
 * tickets and refunds (§5): the financial turnover and its identities (R7 to
 * R15), the hourly turnover (R17), the turnover by IFLS nomenclature and sector
 * (R16), and the best sellers (R19), plus the per-cashier breakdown of a
 * store-scope state (R14).
 *
 * <p>This piece knows NEITHER screen NOR emission: it takes the data it is given,
 * fills the aggregate fields and builds the lines, and returns. The chaining, the
 * sequence, the control totals and the late-ticket count are the emission's job
 * and are left untouched here. It climbs the nomenclature through
 * {@link ProductFamily} static finders, so a plain unit test mocks those.
 */
@ApplicationScoped
public class FinancialStateCalculator {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FinancialStateCalculator.class);

    /** The scale every monetary total is normalized to. */
    private static final int MONEY_SCALE = 4;

    /** One hundred, for the percentage shares. */
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** The three sectors that make up the food turnover (R16). */
    private static final List<String> FOOD_SECTORS = List.of("FRAIS_TRAD", "FRAIS_LS", "SEC");

    /** The label a line carrying an unresolved classification bears (R16). */
    private static final String UNCLASSIFIED = "NON CLASSÉ";

    /** The settlement means print order (R20 keeps TR on its own line). */
    private static final List<String> SETTLEMENT_ORDER =
            List.of("CASH", "CARD", "CHEQUE", "VOUCHER", "FIDELITY", "TR", "CREDIT", "ARRONDI", "DEVISE", "SECOURS");

    /**
     * The calculation parameters, resolved from {@code PosSettingsService} by the
     * caller so the calculator stays free of the settings store.
     *
     * @param hourlySlotMinutes the width of an hourly slot in minutes (R17)
     * @param nomenclatureLevel the restitution level of the nomenclature content (R16)
     * @param sectors the rank-0 node code to sector name map (R16)
     * @param bestSellersCount the depth of the best-sellers ranking (R19)
     * @param cashierBreakdown whether the state carries a per-cashier breakdown (R14)
     */
    public record Config(int hourlySlotMinutes, int nomenclatureLevel, Map<String, String> sectors,
                         int bestSellersCount, boolean cashierBreakdown) {
    }

    /** A mutable accumulator of the figures a grouping key gathers. */
    private static final class Agg {
        /** The tax-included amount. */
        private BigDecimal amount = BigDecimal.ZERO;
        /** The tax-excluded amount. */
        private BigDecimal excludingTax = BigDecimal.ZERO;
        /** The VAT amount. */
        private BigDecimal vat = BigDecimal.ZERO;
        /** The quantity. */
        private BigDecimal quantity = BigDecimal.ZERO;
        /** The number of positive tickets (for the cashier breakdown). */
        private int customers;
        /** The printable label last seen for the key. */
        private String label;
    }

    /**
     * Fills the state's aggregate fields and lines from the given tickets and
     * refunds. The state's scope, period, interval and cashier are read, never
     * written; every total is set to scale four.
     *
     * @param state the state to fill, its identity already set
     * @param tickets the tickets of the interval (closed and abandoned)
     * @param refunds the refunds of the interval
     * @param config the calculation parameters
     */
    public void fill(FinancialState state, List<Ticket> tickets, List<Refund> refunds, Config config) {
        LOGGER.info("Entering method fill");
        BigDecimal grossTurnover = BigDecimal.ZERO;
        BigDecimal netSaleLines = BigDecimal.ZERO;
        BigDecimal refundWithTicket = BigDecimal.ZERO;
        BigDecimal refundWithoutTicket = BigDecimal.ZERO;
        int customerCount = 0;
        int itemCount = 0;
        int cancelledLineCount = 0;
        BigDecimal cancelledLineTotal = BigDecimal.ZERO;
        int endorsedCancelCount = 0;
        BigDecimal endorsedCancelTotal = BigDecimal.ZERO;
        int abandonedTicketCount = 0;
        BigDecimal abandonedTicketTotal = BigDecimal.ZERO;
        int giftCardCount = 0;
        BigDecimal giftCardTotal = BigDecimal.ZERO;
        Map<String, Agg> settlements = new LinkedHashMap<>();
        Map<String, Agg> vatByRate = new LinkedHashMap<>();
        Map<Long, Agg> turnoverBySlot = new LinkedHashMap<>();
        Map<String, Agg> byNode = new LinkedHashMap<>();
        Map<String, Agg> bySector = new LinkedHashMap<>();
        Map<String, Agg> bestSellers = new LinkedHashMap<>();
        Map<String, Agg> byCashier = new LinkedHashMap<>();
        Map<String, ProductFamily> nodeCache = new HashMap<>();
        for (Ticket ticket : tickets) {
            if (ticket.status == Ticket.TicketStatus.CANCELLED) {
                abandonedTicketCount++;
                abandonedTicketTotal = abandonedTicketTotal.add(ticketTotal(ticket));
                continue;
            }
            if (ticket.status != Ticket.TicketStatus.CLOSED) {
                continue;
            }
            boolean positiveTicket = ticketTotal(ticket).signum() > 0;
            if (positiveTicket) {
                customerCount++;
            }
            BigDecimal ticketNet = BigDecimal.ZERO;
            for (TicketLine line : ticket.lines) {
                if (line.cancelled) {
                    if (line.cancelledLast) {
                        cancelledLineCount++;
                        cancelledLineTotal = cancelledLineTotal.add(lineAmount(line));
                    } else {
                        endorsedCancelCount++;
                        endorsedCancelTotal = endorsedCancelTotal.add(lineAmount(line));
                    }
                    continue;
                }
                if (line.moneyProduct) {
                    giftCardCount++;
                    giftCardTotal = giftCardTotal.add(lineAmount(line));
                    continue;
                }
                BigDecimal total = line.totalPrice == null ? BigDecimal.ZERO : line.totalPrice;
                ticketNet = ticketNet.add(total);
                accumulateVat(vatByRate, line, total);
                accumulateNode(byNode, bySector, line, total, config, nodeCache);
                accumulateBestSeller(bestSellers, line);
                if (line.quantity != null && line.quantity.signum() > 0) {
                    grossTurnover = grossTurnover.add(grossLine(line));
                    netSaleLines = netSaleLines.add(total);
                    itemCount += itemUnits(line.quantity);
                } else {
                    refundWithTicket = refundWithTicket.add(total.negate());
                    itemCount -= itemUnits(line.quantity);
                }
            }
            accumulateSettlements(settlements, ticket);
            accumulateHourly(turnoverBySlot, ticket, ticketNet, config);
            accumulateCashier(byCashier, ticket, ticketNet, positiveTicket);
        }
        for (Refund refund : refunds) {
            if (refund.status != Refund.RefundStatus.CLOSED) {
                continue;
            }
            BigDecimal amount = refund.totalAmount == null ? BigDecimal.ZERO : refund.totalAmount;
            if (refund.originalTicketId == null) {
                refundWithoutTicket = refundWithoutTicket.add(amount);
            } else {
                refundWithTicket = refundWithTicket.add(amount);
            }
            for (RefundLine line : refund.lines) {
                itemCount -= itemUnits(line.quantity);
            }
        }
        BigDecimal discountTotal = grossTurnover.subtract(netSaleLines);
        BigDecimal netTurnover = netSaleLines.subtract(refundWithTicket).subtract(refundWithoutTicket);
        state.grossTurnover = scale(grossTurnover);
        state.discountTotal = scale(discountTotal);
        state.refundWithTicketTotal = scale(refundWithTicket);
        state.refundWithoutTicketTotal = scale(refundWithoutTicket);
        state.netTurnover = scale(netTurnover);
        state.customerCount = customerCount;
        state.itemCount = itemCount;
        state.averageBasket = customerCount == 0 ? BigDecimal.ZERO
                : netTurnover.divide(BigDecimal.valueOf(customerCount), 2, RoundingMode.HALF_UP);
        state.cancelledLineCount = cancelledLineCount;
        state.cancelledLineTotal = scale(cancelledLineTotal);
        state.endorsedCancelCount = endorsedCancelCount;
        state.endorsedCancelTotal = scale(endorsedCancelTotal);
        state.abandonedTicketCount = abandonedTicketCount;
        state.abandonedTicketTotal = scale(abandonedTicketTotal);
        state.giftCardCount = giftCardCount;
        state.giftCardTotal = scale(giftCardTotal);
        state.lines.clear();
        buildSettlementLines(state, settlements);
        BigDecimal vatTotal = buildVatLines(state, vatByRate);
        state.vatTotal = scale(vatTotal);
        buildHourlyLines(state, turnoverBySlot, config);
        buildNomenclatureLines(state, byNode);
        BigDecimal foodTurnover = buildSectorLines(state, bySector);
        state.foodShare = netTurnover.signum() == 0 ? BigDecimal.ZERO
                : foodTurnover.multiply(HUNDRED).divide(netTurnover, 2, RoundingMode.HALF_UP);
        buildBestSellerLines(state, bestSellers, config);
        if (state.scope == FinancialState.StateScope.STORE && config.cashierBreakdown()) {
            buildCashierLines(state, byCashier);
        }
        LOGGER.info("Exiting method fill");
    }

    /**
     * The tax-included total of a ticket, its grand total when present, its
     * tax-included total otherwise, zero when neither is set.
     *
     * @param ticket the ticket
     * @return the ticket total, never null
     */
    private BigDecimal ticketTotal(Ticket ticket) {
        if (ticket.grandTotal != null) {
            return ticket.grandTotal;
        }
        return ticket.totalIncludingTax == null ? BigDecimal.ZERO : ticket.totalIncludingTax;
    }

    /**
     * The gross contribution of a positive sale line: its quantity times its
     * original unit price when one was captured, its plain unit price otherwise
     * (R7).
     *
     * @param line the sale line
     * @return the gross contribution, never null
     */
    private BigDecimal grossLine(TicketLine line) {
        BigDecimal unit = line.originalUnitPrice != null ? line.originalUnitPrice : line.unitPrice;
        if (unit == null) {
            return BigDecimal.ZERO;
        }
        return line.quantity.multiply(unit);
    }

    /**
     * The absolute tax-included amount of a line, zero when it has no total.
     *
     * @param line the line
     * @return the absolute amount, never null
     */
    private BigDecimal lineAmount(TicketLine line) {
        return line.totalPrice == null ? BigDecimal.ZERO : line.totalPrice.abs();
    }

    /**
     * The number of items a quantity stands for (R15): its value when integer,
     * one otherwise, taken in magnitude so a return can subtract it.
     *
     * @param quantity the line quantity, possibly null
     * @return the item count the quantity contributes
     */
    private int itemUnits(BigDecimal quantity) {
        if (quantity == null) {
            return 0;
        }
        BigDecimal magnitude = quantity.abs();
        if (magnitude.stripTrailingZeros().scale() <= 0) {
            return magnitude.intValue();
        }
        return 1;
    }

    /**
     * Accumulates a goods line into its VAT-rate group over the signed line total
     * (positive sale, negative return), splitting tax out of it.
     *
     * @param vatByRate the per-rate accumulator
     * @param line the goods line
     * @param total the signed tax-included line total
     */
    private void accumulateVat(Map<String, Agg> vatByRate, TicketLine line, BigDecimal total) {
        BigDecimal rate = line.vatRate == null ? BigDecimal.ZERO : line.vatRate;
        String key = rate.toPlainString();
        Agg agg = vatByRate.computeIfAbsent(key, k -> new Agg());
        BigDecimal excl = total.divide(BigDecimal.ONE.add(rate), MONEY_SCALE, RoundingMode.HALF_UP);
        agg.amount = agg.amount.add(total);
        agg.excludingTax = agg.excludingTax.add(excl);
        agg.vat = agg.vat.add(total.subtract(excl));
        agg.label = rate.multiply(HUNDRED).stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * Accumulates a goods line into its nomenclature node at the restitution
     * level and into its sector at rank zero (R16); an unresolved family goes to
     * the NON CLASSÉ line of each and is never folded into a sector.
     *
     * @param byNode the per-node accumulator
     * @param bySector the per-sector accumulator
     * @param line the goods line
     * @param total the signed tax-included line total
     * @param config the calculation parameters
     * @param nodeCache the per-code climb cache
     */
    private void accumulateNode(Map<String, Agg> byNode, Map<String, Agg> bySector, TicketLine line,
                                BigDecimal total, Config config, Map<String, ProductFamily> nodeCache) {
        BigDecimal rate = line.vatRate == null ? BigDecimal.ZERO : line.vatRate;
        BigDecimal excl = total.divide(BigDecimal.ONE.add(rate), MONEY_SCALE, RoundingMode.HALF_UP);
        ProductFamily node = climb(line.familyCode, config.nomenclatureLevel(), nodeCache);
        String nodeKey;
        String nodeLabel;
        if (node == null || node.nomenclature == null) {
            nodeKey = UNCLASSIFIED;
            nodeLabel = UNCLASSIFIED;
        } else {
            nodeKey = node.code;
            nodeLabel = node.description != null ? node.description : node.code;
        }
        addNode(byNode, nodeKey, nodeLabel, total, excl);
        ProductFamily root = climb(line.familyCode, 0, nodeCache);
        String sector = root == null || root.nomenclature == null ? null
                : config.sectors().get(root.code);
        String sectorKey = sector != null ? sector : UNCLASSIFIED;
        addNode(bySector, sectorKey, sectorKey, total, excl);
    }

    /**
     * Adds a signed amount and its tax-excluded part to a node accumulator.
     *
     * @param accumulator the accumulator map
     * @param key the grouping key
     * @param label the printable label
     * @param total the signed tax-included amount
     * @param excl the tax-excluded part
     */
    private void addNode(Map<String, Agg> accumulator, String key, String label, BigDecimal total, BigDecimal excl) {
        Agg agg = accumulator.computeIfAbsent(key, k -> new Agg());
        agg.amount = agg.amount.add(total);
        agg.excludingTax = agg.excludingTax.add(excl);
        agg.vat = agg.vat.add(total.subtract(excl));
        agg.label = label;
    }

    /**
     * Accumulates a goods line into its best-seller group, keyed by EAN, then PLU,
     * then label (R19), summing the signed quantity and the line total.
     *
     * @param bestSellers the per-article accumulator
     * @param line the goods line
     */
    private void accumulateBestSeller(Map<String, Agg> bestSellers, TicketLine line) {
        String key;
        if (line.ean != null) {
            key = "E:" + line.ean;
        } else if (line.plu != null) {
            key = "P:" + line.plu;
        } else {
            key = "L:" + (line.productLabel == null ? "" : line.productLabel);
        }
        Agg agg = bestSellers.computeIfAbsent(key, k -> new Agg());
        if (line.quantity != null) {
            agg.quantity = agg.quantity.add(line.quantity);
        }
        if (line.totalPrice != null) {
            agg.amount = agg.amount.add(line.totalPrice);
        }
        agg.label = line.productLabel;
    }

    /**
     * Accumulates a closed ticket's payments into the settlement means, the cash
     * amount taken on the applied amount and not the tendered one (R11), the
     * currency amount carried in the quantity (R20 keeps TR on its own line).
     *
     * @param settlements the per-mean accumulator
     * @param ticket the closed ticket
     */
    private void accumulateSettlements(Map<String, Agg> settlements, Ticket ticket) {
        for (TicketPayment payment : ticket.payments) {
            String key = payment.getMethodKey();
            Agg agg = settlements.computeIfAbsent(key, k -> new Agg());
            if (payment.amount != null) {
                agg.amount = agg.amount.add(payment.amount);
            }
            if (payment instanceof ForeignCurrencyPayment currency && currency.foreignAmount != null) {
                agg.quantity = agg.quantity.add(currency.foreignAmount);
            }
            agg.label = key;
        }
    }

    /**
     * Accumulates a closed ticket's net into its hourly slot (R17), the slot taken
     * from the closing date when present and the creation date otherwise.
     *
     * @param turnoverBySlot the per-slot accumulator
     * @param ticket the closed ticket
     * @param ticketNet the ticket's net goods total
     * @param config the calculation parameters
     */
    private void accumulateHourly(Map<Long, Agg> turnoverBySlot, Ticket ticket, BigDecimal ticketNet, Config config) {
        LocalDateTime moment = ticket.closingDate != null ? ticket.closingDate : ticket.creationDate;
        if (moment == null) {
            return;
        }
        long slot = slotIndex(moment, config.hourlySlotMinutes());
        Agg agg = turnoverBySlot.computeIfAbsent(slot, k -> new Agg());
        agg.amount = agg.amount.add(ticketNet);
    }

    /**
     * Accumulates a closed ticket's net into its cashier's breakdown, counting the
     * ticket as a customer when it is positive (R14).
     *
     * @param byCashier the per-cashier accumulator
     * @param ticket the closed ticket
     * @param ticketNet the ticket's net goods total
     * @param positiveTicket whether the ticket counts as a customer
     */
    private void accumulateCashier(Map<String, Agg> byCashier, Ticket ticket, BigDecimal ticketNet, boolean positiveTicket) {
        Employee cashier = ticket.cashier;
        String key = cashier == null || cashier.loginName == null ? "—" : cashier.loginName;
        Agg agg = byCashier.computeIfAbsent(key, k -> new Agg());
        agg.amount = agg.amount.add(ticketNet);
        agg.label = key;
        if (positiveTicket) {
            agg.customers++;
        }
    }

    /**
     * The index of the slot a moment falls in, the number of whole slot widths
     * since the start of its day.
     *
     * @param moment the moment
     * @param slotMinutes the slot width in minutes
     * @return the slot index within the day
     */
    private long slotIndex(LocalDateTime moment, int slotMinutes) {
        int width = slotMinutes <= 0 ? 60 : slotMinutes;
        long minuteOfDay = moment.getHour() * 60L + moment.getMinute();
        return minuteOfDay / width;
    }

    /**
     * Formats a slot index as its start time, {@code HH:mm}.
     *
     * @param slot the slot index
     * @param slotMinutes the slot width in minutes
     * @return the formatted slot start
     */
    private String slotLabel(long slot, int slotMinutes) {
        int width = slotMinutes <= 0 ? 60 : slotMinutes;
        long startMinute = slot * width;
        long hour = startMinute / 60;
        long minute = startMinute % 60;
        return String.format("%02d:%02d", hour, minute);
    }

    /**
     * Climbs a family code to the node at a target level, through the
     * {@link ProductFamily} graph, caching each resolution; returns null for a
     * null or unknown code, and the node itself when it sits at or below the
     * target.
     *
     * @param familyCode the leaf family code, possibly null
     * @param targetRank the level to climb to
     * @param nodeCache the per-code climb cache
     * @return the node at the target level, or null when unresolved
     */
    private ProductFamily climb(String familyCode, int targetRank, Map<String, ProductFamily> nodeCache) {
        if (familyCode == null) {
            return null;
        }
        String cacheKey = targetRank + "|" + familyCode;
        if (nodeCache.containsKey(cacheKey)) {
            return nodeCache.get(cacheKey);
        }
        ProductFamily node = ProductFamily.findByCode(familyCode);
        while (node != null && node.nomenclature != null && node.level != null && node.level > targetRank) {
            String parent = node.nomenclature.parentCodeOf(node.code);
            if (parent == null) {
                break;
            }
            ProductFamily up = ProductFamily.findByCode(parent);
            if (up == null) {
                break;
            }
            node = up;
        }
        nodeCache.put(cacheKey, node);
        return node;
    }

    /**
     * Builds the settlement lines in the fixed print order, unknown means last.
     *
     * @param state the state being filled
     * @param settlements the per-mean accumulator
     */
    private void buildSettlementLines(FinancialState state, Map<String, Agg> settlements) {
        List<String> keys = new ArrayList<>(settlements.keySet());
        keys.sort(Comparator.comparingInt(this::settlementRank).thenComparing(Comparator.naturalOrder()));
        int rank = 0;
        for (String key : keys) {
            Agg agg = settlements.get(key);
            FinancialStateLine line = new FinancialStateLine(StateSection.SETTLEMENT, rank++, key, agg.label, scale(agg.amount));
            if (agg.quantity.signum() != 0) {
                line.quantity = scale(agg.quantity);
            }
            state.addLine(line);
        }
    }

    /**
     * The print rank of a settlement mean, the tail rank for an unknown one.
     *
     * @param key the settlement key
     * @return the order index
     */
    private int settlementRank(String key) {
        int index = SETTLEMENT_ORDER.indexOf(key);
        return index < 0 ? SETTLEMENT_ORDER.size() : index;
    }

    /**
     * Builds the VAT lines in ascending rate order and returns the VAT total.
     *
     * @param state the state being filled
     * @param vatByRate the per-rate accumulator
     * @return the summed VAT amount
     */
    private BigDecimal buildVatLines(FinancialState state, Map<String, Agg> vatByRate) {
        List<String> keys = new ArrayList<>(vatByRate.keySet());
        keys.sort(Comparator.comparing(BigDecimal::new));
        BigDecimal vatTotal = BigDecimal.ZERO;
        int rank = 0;
        for (String key : keys) {
            Agg agg = vatByRate.get(key);
            FinancialStateLine line = new FinancialStateLine(StateSection.VAT, rank++, key, agg.label, scale(agg.amount));
            line.amountExcludingTax = scale(agg.excludingTax);
            line.vatAmount = scale(agg.vat);
            state.addLine(line);
            vatTotal = vatTotal.add(agg.vat);
        }
        return vatTotal;
    }

    /**
     * Builds the hourly lines from the first slot to the last, with no gaps: a
     * slot without a sale in the middle is a zero line, because its absence would
     * read as an editing error (R17).
     *
     * @param state the state being filled
     * @param turnoverBySlot the per-slot accumulator
     * @param config the calculation parameters
     */
    private void buildHourlyLines(FinancialState state, Map<Long, Agg> turnoverBySlot, Config config) {
        if (turnoverBySlot.isEmpty()) {
            return;
        }
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (Long slot : turnoverBySlot.keySet()) {
            first = Math.min(first, slot);
            last = Math.max(last, slot);
        }
        int rank = 0;
        for (long slot = first; slot <= last; slot++) {
            Agg agg = turnoverBySlot.get(slot);
            BigDecimal amount = agg == null ? BigDecimal.ZERO : agg.amount;
            String label = slotLabel(slot, config.hourlySlotMinutes());
            state.addLine(new FinancialStateLine(StateSection.HOURLY, rank++, label, label, scale(amount)));
        }
    }

    /**
     * Builds the nomenclature lines, NON CLASSÉ last, each carrying its
     * tax-excluded and VAT parts.
     *
     * @param state the state being filled
     * @param byNode the per-node accumulator
     */
    private void buildNomenclatureLines(FinancialState state, Map<String, Agg> byNode) {
        List<String> keys = sortedWithUnclassifiedLast(byNode.keySet());
        int rank = 0;
        for (String key : keys) {
            Agg agg = byNode.get(key);
            FinancialStateLine line = new FinancialStateLine(StateSection.NOMENCLATURE, rank++, key, agg.label, scale(agg.amount));
            line.amountExcludingTax = scale(agg.excludingTax);
            line.vatAmount = scale(agg.vat);
            state.addLine(line);
        }
    }

    /**
     * Builds the sector lines, NON CLASSÉ last, and returns the food turnover,
     * the sum of the FRAIS TRAD, FRAIS LS and SEC sectors (R16).
     *
     * @param state the state being filled
     * @param bySector the per-sector accumulator
     * @return the food turnover
     */
    private BigDecimal buildSectorLines(FinancialState state, Map<String, Agg> bySector) {
        List<String> keys = sortedWithUnclassifiedLast(bySector.keySet());
        BigDecimal food = BigDecimal.ZERO;
        int rank = 0;
        for (String key : keys) {
            Agg agg = bySector.get(key);
            state.addLine(new FinancialStateLine(StateSection.SECTOR, rank++, key, key, scale(agg.amount)));
            if (FOOD_SECTORS.contains(key)) {
                food = food.add(agg.amount);
            }
        }
        return food;
    }

    /**
     * Builds the best-seller lines, ranked on the quantity then the amount, both
     * descending, capped at the configured depth (R19).
     *
     * @param state the state being filled
     * @param bestSellers the per-article accumulator
     * @param config the calculation parameters
     */
    private void buildBestSellerLines(FinancialState state, Map<String, Agg> bestSellers, Config config) {
        List<Map.Entry<String, Agg>> entries = new ArrayList<>(bestSellers.entrySet());
        entries.sort((a, b) -> {
            int byQuantity = b.getValue().quantity.compareTo(a.getValue().quantity);
            if (byQuantity != 0) {
                return byQuantity;
            }
            return b.getValue().amount.compareTo(a.getValue().amount);
        });
        int rank = 0;
        for (Map.Entry<String, Agg> entry : entries) {
            if (rank >= config.bestSellersCount()) {
                break;
            }
            Agg agg = entry.getValue();
            String code = entry.getKey().substring(2);
            FinancialStateLine line = new FinancialStateLine(StateSection.BEST_SELLER, rank++, code, agg.label, scale(agg.amount));
            line.quantity = scale(agg.quantity);
            state.addLine(line);
        }
    }

    /**
     * Builds the per-cashier lines (R14), each carrying the cashier's net turnover
     * and, in the quantity, the number of customers.
     *
     * @param state the state being filled
     * @param byCashier the per-cashier accumulator
     */
    private void buildCashierLines(FinancialState state, Map<String, Agg> byCashier) {
        List<String> keys = new ArrayList<>(byCashier.keySet());
        keys.sort(Comparator.naturalOrder());
        int rank = 0;
        for (String key : keys) {
            Agg agg = byCashier.get(key);
            FinancialStateLine line = new FinancialStateLine(StateSection.CASHIER, rank++, key, agg.label, scale(agg.amount));
            line.quantity = BigDecimal.valueOf(agg.customers);
            state.addLine(line);
        }
    }

    /**
     * Sorts a set of keys in natural order with the NON CLASSÉ key, when present,
     * forced to the end so an unclassified figure never hides among the rest.
     *
     * @param keys the keys to sort
     * @return the ordered keys
     */
    private List<String> sortedWithUnclassifiedLast(java.util.Set<String> keys) {
        List<String> ordered = new ArrayList<>(keys);
        ordered.sort((a, b) -> {
            if (a.equals(UNCLASSIFIED)) {
                return 1;
            }
            if (b.equals(UNCLASSIFIED)) {
                return -1;
            }
            return a.compareTo(b);
        });
        return ordered;
    }

    /**
     * Normalizes a monetary amount to the stored scale; every caller passes a
     * non-null accumulator or a zero-initialized running total.
     *
     * @param value the amount, never null
     * @return the amount at scale four
     */
    private BigDecimal scale(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
