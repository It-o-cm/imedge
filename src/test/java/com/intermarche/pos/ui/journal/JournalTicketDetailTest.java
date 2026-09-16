package com.intermarche.pos.ui.journal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JournalTicketDetail} and its nested {@code Line} and
 * {@code Payment} carriers.
 * <p>
 * Branch enumeration (100%): {@code Line} guards {@code ean}, {@code plu} and
 * {@code modifier} against null — each is exercised on both arms (a present
 * value kept, a null value turned into the empty string). The outer detail and
 * {@code Payment} constructors are branch-free field carriers.
 */
class JournalTicketDetailTest {

    /**
     * The detail constructor stores its header, lines and payments.
     */
    @Test
    void detailStoresHeaderAndChildren() {
        JournalTicketDetail.Line line = new JournalTicketDetail.Line(
                1, "Lait", "3001", "42", "x2", "3,00", "REMISE", false);
        JournalTicketDetail.Payment payment = new JournalTicketDetail.Payment("CASH", "3,00");
        JournalTicketDetail detail = new JournalTicketDetail("C04-00000001", "C04", "CLOSED",
                "31/08/2026", "10:00", "Jean Dupont", "12341234", "IM Lyon", "CARTE1",
                "3,00", "2,85", "0,15", List.of(line), List.of(payment));
        assertEquals("C04-00000001", detail.number);
        assertEquals("C04", detail.terminal);
        assertEquals("CLOSED", detail.status);
        assertEquals("31/08/2026", detail.date);
        assertEquals("10:00", detail.time);
        assertEquals("Jean Dupont", detail.cashier);
        assertEquals("12341234", detail.cashierBadge);
        assertEquals("IM Lyon", detail.store);
        assertEquals("CARTE1", detail.fidelityCard);
        assertEquals("3,00", detail.totalIncludingTax);
        assertEquals("2,85", detail.totalExcludingTax);
        assertEquals("0,15", detail.totalVat);
        assertEquals(1, detail.lines.size());
        assertEquals(1, detail.payments.size());
    }

    /**
     * A line with present ean/plu/modifier keeps them (non-null arms).
     */
    @Test
    void lineKeepsPresentOptionalFields() {
        JournalTicketDetail.Line line = new JournalTicketDetail.Line(
                2, "Pain", "3002", "43", "x1", "1,20", "FORCAGE", false);
        assertEquals(2, line.number);
        assertEquals("Pain", line.label);
        assertEquals("3002", line.ean);
        assertEquals("43", line.plu);
        assertEquals("x1", line.quantity);
        assertEquals("1,20", line.total);
        assertEquals("FORCAGE", line.modifier);
        assertFalse(line.cancelled);
    }

    /**
     * A cancelled line carries its flag, so the detail can mark it instead of
     * showing it as a sold article.
     */
    @Test
    void lineKeepsTheCancelledFlag() {
        JournalTicketDetail.Line line = new JournalTicketDetail.Line(
                4, "Annulé", "3004", "44", "x1", "2,00", null, true);
        assertTrue(line.cancelled);
    }

    /**
     * A line with null ean/plu/modifier turns them into empty strings (null
     * arms).
     */
    @Test
    void lineNullOptionalFieldsBecomeEmpty() {
        JournalTicketDetail.Line line = new JournalTicketDetail.Line(
                3, "Inconnu", null, null, "x1", "0,00", null, false);
        assertEquals("", line.ean);
        assertEquals("", line.plu);
        assertEquals("", line.modifier);
    }

    /**
     * The payment carrier stores its method and amount.
     */
    @Test
    void paymentStoresMethodAndAmount() {
        JournalTicketDetail.Payment payment = new JournalTicketDetail.Payment("CARD", "10,00");
        assertEquals("CARD", payment.method);
        assertEquals("10,00", payment.amount);
    }
}
