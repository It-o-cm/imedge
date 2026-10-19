package com.intermarche.edge.service;

import com.intermarche.edge.domain.catalog.Nomenclature;
import com.intermarche.edge.domain.catalog.NomenclatureLevel;
import com.intermarche.edge.domain.catalog.ProductFamily;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NomenclatureImportService}, targeting 100% branch
 * coverage.
 * <p>
 * The importer is a pure Panache active-record consumer: it upserts a
 * {@link Nomenclature} scheme, derives its {@link NomenclatureLevel} table from
 * the code lengths it observes, then places every node as a
 * {@link ProductFamily}. The three declared static finders
 * ({@code Nomenclature.findByCode}, {@code NomenclatureLevel.findByRank},
 * {@code ProductFamily.findByCode}) are intercepted with
 * {@link org.mockito.Mockito#mockStatic} on their own classes; the rows the
 * importer inserts are neutralised with
 * {@link org.mockito.Mockito#mockConstruction} so no {@code persist()} ever
 * reaches a database, and the entity methods the flow reads ({@code depth},
 * {@code isConsistentWithNomenclature}) are stubbed on those mocks. No database
 * and no Quarkus context is booted.
 * <p>
 * Branch enumeration (every arm exercised): {@code load} covers the clean and
 * refused ({@code !isClean}) arms; {@code upsertScheme} covers the create and
 * the existing scheme arms and the three legs of the label ternary (null,
 * blank, present); {@code observedLengths} covers the null-code leg, the
 * blank-code leg, the pass leg, the first-sighting arm, and both the matching
 * and the disagreeing subsequent-length arms; {@code applyLevels} covers the
 * create-level and the existing-level arms; {@code anyCustomAt} covers a
 * matching custom line (true), a matching non-custom line and a non-matching
 * line (false); {@code placeNode} covers the create and the update arms and
 * both the consistent and the inconsistent arms.
 */
class NomenclatureImportServiceTest {

    /** The service under test. */
    private final NomenclatureImportService service = new NomenclatureImportService();

    /**
     * Builds a published row.
     *
     * @param level the 0-based level
     * @param code the cumulative node code
     * @param label the node label
     * @param custom whether the line is point-of-sale specific
     * @return the row
     */
    private NomenclatureImportService.Row row(int level, String code, String label,
                                              boolean custom) {
        return new NomenclatureImportService.Row(level, code, label, custom);
    }

    /**
     * A clean import creating the scheme, deriving two levels and placing every
     * node: the create arm of {@code upsertScheme}, the present-label ternary
     * leg, the first-sighting arm of {@code observedLengths}, the create arm of
     * {@code applyLevels}, both a non-custom (false) and a custom (true) level
     * of {@code anyCustomAt}, and the create + consistent arms of
     * {@code placeNode}.
     */
    @Test
    void loadCreatesSchemeLevelsAndNodes() {
        List<NomenclatureImportService.Row> rows = List.of(
                row(0, "10", "Activite", false),
                row(1, "1002", "Rayon", true));
        try (MockedStatic<Nomenclature> nomStatic = mockStatic(Nomenclature.class);
                MockedStatic<NomenclatureLevel> lvlStatic = mockStatic(NomenclatureLevel.class);
                MockedStatic<ProductFamily> pfStatic = mockStatic(ProductFamily.class);
                MockedConstruction<Nomenclature> nomCtor = mockConstruction(Nomenclature.class,
                        (m, ctx) -> when(m.depth()).thenReturn(2));
                MockedConstruction<NomenclatureLevel> lvlCtor =
                        mockConstruction(NomenclatureLevel.class);
                MockedConstruction<ProductFamily> pfCtor = mockConstruction(ProductFamily.class,
                        (m, ctx) -> when(m.isConsistentWithNomenclature()).thenReturn(true))) {
            nomStatic.when(() -> Nomenclature.findByCode("ITM")).thenReturn(null);
            lvlStatic.when(() -> NomenclatureLevel.findByRank(any(Nomenclature.class), anyInt()))
                    .thenReturn(null);
            pfStatic.when(() -> ProductFamily.findByCode(anyString())).thenReturn(null);
            NomenclatureImportService.Outcome outcome =
                    service.load("ITM", "Intermarche", "ITM_FR", rows);
            Assertions.assertTrue(outcome.isClean());
            Assertions.assertEquals(2, outcome.getCreated());
            Assertions.assertEquals(0, outcome.getUpdated());
            Assertions.assertEquals(2, outcome.getLevels());
            Assertions.assertEquals(2, outcome.getTouched());
            Assertions.assertTrue(outcome.getErrors().isEmpty());
            Nomenclature scheme = nomCtor.constructed().get(0);
            Assertions.assertEquals("ITM", scheme.code);
            Assertions.assertEquals("Intermarche", scheme.label);
            Assertions.assertEquals("ITM_FR", scheme.enseigneCode);
            Assertions.assertTrue(scheme.active);
            verify(scheme, times(1)).persist();
            verify(scheme, times(1)).reloadLevels();
            Assertions.assertEquals(2, lvlCtor.constructed().size());
            NomenclatureLevel level0 = lvlCtor.constructed().get(0);
            Assertions.assertEquals(0, level0.rank);
            Assertions.assertEquals(2, level0.codeLength);
            Assertions.assertEquals("Niveau 0", level0.label);
            Assertions.assertFalse(level0.custom);
            Assertions.assertSame(scheme, level0.nomenclature);
            verify(level0, times(1)).persist();
            NomenclatureLevel level1 = lvlCtor.constructed().get(1);
            Assertions.assertEquals(1, level1.rank);
            Assertions.assertEquals(4, level1.codeLength);
            Assertions.assertEquals("Niveau 1", level1.label);
            Assertions.assertTrue(level1.custom);
            verify(level1, times(1)).persist();
            Assertions.assertEquals(2, pfCtor.constructed().size());
            ProductFamily node0 = pfCtor.constructed().get(0);
            Assertions.assertEquals("10", node0.code);
            Assertions.assertEquals("Activite", node0.description);
            Assertions.assertEquals(0, node0.level);
            Assertions.assertSame(scheme, node0.nomenclature);
            verify(node0, times(1)).persist();
            ProductFamily node1 = pfCtor.constructed().get(1);
            Assertions.assertEquals("1002", node1.code);
            Assertions.assertEquals("Rayon", node1.description);
            Assertions.assertEquals(1, node1.level);
            verify(node1, times(1)).persist();
        }
    }

    /**
     * A re-import over an existing scheme, an existing level and an existing
     * node: the existing arm of {@code upsertScheme} with a null label folding
     * to the code (first leg of the ternary), the existing arm of
     * {@code applyLevels}, and the update + consistent arms of
     * {@code placeNode}.
     */
    @Test
    void loadUpdatesExistingSchemeLevelAndNode() {
        List<NomenclatureImportService.Row> rows = List.of(row(0, "10", "Activite", false));
        Nomenclature scheme = mock(Nomenclature.class);
        when(scheme.depth()).thenReturn(1);
        NomenclatureLevel existingLevel = mock(NomenclatureLevel.class);
        ProductFamily existingNode = mock(ProductFamily.class);
        when(existingNode.isConsistentWithNomenclature()).thenReturn(true);
        try (MockedStatic<Nomenclature> nomStatic = mockStatic(Nomenclature.class);
                MockedStatic<NomenclatureLevel> lvlStatic = mockStatic(NomenclatureLevel.class);
                MockedStatic<ProductFamily> pfStatic = mockStatic(ProductFamily.class)) {
            nomStatic.when(() -> Nomenclature.findByCode("ITM")).thenReturn(scheme);
            lvlStatic.when(() -> NomenclatureLevel.findByRank(any(Nomenclature.class), anyInt()))
                    .thenReturn(existingLevel);
            pfStatic.when(() -> ProductFamily.findByCode("10")).thenReturn(existingNode);
            NomenclatureImportService.Outcome outcome = service.load("ITM", null, null, rows);
            Assertions.assertTrue(outcome.isClean());
            Assertions.assertEquals(0, outcome.getCreated());
            Assertions.assertEquals(1, outcome.getUpdated());
            Assertions.assertEquals(1, outcome.getLevels());
            Assertions.assertEquals("ITM", scheme.label);
            Assertions.assertNull(scheme.enseigneCode);
            Assertions.assertTrue(scheme.active);
            verify(scheme, times(1)).persist();
            Assertions.assertEquals(2, existingLevel.codeLength);
            Assertions.assertFalse(existingLevel.custom);
            verify(existingLevel, times(1)).persist();
            Assertions.assertEquals("Activite", existingNode.description);
            Assertions.assertEquals(0, existingNode.level);
            verify(existingNode, times(1)).persist();
        }
    }

    /**
     * A blank label on a created scheme folds to the code (the {@code isBlank}
     * leg of the label ternary), the created scheme being kept clean.
     */
    @Test
    void loadWithABlankLabelFoldsToTheCode() {
        List<NomenclatureImportService.Row> rows = List.of(row(0, "10", "Activite", false));
        try (MockedStatic<Nomenclature> nomStatic = mockStatic(Nomenclature.class);
                MockedStatic<NomenclatureLevel> lvlStatic = mockStatic(NomenclatureLevel.class);
                MockedStatic<ProductFamily> pfStatic = mockStatic(ProductFamily.class);
                MockedConstruction<Nomenclature> nomCtor = mockConstruction(Nomenclature.class,
                        (m, ctx) -> when(m.depth()).thenReturn(1));
                MockedConstruction<NomenclatureLevel> lvlCtor =
                        mockConstruction(NomenclatureLevel.class);
                MockedConstruction<ProductFamily> pfCtor = mockConstruction(ProductFamily.class,
                        (m, ctx) -> when(m.isConsistentWithNomenclature()).thenReturn(true))) {
            nomStatic.when(() -> Nomenclature.findByCode("ITM")).thenReturn(null);
            lvlStatic.when(() -> NomenclatureLevel.findByRank(any(Nomenclature.class), anyInt()))
                    .thenReturn(null);
            pfStatic.when(() -> ProductFamily.findByCode(anyString())).thenReturn(null);
            NomenclatureImportService.Outcome outcome = service.load("ITM", "   ", null, rows);
            Assertions.assertTrue(outcome.isClean());
            Nomenclature scheme = nomCtor.constructed().get(0);
            Assertions.assertEquals("ITM", scheme.label);
        }
    }

    /**
     * A null-coded line and a blank-coded line are both refused with a
     * "code vide" message, and the disagreement stops the import before any
     * level or node is written (the two legs of the empty-code guard, and the
     * {@code !isClean} early-return arm of {@code load}).
     */
    @Test
    void loadRefusesEmptyCodesAndStopsEarly() {
        List<NomenclatureImportService.Row> rows = List.of(
                row(0, null, "Activite", false),
                row(1, "   ", "Rayon", false));
        try (MockedStatic<Nomenclature> nomStatic = mockStatic(Nomenclature.class);
                MockedConstruction<Nomenclature> nomCtor =
                        mockConstruction(Nomenclature.class)) {
            nomStatic.when(() -> Nomenclature.findByCode("ITM")).thenReturn(null);
            NomenclatureImportService.Outcome outcome =
                    service.load("ITM", "Intermarche", null, rows);
            Assertions.assertFalse(outcome.isClean());
            Assertions.assertEquals(2, outcome.getErrors().size());
            Assertions.assertEquals("Niveau 0 : code vide.", outcome.getErrors().get(0));
            Assertions.assertEquals("Niveau 1 : code vide.", outcome.getErrors().get(1));
            Assertions.assertEquals(0, outcome.getCreated());
            Assertions.assertEquals(0, outcome.getUpdated());
            Assertions.assertEquals(0, outcome.getLevels());
            Nomenclature scheme = nomCtor.constructed().get(0);
            verify(scheme, times(1)).persist();
            verify(scheme, org.mockito.Mockito.never()).reloadLevels();
        }
    }

    /**
     * Two lines of one level disagreeing on the code length are refused: a
     * third line of the matching length is accepted silently (the
     * length-matches arm), while the odd one raises the incoherence message
     * (the length-differs arm), which stops the import.
     */
    @Test
    void loadRefusesInconsistentLengths() {
        List<NomenclatureImportService.Row> rows = List.of(
                row(0, "10", "a", false),
                row(0, "20", "b", false),
                row(0, "1002", "c", false));
        try (MockedStatic<Nomenclature> nomStatic = mockStatic(Nomenclature.class);
                MockedConstruction<Nomenclature> nomCtor =
                        mockConstruction(Nomenclature.class)) {
            nomStatic.when(() -> Nomenclature.findByCode("ITM")).thenReturn(null);
            NomenclatureImportService.Outcome outcome =
                    service.load("ITM", "Intermarche", null, rows);
            Assertions.assertFalse(outcome.isClean());
            Assertions.assertEquals(1, outcome.getErrors().size());
            Assertions.assertEquals(
                    "Niveau 0 : longueurs incohérentes — « 10 » (2) et « 1002 » (4).",
                    outcome.getErrors().get(0));
            Assertions.assertEquals(0, outcome.getCreated());
            Assertions.assertEquals(0, outcome.getUpdated());
            Nomenclature scheme = nomCtor.constructed().get(0);
            verify(scheme, org.mockito.Mockito.never()).reloadLevels();
        }
    }

    /**
     * A node whose code length is incompatible with its declared level is
     * refused at placement (the {@code !isConsistentWithNomenclature} arm of
     * {@code placeNode}): the level table is still written, but the node is not
     * counted and the refusal is reported.
     */
    @Test
    void loadRefusesAnInconsistentNode() {
        List<NomenclatureImportService.Row> rows = List.of(row(0, "10", "Activite", false));
        try (MockedStatic<Nomenclature> nomStatic = mockStatic(Nomenclature.class);
                MockedStatic<NomenclatureLevel> lvlStatic = mockStatic(NomenclatureLevel.class);
                MockedStatic<ProductFamily> pfStatic = mockStatic(ProductFamily.class);
                MockedConstruction<Nomenclature> nomCtor = mockConstruction(Nomenclature.class,
                        (m, ctx) -> when(m.depth()).thenReturn(1));
                MockedConstruction<NomenclatureLevel> lvlCtor =
                        mockConstruction(NomenclatureLevel.class);
                MockedConstruction<ProductFamily> pfCtor = mockConstruction(ProductFamily.class,
                        (m, ctx) -> when(m.isConsistentWithNomenclature()).thenReturn(false))) {
            nomStatic.when(() -> Nomenclature.findByCode("ITM")).thenReturn(null);
            lvlStatic.when(() -> NomenclatureLevel.findByRank(any(Nomenclature.class), anyInt()))
                    .thenReturn(null);
            pfStatic.when(() -> ProductFamily.findByCode("10")).thenReturn(null);
            NomenclatureImportService.Outcome outcome =
                    service.load("ITM", "Intermarche", null, rows);
            Assertions.assertFalse(outcome.isClean());
            Assertions.assertEquals(1, outcome.getErrors().size());
            Assertions.assertEquals(
                    "Code « 10 » : longueur 2 incompatible avec le niveau 0.",
                    outcome.getErrors().get(0));
            Assertions.assertEquals(0, outcome.getCreated());
            Assertions.assertEquals(0, outcome.getUpdated());
            ProductFamily node = pfCtor.constructed().get(0);
            verify(node, org.mockito.Mockito.never()).persist();
        }
    }
}
