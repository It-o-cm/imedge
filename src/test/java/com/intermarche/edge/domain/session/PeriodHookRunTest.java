package com.intermarche.edge.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PeriodHookRun} (BO-09-02-04): the latest runs are paged
 * most recent first.
 */
class PeriodHookRunTest {

    /**
     * The latest runs are read newest first, within the page asked for.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theLatestRunsArePaged() {
        PeriodHookRun run = new PeriodHookRun();
        PanacheQuery<PanacheEntityBase> all = mock(PanacheQuery.class);
        when(all.page(0, 3)).thenReturn(all);
        when(all.list()).thenReturn(List.of(run));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> PanacheEntityBase.find("order by startedAt desc, id desc")).thenReturn(all);
            assertEquals(List.of(run), PeriodHookRun.latest(3));
        }
        assertEquals(PeriodHookRun.Phase.BEFORE, PeriodHookRun.Phase.valueOf("BEFORE"));
    }
}
