package com.intermarche.edge.domain.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TicketFidelityLine}, a frozen loyalty advantage line.
 */
class TicketFidelityLineTest {

    /**
     * The default JPA constructor leaves every field null.
     */
    @Test
    void defaultConstructorLeavesFieldsNull() {
        TicketFidelityLine line = new TicketFidelityLine();
        assertNull(line.ruleCode);
        assertNull(line.label);
        assertNull(line.amount);
    }

    /**
     * The full constructor copies each argument verbatim into its field.
     */
    @Test
    void fullConstructorCopiesArguments() {
        BigDecimal amount = new BigDecimal("1.2345");
        TicketFidelityLine line = new TicketFidelityLine("R1", "Ten percent", amount);
        assertEquals("R1", line.ruleCode);
        assertEquals("Ten percent", line.label);
        assertEquals(amount, line.amount);
    }

    /**
     * The full constructor accepts and stores null arguments unchanged.
     */
    @Test
    void fullConstructorAcceptsNulls() {
        TicketFidelityLine line = new TicketFidelityLine(null, null, null);
        assertNull(line.ruleCode);
        assertNull(line.label);
        assertNull(line.amount);
    }

    /**
     * getLabel returns the empty string when the label is null (null arm).
     */
    @Test
    void getLabelReturnsEmptyWhenNull() {
        TicketFidelityLine line = new TicketFidelityLine("R1", null, BigDecimal.ONE);
        assertEquals("", line.getLabel());
    }

    /**
     * getLabel returns the stored label verbatim when present (non-null arm).
     */
    @Test
    void getLabelReturnsLabelWhenPresent() {
        TicketFidelityLine line = new TicketFidelityLine("R1", "Bonus", BigDecimal.ONE);
        assertEquals("Bonus", line.getLabel());
    }

    /**
     * getAmountFormatted returns "0,00" when the amount is null (null arm).
     */
    @Test
    void getAmountFormattedReturnsZeroWhenNull() {
        TicketFidelityLine line = new TicketFidelityLine("R1", "Bonus", null);
        assertEquals("0,00", line.getAmountFormatted());
    }

    /**
     * getAmountFormatted renders two decimals with a French comma (non-null arm).
     */
    @Test
    void getAmountFormattedRendersTwoDecimalsWithComma() {
        TicketFidelityLine line = new TicketFidelityLine("R1", "Bonus", new BigDecimal("3.5"));
        assertEquals("3,50", line.getAmountFormatted());
    }

    /**
     * getAmountFormatted rounds HALF_UP to two decimals before formatting.
     */
    @Test
    void getAmountFormattedRoundsHalfUp() {
        TicketFidelityLine line = new TicketFidelityLine("R1", "Bonus", new BigDecimal("1.235"));
        assertEquals("1,24", line.getAmountFormatted());
    }

    /**
     * getAmountFormatted formats a zero amount as "0,00".
     */
    @Test
    void getAmountFormattedFormatsZero() {
        TicketFidelityLine line = new TicketFidelityLine("R1", "Bonus", BigDecimal.ZERO);
        assertEquals("0,00", line.getAmountFormatted());
    }

    /**
     * getAmountFormatted keeps the sign of a negative amount.
     */
    @Test
    void getAmountFormattedFormatsNegative() {
        TicketFidelityLine line = new TicketFidelityLine("R1", "Bonus", new BigDecimal("-2.5"));
        assertEquals("-2,50", line.getAmountFormatted());
    }
}
