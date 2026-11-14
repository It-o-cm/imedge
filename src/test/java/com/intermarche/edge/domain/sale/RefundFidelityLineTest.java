package com.intermarche.edge.domain.sale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Unit tests for {@link RefundFidelityLine}, the loyalty advantage a return
 * takes back (LC-05-07-02).
 *
 * <p>The embeddable has no branch: a default constructor for JPA and a
 * constructor that freezes the four values. Both are asserted on absolute
 * values; no static finder or persist is reached, so no Panache mocking is
 * required.
 */
class RefundFidelityLineTest {

    /**
     * The JPA constructor leaves every field unset.
     */
    @Test
    void defaultConstructorLeavesEveryFieldNull() {
        RefundFidelityLine line = new RefundFidelityLine();
        Assertions.assertNull(line.ruleCode);
        Assertions.assertNull(line.label);
        Assertions.assertNull(line.advantageTypeLabel);
        Assertions.assertNull(line.amount);
    }

    /**
     * The freezing constructor keeps the rule code, the label, the advantage
     * type label and the amount taken back exactly as given.
     */
    @Test
    void fullConstructorFreezesTheGivenValues() {
        RefundFidelityLine line = new RefundFidelityLine("R-CAG", "Cagnotte fruits", "Cagnotte",
                new BigDecimal("1.20"));
        Assertions.assertEquals("R-CAG", line.ruleCode);
        Assertions.assertEquals("Cagnotte fruits", line.label);
        Assertions.assertEquals("Cagnotte", line.advantageTypeLabel);
        Assertions.assertEquals(new BigDecimal("1.20"), line.amount);
    }
}
