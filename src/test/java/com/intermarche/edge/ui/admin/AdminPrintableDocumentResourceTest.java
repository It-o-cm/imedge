package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Menu;
import com.intermarche.edge.domain.sale.DocumentType;
import com.intermarche.edge.domain.setting.DocumentTemplate;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.setting.PrintableDocument;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminPrintableDocumentResource} (BO-03-04-06, -09, -10
 * and -14).
 *
 * <p>Plain JUnit 5 + Mockito, no Quarkus and no database. Branches, leg by leg:
 * the page with both notice arms and the template filter on each type;
 * {@code saveDocument} refused on a missing code, a missing name, an unknown
 * nature and an unknown template, then created and updated, with and without a
 * template; {@code toggleDocument} unknown, off and on; {@code deleteDocument}
 * unknown, already printed and deleted.
 */
class AdminPrintableDocumentResourceTest {

    /**
     * Builds a resource with a mocked template.
     *
     * @return the resource
     */
    private AdminPrintableDocumentResource newResource() {
        AdminPrintableDocumentResource resource = new AdminPrintableDocumentResource();
        resource.adminPrintableDocuments = mock(Template.class);
        return resource;
    }

    /**
     * Builds a posted form from key/value pairs.
     *
     * @param pairs the alternating keys and values
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> posted = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            posted.putSingle(pairs[i], pairs[i + 1]);
        }
        return posted;
    }

    /**
     * Tells whether a redirect carries a failure notice.
     *
     * @param response the redirect
     * @return true when the notice reports a refusal
     */
    private boolean refused(Response response) {
        return response.getLocation().toString().contains("noticeOk=false");
    }

    /**
     * Builds a template of a given type.
     *
     * @param code its code
     * @param type its document type
     * @return the template
     */
    private DocumentTemplate template(String code, DocumentTemplate.DocumentType type) {
        DocumentTemplate template = new DocumentTemplate();
        template.code = code;
        template.documentType = type;
        return template;
    }

    /** The screen is declared in the settings menu. */
    @Test
    void theScreenIsDeclaredInTheMenu() {
        Feature feature = Feature.PRINTABLE_DOCUMENT;
        assertEquals("printable_document", feature.getKey());
        assertEquals("/admin/printable-documents", feature.getPath());
        assertEquals(Menu.PARAMETRAGE, feature.getMenu());
    }

    /**
     * The page lists the documents, the natures, and only the templates that
     * lay out a document printed over a ticket.
     */
    @Test
    void thePageListsTheDocumentsAndTheirLayouts() {
        AdminPrintableDocumentResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminPrintableDocuments.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        PrintableDocument row = new PrintableDocument();
        DocumentTemplate roll = template("INV", DocumentTemplate.DocumentType.INVOICE);
        DocumentTemplate page = template("INV_A4", DocumentTemplate.DocumentType.INVOICE_A4);
        DocumentTemplate receipt = template("SALE", DocumentTemplate.DocumentType.SALE_RECEIPT);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by priority, code")).thenReturn(List.of(row));
            panache.when(() -> PanacheEntityBase.list("order by documentType, priority, code"))
                    .thenReturn(List.of(roll, receipt, page));
            assertSame(instance, resource.documentsPage(null, "false"));
            verify(resource.adminPrintableDocuments).data("documents", List.of(row));
            verify(instance).data("kinds", DocumentType.values());
            verify(instance).data("templates", List.of(roll, page));
            verify(instance).data("noticeOk", false);
            resource.documentsPage("Document créé.", "true");
            verify(instance).data("noticeOk", true);
        }
    }

    /** A document without code, or without name, is refused before anything is read. */
    @Test
    void aDocumentWithoutCodeOrNameIsRefused() {
        AdminPrintableDocumentResource resource = newResource();
        assertTrue(refused(resource.saveDocument(form("label", "FACTURE PRO"))));
        assertTrue(refused(resource.saveDocument(form("code", "FACT_PRO"))));
    }

    /** An unknown nature, and a template the referential does not hold, are refused. */
    @Test
    void anUnknownNatureOrTemplateIsRefused() {
        AdminPrintableDocumentResource resource = newResource();
        assertTrue(refused(resource.saveDocument(form("code", "FACT_PRO", "label", "FACTURE PRO",
                "kind", "DEVIS"))));
        try (MockedStatic<DocumentTemplate> templates = mockStatic(DocumentTemplate.class)) {
            templates.when(() -> DocumentTemplate.findByCode("NOPE")).thenReturn(null);
            assertTrue(refused(resource.saveDocument(form("code", "FACT_PRO", "label", "FACTURE PRO",
                    "kind", "FACTURE", "templateCode", "NOPE"))));
        }
    }

    /**
     * A new document is written whole — every mandatory column set before it
     * is persisted —, its printer read as one of the three and its order
     * defaulted.
     */
    @Test
    void aNewDocumentIsCreatedWhole() {
        AdminPrintableDocumentResource resource = newResource();
        try (MockedStatic<DocumentTemplate> templates = mockStatic(DocumentTemplate.class);
             MockedStatic<PrintableDocument> documents = mockStatic(PrintableDocument.class);
             MockedConstruction<PrintableDocument> created = mockConstruction(PrintableDocument.class)) {
            templates.when(() -> DocumentTemplate.findByCode("INV_PRO"))
                    .thenReturn(template("INV_PRO", DocumentTemplate.DocumentType.INVOICE));
            documents.when(() -> PrintableDocument.findByCode("FACT_PRO")).thenReturn(null);
            documents.when(() -> PrintableDocument.outputOf(any())).thenCallRealMethod();
            Response response = resource.saveDocument(form("code", "FACT_PRO", "label", "FACTURE PRO",
                    "kind", "BON_LIVRAISON", "templateCode", "INV_PRO", "output", "facturette",
                    "active", "on"));
            assertFalse(refused(response));
            assertTrue(response.getLocation().toString().contains("notice="));
            PrintableDocument made = created.constructed().getFirst();
            assertEquals("FACT_PRO", made.code);
            assertEquals("FACTURE PRO", made.label);
            assertEquals(DocumentType.BON_LIVRAISON, made.kind);
            assertEquals("INV_PRO", made.templateCode);
            assertEquals("FACTURETTE", made.output);
            assertEquals(100, made.priority);
            assertTrue(made.active);
            verify(made).persist();
        }
    }

    /** An existing document is updated in place, without template and deactivated by an unchecked box. */
    @Test
    void anExistingDocumentIsUpdated() {
        AdminPrintableDocumentResource resource = newResource();
        PrintableDocument existing = mock(PrintableDocument.class);
        existing.code = "FACT_PRO";
        try (MockedStatic<PrintableDocument> documents = mockStatic(PrintableDocument.class)) {
            documents.when(() -> PrintableDocument.findByCode("FACT_PRO")).thenReturn(existing);
            documents.when(() -> PrintableDocument.outputOf(any())).thenCallRealMethod();
            Response response = resource.saveDocument(form("code", "FACT_PRO", "label", "BL PRO",
                    "kind", "FACTURE", "priority", "5"));
            assertFalse(refused(response));
            assertEquals("BL PRO", existing.label);
            assertEquals(DocumentType.FACTURE, existing.kind);
            assertNull(existing.templateCode);
            assertNull(existing.output);
            assertEquals(5, existing.priority);
            assertFalse(existing.active);
            verify(existing).persist();
        }
    }

    /** Toggling an unknown document is refused; a known one is switched off, then back on. */
    @Test
    void aDocumentIsActivatedAndDeactivated() {
        AdminPrintableDocumentResource resource = newResource();
        PrintableDocument existing = mock(PrintableDocument.class);
        existing.active = true;
        try (MockedStatic<PrintableDocument> documents = mockStatic(PrintableDocument.class)) {
            documents.when(() -> PrintableDocument.findByCode("FACT_PRO")).thenReturn(existing);
            assertTrue(refused(resource.toggleDocument(form("code", "NOPE"))));
            assertFalse(refused(resource.toggleDocument(form("code", "FACT_PRO"))));
            assertFalse(existing.active);
            assertFalse(refused(resource.toggleDocument(form("code", "FACT_PRO"))));
            assertTrue(existing.active);
        }
    }

    /**
     * Deleting an unknown document is refused; a document the registers have
     * already printed is deactivated and kept; an unused one is deleted.
     */
    @Test
    void aDocumentIsDeletedUnlessAlreadyPrinted() {
        AdminPrintableDocumentResource resource = newResource();
        PrintableDocument used = mock(PrintableDocument.class);
        used.code = "USED";
        used.active = true;
        PrintableDocument unused = mock(PrintableDocument.class);
        unused.code = "UNUSED";
        try (MockedStatic<PrintableDocument> documents = mockStatic(PrintableDocument.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            documents.when(() -> PrintableDocument.findByCode("USED")).thenReturn(used);
            documents.when(() -> PrintableDocument.findByCode("UNUSED")).thenReturn(unused);
            panache.when(() -> PanacheEntityBase.count("documentCode", "USED")).thenReturn(2L);
            panache.when(() -> PanacheEntityBase.count("documentCode", "UNUSED")).thenReturn(0L);
            assertTrue(refused(resource.deleteDocument(form("code", "NOPE"))));
            assertTrue(refused(resource.deleteDocument(form("code", "USED"))));
            assertFalse(used.active);
            verify(used).persist();
            verify(used, never()).delete();
            assertFalse(refused(resource.deleteDocument(form("code", "UNUSED"))));
            verify(unused).delete();
        }
    }
}
