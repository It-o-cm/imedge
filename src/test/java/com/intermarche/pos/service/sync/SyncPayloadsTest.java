package com.intermarche.pos.service.sync;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SyncPayloads}.
 * <p>
 * {@code SyncPayloads} is a branch-free data-container class: a non-instantiable
 * outer holder plus eight public DTOs whose only members are public fields,
 * implicit no-arg constructors, and two list fields pre-initialized to empty
 * {@link java.util.ArrayList}s. There is no conditional logic, so the tests
 * exercise the private constructor (via reflection) and every DTO's construction,
 * field round-trip, and default state to reach full instruction coverage.
 */
class SyncPayloadsTest {

    /**
     * The outer class must expose a single private no-arg constructor and be
     * final, and invoking that constructor reflectively must yield an instance.
     */
    @Test
    void outerClassIsNonInstantiableHolder() throws Exception {
        assertTrue(Modifier.isFinal(SyncPayloads.class.getModifiers()));
        Constructor<SyncPayloads> ctor = SyncPayloads.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()));
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }

    /**
     * A {@link SyncPayloads.SessionDto} stores and returns each of its fields.
     */
    @Test
    void sessionDtoHoldsItsFields() {
        SyncPayloads.SessionDto dto = new SyncPayloads.SessionDto();
        dto.sessionNumber = "S1";
        dto.terminalId = "T1";
        dto.status = "OPEN";
        dto.openingDate = "2026-01-01T08:00:00";
        dto.closingDate = "2026-01-01T20:00:00";
        dto.openingCashierLogin = "jdoe";
        dto.closingCashierLogin = "asmith";
        dto.openingFloat = new BigDecimal("100.00");
        dto.countedAmount = new BigDecimal("250.00");
        dto.theoreticalAmount = new BigDecimal("249.50");
        dto.variance = new BigDecimal("0.50");
        dto.withdrawnAmount = new BigDecimal("150.00");
        dto.countDetail = "{\"5\":2}";
        assertEquals("S1", dto.sessionNumber);
        assertEquals("T1", dto.terminalId);
        assertEquals("OPEN", dto.status);
        assertEquals("2026-01-01T08:00:00", dto.openingDate);
        assertEquals("2026-01-01T20:00:00", dto.closingDate);
        assertEquals("jdoe", dto.openingCashierLogin);
        assertEquals("asmith", dto.closingCashierLogin);
        assertEquals(new BigDecimal("100.00"), dto.openingFloat);
        assertEquals(new BigDecimal("250.00"), dto.countedAmount);
        assertEquals(new BigDecimal("249.50"), dto.theoreticalAmount);
        assertEquals(new BigDecimal("0.50"), dto.variance);
        assertEquals(new BigDecimal("150.00"), dto.withdrawnAmount);
        assertEquals("{\"5\":2}", dto.countDetail);
    }

    /**
     * A default {@link SyncPayloads.SessionDto} leaves every field null.
     */
    @Test
    void sessionDtoDefaultsAreNull() {
        SyncPayloads.SessionDto dto = new SyncPayloads.SessionDto();
        assertNull(dto.sessionNumber);
        assertNull(dto.closingDate);
        assertNull(dto.closingCashierLogin);
        assertNull(dto.openingFloat);
        assertNull(dto.countedAmount);
        assertNull(dto.theoreticalAmount);
        assertNull(dto.variance);
        assertNull(dto.withdrawnAmount);
        assertNull(dto.countDetail);
    }

    /**
     * A {@link SyncPayloads.TicketDto} stores and returns each of its fields,
     * including its int count and the two list fields.
     */
    @Test
    void ticketDtoHoldsItsFields() {
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "TCK1";
        dto.terminalId = "T1";
        dto.status = "CLOSED";
        dto.creationDate = "2026-01-01T09:00:00";
        dto.closingDate = "2026-01-01T09:05:00";
        dto.storeCode = "ST1";
        dto.cashierLogin = "jdoe";
        dto.sessionNumber = "S1";
        dto.fidelityCard = "FID1";
        dto.digitalKey = "DK1";
        dto.customerEmail = "c@example.com";
        dto.itemCount = 3;
        dto.totalExcludingTax = new BigDecimal("10.00");
        dto.totalIncludingTax = new BigDecimal("12.00");
        dto.totalVat = new BigDecimal("2.00");
        dto.signature = "SIG";
        dto.previousSignature = "PREV";
        dto.grandTotal = new BigDecimal("1000.00");
        dto.valuationStatus = "VALUATED";
        SyncPayloads.LineDto line = new SyncPayloads.LineDto();
        SyncPayloads.PaymentDto payment = new SyncPayloads.PaymentDto();
        dto.lines.add(line);
        dto.payments.add(payment);
        assertEquals("TCK1", dto.ticketNumber);
        assertEquals("T1", dto.terminalId);
        assertEquals("CLOSED", dto.status);
        assertEquals("2026-01-01T09:00:00", dto.creationDate);
        assertEquals("2026-01-01T09:05:00", dto.closingDate);
        assertEquals("ST1", dto.storeCode);
        assertEquals("jdoe", dto.cashierLogin);
        assertEquals("S1", dto.sessionNumber);
        assertEquals("FID1", dto.fidelityCard);
        assertEquals("DK1", dto.digitalKey);
        assertEquals("c@example.com", dto.customerEmail);
        assertEquals(3, dto.itemCount);
        assertEquals(new BigDecimal("10.00"), dto.totalExcludingTax);
        assertEquals(new BigDecimal("12.00"), dto.totalIncludingTax);
        assertEquals(new BigDecimal("2.00"), dto.totalVat);
        assertEquals("SIG", dto.signature);
        assertEquals("PREV", dto.previousSignature);
        assertEquals(new BigDecimal("1000.00"), dto.grandTotal);
        assertEquals("VALUATED", dto.valuationStatus);
        assertEquals(1, dto.lines.size());
        assertSameLine(line, dto.lines.get(0));
        assertEquals(1, dto.payments.size());
        assertSamePayment(payment, dto.payments.get(0));
    }

    /**
     * A default {@link SyncPayloads.TicketDto} leaves nullable fields null, its
     * int count zero, and both list fields non-null and empty.
     */
    @Test
    void ticketDtoDefaults() {
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        assertNull(dto.ticketNumber);
        assertNull(dto.closingDate);
        assertNull(dto.sessionNumber);
        assertNull(dto.fidelityCard);
        assertNull(dto.digitalKey);
        assertNull(dto.customerEmail);
        assertNull(dto.signature);
        assertNull(dto.previousSignature);
        assertNull(dto.grandTotal);
        assertEquals(0, dto.itemCount);
        assertNotNull(dto.lines);
        assertTrue(dto.lines.isEmpty());
        assertNotNull(dto.payments);
        assertTrue(dto.payments.isEmpty());
    }

    /**
     * A {@link SyncPayloads.LineDto} stores and returns each of its fields,
     * covering its int count and the deposit boolean flag set true.
     */
    @Test
    void lineDtoHoldsItsFields() {
        SyncPayloads.LineDto dto = new SyncPayloads.LineDto();
        dto.lineNumber = 1;
        dto.lineUid = "L-UID";
        dto.ean = "3760000000001";
        dto.plu = "42";
        dto.productLabel = "Milk";
        dto.quantity = new BigDecimal("2");
        dto.unitPrice = new BigDecimal("1.20");
        dto.vatRate = new BigDecimal("5.5");
        dto.modifierLabel = "REMISE 10%";
        dto.modifierType = "REMISE";
        dto.modifierValue = new BigDecimal("10");
        dto.originalUnitPrice = new BigDecimal("1.35");
        dto.totalPrice = new BigDecimal("2.40");
        dto.deposit = true;
        dto.familyCode = "FRUITS";
        dto.familyLabel = "Rayon Fruits";
        dto.cancelled = true;
        dto.cancellationDate = "2026-09-01T15:42:00";
        dto.cancelledBy = "12341234";
        assertEquals(1, dto.lineNumber);
        assertEquals("L-UID", dto.lineUid);
        assertEquals("3760000000001", dto.ean);
        assertEquals("42", dto.plu);
        assertEquals("Milk", dto.productLabel);
        assertEquals(new BigDecimal("2"), dto.quantity);
        assertEquals(new BigDecimal("1.20"), dto.unitPrice);
        assertEquals(new BigDecimal("5.5"), dto.vatRate);
        assertEquals("REMISE 10%", dto.modifierLabel);
        assertEquals("REMISE", dto.modifierType);
        assertEquals(new BigDecimal("10"), dto.modifierValue);
        assertEquals(new BigDecimal("1.35"), dto.originalUnitPrice);
        assertEquals(new BigDecimal("2.40"), dto.totalPrice);
        assertTrue(dto.deposit);
        assertEquals("FRUITS", dto.familyCode);
        assertEquals("Rayon Fruits", dto.familyLabel);
        assertTrue(dto.cancelled);
        assertEquals("2026-09-01T15:42:00", dto.cancellationDate);
        assertEquals("12341234", dto.cancelledBy);
    }

    /**
     * A default {@link SyncPayloads.LineDto} leaves nullable fields null, its
     * int count zero, and the deposit and cancelled flags false.
     */
    @Test
    void lineDtoDefaults() {
        SyncPayloads.LineDto dto = new SyncPayloads.LineDto();
        assertNull(dto.lineUid);
        assertNull(dto.ean);
        assertNull(dto.plu);
        assertNull(dto.modifierLabel);
        assertNull(dto.modifierType);
        assertNull(dto.modifierValue);
        assertNull(dto.originalUnitPrice);
        assertNull(dto.familyCode);
        assertNull(dto.familyLabel);
        assertEquals(0, dto.lineNumber);
        assertFalse(dto.deposit);
        assertFalse(dto.cancelled);
        assertNull(dto.cancellationDate);
        assertNull(dto.cancelledBy);
    }

    /**
     * A {@link SyncPayloads.PaymentDto} stores and returns each of its fields.
     */
    @Test
    void paymentDtoHoldsItsFields() {
        SyncPayloads.PaymentDto dto = new SyncPayloads.PaymentDto();
        dto.paymentIndex = 1;
        dto.methodKey = "CASH";
        dto.amount = new BigDecimal("12.00");
        dto.tenderedAmount = new BigDecimal("20.00");
        dto.voucherLabel = "Meal voucher";
        dto.voucherNumber = "V123";
        dto.authorizationNumber = "654321";
        dto.degradedMode = true;
        assertEquals(1, dto.paymentIndex);
        assertEquals("CASH", dto.methodKey);
        assertEquals(new BigDecimal("12.00"), dto.amount);
        assertEquals(new BigDecimal("20.00"), dto.tenderedAmount);
        assertEquals("Meal voucher", dto.voucherLabel);
        assertEquals("V123", dto.voucherNumber);
        assertEquals("654321", dto.authorizationNumber);
        org.junit.jupiter.api.Assertions.assertTrue(dto.degradedMode);
    }

    /**
     * A default {@link SyncPayloads.PaymentDto} leaves nullable fields null and
     * its int index zero.
     */
    @Test
    void paymentDtoDefaults() {
        SyncPayloads.PaymentDto dto = new SyncPayloads.PaymentDto();
        assertEquals(0, dto.paymentIndex);
        assertNull(dto.methodKey);
        assertNull(dto.amount);
        assertNull(dto.tenderedAmount);
        assertNull(dto.voucherLabel);
        assertNull(dto.voucherNumber);
        assertNull(dto.authorizationNumber);
        org.junit.jupiter.api.Assertions.assertFalse(dto.degradedMode);
    }

    /**
     * A {@link SyncPayloads.RefundDto} stores and returns each of its fields,
     * including its list field.
     */
    @Test
    void refundDtoHoldsItsFields() {
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        dto.refundNumber = "RF1";
        dto.terminalId = "T1";
        dto.status = "DONE";
        dto.refundMethod = "CASH";
        dto.originalTicketNumber = "TCK1";
        dto.sessionNumber = "S1";
        dto.creationDate = "2026-01-01T10:00:00";
        dto.totalAmount = new BigDecimal("12.00");
        dto.totalExcludingTax = new BigDecimal("10.00");
        dto.totalVat = new BigDecimal("2.00");
        SyncPayloads.RefundLineDto line = new SyncPayloads.RefundLineDto();
        dto.lines.add(line);
        assertEquals("RF1", dto.refundNumber);
        assertEquals("T1", dto.terminalId);
        assertEquals("DONE", dto.status);
        assertEquals("CASH", dto.refundMethod);
        assertEquals("TCK1", dto.originalTicketNumber);
        assertEquals("S1", dto.sessionNumber);
        assertEquals("2026-01-01T10:00:00", dto.creationDate);
        assertEquals(new BigDecimal("12.00"), dto.totalAmount);
        assertEquals(new BigDecimal("10.00"), dto.totalExcludingTax);
        assertEquals(new BigDecimal("2.00"), dto.totalVat);
        assertEquals(1, dto.lines.size());
        assertSameRefundLine(line, dto.lines.get(0));
    }

    /**
     * A default {@link SyncPayloads.RefundDto} leaves nullable fields null and
     * its list field non-null and empty.
     */
    @Test
    void refundDtoDefaults() {
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        assertNull(dto.refundNumber);
        assertNull(dto.refundMethod);
        assertNull(dto.sessionNumber);
        assertNull(dto.totalExcludingTax);
        assertNull(dto.totalVat);
        assertNotNull(dto.lines);
        assertTrue(dto.lines.isEmpty());
    }

    /**
     * An {@link SyncPayloads.EventDto} stores and returns each of its fields.
     */
    @Test
    void eventDtoHoldsItsFields() {
        SyncPayloads.EventDto dto = new SyncPayloads.EventDto();
        dto.eventUid = "EV1";
        dto.terminalId = "T1";
        dto.type = "DRAWER_OPEN";
        dto.detail = "manual";
        dto.operatorBadgeId = "12341234";
        dto.eventDate = "2026-01-01T11:00:00";
        assertEquals("EV1", dto.eventUid);
        assertEquals("T1", dto.terminalId);
        assertEquals("DRAWER_OPEN", dto.type);
        assertEquals("manual", dto.detail);
        assertEquals("12341234", dto.operatorBadgeId);
        assertEquals("2026-01-01T11:00:00", dto.eventDate);
    }

    /**
     * A default {@link SyncPayloads.EventDto} leaves nullable fields null.
     */
    @Test
    void eventDtoDefaults() {
        SyncPayloads.EventDto dto = new SyncPayloads.EventDto();
        assertNull(dto.eventUid);
        assertNull(dto.terminalId);
        assertNull(dto.type);
        assertNull(dto.detail);
        assertNull(dto.eventDate);
    }

    /**
     * A {@link SyncPayloads.RefundLineDto} stores and returns each of its fields.
     */
    @Test
    void refundLineDtoHoldsItsFields() {
        SyncPayloads.RefundLineDto dto = new SyncPayloads.RefundLineDto();
        dto.originalLineUid = "L-UID";
        dto.productLabel = "Milk";
        dto.quantity = new BigDecimal("1");
        dto.price = new BigDecimal("1.20");
        dto.vatRate = new BigDecimal("5.5");
        assertEquals("L-UID", dto.originalLineUid);
        assertEquals("Milk", dto.productLabel);
        assertEquals(new BigDecimal("1"), dto.quantity);
        assertEquals(new BigDecimal("1.20"), dto.price);
        assertEquals(new BigDecimal("5.5"), dto.vatRate);
    }

    /**
     * A default {@link SyncPayloads.RefundLineDto} leaves nullable fields null.
     */
    @Test
    void refundLineDtoDefaults() {
        SyncPayloads.RefundLineDto dto = new SyncPayloads.RefundLineDto();
        assertNull(dto.originalLineUid);
        assertNull(dto.productLabel);
        assertNull(dto.quantity);
        assertNull(dto.price);
        assertNull(dto.vatRate);
    }

    /**
     * A {@link SyncPayloads.MovementDto} stores and returns each of its fields,
     * and a default instance leaves every nullable field null (lot C5a).
     */
    @Test
    void movementDtoHoldsItsFields() {
        SyncPayloads.MovementDto fresh = new SyncPayloads.MovementDto();
        assertNull(fresh.movementUid);
        assertNull(fresh.terminalId);
        assertNull(fresh.sessionNumber);
        assertNull(fresh.cashierLogin);
        assertNull(fresh.type);
        assertNull(fresh.amount);
        assertNull(fresh.reason);
        assertNull(fresh.movementDate);
        assertNull(fresh.endorsedBy);
        SyncPayloads.MovementDto dto = new SyncPayloads.MovementDto();
        dto.movementUid = "M1";
        dto.terminalId = "C04";
        dto.sessionNumber = "C04-S00001";
        dto.cashierLogin = "jdupont";
        dto.type = "WITHDRAWAL";
        dto.amount = new BigDecimal("30.00");
        dto.reason = "coffre";
        dto.movementDate = "2026-09-01T15:42:00";
        dto.endorsedBy = "11111111";
        assertEquals("M1", dto.movementUid);
        assertEquals("C04", dto.terminalId);
        assertEquals("C04-S00001", dto.sessionNumber);
        assertEquals("jdupont", dto.cashierLogin);
        assertEquals("WITHDRAWAL", dto.type);
        assertEquals(new BigDecimal("30.00"), dto.amount);
        assertEquals("coffre", dto.reason);
        assertEquals("2026-09-01T15:42:00", dto.movementDate);
        assertEquals("11111111", dto.endorsedBy);
    }

    /**
     * Asserts that the given expected and actual line DTOs are the same object,
     * confirming list membership is preserved by identity.
     */
    private void assertSameLine(SyncPayloads.LineDto expected, SyncPayloads.LineDto actual) {
        assertTrue(expected == actual);
    }

    /**
     * Asserts that the given expected and actual payment DTOs are the same
     * object, confirming list membership is preserved by identity.
     */
    private void assertSamePayment(SyncPayloads.PaymentDto expected, SyncPayloads.PaymentDto actual) {
        assertTrue(expected == actual);
    }

    /**
     * Asserts that the given expected and actual refund line DTOs are the same
     * object, confirming list membership is preserved by identity.
     */
    private void assertSameRefundLine(SyncPayloads.RefundLineDto expected, SyncPayloads.RefundLineDto actual) {
        assertTrue(expected == actual);
    }
}
