package com.intermarche.edge.ui.report;

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
        try (org.mockito.MockedStatic<com.intermarche.edge.domain.store.CheckoutPoint> points =
                org.mockito.Mockito.mockStatic(com.intermarche.edge.domain.store.CheckoutPoint.class)) {
            points.when(com.intermarche.edge.domain.store.CheckoutPoint::deactivatedTerminals)
                    .thenReturn(List.of("C09"));
            ReportData data = service.run(definition, criteria);
            assertSame(definition, data.definition);
            assertSame(rows, data.rows);
        }
        verify(definition).rows(criteria);
        // BO-03-09-01: the deactivated registers are handed to the criteria.
        verify(criteria).excludeDeactivated(List.of("C09"));
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

    /**
     * {@code restrictToSelection} leaves a report without a list untouched,
     * restricts it to nothing for a list the point of sale does not know, and
     * to the EANs of the list's articles otherwise (BO-02-03-31).
     */
    @Test
    void restrictToSelectionResolvesTheListArticles() {
        ReportService service = new ReportService();
        ReportCriteria none = new ReportCriteria();
        service.restrictToSelection(none);
        assertNull(none.articleEans);
        ReportCriteria unknown = new ReportCriteria();
        unknown.selection = "Inconnue";
        ReportCriteria known = new ReportCriteria();
        known.selection = "Épicerie";
        com.intermarche.edge.domain.catalog.ArticleSelection selection =
                new com.intermarche.edge.domain.catalog.ArticleSelection();
        selection.name = "Épicerie";
        selection.criteria = "";
        com.intermarche.edge.domain.catalog.Product product = new com.intermarche.edge.domain.catalog.Product();
        product.ean = "300";
        @SuppressWarnings("unchecked")
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> empty = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        @SuppressWarnings("unchecked")
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> found = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(found.firstResult()).thenReturn(selection);
        @SuppressWarnings("unchecked")
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> products = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(products.list()).thenReturn(List.of(product));
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase
                    .find("name = ?1 and pdvCode is null", "Inconnue")).thenReturn(empty);
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase
                    .find("name = ?1 and pdvCode is null", "Épicerie")).thenReturn(found);
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase
                    .find("from Product p order by p.ean")).thenReturn(products);
            service.restrictToSelection(unknown);
            service.restrictToSelection(known);
        }
        assertEquals(java.util.Set.of(), unknown.articleEans);
        assertEquals(java.util.Set.of("300"), known.articleEans);
    }

    /**
     * {@code pdv} answers null without the node service and the node's point
     * of sale with it.
     */
    @Test
    void pdvFollowsTheNode() {
        ReportService service = new ReportService();
        assertNull(service.pdv());
        service.nodeEchelonService = mock(com.intermarche.edge.service.NodeEchelonService.class);
        when(service.nodeEchelonService.currentPdv()).thenReturn("PDV1");
        assertEquals("PDV1", service.pdv());
    }

    /**
     * {@code prepare} leaves a report without island unrestricted, restricts it
     * to the island's registers, and to none for an island the node does not
     * know.
     */
    @Test
    void prepareResolvesTheIsland() {
        ReportService service = new ReportService();
        ReportCriteria none = new ReportCriteria();
        service.prepare(none);
        assertNull(none.islandTerminals);
        ReportCriteria known = new ReportCriteria();
        known.island = "I1";
        ReportCriteria unknown = new ReportCriteria();
        unknown.island = "I9";
        com.intermarche.edge.domain.store.CheckoutIsland island = new com.intermarche.edge.domain.store.CheckoutIsland();
        island.code = "I1";
        island.terminalIds = "C01;C02";
        @SuppressWarnings("unchecked")
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> found = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(found.firstResult()).thenReturn(island);
        @SuppressWarnings("unchecked")
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> missing = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("code", "I1")).thenReturn(found);
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("code", "I9")).thenReturn(missing);
            service.prepare(known);
            service.prepare(unknown);
        }
        assertEquals(java.util.Set.of("C01", "C02"), known.islandTerminals);
        assertEquals(java.util.Set.of(), unknown.islandTerminals);
    }
}
