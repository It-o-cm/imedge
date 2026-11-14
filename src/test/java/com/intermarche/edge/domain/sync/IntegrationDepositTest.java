package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link IntegrationDeposit}. */
class IntegrationDepositTest {

    /** BO-08-04-03 to -13: the replay reads the failed and partial deposits, the console the latest ones. */
    @Test
    @SuppressWarnings("unchecked")
    void findersReadTheDeposits() {
        List<Object> rows = List.of(new IntegrationDeposit());
        PanacheQuery<Object> ordered = mock(PanacheQuery.class);
        PanacheQuery<Object> page = mock(PanacheQuery.class);
        when(ordered.page(0, 30)).thenReturn(page);
        when(page.list()).thenReturn(rows);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("status in ?1 order by depositedAt",
                    List.of(IntegrationDeposit.Status.FAILED, IntegrationDeposit.Status.PARTIAL))).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.find("order by depositedAt desc, id desc")).thenReturn(ordered);
            assertSame(rows, IntegrationDeposit.retryable());
            assertSame(rows, IntegrationDeposit.latest(30));
        }
    }
}
