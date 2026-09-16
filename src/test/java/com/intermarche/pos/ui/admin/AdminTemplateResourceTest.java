package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.service.DocumentTemplateService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.inOrder;

/**
 * Unit tests for {@link AdminTemplateResource}.
 * <p>
 * A Qute-backed back-office page over the Panache statics of
 * {@link DocumentTemplate} (resolved to {@link PanacheEntityBase} outside a
 * Quarkus context, intercepted with {@link org.mockito.Mockito#mockStatic}) plus
 * a mocked {@link DocumentTemplateService}. The row inserted by the create arm
 * is neutralised with {@link org.mockito.Mockito#mockConstruction}. No database,
 * no Quarkus boot.
 * <p>
 * Branch enumeration (every arm exercised): {@code saveTemplate} covers the
 * blank-code arm, the unknown-type arm, the refused-source arm, both width
 * bounds at their exact values and one outside each, the insert arm, the update
 * arm, both arms of the active checkbox and both arms of the three number
 * fallbacks; {@code deleteTemplate} covers the unknown arm and the removed arm;
 * {@code templatesPage} covers the successful and failed notice arms; the
 * preview covers the rendered arm and the renders-nothing arm.
 */
class AdminTemplateResourceTest {

    /**
     * Builds a resource over a mocked template and renderer.
     *
     * @return the wired resource
     */
    private AdminTemplateResource newResource() {
        AdminTemplateResource resource = new AdminTemplateResource();
        resource.adminTemplates = mock(Template.class);
        resource.documentTemplateService = mock(DocumentTemplateService.class);
        return resource;
    }

    /**
     * Builds a resource over a REAL renderer.
     * <p>
     * The preview and the example are about what a layout renders, and a mocked
     * renderer answers null to everything: it would let a starter that renders
     * nothing pass. The engine here is the one the register runs.
     *
     * @return the wired resource
     */
    private AdminTemplateResource newRenderingResource() {
        AdminTemplateResource resource = new AdminTemplateResource();
        resource.adminTemplates = mock(Template.class);
        resource.documentTemplateService = new DocumentTemplateService(
                io.quarkus.qute.Engine.builder().addDefaults().build());
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminTemplateResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminTemplates.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds an empty posted form.
     *
     * @return a mutable empty form
     */
    private MultivaluedMap<String, String> form() {
        return new MultivaluedHashMap<>();
    }

    /**
     * Builds a posted form from alternating names and values.
     *
     * @param pairs the field names and values, in pairs
     * @return the posted form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> posted = new MultivaluedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            posted.putSingle(pairs[i], pairs[i + 1]);
        }
        return posted;
    }

    /**
     * Builds a sound posted description of a template.
     *
     * @param code the template code
     * @return the posted form
     */
    private MultivaluedMap<String, String> soundForm(String code) {
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", code);
        posted.putSingle("label", "Ticket de vente");
        posted.putSingle("documentType", "SALE_RECEIPT");
        posted.putSingle("source", "TOTAL {totals.includingTax}");
        posted.putSingle("width", "42");
        posted.putSingle("copies", "2");
        posted.putSingle("priority", "50");
        return posted;
    }

    /**
     * Builds an administered template.
     *
     * @return the administered row
     */
    private DocumentTemplate stored() {
        DocumentTemplate template = new DocumentTemplate();
        template.code = "TICKET_VENTE";
        template.label = "Ticket de vente";
        template.documentType = DocumentTemplate.DocumentType.SALE_RECEIPT;
        template.active = true;
        template.width = 42;
        template.source = "TOTAL {totals.includingTax}";
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
     * Reads the notice a redirect carries.
     *
     * @param response the redirect response
     * @return the decoded notice
     */
    private String notice(Response response) {
        String query = response.getLocation().getQuery();
        int at = query.indexOf("notice=");
        return java.net.URLDecoder.decode(query.substring(at + "notice=".length()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The page hands the template one preview per administered layout, each
     * rendered on the demonstration document of its own type.
     */
    @Test
    void thePageShowsOnePreviewPerTemplate() {
        AdminTemplateResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        DocumentTemplate template = stored();
        Map<String, Object> sample = Map.of("totals", Map.of("includingTax", "4,60"));
        when(resource.documentTemplateService.sampleData(
                DocumentTemplate.DocumentType.SALE_RECEIPT)).thenReturn(sample);
        when(resource.documentTemplateService.renderTemplate(template, sample))
                .thenReturn("TOTAL 4,60");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list("order by documentType, priority, code"))
                    .thenReturn(List.of(template));
            assertSame(instance, resource.templatesPage(null, null));
        }
        verify(instance).data("documentTypes", DocumentTemplate.DocumentType.values());
        verify(instance).data("noticeOk", true);
    }

    /**
     * A notice explicitly reported as a failure reaches the page as one, which
     * is the other leg of the {@code noticeOk} guard.
     */
    @Test
    void thePageCarriesAFailedNotice() {
        AdminTemplateResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list("order by documentType, priority, code"))
                    .thenReturn(List.of());
            resource.templatesPage("Refusé.", "false");
        }
        verify(instance).data("noticeOk", false);
    }

    /**
     * The preview carries what the template renders, and says plainly when it
     * renders nothing — both arms of what the page draws.
     */
    @Test
    void thePreviewSaysWhatTheTemplateRenders() {
        AdminTemplateResource resource = newResource();
        DocumentTemplate template = stored();
        // The DEMONSTRATION document of the template's OWN type, stubbed
        // exactly: a preview rendered against anything else would show the
        // paramétreur a receipt the printers will never produce.
        Map<String, Object> sample = Map.of("totals", Map.of("includingTax", "4,60"));
        when(resource.documentTemplateService.sampleData(
                DocumentTemplate.DocumentType.SALE_RECEIPT)).thenReturn(sample);
        when(resource.documentTemplateService.renderTemplate(template, sample))
                .thenReturn("TOTAL 4,60");
        AdminTemplateResource.Preview rendered = resource.preview(template, List.of());
        assertTrue(rendered.isRendered());
        assertEquals("TOTAL 4,60", rendered.getRendered());
        assertSame(template, rendered.getTemplate());

        when(resource.documentTemplateService.renderTemplate(template, sample))
                .thenReturn(null);
        AdminTemplateResource.Preview silent = resource.preview(template, List.of());
        assertFalse(silent.isRendered());
        assertNull(silent.getRendered());
    }

    /**
     * A template without a code is refused, and nothing is written.
     */
    @Test
    void aTemplateWithoutACodeIsRefused() {
        AdminTemplateResource resource = newResource();
        MultivaluedMap<String, String> posted = soundForm("   ");
        try (MockedConstruction<DocumentTemplate> created =
                mockConstruction(DocumentTemplate.class)) {
            Response response = resource.saveTemplate(posted);
            assertEquals(303, response.getStatus());
            assertEquals("Le code du gabarit est obligatoire.", notice(response));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * A template attached to a document the register does not print is refused.
     */
    @Test
    void anUnknownDocumentTypeIsRefused() {
        AdminTemplateResource resource = newResource();
        MultivaluedMap<String, String> posted = soundForm("TICKET_VENTE");
        posted.putSingle("documentType", "BON_DE_LIVRAISON");
        Response response = resource.saveTemplate(posted);
        assertEquals("Type de document inconnu.", notice(response));
    }

    /**
     * A source the engine refuses is refused HERE, with the engine's own
     * complaint: a broken layout saved is a till that prints nothing, and the
     * till is the worst place to discover a typo.
     */
    @Test
    void aSourceTheEngineRefusesIsRefused() {
        AdminTemplateResource resource = newResource();
        when(resource.documentTemplateService.parseError("TOTAL {totals.includingTax}"))
                .thenReturn("section for non fermée");
        Response response = resource.saveTemplate(soundForm("TICKET_VENTE"));
        assertEquals("Gabarit refusé : section for non fermée", notice(response));
    }

    /**
     * The width is accepted at both bounds and refused just outside each of
     * them — the two boundaries of the guard.
     */
    @Test
    void theWidthIsCheckedAtItsExactBounds() {
        AdminTemplateResource resource = newResource();
        PanacheQuery<DocumentTemplate> absent = queryOf(null);

        MultivaluedMap<String, String> tooNarrow = soundForm("T1");
        tooNarrow.putSingle("width", String.valueOf(DocumentTemplate.MIN_WIDTH - 1));
        assertTrue(notice(resource.saveTemplate(tooNarrow)).contains("largeur"));

        MultivaluedMap<String, String> tooWide = soundForm("T2");
        tooWide.putSingle("width", String.valueOf(DocumentTemplate.MAX_WIDTH + 1));
        assertTrue(notice(resource.saveTemplate(tooWide)).contains("largeur"));

        MultivaluedMap<String, String> narrow = soundForm("T3");
        narrow.putSingle("width", String.valueOf(DocumentTemplate.MIN_WIDTH));
        MultivaluedMap<String, String> wide = soundForm("T4");
        wide.putSingle("width", String.valueOf(DocumentTemplate.MAX_WIDTH));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<DocumentTemplate> created =
                        mockConstruction(DocumentTemplate.class)) {
            panache.when(() -> DocumentTemplate.find("code", "T3")).thenReturn(absent);
            panache.when(() -> DocumentTemplate.find("code", "T4")).thenReturn(absent);
            assertEquals("Gabarit créé.", notice(resource.saveTemplate(narrow)));
            assertEquals("Gabarit créé.", notice(resource.saveTemplate(wide)));
            assertEquals(DocumentTemplate.MIN_WIDTH, created.constructed().get(0).width);
            assertEquals(DocumentTemplate.MAX_WIDTH, created.constructed().get(1).width);
        }
    }

    /**
     * A code no row carries is created with everything the form stated, and the
     * renderer is told to forget what it had parsed.
     */
    @Test
    void anUnadministeredCodeIsCreated() {
        AdminTemplateResource resource = newResource();
        MultivaluedMap<String, String> posted = soundForm("TICKET_VENTE");
        posted.putSingle("active", "on");
        PanacheQuery<DocumentTemplate> absent = queryOf(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<DocumentTemplate> created =
                        mockConstruction(DocumentTemplate.class)) {
            panache.when(() -> DocumentTemplate.find("code", "TICKET_VENTE")).thenReturn(absent);
            assertEquals("Gabarit créé.", notice(resource.saveTemplate(posted)));
            DocumentTemplate inserted = created.constructed().get(0);
            assertEquals("TICKET_VENTE", inserted.code);
            assertEquals("Ticket de vente", inserted.label);
            assertEquals(DocumentTemplate.DocumentType.SALE_RECEIPT, inserted.documentType);
            assertEquals("TOTAL {totals.includingTax}", inserted.source);
            assertEquals(42, inserted.width);
            assertEquals(2, inserted.copies);
            assertEquals(50, inserted.priority);
            assertTrue(inserted.active);
            verify(inserted, times(1)).persist();
        }
        verify(resource.documentTemplateService, times(1)).clearCache();
    }

    /**
     * An administered code is updated in place, the unchecked box takes it out
     * of service, and the three number fallbacks answer for what the form did
     * not state.
     */
    @Test
    void anAdministeredCodeIsUpdatedInPlace() {
        AdminTemplateResource resource = newResource();
        DocumentTemplate existing = stored();
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", "TICKET_VENTE");
        posted.putSingle("label", "Ticket refait");
        posted.putSingle("documentType", "SALE_RECEIPT");
        posted.putSingle("source", "TICKET");
        PanacheQuery<DocumentTemplate> found = queryOf(existing);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<DocumentTemplate> created =
                        mockConstruction(DocumentTemplate.class)) {
            panache.when(() -> DocumentTemplate.find("code", "TICKET_VENTE")).thenReturn(found);
            assertEquals("Gabarit enregistré.", notice(resource.saveTemplate(posted)));
            assertTrue(created.constructed().isEmpty());
        }
        assertEquals("Ticket refait", existing.label);
        assertEquals("TICKET", existing.source);
        assertEquals(42, existing.width);
        assertEquals(1, existing.copies);
        assertEquals(100, existing.priority);
        assertFalse(existing.active);
    }

    /**
     * Deleting a code no row carries is refused, and nothing is touched.
     */
    @Test
    void deletingAnUnknownTemplateIsRefused() {
        AdminTemplateResource resource = newResource();
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", "INCONNU");
        PanacheQuery<DocumentTemplate> absent = queryOf(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.find("code", "INCONNU")).thenReturn(absent);
            assertEquals("Gabarit inconnu.", notice(resource.deleteTemplate(posted)));
        }
        verify(resource.documentTemplateService, never()).clearCache();
    }

    /**
     * Deleting an administered template removes its row and clears the parsed
     * form the renderer was holding.
     */
    @Test
    void deletingAnAdministeredTemplateRemovesIt() {
        AdminTemplateResource resource = newResource();
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", "TICKET_VENTE");
        DocumentTemplate template = mock(DocumentTemplate.class);
        template.code = "TICKET_VENTE";
        PanacheQuery<DocumentTemplate> found = queryOf(template);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.find("code", "TICKET_VENTE")).thenReturn(found);
            assertEquals("Gabarit supprimé.", notice(resource.deleteTemplate(posted)));
        }
        verify(template, times(1)).delete();
        verify(resource.documentTemplateService, times(1)).clearCache();
    }
    // --------------------------------------------------
    // preview / example — trying a layout without storing it
    // --------------------------------------------------

    /**
     * {@code previewTemplate} renders the POSTED source against the sample
     * sale and stores NOTHING — the whole point of the button: trying a layout
     * must not be a commitment, or the operator stops trying.
     */
    @Test
    @SuppressWarnings("unchecked")
    void previewRendersThePostedSourceAndStoresNothing() {
        AdminTemplateResource resource = newRenderingResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by code")).thenReturn(List.of());
            assertEquals(instance, resource.previewTemplate(form(
                    "card", "TICKET", "code", "TICKET", "documentType", "SALE_RECEIPT",
                    "source", "  {store.name} / {totals.includingTax}  ")));
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(instance).data(eq("draft"), captor.capture());
            AdminTemplateResource.Draft draft =
                    (AdminTemplateResource.Draft) captor.getValue();
            assertEquals("TICKET", draft.getCard());
            assertFalse(draft.isNew());
            assertTrue(draft.isFor("TICKET"));
            assertNull(draft.getError());
            assertEquals("INTERMARCHE VAUCRESSON / 4,60", draft.getRendered());
            
        }
    }

    /**
     * A source that does not parse yields the parser's complaint and no
     * rendering — the operator is told what is wrong instead of shown an empty
     * pane.
     */
    @Test
    @SuppressWarnings("unchecked")
    void previewReportsASourceThatDoesNotParse() {
        AdminTemplateResource resource = newRenderingResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by code")).thenReturn(List.of());
            resource.previewTemplate(form("card", "TICKET", "documentType", "SALE_RECEIPT",
                    "source", "{#for line in lines}{line.label}"));
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(instance).data(eq("draft"), captor.capture());
            AdminTemplateResource.Draft draft =
                    (AdminTemplateResource.Draft) captor.getValue();
            assertNotNull(draft.getError());
            assertNull(draft.getRendered());
        }
    }

    /**
     * {@code copySource} puts the PICKED layout into the editor, already
     * rendered — here a house example, whose kind the pick names.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theExampleIsTheStarterOfTheChosenKind() {
        AdminTemplateResource resource = newRenderingResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by code")).thenReturn(List.of());
            resource.copySource(form("card", "new", "documentType", "CARD_RECEIPT",
                    "from", "example:CARD_RECEIPT"));
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(instance).data(eq("draft"), captor.capture());
            AdminTemplateResource.Draft draft =
                    (AdminTemplateResource.Draft) captor.getValue();
            assertTrue(draft.isNew());
            assertEquals(DocumentTemplate.DocumentType.CARD_RECEIPT, draft.getDocumentType());
            assertEquals(resource.documentTemplateService.defaultSource(
                    DocumentTemplate.DocumentType.CARD_RECEIPT), draft.getSource());
            assertTrue(draft.getRendered().contains("JUSTIFICATIF CARTE"));
        }
    }

    /**
     * An unknown, absent or blank kind falls back on the sale receipt: the
     * button must answer something rather than refuse, and the receipt is the
     * document every register prints.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anUnknownKindFallsBackOnTheSaleReceipt() {
        AdminTemplateResource resource = newRenderingResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by code")).thenReturn(List.of());
            resource.copySource(form("card", "new", "documentType", "PAS_UN_TYPE"));
            resource.copySource(form("card", "new"));
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(instance, times(2)).data(eq("draft"), captor.capture());
            for (Object value : captor.getAllValues()) {
                AdminTemplateResource.Draft draft = (AdminTemplateResource.Draft) value;
                assertEquals(DocumentTemplate.DocumentType.SALE_RECEIPT,
                        draft.getDocumentType());
                assertTrue(draft.isNew());
            }
        }
    }

    /**
     * The plain page carries NO draft: nothing is being tried until a button
     * is pressed, and a stale draft would show the operator a layout they did
     * not ask about.
     */
    @Test
    void thePlainPageCarriesNoDraft() {
        AdminTemplateResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by code")).thenReturn(List.of());
            resource.templatesPage(null, null);
            verify(instance).data("draft", null);
        }
    }
    /**
     * The CREATION card is identified by the card it posts, never by the code
     * the operator is typing into it.
     * <p>
     * Keying the draft on the code was the defect: on the creation card the
     * operator types the code of the template they are about to make, so the
     * preview was addressed to a card that does not exist yet and the box
     * stayed empty. The example landed nowhere and looked like a feature that
     * did nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theCreationCardIsIdentifiedByTheCardNotByTheTypedCode() {
        AdminTemplateResource resource = newRenderingResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by code")).thenReturn(List.of());
            resource.copySource(form("card", "new", "code", "NOUVEAU_TICKET",
                    "documentType", "SALE_RECEIPT", "from", "example:SALE_RECEIPT"));
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(instance).data(eq("draft"), captor.capture());
            AdminTemplateResource.Draft draft =
                    (AdminTemplateResource.Draft) captor.getValue();
            assertTrue(draft.isNew(), "le brouillon doit viser la carte de création");
            assertFalse(draft.isFor("NOUVEAU_TICKET"));
            assertFalse(draft.getSource().isBlank());
        }
    }

    /**
     * Creating a template writes EVERY non-null column before the row reaches
     * the database.
     * <p>
     * The row used to be persisted as soon as its code was known and filled
     * afterwards, which held only as long as the flush came after the
     * assignments; {@code label} is NOT NULL, so a creation reached H2 with a
     * null label and the whole transaction rolled back.
     */
    @Test
    void creatingATemplateFillsEveryColumnBeforeWriting() {
        AdminTemplateResource resource = newResource();
        when(resource.documentTemplateService.parseError(any())).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<DocumentTemplate> created =
                        mockConstruction(DocumentTemplate.class)) {
            PanacheQuery<DocumentTemplate> absent = mock(PanacheQuery.class);
            when(absent.firstResult()).thenReturn(null);
            mocked.when(() -> DocumentTemplate.find("code", "NEUF")).thenReturn(absent);
            resource.saveTemplate(form("card", "new", "code", "NEUF", "label", "Ticket neuf",
                    "documentType", "SALE_RECEIPT", "source", "{store.name}",
                    "width", "42", "copies", "1", "priority", "100"));
            DocumentTemplate row = created.constructed().get(0);
            assertEquals("NEUF", row.code);
            assertEquals("Ticket neuf", row.label);
            assertEquals(DocumentTemplate.DocumentType.SALE_RECEIPT, row.documentType);
            // Persisted once, and only after the columns were set.
            InOrder order = inOrder(row);
            order.verify(row).persist();
            verify(row, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // The "Partir de" picker (BO-03-03)
    // --------------------------------------------------

    /**
     * The picker offers the house example of every document AND every layout
     * the shop administers, those of the card's OWN document first — a layout
     * written for another document reads keys this one does not carry, so the
     * ones that will work are the ones offered first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thePickerPutsTheLayoutsOfTheSameDocumentFirst() {
        AdminTemplateResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        DocumentTemplate receipt = stored();
        DocumentTemplate report = new DocumentTemplate();
        report.code = "RAPPORT_Z";
        report.label = "Rapport Z";
        report.documentType = DocumentTemplate.DocumentType.Z_REPORT;
        when(resource.documentTemplateService.sampleData(any())).thenReturn(Map.of());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by documentType, priority, code"))
                    .thenReturn(List.of(receipt, report));
            resource.templatesPage(null, null);
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(instance).data(eq("newPicks"), captor.capture());
            List<AdminTemplateResource.Pick> picks =
                    (List<AdminTemplateResource.Pick>) captor.getValue();
            // One example per document type, plus the two administered layouts.
            assertEquals(DocumentTemplate.DocumentType.values().length + 2, picks.size());
            boolean others = false;
            for (AdminTemplateResource.Pick pick : picks) {
                if (!pick.isSameType()) {
                    others = true;
                } else {
                    assertFalse(others, "un gabarit du même document après un autre");
                }
            }
            // The creation card defaults to the sale receipt: its example and
            // the receipt layout are the two same-type entries.
            assertEquals(2, picks.stream().filter(AdminTemplateResource.Pick::isSameType)
                    .count());
            assertTrue(picks.stream().anyMatch(p -> p.getKey().equals("template:TICKET_VENTE")));
            assertTrue(picks.stream().anyMatch(
                    p -> p.getKey().equals("example:SALE_RECEIPT")));
        }
    }

    /**
     * A layout with no label is offered by its code alone rather than with a
     * dangling dash — the blank arm of the entry label.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aLayoutWithoutALabelIsOfferedByItsCodeAlone() {
        AdminTemplateResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        DocumentTemplate bare = new DocumentTemplate();
        bare.code = "SANS_LIBELLE";
        bare.label = "   ";
        bare.documentType = null;
        when(resource.documentTemplateService.sampleData(any())).thenReturn(Map.of());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by documentType, priority, code")).thenReturn(List.of(bare));
            resource.templatesPage(null, null);
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(instance).data(eq("newPicks"), captor.capture());
            List<AdminTemplateResource.Pick> picks =
                    (List<AdminTemplateResource.Pick>) captor.getValue();
            AdminTemplateResource.Pick found = picks.stream()
                    .filter(p -> p.getKey().equals("template:SANS_LIBELLE"))
                    .findFirst().orElseThrow();
            assertEquals("SANS_LIBELLE", found.getLabel());
            assertEquals("", found.getType());
            assertFalse(found.isSameType());
        }
    }

    /**
     * Picking a layout the shop administers copies ITS source — and never its
     * code, which is the identity of the row: two layouts sharing one would
     * overwrite each other at the next save.
     */
    @Test
    @SuppressWarnings("unchecked")
    void pickingAnAdministeredLayoutCopiesItsSourceAndNotItsCode() {
        AdminTemplateResource resource = newRenderingResource();
        TemplateInstance instance = wireTemplate(resource);
        DocumentTemplate held = stored();
        held.source = "MON EN-TETE {store.name}";
        // Built BEFORE the static mock opens: a mock created inside an open
        // mockStatic leaves Mockito mid-stubbing.
        PanacheQuery<DocumentTemplate> heldQuery = queryOf(held);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by documentType, priority, code")).thenReturn(List.of());
            mocked.when(() -> DocumentTemplate.find("code", "TICKET_VENTE"))
                    .thenReturn(heldQuery);
            resource.copySource(form("card", "new", "documentType", "SALE_RECEIPT",
                    "from", "template:TICKET_VENTE"));
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(instance).data(eq("draft"), captor.capture());
            AdminTemplateResource.Draft draft =
                    (AdminTemplateResource.Draft) captor.getValue();
            assertEquals("MON EN-TETE {store.name}", draft.getSource());
            assertTrue(draft.isNew());
            assertTrue(draft.getRendered().contains("INTERMARCHE VAUCRESSON"));
        }
    }

    /**
     * A pick naming a layout that is gone, or one carrying no source at all,
     * falls back on the example of the card's document rather than emptying
     * the box — the two refusal arms of the lookup.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aPickThatResolvesToNothingFallsBackOnTheExample() {
        AdminTemplateResource resource = newRenderingResource();
        TemplateInstance instance = wireTemplate(resource);
        DocumentTemplate sourceless = stored();
        sourceless.source = null;
        PanacheQuery<DocumentTemplate> goneQuery = queryOf(null);
        PanacheQuery<DocumentTemplate> sourcelessQuery = queryOf(sourceless);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by documentType, priority, code")).thenReturn(List.of());
            mocked.when(() -> DocumentTemplate.find("code", "DISPARU"))
                    .thenReturn(goneQuery);
            mocked.when(() -> DocumentTemplate.find("code", "TICKET_VENTE"))
                    .thenReturn(sourcelessQuery);
            resource.copySource(form("card", "new", "documentType", "CARD_RECEIPT",
                    "from", "template:DISPARU"));
            resource.copySource(form("card", "new", "documentType", "CARD_RECEIPT",
                    "from", "template:TICKET_VENTE"));
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(instance, times(2)).data(eq("draft"), captor.capture());
            String expected = resource.documentTemplateService.defaultSource(
                    DocumentTemplate.DocumentType.CARD_RECEIPT);
            for (Object value : captor.getAllValues()) {
                assertEquals(expected, ((AdminTemplateResource.Draft) value).getSource());
            }
        }
    }

    /**
     * The page carries the references of the CREATION card's own document and
     * of no other: the collapsed list under that card is the fallback for a
     * browser where the context menu does not run, and a fallback naming ten
     * other documents would be the very catalogue the menu replaced.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thePageCarriesTheReferencesOfTheCreationCardAlone() {
        AdminTemplateResource resource = newRenderingResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by documentType, priority, code")).thenReturn(List.of());
            resource.templatesPage(null, null);
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(instance).data(eq("newReferences"), captor.capture());
            List<DocumentTemplateService.Reference> references =
                    (List<DocumentTemplateService.Reference>) captor.getValue();
            assertEquals(resource.documentTemplateService.references(
                    DocumentTemplate.DocumentType.SALE_RECEIPT).size(), references.size());
            assertFalse(references.isEmpty());
        }
    }

    /**
     * The page is never handed a catalogue of every document type again: the
     * removal of that key is the point of the rework, and a template still
     * reading it would render an empty block instead of failing loudly.
     */
    @Test
    void thePageNoLongerCarriesACatalogueOfEveryType() {
        AdminTemplateResource resource = newRenderingResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by documentType, priority, code")).thenReturn(List.of());
            resource.templatesPage(null, null);
            verify(instance, never()).data(eq("catalogs"), any());
        }
    }

    /**
     * The creation card being tried on another document carries THAT
     * document's references, which is what lets the menu follow a type change
     * after the page has been reloaded by a preview.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theReferencesFollowTheDocumentTheCreationCardIsTryingOut() {
        AdminTemplateResource resource = newRenderingResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> DocumentTemplate.list("order by documentType, priority, code")).thenReturn(List.of());
            resource.previewTemplate(
                    form("card", "new", "documentType", "Z_REPORT", "source", "Z"));
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(instance).data(eq("newReferences"), captor.capture());
            List<DocumentTemplateService.Reference> references =
                    (List<DocumentTemplateService.Reference>) captor.getValue();
            assertEquals(resource.documentTemplateService.references(
                    DocumentTemplate.DocumentType.Z_REPORT).size(), references.size());
        }
    }

    /**
     * The references API serves the type asked for and nothing else: the menu
     * opens on ONE card, and an entry belonging to another document would
     * render empty wherever the paramétreur inserted it.
     */
    @Test
    void theReferencesApiServesTheTypeAskedForAndNoOther() {
        AdminTemplateResource resource = newRenderingResource();
        Response response = resource.apiReferences("Z_REPORT");
        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertEquals(resource.documentTemplateService.references(
                        DocumentTemplate.DocumentType.Z_REPORT).size(),
                body.split("\\{\"expression\"", -1).length - 1);
        assertTrue(body.contains("{session.closedAt}"));
        assertFalse(body.contains("{customer.siret}"));
    }

    /**
     * An entry read inside a loop says so, and one read at the top level says
     * {@code null}: both legs of the same ternary, and the menu shows the
     * warning on exactly the first kind.
     */
    @Test
    void theReferencesApiNamesTheLoopOfARepeatingEntryAndNullOtherwise() {
        AdminTemplateResource resource = newRenderingResource();
        String body = (String) resource.apiReferences("SALE_RECEIPT").getEntity();
        assertTrue(body.contains("{\"expression\":\"{line.label}\",\"sample\":\"LAIT DEMI-ECREME 1L\","
                + "\"loop\":\"{#for line in lines}…{/for}\"}"));
        assertTrue(body.contains("{\"expression\":\"{operator}\",\"sample\":\"MARIE\",\"loop\":null}"));
    }

    /**
     * An unknown type, and no type at all, both fall back on the sale receipt
     * rather than answering an error: the menu asks with whatever the card
     * carries, and an empty menu would look like a broken screen.
     */
    @Test
    void theReferencesApiFallsBackOnTheSaleReceipt() {
        AdminTemplateResource resource = newRenderingResource();
        String expected = (String) resource.apiReferences("SALE_RECEIPT").getEntity();
        assertEquals(expected, resource.apiReferences("N_IMPORTE_QUOI").getEntity());
        assertEquals(expected, resource.apiReferences(null).getEntity());
    }

    /**
     * Every document type answers the API with a non-empty, well-formed body:
     * eleven types, eleven menus, none of them silent.
     */
    @Test
    void everyDocumentTypeAnswersTheReferencesApi() {
        AdminTemplateResource resource = newRenderingResource();
        for (DocumentTemplate.DocumentType type : DocumentTemplate.DocumentType.values()) {
            String body = (String) resource.apiReferences(type.name()).getEntity();
            assertTrue(body.startsWith("[{"), type + " ne propose aucune donnée");
            assertTrue(body.endsWith("}]"), type + " répond un corps tronqué");
            assertEquals(resource.documentTemplateService.references(type).size(),
                    body.split("\\{\"expression\"", -1).length - 1,
                    type + " n'annonce pas le bon nombre de données");
        }
    }
}
