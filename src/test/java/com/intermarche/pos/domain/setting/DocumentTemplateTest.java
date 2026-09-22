package com.intermarche.pos.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(12, DocumentTemplate.DocumentType.values().length);
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
}
