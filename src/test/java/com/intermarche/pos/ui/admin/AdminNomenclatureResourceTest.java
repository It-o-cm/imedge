package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.Nomenclature;
import com.intermarche.pos.domain.catalog.NomenclatureLevel;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.imports.NomenclatureCsvResource;
import com.intermarche.pos.service.NomenclatureImportService;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminNomenclatureResource}.
 * <p>
 * The resource is a Qute-backed back-office page over the Panache static
 * finders of {@link Nomenclature} and {@link ProductFamily} (resolved to
 * {@link PanacheEntityBase} under plain {@code mvn test}, intercepted with
 * {@link org.mockito.Mockito#mockStatic}), plus two mocked collaborators for
 * the integration: the reader of the published file
 * ({@link NomenclatureCsvResource}) and the loader
 * ({@link NomenclatureImportService}). No database, no Quarkus boot.
 * <p>
 * Branch enumeration (every arm exercised): {@code nomenclaturesPage} covers
 * no scheme at all, a requested scheme that matches, a requested scheme that
 * does not, the top level and an opened node, a page below one and a page
 * above the last, and a node with and without children; {@code importFile}
 * covers the null form, the null and blank scheme code, a reading that
 * complains, a reading that yields nothing, a load that refuses and a load
 * that goes through; {@code summarize} covers the under-cap and over-cap arms;
 * {@code trimmed} covers null, blank and present; {@code saveLevels} covers
 * the unknown scheme, a renamed level, a blank posted label and an absent one;
 * {@code crumbs} covers the root, a code shorter than the next level and a
 * node the catalog does not know; {@code saveNode} covers the unknown scheme,
 * each leg of the code/label guard, a length no level declares, a missing
 * parent, a code already held by another scheme, a creation and a rename;
 * {@code deleteNode} covers the unknown scheme, the unknown node, a node with
 * children, a node with articles and a clean delete; {@code value} covers the
 * null form, the absent field and the present one; {@code moveNode} covers the
 * unknown scheme, the unknown node, a top node, a target of the wrong level, a
 * target that does not exist, a drop into the node's own descent, a drop where
 * it already is, a code already taken and a move that re-codes a whole
 * subtree.
 */
class AdminNomenclatureResourceTest {

    /**
     * Builds a resource over a mocked template, reader and loader.
     *
     * @return the wired resource
     */
    private AdminNomenclatureResource newResource() {
        AdminNomenclatureResource resource = new AdminNomenclatureResource();
        resource.adminNomenclatures = mock(Template.class);
        resource.nomenclatureCsvResource = mock(NomenclatureCsvResource.class);
        resource.nomenclatureImportService = mock(NomenclatureImportService.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminNomenclatureResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminNomenclatures.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a scheme, without its level table: the table is stubbed on the
     * static mock by {@link #stubLevels}, because the cache the entity keeps is
     * private and filling it is not the test's business.
     *
     * @param code the scheme code
     * @return the scheme
     */
    private Nomenclature scheme(String code) {
        Nomenclature nomenclature = new Nomenclature();
        nomenclature.id = 7L;
        nomenclature.code = code;
        nomenclature.label = "Nomenclature " + code;
        nomenclature.enseigneCode = "ITM";
        return nomenclature;
    }

    /**
     * Stubs the level table of a scheme, each level SPIED so its
     * {@code persist()} writes nothing — a real entity's persist reaches for a
     * CDI container no unit test boots.
     *
     * @param mocked the active PanacheEntityBase static mock
     * @param nomenclature the scheme the levels belong to
     * @param lengths the cumulative code length of each level, top first
     * @return the spied levels, in rank order
     */
    private List<NomenclatureLevel> stubLevels(MockedStatic<PanacheEntityBase> mocked,
            Nomenclature nomenclature, int... lengths) {
        List<NomenclatureLevel> levels = new ArrayList<>();
        for (int rank = 0; rank < lengths.length; rank++) {
            NomenclatureLevel level = new NomenclatureLevel();
            level.nomenclature = nomenclature;
            level.rank = rank;
            level.label = "Niveau " + rank;
            level.codeLength = lengths[rank];
            NomenclatureLevel spied = spy(level);
            doNothing().when(spied).persist();
            levels.add(spied);
        }
        // The entity's own finder is not what the static mock intercepts: only
        // the query PanacheEntityBase runs underneath it is.
        mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by rank",
                nomenclature.id)).thenReturn(levels);
        return levels;
    }

    /**
     * Stubs the node listing of one level, by the query the entity runs.
     *
     * @param mocked the active PanacheEntityBase static mock
     * @param nomenclature the scheme
     * @param level the 0-based level
     * @param nodes the nodes the listing answers
     */
    private void stubLevelNodes(MockedStatic<PanacheEntityBase> mocked,
            Nomenclature nomenclature, int level, List<ProductFamily> nodes) {
        mocked.when(() -> PanacheEntityBase.list(
                "nomenclature.id = ?1 and level = ?2 order by code",
                nomenclature.id, level)).thenReturn(nodes);
    }

    /**
     * Stubs a lookup by code, by the query the entity runs.
     *
     * @param mocked the active PanacheEntityBase static mock
     * @param code the code looked up
     * @param found the row the lookup answers, or null
     */
    private void stubByCode(MockedStatic<PanacheEntityBase> mocked, String code,
            Object found) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(found);
        mocked.when(() -> PanacheEntityBase.find("code", code)).thenReturn(query);
    }

    /**
     * Builds a node of a scheme.
     *
     * @param nomenclature the scheme it belongs to
     * @param code the node code
     * @param level the node's 0-based level
     * @param description the node label
     * @return the node
     */
    private ProductFamily node(Nomenclature nomenclature, String code, int level,
            String description) {
        ProductFamily family = new ProductFamily();
        family.code = code;
        family.level = level;
        family.description = description;
        family.nomenclature = nomenclature;
        return family;
    }

    /**
     * Stubs the scheme listing and the per-level node listing on an active
     * static mock.
     *
     * @param mocked the active PanacheEntityBase static mock
     * @param schemes the schemes the listing answers
     */
    private void stubSchemes(MockedStatic<PanacheEntityBase> mocked,
            List<Nomenclature> schemes) {
        mocked.when(() -> PanacheEntityBase.list("order by code")).thenReturn(schemes);
    }

    /**
     * Opens the published file as the reader receives it.
     *
     * @param text the file content
     * @return the stream
     */
    private InputStream file(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds the integration form.
     *
     * @param scheme the posted scheme code
     * @param label the posted label
     * @param enseigne the posted enseigne
     * @param content the posted file content, or null for no file
     * @return the form
     */
    private AdminNomenclatureResource.ImportForm form(String scheme, String label,
            String enseigne, String content) {
        AdminNomenclatureResource.ImportForm posted = new AdminNomenclatureResource.ImportForm();
        posted.scheme = scheme;
        posted.label = label;
        posted.enseigne = enseigne;
        posted.file = content == null ? null : file(content);
        return posted;
    }

    /**
     * Reads the notice a redirect carries.
     *
     * @param response the redirect
     * @return the decoded notice
     */
    private String notice(Response response) {
        String target = response.getLocation().toString();
        String tail = target.substring(target.indexOf("notice=") + "notice=".length());
        int end = tail.indexOf('&');
        return java.net.URLDecoder.decode(end < 0 ? tail : tail.substring(0, end),
                StandardCharsets.UTF_8);
    }

    /**
     * Whether a redirect reports a success.
     *
     * @param response the redirect
     * @return true when the notice is flagged ok
     */
    private boolean ok(Response response) {
        return response.getLocation().toString().contains("noticeOk=true");
    }

    // --------------------------------------------------
    // nomenclaturesPage
    // --------------------------------------------------

    /**
     * With no scheme integrated at all the page is still drawn: the scheme is
     * null, the level table empty and the listing empty — the empty arms of
     * {@code resolveScheme}, {@code levelOf}, {@code childrenOf} and
     * {@code levelLabel} on one pass.
     */
    @Test
    void anEmptyBaseStillDrawsThePage() {
        AdminNomenclatureResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubSchemes(mocked, List.of());
            assertSame(instance, resource.nomenclaturesPage(null, null, 1, null, true));
            verify(resource.adminNomenclatures).data("schemes", List.of());
            verify(instance).data("scheme", null);
            verify(instance).data("levelLabel", "Niveau 0");
            verify(instance).data("total", 0);
            verify(instance).data("totalPages", 1);
        }
    }

    /**
     * An unknown scheme code falls back on the first of the list rather than
     * showing nothing (the no-match arm of {@code resolveScheme}), and the top
     * level is listed with each node's child count — the openable and the
     * childless arms of {@code countChildren} together.
     */
    @Test
    void anUnknownSchemeFallsBackOnTheFirstAndListsTheTopLevel() {
        AdminNomenclatureResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily frais = node(itm, "10", 0, "FRAIS TRAD");
        ProductFamily epicerie = node(itm, "20", 0, "EPICERIE");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubSchemes(mocked, List.of(itm));
            stubLevels(mocked, itm, 2, 4);
            stubLevelNodes(mocked, itm, 0, List.of(frais, epicerie));
            stubLevelNodes(mocked, itm, 1, List.of(node(itm, "1002", 1, "BOUCHERIE")));
            resource.nomenclaturesPage("INCONNU", null, 1, null, true);
            verify(instance).data("scheme", itm);
            verify(instance).data("total", 2);
            verify(instance).data(eq("rows"), any());
            verify(instance).data("parent", "");
        }
    }

    /**
     * Opening a node lists the level below kept to the codes starting with its
     * own (the non-empty arm of {@code childrenOf} and the prefix filter), and
     * the breadcrumb climbs back through the levels — including the arm where
     * a level's code length runs past the opened code, which stops the walk.
     */
    @Test
    void openingANodeListsItsChildrenAndBuildsTheBreadcrumb() {
        AdminNomenclatureResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily boucherie = node(itm, "1002", 1, "BOUCHERIE");
        ProductFamily cremerie = node(itm, "2001", 1, "CREMERIE");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubSchemes(mocked, List.of(itm));
            stubLevels(mocked, itm, 2, 4, 8);
            stubLevelNodes(mocked, itm, 1, List.of(boucherie, cremerie));
            stubLevelNodes(mocked, itm, 2, List.of());
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS TRAD"));
            resource.nomenclaturesPage("ITM_FR", "10", 1, null, true);
            verify(instance).data("total", 1);
            verify(instance).data("parent", "10");
            verify(instance).data("levelLabel", "Niveau 1");
        }
    }

    /**
     * A node the catalog does not know is still a crumb, under its bare code
     * (the {@code node == null} arm of {@code crumbs}).
     */
    @Test
    void anUnknownAncestorIsStillACrumb() {
        AdminNomenclatureResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubSchemes(mocked, List.of(itm));
            stubLevels(mocked, itm, 2, 4);
            stubLevelNodes(mocked, itm, 0, List.of());
            stubLevelNodes(mocked, itm, 1, List.of());
            stubByCode(mocked, "10", null);
            resource.nomenclaturesPage("ITM_FR", "10", 1, null, true);
            verify(instance).data(eq("crumbs"), any());
        }
    }

    /**
     * A page number below one and a page number past the last are both brought
     * back inside the listing rather than showing an empty page or failing (the
     * two clamping arms of the paging).
     */
    @Test
    void anOutOfRangePageIsClampedToTheListing() {
        AdminNomenclatureResource resource = newResource();
        TemplateInstance low = wireTemplate(resource);
        Nomenclature itm = scheme("ITM_FR");
        List<ProductFamily> nodes = new ArrayList<>();
        for (int i = 0; i < AdminNomenclatureResource.PAGE_SIZE + 5; i++) {
            nodes.add(node(itm, String.format("%02d", i), 0, "N" + i));
        }
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubSchemes(mocked, List.of(itm));
            stubLevels(mocked, itm, 2);
            stubLevelNodes(mocked, itm, 0, nodes);
            stubLevelNodes(mocked, itm, 1, List.of());
            resource.nomenclaturesPage("ITM_FR", null, 0, null, true);
            verify(low).data("page", 1);
            verify(low).data("totalPages", 2);
        }
        AdminNomenclatureResource high = newResource();
        TemplateInstance top = wireTemplate(high);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubSchemes(mocked, List.of(itm));
            stubLevels(mocked, itm, 2);
            stubLevelNodes(mocked, itm, 0, nodes);
            stubLevelNodes(mocked, itm, 1, List.of());
            high.nomenclaturesPage("ITM_FR", null, 99, null, true);
            verify(top).data("page", 2);
        }
    }

    // --------------------------------------------------
    // importFile
    // --------------------------------------------------

    /**
     * A clean integration reports what it wrote and nothing is refused: the
     * reader yields rows, the loader accepts them, and the notice names the
     * created, the updated and the level count.
     */
    @Test
    void aCleanIntegrationReportsWhatItWrote() {
        AdminNomenclatureResource resource = newResource();
        List<NomenclatureImportService.Row> rows =
                List.of(new NomenclatureImportService.Row(0, "10", "FRAIS TRAD", false));
        when(resource.nomenclatureCsvResource.read(any(), any())).thenReturn(rows);
        NomenclatureImportService.Outcome outcome = new NomenclatureImportService.Outcome();
        outcome.created = 4;
        outcome.updated = 2;
        outcome.levels = 3;
        when(resource.nomenclatureImportService.load(eq("ITM_FR"), eq("France"), eq("ITM"),
                eq(rows))).thenReturn(outcome);
        Response response = resource.importFile(form(" ITM_FR ", " France ", " ITM ", "DATA"));
        assertEquals(303, response.getStatus());
        assertTrue(ok(response));
        assertEquals("4 nœud(s) créé(s), 2 mis à jour, 3 niveau(x).", notice(response));
    }

    /**
     * A blank label and a blank enseigne are handed over as absent rather than
     * as empty strings, so a re-integration never blanks what the scheme
     * already carries (the blank arms of {@code trimmed}); a null one takes the
     * same arm.
     */
    @Test
    void blankSchemeDetailsAreHandedOverAsAbsent() {
        AdminNomenclatureResource resource = newResource();
        List<NomenclatureImportService.Row> rows =
                List.of(new NomenclatureImportService.Row(0, "10", "FRAIS TRAD", false));
        when(resource.nomenclatureCsvResource.read(any(), any())).thenReturn(rows);
        when(resource.nomenclatureImportService.load(any(), any(), any(), any()))
                .thenReturn(new NomenclatureImportService.Outcome());
        resource.importFile(form("ITM_FR", "   ", null, "DATA"));
        verify(resource.nomenclatureImportService).load("ITM_FR", null, null, rows);
    }

    /**
     * No form at all and a scheme code that says nothing are both refused
     * before anything is read: the three legs of the guard — null form, null
     * code, blank code — and no reading, no loading.
     */
    @Test
    void anIntegrationWithoutASchemeCodeIsRefusedBeforeReading() {
        AdminNomenclatureResource noForm = newResource();
        Response first = noForm.importFile(null);
        assertEquals(303, first.getStatus());
        assertFalse(ok(first));
        assertEquals("Le code de la nomenclature est obligatoire.", notice(first));
        verify(noForm.nomenclatureCsvResource, never()).read(any(), any());
        AdminNomenclatureResource nullCode = newResource();
        assertFalse(ok(nullCode.importFile(form(null, "L", "E", "DATA"))));
        verify(nullCode.nomenclatureCsvResource, never()).read(any(), any());
        AdminNomenclatureResource blankCode = newResource();
        assertFalse(ok(blankCode.importFile(form("   ", "L", "E", "DATA"))));
        verify(blankCode.nomenclatureImportService, never())
                .load(any(), any(), any(), any());
    }

    /**
     * A file the reader cannot make sense of is refused and never loaded, the
     * notice naming what stopped the reading.
     */
    @Test
    void anUnreadableFileIsRefusedAndNeverLoaded() {
        AdminNomenclatureResource resource = newResource();
        when(resource.nomenclatureCsvResource.read(any(), any())).thenAnswer(call -> {
            List<String> complaints = call.getArgument(1);
            complaints.add("Colonne NUMERO absente.");
            return new ArrayList<NomenclatureImportService.Row>();
        });
        Response response = resource.importFile(form("ITM_FR", "France", "ITM", "DATA"));
        assertFalse(ok(response));
        assertEquals("Colonne NUMERO absente.", notice(response));
        verify(resource.nomenclatureImportService, never()).load(any(), any(), any(), any());
    }

    /**
     * A readable file carrying no usable line is refused rather than loaded as
     * an empty nomenclature — the emptied-classification accident this screen
     * must not cause.
     */
    @Test
    void aFileWithoutAUsableLineIsRefused() {
        AdminNomenclatureResource resource = newResource();
        when(resource.nomenclatureCsvResource.read(any(), any()))
                .thenReturn(new ArrayList<>());
        Response response = resource.importFile(form("ITM_FR", "France", "ITM", "HEADER\n"));
        assertFalse(ok(response));
        assertEquals("Fichier sans aucune ligne exploitable.", notice(response));
        verify(resource.nomenclatureImportService, never()).load(any(), any(), any(), any());
    }

    /**
     * A load that refuses lines reports them: up to three are named, and past
     * that the rest are counted — the two arms of {@code summarize}.
     */
    @Test
    void aRefusedLoadNamesTheFirstRefusalsAndCountsTheRest() {
        AdminNomenclatureResource few = newResource();
        List<NomenclatureImportService.Row> rows =
                List.of(new NomenclatureImportService.Row(0, "10", "FRAIS", false));
        when(few.nomenclatureCsvResource.read(any(), any())).thenReturn(rows);
        NomenclatureImportService.Outcome small = new NomenclatureImportService.Outcome();
        small.errors.add("Niveau 1 : longueurs incohérentes.");
        when(few.nomenclatureImportService.load(any(), any(), any(), any())).thenReturn(small);
        Response short_ = few.importFile(form("ITM_FR", "France", "ITM", "DATA"));
        assertFalse(ok(short_));
        assertEquals("Niveau 1 : longueurs incohérentes.", notice(short_));
        AdminNomenclatureResource many = newResource();
        when(many.nomenclatureCsvResource.read(any(), any())).thenReturn(rows);
        NomenclatureImportService.Outcome big = new NomenclatureImportService.Outcome();
        for (int i = 1; i <= 5; i++) {
            big.errors.add("E" + i);
        }
        when(many.nomenclatureImportService.load(any(), any(), any(), any())).thenReturn(big);
        Response long_ = many.importFile(form("ITM_FR", "France", "ITM", "DATA"));
        assertFalse(ok(long_));
        assertEquals("E1 E2 E3 (+2 autre(s))", notice(long_));
    }

    // --------------------------------------------------
    // saveLevels, addLevel, deleteLevel — the level table
    // --------------------------------------------------

    /**
     * Builds a posted level table: one label and one length per rank.
     *
     * @param scheme the posted scheme code, or null to omit the field
     * @param lengths the posted length of each rank, in rank order
     * @return the form, its labels left absent
     */
    private MultivaluedMap<String, String> levelForm(String scheme, String... lengths) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        if (scheme != null) {
            form.putSingle("scheme", scheme);
        }
        for (int rank = 0; rank < lengths.length; rank++) {
            if (lengths[rank] != null) {
                form.putSingle("length-" + rank, lengths[rank]);
            }
        }
        return form;
    }

    /**
     * Stubs how many nodes sit at a rank or below.
     *
     * @param mocked the active PanacheEntityBase mock
     * @param nomenclature the scheme the count is asked of
     * @param rank the 0-based rank
     * @param count the answer
     */
    private void stubNodeCount(MockedStatic<PanacheEntityBase> mocked,
            Nomenclature nomenclature, int rank, long count) {
        mocked.when(() -> PanacheEntityBase.count("nomenclature.id = ?1 and level >= ?2",
                nomenclature.id, rank)).thenReturn(count);
    }

    /**
     * Stubs the rank lookup of one level.
     *
     * @param mocked the active PanacheEntityBase mock
     * @param nomenclature the scheme the level belongs to
     * @param rank the 0-based rank
     * @param found the level to resolve, or null
     */
    @SuppressWarnings("unchecked")
    private void stubByRank(MockedStatic<PanacheEntityBase> mocked, Nomenclature nomenclature,
            int rank, NomenclatureLevel found) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(found);
        mocked.when(() -> PanacheEntityBase.find("nomenclature.id = ?1 and rank = ?2",
                nomenclature.id, rank)).thenReturn(query);
    }

    /**
     * Saving the levels of an unknown scheme is refused, and the redirect
     * carries no scheme to come back to (the null arm of {@code redirect}).
     */
    @Test
    void savingLevelsOfAnUnknownSchemeIsRefused() {
        AdminNomenclatureResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ABSENT", null);
            Response response = resource.saveLevels(levelForm("ABSENT"));
            assertFalse(ok(response));
            assertEquals("Nomenclature introuvable.", notice(response));
            assertFalse(response.getLocation().toString().contains("scheme="));
        }
    }

    /**
     * A posted scheme field that is null is refused the same way, without a
     * lookup (the {@code schemeCode == null} arm).
     */
    @Test
    void savingLevelsWithoutASchemeFieldIsRefused() {
        AdminNomenclatureResource resource = newResource();
        Response response = resource.saveLevels(new MultivaluedHashMap<>());
        assertFalse(ok(response));
        assertEquals("Nomenclature introuvable.", notice(response));
    }

    /**
     * The three legs that make a posted length unusable — absent, unreadable
     * and below one — each refuse the whole table, and nothing is written.
     */
    @Test
    void anunusableLengthRefusesTheWholeTable() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            List<NomenclatureLevel> levels = stubLevels(mocked, itm, 2, 4);
            for (String bad : new String[] {null, "beaucoup", "0"}) {
                itm.reloadLevels();
                Response response = resource.saveLevels(levelForm("ITM_FR", "2", bad));
                assertFalse(ok(response), String.valueOf(bad));
                assertEquals("Niveau 1 : le nombre de caractères doit être un entier d'au"
                        + " moins 1.", notice(response));
            }
            verify(levels.get(0), never()).persist();
            verify(levels.get(1), never()).persist();
        }
    }

    /**
     * Lengths that do not grow strictly with the rank are refused: a level
     * that adds no character would name the very same node as the one above.
     * Equal and shorter are the two legs of that guard.
     */
    @Test
    void lengthsThatDoNotGrowAreRefused() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubLevels(mocked, itm, 2, 4);
            itm.reloadLevels();
            Response equal = resource.saveLevels(levelForm("ITM_FR", "4", "4"));
            assertFalse(ok(equal));
            assertEquals("Niveau 1 : les codes doivent s'allonger d'un niveau à l'autre, au-delà"
                    + " de 4 caractères.", notice(equal));
            itm.reloadLevels();
            Response shorter = resource.saveLevels(levelForm("ITM_FR", "6", "4"));
            assertFalse(ok(shorter));
            assertEquals("Niveau 1 : les codes doivent s'allonger d'un niveau à l'autre, au-delà"
                    + " de 6 caractères.", notice(shorter));
        }
    }

    /**
     * A length may not change on a level carrying nodes at its rank or below:
     * a node is placed by the length of its code, so moving the length would
     * leave every one of them hanging from a length nobody declares.
     */
    @Test
    void alengthCannotChangeUnderPlacedNodes() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            List<NomenclatureLevel> levels = stubLevels(mocked, itm, 2, 4);
            stubNodeCount(mocked, itm, 1, 539L);
            itm.reloadLevels();
            Response response = resource.saveLevels(levelForm("ITM_FR", "2", "8"));
            assertFalse(ok(response));
            assertEquals("Niveau 1 : 539 nœud(s) y sont posés ou en dessous — la longueur des"
                    + " codes ne peut plus changer.", notice(response));
            verify(levels.get(1), never()).persist();
        }
    }

    /**
     * On an EMPTY level the length changes, and the level is written once.
     */
    @Test
    void alengthChangesOnAnemptyLevel() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            List<NomenclatureLevel> levels = stubLevels(mocked, itm, 2, 4);
            stubNodeCount(mocked, itm, 1, 0L);
            itm.reloadLevels();
            Response response = resource.saveLevels(levelForm("ITM_FR", "2", "6"));
            assertTrue(ok(response));
            assertEquals("1 niveau(x) enregistré(s).", notice(response));
            assertEquals(6, levels.get(1).codeLength);
            verify(levels.get(1)).persist();
            verify(levels.get(0), never()).persist();
        }
    }

    /**
     * A level whose label is posted is renamed at an unchanged length; a level
     * whose posted label is blank, one whose field is absent and one whose
     * label is already that text are all left alone — the four arms of the
     * rename guard, with no write at all on the last three.
     */
    @Test
    void onlyTheLevelsCarryingAnewLabelAreWritten() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        MultivaluedMap<String, String> form = levelForm("ITM_FR", "2", "4", "8");
        form.putSingle("level-0", "  Activité  ");
        form.putSingle("level-1", "   ");
        form.putSingle("level-2", "Niveau 2");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            List<NomenclatureLevel> levels = stubLevels(mocked, itm, 2, 4, 8);
            Response response = resource.saveLevels(form);
            assertTrue(ok(response));
            assertEquals("1 niveau(x) enregistré(s).", notice(response));
            assertEquals("Activité", levels.get(0).label);
            assertEquals("Niveau 1", levels.get(1).label);
            assertEquals("Niveau 2", levels.get(2).label);
            verify(levels.get(0)).persist();
            verify(levels.get(1), never()).persist();
            verify(levels.get(2), never()).persist();
            assertTrue(response.getLocation().toString().contains("scheme=ITM_FR"));
        }
    }

    /**
     * Adding a level to an unknown scheme is refused, with and without the
     * scheme field — the two legs of that guard.
     */
    @Test
    void addingAlevelToAnUnknownSchemeIsRefused() {
        AdminNomenclatureResource resource = newResource();
        assertEquals("Nomenclature introuvable.",
                notice(resource.addLevel(new MultivaluedHashMap<>())));
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("scheme", "ABSENT");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ABSENT", null);
            assertFalse(ok(resource.addLevel(form)));
        }
    }

    /**
     * A new level with an unusable length is refused — absent, unreadable and
     * below one, the same three legs as the table.
     */
    @Test
    void addingAlevelWithoutAusableLengthIsRefused() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            for (String bad : new String[] {null, "douze", "-3"}) {
                MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
                form.putSingle("scheme", "ITM_FR");
                if (bad != null) {
                    form.putSingle("length", bad);
                }
                Response response = resource.addLevel(form);
                assertFalse(ok(response), String.valueOf(bad));
                assertEquals("Le nombre de caractères doit être un entier d'au moins 1.",
                        notice(response));
            }
        }
    }

    /**
     * A new level must carry LONGER codes than the level above it: equal and
     * shorter are both refused, and the message names the length to beat.
     */
    @Test
    void anewLevelMustCarryLongerCodesThanTheOneAbove() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubLevels(mocked, itm, 2, 8);
            for (String bad : new String[] {"8", "4"}) {
                itm.reloadLevels();
                MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
                form.putSingle("scheme", "ITM_FR");
                form.putSingle("length", bad);
                Response response = resource.addLevel(form);
                assertFalse(ok(response), bad);
                assertEquals("Un niveau plus bas porte des codes plus longs : au-delà de 8"
                        + " caractères.", notice(response));
            }
        }
    }

    /**
     * A level appended under the deepest one takes the next rank, the posted
     * name and the posted length, and belongs to the point of sale.
     */
    @Test
    void anappendedLevelTakesTheNextRank() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("scheme", "ITM_FR");
        form.putSingle("length", "12");
        form.putSingle("label", "  Sous-famille  ");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            // Stubbed BEFORE the construction mock opens: the fixture builds
            // real levels, and a level built inside that block would come back
            // a mock the spy refuses.
            stubLevels(mocked, itm, 2, 4, 8);
            try (MockedConstruction<NomenclatureLevel> created =
                    mockConstruction(NomenclatureLevel.class)) {
                Response response = resource.addLevel(form);
                assertTrue(ok(response));
                assertEquals("Niveau 3 ajouté (12 caractères).", notice(response));
                NomenclatureLevel added = created.constructed().get(0);
                assertSame(itm, added.nomenclature);
                assertEquals(3, added.rank);
                assertEquals("Sous-famille", added.label);
                assertEquals(12, added.codeLength);
                assertTrue(added.custom);
                verify(added).persist();
            }
        }
    }

    /**
     * The FIRST level of a scheme that declares none is accepted whatever its
     * length — there is nothing above to be longer than — and an absent label
     * falls back on the rank.
     */
    @Test
    void thefirstLevelNeedsNothingAboveIt() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("NEUF");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("scheme", "NEUF");
        form.putSingle("length", "2");
        form.putSingle("label", "   ");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "NEUF", itm);
            stubLevels(mocked, itm);
            try (MockedConstruction<NomenclatureLevel> created =
                    mockConstruction(NomenclatureLevel.class)) {
                Response response = resource.addLevel(form);
                assertTrue(ok(response));
                NomenclatureLevel added = created.constructed().get(0);
                assertEquals(0, added.rank);
                assertEquals("Niveau 0", added.label);
            }
        }
    }

    /**
     * Removing a level of an unknown scheme is refused, with and without the
     * scheme field.
     */
    @Test
    void removingAlevelOfAnUnknownSchemeIsRefused() {
        AdminNomenclatureResource resource = newResource();
        assertEquals("Nomenclature introuvable.",
                notice(resource.deleteLevel(new MultivaluedHashMap<>())));
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("scheme", "ABSENT");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ABSENT", null);
            assertFalse(ok(resource.deleteLevel(form)));
        }
    }

    /**
     * A rank that is absent, unreadable, negative or names no level is refused
     * — the four legs that leave the lookup with nothing.
     */
    @Test
    void anunusableRankIsRefused() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubByRank(mocked, itm, 9, null);
            for (String bad : new String[] {null, "dernier", "-1", "9"}) {
                MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
                form.putSingle("scheme", "ITM_FR");
                if (bad != null) {
                    form.putSingle("rank", bad);
                }
                Response response = resource.deleteLevel(form);
                assertFalse(ok(response), String.valueOf(bad));
                assertEquals("Niveau introuvable.", notice(response));
            }
        }
    }

    /**
     * A level carrying nodes at its rank or below is not removed: the message
     * says how many stand in the way.
     */
    @Test
    void alevelCarryingNodesIsNotRemoved() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("scheme", "ITM_FR");
        form.putSingle("rank", "2");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            List<NomenclatureLevel> levels = stubLevels(mocked, itm, 2, 4, 8);
            stubByRank(mocked, itm, 2, levels.get(2));
            stubNodeCount(mocked, itm, 2, 1909L);
            Response response = resource.deleteLevel(form);
            assertFalse(ok(response));
            assertEquals("Niveau 2 : 1909 nœud(s) y sont posés ou en dessous — retirez-les"
                    + " d'abord.", notice(response));
            verify(levels.get(2), never()).delete();
        }
    }

    /**
     * An empty level is removed and the empty ranks under it shift up — free
     * precisely because nothing is placed there to be re-parented.
     */
    @Test
    void anemptyLevelIsRemovedAndTheRanksBelowShiftUp() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("scheme", "ITM_FR");
        form.putSingle("rank", "1");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            List<NomenclatureLevel> levels = stubLevels(mocked, itm, 2, 4, 8);
            doNothing().when(levels.get(1)).delete();
            stubByRank(mocked, itm, 1, levels.get(1));
            stubNodeCount(mocked, itm, 1, 0L);
            Response response = resource.deleteLevel(form);
            assertTrue(ok(response));
            assertEquals("Niveau 1 retiré.", notice(response));
            assertEquals(0, levels.get(0).rank);
            assertEquals(1, levels.get(2).rank);
            verify(levels.get(0), never()).persist();
            verify(levels.get(2)).persist();
            verify(levels.get(1)).delete();
        }
    }

    /**
     * A level row says whether it may still be reshaped: an empty one may, a
     * populated one may not — the two arms the screen reads to offer the
     * length field and the removal button.
     */
    @Test
    void alevelRowSaysWhetherItMayStillBeReshaped() {
        assertTrue(new AdminNomenclatureResource.LevelRow(0, "Activité", 2, false, 0L)
                .isReshapable());
        AdminNomenclatureResource.LevelRow filled =
                new AdminNomenclatureResource.LevelRow(3, "Sous-famille", 12, true, 1909L);
        assertFalse(filled.isReshapable());
        assertEquals(3, filled.getRank());
        assertEquals("Sous-famille", filled.getLabel());
        assertEquals(12, filled.getCodeLength());
        assertTrue(filled.isCustom());
        assertEquals(1909L, filled.getNodes());
    }

    // --------------------------------------------------
    // saveNode
    // --------------------------------------------------

    /**
     * Builds a posted node form.
     *
     * @param scheme the posted scheme code, or null to omit the field
     * @param code the posted node code, or null to omit the field
     * @param label the posted label, or null to omit the field
     * @return the form
     */
    private MultivaluedMap<String, String> nodeForm(String scheme, String code, String label) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        if (scheme != null) {
            form.putSingle("scheme", scheme);
        }
        if (code != null) {
            form.putSingle("code", code);
        }
        if (label != null) {
            form.putSingle("label", label);
        }
        return form;
    }

    /**
     * A node of a length one level declares, hanging under an existing parent,
     * is created at the level that length names — the level is read off the
     * code, never posted.
     */
    @Test
    void aNodeIsCreatedAtTheLevelItsCodeLengthNames() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS TRAD"));
            stubByCode(mocked, "1002", null);
            List<ProductFamily> built = new ArrayList<>();
            try (org.mockito.MockedConstruction<ProductFamily> made =
                         org.mockito.Mockito.mockConstruction(ProductFamily.class,
                                 (created, context) -> built.add(created))) {
                Response response = resource.saveNode(nodeForm("ITM_FR", " 1002 ", " BOUCHERIE "));
                assertTrue(ok(response));
                assertEquals("Nœud « 1002 » créé.", notice(response));
                assertEquals(1, built.size());
                assertEquals("1002", built.get(0).code);
                assertEquals("BOUCHERIE", built.get(0).description);
                assertEquals(1, built.get(0).level);
                assertSame(itm, built.get(0).nomenclature);
                verify(built.get(0)).persist();
            }
        }
    }

    /**
     * A node that already exists is RENAMED, and nothing else moves: the code
     * stays, the level stays, and no second row is created.
     */
    @Test
    void anExistingNodeIsRenamedAndNotDuplicated() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily existing = spy(node(itm, "1002", 1, "BOUCHERIE"));
        doNothing().when(existing).persist();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS TRAD"));
            stubByCode(mocked, "1002", existing);
            Response response = resource.saveNode(nodeForm("ITM_FR", "1002", "BOUCHERIE TRAD"));
            assertTrue(ok(response));
            assertEquals("Nœud « 1002 » renommé.", notice(response));
            assertEquals("BOUCHERIE TRAD", existing.description);
            assertEquals("1002", existing.code);
            assertEquals(1, existing.level);
            verify(existing).persist();
        }
    }

    /**
     * A top-level node has no parent to check: the null arm of the parent
     * guard, which a level-0 code always takes.
     */
    @Test
    void aTopLevelNodeNeedsNoParent() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "30", null);
            try (org.mockito.MockedConstruction<ProductFamily> made =
                         org.mockito.Mockito.mockConstruction(ProductFamily.class)) {
                assertTrue(ok(resource.saveNode(nodeForm("ITM_FR", "30", "BAZAR"))));
            }
        }
    }

    /**
     * Saving a node into an unknown scheme is refused before anything is read,
     * and the redirect carries no scheme to come back to.
     */
    @Test
    void savingANodeInAnUnknownSchemeIsRefused() {
        AdminNomenclatureResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ABSENT", null);
            Response response = resource.saveNode(nodeForm("ABSENT", "10", "FRAIS"));
            assertFalse(ok(response));
            assertEquals("Nomenclature introuvable.", notice(response));
        }
        AdminNomenclatureResource noField = newResource();
        Response bare = noField.saveNode(nodeForm(null, "10", "FRAIS"));
        assertFalse(ok(bare));
        assertEquals("Nomenclature introuvable.", notice(bare));
    }

    /**
     * An empty code and an empty label are both refused — each leg of the
     * guard on its own, and a form carrying neither field takes the same arms.
     */
    @Test
    void aNodeWithoutACodeOrWithoutALabelIsRefused() {
        Nomenclature itm = scheme("ITM_FR");
        for (MultivaluedMap<String, String> form : List.of(
                nodeForm("ITM_FR", "   ", "FRAIS"),
                nodeForm("ITM_FR", "10", "   "),
                nodeForm("ITM_FR", null, null))) {
            AdminNomenclatureResource resource = newResource();
            try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
                stubByCode(mocked, "ITM_FR", itm);
                Response response = resource.saveNode(form);
                assertFalse(ok(response));
                assertEquals("Le code et le libellé sont obligatoires.", notice(response));
            }
        }
    }

    /**
     * A code whose length no level declares is refused rather than filed at a
     * guessed depth, the notice naming the length that was read.
     */
    @Test
    void aCodeOfAnUndeclaredLengthIsRefused() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            Response response = resource.saveNode(nodeForm("ITM_FR", "100", "BOUCHERIE"));
            assertFalse(ok(response));
            assertEquals("Code « 100 » : aucun niveau de ITM_FR n'a des codes de 3 caractères.",
                    notice(response));
        }
    }

    /**
     * A node whose parent does not exist is refused: the classification never
     * grows a branch hanging from nothing.
     */
    @Test
    void aNodeWhoseParentIsMissingIsRefused() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "99", null);
            Response response = resource.saveNode(nodeForm("ITM_FR", "9901", "INCONNU"));
            assertFalse(ok(response));
            assertEquals("Code « 9901 » : le nœud parent « 99 » n'existe pas.",
                    notice(response));
        }
    }

    /**
     * A code already held by ANOTHER scheme is refused rather than stolen: the
     * code is unique across the catalog, and moving it would empty the other
     * classification without saying so.
     */
    @Test
    void aCodeHeldByAnotherSchemeIsNotStolen() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        Nomenclature brico = new Nomenclature();
        brico.id = 9L;
        brico.code = "BRICO";
        ProductFamily foreign = node(brico, "1002", 1, "PLOMBERIE");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS TRAD"));
            stubByCode(mocked, "1002", foreign);
            Response response = resource.saveNode(nodeForm("ITM_FR", "1002", "BOUCHERIE"));
            assertFalse(ok(response));
            assertEquals("Le code « 1002 » appartient déjà à la nomenclature BRICO.",
                    notice(response));
            assertEquals("PLOMBERIE", foreign.description);
        }
    }

    /**
     * A node belonging to NO scheme yet — a touch group built on the grid — is
     * adopted rather than refused: the false arm of the foreign-scheme guard
     * that the null check short-circuits.
     */
    @Test
    void aNodeBelongingToNoSchemeIsAdopted() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily loose = spy(node(null, "1002", 0, "GROUPE LIBRE"));
        loose.nomenclature = null;
        loose.level = null;
        doNothing().when(loose).persist();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS TRAD"));
            stubByCode(mocked, "1002", loose);
            assertTrue(ok(resource.saveNode(nodeForm("ITM_FR", "1002", "BOUCHERIE"))));
            assertSame(itm, loose.nomenclature);
            assertEquals(1, loose.level);
        }
    }

    // --------------------------------------------------
    // deleteNode
    // --------------------------------------------------

    /**
     * A node with nothing hanging from it is deleted.
     */
    @Test
    void anEmptyNodeIsDeleted() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily leaf = spy(node(itm, "1002", 1, "BOUCHERIE"));
        doNothing().when(leaf).delete();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", leaf);
            stubLevelNodes(mocked, itm, 2, List.of());
            Response response = resource.deleteNode(nodeForm("ITM_FR", "1002", null));
            assertTrue(ok(response));
            assertEquals("Nœud « 1002 » supprimé.", notice(response));
            verify(leaf).delete();
        }
    }

    /**
     * Deleting from an unknown scheme, and deleting a node the catalog does
     * not know, are both refused before anything is touched.
     */
    @Test
    void deletingAnUnknownSchemeOrAnUnknownNodeIsRefused() {
        AdminNomenclatureResource unknownScheme = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ABSENT", null);
            Response response = unknownScheme.deleteNode(nodeForm("ABSENT", "1002", null));
            assertFalse(ok(response));
            assertEquals("Nomenclature introuvable.", notice(response));
        }
        AdminNomenclatureResource unknownNode = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "9999", null);
            Response response = unknownNode.deleteNode(nodeForm("ITM_FR", "9999", null));
            assertFalse(ok(response));
            assertEquals("Nœud « 9999 » introuvable.", notice(response));
        }
        AdminNomenclatureResource noCode = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            Response response = noCode.deleteNode(nodeForm("ITM_FR", null, null));
            assertFalse(ok(response));
            assertEquals("Nœud «  » introuvable.", notice(response));
        }
    }

    /**
     * A node with children is not deleted: they hang from it by their code
     * prefix, and nothing would even signal the break.
     */
    @Test
    void aNodeWithChildrenIsNotDeleted() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily branch = spy(node(itm, "10", 0, "FRAIS TRAD"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "10", branch);
            stubLevelNodes(mocked, itm, 1,
                    List.of(node(itm, "1002", 1, "BOUCHERIE"), node(itm, "1004", 1, "TRAITEUR")));
            Response response = resource.deleteNode(nodeForm("ITM_FR", "10", null));
            assertFalse(ok(response));
            assertEquals("Nœud « 10 » : 2 nœud(s) en dépendent, supprimez-les d'abord.",
                    notice(response));
            verify(branch, never()).delete();
        }
    }

    /**
     * A node with articles filed under it is not deleted: the relation
     * cascades, so deleting the node would take the articles with it.
     */
    @Test
    void aNodeWithArticlesIsNotDeleted() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily filled = spy(node(itm, "1002", 1, "BOUCHERIE"));
        filled.products = new java.util.HashSet<>(List.of(new com.intermarche.pos.domain.catalog.Product()));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", filled);
            stubLevelNodes(mocked, itm, 2, List.of());
            Response response = resource.deleteNode(nodeForm("ITM_FR", "1002", null));
            assertFalse(ok(response));
            assertEquals("Nœud « 1002 » : 1 article(s) y sont rattachés, reclassez-les d'abord.",
                    notice(response));
            verify(filled, never()).delete();
        }
    }

    /**
     * A node whose code lands on no declared level is counted as childless
     * rather than failing (the null arm of the level lookup in the delete
     * path), and a node carrying a null article set is read as empty.
     */
    @Test
    void anUnplaceableNodeIsStillDeletable() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily odd = spy(node(itm, "100", 1, "HORS NIVEAU"));
        odd.products = null;
        doNothing().when(odd).delete();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "100", odd);
            Response response = resource.deleteNode(nodeForm("ITM_FR", "100", null));
            assertTrue(ok(response));
            verify(odd).delete();
        }
    }

    // --------------------------------------------------
    // moveNode — the drag and drop
    // --------------------------------------------------

    /**
     * Builds a posted move.
     *
     * @param scheme the posted scheme code, or null to omit the field
     * @param code the moved node's code, or null to omit the field
     * @param parent the target node's code, or null to omit the field
     * @return the form
     */
    private MultivaluedMap<String, String> moveForm(String scheme, String code, String parent) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        if (scheme != null) {
            form.putSingle("scheme", scheme);
        }
        if (code != null) {
            form.putSingle("code", code);
        }
        if (parent != null) {
            form.putSingle("parent", parent);
        }
        return form;
    }

    /**
     * Reads the verdict flag out of a JSON answer.
     *
     * @param response the JSON answer
     * @return true when the write went through
     */
    private boolean jsonOk(Response response) {
        return response.getEntity().toString().startsWith("{\"ok\":true");
    }

    /**
     * Reads the message out of a JSON answer.
     *
     * @param response the JSON answer
     * @return the message
     */
    private String jsonMessage(Response response) {
        String body = response.getEntity().toString();
        int from = body.indexOf("\"message\":\"") + 11;
        // The message is no longer the last field — a move answers the code it
        // landed on — so the end is the closing quote, not the end of body.
        int to = body.indexOf("\",", from);
        return body.substring(from, to < 0 ? body.length() - 2 : to);
    }

    /**
     * Reads the landing code out of a JSON answer.
     *
     * @param response the JSON answer
     * @return the code, or "null" when the write moved nothing
     */
    private String jsonCode(Response response) {
        String body = response.getEntity().toString();
        int from = body.indexOf("\"code\":") + 7;
        return body.substring(from, body.length() - 1).replace("\"", "");
    }

    /**
     * Builds the renumbering form.
     *
     * @param scheme the posted scheme code
     * @param code the posted node code
     * @param segment the posted free part of the code
     * @return the form
     */
    private MultivaluedMap<String, String> recodeForm(String scheme, String code,
            String segment) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        if (scheme != null) {
            form.putSingle("scheme", scheme);
        }
        if (code != null) {
            form.putSingle("code", code);
        }
        if (segment != null) {
            form.putSingle("segment", segment);
        }
        return form;
    }

    /**
     * Moving a node RE-CODES it and everything under it: the node loses its
     * old parent's prefix and takes the new one, its descendants follow, and
     * every level stays what it was — both parents being of the same level,
     * every code keeps its length.
     */
    @Test
    void movingANodeRecodesItAndItsWholeDescent() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily moved = spy(node(itm, "1002", 1, "BOUCHERIE"));
        ProductFamily child = spy(node(itm, "10020200", 2, "BOEUF"));
        ProductFamily grandChild = spy(node(itm, "100202000001", 3, "ENTRECOTE"));
        for (ProductFamily row : List.of(moved, child, grandChild)) {
            doNothing().when(row).persist();
        }
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4, 8, 12);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", moved);
            stubByCode(mocked, "20", node(itm, "20", 0, "EPICERIE"));
            stubByCode(mocked, "2002", null);
            stubByCode(mocked, "20020200", null);
            stubByCode(mocked, "200202000001", null);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(List.of(moved, child, grandChild,
                            node(itm, "1004", 1, "TRAITEUR")));
            Response response = resource.apiMoveNode(moveForm("ITM_FR", "1002", "20"));
            assertTrue(jsonOk(response));
            assertEquals("Nœud « 1002 » déplacé en « 2002 » — 3 nœud(s) recodé(s).",
                    jsonMessage(response));
            assertEquals("2002", moved.code);
            assertEquals("20020200", child.code);
            assertEquals("200202000001", grandChild.code);
            assertEquals(1, moved.level);
            assertEquals(3, grandChild.level);
            verify(moved).persist();
            verify(grandChild).persist();
        }
    }

    /**
     * A move into an unknown scheme, and a move of a node the catalog does not
     * know, are both refused before anything is read.
     */
    @Test
    void movingInAnUnknownSchemeOrAnUnknownNodeIsRefused() {
        AdminNomenclatureResource unknownScheme = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ABSENT", null);
            Response response = unknownScheme.apiMoveNode(moveForm("ABSENT", "1002", "20"));
            assertEquals(404, response.getStatus());
            assertFalse(jsonOk(response));
        }
        AdminNomenclatureResource unknownNode = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "9999", null);
            Response response = unknownNode.apiMoveNode(moveForm("ITM_FR", "9999", "20"));
            assertFalse(jsonOk(response));
            assertEquals("Nœud « 9999 » introuvable.", jsonMessage(response));
        }
    }

    /**
     * A TOP node has no parent to change, and a node whose code lands on no
     * declared level is refused the same way — the two legs of that guard.
     */
    @Test
    void aTopNodeAndAnUnplaceableNodeCannotBeMoved() {
        Nomenclature itm = scheme("ITM_FR");
        AdminNomenclatureResource top = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS TRAD"));
            Response response = top.apiMoveNode(moveForm("ITM_FR", "10", "20"));
            assertFalse(jsonOk(response));
            assertEquals("Nœud « 10 » : un nœud de premier niveau n'a pas de parent à "
                    + "changer.", jsonMessage(response));
        }
        AdminNomenclatureResource odd = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "100", node(itm, "100", 1, "HORS NIVEAU"));
            assertFalse(jsonOk(odd.apiMoveNode(moveForm("ITM_FR", "100", "20"))));
        }
    }

    /**
     * The target must be exactly ONE level above: a code of any other length
     * is refused, because the moved node would need a code of a length its own
     * level does not have.
     */
    @Test
    void aTargetOfTheWrongLevelIsRefused() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4, 8);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "10020200", node(itm, "10020200", 2, "BOEUF"));
            Response response = resource.apiMoveNode(moveForm("ITM_FR", "10020200", "10"));
            assertFalse(jsonOk(response));
            assertEquals("Nœud « 10020200 » : il ne peut être déposé que sur un nœud de "
                    + "niveau 1.", jsonMessage(response));
        }
    }

    /**
     * A target of the right length that does not exist is refused: the
     * classification never grows a branch hanging from nothing.
     */
    @Test
    void aTargetThatDoesNotExistIsRefused() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", node(itm, "1002", 1, "BOUCHERIE"));
            stubByCode(mocked, "99", null);
            Response response = resource.apiMoveNode(moveForm("ITM_FR", "1002", "99"));
            assertFalse(jsonOk(response));
            assertEquals("Nœud « 99 » introuvable.", jsonMessage(response));
        }
    }

    /**
     * A node cannot be dropped into its own descent: the result would be a
     * branch hanging from a code it also carries.
     */
    @Test
    void aNodeCannotBeDroppedIntoItsOwnDescent() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4, 8, 12);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", node(itm, "1002", 1, "BOUCHERIE"));
            stubByCode(mocked, "10020200", node(itm, "10020200", 2, "BOEUF"));
            Response response = resource.apiMoveNode(
                    moveForm("ITM_FR", "1002", "10020200"));
            assertFalse(jsonOk(response));
        }
    }

    /**
     * Dropping a node where it already sits changes nothing and says so,
     * rather than rewriting every code of its descent for no reason.
     */
    @Test
    void droppingANodeWhereItAlreadySitsChangesNothing() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily moved = spy(node(itm, "1002", 1, "BOUCHERIE"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", moved);
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS TRAD"));
            Response response = resource.apiMoveNode(moveForm("ITM_FR", "1002", "10"));
            assertFalse(jsonOk(response));
            assertEquals("Nœud « 1002 » est déjà sous « 10 ».", jsonMessage(response));
            verify(moved, never()).persist();
        }
    }

    /**
     * A move whose number is already taken under the new parent RENUMBERS the
     * node instead of refusing: the codes of the target branch are not on the
     * operator's screen, so a refusal asked them to solve a problem they could
     * neither see nor have caused.
     */
    @Test
    void aMoveOntoATakenNumberRenumbersRatherThanRefusing() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily moved = spy(node(itm, "1002", 1, "BOUCHERIE"));
        doNothing().when(moved).persist();
        ProductFamily standing = node(itm, "2002", 1, "DÉJÀ LÀ");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", moved);
            stubByCode(mocked, "20", node(itm, "20", 0, "EPICERIE"));
            stubByCode(mocked, "2000", null);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(List.of(moved, standing));
            Response response = resource.apiMoveNode(moveForm("ITM_FR", "1002", "20"));
            assertTrue(jsonOk(response));
            assertEquals("2000", moved.code);
            assertEquals("2000", jsonCode(response));
            assertEquals("Nœud « 1002 » déplacé en « 2000 » — numéro « 02 » déjà pris,"
                    + " renuméroté en « 00 » — 1 nœud(s) recodé(s).", jsonMessage(response));
            verify(moved).persist();
        }
    }

    /**
     * The renumbering picks the FIRST free number, not merely a free one: the
     * shop reads its classification in order, and a node parked at 47 because
     * 0 to 46 were tried in some other sequence would be unfindable.
     */
    @Test
    void theRenumberingTakesTheFirstFreeNumber() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily moved = spy(node(itm, "1002", 1, "BOUCHERIE"));
        doNothing().when(moved).persist();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", moved);
            stubByCode(mocked, "20", node(itm, "20", 0, "EPICERIE"));
            stubByCode(mocked, "2003", null);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(List.of(moved,
                            node(itm, "2000", 1, "ZERO"), node(itm, "2001", 1, "UN"),
                            node(itm, "2002", 1, "DEUX")));
            Response response = resource.apiMoveNode(moveForm("ITM_FR", "1002", "20"));
            assertTrue(jsonOk(response));
            assertEquals("2003", moved.code);
        }
    }

    /**
     * The whole descent follows the renumbering, and a descendant's own
     * collision counts too: the number is free only when EVERY code the
     * subtree would take is free.
     */
    @Test
    void aDescendantsCollisionAlsoRulesTheNumberOut() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily moved = spy(node(itm, "1002", 1, "BOUCHERIE"));
        doNothing().when(moved).persist();
        ProductFamily child = spy(node(itm, "10020200", 2, "BOEUF"));
        doNothing().when(child).persist();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4, 8);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", moved);
            stubByCode(mocked, "20", node(itm, "20", 0, "EPICERIE"));
            stubByCode(mocked, "2001", null);
            stubByCode(mocked, "20010200", null);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(List.of(moved, child,
                            // 02 is held, so the scan starts. 2000 is free but
                            // 20000200 is not: the subtree does not fit there,
                            // so 2000 is not a candidate either.
                            node(itm, "2002", 1, "DÉJÀ LÀ"),
                            node(itm, "20000200", 2, "OCCUPANT")));
            Response response = resource.apiMoveNode(moveForm("ITM_FR", "1002", "20"));
            assertTrue(jsonOk(response));
            assertEquals("2001", moved.code);
            assertEquals("20010200", child.code);
            verify(child).persist();
        }
    }

    /**
     * A number free in the scheme being edited but held by ANOTHER scheme is
     * not free: a family code is unique across the whole base, so the cheap
     * in-memory scan is confirmed against it before the number is taken.
     */
    @Test
    void aNumberHeldByAnotherSchemeIsNotFree() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        Nomenclature other = scheme("AUTRE");
        ProductFamily moved = spy(node(itm, "1002", 1, "BOUCHERIE"));
        doNothing().when(moved).persist();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", moved);
            stubByCode(mocked, "20", node(itm, "20", 0, "EPICERIE"));
            stubByCode(mocked, "2002", node(other, "2002", 1, "AILLEURS"));
            stubByCode(mocked, "2000", null);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(List.of(moved));
            Response response = resource.apiMoveNode(moveForm("ITM_FR", "1002", "20"));
            assertTrue(jsonOk(response));
            assertEquals("2000", moved.code);
        }
    }

    /**
     * A level with no number left at all is said as such rather than moving
     * the node onto a code somebody holds.
     */
    @Test
    void aFullLevelRefusesTheMoveInsteadOfOverwriting() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily moved = spy(node(itm, "102", 1, "NŒUD"));
        List<ProductFamily> crowd = new ArrayList<>();
        crowd.add(moved);
        // A one-character segment: ten numbers, all held.
        for (int taken = 0; taken <= 9; taken++) {
            crowd.add(node(itm, "20" + taken, 1, "PRIS " + taken));
        }
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 3);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "102", moved);
            stubByCode(mocked, "20", node(itm, "20", 0, "EPICERIE"));
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(crowd);
            Response response = resource.apiMoveNode(moveForm("ITM_FR", "102", "20"));
            assertFalse(jsonOk(response));
            assertEquals("Nœud « 102 » : aucun numéro libre sous « 20 ».",
                    jsonMessage(response));
            assertEquals("102", moved.code);
            verify(moved, never()).persist();
        }
    }

    /**
     * The free part of a code is the operator's to change: what the machine
     * picked on a drop, the shop corrects, and the whole descent follows.
     */
    @Test
    void theFreePartOfACodeCanBeRenumberedByHand() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily renamed = spy(node(itm, "1002", 1, "BOUCHERIE"));
        doNothing().when(renamed).persist();
        ProductFamily child = spy(node(itm, "10020200", 2, "BOEUF"));
        doNothing().when(child).persist();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4, 8);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", renamed);
            stubByCode(mocked, "1008", null);
            stubByCode(mocked, "10080200", null);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(List.of(renamed, child));
            Response response = resource.apiRecodeNode(recodeForm("ITM_FR", "1002", "08"));
            assertTrue(jsonOk(response));
            assertEquals("1008", renamed.code);
            assertEquals("10080200", child.code);
            assertEquals("1008", jsonCode(response));
            assertEquals("Nœud « 1002 » renuméroté en « 1008 » — 2 nœud(s) recodé(s).",
                    jsonMessage(response));
            verify(renamed).persist();
            verify(child).persist();
        }
    }

    /**
     * A first level node has no prefix at all, so its WHOLE code is the free
     * part — the other leg of the guard that computes it.
     */
    @Test
    void aFirstLevelNodeRenumbersItsWholeCode() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily top = spy(node(itm, "10", 0, "FRAIS TRAD"));
        doNothing().when(top).persist();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "10", top);
            stubByCode(mocked, "30", null);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(List.of(top));
            Response response = resource.apiRecodeNode(recodeForm("ITM_FR", "10", "30"));
            assertTrue(jsonOk(response));
            assertEquals("30", top.code);
        }
    }

    /**
     * A number asked for BY HAND that is already taken is refused, and not
     * silently changed the way a drop's is: it is a precise request, and
     * answering it with another number would leave the operator correcting a
     * correction.
     */
    @Test
    void aNumberAskedForByHandIsRefusedWhenTakenRatherThanRenumbered() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily renamed = spy(node(itm, "1002", 1, "BOUCHERIE"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", renamed);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(List.of(renamed, node(itm, "1004", 1, "TRAITEUR")));
            Response response = resource.apiRecodeNode(recodeForm("ITM_FR", "1002", "04"));
            assertFalse(jsonOk(response));
            assertEquals("Le code « 1004 » est déjà pris.", jsonMessage(response));
            assertEquals("null", jsonCode(response));
            assertEquals("1002", renamed.code);
            verify(renamed, never()).persist();
        }
    }

    /**
     * A segment of the wrong length is refused with the length expected: the
     * screen caps the field, but the endpoint answers the machine too.
     */
    @Test
    void aSegmentOfTheWrongLengthIsRefused() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily renamed = spy(node(itm, "1002", 1, "BOUCHERIE"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", renamed);
            Response response = resource.apiRecodeNode(recodeForm("ITM_FR", "1002", "123"));
            assertFalse(jsonOk(response));
            assertEquals("Nœud « 1002 » : le numéro compte 2 caractère(s), pas 3.",
                    jsonMessage(response));
            verify(renamed, never()).persist();
        }
    }

    /**
     * Renumbering a node to the number it already carries writes nothing: the
     * field is left on blur as often as it is changed.
     */
    @Test
    void renumberingToTheSameNumberWritesNothing() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily renamed = spy(node(itm, "1002", 1, "BOUCHERIE"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", renamed);
            Response response = resource.apiRecodeNode(recodeForm("ITM_FR", "1002", "02"));
            assertFalse(jsonOk(response));
            assertEquals("Nœud « 1002 » porte déjà ce numéro.", jsonMessage(response));
            verify(renamed, never()).persist();
        }
    }

    /**
     * Renumbering a node that does not exist is said, not thrown.
     */
    @Test
    void renumberingAnUnknownNodeIsRefused() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "9999", null);
            assertFalse(jsonOk(resource.apiRecodeNode(recodeForm("ITM_FR", "9999", "02"))));
            // And the empty code, the other leg of the same guard.
            assertFalse(jsonOk(resource.apiRecodeNode(recodeForm("ITM_FR", "", "02"))));
        }
    }

    /**
     * A node whose code length matches no declared level cannot be renumbered:
     * nothing says how much of it is the prefix.
     */
    @Test
    void renumberingANodeOfNoDeclaredLevelIsRefused() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "100", node(itm, "100", 1, "BANCAL"));
            Response response = resource.apiRecodeNode(recodeForm("ITM_FR", "100", "0"));
            assertFalse(jsonOk(response));
            assertEquals("Nœud « 100 » : aucun niveau de ITM_FR n'a des codes de"
                    + " 3 caractères.", jsonMessage(response));
        }
    }

    /**
     * Renumbering under a scheme that does not exist answers 404, the same way
     * every other tree endpoint does.
     */
    @Test
    void renumberingUnderAnUnknownSchemeAnswersNotFound() {
        AdminNomenclatureResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ABSENT", null);
            Response response = resource.apiRecodeNode(recodeForm("ABSENT", "1002", "02"));
            assertEquals(404, response.getStatus());
            assertFalse(jsonOk(response));
            // And with no scheme posted at all, the other leg of the guard.
            assertEquals(404, resource.apiRecodeNode(recodeForm(null, "1002", "02"))
                    .getStatus());
        }
    }

    /**
     * A write that moves nothing says {@code null} for the landing code, which
     * is the other leg of the ternary the JSON is built with.
     */
    @Test
    void aWriteThatMovesNothingNamesNoLandingCode() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily moved = spy(node(itm, "1002", 1, "BOUCHERIE"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", moved);
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS TRAD"));
            assertEquals("null", jsonCode(resource.apiMoveNode(
                    moveForm("ITM_FR", "1002", "10"))));
        }
    }

    // --------------------------------------------------
    // children — the tree's JSON listing
    // --------------------------------------------------

    /**
     * The top level answers one JSON object per node, each carrying whether
     * anything hangs under it: the first node has children of the level below
     * and the others do not — the true and false arms of {@code hasPrefix} and
     * of the {@code openable} flag together — and a node whose level is unset is
     * written {@code null} rather than crashing, the {@code node.level == null}
     * arm of {@code nodeJson}. The first-comma flag flips across the three rows.
     */
    @Test
    void theTopLevelIsAnsweredAsJson() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily frais = node(itm, "10", 0, "FRAIS");
        ProductFamily epicerie = node(itm, "20", 0, "EPICERIE");
        ProductFamily hors = node(itm, "30", 0, "HORS");
        hors.level = null;
        ProductFamily bouch = node(itm, "1002", 1, "BOUCHERIE");
        ProductFamily ghost = node(itm, "1099", 1, "SANS CODE");
        ghost.code = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubLevelNodes(mocked, itm, 0, List.of(frais, epicerie, hors));
            stubLevelNodes(mocked, itm, 1, java.util.Arrays.asList(bouch, ghost));
            Response response = resource.children("ITM_FR", null);
            assertEquals(200, response.getStatus());
            assertEquals("[{\"code\":\"10\",\"label\":\"FRAIS\",\"level\":0,\"openable\":true},"
                    + "{\"code\":\"20\",\"label\":\"EPICERIE\",\"level\":0,\"openable\":false},"
                    + "{\"code\":\"30\",\"label\":\"HORS\",\"level\":null,\"openable\":false}]",
                    response.getEntity().toString());
        }
    }

    /**
     * Opening a node lists only the children of the level below whose code
     * starts with its own (the prefix filter of {@code childrenOf}, one node
     * kept and one dropped), with the trimmed parent and the empty
     * level-below listing that leaves every node closed.
     */
    @Test
    void openingANodeAnswersOnlyItsChildrenAsJson() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily bouch = node(itm, "1002", 1, "BOUCHERIE");
        ProductFamily crem = node(itm, "2001", 1, "CREMERIE");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubLevels(mocked, itm, 2, 4);
            stubLevelNodes(mocked, itm, 1, List.of(bouch, crem));
            stubLevelNodes(mocked, itm, 2, List.of());
            Response response = resource.children("ITM_FR", " 10 ");
            assertEquals(200, response.getStatus());
            assertEquals("[{\"code\":\"1002\",\"label\":\"BOUCHERIE\",\"level\":1,"
                    + "\"openable\":false}]", response.getEntity().toString());
        }
    }

    /**
     * A null scheme code and an unknown scheme code both answer 404 with an
     * empty JSON array — the {@code schemeCode == null} arm and the
     * {@code scheme == null} arm.
     */
    @Test
    void listingChildrenOfAnUnknownSchemeAnswersNotFound() {
        AdminNomenclatureResource nullCode = newResource();
        Response byNull = nullCode.children(null, null);
        assertEquals(404, byNull.getStatus());
        assertEquals("[]", byNull.getEntity().toString());
        AdminNomenclatureResource absent = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ABSENT", null);
            Response byAbsent = absent.children("ABSENT", null);
            assertEquals(404, byAbsent.getStatus());
            assertEquals("[]", byAbsent.getEntity().toString());
        }
    }

    // --------------------------------------------------
    // search — the tree's JSON search
    // --------------------------------------------------

    /**
     * A search by LABEL answers the one hit, its full readable path built by
     * climbing its declared ancestors: an ancestor the catalog knows lends its
     * description (the {@code ancestor != null} arm of {@code pathOf}), the
     * level whose length reaches the node's own stops the walk, and a node that
     * does not match is skipped.
     */
    @Test
    void aSearchByLabelAnswersTheHitWithItsPath() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily frais = node(itm, "10", 0, "FRAIS");
        ProductFamily bouch = node(itm, "1002", 1, "BOUCHERIE");
        ProductFamily trait = node(itm, "1004", 1, "TRAITEUR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "10", frais);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(List.of(frais, bouch, trait));
            Response response = resource.search("ITM_FR", " Bou ");
            assertEquals(200, response.getStatus());
            assertEquals("[{\"code\":\"1002\",\"label\":\"BOUCHERIE\",\"level\":1,"
                    + "\"openable\":false,\"path\":\"FRAIS › BOUCHERIE\"}]",
                    response.getEntity().toString());
        }
    }

    /**
     * A search by CODE answers several hits separated by a comma (the
     * {@code found > 0} arm on the second), skips the one that matches neither
     * field, and covers the pathless corners: a top node whose walk breaks on
     * the first level (empty path), an ancestor the catalog does NOT know (its
     * bare code stands in), and a hit whose label is null (the {@code null} arm
     * of {@code escape} and of the {@code pathOf} tail).
     */
    @Test
    void aSearchByCodeAnswersEveryHit() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily epicerie = node(itm, "20", 0, "EPICERIE");
        ProductFamily frais = node(itm, "10", 0, "FRAIS");
        ProductFamily conserves = node(itm, "2001", 1, "CONSERVES");
        ProductFamily nameless = node(itm, "2002", 1, "X");
        nameless.description = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "20", null);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(List.of(epicerie, frais, conserves, nameless));
            Response response = resource.search("ITM_FR", "20");
            assertEquals(200, response.getStatus());
            assertEquals("[{\"code\":\"20\",\"label\":\"EPICERIE\",\"level\":0,\"openable\":false,"
                    + "\"path\":\"EPICERIE\"},"
                    + "{\"code\":\"2001\",\"label\":\"CONSERVES\",\"level\":1,\"openable\":false,"
                    + "\"path\":\"20 › CONSERVES\"},"
                    + "{\"code\":\"2002\",\"label\":\"\",\"level\":1,\"openable\":false,"
                    + "\"path\":\"20 › \"}]", response.getEntity().toString());
        }
    }

    /**
     * The four ways a search answers nothing: an unknown scheme, a null scheme
     * code, a blank query and a null query — each leg of
     * {@code scheme == null || needle.isEmpty()} and of the two query-guarding
     * ternaries.
     */
    @Test
    void anEmptyOrSchemelessSearchAnswersAnEmptyArray() {
        AdminNomenclatureResource absent = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ABSENT", null);
            Response byAbsent = absent.search("ABSENT", "bou");
            assertEquals(200, byAbsent.getStatus());
            assertEquals("[]", byAbsent.getEntity().toString());
        }
        AdminNomenclatureResource nullScheme = newResource();
        assertEquals("[]", nullScheme.search(null, "bou").getEntity().toString());
        AdminNomenclatureResource blankQuery = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            assertEquals("[]", blankQuery.search("ITM_FR", "   ").getEntity().toString());
            assertEquals("[]", blankQuery.search("ITM_FR", null).getEntity().toString());
        }
    }

    /**
     * A search stops at {@code SEARCH_LIMIT} hits: past it a trailing comma is
     * left and the scan breaks, so exactly fifty paths are written even though
     * fifty-one nodes match — the {@code ++found > SEARCH_LIMIT} arm.
     */
    @Test
    void aSearchStopsAtTheHitLimit() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        List<ProductFamily> crowd = new ArrayList<>();
        for (int i = 0; i <= AdminNomenclatureResource.SEARCH_LIMIT; i++) {
            crowd.add(node(itm, String.format("%02d", i), 0, "NODE " + i));
        }
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubLevels(mocked, itm, 2, 4);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(crowd);
            Response response = resource.search("ITM_FR", "node");
            String body = response.getEntity().toString();
            assertEquals(200, response.getStatus());
            assertEquals(AdminNomenclatureResource.SEARCH_LIMIT,
                    body.split("\"path\":", -1).length - 1);
            assertTrue(body.endsWith("]"));
        }
    }

    // --------------------------------------------------
    // apiSaveNode, apiDeleteNode — the tree's JSON writes
    // --------------------------------------------------

    /**
     * The tree's create goes through the very same rule the form posts use and
     * answers a JSON verdict rather than redirecting — the {@code saving} arm of
     * {@code verdictJson}.
     */
    @Test
    void theTreeCreatesANodeAndAnswersJson() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS TRAD"));
            stubByCode(mocked, "1002", null);
            try (org.mockito.MockedConstruction<ProductFamily> made =
                         org.mockito.Mockito.mockConstruction(ProductFamily.class)) {
                Response response = resource.apiSaveNode(nodeForm("ITM_FR", "1002", "BOUCHERIE"));
                assertEquals(200, response.getStatus());
                assertTrue(jsonOk(response));
                assertEquals("Nœud « 1002 » créé.", jsonMessage(response));
            }
        }
    }

    /**
     * The tree's delete goes through {@code removeNode} and answers a JSON
     * verdict — the {@code !saving} arm of {@code verdictJson}.
     */
    @Test
    void theTreeDeletesANodeAndAnswersJson() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily leaf = spy(node(itm, "1002", 1, "BOUCHERIE"));
        leaf.products = null;
        doNothing().when(leaf).delete();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", leaf);
            stubLevelNodes(mocked, itm, 2, List.of());
            Response response = resource.apiDeleteNode(nodeForm("ITM_FR", "1002", null));
            assertEquals(200, response.getStatus());
            assertTrue(jsonOk(response));
            assertEquals("Nœud « 1002 » supprimé.", jsonMessage(response));
            verify(leaf).delete();
        }
    }

    /**
     * Both tree writes refuse an unknown scheme with a 404 JSON verdict: a
     * missing scheme field takes the {@code schemeCode.isEmpty()} arm and an
     * absent code takes the lookup arm.
     */
    @Test
    void theTreeWritesRefuseAnUnknownScheme() {
        AdminNomenclatureResource save = newResource();
        Response bare = save.apiSaveNode(nodeForm(null, "1002", "BOUCHERIE"));
        assertEquals(404, bare.getStatus());
        assertFalse(jsonOk(bare));
        assertEquals("Nomenclature introuvable.", jsonMessage(bare));
        AdminNomenclatureResource remove = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ABSENT", null);
            Response absent = remove.apiDeleteNode(nodeForm("ABSENT", "1002", null));
            assertEquals(404, absent.getStatus());
            assertFalse(jsonOk(absent));
        }
    }

    // --------------------------------------------------
    // NodeRow
    // --------------------------------------------------

    /**
     * A node row says whether anything hangs under it: a node with children is
     * openable, a leaf is not — the two arms of {@code isOpenable} — and its
     * getters read back the code, label and level it was built with.
     */
    @Test
    void aNodeRowSaysWhetherItIsOpenable() {
        AdminNomenclatureResource.NodeRow branch =
                new AdminNomenclatureResource.NodeRow("10", "FRAIS", 0, 3L);
        assertEquals("10", branch.getCode());
        assertEquals("FRAIS", branch.getLabel());
        assertEquals(0, branch.getLevel());
        assertEquals(3L, branch.getChildren());
        assertTrue(branch.isOpenable());
        AdminNomenclatureResource.NodeRow leaf =
                new AdminNomenclatureResource.NodeRow("1002", "BOUCHERIE", 1, 0L);
        assertFalse(leaf.isOpenable());
    }

    // --------------------------------------------------
    // Gap-fill: the still-red arms of the guards and loops
    // --------------------------------------------------

    /**
     * A delete posted with NO form at all reads its scheme through the null-form
     * arm of {@code value} and finds the scheme code empty — the
     * {@code form == null} arm of {@code value} and the
     * {@code schemeCode.isEmpty()} arm of {@code deleteNode} at once.
     */
    @Test
    void deletingWithNoFormIsRefusedThroughTheEmptyArms() {
        AdminNomenclatureResource resource = newResource();
        Response response = resource.deleteNode(null);
        assertFalse(ok(response));
        assertEquals("Nomenclature introuvable.", notice(response));
    }

    /**
     * A move posted with an empty scheme field takes the
     * {@code schemeCode.isEmpty()} arm and answers 404, and a move whose node
     * code is empty takes the {@code code.isEmpty()} arm of {@code moveNode}.
     */
    @Test
    void aMoveWithAnEmptySchemeOrEmptyCodeIsRefused() {
        AdminNomenclatureResource emptyScheme = newResource();
        Response bySchemeless = emptyScheme.apiMoveNode(moveForm(null, "1002", "20"));
        assertEquals(404, bySchemeless.getStatus());
        assertFalse(jsonOk(bySchemeless));
        AdminNomenclatureResource emptyCode = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            Response byCodeless = emptyCode.apiMoveNode(moveForm("ITM_FR", "", "20"));
            assertFalse(jsonOk(byCodeless));
            assertEquals("Nœud «  » introuvable.", jsonMessage(byCodeless));
        }
    }

    /**
     * The move's in-memory subtree scan skips a node whose code is null rather
     * than throwing — the {@code candidate.code != null} arm of
     * {@code subtreeOf} — and the move still goes through for the real subtree.
     */
    @Test
    void theSubtreeScanSkipsANullCodedNode() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily moved = spy(node(itm, "1002", 1, "BOUCHERIE"));
        doNothing().when(moved).persist();
        ProductFamily ghost = node(itm, "9999", 1, "SANS CODE");
        ghost.code = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "ITM_FR", itm);
            stubByCode(mocked, "1002", moved);
            stubByCode(mocked, "20", node(itm, "20", 0, "EPICERIE"));
            stubByCode(mocked, "2002", null);
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(java.util.Arrays.asList(moved, ghost));
            Response response = resource.apiMoveNode(moveForm("ITM_FR", "1002", "20"));
            assertTrue(jsonOk(response));
            assertEquals("2002", moved.code);
        }
    }

    /**
     * A search matches a node by its LABEL even when its code is null (the
     * {@code node.code != null} false arm of {@code matches}), and leaves out a
     * node that matches neither because its label is null (the
     * {@code node.description != null} false arm); the null-coded hit's path is
     * its label alone (the {@code node.code == null} arm of {@code pathOf}),
     * while a hit deeper than every level walks its whole ancestry (the
     * normal-completion arm of the {@code pathOf} loop).
     */
    @Test
    void aSearchReadsTheCornersOfMatchesAndPath() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily ghost = node(itm, "x", 0, "XENON");
        ghost.code = null;
        ProductFamily dumb = node(itm, "99", 0, "N");
        dumb.description = null;
        ProductFamily deep = node(itm, "100200", 2, "OXO");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubLevels(mocked, itm, 2, 4);
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS"));
            stubByCode(mocked, "1002", node(itm, "1002", 1, "BOUCHERIE"));
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by level, code",
                    itm.id)).thenReturn(java.util.Arrays.asList(ghost, dumb, deep));
            Response response = resource.search("ITM_FR", "x");
            assertEquals("[{\"code\":\"\",\"label\":\"XENON\",\"level\":0,\"openable\":false,"
                    + "\"path\":\"XENON\"},"
                    + "{\"code\":\"100200\",\"label\":\"OXO\",\"level\":2,\"openable\":false,"
                    + "\"path\":\"FRAIS › BOUCHERIE › OXO\"}]", response.getEntity().toString());
        }
    }

    /**
     * The page opened on a parent code of an undeclared length lists the top
     * level (the {@code placed == null} arm of {@code levelOf}) and, with a
     * parent longer than every level, its breadcrumb walks to the last level
     * without a break — the normal-completion arm of the {@code crumbs} loop.
     */
    @Test
    void aParentLongerThanEveryLevelWalksTheWholeBreadcrumb() {
        AdminNomenclatureResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubSchemes(mocked, List.of(itm));
            stubLevels(mocked, itm, 2, 4);
            stubLevelNodes(mocked, itm, 0, List.of());
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS"));
            stubByCode(mocked, "1002", node(itm, "1002", 1, "BOUCHERIE"));
            resource.nomenclaturesPage("ITM_FR", "100200", 1, null, true);
            verify(instance).data("parent", "100200");
            verify(instance).data("total", 0);
            verify(instance).data(eq("crumbs"), any());
        }
    }

    /**
     * On the page the level listing skips a node whose code is null when a
     * parent is opened (the {@code node.code != null} arm of {@code childrenOf})
     * and again when counting a node's children (the same arm of
     * {@code countChildren}), the real child being counted alongside.
     */
    @Test
    void theListingAndTheChildCountSkipNullCodedNodes() {
        AdminNomenclatureResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        Nomenclature itm = scheme("ITM_FR");
        ProductFamily bouch = node(itm, "1002", 1, "BOUCHERIE");
        ProductFamily ghost = node(itm, "1099", 1, "SANS CODE");
        ghost.code = null;
        ProductFamily crem = node(itm, "2001", 1, "CREMERIE");
        ProductFamily child = node(itm, "10020200", 2, "BOEUF");
        ProductFamily ghost2 = node(itm, "10020400", 2, "SANS CODE");
        ghost2.code = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubSchemes(mocked, List.of(itm));
            stubLevels(mocked, itm, 2, 4, 8);
            stubLevelNodes(mocked, itm, 1, java.util.Arrays.asList(bouch, ghost, crem));
            stubLevelNodes(mocked, itm, 2, java.util.Arrays.asList(child, ghost2));
            stubByCode(mocked, "10", node(itm, "10", 0, "FRAIS"));
            resource.nomenclaturesPage("ITM_FR", "10", 1, null, true);
            verify(instance).data("total", 1);
            verify(instance).data("parent", "10");
        }
    }

    /**
     * A scheme that declares no level at all still draws: the next-length hint
     * falls back on one (the {@code depth() == 0} arm of {@code nextLength}),
     * the scheme itself being non-null.
     */
    @Test
    void aSchemeWithoutLevelsProposesTheFirstLength() {
        AdminNomenclatureResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        Nomenclature neuf = scheme("NEUF");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubSchemes(mocked, List.of(neuf));
            stubLevels(mocked, neuf);
            stubLevelNodes(mocked, neuf, 0, List.of());
            resource.nomenclaturesPage("NEUF", null, 1, null, true);
            verify(instance).data("nextLength", 1);
            verify(instance).data("scheme", neuf);
        }
    }

    /**
     * The displayed level name and its custom flag read the level table: a
     * custom level flags the screen so (the {@code custom} true arm of
     * {@code isCustomLevel}), a level whose label is null and one whose label is
     * blank both fall back on a generic name (the two remaining arms of
     * {@code levelLabel}).
     */
    @Test
    void theDisplayedLevelReadsItsNameAndCustomFlag() {
        AdminNomenclatureResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        Nomenclature itm = scheme("ITM_FR");
        NomenclatureLevel rayon = new NomenclatureLevel();
        rayon.nomenclature = itm;
        rayon.rank = 0;
        rayon.label = "Rayon";
        rayon.codeLength = 2;
        rayon.custom = true;
        NomenclatureLevel nameless = new NomenclatureLevel();
        nameless.nomenclature = itm;
        nameless.rank = 1;
        nameless.label = null;
        nameless.codeLength = 4;
        NomenclatureLevel blank = new NomenclatureLevel();
        blank.nomenclature = itm;
        blank.rank = 2;
        blank.label = "  ";
        blank.codeLength = 8;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubSchemes(mocked, List.of(itm));
            mocked.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by rank",
                    itm.id)).thenReturn(List.of(rayon, nameless, blank));
            stubLevelNodes(mocked, itm, 0, List.of());
            resource.nomenclaturesPage("ITM_FR", null, 1, null, true);
            verify(instance).data("levelLabel", "Rayon");
            verify(instance).data("levelCustom", true);
        }
    }

    /**
     * A blank posted length is unusable exactly like an absent one: the
     * {@code posted.trim().isEmpty()} arm of {@code digits} refuses the whole
     * table.
     */
    @Test
    void aBlankLengthIsUnusable() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubLevels(mocked, itm, 2, 4);
            itm.reloadLevels();
            Response response = resource.saveLevels(levelForm("ITM_FR", "2", "   "));
            assertFalse(ok(response));
            assertEquals("Niveau 1 : le nombre de caractères doit être un entier d'au"
                    + " moins 1.", notice(response));
        }
    }

    /**
     * A blank posted rank is unusable exactly like an absent one: the
     * {@code posted.trim().isEmpty()} arm of {@code rank} leaves the lookup with
     * nothing and the removal is refused.
     */
    @Test
    void aBlankRankIsUnusable() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("scheme", "ITM_FR");
        form.putSingle("rank", "   ");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            Response response = resource.deleteLevel(form);
            assertFalse(ok(response));
            assertEquals("Niveau introuvable.", notice(response));
        }
    }

    /**
     * A level appended with NO label field falls back on its rank (the
     * {@code posted == null} arm of the label default in {@code addLevel}).
     */
    @Test
    void anAppendedLevelWithoutALabelFallsBackOnItsRank() {
        AdminNomenclatureResource resource = newResource();
        Nomenclature itm = scheme("ITM_FR");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("scheme", "ITM_FR");
        form.putSingle("length", "12");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubByCode(mocked, "ITM_FR", itm);
            stubLevels(mocked, itm, 2, 4, 8);
            try (MockedConstruction<NomenclatureLevel> created =
                    mockConstruction(NomenclatureLevel.class)) {
                Response response = resource.addLevel(form);
                assertTrue(ok(response));
                assertEquals("Niveau 3", created.constructed().get(0).label);
            }
        }
    }
}
