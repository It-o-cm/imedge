package com.intermarche.edge.domain.session;

/** Unit contracts for HD_43 referential and document coverage. */
class ScheduledReportRunTest {
    /** BO-03/08: the finder selects the required business scope and ordering, preserving the returned rows. */
    @org.junit.jupiter.api.Test
    void ofPeriodUsesItsBusinessScopeAndOrder() {
        var row = new ScheduledReportRun();
        java.util.List<ScheduledReportRun> rows = java.util.List.of(row);
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("periodEnd = ?1 order by reportCode", java.time.LocalDate.of(2026, 10, 31))).thenReturn(rows);
            org.junit.jupiter.api.Assertions.assertSame(rows, ScheduledReportRun.ofPeriod(java.time.LocalDate.of(2026, 10, 31)));
            panache.verify(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("periodEnd = ?1 order by reportCode", java.time.LocalDate.of(2026, 10, 31)));
        }
    }

    /** BO-08/09: recent history is paged newest first; a scheduled-report limit cannot suppress the first result. */
    @org.junit.jupiter.api.Test
    void recentHistoryRetainsItsOrderingAndPageBound() {
        var query = org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        var rows = java.util.List.of(new ScheduledReportRun());
        org.mockito.Mockito.when(query.page(0, 1)).thenReturn(query);
        org.mockito.Mockito.when(query.list()).thenReturn(rows);
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("order by generatedAt desc, reportCode")).thenReturn(query);
            org.junit.jupiter.api.Assertions.assertSame(rows, ScheduledReportRun.latest(0));
            org.mockito.Mockito.verify(query).page(0, 1);
        }
    }

}
