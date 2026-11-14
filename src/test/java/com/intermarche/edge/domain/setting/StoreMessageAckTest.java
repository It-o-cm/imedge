package com.intermarche.edge.domain.setting;

/** Unit contracts for HD_43 referential and document coverage. */
class StoreMessageAckTest {
    /** BO-03/08: the finder selects the required business scope and ordering, preserving the returned rows. */
    @org.junit.jupiter.api.Test
    void listForUsesItsBusinessScopeAndOrder() {
        var row = new StoreMessageAck();
        java.util.List<StoreMessageAck> rows = java.util.List.of(row);
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("messageId = ?1 order by confirmedAt desc", 42L)).thenReturn(rows);
            org.junit.jupiter.api.Assertions.assertSame(rows, StoreMessageAck.listFor(42L));
            panache.verify(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("messageId = ?1 order by confirmedAt desc", 42L));
        }
    }

    /** BO-03-08-04: acknowledgements carry their reception time and count only the requested message. */
    @org.junit.jupiter.api.Test
    void acknowledgementsAreDatedAndCountedPerMessage() {
        var before = java.time.LocalDateTime.now();
        var ack = new StoreMessageAck();
        org.junit.jupiter.api.Assertions.assertFalse(ack.confirmedAt.isBefore(before));
        org.junit.jupiter.api.Assertions.assertFalse(ack.confirmedAt.isAfter(java.time.LocalDateTime.now()));
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.count("messageId", 42L)).thenReturn(3L);
            org.junit.jupiter.api.Assertions.assertEquals(3L, StoreMessageAck.countFor(42L));
        }
    }

}
