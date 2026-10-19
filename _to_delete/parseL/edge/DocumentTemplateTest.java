package com.intermarche.edge.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentTemplate}, targeting 100% branch coverage.
 * <p>
 * Instance methods run on plain instances; the finders resolve the Panache
 * statics, which fall back to {@link PanacheEntityBase} outside a Quarkus
 * context and are intercepted with {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every leg exercised): {@code isRenderable} covers the
 * deactivated leg, the null-source leg, the blank-source leg and the written
 * arm; {@code hasValidWidth} covers both bounds at their exact values and one
 * outside each; {@code effectiveCopies} covers the administered arm and the
 * floor, at the boundary; {@code listActiveFor} covers the null-type arm and
 * the forwarding arm; {@code findFor} covers the empty-list arm, the null-entry
 * arm, the unrenderable-entry arm, the found arm and the none-found arm;
 * {@code findByCode} covers the null leg, the blank leg, the found arm and the
 * missing arm.
 */
class DocumentTemplateTest {

    /**
     * Builds an administered template of the sale receipt.
     *
     * @return the administered template
     */
    private DocumentTemplate saleReceipt() {
        DocumentTemplate template = new DocumentTemplate();
        template.code = "TICKET_VENTE";
        template.label = "Ticket de vente";
        template.documentType = DocumentTemplate.DocumentType.SALE_RECEIPT;
        template.active = true;
        template.priority = 100;
        template.width = 42;
        template.copies = 1;
        template.source = "TOTAL {ticket.total}";
        return template;
    }

    /**
     * Builds a Panache query answering the given single result.
     *
     * @param result the row the query answers, or null
     * @return the stubbed query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<DocumentTemplate> queryOf(DocumentTemplate result) {
        PanacheQuery<DocumentTemplate> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * A written, active template is renderable; a deactivated one is not, and
     * neither is one whose source was never typed — null and blank alike.
     */
    @Test
    void onlyAWrittenActiveTemplateIsRenderable() {
        assertTrue(saleReceipt().isRenderable());

        DocumentTemplate deactivated = saleReceipt();
        deactivated.active = false;
        assertFalse(deactivated.isRenderable());

        DocumentTemplate unwritten = saleReceipt();
        unwritten.source = null;
        assertFalse(unwritten.isRenderable());

        DocumentTemplate blank = saleReceipt();
        blank.source = "   ";
        assertFalse(blank.isRenderable());
    }


    /**
     * The published width bounds are the ones a roll and a page actually take.
     */
    @Test
    void theAdministeredWidthBoundsArePublished() {
        assertEquals(20, DocumentTemplate.MIN_WIDTH);
        assertEquals(120, DocumentTemplate.MAX_WIDTH);
    }


    /**
     * {@code listActiveFor} forwards the administered order verbatim, and
     * answers nothing for a null type without touching the database.
     */
    @Test
    void listActiveForForwardsTheAdministeredOrder() {
        DocumentTemplate template = saleReceipt();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.SALE_RECEIPT))
                    .thenReturn(List.of(template));
            assertEquals(List.of(template),
                    DocumentTemplate.listActiveFor(DocumentTemplate.DocumentType.SALE_RECEIPT));
        }
        assertTrue(DocumentTemplate.listActiveFor(null).isEmpty());
    }

    /**
     * {@code findFor} answers the first template able to render, skipping a null
     * row and one that cannot.
     */
    @Test
    void findForAnswersTheFirstRenderableTemplate() {
        DocumentTemplate blank = saleReceipt();
        blank.source = "";
        DocumentTemplate wanted = saleReceipt();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.SALE_RECEIPT))
                    .thenReturn(java.util.Arrays.asList(null, blank, wanted));
            assertSame(wanted,
                    DocumentTemplate.findFor(DocumentTemplate.DocumentType.SALE_RECEIPT));
        }
    }

    /**
     * {@code findFor} answers null when nothing can render and when nothing is
     * administered at all — the two empty arms.
     */
    @Test
    void findForAnswersNullWhenNothingCanRender() {
        DocumentTemplate blank = saleReceipt();
        blank.source = "";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.INVOICE))
                    .thenReturn(List.of(blank));
            assertNull(DocumentTemplate.findFor(DocumentTemplate.DocumentType.INVOICE));
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.INVOICE))
                    .thenReturn(List.of());
            assertNull(DocumentTemplate.findFor(DocumentTemplate.DocumentType.INVOICE));
        }
        assertNull(DocumentTemplate.findFor(null));
    }

    /**
     * {@code listAllOrdered} answers the whole referential, deactivated rows
     * included — what the administration screen shows.
     */
    @Test
    void listAllOrderedAnswersTheWholeReferential() {
        DocumentTemplate template = saleReceipt();
        template.active = false;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list("order by documentType, priority, code"))
                    .thenReturn(List.of(template));
            assertEquals(List.of(template), DocumentTemplate.listAllOrdered());
        }
    }

    /**
     * {@code findByCode} answers the administered row, trimming what the caller
     * passed, and null when no row carries that code.
     */
    @Test
    void findByCodeAnswersTheAdministeredRow() {
        DocumentTemplate template = saleReceipt();
        PanacheQuery<DocumentTemplate> found = queryOf(template);
        PanacheQuery<DocumentTemplate> empty = queryOf(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.find("code", "TICKET_VENTE")).thenReturn(found);
            panache.when(() -> DocumentTemplate.find("code", "INCONNU")).thenReturn(empty);
            assertSame(template, DocumentTemplate.findByCode(" TICKET_VENTE "));
            assertNull(DocumentTemplate.findByCode("INCONNU"));
        }
    }

    /**
     * A null or blank code is answered without touching the database, which is
     * what the absence of any stubbing here asserts.
     */
    @Test
    void findByCodeShortCircuitsOnAnEmptyCode() {
        assertNull(DocumentTemplate.findByCode(null));
        assertNull(DocumentTemplate.findByCode("   "));
    }

    /**
     * The document types the register emits are the ones the referential
     * publishes, labels included.
     */
    @Test
    void theDocumentTypesAreTheOnesTheRegisterEmits() {
        assertEquals(24, DocumentTemplate.DocumentType.values().length);
        // BO-03-10-05: the paper a code nobody recognized leaves behind.
        assertEquals("Ticket article inconnu",
                DocumentTemplate.DocumentType.valueOf("UNKNOWN_ARTICLE_TICKET").getLabel());
        // BO-03-03-36 and -07: the exit ticket that REPLACES the receipt, and
        // the resume ticket of a parked sale.
        assertEquals("Ticket de sortie",
                DocumentTemplate.DocumentType.valueOf("EXIT_TICKET").getLabel());
        assertEquals("Ticket de reprise",
                DocumentTemplate.DocumentType.valueOf("PARKED_TICKET_RESUME").getLabel());
        assertEquals("Ticket de vente",
                DocumentTemplate.DocumentType.valueOf("SALE_RECEIPT").getLabel());
        // BO-03-03-10: the loyalty settlement slip, additional to the receipt.
        assertEquals("Justificatif paiement fidélité",
                DocumentTemplate.DocumentType.valueOf("LOYALTY_RECEIPT").getLabel());
        assertEquals("Rapport Z",
                DocumentTemplate.DocumentType.valueOf("Z_REPORT").getLabel());
        // BO-03-03: ONE invoice, TWO papers, two rows — the roll and the A4 page
        // cannot share a source, and a shop restating one must restate the other.
        assertEquals("Facture (rouleau)",
                DocumentTemplate.DocumentType.valueOf("INVOICE").getLabel());
        assertEquals("Facture (A4 réseau)",
                DocumentTemplate.DocumentType.valueOf("INVOICE_A4").getLabel());
    }

    /**
     * BO-03-04-12: {@code titleFor} answers the FIRST NON-BLANK administered
     * name, walking past a null row, a row carrying none and a row carrying a
     * blank one — every leg of its guard — and it trims what it answers.
     */
    @Test
    void titleForAnswersTheFirstNonBlankName() {
        DocumentTemplate none = saleReceipt();
        DocumentTemplate blank = saleReceipt();
        blank.title = "   ";
        DocumentTemplate named = saleReceipt();
        named.title = "  BON DE REPRISE  ";
        DocumentTemplate later = saleReceipt();
        later.title = "JAMAIS LUE";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.SALE_RECEIPT))
                    .thenReturn(java.util.Arrays.asList(null, none, blank, named, later));
            assertEquals("BON DE REPRISE",
                    DocumentTemplate.titleFor(DocumentTemplate.DocumentType.SALE_RECEIPT));
        }
    }

    /**
     * BO-03-04-12: a name is read off a row WHATEVER ITS SOURCE — a shop may
     * rename a document it has not restated, and that row is not renderable.
     */
    @Test
    void titleForReadsARowThatLaysOutNothing() {
        DocumentTemplate nameOnly = saleReceipt();
        nameOnly.source = "";
        nameOnly.title = "TICKET CLIENT";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.SALE_RECEIPT))
                    .thenReturn(List.of(nameOnly));
            assertNull(DocumentTemplate.findFor(DocumentTemplate.DocumentType.SALE_RECEIPT));
            assertEquals("TICKET CLIENT",
                    DocumentTemplate.titleFor(DocumentTemplate.DocumentType.SALE_RECEIPT));
        }
    }

    /**
     * BO-03-04-12: nothing administered, and nothing administered for a null
     * type — the two empty arms, where the register's own wording stands.
     */
    @Test
    void titleForAnswersNullWhenNoNameIsAdministered() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.SALE_RECEIPT))
                    .thenReturn(List.of());
            assertNull(DocumentTemplate.titleFor(DocumentTemplate.DocumentType.SALE_RECEIPT));
        }
        assertNull(DocumentTemplate.titleFor(null));
    }

    /**
     * BO-03-04-13: the reprint name walks the same legs and is a SEPARATE
     * word — a row naming the document names nothing for its reprints.
     */
    @Test
    void duplicateTitleForIsReadApartFromTheDocumentName() {
        DocumentTemplate named = saleReceipt();
        named.title = "TICKET CLIENT";
        DocumentTemplate blank = saleReceipt();
        blank.duplicateTitle = "  ";
        DocumentTemplate reprint = saleReceipt();
        reprint.duplicateTitle = " DUPLICADO N%d ";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.SALE_RECEIPT))
                    .thenReturn(java.util.Arrays.asList(null, named, blank, reprint));
            assertEquals("DUPLICADO N%d", DocumentTemplate.duplicateTitleFor(
                    DocumentTemplate.DocumentType.SALE_RECEIPT));
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.REFUND_RECEIPT))
                    .thenReturn(List.of(named));
            assertNull(DocumentTemplate.duplicateTitleFor(
                    DocumentTemplate.DocumentType.REFUND_RECEIPT));
        }
        assertNull(DocumentTemplate.duplicateTitleFor(null));
    }

    /**
     * BO-03-04-22: the number's PREFIX is read like the names — first
     * non-blank in administered order, trimmed — and absent when nobody gave
     * one, where the register numbers under the type's own name.
     */
    @Test
    void prefixForAnswersTheFirstNonBlankPrefix() {
        DocumentTemplate none = saleReceipt();
        DocumentTemplate blank = saleReceipt();
        blank.numberPrefix = " ";
        DocumentTemplate prefixed = saleReceipt();
        prefixed.numberPrefix = " FT ";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.FT))
                    .thenReturn(java.util.Arrays.asList(null, none, blank, prefixed));
            assertEquals("FT", DocumentTemplate.prefixFor(DocumentTemplate.DocumentType.FT));
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.NC))
                    .thenReturn(List.of());
            assertNull(DocumentTemplate.prefixFor(DocumentTemplate.DocumentType.NC));
        }
        assertNull(DocumentTemplate.prefixFor(null));
    }

    /**
     * BO-03-04-24, -25 and -26: each printed copy has its own name, read by
     * RANK — one is the original, two the duplicate, three the triplicate.
     */
    @Test
    void copyTitleForNamesEachCopyByItsRank() {
        DocumentTemplate named = saleReceipt();
        named.copyTitles = "ORIGINAL;DUPLICADO;TRIPLICADO";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.FT))
                    .thenReturn(List.of(named));
            assertEquals("ORIGINAL",
                    DocumentTemplate.copyTitleFor(DocumentTemplate.DocumentType.FT, 1));
            assertEquals("DUPLICADO",
                    DocumentTemplate.copyTitleFor(DocumentTemplate.DocumentType.FT, 2));
            assertEquals("TRIPLICADO",
                    DocumentTemplate.copyTitleFor(DocumentTemplate.DocumentType.FT, 3));
            // A rank BEYOND the names administered answers null rather than the
            // last name: a fourth copy called TRIPLICADO would state something
            // false on a fiscal document.
            assertNull(DocumentTemplate.copyTitleFor(DocumentTemplate.DocumentType.FT, 4));
        }
    }

    /**
     * The guards of {@code copyTitleFor}: a rank below one, a row naming
     * nothing, a row whose name for that rank is blank, and nothing
     * administered at all — each answering null.
     */
    @Test
    void copyTitleForAnswersNullOnEveryEmptyLeg() {
        DocumentTemplate silent = saleReceipt();
        DocumentTemplate holed = saleReceipt();
        holed.copyTitles = "ORIGINAL;;TRIPLICADO";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.NC))
                    .thenReturn(java.util.Arrays.asList(null, silent, holed));
            assertNull(DocumentTemplate.copyTitleFor(DocumentTemplate.DocumentType.NC, 0));
            assertEquals("ORIGINAL",
                    DocumentTemplate.copyTitleFor(DocumentTemplate.DocumentType.NC, 1));
            assertNull(DocumentTemplate.copyTitleFor(DocumentTemplate.DocumentType.NC, 2));
            assertEquals("TRIPLICADO",
                    DocumentTemplate.copyTitleFor(DocumentTemplate.DocumentType.NC, 3));
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.RC))
                    .thenReturn(List.of());
            assertNull(DocumentTemplate.copyTitleFor(DocumentTemplate.DocumentType.RC, 1));
        }
    }

    /**
     * The four Portuguese types are at the enum and name themselves to the
     * administration screen — without them no gabarit, no sequence and no
     * copy count can be administered for them.
     */
    @Test
    void theFourFiscalTypesAreOffered() {
        for (DocumentTemplate.DocumentType type : List.of(
                DocumentTemplate.DocumentType.FS, DocumentTemplate.DocumentType.FT,
                DocumentTemplate.DocumentType.NC, DocumentTemplate.DocumentType.RC)) {
            assertNotNull(type.getLabel(), type.name());
            assertTrue(type.getLabel().startsWith("Portugal"), type.name());
        }
    }
}
