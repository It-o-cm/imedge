package com.intermarche.edge.domain.sync;

/** Unit contracts for HD_43 referential and document coverage. */
class RefApplyAuditTest {
    /** BO-08/09: recent history is paged newest first; a scheduled-report limit cannot suppress the first result. */
    @org.junit.jupiter.api.Test
    void recentHistoryRetainsItsOrderingAndPageBound() {
        var query = org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        var rows = java.util.List.of(new RefApplyAudit());
        org.mockito.Mockito.when(query.page(0, 7)).thenReturn(query);
        org.mockito.Mockito.when(query.list()).thenReturn(rows);
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("order by appliedAt desc, id desc")).thenReturn(query);
            org.junit.jupiter.api.Assertions.assertSame(rows, RefApplyAudit.latest(7));
            org.mockito.Mockito.verify(query).page(0, 7);
        }
    }

}
