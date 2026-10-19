package com.intermarche.edge.domain.sale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Unit tests for {@link TicketLineValuation}, targeting 100% branch coverage.
 * <p>
 * The class is a pure Panache trace entity: it carries only public fields and
 * the implicit default constructor, with no branching logic whatsoever. Branch
 * coverage is therefore complete once the entity is instantiated. The tests
 * assert that a freshly constructed instance leaves every field at its declared
 * default (all null, as none are primitives) and that each field round-trips the
 * value assigned to it. No static finder or persist is reached, so no Panache
 * mocking is required. Each test is fully isolated and asserts absolute expected
 * values.
 */
class TicketLineValuationTest {

    /**
     * The default constructor leaves the valued ticket unset (null).
     */
    @Test
    void defaultConstructorLeavesTicketNull() {
        TicketLineValuation valuation = new TicketLineValuation();
        Assertions.assertNull(valuation.ticket);
    }

    /**
     * The default constructor leaves the line uid unset (null).
     */
    @Test
    void defaultConstructorLeavesLineUidNull() {
        TicketLineValuation valuation = new TicketLineValuation();
        Assertions.assertNull(valuation.lineUid);
    }

    /**
     * The default constructor leaves the local total unset (null).
     */
    @Test
    void defaultConstructorLeavesLocalTotalNull() {
        TicketLineValuation valuation = new TicketLineValuation();
        Assertions.assertNull(valuation.localTotal);
    }

    /**
     * The default constructor leaves the valued total unset (null).
     */
    @Test
    void defaultConstructorLeavesValuedTotalNull() {
        TicketLineValuation valuation = new TicketLineValuation();
        Assertions.assertNull(valuation.valuedTotal);
    }

    /**
     * The default constructor leaves the offer label unset (null).
     */
    @Test
    void defaultConstructorLeavesOfferLabelNull() {
        TicketLineValuation valuation = new TicketLineValuation();
        Assertions.assertNull(valuation.offerLabel);
    }

    /**
     * The default constructor leaves the advantage label unset (null).
     */
    @Test
    void defaultConstructorLeavesAdvantageLabelNull() {
        TicketLineValuation valuation = new TicketLineValuation();
        Assertions.assertNull(valuation.advantageLabel);
    }

    /**
     * The default constructor leaves the advantage amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAdvantageAmountNull() {
        TicketLineValuation valuation = new TicketLineValuation();
        Assertions.assertNull(valuation.advantageAmount);
    }

    /**
     * The default constructor leaves the correction label unset (null).
     */
    @Test
    void defaultConstructorLeavesCorrectionLabelNull() {
        TicketLineValuation valuation = new TicketLineValuation();
        Assertions.assertNull(valuation.correctionLabel);
    }

    /**
     * The default constructor leaves the correction amount unset (null).
     */
    @Test
    void defaultConstructorLeavesCorrectionAmountNull() {
        TicketLineValuation valuation = new TicketLineValuation();
        Assertions.assertNull(valuation.correctionAmount);
    }

    /**
     * The valued ticket field round-trips the reference assigned to it.
     */
    @Test
    void ticketFieldRoundTripsAssignedReference() {
        TicketLineValuation valuation = new TicketLineValuation();
        Ticket ticket = new Ticket();
        valuation.ticket = ticket;
        Assertions.assertSame(ticket, valuation.ticket);
    }

    /**
     * The line uid field round-trips the value assigned to it.
     */
    @Test
    void lineUidFieldRoundTripsAssignedValue() {
        TicketLineValuation valuation = new TicketLineValuation();
        valuation.lineUid = "line-42";
        Assertions.assertEquals("line-42", valuation.lineUid);
    }

    /**
     * The local total field round-trips the value assigned to it.
     */
    @Test
    void localTotalFieldRoundTripsAssignedValue() {
        TicketLineValuation valuation = new TicketLineValuation();
        BigDecimal localTotal = new BigDecimal("12.50");
        valuation.localTotal = localTotal;
        Assertions.assertSame(localTotal, valuation.localTotal);
    }

    /**
     * The valued total field round-trips the value assigned to it.
     */
    @Test
    void valuedTotalFieldRoundTripsAssignedValue() {
        TicketLineValuation valuation = new TicketLineValuation();
        BigDecimal valuedTotal = new BigDecimal("9.90");
        valuation.valuedTotal = valuedTotal;
        Assertions.assertSame(valuedTotal, valuation.valuedTotal);
    }

    /**
     * The offer label field round-trips the value assigned to it.
     */
    @Test
    void offerLabelFieldRoundTripsAssignedValue() {
        TicketLineValuation valuation = new TicketLineValuation();
        valuation.offerLabel = "OFFER-1";
        Assertions.assertEquals("OFFER-1", valuation.offerLabel);
    }

    /**
     * The advantage label field round-trips the value assigned to it.
     */
    @Test
    void advantageLabelFieldRoundTripsAssignedValue() {
        TicketLineValuation valuation = new TicketLineValuation();
        valuation.advantageLabel = "ADV-1";
        Assertions.assertEquals("ADV-1", valuation.advantageLabel);
    }

    /**
     * The advantage amount field round-trips the value assigned to it.
     */
    @Test
    void advantageAmountFieldRoundTripsAssignedValue() {
        TicketLineValuation valuation = new TicketLineValuation();
        BigDecimal advantageAmount = new BigDecimal("1.10");
        valuation.advantageAmount = advantageAmount;
        Assertions.assertSame(advantageAmount, valuation.advantageAmount);
    }

    /**
     * The correction label field round-trips the value assigned to it.
     */
    @Test
    void correctionLabelFieldRoundTripsAssignedValue() {
        TicketLineValuation valuation = new TicketLineValuation();
        valuation.correctionLabel = "CORR-1";
        Assertions.assertEquals("CORR-1", valuation.correctionLabel);
    }

    /**
     * The correction amount field round-trips the value assigned to it.
     */
    @Test
    void correctionAmountFieldRoundTripsAssignedValue() {
        TicketLineValuation valuation = new TicketLineValuation();
        BigDecimal correctionAmount = new BigDecimal("2.20");
        valuation.correctionAmount = correctionAmount;
        Assertions.assertSame(correctionAmount, valuation.correctionAmount);
    }
}
