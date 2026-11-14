package com.intermarche.edge.domain.deploy;

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

/** Unit tests for {@link UplinkedTicket}. */
class UplinkedTicketTest {

    /** BO-02-05-05: the journal type follows the status, then the sign of the total. */
    @Test
    void theKindFollowsTheStatusThenTheSign() {
        assertEquals(UplinkedTicket.CANCELLED, UplinkedTicket.kindOf("CANCELLED", new BigDecimal("-3")));
        assertEquals(UplinkedTicket.NEGATIVE, UplinkedTicket.kindOf("CLOSED", new BigDecimal("-3")));
        assertEquals(UplinkedTicket.SALE, UplinkedTicket.kindOf("CLOSED", BigDecimal.ZERO));
        assertEquals(UplinkedTicket.SALE, UplinkedTicket.kindOf(null, null));
    }

    /** The finders read the first row of their query. */
    @Test
    @SuppressWarnings("unchecked")
    void theFindersReadTheFirstRow() {
        UplinkedTicket row = new UplinkedTicket();
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            List<String> numbers = List.of("10001");
            panache.when(() -> PanacheEntityBase.find("pdvNumber in ?1 and ticketNumber = ?2", numbers, "T1"))
                    .thenReturn(query);
            panache.when(() -> PanacheEntityBase.find("pdvNumber in ?1 order by sourceUpdatedAt desc, id desc",
                    numbers)).thenReturn(query);
            assertSame(row, UplinkedTicket.findFiled(numbers, "T1"));
            assertSame(row, UplinkedTicket.latestOf(numbers));
        }
    }
}
