package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.sale.DocumentType;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        resource.adminDocument = mock(Template.class);
        return resource;
    }

    /**
     * Builds a Portuguese fiscal document (BO-02-04-19).
     *
     * @param number the document number
     * @param type its type, possibly null
     * @param ticketId the store-local id of the ticket it states, possibly null
     * @return the document
     */
    private FiscalDocument fiscal(String number, FiscalDocument.Type type, Long ticketId) {
        FiscalDocument document = new FiscalDocument();
        document.number = number;
        document.documentType = type;
        document.ticketId = ticketId;
        document.issuedAt = LocalDateTime.of(2026, 9, 11, 9, 0);
        document.terminalId = "POS01";
        document.customerName = "Cliente";
        document.customerTaxId = "PT999999990";
        document.grossTotal = new BigDecimal("12.30");
        document.taxTotal = new BigDecimal("2.30");
        document.atcud = "ABCD1234-7";
        document.hash = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
        document.qrPayload = "A:1*B:2";
        document.sourceNumber = "FT POS01/1";
        return document;
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
            List<AdminDocumentResource.DocumentRow> matches = resource.search(null, "pt123", null, null, null);
            assertEquals(1, matches.size());
            assertEquals("F-1", matches.get(0).number);
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
            assertTrue(csv.contains("Acme, et fils;SIR;;PT1;;100.00;120.00;;0;1;;;0"));
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
    /**
     * The Portuguese documents are listed beside the invoices, told apart by
     * their type, each with its ticket number, most recent first (BO-02-04-19).
     */
    @Test
    void thePortugueseDocumentsAreListedByType() {
        AdminDocumentResource resource = newResource();
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "POS01-000012";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(document("F-1", DocumentType.FACTURE, "Acme")));
            panache.when(() -> FiscalDocument.list("order by issuedAt desc")).thenReturn(List.of(
                    fiscal("FS POS01/1", FiscalDocument.Type.FS, 7L),
                    fiscal("RC POS01/1", FiscalDocument.Type.RC, null)));
            panache.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            List<AdminDocumentResource.DocumentRow> rows = resource.search(null, null, null, null, null);
            assertEquals(3, rows.size());
            assertEquals("FS POS01/1", rows.get(0).number);
            assertEquals("FS", rows.get(0).type);
            assertEquals("FATURA SIMPLIFICADA", rows.get(0).typeTitle);
            assertEquals("POS01-000012", rows.get(0).ticketNumber);
            assertEquals(new BigDecimal("10.00"), rows.get(0).totalExcludingTax);
            assertTrue(rows.get(0).isFiscal());
            assertNull(rows.get(1).ticketNumber);
            assertFalse(rows.get(2).isFiscal());
            assertEquals(1, resource.search(null, null, "RC", null, null).size());
            assertEquals(2, resource.search(null, "pt9999", null, null, null).size());
        }
    }

    /**
     * The four bound cases of the fiscal documents each issue their own query.
     */
    @Test
    void theFiscalBoundsHaveTheirOwnQueries() {
        AdminDocumentResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> FiscalDocument.list("issuedAt >= ?1 and issuedAt < ?2 order by issuedAt desc",
                    LocalDate.of(2026, 9, 1).atStartOfDay(), LocalDate.of(2026, 9, 2).atStartOfDay()))
                    .thenReturn(List.of(fiscal("FT 1", FiscalDocument.Type.FT, null)));
            panache.when(() -> FiscalDocument.list("issuedAt >= ?1 order by issuedAt desc",
                    LocalDate.of(2026, 9, 1).atStartOfDay()))
                    .thenReturn(List.of(fiscal("FT 2", FiscalDocument.Type.FT, null)));
            panache.when(() -> FiscalDocument.list("issuedAt < ?1 order by issuedAt desc",
                    LocalDate.of(2026, 9, 2).atStartOfDay()))
                    .thenReturn(List.of(fiscal("FT 3", FiscalDocument.Type.FT, null)));
            assertEquals("FT 1", resource.search(null, null, null, "2026-09-01", "2026-09-01").get(0).number);
            assertEquals("FT 2", resource.search(null, null, null, "2026-09-01", null).get(0).number);
            assertEquals("FT 3", resource.search(null, null, null, null, "2026-09-01").get(0).number);
        }
    }

    /**
     * A fiscal document without a type, totals or signature still makes a row,
     * and a row without an issue date sorts last.
     */
    @Test
    void aBareFiscalDocumentMakesARow() {
        FiscalDocument bare = fiscal("X", null, null);
        bare.grossTotal = null;
        bare.hash = null;
        AdminDocumentResource.DocumentRow row = AdminDocumentResource.DocumentRow.of(bare, null);
        assertNull(row.type);
        assertNull(row.typeTitle);
        assertNull(row.totalExcludingTax);
        assertNull(row.hashCharacters);
        FiscalDocument noTax = fiscal("Y", FiscalDocument.Type.NC, null);
        noTax.taxTotal = null;
        assertNull(AdminDocumentResource.DocumentRow.of(noTax, null).totalExcludingTax);
        assertEquals(4, AdminDocumentResource.DocumentRow.of(fiscal("Z", FiscalDocument.Type.FT, null), null)
                .hashCharacters.length());
        AdminDocumentResource resource = newResource();
        Invoice undated = document("F-0", DocumentType.FACTURE, "Zulu");
        undated.issueDate = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(undated, document("F-1", DocumentType.FACTURE, "Acme")));
            assertEquals("F-0", resource.search(null, null, null, null, null).get(1).number);
        }
    }

    /**
     * The kinds offered by the filter are the two commercial ones, then the four
     * Portuguese ones with their names.
     */
    @Test
    void theKindsListBothFamilies() {
        List<AdminDocumentResource.Kind> kinds = AdminDocumentResource.kinds();
        assertEquals(6, kinds.size());
        assertEquals("FACTURE", kinds.get(0).code());
        assertEquals("NC", kinds.get(4).code());
        assertEquals("NC — NOTA DE CRÉDITO", kinds.get(4).title());
    }

    /**
     * {@code find} answers null on a null or blank number, an invoice first,
     * else a fiscal document, else null.
     */
    @Test
    void findLooksInBothFamilies() {
        AdminDocumentResource resource = newResource();
        assertNull(resource.find(null));
        assertNull(resource.find("  "));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Invoice> invoices = mock(PanacheQuery.class);
            PanacheQuery<FiscalDocument> fiscals = mock(PanacheQuery.class);
            PanacheQuery<FiscalDocument> none = mock(PanacheQuery.class);
            PanacheQuery<Invoice> noInvoice = mock(PanacheQuery.class);
            when(invoices.firstResult()).thenReturn(document("F-1", DocumentType.FACTURE, "Acme"));
            when(fiscals.firstResult()).thenReturn(fiscal("FS 1", FiscalDocument.Type.FS, null));
            panache.when(() -> Invoice.find("documentNumber", "F-1")).thenReturn(invoices);
            panache.when(() -> Invoice.find("documentNumber", "FS 1")).thenReturn(noInvoice);
            panache.when(() -> Invoice.find("documentNumber", "NONE")).thenReturn(noInvoice);
            panache.when(() -> FiscalDocument.find("number", "FS 1")).thenReturn(fiscals);
            panache.when(() -> FiscalDocument.find("number", "NONE")).thenReturn(none);
            assertEquals("FACTURE", resource.find(" F-1 ").type);
            assertEquals("FS", resource.find("FS 1").type);
            assertNull(resource.find("NONE"));
        }
    }

    /**
     * The detail of a fiscal document shows the lines it covers, the cancelled
     * ones left out, and the printed image of its sale.
     */
    @Test
    void theDetailOfAFiscalDocumentShowsItsCoveredLines() {
        AdminDocumentResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminDocument.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        FiscalDocument document = fiscal("FS 1", FiscalDocument.Type.FS, 7L);
        Ticket ticket = new Ticket();
        ticket.formattedContent = "TICKET";
        TicketLine sold = new TicketLine();
        sold.lineNumber = 1;
        TicketLine cancelled = new TicketLine();
        cancelled.cancelled = true;
        ticket.lines = new java.util.ArrayList<>(List.of(sold, cancelled));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Invoice> noInvoice = mock(PanacheQuery.class);
            PanacheQuery<FiscalDocument> fiscals = mock(PanacheQuery.class);
            when(fiscals.firstResult()).thenReturn(document);
            panache.when(() -> Invoice.find("documentNumber", "FS 1")).thenReturn(noInvoice);
            panache.when(() -> FiscalDocument.find("number", "FS 1")).thenReturn(fiscals);
            panache.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(instance, resource.view("FS 1"));
        }
        verify(instance).data(eq("lines"), eq(List.of(sold)));
        verify(instance).data(eq("printed"), eq("TICKET"));
    }

    /**
     * The printed ticket of the detail drops the printer's image, barcode and
     * QR markup and keeps its text; no printout stays none.
     */
    @Test
    void thePrintedTicketDropsThePrinterMarkup() {
        assertEquals("HEAD\nTEXT\nEND", AdminDocumentResource.readable(
                "[[IMAGE 384 72 A/8+]]\nHEAD\nTEXT\n[[QR A:1*B:2]]\n[[BARCODE POS01-1]]\nEND"));
        assertNull(AdminDocumentResource.readable(null));
    }

    /**
     * The detail of an invoice keeps every live line of its ticket, a ticket
     * without lines shows none, and an unknown number shows nothing at all.
     */
    @Test
    void theDetailOfAnInvoiceAndOfNothing() {
        AdminDocumentResource resource = newResource();
        TemplateInstance first = mock(TemplateInstance.class);
        when(resource.adminDocument.data(anyString(), any())).thenReturn(first);
        when(first.data(anyString(), any())).thenReturn(first);
        Invoice invoice = document("F-1", DocumentType.FACTURE, "Acme");
        Ticket ticket = new Ticket();
        ticket.id = 9L;
        TicketLine sold = new TicketLine();
        ticket.lines = new java.util.ArrayList<>(List.of(sold));
        invoice.ticket = ticket;
        Invoice orphan = document("F-2", DocumentType.FACTURE, "Bravo");
        Ticket lineless = new Ticket();
        lineless.id = 10L;
        lineless.lines = null;
        orphan.ticket = lineless;
        Invoice gone = document("F-3", DocumentType.FACTURE, "Charlie");
        Ticket missing = new Ticket();
        missing.id = 11L;
        gone.ticket = missing;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Invoice> one = mock(PanacheQuery.class);
            PanacheQuery<Invoice> two = mock(PanacheQuery.class);
            PanacheQuery<Invoice> three = mock(PanacheQuery.class);
            when(one.firstResult()).thenReturn(invoice);
            when(two.firstResult()).thenReturn(orphan);
            when(three.firstResult()).thenReturn(gone);
            panache.when(() -> Invoice.find("documentNumber", "F-1")).thenReturn(one);
            panache.when(() -> Invoice.find("documentNumber", "F-2")).thenReturn(two);
            panache.when(() -> Invoice.find("documentNumber", "F-3")).thenReturn(three);
            panache.when(() -> Ticket.findById(9L)).thenReturn(ticket);
            panache.when(() -> Ticket.findById(10L)).thenReturn(lineless);
            resource.view("F-1");
            resource.view("F-2");
            resource.view("F-3");
            resource.view(null);
        }
        verify(first).data(eq("lines"), eq(List.of(sold)));
        verify(resource.adminDocument).data(eq("document"), eq(null));
    }

    /**
     * The extraction states a fiscal document's ATCUD, its source document and
     * its cancellation, the print count left empty.
     */
    @Test
    void theExtractionStatesTheFiscalColumns() {
        AdminDocumentResource resource = newResource();
        FiscalDocument cancelled = fiscal("NC POS01/1", FiscalDocument.Type.NC, null);
        cancelled.cancelled = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> FiscalDocument.list("order by issuedAt desc")).thenReturn(List.of(cancelled));
            String csv = (String) resource.export(null, null, null, null, null).getEntity();
            assertTrue(csv.contains(";10.00;12.30;2.30;;;ABCD1234-7;FT POS01/1;1"));
        }
    }
    /**
     * The detail of an invoice stating no ticket shows no line, a fiscal
     * document shows only the lines it covers, and a fiscal document whose
     * ticket this node does not hold states no ticket number.
     */
    @Test
    void theDetailKeepsOnlyWhatTheDocumentStates() {
        AdminDocumentResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminDocument.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        Invoice ticketless = document("F-9", DocumentType.FACTURE, "Acme");
        FiscalDocument partial = fiscal("FT 2", FiscalDocument.Type.FT, 8L);
        partial.lineUids = "U1";
        Ticket ticket = new Ticket();
        TicketLine covered = new TicketLine();
        covered.lineUid = "U1";
        TicketLine other = new TicketLine();
        other.lineUid = "U2";
        ticket.lines = new java.util.ArrayList<>(List.of(covered, other));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Invoice> invoice = mock(PanacheQuery.class);
            when(invoice.firstResult()).thenReturn(ticketless);
            PanacheQuery<Invoice> noInvoice = mock(PanacheQuery.class);
            PanacheQuery<FiscalDocument> fiscals = mock(PanacheQuery.class);
            when(fiscals.firstResult()).thenReturn(partial);
            panache.when(() -> Invoice.find("documentNumber", "F-9")).thenReturn(invoice);
            panache.when(() -> Invoice.find("documentNumber", "FT 2")).thenReturn(noInvoice);
            panache.when(() -> FiscalDocument.find("number", "FT 2")).thenReturn(fiscals);
            panache.when(() -> Ticket.findById(8L)).thenReturn(ticket);
            resource.view("F-9");
            resource.view("FT 2");
            panache.when(() -> FiscalDocument.list("order by issuedAt desc"))
                    .thenReturn(List.of(fiscal("FS 3", FiscalDocument.Type.FS, 77L)));
            assertNull(resource.search(null, null, null, null, null).get(0).ticketNumber);
        }
        verify(instance).data(eq("lines"), eq(List.of()));
        verify(instance).data(eq("lines"), eq(List.of(covered)));
    }
}
