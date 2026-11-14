package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcommerceReturn} (DG-01-03): a return is found by its
 * trimmed reference, and the back office lists them most recent first.
 */
class EcommerceReturnTest {

    /**
     * A null or blank reference finds nothing without a query; a reference is
     * looked up trimmed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findByReferenceTrimsOrShortCircuits() {
        EcommerceReturn row = new EcommerceReturn();
        PanacheQuery<EcommerceReturn> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> EcommerceReturn.find("reference", "R1")).thenReturn(query);
            assertNull(EcommerceReturn.findByReference(null));
            assertNull(EcommerceReturn.findByReference(" "));
            assertSame(row, EcommerceReturn.findByReference(" R1 "));
        }
    }

    /**
     * The back-office listing asks for the most recent first.
     */
    @Test
    void listAllOrderedIsTheMostRecentFirst() {
        EcommerceReturn row = new EcommerceReturn();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> EcommerceReturn.list("order by receivedAt desc, id desc")).thenReturn(List.of(row));
            assertEquals(List.of(row), EcommerceReturn.listAllOrdered());
        }
    }
}
