package com.intermarche.edge.domain.fiscal;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link FiscalArchive} (BO-05-01-01, BO-05-01-04): the kinds and
 * their file prefixes, the finders and the size shown.
 */
class FiscalArchiveTest {

    /**
     * Each kind names its files after its own prefix.
     */
    @Test
    void eachKindHasItsPrefix() {
        assertEquals("TaxAuditExtracts", FiscalArchive.Kind.TAX_AUDIT_EXTRACTS.getPrefix());
        assertEquals("TaxAuditReports", FiscalArchive.Kind.TAX_AUDIT_REPORTS.getPrefix());
        assertEquals("SAFT-PT", FiscalArchive.Kind.SAFT_MONTHLY.getPrefix());
    }

    /**
     * The files of a period and the latest ones are read through Panache.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFilesAreFoundByPeriodAndByDate() {
        FiscalArchive archive = new FiscalArchive();
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        PanacheQuery<FiscalArchive> period = mock(PanacheQuery.class);
        when(period.list()).thenReturn(List.of(archive));
        PanacheQuery<FiscalArchive> latest = mock(PanacheQuery.class);
        when(latest.page(0, 5)).thenReturn(latest);
        when(latest.list()).thenReturn(List.of(archive));
        List<FiscalArchive.Kind> kinds = List.of(FiscalArchive.Kind.SAFT_MONTHLY);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> FiscalArchive.find("kind = ?1 and periodFrom = ?2 and periodTo = ?3 order by id desc",
                    FiscalArchive.Kind.TAX_AUDIT_EXTRACTS, from, to)).thenReturn(period);
            panache.when(() -> FiscalArchive.find("kind in ?1 order by generatedAt desc, id desc", kinds))
                    .thenReturn(latest);
            assertEquals(List.of(archive), FiscalArchive.forPeriod(FiscalArchive.Kind.TAX_AUDIT_EXTRACTS, from, to));
            assertEquals(List.of(archive), FiscalArchive.latest(kinds, 5));
        }
    }

    /**
     * The size is shown in kilobytes, rounded up.
     */
    @Test
    void theSizeIsShownInKilobytes() {
        FiscalArchive archive = new FiscalArchive();
        assertEquals(0, archive.getSizeKb());
        archive.sizeBytes = 1;
        assertEquals(1, archive.getSizeKb());
        archive.sizeBytes = 2049;
        assertEquals(3, archive.getSizeKb());
    }
}
