package com.intermarche.pos.domain.session;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TicketCounter}, targeting 100% branch coverage.
 * <p>
 * The class is a pure Panache entity: it declares no method and no conditional
 * logic, so it carries no ternary or null guard to split. The only executable
 * code is the field initializer {@code grandTotal = BigDecimal.ZERO} run by the
 * constructor. Coverage is therefore complete once the constructor default and
 * the read/write of every public field are asserted. No static finder or
 * persist is reached, so no Panache mocking is required. Each test is fully
 * isolated and asserts absolute expected values.
 */
class TicketCounterTest {

    /**
     * A freshly constructed counter zeroes grandTotal and leaves every other
     * field at its JVM default (null references, 0L longs).
     */
    @Test
    void defaultsAreZeroed() {
        TicketCounter counter = new TicketCounter();
        Assertions.assertEquals(BigDecimal.ZERO, counter.grandTotal);
        Assertions.assertNull(counter.terminalId);
        Assertions.assertNull(counter.lastSignature);
        Assertions.assertEquals(0L, counter.lastNumber);
        Assertions.assertEquals(0L, counter.lastSessionNumber);
        Assertions.assertEquals(0L, counter.lastRefundNumber);
    }

    /**
     * All public fields accept and return their assigned values.
     */
    @Test
    void fieldsAreReadWrite() {
        TicketCounter counter = new TicketCounter();
        counter.terminalId = "C04";
        counter.lastNumber = 123L;
        counter.lastSessionNumber = 12L;
        counter.lastRefundNumber = 7L;
        counter.lastSignature = "abcdef0123456789";
        counter.grandTotal = new BigDecimal("1000.5000");
        Assertions.assertEquals("C04", counter.terminalId);
        Assertions.assertEquals(123L, counter.lastNumber);
        Assertions.assertEquals(12L, counter.lastSessionNumber);
        Assertions.assertEquals(7L, counter.lastRefundNumber);
        Assertions.assertEquals("abcdef0123456789", counter.lastSignature);
        Assertions.assertEquals(new BigDecimal("1000.5000"), counter.grandTotal);
    }
}
