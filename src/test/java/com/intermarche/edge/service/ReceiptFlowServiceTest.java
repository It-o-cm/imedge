package com.intermarche.edge.service;

import com.intermarche.edge.domain.safe.ReceiptFlow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link ReceiptFlowService} (BO-05-02-03): the three existing
 * feeds declared on first use, the rewriting of a feed's file in the
 * importer's layout — references prefixed, days made ISO, absent columns left
 * empty — and the trace of the last integration.
 */
class ReceiptFlowServiceTest {

    /**
     * Stubs the listing of the flows.
     *
     * @param panache the active PanacheEntityBase mock
     * @param flows the flows listed
     */
    @SuppressWarnings("unchecked")
    private void stubList(MockedStatic<PanacheEntityBase> panache, List<ReceiptFlow> flows) {
        PanacheQuery<ReceiptFlow> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(flows);
        panache.when(() -> ReceiptFlow.find("order by code")).thenReturn(query);
    }

    /**
     * With no flow declared, the three existing feeds are declared — none, one
     * and two VAT rates — every mandatory column set; once declared, nothing
     * is added.
     */
    @Test
    void theThreeExistingFeedsAreDeclaredOnFirstUse() {
        ReceiptFlowService service = new ReceiptFlowService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ReceiptFlow> created = mockConstruction(ReceiptFlow.class)) {
            panache.when(() -> ReceiptFlow.count()).thenReturn(0L, 3L);
            stubList(panache, List.of());
            service.ensureDefaults();
            assertEquals(3, created.constructed().size());
            ReceiptFlow station = created.constructed().get(0);
            assertEquals("STATION", station.code);
            assertNotNull(station.label);
            assertEquals(";", station.separator);
            assertTrue(station.mapping.contains("VAT_RATE=TAUX_TVA"));
            assertEquals("dd/MM/yyyy", station.datePattern);
            assertTrue(station.active);
            verify(station).persist();
            assertTrue(created.constructed().get(1).mapping.contains("VAT_RATE_2="));
            assertEquals("GAZ_CITERNE", created.constructed().get(2).code);
            assertEquals("|", created.constructed().get(2).separator);
            service.ensureDefaults();
            assertEquals(3, created.constructed().size());
        }
    }

    /**
     * Builds a flow.
     *
     * @param separator its separator
     * @param pattern its date pattern
     * @return the flow
     */
    private ReceiptFlow flow(String separator, String pattern) {
        ReceiptFlow flow = new ReceiptFlow();
        flow.code = "GAZ";
        flow.separator = separator;
        flow.datePattern = pattern;
        flow.mapping = "REFERENCE=NUM;AMOUNT=MT;DATE=JOUR;LABEL=LIB;VAT_RATE=TX;VAT_RATE_2=TX2";
        return flow;
    }

    /**
     * A file is read by its header names, whatever their order: references
     * prefixed by the flow, days made ISO, a missing column or a short line
     * left empty, blank lines skipped and the pipe never left in a value.
     */
    @Test
    void afeedsFileIsRewrittenInTheCanonicalLayout() {
        ReceiptFlowService service = new ReceiptFlowService();
        String file = "jour;mt;num;lib;tx\r\n15/09/2026;12,50;B1;Gaz|butane;20\n\n16/09/2026;3\n;1;;x;0\n";
        String out = service.translate(flow(";", "dd/MM/yyyy"), file);
        assertEquals("REFERENCE|AMOUNT|DATE|LABEL|VAT_RATE|VAT_RATE_2\n"
                + "GAZ-B1|12,50|2026-09-15|Gaz butane|20|\n"
                + "|3|2026-09-16|||\n"
                + "|1||x|0|\n", out);
    }

    /**
     * An empty file gives the canonical header alone, and a flow without
     * separator reads semicolons.
     */
    @Test
    void anEmptyFileGivesTheHeaderAlone() {
        ReceiptFlowService service = new ReceiptFlowService();
        assertEquals("REFERENCE|AMOUNT|DATE|LABEL|VAT_RATE|VAT_RATE_2\n", service.translate(flow("", null), " \n"));
        assertEquals("REFERENCE|AMOUNT|DATE|LABEL|VAT_RATE|VAT_RATE_2\nGAZ-1|2|2026-09-15|||\n",
                service.translate(flow(null, " "), "NUM;MT;JOUR\n1;2;2026-09-15\n"));
    }

    /**
     * A day is rewritten from the flow's pattern, kept as it came when blank,
     * without pattern or unreadable.
     */
    @Test
    void adayIsRewrittenFromThePattern() {
        assertEquals("2026-09-15", ReceiptFlowService.isoDay("15/09/2026", "dd/MM/yyyy"));
        assertEquals("", ReceiptFlowService.isoDay("", "dd/MM/yyyy"));
        assertEquals("15/09/2026", ReceiptFlowService.isoDay("15/09/2026", null));
        assertEquals("15/09/2026", ReceiptFlowService.isoDay("15/09/2026", " "));
        assertEquals("demain", ReceiptFlowService.isoDay("demain", "dd/MM/yyyy"));
    }

    /**
     * The last integration is recorded on its flow, clipped to its column,
     * null kept null; an unknown flow records nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thelastIntegrationIsRecorded() {
        ReceiptFlowService service = new ReceiptFlowService();
        ReceiptFlow flow = mock(ReceiptFlow.class);
        PanacheQuery<ReceiptFlow> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(flow);
        PanacheQuery<ReceiptFlow> missing = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ReceiptFlow.find("code", "GAZ")).thenReturn(found);
            panache.when(() -> ReceiptFlow.find("code", "NONE")).thenReturn(missing);
            service.recordImport("GAZ", "200 ok");
            assertEquals("200 ok", flow.lastResult);
            assertNotNull(flow.lastImportAt);
            service.recordImport("GAZ", "x".repeat(300));
            assertEquals(255, flow.lastResult.length());
            service.recordImport("GAZ", null);
            assertNull(flow.lastResult);
            service.recordImport("NONE", "200");
            verify(flow, org.mockito.Mockito.times(3)).persist();
        }
    }

    /**
     * The summary of an integration names its flow and states, in words, the
     * importer's counts, an empty file, a refusal by its status, and an answer
     * that is no JSON by its status alone.
     */
    @Test
    void anIntegrationIsSummedUpInWords() {
        assertEquals("Flux GAZ : 3 recette(s) créée(s), 1 mise(s) à jour, 2 erreur(s).", ReceiptFlowService.summary(
                "GAZ", 200, "{\"createdCount\":3,\"updatedCount\":1,\"errors\":[\"a\",\"b\"]}"));
        assertEquals("Flux GAZ : 0 recette(s) créée(s), 0 mise(s) à jour, 0 erreur(s).",
                ReceiptFlowService.summary("GAZ", 200, "{}"));
        assertEquals("Flux GAZ : aucune recette dans le fichier.", ReceiptFlowService.summary("GAZ", 204, null));
        assertEquals("Flux GAZ refusé (statut 400) : fichier non conforme au flux.",
                ReceiptFlowService.summary("GAZ", 400, "{\"error\":\"x\"}"));
        assertEquals("Flux GAZ intégré (statut 200).", ReceiptFlowService.summary("GAZ", 200, "not json"));
        assertEquals("Flux GAZ : 0 recette(s) créée(s), 0 mise(s) à jour, 2 erreur(s).", ReceiptFlowService.summary(
                "GAZ", 200, "{\"createdCount\":0, \"updatedCount\":0, \"errors\":[Line 2\",\"Line 3\"]}"));
        assertEquals("Flux GAZ : 1 recette(s) créée(s), 2 mise(s) à jour, 0 erreur(s).",
                ReceiptFlowService.summary("GAZ", 200, "{\"createdCount\":1, \"updatedCount\":2, }"));
        assertEquals("Flux GAZ intégré (statut 200).",
                ReceiptFlowService.summary("GAZ", 200, "{\"createdCount\":1, x"));
    }
}
