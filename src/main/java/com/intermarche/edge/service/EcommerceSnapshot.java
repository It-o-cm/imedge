package com.intermarche.edge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.service.sync.SyncPayloads;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CLOSING SNAPSHOT of an e-commerce basket (DG-01-01-06) — the final
 * basket and its evaluation — read into the ticket the order is booked as: one line
 * per basket line at the price the engine valued it, the promotions it took
 * as the per-line valuation trace the registers push for the engine's
 * advantages (BO-06-08-02), and the request and evaluation themselves for
 * the loyalty earn of the order.
 *
 * <p>The reading mirrors the register's reconciliation of an engine answer:
 * the valued portions of each line summed over the offers, a discount
 * advantage spread over the lines of its offer pro rata to their valued
 * share (the rounding residual on the largest share), a correction
 * advantage put back on the lines it names.
 *
 * <p>Built at the close from the platform's final basket and the engine's
 * stateless evaluation of it, and never stored by the node: the basket is
 * the platform's. The node keeps what any sale leaves — its ticket, lines
 * and valuation trace — and hands the snapshot back to the platform, which
 * sends it again with a return (DG-01-03).
 */
public final class EcommerceSnapshot {

    /** The booked lines, in the basket's order. */
    public final List<SyncPayloads.LineDto> lines = new ArrayList<>();

    /** The per-line valuation trace. */
    public final List<SyncPayloads.ValuationDto> valuations = new ArrayList<>();

    /** The coupon codes the close consumed, as the engine listed them. */
    public final List<String> consumedCoupons = new ArrayList<>();

    /** The frozen request, as a tree of maps and lists, never null. */
    public Object request;

    /** The frozen evaluation, as a tree of maps and lists, never null. */
    public Object evaluation;

    /**
     * Builds an empty snapshot; {@link #read} fills it.
     */
    private EcommerceSnapshot() {
    }

    /**
     * Reads the final basket of a close and the engine's evaluation of it.
     * The coupons the close consumed are the basket's coupon codes the
     * evaluation used: those it lists in {@code consumedCoupons} when it does,
     * else those an advantage names.
     *
     * @param request the final basket as the engine valued it
     * @param evaluation the engine's evaluation, JSON text
     * @param mapper the JSON reader
     * @return the snapshot, or null when the evaluation is unreadable or the
     *     basket holds no line
     */
    public static EcommerceSnapshot of(JsonNode request, String evaluation, ObjectMapper mapper) {
        JsonNode answer;
        try {
            answer = mapper.readTree(evaluation);
        } catch (Exception e) {
            return null;
        }
        if (!answer.isObject() || !request.path("items").isArray() || request.path("items").isEmpty()) {
            return null;
        }
        EcommerceSnapshot snapshot = new EcommerceSnapshot();
        snapshot.request = mapper.convertValue(request, Object.class);
        snapshot.evaluation = mapper.convertValue(answer, Object.class);
        String advantages = answer.path("advantages").toString();
        for (JsonNode coupon : request.path("couponCodes")) {
            String code = coupon.asText();
            boolean listed = false;
            for (JsonNode consumed : answer.path("consumedCoupons")) {
                listed = listed || code.equals(consumed.asText());
            }
            if (answer.has("consumedCoupons") ? listed : advantages.contains(code)) {
                snapshot.consumedCoupons.add(code);
            }
        }
        snapshot.fill(request.path("items"), answer);
        return snapshot;
    }

    /**
     * Books the basket lines with their valued totals and promotions.
     *
     * @param items the request's items
     * @param evaluation the engine's evaluation
     */
    private void fill(JsonNode items, JsonNode evaluation) {
        Map<String, BigDecimal> valued = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        Map<String, BigDecimal> rates = new HashMap<>();
        Map<String, String> offerLabels = new HashMap<>();
        for (JsonNode offer : evaluation.path("offers")) {
            String offerLabel = offer.path("offerId").asText(offer.path("type").asText(""));
            for (JsonNode portion : offer.path("items")) {
                BigDecimal amount = decimal(portion.path("amount").path("amountIncludingTax"));
                String lineId = portion.path("lineId").asText("");
                if (lineId.isEmpty() || amount == null) {
                    continue;
                }
                valued.merge(lineId, amount, BigDecimal::add);
                labels.putIfAbsent(lineId, portion.path("label").asText(""));
                rates.putIfAbsent(lineId, decimal(portion.path("amount").path("vatRate")));
                offerLabels.merge(lineId, offerLabel, (a, b) -> a.contains(b) ? a : a + "; " + b);
            }
        }
        Map<String, BigDecimal> cuts = new HashMap<>();
        Map<String, String> cutLabels = new HashMap<>();
        Map<String, BigDecimal> corrections = new HashMap<>();
        Map<String, String> correctionLabels = new HashMap<>();
        for (JsonNode advantage : evaluation.path("advantages")) {
            String type = advantage.path("type").asText("");
            if (!advantage.path("details").isEmpty()) {
                for (JsonNode detail : advantage.path("details")) {
                    BigDecimal correction = decimal(detail.path("correction"));
                    String lineId = detail.path("lineId").asText("");
                    if (!lineId.isEmpty() && correction != null && correction.signum() > 0) {
                        corrections.merge(lineId, correction, BigDecimal::add);
                        correctionLabels.merge(lineId, type, (a, b) -> a.contains(b) ? a : a + "; " + b);
                    }
                }
                continue;
            }
            BigDecimal discount = decimal(advantage.path("discountAmount").path("amountIncludingTax"));
            JsonNode offer = offerOf(evaluation, advantage.path("offer").asText(""));
            if (discount != null && offer != null) {
                allocate(type, discount, offer, cuts, cutLabels);
            }
        }
        int number = 0;
        for (JsonNode item : items) {
            number++;
            String lineId = item.path("lineId").asText("L" + number);
            BigDecimal quantity = decimal(item.path("quantity"));
            quantity = quantity == null || quantity.signum() <= 0 ? BigDecimal.ONE : quantity;
            BigDecimal pushed = decimal(item.path("pricePerUnitInclTax"));
            BigDecimal fallback = pushed == null ? BigDecimal.ZERO : pushed.multiply(quantity);
            BigDecimal base = valued.getOrDefault(lineId, fallback).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cut = cuts.getOrDefault(lineId, BigDecimal.ZERO);
            BigDecimal correction = corrections.getOrDefault(lineId, BigDecimal.ZERO);
            BigDecimal net = base.subtract(cut).add(correction).setScale(2, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
            BigDecimal rate = rates.get(lineId) != null ? rates.get(lineId) : decimal(item.path("vatRate"));
            String ean = item.path("produceEan").asText("");
            String label = labels.getOrDefault(lineId, "");
            SyncPayloads.LineDto line = new SyncPayloads.LineDto();
            line.lineNumber = number;
            line.lineUid = lineId;
            line.ean = ean;
            line.productLabel = label.isEmpty() ? item.path("label").asText(ean) : label;
            line.quantity = quantity;
            line.unitPrice = base.divide(quantity, 2, RoundingMode.HALF_UP);
            line.vatRate = rate == null ? BigDecimal.ZERO : rate;
            line.totalPrice = net;
            lines.add(line);
            SyncPayloads.ValuationDto trace = new SyncPayloads.ValuationDto();
            trace.lineUid = lineId;
            trace.localTotal = base;
            trace.valuedTotal = net;
            trace.offerLabel = offerLabels.get(lineId);
            trace.advantageLabel = cutLabels.get(lineId);
            trace.advantageAmount = cut.signum() > 0 ? cut.setScale(2, RoundingMode.HALF_UP) : null;
            trace.correctionLabel = correctionLabels.get(lineId);
            trace.correctionAmount = correction.signum() > 0 ? correction.setScale(2, RoundingMode.HALF_UP) : null;
            valuations.add(trace);
        }
    }

    /**
     * Spreads a discount over the lines of its offer, pro rata to their
     * valued share, the rounding residual on the largest share.
     *
     * @param type the advantage's type, its label on the lines
     * @param discount the discount, TTC
     * @param offer the offer it rewards
     * @param cuts the per-line cut accumulator
     * @param cutLabels the per-line label accumulator
     */
    private static void allocate(String type, BigDecimal discount, JsonNode offer,
                                 Map<String, BigDecimal> cuts, Map<String, String> cutLabels) {
        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode portion : offer.path("items")) {
            BigDecimal amount = decimal(portion.path("amount").path("amountIncludingTax"));
            String lineId = portion.path("lineId").asText("");
            if (!lineId.isEmpty() && amount != null) {
                shares.merge(lineId, amount, BigDecimal::add);
                total = total.add(amount);
            }
        }
        if (total.signum() == 0) {
            return;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        String largest = null;
        BigDecimal largestShare = BigDecimal.valueOf(-1);
        for (Map.Entry<String, BigDecimal> share : shares.entrySet()) {
            BigDecimal cut = discount.multiply(share.getValue()).divide(total, 2, RoundingMode.HALF_UP);
            cuts.merge(share.getKey(), cut, BigDecimal::add);
            cutLabels.merge(share.getKey(), type, (a, b) -> a.contains(b) ? a : a + "; " + b);
            allocated = allocated.add(cut);
            if (share.getValue().compareTo(largestShare) > 0) {
                largestShare = share.getValue();
                largest = share.getKey();
            }
        }
        cuts.merge(largest, discount.subtract(allocated), BigDecimal::add);
    }

    /**
     * The offer an advantage rewards, by its identifier first, its type second.
     *
     * @param evaluation the engine's evaluation
     * @param reference the advantage's offer reference
     * @return the offer, or null when the reference is empty or none matches
     */
    private static JsonNode offerOf(JsonNode evaluation, String reference) {
        if (reference.isEmpty()) {
            return null;
        }
        for (JsonNode offer : evaluation.path("offers")) {
            if (reference.equals(offer.path("offerId").asText())) {
                return offer;
            }
        }
        for (JsonNode offer : evaluation.path("offers")) {
            if (reference.equals(offer.path("type").asText())) {
                return offer;
            }
        }
        return null;
    }

    /**
     * Reads a number, tolerating a missing field.
     *
     * @param node the field
     * @return the number, or null when absent or not numeric
     */
    static BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }

    /**
     * The total of the booked lines, TTC.
     *
     * @return the total
     */
    public BigDecimal total() {
        BigDecimal total = BigDecimal.ZERO;
        for (SyncPayloads.LineDto line : lines) {
            total = total.add(line.totalPrice);
        }
        return total;
    }
}
