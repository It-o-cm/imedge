package com.intermarche.edge.domain.fiscal;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link EInvoice} (BO-05-01-02): the life cycle and its codes,
 * the history, the formatted total and the finders.
 */
class EInvoiceTest {

    /**
     * A status is read by its code or its name, case ignored; anything else
     * reads as none; the final statuses end the cycle.
     */
    @Test
    void aStatusIsReadByCodeOrName() {
        assertEquals(EInvoice.Status.REJECTED, EInvoice.Status.of("213"));
        assertEquals(EInvoice.Status.CASHED, EInvoice.Status.of(" cashed "));
        assertNull(EInvoice.Status.of("999"));
        assertNull(EInvoice.Status.of(" "));
        assertNull(EInvoice.Status.of(null));
        assertEquals("Encaissée", EInvoice.Status.CASHED.getLabel());
        assertTrue(EInvoice.Status.REFUSED.isLast());
        assertFalse(EInvoice.Status.DEPOSITED.isLast());
        assertEquals(0, EInvoice.Status.PENDING.getCode());
    }

    /**
     * Every move is kept in the history, which keeps its last 2000 characters.
     */
    @Test
    void everyMoveIsKeptInTheHistory() {
        EInvoice invoice = new EInvoice();
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 9, 0, 0, 500);
        invoice.move(EInvoice.Status.PENDING, at);
        invoice.move(EInvoice.Status.DEPOSITED, at);
        assertEquals("0@2026-10-01T09:00;200@2026-10-01T09:00", invoice.history);
        assertEquals(EInvoice.Status.DEPOSITED, invoice.status);
        assertSame(at, invoice.statusAt);
        invoice.history = "";
        invoice.move(EInvoice.Status.APPROVED, at);
        assertEquals("205@2026-10-01T09:00", invoice.history);
        invoice.history = "x".repeat(1995);
        invoice.move(EInvoice.Status.CASHED, at);
        assertEquals(2000, invoice.history.length());
        assertTrue(invoice.history.endsWith(";212@2026-10-01T09:00"));
    }

    /**
     * The finders read through Panache: by number, by platform identifier, the
     * deposits due, the refreshes due and the latest of one status or all.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFindersReadThroughPanache() {
        EInvoice invoice = new EInvoice();
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 9, 0);
        PanacheQuery<EInvoice> one = mock(PanacheQuery.class);
        when(one.firstResult()).thenReturn(invoice);
        PanacheQuery<EInvoice> many = mock(PanacheQuery.class);
        when(many.page(0, 10)).thenReturn(many);
        when(many.list()).thenReturn(List.of(invoice));
        List<EInvoice.Status> open = new ArrayList<>();
        for (EInvoice.Status status : EInvoice.Status.values()) {
            if (status.getCode() >= 200 && !status.isLast()) {
                open.add(status);
            }
        }
        String due = "status in ?1 and (nextAttemptAt is null or nextAttemptAt <= ?2) order by id";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EInvoice.find("invoiceNumber", "F1")).thenReturn(one);
            panache.when(() -> EInvoice.find("platformId", "P1")).thenReturn(one);
            panache.when(() -> EInvoice.find(due, List.of(EInvoice.Status.PENDING, EInvoice.Status.FAILED), now))
                    .thenReturn(many);
            panache.when(() -> EInvoice.find(due, open, now)).thenReturn(many);
            panache.when(() -> EInvoice.find("order by id desc")).thenReturn(many);
            panache.when(() -> EInvoice.find("status = ?1 order by id desc", EInvoice.Status.REJECTED))
                    .thenReturn(many);
            assertSame(invoice, EInvoice.findByNumber("F1"));
            assertSame(invoice, EInvoice.findByPlatformId("P1"));
            assertEquals(List.of(invoice), EInvoice.dueForDeposit(now, 10));
            assertEquals(List.of(invoice), EInvoice.dueForRefresh(now, 10));
            assertEquals(List.of(invoice), EInvoice.latest(null, 10));
            assertEquals(List.of(invoice), EInvoice.latest(EInvoice.Status.REJECTED, 10));
        }
        assertEquals(10, open.size());
    }
}
