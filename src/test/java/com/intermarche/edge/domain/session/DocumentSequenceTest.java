package com.intermarche.edge.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BO-03-04: perpetual fiscal counters are isolated by document, register, shop and year. */
class DocumentSequenceTest {
    /** BO-03-04: the first use creates a locked scope starting at one, without touching an earlier year. */
    @Test
    void newScopeStartsAtOne() {
        var query = mock(PanacheQuery.class, RETURNS_SELF);
        try (var base = mockStatic(PanacheEntityBase.class);
             var created = mockConstruction(DocumentSequence.class)) {
            base.when(() -> PanacheEntityBase.find("documentType = ?1 and terminalId = ?2 and pdvNumber = ?3 and sequenceYear = ?4",
                    "FT", "C04", "PDV", 2026)).thenReturn(query);
            assertEquals(1L, DocumentSequence.next("FT", "C04", "PDV", 2026));
            assertEquals(1, created.constructed().size());
            var row = created.constructed().getFirst();
            assertEquals("FT", row.documentType);
            assertEquals("C04", row.terminalId);
            assertEquals("PDV", row.pdvNumber);
            assertEquals(2026, row.sequenceYear);
            assertEquals(1L, row.lastNumber);
            verify(query).withLock(LockModeType.PESSIMISTIC_WRITE);
            verify(row).persist();
        }
    }
}
