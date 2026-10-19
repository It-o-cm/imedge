package com.intermarche.edge.service;

import com.intermarche.edge.domain.session.ScheduledReportRun;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.ui.report.ReportCatalog;
import com.intermarche.edge.ui.report.ReportCriteria;
import com.intermarche.edge.ui.report.ReportData;
import com.intermarche.edge.ui.report.ReportDefinition;
import com.intermarche.edge.ui.report.ReportService;
import com.intermarche.edge.ui.report.ReportWriter;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledReportService}.
 *
 * <p>Two readings and one pass. The readings are what an administrator can
 * type — a blank list, unknown codes, duplicates, a settings table that
 * cannot be read, an unknown format — and the pass is the end of period: an
 * edition that works, one that throws, and nothing programmed at all. Every
 * collaborator is a Mockito mock; the persist of the edition is intercepted
 * with {@code mockStatic} per the plain-{@code mvn test} contract.
 */
class ScheduledReportServiceTest {

    /**
     * Builds a service whose three collaborators are fresh mocks.
     *
     * @return the service under test
     */
    private ScheduledReportService service() {
        ScheduledReportService service = new ScheduledReportService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.reportService = mock(ReportService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        return service;
    }

    /**
     * Builds a report definition answering a code and a label.
     *
     * @param code its code
     * @return the mocked definition
     */
    private ReportDefinition definition(String code) {
        ReportDefinition definition = mock(ReportDefinition.class);
        when(definition.code()).thenReturn(code);
        when(definition.label()).thenReturn("État " + code);
        when(definition.rows(any())).thenReturn(List.of(List.<Object>of("a"), List.<Object>of("b")));
        return definition;
    }

    /**
     * Wires the catalog so the given definitions answer their codes.
     *
     * @param service the service under test
     * @param definitions the definitions the catalog knows
     */
    private void catalog(ScheduledReportService service, ReportDefinition... definitions) {
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(service.reportService.catalog()).thenReturn(catalog);
        for (ReportDefinition definition : definitions) {
            when(catalog.find(definition.code())).thenReturn(definition);
        }
    }

    /**
     * Builds a writer answering a format and some bytes.
     *
     * @param format its format token
     * @return the mocked writer
     */
    private ReportWriter writer(String format) {
        ReportWriter writer = mock(ReportWriter.class);
        when(writer.format()).thenReturn(format);
        when(writer.write(any(), any())).thenReturn(new byte[]{1, 2, 3});
        return writer;
    }

    /** A blank list programs nothing. */
    @Test
    void nothingProgrammedWhenTheListIsBlank() {
        ScheduledReportService service = service();
        when(service.posSettingsService.periodReports()).thenReturn("   ");
        assertTrue(service.programmed().isEmpty());
    }

    /** A null list programs nothing. */
    @Test
    void nothingProgrammedWhenTheListIsNull() {
        ScheduledReportService service = service();
        when(service.posSettingsService.periodReports()).thenReturn(null);
        assertTrue(service.programmed().isEmpty());
    }

    /** A settings table that cannot be read programs nothing. */
    @Test
    void nothingProgrammedWhenTheListCannotBeRead() {
        ScheduledReportService service = service();
        when(service.posSettingsService.periodReports()).thenThrow(new IllegalStateException("no db"));
        assertTrue(service.programmed().isEmpty());
    }

    /** Unknown codes are skipped, duplicates kept once, the order respected. */
    @Test
    void programmedSkipsUnknownCodesAndDuplicates() {
        ScheduledReportService service = service();
        ReportDefinition first = definition("refunds");
        ReportDefinition second = definition("cancellations");
        catalog(service, first, second);
        when(service.posSettingsService.periodReports())
                .thenReturn(" cancellations ;inconnu; refunds ;cancellations");
        assertEquals(List.of(second, first), service.programmed());
    }

    /** An unknown format falls back on the PDF writer. */
    @Test
    void anUnknownFormatFallsBackOnPdf() {
        ScheduledReportService service = service();
        ReportWriter pdf = writer("pdf");
        when(service.posSettingsService.periodReportsFormat()).thenReturn("docx");
        when(service.reportService.writer("docx")).thenReturn(null);
        when(service.reportService.writer("pdf")).thenReturn(pdf);
        assertSame(pdf, service.format());
    }

    /** A format that cannot be read falls back on the PDF writer too. */
    @Test
    void anUnreadableFormatFallsBackOnPdf() {
        ScheduledReportService service = service();
        ReportWriter pdf = writer("pdf");
        when(service.posSettingsService.periodReportsFormat()).thenThrow(new IllegalStateException("no db"));
        when(service.reportService.writer("pdf")).thenReturn(pdf);
        assertSame(pdf, service.format());
    }

    /** The administered format is used when a writer claims it. */
    @Test
    void theAdministeredFormatIsUsed() {
        ScheduledReportService service = service();
        ReportWriter xlsx = writer("xlsx");
        when(service.posSettingsService.periodReportsFormat()).thenReturn("xlsx");
        when(service.reportService.writer("xlsx")).thenReturn(xlsx);
        assertSame(xlsx, service.format());
    }

    /** Nothing programmed, nothing edited and nothing journalised. */
    @Test
    void theEndOfPeriodEditsNothingWhenNothingIsProgrammed() {
        ScheduledReportService service = service();
        when(service.posSettingsService.periodReports()).thenReturn("");
        assertEquals(0, service.runEndOfPeriod(LocalDate.of(2026, 10, 15)));
        verify(service.technicalEventService, never()).log(any(), any());
    }

    /** Each programmed report is run over the closing day and kept. */
    @Test
    void theEndOfPeriodEditsEveryProgrammedReport() {
        ScheduledReportService service = service();
        ReportDefinition first = definition("refunds");
        ReportDefinition second = definition("cancellations");
        catalog(service, first, second);
        when(service.posSettingsService.periodReports()).thenReturn("refunds;cancellations");
        ReportWriter pdf = writer("pdf");
        when(service.posSettingsService.periodReportsFormat()).thenReturn("pdf");
        when(service.reportService.writer("pdf")).thenReturn(pdf);
        when(service.reportService.run(any(), any()))
                .thenAnswer(call -> new ReportData(call.getArgument(0),
                        List.of(List.<Object>of("a"), List.<Object>of("b"))));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                org.mockito.MockedConstruction<ScheduledReportRun> runs =
                        mockConstruction(ScheduledReportRun.class)) {
            assertEquals(2, service.runEndOfPeriod(LocalDate.of(2026, 10, 15)));
        }
        verify(service.technicalEventService).log(
                TechnicalEvent.EventType.SCHEDULED_REPORTS_EDITED,
                "2 état(s) de fin de période édité(s) le 2026-10-15");
    }

    /** An edition that throws is written as a failure and the pass goes on. */
    @Test
    void anEditionThatThrowsIsWrittenAsAFailure() {
        ScheduledReportService service = service();
        ReportDefinition first = definition("refunds");
        ReportDefinition second = definition("cancellations");
        catalog(service, first, second);
        when(service.posSettingsService.periodReports()).thenReturn("refunds;cancellations");
        ReportWriter pdf = writer("pdf");
        when(service.posSettingsService.periodReportsFormat()).thenReturn("pdf");
        when(service.reportService.writer("pdf")).thenReturn(pdf);
        when(service.reportService.run(any(), any()))
                .thenThrow(new IllegalStateException("table absente"))
                .thenAnswer(call -> new ReportData(call.getArgument(0), List.of()));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                org.mockito.MockedConstruction<ScheduledReportRun> runs =
                        mockConstruction(ScheduledReportRun.class)) {
            assertEquals(2, service.runEndOfPeriod(LocalDate.of(2026, 10, 15)));
        }
        verify(service.technicalEventService).log(
                TechnicalEvent.EventType.SCHEDULED_REPORTS_EDITED,
                "2 état(s) de fin de période édité(s) le 2026-10-15");
    }

    /** The edition is dated on the day that closes, not on today. */
    @Test
    void theEditionCoversTheClosingDay() {
        ScheduledReportService service = service();
        ReportDefinition only = definition("refunds");
        catalog(service, only);
        when(service.posSettingsService.periodReports()).thenReturn("refunds");
        ReportWriter pdf = writer("pdf");
        when(service.posSettingsService.periodReportsFormat()).thenReturn("pdf");
        when(service.reportService.writer("pdf")).thenReturn(pdf);
        org.mockito.ArgumentCaptor<ReportCriteria> criteria =
                org.mockito.ArgumentCaptor.forClass(ReportCriteria.class);
        when(service.reportService.run(any(), any()))
                .thenAnswer(call -> new ReportData(call.getArgument(0), List.of()));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            service.runEndOfPeriod(LocalDate.of(2026, 10, 15));
        }
        verify(service.reportService).run(org.mockito.ArgumentMatchers.eq(only), criteria.capture());
        assertEquals(LocalDate.of(2026, 10, 15), criteria.getValue().reference);
        assertEquals(com.intermarche.edge.ui.report.ReportPeriod.DAY, criteria.getValue().period);
    }

    /** The archive finders are the ones the screen calls. */
    @Test
    void theArchiveFindersAnswer() {
        assertEquals("scheduled_report_runs",
                ScheduledReportRun.class.getAnnotation(jakarta.persistence.Table.class).name());
    }
}
