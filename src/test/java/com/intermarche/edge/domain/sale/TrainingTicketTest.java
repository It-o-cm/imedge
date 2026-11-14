package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TrainingTicket}: its two finders, over a static Panache
 * mock.
 */
class TrainingTicketTest {

    /**
     * The copy of a register's row is found by its register and the register's id.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findBySourceReadsTheRegistersRow() {
        TrainingTicket training = new TrainingTicket();
        PanacheQuery<TrainingTicket> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(training);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> TrainingTicket.find("terminalId = ?1 and sourceId = ?2", "C04", 7L)).thenReturn(query);
            assertSame(training, TrainingTicket.findBySource("C04", 7L));
        }
    }

    /**
     * The training sales of a period are read most recent first, the carts
     * put on hold left out.
     */
    @Test
    void listBetweenReadsThePeriod() {
        TrainingTicket training = new TrainingTicket();
        LocalDateTime from = LocalDateTime.of(2026, 10, 24, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 25, 0, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> TrainingTicket.list("parkedAt is null and issuedAt >= ?1 and issuedAt < ?2 order by issuedAt desc",
                    from, to)).thenReturn(List.of(training));
            assertEquals(List.of(training), TrainingTicket.listBetween(from, to));
        }
    }
}
