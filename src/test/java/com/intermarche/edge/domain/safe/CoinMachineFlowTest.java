package com.intermarche.edge.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link CoinMachineFlow} (BO-05-02-16): the journal finders and
 * the amounts formatted for the screen.
 */
class CoinMachineFlowTest {

    /**
     * The latest flows of one machine, and of every machine, are read newest
     * first, bounded.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theLatestFlowsAreReadNewestFirst() {
        CoinMachineFlow flow = new CoinMachineFlow();
        PanacheQuery<CoinMachineFlow> one = mock(PanacheQuery.class);
        PanacheQuery<CoinMachineFlow> all = mock(PanacheQuery.class);
        when(one.page(0, 5)).thenReturn(one);
        when(one.list()).thenReturn(List.of(flow));
        when(all.page(0, 7)).thenReturn(all);
        when(all.list()).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinMachineFlow.find("machineCode = ?1 order by recordedAt desc, id desc", "MON-01"))
                    .thenReturn(one);
            panache.when(() -> CoinMachineFlow.find("order by recordedAt desc, id desc")).thenReturn(all);
            assertSame(flow, CoinMachineFlow.latestOf("MON-01", 5).get(0));
            assertEquals(List.of(), CoinMachineFlow.latest(7));
        }
    }

    /**
     * The amount and the running total are shown with two decimals; a new flow
     * moves nothing.
     */
    @Test
    void theAmountsAreFormattedWithTwoDecimals() {
        CoinMachineFlow flow = new CoinMachineFlow();
        assertEquals("0.00", flow.getAmountFormatted());
        flow.amount = new BigDecimal("-12.505");
        flow.balanceAfter = new BigDecimal("287.4");
        assertEquals("-12.51", flow.getAmountFormatted());
        assertEquals("287.40", flow.getBalanceAfterFormatted());
    }
}
