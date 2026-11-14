package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link NodeUpdateLog}. */
class NodeUpdateLogTest {

    /** BO-08-04-14: the latest steps are read newest first, one page. */
    @Test
    @SuppressWarnings("unchecked")
    void latestReadsOnePageNewestFirst() {
        List<Object> rows = List.of(new NodeUpdateLog());
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        PanacheQuery<Object> page = mock(PanacheQuery.class);
        when(query.page(0, 5)).thenReturn(page);
        when(page.list()).thenReturn(rows);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("order by loggedAt desc, id desc")).thenReturn(query);
            assertSame(rows, NodeUpdateLog.latest(5));
        }
    }
}
