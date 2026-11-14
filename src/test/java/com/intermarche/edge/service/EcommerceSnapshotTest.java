package com.intermarche.edge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.service.sync.SyncPayloads;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link EcommerceSnapshot} (DG-01-01-06): the closing
 * snapshot of a basket — the final basket and the engine's evaluation — is
 * read into the lines of the ticket and their
 * valuation trace the way the register reconciles an engine answer — valued
 * portions summed, discounts spread pro rata over their offer's lines,
 * corrections put back on the lines they name — and an answer without a
 * basket line reads as nothing.
 */
class EcommerceSnapshotTest {

    /** Reads the answers. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** A closing answer whose snapshot exercises every rule of the reading. */
    private static final String CLOSED = """
            {"snapshot":{"request":{"couponCodes":["C1","C2"],"items":[
               {"lineId":"L1","produceEan":"E1","quantity":2},
               {"lineId":"L2","produceEan":"E2","quantity":1,"vatRate":0.055,"label":"Riz"},
               {"produceEan":"E3","quantity":0,"pricePerUnitInclTax":1.50},
               {"lineId":"L4","produceEan":"E4"},
               {"lineId":"L5","produceEan":"E5","quantity":1}]},
             "evaluation":{
               "offers":[
                 {"offerId":"BASE","items":[
                   {"lineId":"L1","label":"Lait","amount":{"amountIncludingTax":5.00,"vatRate":0.20}},
                   {"lineId":"L1","amount":{"amountIncludingTax":0.00}},
                   {"lineId":"L2","amount":{"amountIncludingTax":2.00}},
                   {"lineId":"","amount":{"amountIncludingTax":1}},
                   {"lineId":"L4"}]},
                 {"type":"PROMO","items":[
                   {"lineId":"L1","amount":{"amountIncludingTax":1.00}},
                   {"lineId":"L4","amount":{"amountIncludingTax":3.00}}]},
                 {"offerId":"ZERO","items":[{"lineId":"L2","amount":{"amountIncludingTax":0}}]}],
               "advantages":[
                 {"type":"REMISE","offer":"BASE","discountAmount":{"amountIncludingTax":0.70}},
                 {"type":"REMISE","offer":"PROMO","discountAmount":{"amountIncludingTax":1.00}},
                 {"type":"FID","offer":"BASE","discountAmount":{"amountIncludingTax":0.10}},
                 {"type":"NONE","offer":"ZERO","discountAmount":{"amountIncludingTax":1}},
                 {"type":"NOOFFER","offer":"MISSING","discountAmount":{"amountIncludingTax":1}},
                 {"type":"EMPTYREF","discountAmount":{"amountIncludingTax":1}},
                 {"type":"UPSELL","offer":"BASE"},
                 {"type":"CAP","details":[{"lineId":"L1","correction":0.05},{"lineId":"L1","correction":0.02},
                   {"lineId":"","correction":1},{"lineId":"L2"},{"lineId":"L2","correction":0}]},
                 {"type":"CAP2","details":[{"lineId":"L1","correction":0.01}]}],
               "consumedCoupons":["C1","C9"]}}}
            """;

    /**
     * The snapshot is read line by line: the valued total, the cut and the
     * correction of each line, its label, rate and trace, the fallbacks of a
     * line the engine did not cover, and the coupons the close consumed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theSnapshotIsReadIntoTheTicket() {
        com.fasterxml.jackson.databind.JsonNode root = read(CLOSED);
        EcommerceSnapshot snapshot = EcommerceSnapshot.of(root.at("/snapshot/request"),
                root.at("/snapshot/evaluation").toString(), mapper);
        Assertions.assertNotNull(snapshot);
        Assertions.assertEquals(List.of("C1"), snapshot.consumedCoupons);
        Assertions.assertTrue(((Map<String, Object>) snapshot.evaluation).containsKey("consumedCoupons"));
        Assertions.assertEquals(5, snapshot.lines.size());
        SyncPayloads.LineDto milk = snapshot.lines.get(0);
        Assertions.assertEquals("L1", milk.lineUid);
        Assertions.assertEquals("Lait", milk.productLabel);
        Assertions.assertEquals(new BigDecimal("3.00"), milk.unitPrice);
        Assertions.assertEquals(new BigDecimal("5.26"), milk.totalPrice);
        Assertions.assertEquals(new BigDecimal("0.20"), milk.vatRate.setScale(2));
        SyncPayloads.ValuationDto milkTrace = snapshot.valuations.get(0);
        Assertions.assertEquals(new BigDecimal("6.00"), milkTrace.localTotal);
        Assertions.assertEquals(new BigDecimal("0.82"), milkTrace.advantageAmount);
        Assertions.assertEquals(new BigDecimal("0.08"), milkTrace.correctionAmount);
        Assertions.assertEquals("REMISE; FID", milkTrace.advantageLabel);
        Assertions.assertEquals("BASE; PROMO", milkTrace.offerLabel);
        Assertions.assertEquals("CAP; CAP2", milkTrace.correctionLabel);
        SyncPayloads.LineDto rice = snapshot.lines.get(1);
        Assertions.assertEquals("Riz", rice.productLabel);
        Assertions.assertEquals(new BigDecimal("0.055"), rice.vatRate);
        Assertions.assertEquals(new BigDecimal("1.77"), rice.totalPrice);
        Assertions.assertNull(snapshot.valuations.get(1).correctionAmount);
        Assertions.assertEquals("BASE; ZERO", snapshot.valuations.get(1).offerLabel);
        SyncPayloads.LineDto pushed = snapshot.lines.get(2);
        Assertions.assertEquals("L3", pushed.lineUid);
        Assertions.assertEquals(BigDecimal.ONE, pushed.quantity);
        Assertions.assertEquals(new BigDecimal("1.50"), pushed.totalPrice);
        Assertions.assertEquals("E3", pushed.productLabel);
        SyncPayloads.LineDto unlabelled = snapshot.lines.get(3);
        Assertions.assertEquals("E4", unlabelled.productLabel);
        Assertions.assertEquals(BigDecimal.ZERO, unlabelled.vatRate);
        Assertions.assertEquals(new BigDecimal("2.25"), unlabelled.totalPrice);
        SyncPayloads.LineDto uncovered = snapshot.lines.get(4);
        Assertions.assertEquals(new BigDecimal("0.00"), uncovered.totalPrice);
        Assertions.assertNull(snapshot.valuations.get(4).advantageAmount);
        Assertions.assertEquals(new BigDecimal("10.78"), snapshot.total());
        Assertions.assertTrue(((Map<String, Object>) snapshot.request).containsKey("items"));
        Assertions.assertTrue(((Map<String, Object>) snapshot.evaluation).containsKey("offers"));
    }

    /**
     * Reads a JSON text.
     *
     * @param json the text
     * @return the tree
     */
    private com.fasterxml.jackson.databind.JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Without a consumed list, the coupons consumed are those an advantage
     * names; a line without priced portion takes its pushed price.
     */
    @Test
    void theCouponsConsumedAreThoseTheAdvantagesName() {
        EcommerceSnapshot snapshot = EcommerceSnapshot.of(read(
                "{\"couponCodes\":[\"BIENVENUE\",\"AUTRE\"],\"items\":[{\"lineId\":\"X\",\"produceEan\":\"E\","
                        + "\"quantity\":1,\"pricePerUnitInclTax\":2}]}"),
                "{\"advantages\":[{\"offerCode\":\"COUPON:BIENVENUE\"}]}", mapper);
        Assertions.assertEquals(List.of("BIENVENUE"), snapshot.consumedCoupons);
        Assertions.assertEquals(new BigDecimal("2.00"), snapshot.total());
    }

    /**
     * An unreadable or non-object evaluation, or a basket without line,
     * reads as nothing.
     */
    @Test
    void anAnswerWithoutLinesReadsAsNothing() {
        com.fasterxml.jackson.databind.JsonNode basket = read("{\"items\":[{\"lineId\":\"X\"}]}");
        Assertions.assertNull(EcommerceSnapshot.of(basket, "not json", mapper));
        Assertions.assertNull(EcommerceSnapshot.of(basket, "[1]", mapper));
        Assertions.assertNull(EcommerceSnapshot.of(read("{}"), "{}", mapper));
        Assertions.assertNull(EcommerceSnapshot.of(read("{\"items\":[]}"), "{}", mapper));
    }
}
