package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TaxRefundForm}: the finders, the defaults and the
 * formatted amount to refund.
 */
class TaxRefundFormTest {

    /**
     * A null number finds nothing, a number is trimmed; the latest slips are
     * a page of at least one; a new slip is issued and refunds nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFindersAndTheDefaults() {
        TaxRefundForm form = new TaxRefundForm();
        assertEquals(TaxRefundForm.ISSUED, form.status);
        assertEquals(BigDecimal.ZERO, form.refundAmount);
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(form);
        when(query.page(0, 1)).thenReturn(query);
        when(query.list()).thenReturn(List.of(form));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("number", "BV1")).thenReturn(query);
            panache.when(() -> PanacheEntityBase.find("order by createdAt desc")).thenReturn(query);
            assertNull(TaxRefundForm.findByNumber(null));
            assertSame(form, TaxRefundForm.findByNumber(" BV1 "));
            assertEquals(List.of(form), TaxRefundForm.latest(0));
            verify(query).page(0, 1);
        }
    }

    /**
     * The amount to refund reads with two decimals, the decimal comma, the
     * thousands grouped and the euro sign.
     */
    @Test
    void theRefundIsFormatted() {
        TaxRefundForm form = new TaxRefundForm();
        form.refundAmount = new BigDecimal("1234.5");
        assertEquals("1 234,50 €", form.getRefundFormatted());
    }
}
