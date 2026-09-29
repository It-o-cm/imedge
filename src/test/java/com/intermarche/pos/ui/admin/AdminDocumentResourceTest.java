package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.sale.DocumentType;
import com.intermarche.pos.domain.sale.Invoice;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminDocumentResource}.
 * <p>
 * A read-only Qute screen over the {@link Invoice} static finders, intercepted
 * with {@link org.mockito.Mockito#mockStatic}; the template is a mock. No
 * Quarkus context and no database is booted.
 * <p>
 * Branch enumeration (every arm exercised): {@code bounded} — its four cases
 * (both bounds, first alone, last alone, neither), each asserted on the exact
 * query and arguments it issues; {@code search} — each of the three fragment
 * criteria absent and present, the three columns the text matches on, a
 * document whose fiscal identifier is null against a fiscal criterion, a
 * document whose kind is null against a kind criterion, and an unreadable date
 * read as no bound; {@code export} — the header, a rendered line, the
 * separator neutralized in a cell and the null amounts.
 */
class AdminDocumentResourceTest {

    /** The query issued when both bounds are given. */
    private static final String BOTH_BOUNDS =
            "issueDate >= ?1 and issueDate < ?2 order by issueDate desc";

    /** The query issued when only the first bound is given. */
    private static final String FIRST_ONLY = "issueDate >= ?1 order by issueDate desc";

    /** The query issued when only the last bound is given. */
    private static final String LAST_ONLY = "issueDate < ?1 order by issueDate desc";

    /** The query issued when no bound is given. */
    private static final String NO_BOUND = "order by issueDate desc";

    /**
     * Builds a resource with a mocked template.
     *
     * @return the wired resource
     */
    private AdminDocumentResource newResource() {
        AdminDocumentResource resource = new AdminDocumentResource();
        resource.adminDocuments = mock(Template.class);
        return resource;
    }

    /**
     * Builds an issued document.
     *
     * @param number the document number
     * @param type its kind, possibly null
     * @param name the customer name as printed
     * @return the document
     */
    private Invoice document(String number, DocumentType type, String name) {
        Invoice document = new Invoice();
        document.documentNumber = number;
        document.documentType = type;
        document.customerName = name;
        document.issueDate = LocalDateTime.of(2026, 9, 10, 18, 42);
        document.terminalId = "CAISSE1";
        document.ticketNumber = "T-1";
        document.totalExcludingTax = new BigDecimal("100.00");
        document.totalIncludingTax = new BigDecimal("120.00");
        document.totalVat = new BigDecimal("20.00");
        document.printCount = 1;
        return document;
    }

    /**
     * Stubs the unbounded listing.
     *
     * @param panache the active PanacheEntityBase mock
     * @param documents the documents to resolve
     */
    private void stubAll(MockedStatic<PanacheEntityBase> panache, List<Invoice> documents) {
        panache.when(() -> Invoice.list(NO_BOUND)).thenReturn(documents);
    }

    /**
     * With no bound at all, the unbounded query is the one issued.
     */
    @Test
    void withoutABoundTheWholeListIsRead() {
        AdminDocumentResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(document("F-1", DocumentType.FACTURE, "Acme")));
            assertEquals(1, resource.search(null, null, null, null, null).size());
            panache.verify(() -> Invoice.list(NO_BOUND));
        }
    }

    /**
     * Both bounds given: the period is pushed down to the database, the last
     * day included — a document issued at 18:42 on the last day must be in.
     */
    @Test
    void bothBoundsArePushedDownWithTheLastDayIncluded() {
        AdminDocumentResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Invoice.list(BOTH_BOUNDS,
                            LocalDate.of(2026, 9, 1).atStartOfDay(),
                            LocalDate.of(2026, 9, 30).plusDays(1).atStartOfDay()))
                    .thenReturn(List.of(document("F-1", DocumentType.FACTURE, "Acme")));
            assertEquals(1, resource.search(null, null, null, "2026-09-01", "2026-09-30").size());
        }
    }

    /**
     * The first bound alone, and the last bound alone, each have their own
     * query.
     */
    @Test
    void eachBoundAloneHasItsOwnQuery() {
        AdminDocumentResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Invoice.list(FIRST_ONLY, LocalDate.of(2026, 9, 1).atStartOfDay()))
                    .thenReturn(List.of(document("F-1", DocumentType.FACTURE, "Acme")));
            assertEquals(1, resource.search(null, null, null, "2026-09-01", null).size());
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Invoice.list(LAST_ONLY,
                            LocalDate.of(2026, 9, 30).plusDays(1).atStartOfDay()))
                    .thenReturn(List.of(document("F-2", DocumentType.FACTURE, "Bravo")));
            assertEquals(1, resource.search(null, null, null, null, "2026-09-30").size());
        }
    }

    /**
     * An unreadable date is read as NO bound rather than as a failure: a
     * mistyped day must not take the screen down.
     */
    @Test
    void anUnreadableDateIsNoBound() {
        AdminDocumentResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(document("F-1", DocumentType.FACTURE, "Acme")));
            assertEquals(1, resource.search(null, null, null, "le 1er", "  ").size());
            panache.verify(() -> Invoice.list(NO_BOUND));
        }
    }

    /**
     * The text criterion matches the document number, the customer name and
     * the account — its three columns — and ignores case.
     */
    @Test
    void theTextCriterionMatchesItsThreeColumns() {
        AdminDocumentResource resource = newResource();
        Invoice withAccount = document("F-2", DocumentType.FACTURE, "Bravo");
        withAccount.customerAccountNumber = "CPT-77";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(document("F-1", DocumentType.FACTURE, "Acme"), withAccount));
            assertEquals(1, resource.search("f-1", null, null, null, null).size());
            assertEquals(1, resource.search("ACME", null, null, null, null).size());
            assertEquals(1, resource.search("cpt-77", null, null, null, null).size());
            assertTrue(resource.search("inconnu", null, null, null, null).isEmpty());
        }
    }

    /**
     * The fiscal criterion keeps only the documents carrying one, which is the
     * extraction the requirement asks for.
     */
    @Test
    void theFiscalCriterionKeepsOnlyTheDocumentsCarryingOne() {
        AdminDocumentResource resource = newResource();
        Invoice withNif = document("F-1", DocumentType.FACTURE, "Acme");
        withNif.customerTaxId = "PT123456789";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(withNif, document("F-2", DocumentType.FACTURE, "Bravo")));
            List<Invoice> matches = resource.search(null, "pt123", null, null, null);
            assertEquals(1, matches.size());
            assertEquals("F-1", matches.get(0).documentNumber);
            assertTrue(resource.search(null, "FR", null, null, null).isEmpty());
        }
    }

    /**
     * The kind criterion separates invoices from delivery notes, and a document
     * whose kind is null is kept out of both — the null arm of that guard.
     */
    @Test
    void theKindCriterionSeparatesTheTwoKinds() {
        AdminDocumentResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(document("F-1", DocumentType.FACTURE, "Acme"),
                    document("L-1", DocumentType.BON_LIVRAISON, "Bravo"),
                    document("X-1", null, "Charlie")));
            assertEquals(1, resource.search(null, null, "FACTURE", null, null).size());
            assertEquals(1, resource.search(null, null, "BON_LIVRAISON", null, null).size());
            assertEquals(3, resource.search(null, null, "", null, null).size());
        }
    }

    /**
     * The list page hands the template what it filtered and echoes the criteria
     * back, so the form stays filled after a search.
     */
    @Test
    void theListPageEchoesTheCriteria() {
        AdminDocumentResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminDocuments.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(document("F-1", DocumentType.FACTURE, "Acme")));
            assertSame(instance, resource.list("f", "pt", "FACTURE", null, null));
        }
        verify(resource.adminDocuments).data(eq("documents"), any());
    }

    /**
     * The extraction renders a header and one line per document, the separator
     * neutralized in the cells and the null amounts left empty.
     */
    @Test
    void theExtractionRendersTheFilteredDocuments() {
        AdminDocumentResource resource = newResource();
        Invoice document = document("F-1", DocumentType.FACTURE, "Acme; et fils");
        document.customerTaxId = "PT1";
        document.customerSiret = "SIR";
        document.totalVat = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(document));
            Response response = resource.export(null, null, null, null, null);
            assertEquals(200, response.getStatus());
            String csv = (String) response.getEntity();
            assertTrue(csv.startsWith("DOCUMENT_NUMBER;DOCUMENT_TYPE;ISSUE_DATE;"));
            assertTrue(csv.contains("F-1;FACTURE;2026-09-10T18:42;CAISSE1;T-1;"));
            // BO-10-04-19 et -14 : la date d'échéance et l'éco-taxe sont deux
            // colonnes de plus, vides sur un document qui n'en porte pas.
            assertTrue(csv.contains("Acme, et fils;SIR;;PT1;;100.00;120.00;;0;1"));
            assertTrue(response.getHeaderString("Content-Disposition").contains("documents-"));
        }
    }

    /**
     * The list page also echoes the opposite arms of its five null guards: the
     * text, taxId and type criteria absent (echoed as empty), and both date
     * bounds present (echoed as they were typed) — the arms the first echo test
     * does not exercise.
     */
    @Test
    void theListPageEchoesTheOppositeArms() {
        AdminDocumentResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminDocuments.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Invoice.list(BOTH_BOUNDS,
                            LocalDate.of(2026, 9, 1).atStartOfDay(),
                            LocalDate.of(2026, 9, 30).plusDays(1).atStartOfDay()))
                    .thenReturn(List.of(document("F-1", DocumentType.FACTURE, "Acme")));
            assertSame(instance, resource.list(null, null, null, "2026-09-01", "2026-09-30"));
        }
        verify(instance).data(eq("text"), eq(""));
        verify(instance).data(eq("taxId"), eq(""));
        verify(instance).data(eq("type"), eq(""));
        verify(instance).data(eq("from"), eq("2026-09-01"));
        verify(instance).data(eq("to"), eq("2026-09-30"));
    }

    /**
     * A blank text criterion counts as absent rather than as a fragment to match
     * — the blank arm of the lower-casing guard — so every document is kept.
     */
    @Test
    void aBlankTextCriterionCountsAsAbsent() {
        AdminDocumentResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(document("F-1", DocumentType.FACTURE, "Acme"),
                    document("F-2", DocumentType.FACTURE, "Bravo")));
            assertEquals(2, resource.search("   ", null, null, null, null).size());
        }
    }

    /**
     * A document that carries a due date renders it in its own column — the
     * non-null arm of the export's due-date guard.
     */
    @Test
    void theExtractionRendersTheDueDate() {
        AdminDocumentResource resource = newResource();
        Invoice document = document("F-1", DocumentType.FACTURE, "Acme");
        document.customerDueDate = LocalDate.of(2026, 10, 15);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(document));
            String csv = (String) resource.export(null, null, null, null, null).getEntity();
            assertTrue(csv.contains(";2026-10-15;"));
        }
    }

    /**
     * A document with no kind and no date at all still extracts, its cells
     * empty — the null arms of the export's own guards.
     */
    @Test
    void theExtractionToleratesTheMissingParts() {
        AdminDocumentResource resource = newResource();
        Invoice bare = document("X-1", null, "Sans type");
        bare.issueDate = null;
        bare.totalExcludingTax = null;
        bare.totalIncludingTax = null;
        bare.totalVat = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(bare));
            String csv = (String) resource.export(null, null, null, null, null).getEntity();
            assertTrue(csv.contains("X-1;;;CAISSE1;T-1;"));
            assertFalse(csv.contains("null"));
        }
    }
}
