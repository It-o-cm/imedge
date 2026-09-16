package com.intermarche.pos.ui.journal;

import com.intermarche.pos.domain.session.CashMovement;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JournalCriteria} parsing.
 * <p>
 * Branch enumeration (100%): {@code fromParams} covers the null-params arm and
 * the populated arm, and the {@code descending} guard on both values;
 * {@code blankToNull} covers null / blank / non-blank; {@code parseAmount}
 * covers null / valid / malformed; {@code parseDateTime} covers null / full
 * datetime / bare date / fully-malformed (both catch arms); {@code addNonBlank}
 * covers the null-list arm and the blank-skip / kept arms; {@code addFlags}
 * covers the null-list arm, blank-skip, valid-add and unknown-catch arms;
 * {@code addMovementTypes} covers the null-list arm, blank-skip, valid-add and
 * unknown-catch arms; {@code parsePage} covers blank / valid / non-positive /
 * malformed.
 */
class JournalCriteriaTest {

    /**
     * Null params yield an empty match-everything criteria (null-params arm).
     */
    @Test
    void nullParamsYieldEmptyCriteria() {
        JournalCriteria criteria = JournalCriteria.fromParams(null);
        assertNull(criteria.text);
        assertNull(criteria.amountMin);
        assertNull(criteria.familyMin);
        assertNull(criteria.familyMax);
        assertNull(criteria.refundPluMin);
        assertNull(criteria.refundAmountMin);
        assertNull(criteria.cancelPluMin);
        assertNull(criteria.cancelPluMax);
        assertNull(criteria.cancelAmountMin);
        assertNull(criteria.cancelAmountMax);
        assertTrue(criteria.methods.isEmpty());
        assertTrue(criteria.flags.isEmpty());
        assertTrue(criteria.movementTypes.isEmpty());
        assertFalse(criteria.descending);
        assertEquals(1, criteria.page);
    }

    /**
     * A fully populated map parses every field: trimmed strings, comma
     * amounts, a full datetime and a bare date, the non-blank method values,
     * and the valid flag among a blank and an unknown one; {@code dir=desc}
     * sets descending.
     */
    @Test
    void populatedParamsParseEveryField() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("text", "  lait  ");
        params.putSingle("cashierMin", " 100 ");
        params.putSingle("cashierMax", "200");
        params.putSingle("terminalMin", "C01");
        params.putSingle("terminalMax", "C09");
        params.putSingle("txMin", "C04-00000001");
        params.putSingle("txMax", "C04-00000999");
        params.putSingle("amountMin", "12,50");
        params.putSingle("amountMax", "99.00");
        params.putSingle("grossMin", "10,00");
        params.putSingle("grossMax", "90.00");
        params.putSingle("hourFrom", "12");
        params.putSingle("hourTo", "14");
        params.putSingle("familyCodes", " FRUITS, BOULANGERIE , ");
        params.putSingle("movementAmountMin", "50,00");
        params.putSingle("movementAmountMax", "500.00");
        params.putSingle("dateFrom", "2026-08-31T14:30");
        params.putSingle("dateTo", "2026-08-31");
        params.putSingle("pluMin", "40");
        params.putSingle("pluMax", "50");
        params.putSingle("familyMin", " FRUITS ");
        params.putSingle("familyMax", "LEGUMES");
        params.putSingle("refundPluMin", "40");
        params.putSingle("refundPluMax", "60");
        params.putSingle("refundAmountMin", "2,00");
        params.putSingle("refundAmountMax", "20.00");
        params.putSingle("cancelPluMin", " 70 ");
        params.putSingle("cancelPluMax", "80");
        params.putSingle("cancelAmountMin", "3,00");
        params.putSingle("cancelAmountMax", "30.00");
        params.putSingle("vatRate", "0,2000");
        params.putSingle("reductionMin", "1,00");
        params.putSingle("reductionMax", "5,00");
        params.putSingle("authMin", " 100000 ");
        params.putSingle("authMax", "999999");
        params.put("method", List.of("CARD", "  "));
        params.put("eventType", List.of("SESSION_CLOSED"));
        params.put("flag", List.of("CARD", "DEGRADED", "DEGRADED_MANUAL", "  ", "XXX"));
        params.put("movementType", List.of("WITHDRAWAL", "DECLARATION", "  ", "NOPE"));
        params.putSingle("sort", "amount");
        params.putSingle("dir", "desc");
        JournalCriteria criteria = JournalCriteria.fromParams(params);
        assertEquals("lait", criteria.text);
        assertEquals("100", criteria.cashierMin);
        assertEquals("200", criteria.cashierMax);
        assertEquals("C01", criteria.terminalMin);
        assertEquals("C09", criteria.terminalMax);
        assertEquals("C04-00000001", criteria.txMin);
        assertEquals("C04-00000999", criteria.txMax);
        assertEquals(new BigDecimal("12.50"), criteria.amountMin);
        assertEquals(new BigDecimal("99.00"), criteria.amountMax);
        assertEquals(new BigDecimal("10.00"), criteria.grossMin);
        assertEquals(new BigDecimal("90.00"), criteria.grossMax);
        assertEquals(Integer.valueOf(12), criteria.hourFrom);
        assertEquals(Integer.valueOf(14), criteria.hourTo);
        assertEquals(java.util.Set.of("FRUITS", "BOULANGERIE"), criteria.families);
        assertEquals(new BigDecimal("50.00"), criteria.movementAmountMin);
        assertEquals(new BigDecimal("500.00"), criteria.movementAmountMax);
        assertEquals(LocalDateTime.of(2026, 8, 31, 14, 30), criteria.dateFrom);
        assertEquals(LocalDateTime.of(2026, 8, 31, 0, 0), criteria.dateTo);
        assertEquals("40", criteria.pluMin);
        assertEquals("50", criteria.pluMax);
        assertEquals("FRUITS", criteria.familyMin);
        assertEquals("LEGUMES", criteria.familyMax);
        assertEquals("40", criteria.refundPluMin);
        assertEquals("60", criteria.refundPluMax);
        assertEquals(new BigDecimal("2.00"), criteria.refundAmountMin);
        assertEquals(new BigDecimal("20.00"), criteria.refundAmountMax);
        assertEquals("70", criteria.cancelPluMin);
        assertEquals("80", criteria.cancelPluMax);
        assertEquals(new BigDecimal("3.00"), criteria.cancelAmountMin);
        assertEquals(new BigDecimal("30.00"), criteria.cancelAmountMax);
        assertEquals(new BigDecimal("0.2000"), criteria.vatRate);
        assertEquals(new BigDecimal("1.00"), criteria.reductionMin);
        assertEquals(new BigDecimal("5.00"), criteria.reductionMax);
        assertEquals("100000", criteria.authMin);
        assertEquals("999999", criteria.authMax);
        assertEquals("[CARD]", criteria.methods.toString());
        assertEquals("[SESSION_CLOSED]", criteria.eventTypes.toString());
        assertEquals(3, criteria.flags.size());
        assertTrue(criteria.flags.contains(JournalCriteria.Flag.CARD));
        assertTrue(criteria.flags.contains(JournalCriteria.Flag.DEGRADED));
        assertTrue(criteria.flags.contains(JournalCriteria.Flag.DEGRADED_MANUAL));
        assertEquals(2, criteria.movementTypes.size());
        assertTrue(criteria.movementTypes.contains(CashMovement.MovementType.WITHDRAWAL));
        assertTrue(criteria.movementTypes.contains(CashMovement.MovementType.DECLARATION));
        assertEquals("amount", criteria.sort);
        assertTrue(criteria.descending);
    }

    /**
     * Absent, blank and malformed inputs are all dropped: a blank text becomes
     * null (blank arm), a malformed amount and a fully-malformed date become
     * null (catch arms), the absent multi-value keys hit the null-list arms of
     * {@code addNonBlank}/{@code addFlags}/{@code addMovementTypes}, and an
     * absent {@code dir} leaves descending false.
     */
    @Test
    void blankAndMalformedInputsAreDropped() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("text", "   ");
        params.putSingle("amountMin", "abc");
        params.putSingle("dateFrom", "xx");
        JournalCriteria criteria = JournalCriteria.fromParams(params);
        assertNull(criteria.text);
        assertNull(criteria.amountMin);
        assertNull(criteria.dateFrom);
        assertTrue(criteria.methods.isEmpty());
        assertTrue(criteria.eventTypes.isEmpty());
        assertTrue(criteria.flags.isEmpty());
        assertTrue(criteria.movementTypes.isEmpty());
        assertFalse(criteria.descending);
        assertEquals(1, criteria.page);
    }

    /**
     * A page parameter is read when it is a positive number (valid arm) and
     * reaches the criteria through {@code fromParams}.
     */
    @Test
    void positivePageIsRead() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("page", " 4 ");
        assertEquals(4, JournalCriteria.fromParams(params).page);
    }

    /**
     * A blank, non-positive or malformed page falls back to the first page
     * (blank arm, below-one arm, catch arm).
     */
    @Test
    void blankNonPositiveAndMalformedPagesFallBackToTheFirst() {
        assertEquals(1, JournalCriteria.parsePage(null));
        assertEquals(1, JournalCriteria.parsePage("   "));
        assertEquals(1, JournalCriteria.parsePage("0"));
        assertEquals(1, JournalCriteria.parsePage("-3"));
        assertEquals(1, JournalCriteria.parsePage("abc"));
    }

    /**
     * {@code parseHour} (BO-04-01-09) keeps an in-range hour and drops a blank,
     * a below-zero, an above-23 and a malformed value: every arm of the guard.
     */
    @Test
    void parseHourCoversEveryArm() {
        assertEquals(Integer.valueOf(0), JournalCriteria.parseHour("0"));
        assertEquals(Integer.valueOf(23), JournalCriteria.parseHour("23"));
        assertNull(JournalCriteria.parseHour("   "));
        assertNull(JournalCriteria.parseHour("-1"));
        assertNull(JournalCriteria.parseHour("24"));
        assertNull(JournalCriteria.parseHour("abc"));
    }

    /**
     * {@code addFamilyCodes} (BO-04-01-11) splits the comma list, trims each and
     * drops blanks; a blank field adds nothing (the blank arm).
     */
    @Test
    void addFamilyCodesSplitsTrimsAndDropsBlanks() {
        java.util.Set<String> target = new java.util.LinkedHashSet<>();
        JournalCriteria.addFamilyCodes(target, " FRUITS ,, BOULANGERIE , ");
        assertEquals(java.util.Set.of("FRUITS", "BOULANGERIE"), target);
        java.util.Set<String> blankTarget = new java.util.LinkedHashSet<>();
        JournalCriteria.addFamilyCodes(blankTarget, "   ");
        assertTrue(blankTarget.isEmpty());
    }

    /**
     * {@code familiesAsText} joins the selected codes back for the form, and
     * yields an empty string when none is selected.
     */
    @Test
    void familiesAsTextJoinsSelectedCodes() {
        JournalCriteria criteria = new JournalCriteria();
        criteria.families.add("FRUITS");
        criteria.families.add("BOULANGERIE");
        assertEquals("FRUITS,BOULANGERIE", criteria.familiesAsText());
        assertEquals("", new JournalCriteria().familiesAsText());
    }
}
