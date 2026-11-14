package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

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
 * Unit tests for {@link EcommerceOrder}: the finder by reference short-circuits
 * a missing reference and trims a given one, the listing is the most recent
 * first, and the outcome reads off the status.
 */
class EcommerceOrderTest {

    /**
     * A null or blank reference names nothing without a query; a given one is
     * looked up trimmed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findByReferenceTrimsOrShortCircuits() {
        EcommerceOrder order = new EcommerceOrder();
        PanacheQuery<EcommerceOrder> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(order);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> EcommerceOrder.find("reference", "W1")).thenReturn(query);
            assertNull(EcommerceOrder.findByReference(null));
            assertNull(EcommerceOrder.findByReference(" "));
            assertSame(order, EcommerceOrder.findByReference(" W1 "));
        }
    }

    /**
     * The back-office listing asks for the most recent first.
     */
    @Test
    void listAllOrderedIsTheMostRecentFirst() {
        EcommerceOrder order = new EcommerceOrder();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> EcommerceOrder.list("order by receivedAt desc, id desc")).thenReturn(List.of(order));
            assertEquals(List.of(order), EcommerceOrder.listAllOrdered());
        }
    }

    /**
     * An integrated order reads as integrated, a rejected one does not.
     */
    @Test
    void theOutcomeReadsOffTheStatus() {
        EcommerceOrder order = new EcommerceOrder();
        order.status = EcommerceOrder.Status.INTEGRATED;
        assertTrue(order.isIntegrated());
        order.status = EcommerceOrder.Status.REJECTED;
        assertFalse(order.isIntegrated());
    }

    /**
     * DG-03-01-05: an order paid short reads as pending payment, no other.
     */
    @Test
    void anOrderPaidShortReadsAsPendingPayment() {
        EcommerceOrder order = new EcommerceOrder();
        order.status = EcommerceOrder.Status.PENDING_PAYMENT;
        assertTrue(order.isPendingPayment());
        assertFalse(order.isIntegrated());
        order.status = EcommerceOrder.Status.INTEGRATED;
        assertFalse(order.isPendingPayment());
        assertFalse(order.isSettled());
        order.status = EcommerceOrder.Status.SETTLED;
        assertTrue(order.isSettled());
    }
}
