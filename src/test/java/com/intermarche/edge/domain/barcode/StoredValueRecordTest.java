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
 * Unit tests for {@link StoredValueRecord} (BO-03-06-48/62): its three finders
 * ask the queries the shop's registry relies on. No branch.
 */
class StoredValueRecordTest {

    /**
     * An instrument is found by the register that printed it and its number.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anInstrumentIsFoundByItsIssuer() {
        StoredValueRecord record = new StoredValueRecord();
        PanacheQuery<StoredValueRecord> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(record);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("issuingTerminalId = ?1 and number = ?2", "C04", "297"))
                    .thenReturn(query);
            assertSame(record, StoredValueRecord.findByIssuer("C04", "297"));
        }
    }

    /**
     * BO-03-06-48: on the central node an instrument is found by its shop, the
     * register that printed it and its number.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anInstrumentIsFoundByItsShopAndIssuer() {
        StoredValueRecord record = new StoredValueRecord();
        PanacheQuery<StoredValueRecord> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(record);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("pdvNumber = ?1 and issuingTerminalId = ?2 and number = ?3",
                    "1234", "C04", "297")).thenReturn(query);
            assertSame(record, StoredValueRecord.findByIssuer("1234", "C04", "297"));
        }
    }

    /**
     * An instrument is found by its number alone, the latest report first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anInstrumentIsFoundByItsNumber() {
        StoredValueRecord record = new StoredValueRecord();
        PanacheQuery<StoredValueRecord> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(record);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("number = ?1 order by updatedAt desc, id desc", "297"))
                    .thenReturn(query);
            assertSame(record, StoredValueRecord.findByNumber("297"));
        }
    }

    /**
     * The instruments printed over a period are listed most recent first.
     */
    @Test
    void theInstrumentsOfAPeriodAreListed() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        List<StoredValueRecord> records = List.of(new StoredValueRecord());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(
                    "issuedAt >= ?1 and issuedAt < ?2 order by issuedAt desc, id desc", from, to))
                    .thenReturn(records);
            assertEquals(records, StoredValueRecord.listIssuedBetween(from, to));
        }
    }
}
