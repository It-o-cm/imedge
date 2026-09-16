package com.intermarche.pos.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.LocalDateTime;

/**
 * Unit tests for {@link BalanceTicket}, targeting full branch coverage of the
 * {@link BalanceTicket#isConsumed()} null-guard (both arms) plus the static
 * {@link BalanceTicket#findByReference(String)} finder.
 */
class BalanceTicketTest {

    /**
     * Rule: a freshly emitted ticket carries a null consumedAt.
     * Branch: isConsumed() null-guard, NULL arm -> not consumed.
     */
    @Test
    void isConsumedReturnsFalseWhenConsumedAtIsNull() {
        BalanceTicket ticket = new BalanceTicket();
        ticket.consumedAt = null;
        Assertions.assertFalse(ticket.isConsumed());
    }

    /**
     * Rule: once a register picks it up, consumedAt is set and the ticket reads as consumed.
     * Branch: isConsumed() null-guard, NON-NULL arm -> consumed.
     */
    @Test
    void isConsumedReturnsTrueWhenConsumedAtIsSet() {
        BalanceTicket ticket = new BalanceTicket();
        ticket.consumedAt = LocalDateTime.of(2026, 9, 11, 10, 30, 0);
        Assertions.assertTrue(ticket.isConsumed());
    }

    /**
     * Rule: findByReference delegates to the Panache static finder on the printed reference
     * and returns its first result.
     * Branch: none (straight-line delegation); asserts the finder wiring.
     */
    @Test
    void findByReferenceReturnsFirstResultOfReferenceQuery() {
        BalanceTicket expected = new BalanceTicket();
        expected.reference = "R-123";
        @SuppressWarnings("unchecked")
        PanacheQuery<BalanceTicket> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(expected);
        try (MockedStatic<PanacheEntityBase> statics = Mockito.mockStatic(PanacheEntityBase.class)) {
            statics.when(() -> PanacheEntityBase.find("reference", "R-123")).thenReturn(query);
            BalanceTicket actual = BalanceTicket.findByReference("R-123");
            Assertions.assertSame(expected, actual);
        }
    }
}
