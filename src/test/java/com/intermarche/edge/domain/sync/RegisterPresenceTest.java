package com.intermarche.edge.domain.sync;

/** Unit coverage of the referential null and identity contracts. */
class RegisterPresenceTest {

    /** BO-13: terminal lookup refuses absent identities and normalizes the configured identifier. */
    @org.junit.jupiter.api.Test
    void coverageNullableAndTrimmedTerminalLookup() {
        org.junit.jupiter.api.Assertions.assertNull(RegisterPresence.findByTerminal(null));
        org.junit.jupiter.api.Assertions.assertNull(RegisterPresence.findByTerminal(" "));
        RegisterPresence row = new RegisterPresence();
        var query = org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        org.mockito.Mockito.when(query.firstResult()).thenReturn(row);
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("terminalId", "C04")).thenReturn(query);
            org.junit.jupiter.api.Assertions.assertSame(row, RegisterPresence.findByTerminal(" C04 "));
        }
    }
    /** BO-03/08: the finder selects the required business scope and ordering, preserving the returned rows. */
    @org.junit.jupiter.api.Test
    void allByAgeUsesItsBusinessScopeAndOrder() {
        var row = new RegisterPresence();
        java.util.List<RegisterPresence> rows = java.util.List.of(row);
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("order by lastSeenAt")).thenReturn(rows);
            org.junit.jupiter.api.Assertions.assertSame(rows, RegisterPresence.allByAge());
            panache.verify(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("order by lastSeenAt"));
        }
    }

}
