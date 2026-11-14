package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link FunctionUsage}. */
class FunctionUsageTest {

    /** BO-08-02-02: a counter is found by its day, node, channel and function. */
    @Test
    @SuppressWarnings("unchecked")
    void findKeyQueriesTheFourColumns() {
        FunctionUsage row = new FunctionUsage();
        LocalDate day = LocalDate.of(2026, 10, 24);
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "usageDay = ?1 and nodeId = ?2 and channel = ?3 and functionCode = ?4",
                    day, "C04", FunctionUsage.REGISTER, "/action/pay")).thenReturn(query);
            assertSame(row, FunctionUsage.findKey(day, "C04", FunctionUsage.REGISTER, "/action/pay"));
        }
    }

    /** BO-08-02-02: the counters from a day on are read oldest first. */
    @Test
    void sinceListsFromTheDay() {
        LocalDate day = LocalDate.of(2026, 10, 20);
        List<Object> rows = List.of(new FunctionUsage());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("usageDay >= ?1 order by usageDay, nodeId, functionCode", day))
                    .thenReturn(rows);
            assertSame(rows, FunctionUsage.since(day));
        }
    }
}
