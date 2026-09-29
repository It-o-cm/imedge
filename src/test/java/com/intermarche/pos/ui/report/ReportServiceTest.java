package com.intermarche.pos.ui.report;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportService}, the single place a report is executed
 * and matched to its renderer.
 */
class ReportServiceTest {

    /**
     * Builds a mock {@link ReportWriter} answering the given format token.
     *
     * @param format the format token the writer claims
     * @return the configured mock
     */
    private ReportWriter writer(String format) {
        ReportWriter writer = mock(ReportWriter.class);
        when(writer.format()).thenReturn(format);
        return writer;
    }

    /**
     * Builds a {@link ReportService} whose writers instance yields the given
     * renderers on every iteration.
     *
     * @param writers the renderers the container is pretended to have found
     * @return the service with its writers wired
     */
    @SuppressWarnings("unchecked")
    private ReportService serviceWithWriters(ReportWriter... writers) {
        ReportService service = new ReportService();
        Instance<ReportWriter> instance = mock(Instance.class);
        List<ReportWriter> list = Arrays.asList(writers);
        when(instance.iterator()).thenAnswer(invocation -> list.iterator());
        service.writers = instance;
        return service;
    }

    /**
     * run executes the definition's rows and wraps them, together with the
     * definition, in the returned {@link ReportData}.
     */
    @Test
    void runWrapsDefinitionRowsInReportData() {
        ReportService service = new ReportService();
        ReportDefinition definition = mock(ReportDefinition.class);
        ReportCriteria criteria = mock(ReportCriteria.class);
        when(definition.code()).thenReturn("cashier");
        when(definition.columns()).thenReturn(List.of());
        List<List<Object>> rows = List.of(List.of((Object) "a"), List.of((Object) "b"));
        when(definition.rows(criteria)).thenReturn(rows);
        ReportData data = service.run(definition, criteria);
        assertSame(definition, data.definition);
        assertSame(rows, data.rows);
        verify(definition).rows(criteria);
    }

    /**
     * writer returns null for a null format, taking the first arm of the guard.
     */
    @Test
    void writerReturnsNullForNullFormat() {
        ReportService service = serviceWithWriters(writer("csv"));
        assertNull(service.writer(null));
    }

    /**
     * writer returns null for a blank format, taking the second arm of the
     * guard after the null arm passed.
     */
    @Test
    void writerReturnsNullForBlankFormat() {
        ReportService service = serviceWithWriters(writer("csv"));
        assertNull(service.writer("   "));
    }

    /**
     * writer returns the matching renderer, case-insensitively and after
     * trimming, skipping a non-matching one first to exercise both arms of the
     * loop test.
     */
    @Test
    void writerReturnsMatchingRendererIgnoringCaseAndSpace() {
        ReportWriter csv = writer("csv");
        ReportWriter pdf = writer("pdf");
        ReportService service = serviceWithWriters(csv, pdf);
        assertSame(pdf, service.writer("  PDF  "));
    }

    /**
     * writer returns null when no renderer claims the token, after the loop has
     * exhausted every writer without a match.
     */
    @Test
    void writerReturnsNullWhenNoRendererMatches() {
        ReportService service = serviceWithWriters(writer("csv"), writer("pdf"));
        assertNull(service.writer("xlsx"));
    }

    /**
     * fileName joins the report code, the period's start stamped as yyyyMMdd and
     * the writer's extension.
     */
    @Test
    void fileNameJoinsCodeStampAndExtension() {
        ReportService service = new ReportService();
        ReportDefinition definition = mock(ReportDefinition.class);
        ReportCriteria criteria = mock(ReportCriteria.class);
        ReportWriter writer = mock(ReportWriter.class);
        when(definition.code()).thenReturn("cashier");
        when(criteria.from()).thenReturn(LocalDateTime.of(2026, 9, 26, 8, 0));
        when(writer.extension()).thenReturn("pdf");
        assertEquals("cashier-20260926.pdf", service.fileName(definition, criteria, writer));
    }

    /**
     * catalog returns the very catalog wired into the service.
     */
    @Test
    void catalogReturnsInjectedRegistry() {
        ReportService service = new ReportService();
        ReportCatalog catalog = mock(ReportCatalog.class);
        service.catalog = catalog;
        assertSame(catalog, service.catalog());
    }
}
