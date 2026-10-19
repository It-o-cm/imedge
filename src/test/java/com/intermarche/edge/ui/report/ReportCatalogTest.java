package com.intermarche.edge.ui.report;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportCatalog}, the registry of report beans indexed by
 * code and grouped by family.
 */
class ReportCatalogTest {

    /**
     * Builds a mock {@link ReportDefinition} with the given identity.
     *
     * @param code the report code
     * @param family the report family
     * @param requirement the requirement code used to order within a family
     * @return the configured mock
     */
    private ReportDefinition definition(String code, ReportFamily family, String requirement) {
        ReportDefinition definition = mock(ReportDefinition.class);
        when(definition.code()).thenReturn(code);
        when(definition.family()).thenReturn(family);
        when(definition.requirement()).thenReturn(requirement);
        return definition;
    }

    /**
     * Builds a {@link ReportCatalog} whose discovered instance yields the given
     * definitions, then runs its post-construct indexing.
     *
     * @param definitions the beans the container is pretended to have found
     * @return the indexed catalog
     */
    @SuppressWarnings("unchecked")
    private ReportCatalog indexed(ReportDefinition... definitions) {
        ReportCatalog catalog = new ReportCatalog();
        Instance<ReportDefinition> instance = mock(Instance.class);
        List<ReportDefinition> list = Arrays.asList(definitions);
        when(instance.iterator()).thenAnswer(invocation -> list.iterator());
        catalog.discovered = instance;
        catalog.index();
        return catalog;
    }

    /**
     * Indexing no discovered bean leaves the catalog empty: all() is empty and
     * families() reports no used family.
     */
    @Test
    void indexWithNoBeanLeavesCatalogEmpty() {
        ReportCatalog catalog = indexed();
        assertTrue(catalog.all().isEmpty());
        assertTrue(catalog.families().isEmpty());
    }

    /**
     * Indexing orders reports by family ordinal first, then by requirement code
     * within a family, and all() returns them in that display order.
     */
    @Test
    void indexOrdersByFamilyThenRequirement() {
        ReportDefinition a = definition("a", ReportFamily.SALES, "BO-06-01-02");
        ReportDefinition b = definition("b", ReportFamily.SALES, "BO-06-01-01");
        ReportDefinition c = definition("c", ReportFamily.TAX, "BO-06-04-01");
        ReportCatalog catalog = indexed(a, c, b);
        assertEquals(List.of(b, a, c), catalog.all());
    }

    /**
     * find(null) returns null, exercising the null arm of the guard.
     */
    @Test
    void findWithNullCodeReturnsNull() {
        ReportCatalog catalog = indexed(definition("a", ReportFamily.SALES, "BO-06-01-01"));
        assertNull(catalog.find(null));
    }

    /**
     * find with an unknown code returns null: the non-null arm resolves to a
     * map miss.
     */
    @Test
    void findWithUnknownCodeReturnsNull() {
        ReportCatalog catalog = indexed(definition("a", ReportFamily.SALES, "BO-06-01-01"));
        assertNull(catalog.find("ghost"));
    }

    /**
     * find with a known code returns the matching report: the non-null arm
     * resolves to a map hit.
     */
    @Test
    void findWithKnownCodeReturnsReport() {
        ReportDefinition a = definition("a", ReportFamily.SALES, "BO-06-01-01");
        ReportCatalog catalog = indexed(a);
        assertEquals(a, catalog.find("a"));
    }

    /**
     * of(family) returns only the reports of that family, in display order,
     * exercising both the matching and the non-matching arm of the filter.
     */
    @Test
    void ofReturnsOnlyTheFamilyReports() {
        ReportDefinition a = definition("a", ReportFamily.SALES, "BO-06-01-02");
        ReportDefinition b = definition("b", ReportFamily.SALES, "BO-06-01-01");
        ReportDefinition c = definition("c", ReportFamily.TAX, "BO-06-04-01");
        ReportCatalog catalog = indexed(a, b, c);
        assertEquals(List.of(b, a), catalog.of(ReportFamily.SALES));
    }

    /**
     * of(family) returns an empty list for a family that holds no report.
     */
    @Test
    void ofReturnsEmptyForUnusedFamily() {
        ReportCatalog catalog = indexed(definition("a", ReportFamily.SALES, "BO-06-01-01"));
        assertTrue(catalog.of(ReportFamily.CASHIERS).isEmpty());
    }

    /**
     * families() returns only the non-empty families, in enum declaration
     * order, exercising both the kept and the skipped arm of the filter.
     */
    @Test
    void familiesReturnsOnlyUsedFamiliesInDeclarationOrder() {
        ReportDefinition a = definition("a", ReportFamily.SALES, "BO-06-01-01");
        ReportDefinition c = definition("c", ReportFamily.TAX, "BO-06-04-01");
        ReportCatalog catalog = indexed(a, c);
        assertEquals(List.of(ReportFamily.SALES, ReportFamily.TAX), catalog.families());
    }
}
