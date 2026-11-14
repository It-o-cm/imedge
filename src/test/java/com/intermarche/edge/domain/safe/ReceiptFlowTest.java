package com.intermarche.edge.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link ReceiptFlow} (BO-05-02-03): the reading of the column
 * mapping and the finders.
 */
class ReceiptFlowTest {

    /**
     * The mapping is read in order, upper case, a pair without both sides
     * ignored; no mapping reads as none.
     */
    @Test
    void theMappingIsReadInOrder() {
        ReceiptFlow flow = new ReceiptFlow();
        assertTrue(flow.columns().isEmpty());
        assertEquals(";", flow.separator);
        assertTrue(flow.active);
        flow.mapping = "reference = num ;AMOUNT=mt\nbroken;=nothing;LABEL=";
        assertEquals(Map.of("REFERENCE", "NUM", "AMOUNT", "MT"), flow.columns());
        assertEquals(List.of("REFERENCE", "AMOUNT"), List.copyOf(flow.columns().keySet()));
    }

    /**
     * A flow is found by its code, trimmed and upper case; a blank or null
     * code finds none; every flow is listed in code order.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aFlowIsFoundByItsCode() {
        ReceiptFlow flow = new ReceiptFlow();
        PanacheQuery<ReceiptFlow> byCode = mock(PanacheQuery.class);
        when(byCode.firstResult()).thenReturn(flow);
        PanacheQuery<ReceiptFlow> all = mock(PanacheQuery.class);
        when(all.list()).thenReturn(List.of(flow));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ReceiptFlow.find("code", "STATION")).thenReturn(byCode);
            panache.when(() -> ReceiptFlow.find("order by code")).thenReturn(all);
            assertSame(flow, ReceiptFlow.findByCode(" station "));
            assertEquals(List.of(flow), ReceiptFlow.listOrdered());
        }
        assertNull(ReceiptFlow.findByCode(null));
        assertNull(ReceiptFlow.findByCode(" "));
    }
}
