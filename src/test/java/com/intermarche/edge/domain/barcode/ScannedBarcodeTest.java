package com.intermarche.edge.domain.barcode;

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
 * Unit tests for {@link ScannedBarcode} (BO-03-06-63): its two finders ask the
 * queries the journal relies on. No branch.
 */
class ScannedBarcodeTest {

    /**
     * A reading is found by the register that read it and its row there.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aReadingIsFoundByItsSource() {
        ScannedBarcode reading = new ScannedBarcode();
        PanacheQuery<ScannedBarcode> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(reading);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("terminalId = ?1 and sourceId = ?2", "C04", 7L))
                    .thenReturn(query);
            assertSame(reading, ScannedBarcode.findBySource("C04", 7L));
        }
    }

    /**
     * The readings of a period are listed most recent first.
     */
    @Test
    void theReadingsOfAPeriodAreListed() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        List<ScannedBarcode> readings = List.of(new ScannedBarcode());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(
                    "scannedAt >= ?1 and scannedAt < ?2 order by scannedAt desc, id desc", from, to))
                    .thenReturn(readings);
            assertEquals(readings, ScannedBarcode.listBetween(from, to));
        }
    }
}
