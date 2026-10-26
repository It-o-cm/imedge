package com.intermarche.edge.service;

import com.intermarche.edge.domain.setting.DocumentTemplate;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentTemplateService}.
 * <p>
 * The engine is a REAL Qute engine built with its defaults, not a stand-in:
 * what these cases are about is that an administered template renders the maps,
 * lists and strings the register hands it, and a mocked engine would have
 * asserted nothing of the sort. The referential is reached through the Panache
 * statics of {@link DocumentTemplate}, resolved to {@link PanacheEntityBase}
 * outside a Quarkus context and intercepted with
 * {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every leg exercised): {@code render} covers the
 * unadministered-type arm and the rendered arm; {@code renderTemplate} covers
 * the null-template leg, the deactivated leg, the null-source leg, the
 * blank-source leg, the unparsable arm, the failing-render arm and the rendered
 * arm; {@code parse} covers the cold-cache arm, the warm-cache arm, the
 * source-changed arm and the unparsable arm; {@code parseError} covers the null
 * leg, the blank leg, the sound arm and the broken arm; {@code findFor} covers
 * the null-type arm, the empty-list arm, the null-entry arm, the
 * unrenderable-entry arm and the found arm.
 */
class DocumentTemplateServiceTest {

    /**
     * Builds the service over a real Qute engine carrying its default section
     * helpers and value resolvers — the engine the register itself runs.
     *
     * @return the wired service
     */
    private DocumentTemplateService newService() {
        DocumentTemplateService service = new DocumentTemplateService();
        service.engine = Engine.builder().addDefaults().build();
        return service;
    }

    /**
     * Builds the service over an engine that also resolves plain objects by
     * reflection.
     *
     * <p>The register's own engine reaches {@link DocumentLayout} through the
     * resolver its {@code @TemplateData} generates at build time, which a
     * hand-assembled engine has no way to carry; reflection resolves the very
     * same methods and is the stand-in a unit test can run. What this proves
     * is the RENDERER's wiring — that the primitives are handed to the
     * template, bound to its width — not the build-time generation, which only
     * a running application can show.
     *
     * @return the wired service
     */
    private DocumentTemplateService newReflectiveService() {
        DocumentTemplateService service = new DocumentTemplateService();
        service.engine = Engine.builder().addDefaults()
                .addValueResolver(new io.quarkus.qute.ReflectionValueResolver()).build();
        return service;
    }

    /**
     * Builds an administered template of the sale receipt.
     *
     * @param source the Qute source
     * @return the administered template
     */
    private DocumentTemplate template(String source) {
        DocumentTemplate template = new DocumentTemplate();
        template.code = "TICKET_VENTE";
        template.label = "Ticket de vente";
        template.documentType = DocumentTemplate.DocumentType.SALE_RECEIPT;
        template.active = true;
        template.width = 42;
        template.source = source;
        return template;
    }

    /**
     * The sale a template lays out, already formatted — maps, lists and strings
     * and nothing else.
     *
     * @return the document values
     */
    private Map<String, Object> saleData() {
        return Map.of(
                "store", Map.of("name", "INTERMARCHE VAUCRESSON"),
                "ticket", Map.of("number", "C04-000123", "total", "24,90"),
                "lines", List.of(
                        Map.of("label", "LAIT DEMI-ECREME", "total", "1,15"),
                        Map.of("label", "PAIN COMPLET", "total", "2,30")));
    }

    /**
     * An administered template lays out the values the register hands it — the
     * whole point of the design, asserted against a real engine.
     */
    @Test
    void anAdministeredTemplateLaysOutTheDocument() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template(
                "{store.name}\n{#for line in lines}{line.label} {line.total}\n{/for}"
                        + "TOTAL {ticket.total}");
        String rendered = service.renderTemplate(template, saleData());
        assertEquals("INTERMARCHE VAUCRESSON\n"
                + "LAIT DEMI-ECREME 1,15\n"
                + "PAIN COMPLET 2,30\n"
                + "TOTAL 24,90", rendered);
    }

    /**
     * A SECOND administered layout states the very same sale differently, which
     * is what proves the layout is read rather than compiled in.
     */
    @Test
    void aSecondAdministeredLayoutStatesTheSameSaleDifferently() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template("TOTAL {ticket.total} - {ticket.number}");
        assertEquals("TOTAL 24,90 - C04-000123",
                service.renderTemplate(template, saleData()));
    }

    /**
     * {@code render} goes through the referential: an administered type is laid
     * out by its template.
     */
    @Test
    void renderFindsTheAdministeredTemplate() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template("TOTAL {ticket.total}");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.SALE_RECEIPT))
                    .thenReturn(List.of(template));
            assertEquals("TOTAL 24,90",
                    service.render(DocumentTemplate.DocumentType.SALE_RECEIPT, saleData()));
        }
    }

    /**
     * A document type NO row administers renders nothing, which is how the
     * caller knows to print it its own way.
     */
    @Test
    void anUnadministeredTypeRendersNothing() {
        DocumentTemplateService service = newService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.Z_REPORT))
                    .thenReturn(List.of());
            assertNull(service.render(DocumentTemplate.DocumentType.Z_REPORT, saleData()));
        }
    }

    /**
     * A null document type is answered without touching the referential, which
     * is what the absence of any stubbing here asserts.
     */
    @Test
    void aNullTypeRendersNothing() {
        assertNull(newService().render(null, saleData()));
    }

    /**
     * {@code findFor} skips a null row and a row that cannot render, and answers
     * the first that can.
     */
    @Test
    void findForSkipsWhatCannotRender() {
        DocumentTemplate deactivated = template("TOTAL {ticket.total}");
        deactivated.active = false;
        DocumentTemplate empty = template("   ");
        DocumentTemplate wanted = template("TOTAL {ticket.total}");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.SALE_RECEIPT))
                    .thenReturn(java.util.Arrays.asList(null, deactivated, empty, wanted));
            assertEquals(wanted, DocumentTemplate.findFor(DocumentTemplate.DocumentType.SALE_RECEIPT));
        }
    }

    /**
     * A null template, a deactivated one and one whose source was never written
     * all render nothing — the legs of the renderable guard.
     */
    @Test
    void anUnwrittenTemplateRendersNothing() {
        DocumentTemplateService service = newService();
        assertNull(service.renderTemplate(null, saleData()));

        DocumentTemplate deactivated = template("TOTAL {ticket.total}");
        deactivated.active = false;
        assertNull(service.renderTemplate(deactivated, saleData()));

        assertNull(service.renderTemplate(template(null), saleData()));
        assertNull(service.renderTemplate(template("   "), saleData()));
    }

    /**
     * A source the engine cannot parse renders nothing rather than throwing: a
     * paramétreur's typo costs the built-in layout, never a sale that cannot be
     * printed.
     */
    @Test
    void anUnparsableSourceRendersNothing() {
        DocumentTemplateService service = newService();
        assertNull(service.renderTemplate(template("{#for line in lines}oubli du for"),
                saleData()));
    }

    /**
     * A template that PARSES but fails while rendering is caught the same way —
     * the other arm of the same promise.
     */
    @Test
    void aFailingRenderFallsBackToNothing() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template("{#for line in lines}{line.label}{/for}");
        // The values contradict the layout: "lines" is not iterable, so the
        // engine parses happily and blows up at render time.
        assertNull(service.renderTemplate(template, Map.of("lines", "pas une liste")));
    }

    /**
     * Rendering with no values at all is legitimate — a template that reads
     * nothing lays out its constant text.
     */
    @Test
    void aTemplateReadingNothingStillLaysOutItsText() {
        DocumentTemplateService service = newService();
        assertEquals("TICKET", service.renderTemplate(template("TICKET"), null));
    }

    /**
     * The parsed form is REUSED while the source is unchanged, and thrown away
     * the moment the back office rewrites it — the two arms of the cache guard.
     */
    @Test
    void theParsedFormIsReusedUntilTheSourceChanges() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template("A {ticket.total}");
        assertEquals("A 24,90", service.renderTemplate(template, saleData()));
        assertEquals("A 24,90", service.renderTemplate(template, saleData()));

        template.source = "B {ticket.total}";
        assertEquals("B 24,90", service.renderTemplate(template, saleData()));
    }

    /**
     * A source is parsed ONCE however often the shop prints it, parsed again
     * when the back office rewrites it, and parsed again after the cache is
     * cleared — the three states of the cache, counted on the engine itself
     * because a parse that happens twice is invisible in the output.
     */
    @Test
    void aSourceIsParsedOncePerVersion() {
        DocumentTemplateService service = new DocumentTemplateService();
        Engine engine = org.mockito.Mockito.spy(Engine.builder().addDefaults().build());
        service.engine = engine;
        DocumentTemplate template = template("A {ticket.total}");

        assertEquals("A 24,90", service.renderTemplate(template, saleData()));
        assertEquals("A 24,90", service.renderTemplate(template, saleData()));
        assertEquals("A 24,90", service.renderTemplate(template, saleData()));
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(1)).parse("A {ticket.total}");

        template.source = "B {ticket.total}";
        assertEquals("B 24,90", service.renderTemplate(template, saleData()));
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(1)).parse("B {ticket.total}");

        service.clearCache();
        assertEquals("B 24,90", service.renderTemplate(template, saleData()));
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(2)).parse("B {ticket.total}");
    }

    /**
     * An unparsable source leaves NOTHING in the cache, so correcting it takes
     * effect at once.
     */
    @Test
    void anUnparsableSourceLeavesNothingBehind() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template("A {ticket.total}");
        assertEquals("A 24,90", service.renderTemplate(template, saleData()));

        template.source = "{#for x in lines}cassé";
        assertNull(service.renderTemplate(template, saleData()));

        template.source = "C {ticket.total}";
        assertEquals("C 24,90", service.renderTemplate(template, saleData()));
    }

    /**
     * {@code parseError} says nothing about a sound source and about an empty
     * one — null and blank alike — and complains about a broken one.
     */
    @Test
    void parseErrorComplainsOnlyAboutABrokenSource() {
        DocumentTemplateService service = newService();
        assertNull(service.parseError(null));
        assertNull(service.parseError("   "));
        assertNull(service.parseError("TOTAL {ticket.total}"));
        assertNotNull(service.parseError("{#for line in lines}oubli du for"));
    }

    /**
     * The demonstration document carries what EVERY document carries — the
     * store, the register, the operator and the moment — whatever its type, and
     * even when no type is named.
     */
    @Test
    void everyDemonstrationDocumentCarriesTheCommonValues() {
        DocumentTemplateService service = newService();
        for (DocumentTemplate.DocumentType type : DocumentTemplate.DocumentType.values()) {
            Map<String, Object> data = service.sampleData(type);
            assertNotNull(data.get("store"), type.name());
            assertNotNull(data.get("terminal"), type.name());
            assertNotNull(data.get("operator"), type.name());
            assertNotNull(data.get("date"), type.name());
            assertNotNull(data.get("time"), type.name());
        }
        Map<String, Object> untyped = service.sampleData(null);
        assertNotNull(untyped.get("store"));
        assertNull(untyped.get("lines"));
    }

    /**
     * Each family of document carries what its own templates read — the four
     * arms of the switch, each asserted on the key that only it provides.
     */
    @Test
    void eachFamilyOfDocumentCarriesItsOwnValues() {
        DocumentTemplateService service = newService();
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.SALE_RECEIPT).get("lines"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.REFUND_RECEIPT).get("vatRows"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.INVOICE).get("payments"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.X_REPORT).get("tenders"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.Z_REPORT).get("session"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.WITHDRAWAL_TICKET).get("movement"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.TRANSFER_TICKET).get("movement"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.GIFT_CARD_VOUCHER).get("instrument"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.CREDIT_NOTE_VOUCHER).get("instrument"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.CARD_RECEIPT).get("card"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.LOYALTY_RECEIPT).get("settlement"));
    }

    /**
     * BO-03-03-25/-29/-31/-32: the LOYALTY ZONE the receipt and the loyalty slip
     * both advertise — the card, its presence, the earn of the day and its
     * advantage lines, the balance and the amount settled on it. A paramétreur
     * who learns the zone on one document knows it on the other, so the two are
     * asserted to be the SAME map.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theloyaltyZoneIsAdvertisedOnBothDocumentsAlike() {
        DocumentTemplateService service = newService();
        Map<String, Object> onReceipt = (Map<String, Object>) service.sampleData(
                DocumentTemplate.DocumentType.SALE_RECEIPT).get("fidelity");
        Map<String, Object> onSlip = (Map<String, Object>) service.sampleData(
                DocumentTemplate.DocumentType.LOYALTY_RECEIPT).get("fidelity");
        assertEquals(onReceipt, onSlip);
        assertEquals("6045200000123", onReceipt.get("card"));
        assertEquals(Boolean.TRUE, onReceipt.get("present"));
        assertEquals("1,03", onReceipt.get("earnTotal"));
        assertEquals("42,30", onReceipt.get("availableBalance"));
        assertEquals("3,00", onReceipt.get("usedAmount"));
        assertEquals(Boolean.FALSE, onReceipt.get("unavailable"));
        assertEquals("", onReceipt.get("message"));
        assertEquals(2, ((java.util.List<?>) onReceipt.get("lines")).size());
    }

    /**
     * A document type carries ONLY its own values: a report has no sale lines
     * and a sale has no session — which is what makes the demonstration a
     * contract rather than a grab bag.
     */
    @Test
    void aDocumentTypeCarriesOnlyItsOwnValues() {
        DocumentTemplateService service = newService();
        assertNull(service.sampleData(DocumentTemplate.DocumentType.Z_REPORT).get("lines"));
        assertNull(service.sampleData(DocumentTemplate.DocumentType.SALE_RECEIPT).get("session"));
        assertNull(service.sampleData(
                DocumentTemplate.DocumentType.CARD_RECEIPT).get("instrument"));
    }

    /**
     * The demonstration document RENDERS: a layout written against the keys the
     * editor advertises lays out without a fault, which is what makes the
     * preview pane trustworthy.
     */
    @Test
    void theDemonstrationDocumentRenders() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template(
                "{store.name}\n{#for l in lines}{l.label} {l.total}\n{/for}"
                        + "TOTAL {totals.includingTax}");
        String rendered = service.renderTemplate(template,
                service.sampleData(DocumentTemplate.DocumentType.SALE_RECEIPT));
        assertNotNull(rendered);
        assertTrue(rendered.contains("INTERMARCHE VAUCRESSON"));
        assertTrue(rendered.contains("TOTAL 4,60"));
    }

    /**
     * The engine is asked to parse only what the back office typed: a mocked
     * engine that answers nothing leaves the render on the fallback path.
     */
    @Test
    void anEngineAnsweringNothingLeavesTheFallbackPath() {
        DocumentTemplateService service = new DocumentTemplateService();
        Engine engine = mock(Engine.class);
        when(engine.parse("TOTAL {ticket.total}")).thenThrow(new IllegalStateException("boum"));
        service.engine = engine;
        assertNull(service.renderTemplate(template("TOTAL {ticket.total}"), saleData()));
        assertTrue(service.parseError("TOTAL {ticket.total}").contains("boum"));
    }
    // --------------------------------------------------
    // defaultSource — the starter layout offered in the editor
    // --------------------------------------------------

    /**
     * EVERY kind of document offers a starter, every starter PARSES, and every
     * starter RENDERS against the sample data of its own kind.
     * <p>
     * This is the test that matters, and it is written as one loop over the
     * whole enum on purpose: a starter is only worth offering if it works, and
     * the way it stops working is that someone adds a document type — or
     * renames a key in {@code sampleData} — and the example nobody re-reads
     * silently starts rendering an empty document. The rendering is asserted
     * non-blank for the same reason: a layout referring only to absent keys
     * parses cleanly and renders to nothing.
     */
    @Test
    void everyKindOffersAStarterThatParsesAndRenders() {
        // The starters typeset with fmt, which the running application resolves
        // through the generated @TemplateData resolver: the engine here must do
        // as much, or the test would condemn layouts that print perfectly.
        DocumentTemplateService service = newReflectiveService();
        for (DocumentTemplate.DocumentType type : DocumentTemplate.DocumentType.values()) {
            String source = service.defaultSource(type);
            assertNotNull(source, type + " offers no starter");
            assertFalse(source.isBlank(), type + " offers a blank starter");
            assertNull(service.parseError(source), type + " offers a starter that does not parse");
            DocumentTemplate template = new DocumentTemplate();
            template.code = "STARTER";
            template.documentType = type;
            template.active = true;
            template.width = DocumentTemplate.MIN_WIDTH;
            template.source = source;
            String rendered = service.renderTemplate(template, service.sampleData(type));
            assertNotNull(rendered, type + " offers a starter that renders nothing");
            assertFalse(rendered.isBlank(), type + " offers a starter that renders blank");
        }
    }

    /**
     * A starter names the data of ITS OWN kind: the sale receipt shows the
     * articles and the total, the movement ticket shows the movement, the
     * voucher shows the instrument. A starter rendering the store name alone
     * would pass the loop above and teach the operator nothing.
     */
    @Test
    void aStarterNamesTheDataOfItsOwnKind() {
        DocumentTemplateService service = newService();
        assertTrue(rendered(service, DocumentTemplate.DocumentType.SALE_RECEIPT)
                .contains("LAIT DEMI-ECREME 1L"));
        assertTrue(rendered(service, DocumentTemplate.DocumentType.SALE_RECEIPT)
                .contains("4,60"));
        assertTrue(rendered(service, DocumentTemplate.DocumentType.REFUND_RECEIPT)
                .contains("C04-R000012"));
        assertTrue(rendered(service, DocumentTemplate.DocumentType.INVOICE)
                .contains("SARL DUPONT"));
        assertTrue(rendered(service, DocumentTemplate.DocumentType.INVOICE_A4)
                .contains("FR00123456789"));
        assertTrue(rendered(service, DocumentTemplate.DocumentType.Z_REPORT)
                .contains("C04-S00012"));
        assertTrue(rendered(service, DocumentTemplate.DocumentType.WITHDRAWAL_TICKET)
                .contains("Billets 50"));
        assertTrue(rendered(service, DocumentTemplate.DocumentType.GIFT_CARD_VOUCHER)
                .contains("296000000000042"));
        assertTrue(rendered(service, DocumentTemplate.DocumentType.CARD_RECEIPT)
                .contains("CREDIT"));
    }

    /**
     * The A4 starter keeps its stylesheet: the CSS braces sit next to Qute's
     * own delimiter, and a starter whose style block was eaten would render a
     * page nobody wants to print.
     */
    @Test
    void theA4StarterKeepsItsStylesheet() {
        String page = rendered(newService(), DocumentTemplate.DocumentType.INVOICE_A4);
        assertTrue(page.contains("border-collapse: collapse"));
        assertTrue(page.contains("</style>"));
    }

    /**
     * No kind means no starter — the null arm, which the editor hits before a
     * type is chosen.
     */
    @Test
    void noKindOffersNoStarter() {
        assertEquals("", newService().defaultSource(null));
    }

    /**
     * Renders the starter of one kind against the sample data of that kind.
     *
     * @param service the service under test
     * @param type the kind of document
     * @return what the starter renders
     */
    private String rendered(DocumentTemplateService service,
                            DocumentTemplate.DocumentType type) {
        DocumentTemplate template = new DocumentTemplate();
        template.code = "STARTER";
        template.documentType = type;
        template.active = true;
        template.width = DocumentTemplate.MIN_WIDTH;
        template.source = service.defaultSource(type);
        return service.renderTemplate(template, service.sampleData(type));
    }

    // --------------------------------------------------
    // references (BO-03-03)
    // --------------------------------------------------

    /**
     * The catalogue is DERIVED from the demonstration document: every
     * expression it offers resolves against {@code sampleData} of the same
     * type, and none is missing. This is the property the screen rests on —
     * a paramétreur told about a key the renderer does not serve would write
     * a layout that renders empty.
     */
    @Test
    void everyReferenceOfferedResolvesOnTheDemonstrationDocument() {
        DocumentTemplateService service = newService();
        for (DocumentTemplate.DocumentType type : DocumentTemplate.DocumentType.values()) {
            Map<String, Object> sample = service.sampleData(type);
            java.util.Set<String> offered = new java.util.HashSet<>();
            for (DocumentTemplateService.Reference reference : service.references(type)) {
                String expression = reference.getExpression();
                if (expression.startsWith("{#for ")) {
                    String list = expression.substring(expression.indexOf(" in ") + 4,
                            expression.indexOf("}"));
                    assertTrue(sample.get(list) instanceof List,
                            type + " : " + list + " n'est pas une liste du document");
                    offered.add(list);
                    continue;
                }
                String path = expression.substring(1, expression.length() - 1);
                offered.add(path.contains(".") ? path.substring(0, path.indexOf('.')) : path);
            }
            for (String key : sample.keySet()) {
                assertTrue(offered.contains(key),
                        type + " : la clé " + key + " n'est offerte nulle part");
            }
        }
    }

    /**
     * A block is offered field by field, each with what it yields — the map
     * arm of the walk.
     */
    @Test
    void aBlockIsOfferedFieldByFieldWithItsValue() {
        DocumentTemplateService service = newService();
        List<DocumentTemplateService.Reference> references =
                service.references(DocumentTemplate.DocumentType.SALE_RECEIPT);
        DocumentTemplateService.Reference name = find(references, "{store.name}");
        assertEquals("INTERMARCHE VAUCRESSON", name.getSample());
        assertNull(name.getLoop());
        assertFalse(name.isRepeating());
    }

    /**
     * A scalar is offered on its own — the neither-map-nor-list arm.
     */
    @Test
    void aScalarIsOfferedOnItsOwn() {
        DocumentTemplateService service = newService();
        DocumentTemplateService.Reference terminal = find(
                service.references(DocumentTemplate.DocumentType.SALE_RECEIPT), "{terminal}");
        assertEquals("C04", terminal.getSample());
        assertFalse(terminal.isRepeating());
    }

    /**
     * A repeating block is offered as its loop plus one expression per field
     * of its rows, each marked as readable only INSIDE that loop — the list
     * arm, and the list-of-maps arm under it.
     */
    @Test
    void aRepeatingBlockIsOfferedAsItsLoopAndItsFields() {
        DocumentTemplateService service = newService();
        List<DocumentTemplateService.Reference> references =
                service.references(DocumentTemplate.DocumentType.SALE_RECEIPT);
        DocumentTemplateService.Reference loop = find(references, "{#for line in lines}…{/for}");
        assertEquals("2 ligne(s)", loop.getSample());
        assertFalse(loop.isRepeating());
        DocumentTemplateService.Reference label = find(references, "{line.label}");
        assertEquals("LAIT DEMI-ECREME 1L", label.getSample());
        assertTrue(label.isRepeating());
        assertEquals("{#for line in lines}…{/for}", label.getLoop());
    }

    /**
     * A list of plain values is offered as its loop and the value itself, not
     * as fields — the not-a-map arm; the invoice's address lines are one.
     */
    @Test
    void aListOfPlainValuesIsOfferedAsTheValueItself() {
        DocumentTemplateService service = newService();
        List<DocumentTemplateService.Reference> references =
                service.references(DocumentTemplate.DocumentType.INVOICE);
        DocumentTemplateService.Reference lines = find(references, "{seller.addressLines}");
        assertEquals("15 RUE DE LA GARE / 92420 VAUCRESSON", lines.getSample());
    }

    /**
     * An EMPTY repeating block is offered as its loop alone: there is no row
     * to read the fields off, and inventing them would describe a shape the
     * document does not carry. A transfer ticket counts nothing.
     */
    @Test
    void anEmptyRepeatingBlockIsOfferedAsItsLoopAlone() {
        DocumentTemplateService service = newService();
        List<DocumentTemplateService.Reference> references =
                service.references(DocumentTemplate.DocumentType.TRANSFER_TICKET);
        DocumentTemplateService.Reference loop =
                find(references, "{#for count in counts}…{/for}");
        assertEquals("0 ligne(s)", loop.getSample());
        for (DocumentTemplateService.Reference reference : references) {
            assertFalse(reference.getExpression().startsWith("{count."),
                    "aucun champ ne doit être inventé sur une liste vide");
        }
    }

    /**
     * Without a type the catalogue names the values EVERY document carries and
     * nothing else — the null arm, which the page uses before a type is
     * chosen.
     */
    @Test
    void withoutATypeOnlyTheCommonValuesAreOffered() {
        DocumentTemplateService service = newService();
        List<DocumentTemplateService.Reference> references = service.references(null);
        assertEquals(15, references.size());
        assertNotNull(find(references, "{store.siret}"));
        // BO-03-10-19/20: the whole store record is offered to a header.
        assertNotNull(find(references, "{store.phone}"));
        assertNotNull(find(references, "{store.vatNumber}"));
        assertNotNull(find(references, "{store.bankAccount}"));
        assertNotNull(find(references, "{date}"));
        for (DocumentTemplateService.Reference reference : references) {
            assertFalse(reference.isRepeating());
        }
    }

    /**
     * The order is the order a document is written in: the standalone values,
     * then the blocks, then the repeating rows.
     */
    @Test
    void theOrderGoesFromStandaloneValuesDownToRepeatingRows() {
        DocumentTemplateService service = newService();
        List<DocumentTemplateService.Reference> references =
                service.references(DocumentTemplate.DocumentType.SALE_RECEIPT);
        int lastScalar = -1;
        int firstBlock = Integer.MAX_VALUE;
        int firstLoop = Integer.MAX_VALUE;
        for (int i = 0; i < references.size(); i++) {
            String expression = references.get(i).getExpression();
            if (expression.startsWith("{#for ")) {
                firstLoop = Math.min(firstLoop, i);
            } else if (expression.contains(".")) {
                firstBlock = Math.min(firstBlock, i);
            } else {
                lastScalar = i;
            }
        }
        assertTrue(lastScalar < firstBlock, "les valeurs seules viennent en premier");
        assertTrue(firstBlock < firstLoop, "les blocs viennent avant les lignes répétées");
    }

    /**
     * Finds one offered expression.
     *
     * @param references the catalogue
     * @param expression the expression looked for
     * @return the reference, never null
     */
    private DocumentTemplateService.Reference find(
            List<DocumentTemplateService.Reference> references, String expression) {
        for (DocumentTemplateService.Reference reference : references) {
            if (reference.getExpression().equals(expression)) {
                return reference;
            }
        }
        throw new AssertionError("référence absente du catalogue : " + expression);
    }

    /**
     * {@code parseError} falls back to the exception's type when the parser
     * raises without a message — the null-message arm of its ternary, which a
     * real Qute failure always carries a message for.
     */
    @Test
    void parseErrorFallsBackToTheExceptionTypeWhenMessageIsNull() {
        DocumentTemplateService service = new DocumentTemplateService();
        Engine engine = mock(Engine.class);
        when(engine.parse("x")).thenThrow(new RuntimeException((String) null));
        service.engine = engine;
        assertEquals("java.lang.RuntimeException", service.parseError("x"));
    }

    /**
     * {@code text} writes a null value as the empty string — the null arm of its
     * guard, which no demonstration value reaches, exercised directly.
     */
    @Test
    void textWritesNullAsTheEmptyString() throws Exception {
        DocumentTemplateService service = newService();
        assertEquals("", invokePrivate(service, "text",
                new Class<?>[]{Object.class}, new Object[]{null}));
    }

    /**
     * {@code singular} drops the plural s of a list name, invents a loop variable
     * for a name that carries none, and for a one-letter name too short to
     * strip — both arms of each condition, driven directly because every
     * demonstration list happens to be a plural longer than one letter.
     */
    @Test
    void singularStripsThePluralOrInventsALoopVariable() throws Exception {
        DocumentTemplateService service = newService();
        assertEquals("line", invokePrivate(service, "singular",
                new Class<?>[]{String.class}, new Object[]{"lines"}));
        assertEquals("movementItem", invokePrivate(service, "singular",
                new Class<?>[]{String.class}, new Object[]{"movement"}));
        assertEquals("sItem", invokePrivate(service, "singular",
                new Class<?>[]{String.class}, new Object[]{"s"}));
    }

    /**
     * {@code listReferences} offers a list of PLAIN values as its loop and the
     * value itself, not as fields — the not-a-map arm, which no demonstration
     * list reaches because every one carries maps, exercised directly.
     */
    @Test
    @SuppressWarnings("unchecked")
    void listReferencesOffersAPlainListAsTheValueItself() throws Exception {
        DocumentTemplateService service = newService();
        List<DocumentTemplateService.Reference> found =
                (List<DocumentTemplateService.Reference>) invokePrivate(service, "listReferences",
                        new Class<?>[]{String.class, List.class},
                        new Object[]{"tags", List.of("PROMO", "BIO")});
        assertEquals(2, found.size());
        assertEquals("{#for tag in tags}…{/for}", found.get(0).getExpression());
        assertEquals("2 ligne(s)", found.get(0).getSample());
        assertEquals("{tag}", found.get(1).getExpression());
        assertEquals("PROMO", found.get(1).getSample());
        assertEquals("{#for tag in tags}…{/for}", found.get(1).getLoop());
    }

    /**
     * Invokes a private method of the service by reflection, so the arms no
     * demonstration document can reach are still exercised.
     *
     * @param service the service under test
     * @param name the method name
     * @param types the parameter types
     * @param args the arguments
     * @return whatever the method returns
     * @throws Exception when the method cannot be reached or raises
     */
    private Object invokePrivate(DocumentTemplateService service, String name,
                                 Class<?>[] types, Object[] args) throws Exception {
        java.lang.reflect.Method method = DocumentTemplateService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    /**
     * A template reaches the register's OWN typesetting primitives through
     * {@code fmt}, and lays a value flush right exactly as the built-in
     * receipt does — the wiring that makes a factory template able to
     * reproduce a printed document instead of approximating it.
     */
    @Test
    void atemplateLaysOutWithTheRegistersOwnPrimitives() {
        DocumentTemplateService service = newReflectiveService();
        String rendered = service.renderTemplate(
                template("{fmt.line('TOTAL TTC', '4,62 E')}"), java.util.Map.of());
        assertEquals("TOTAL TTC" + " ".repeat(27) + "4,62 E", rendered);
        assertEquals(42, rendered.length());
    }

    /**
     * The primitives are bound to the template's ADMINISTERED width, so the
     * same source laid out on a narrower paper aligns on that paper. Until
     * this lot the width was validated, stored and distributed, and no
     * renderer ever read it.
     */
    @Test
    void thePrimitivesFollowTheAdministeredWidth() {
        DocumentTemplateService service = newReflectiveService();
        DocumentTemplate narrow = template("{fmt.line('A', 'B')}");
        narrow.width = 20;
        assertEquals("A" + " ".repeat(18) + "B",
                service.renderTemplate(narrow, java.util.Map.of()));
    }

    /**
     * The width itself is exposed beside the primitives, for a template that
     * needs the number rather than the alignment.
     */
    @Test
    void thewidthIsExposedToTheTemplate() {
        DocumentTemplateService service = newService();
        assertEquals("42", service.renderTemplate(template("{width}"), java.util.Map.of()));
    }

    /**
     * Centring and the rule are reachable too, and a template mixing them with
     * its own values still reads those values — the enrichment ADDS, it does
     * not replace what the printer assembled.
     */
    @Test
    void atemplateCentresRulesAndStillReadsItsOwnValues() {
        DocumentTemplateService service = newReflectiveService();
        String rendered = service.renderTemplate(
                template("{fmt.centre('MERCI')}|{fmt.rule()}|{store}"),
                java.util.Map.of("store", "LYON"));
        assertEquals(" ".repeat(18) + "MERCI|" + "-".repeat(42) + "|LYON", rendered);
    }

    /**
     * A NULL data map still renders: the primitives are handed over all the
     * same, which is the null arm of the enrichment.
     */
    @Test
    void anullDataMapStillGetsThePrimitives() {
        DocumentTemplateService service = newReflectiveService();
        assertEquals("-".repeat(42), service.renderTemplate(template("{fmt.rule()}"), null));
    }

    /**
     * The caller's map is COPIED and never enriched in place: the values a
     * printer assembled for one document must not grow a key because they were
     * passed through the renderer.
     */
    @Test
    void thecallersMapIsNotEnrichedInPlace() {
        DocumentTemplateService service = newReflectiveService();
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("store", "LYON");
        service.renderTemplate(template("{fmt.rule()}"), data);
        assertEquals(java.util.Set.of("store"), data.keySet());
    }

    /**
     * {@code parseError} falls back to the exception's {@code toString} when the
     * parse failure carries no message — the null-message arm of its ternary,
     * exercised with an engine whose parse throws a message-less exception.
     */
    @Test
    void parseErrorFallsBackToToStringWhenTheFailureHasNoMessage() {
        DocumentTemplateService service = new DocumentTemplateService();
        service.engine = mock(Engine.class);
        when(service.engine.parse(anyString())).thenThrow(new RuntimeException());
        String error = service.parseError("{broken");
        assertEquals(new RuntimeException().toString(), error);
    }

    /**
     * {@code singular} appends "Item" when the list name is not a plural: a key
     * that does not end in "s" and a single-letter "s" key both take the else
     * arm of the {@code endsWith("s") && length() > 1} guard.
     */
    @Test
    void singularAppendsItemForANonPluralKey() throws Exception {
        DocumentTemplateService service = new DocumentTemplateService();
        java.lang.reflect.Method method =
                DocumentTemplateService.class.getDeclaredMethod("singular", String.class);
        method.setAccessible(true);
        assertEquals("xItem", method.invoke(service, "x"));
        assertEquals("sItem", method.invoke(service, "s"));
    }

    /**
     * {@code text} maps a null demonstration value to the empty string — the
     * null arm of its guard.
     */
    @Test
    void textMapsNullToTheEmptyString() throws Exception {
        DocumentTemplateService service = new DocumentTemplateService();
        java.lang.reflect.Method method =
                DocumentTemplateService.class.getDeclaredMethod("text", Object.class);
        method.setAccessible(true);
        assertEquals("", method.invoke(service, new Object[] {null}));
    }

    /**
     * {@code listReferences} describes a list of plain values through a single
     * {@code {item}} reference — the not-a-map arm taken when the first element
     * is not a map.
     */
    @Test
    void listReferencesDescribesAListOfPlainValues() throws Exception {
        DocumentTemplateService service = new DocumentTemplateService();
        java.lang.reflect.Method method = DocumentTemplateService.class.getDeclaredMethod(
                "listReferences", String.class, java.util.List.class);
        method.setAccessible(true);
        java.util.List<?> references =
                (java.util.List<?>) method.invoke(service, "items", java.util.List.of("PAIN"));
        assertEquals(2, references.size());
    }

    /**
     * Reads a block of the demonstration document.
     *
     * @param service the service under test
     * @param type the document type
     * @param key the block's name
     * @return the block
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> block(DocumentTemplateService service,
            DocumentTemplate.DocumentType type, String key) {
        return (Map<String, Object>) service.sampleData(type).get(key);
    }

    /**
     * Reads the first row of a repeating block of the demonstration document.
     *
     * @param service the service under test
     * @param type the document type
     * @param key the list's name
     * @return the first row
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> firstRow(DocumentTemplateService service,
            DocumentTemplate.DocumentType type, String key) {
        return ((List<Map<String, Object>>) service.sampleData(type).get(key)).get(0);
    }

    /**
     * BO-03-03-02 / BO-03-03-03 / BO-03-03-04: the INVOICE advertises the keys
     * the invoice itself hands — the due date, the addressee's tax id, both
     * unit prices, the attributes line and the tax-included column of the VAT
     * table. They were all served by the renderer and advertised by nobody, so
     * a paramétreur could not discover them.
     */
    @Test
    void theInvoiceSampleAdvertisesTheInvoicesOwnKeys() {
        DocumentTemplateService service = newService();
        assertEquals("15/10/2026",
                block(service, DocumentTemplate.DocumentType.INVOICE, "document").get("dueDate"));
        assertNotNull(
                block(service, DocumentTemplate.DocumentType.INVOICE, "customer").get("taxId"));
        Map<String, Object> line = firstRow(service, DocumentTemplate.DocumentType.INVOICE, "lines");
        assertNotNull(line.get("unitExcludingTax"));
        assertNotNull(line.get("unitIncludingTax"));
        assertNotNull(line.get("attributes"));
        assertNotNull(firstRow(service, DocumentTemplate.DocumentType.INVOICE, "vatRows")
                .get("includingTax"));
    }

    /**
     * The same keys on the A4 format, which is the SAME document in another
     * shape: a paramétreur who learns one knows the other.
     */
    @Test
    void theA4InvoiceSampleAdvertisesTheSameKeys() {
        DocumentTemplateService service = newService();
        assertEquals("15/10/2026",
                block(service, DocumentTemplate.DocumentType.INVOICE_A4, "document").get("dueDate"));
        assertNotNull(firstRow(service, DocumentTemplate.DocumentType.INVOICE_A4, "lines")
                .get("unitIncludingTax"));
    }

    /**
     * The invoice does NOT advertise the receipt's own line keys: its rows name
     * neither a family nor a unit nor a {@code unitPrice}, because the invoice
     * renderer hands none of the three. Advertising them would promise values
     * that render empty on a real invoice.
     */
    @Test
    void theInvoiceSampleDoesNotAdvertiseTheReceiptsLineKeys() {
        DocumentTemplateService service = newService();
        Map<String, Object> line = firstRow(service, DocumentTemplate.DocumentType.INVOICE, "lines");
        assertNull(line.get("family"));
        assertNull(line.get("unit"));
        assertNull(line.get("unitPrice"));
    }

    /**
     * The REFUND carries neither a settlement list nor a loyalty zone — the
     * refund renderer hands neither — and it carries the two blocks that are
     * its own: the return reason and the recalculation of the kept basket.
     */
    @Test
    void theRefundSampleCarriesOnlyWhatARefundHands() {
        DocumentTemplateService service = newService();
        Map<String, Object> data = service.sampleData(DocumentTemplate.DocumentType.REFUND_RECEIPT);
        assertNull(data.get("payments"));
        assertNull(data.get("fidelity"));
        assertNotNull(data.get("recalculation"));
        assertNotNull(block(service, DocumentTemplate.DocumentType.REFUND_RECEIPT, "document")
                .get("returnReason"));
    }

    /**
     * BO-03-03-03 / BO-03-03-22 / BO-03-03-24: the RECEIPT advertises the
     * tax-excluded unit price, the line's own modification and the cumulated
     * immediate discounts.
     */
    @Test
    void theReceiptSampleAdvertisesThePricesAndTheDiscounts() {
        DocumentTemplateService service = newService();
        Map<String, Object> line = firstRow(service,
                DocumentTemplate.DocumentType.SALE_RECEIPT, "lines");
        assertNotNull(line.get("unitExcludingTax"));
        assertNotNull(line.get("discountLabel"));
        assertNotNull(line.get("discount"));
        assertNotNull(block(service, DocumentTemplate.DocumentType.SALE_RECEIPT, "totals")
                .get("immediateDiscount"));
    }

    /**
     * BO-03-03-25 / BO-03-03-33 / BO-03-03-34: the loyalty zone advertises the
     * grouping that travels with each advantage line and the balances of imfid
     * responses B and C — the register hands all of them.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theLoyaltySampleAdvertisesTheGroupingAndTheBalances() {
        DocumentTemplateService service = newService();
        Map<String, Object> fidelity = block(service,
                DocumentTemplate.DocumentType.SALE_RECEIPT, "fidelity");
        assertNotNull(fidelity.get("projectedAfterEarn"));
        assertNotNull(fidelity.get("projectedLabel"));
        assertNotNull(fidelity.get("balanceAfter"));
        assertNotNull(fidelity.get("anonymousProjection"));
        Map<String, Object> line =
                ((List<Map<String, Object>>) fidelity.get("lines")).get(0);
        assertNotNull(line.get("advantageType"));
        assertNotNull(line.get("advantageTypeLabel"));
        assertNotNull(line.get("advantageTypeOrder"));
        assertNotNull(line.get("advantageCategory"));
        assertNotNull(line.get("advantageCategoryLabel"));
    }

    /**
     * BO-03-03-08: the credit note advertises its end of validity and its
     * barcode directive; the gift card advertises the same keys and states no
     * expiry, because the registry holds one only for a note.
     */
    @Test
    void theInstrumentSampleAdvertisesTheExpiryAndTheBarcode() {
        DocumentTemplateService service = newService();
        Map<String, Object> note = block(service,
                DocumentTemplate.DocumentType.CREDIT_NOTE_VOUCHER, "instrument");
        assertEquals("15/03/2027", note.get("validUntil"));
        assertEquals("[[BARCODE 297000000000042]]", note.get("barcode"));
        Map<String, Object> card = block(service,
                DocumentTemplate.DocumentType.GIFT_CARD_VOUCHER, "instrument");
        assertEquals("", card.get("validUntil"));
        assertEquals("[[BARCODE 296000000000042]]", card.get("barcode"));
    }

    /**
     * BO-03-04-03: a document NAMES its zones and the renderer puts them in
     * place before parsing — the zones of a document are the placeholders its
     * source carries, in the order it carries them.
     */
    @Test
    void aDocumentIsComposedOfTheZonesItNames() {
        DocumentTemplateService service = newService();
        DocumentTemplate zone = new DocumentTemplate();
        zone.code = "TOTAUX";
        zone.documentType = DocumentTemplate.DocumentType.FRAGMENT;
        zone.active = true;
        zone.source = "TOTAL {totals.includingTax}";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.find("code", "TOTAUX"))
                    .thenReturn(query(zone));
            assertEquals("DEBUT\nTOTAL {totals.includingTax}\nFIN",
                    service.expandZones("DEBUT\n[[ZONE TOTAUX]]\nFIN"));
        }
    }

    /**
     * BO-03-04-04: the SAME zone name may be carried by several models, and a
     * zone may itself name another — composition to a bounded depth.
     */
    @Test
    void aZoneMayNameAnotherZone() {
        DocumentTemplateService service = newService();
        DocumentTemplate outer = new DocumentTemplate();
        outer.code = "A";
        outer.source = "<[[ZONE B]]>";
        DocumentTemplate inner = new DocumentTemplate();
        inner.code = "B";
        inner.source = "coeur";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.find("code", "A")).thenReturn(query(outer));
            panache.when(() -> DocumentTemplate.find("code", "B")).thenReturn(query(inner));
            assertEquals("<coeur>", service.expandZones("[[ZONE A]]"));
        }
    }

    /**
     * A zone that names ITSELF stops at the bounded depth and prints its own
     * placeholder rather than hanging the register — the loop arm.
     */
    @Test
    void aZoneThatNamesItselfStopsAtTheBound() {
        DocumentTemplateService service = newService();
        DocumentTemplate loop = new DocumentTemplate();
        loop.code = "BOUCLE";
        loop.source = "x[[ZONE BOUCLE]]";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.find("code", "BOUCLE")).thenReturn(query(loop));
            String out = service.expandZones("[[ZONE BOUCLE]]");
            assertEquals("xxxxx[[ZONE BOUCLE]]", out);
        }
    }

    /**
     * A zone NOBODY administers expands to nothing: a document whose zone was
     * deleted prints without it, rather than printing the placeholder onto the
     * customer's paper — the unknown-code arm.
     */
    @Test
    void anUnknownZoneExpandsToNothing() {
        DocumentTemplateService service = newService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.find("code", "ABSENTE"))
                    .thenReturn(query(null));
            assertEquals("DEBUT\n\nFIN", service.expandZones("DEBUT\n[[ZONE ABSENTE]]\nFIN"));
        }
    }

    /**
     * A source naming NO zone is answered untouched, and a null source stays
     * null — the two short-circuit arms, which every ordinary template takes.
     */
    @Test
    void aSourceWithoutZonesIsUntouched() {
        DocumentTemplateService service = newService();
        assertEquals("rien a etendre", service.expandZones("rien a etendre"));
        assertNull(service.expandZones(null));
    }

    /**
     * A Panache query answering one template, for the zone lookups.
     *
     *
     * <p>Built with an ANSWER and not with {@code when(...)}: this helper is
     * called INSIDE the argument list of an outer {@code thenReturn(...)}, and a
     * second stubbing started while the first is unfinished is what Mockito
     * refuses with {@code UnfinishedStubbingException}.
     * @param result the template the lookup answers, or null for none
     * @return the stubbed query
     */
    @SuppressWarnings("unchecked")
    private io.quarkus.hibernate.orm.panache.PanacheQuery<DocumentTemplate> query(
            DocumentTemplate result) {
        return mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class, invocation ->
                "firstResult".equals(invocation.getMethod().getName()) ? result : null);
    }
    /** BO-03-03: incomplete zone markup is preserved and a declared zone without a source expands to nothing. */
    @Test
    void malformedAndEmptyZoneSourcesKeepTheirFallbacks() {
        DocumentTemplateService service = newService();
        assertEquals("[[ZONE ???]]", service.expandZones("[[ZONE ???]]"));
        DocumentTemplate empty = new DocumentTemplate();
        try (var templates = mockStatic(DocumentTemplate.class)) {
            templates.when(() -> DocumentTemplate.findByCode("EMPTY")).thenReturn(empty);
            assertEquals("AB", service.expandZones("A[[ZONE EMPTY]]B"));
        }
        for (var type : DocumentTemplate.DocumentType.values()) {
            assertNotNull(service.sampleData(type).get("store"), type.name());
        }
    }

}
