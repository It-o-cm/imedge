package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.Promotion;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static com.intermarche.edge.ui.report.QueryStub.row;
import static com.intermarche.edge.ui.report.QueryStub.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PromotionArticleReport}: the articles sold under a
 * promotion, summed per article and promotion, the code read off the EAN or
 * the PLU, the administered label and upstream funding, the hours and article
 * criteria, and the metadata.
 */
class PromotionArticleReportTest {

    /** A moment of the period. */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 20, 10, 0);

    /**
     * Runs the report, the promotion OFF1 administered and funded upstream.
     *
     * @param stub the stub
     * @param criteria the edition parameters
     * @return the rows
     */
    @SuppressWarnings("unchecked")
    private List<List<Object>> run(QueryStub stub, ReportCriteria criteria) {
        PromotionArticleReport report = new PromotionArticleReport();
        report.entityManager = stub.entityManager;
        Promotion off1 = new Promotion();
        off1.code = "OFF1";
        off1.label = "Lot de 3";
        off1.upstreamFunded = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class))).thenAnswer(call -> {
                PanacheQuery<Object> query = mock(PanacheQuery.class);
                when(query.firstResult()).thenReturn("OFF1".equals(call.getArguments()[1]) ? off1 : null);
                return query;
            });
            return report.rows(criteria);
        }
    }

    /**
     * Per article and promotion: the quantities, net sales and discounts
     * summed; an unknown promotion keeps the engine's label and is not funded
     * upstream; a line without code or offer reads blank; the late line is
     * out of the hours.
     */
    @Test
    void perArticleAndPromotion() {
        QueryStub stub = new QueryStub().answer("from Ticket t join t.lines l", rows(
                row("301", null, "Pain", new BigDecimal("2"), new BigDecimal("2.00"), "OFF1", "Remise",
                        new BigDecimal("2.40"), new BigDecimal("2.00"), AT),
                row("301", null, "Pain", null, null, "OFF1", "Remise", new BigDecimal("1.20"),
                        new BigDecimal("1.00"), AT),
                row(null, "77", "Pommes", BigDecimal.ONE, BigDecimal.ONE, "OFF2", "Moins 10 %",
                        new BigDecimal("1.10"), BigDecimal.ONE, AT),
                row(null, null, "Divers", BigDecimal.ONE, BigDecimal.ONE, null, null, BigDecimal.TEN,
                        BigDecimal.ONE, AT),
                row("301", null, "Pain", BigDecimal.ONE, BigDecimal.ONE, "OFF1", "Remise", BigDecimal.TEN,
                        BigDecimal.ONE, AT.withHour(21))));
        ReportCriteria criteria = new ReportCriteria();
        criteria.hourTo = LocalTime.of(20, 0);
        criteria.families.add("10");
        List<List<Object>> rows = run(stub, criteria);
        assertEquals(3, rows.size());
        assertEquals(Arrays.asList("", "Divers", "", "", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("9"), "Non"),
                rows.get(0));
        assertEquals(Arrays.asList("301", "Pain", "OFF1", "Lot de 3", new BigDecimal("2"), new BigDecimal("2.00"),
                new BigDecimal("0.60"), "Oui"), rows.get(1));
        assertEquals("Moins 10 %", rows.get(2).get(3));
        assertEquals("Non", rows.get(2).get(7));
        assertEquals("10%", stub.parameters.get("family0"));
        assertTrue(stub.asked("v.valuedTotal < v.localTotal"));
    }

    /**
     * The metadata: identity, columns, chart and filters.
     */
    @Test
    void metadataDescribesTheReport() {
        PromotionArticleReport report = new PromotionArticleReport();
        assertEquals("promotion-articles", report.code());
        assertEquals("État des ventes articles promotionnels", report.label());
        assertEquals("BO-06-08-02", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(8, report.columns().size());
        assertEquals(6, report.chartColumn());
        assertEquals(1, report.chartLabelColumn());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.ARTICLES),
                report.filters());
    }
}
