package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.session.ReportSchedule;
import com.intermarche.edge.domain.session.ScheduledReportRun;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportScheduleService}: the loop's start and stop, a
 * round in a transaction of its own that never throws, the planning of an
 * extraction, the edition of a due one — succeeded, of an unknown report, of
 * an unknown format, failing with and without a message — and the reading of
 * a query string.
 */
class ReportScheduleServiceTest {

    /** The clock of the tests. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 6, 0);

    /**
     * Builds a service over a mocked report runner and a fixed clock.
     *
     * @return the service
     */
    private ReportScheduleService service() {
        ReportScheduleService service = new ReportScheduleService();
        service.reportService = mock(ReportService.class);
        service.now = () -> NOW;
        return service;
    }

    /**
     * Reads the loop executor.
     *
     * @param service the service
     * @return the executor, or null
     * @throws Exception never
     */
    private ScheduledExecutorService executor(ReportScheduleService service) throws Exception {
        Field field = ReportScheduleService.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(service);
    }

    /**
     * The loop starts on the startup event and stops, twice without harm.
     *
     * @throws Exception never
     */
    @Test
    void theLoopStartsAndStops() throws Exception {
        ReportScheduleService service = service();
        service.onStart(null);
        assertNotNull(executor(service));
        service.onStop();
        assertNull(executor(service));
        service.onStop();
        assertNull(executor(service));
    }

    /**
     * A tick runs the round in a transaction of its own and swallows a failure.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aTickRunsTheRoundAndSwallowsItsFailure() {
        ReportScheduleService service = service();
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        when(runner.call(any(Callable.class))).thenAnswer(call -> ((Callable<?>) call.getArgument(0)).call())
                .thenThrow(new IllegalStateException("down"));
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            service.tick();
            service.tick();
            panache.verify(() -> PanacheEntityBase.list("done = false and runAt <= ?1 order by runAt", NOW));
        }
    }

    /**
     * Planning an extraction keeps its report, its parameters, its format, its
     * moment and who planned it, then persists it.
     */
    @Test
    void anExtractionIsPlanned() {
        ReportScheduleService service = service();
        try (MockedConstruction<ReportSchedule> built = mockConstruction(ReportSchedule.class)) {
            ReportSchedule schedule = service.plan("rpt", "period=DAY&", "pdf", NOW.plusHours(1), "admin");
            assertSame(built.constructed().get(0), schedule);
            assertEquals("rpt", schedule.reportCode);
            assertEquals("period=DAY&", schedule.query);
            assertEquals("pdf", schedule.format);
            assertEquals(NOW.plusHours(1), schedule.runAt);
            assertEquals("admin", schedule.plannedBy);
            assertEquals(NOW, schedule.plannedAt);
            org.mockito.Mockito.verify(schedule).persist();
        }
    }

    /**
     * A round edits every due extraction: one succeeds over the period its
     * parameters name, one names an unknown report, one an unknown format,
     * and three fail, with and without a message, one too long to keep whole;
     * each is marked done with its failure.
     */
    @Test
    void aRoundEditsEveryDueExtraction() {
        ReportScheduleService service = service();
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(service.reportService.catalog()).thenReturn(catalog);
        ReportDefinition definition = mock(ReportDefinition.class);
        when(definition.label()).thenReturn("État");
        when(catalog.find("rpt")).thenReturn(definition);
        ReportWriter writer = mock(ReportWriter.class);
        when(writer.write(any(), any())).thenReturn(new byte[]{1});
        when(service.reportService.writer("pdf")).thenReturn(writer);
        ReportData data = new ReportData(ReportFixture.standard(), ReportFixture.standard().rows(null));
        when(service.reportService.run(eq(definition), any())).thenReturn(data)
                .thenThrow(new IllegalStateException("boom"))
                .thenThrow(new IllegalStateException())
                .thenThrow(new IllegalStateException("x".repeat(600)));
        ReportSchedule ok = schedule("rpt", "pdf", "period=DAY&reference=2026-10-20");
        ReportSchedule unknown = schedule("nope", "pdf", null);
        ReportSchedule badFormat = schedule("rpt", "zip", null);
        ReportSchedule failing = schedule("rpt", "pdf", "");
        ReportSchedule silent = schedule("rpt", "pdf", "");
        ReportSchedule verbose = schedule("rpt", "pdf", "");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ScheduledReportRun> runs = mockConstruction(ScheduledReportRun.class)) {
            panache.when(() -> PanacheEntityBase.list("done = false and runAt <= ?1 order by runAt", NOW))
                    .thenReturn(List.of(ok, unknown, badFormat, failing, silent, verbose));
            assertEquals(6, service.round());
            List<ScheduledReportRun> made = runs.constructed();
            assertTrue(made.get(0).succeeded);
            assertEquals("État", made.get(0).reportLabel);
            assertEquals(LocalDate.of(2026, 10, 20), made.get(0).periodEnd);
            assertEquals(2, made.get(0).rowCount);
            assertEquals("Rapport inconnu : nope", made.get(1).failure);
            assertEquals(NOW.toLocalDate(), made.get(1).periodEnd);
            assertEquals("Format inconnu : zip", made.get(2).failure);
            assertEquals("boom", made.get(3).failure);
            assertEquals("IllegalStateException", made.get(4).failure);
            assertFalse(made.get(4).succeeded);
        }
        assertTrue(ok.done);
        assertNull(ok.failure);
        assertTrue(unknown.done);
        assertEquals("Rapport inconnu : nope", unknown.failure);
        assertEquals(500, verbose.failure.length());
        verifyPrepared(service);
    }

    /**
     * Checks the parameters of an extraction were prepared like the screen's.
     *
     * @param service the service
     */
    private void verifyPrepared(ReportScheduleService service) {
        org.mockito.Mockito.verify(service.reportService, org.mockito.Mockito.times(4)).prepare(any());
    }

    /**
     * Builds a due extraction.
     *
     * @param code the report's code
     * @param format the format
     * @param query the parameters
     * @return the extraction
     */
    private ReportSchedule schedule(String code, String format, String query) {
        ReportSchedule schedule = new ReportSchedule();
        schedule.reportCode = code;
        schedule.format = format;
        schedule.query = query;
        return schedule;
    }

    /**
     * A query string is read back: null and empty pairs give nothing, a name
     * without value reads empty, the values are decoded and repeated.
     */
    @Test
    void aQueryStringIsReadBack() {
        assertTrue(ReportScheduleService.parse(null).isEmpty());
        MultivaluedMap<String, String> params = ReportScheduleService.parse("a=1&&b&terminal=C+1&terminal=%C3%A9");
        assertEquals(List.of("1"), params.get("a"));
        assertEquals(List.of(""), params.get("b"));
        assertEquals(List.of("C 1", "é"), params.get("terminal"));
    }
}
