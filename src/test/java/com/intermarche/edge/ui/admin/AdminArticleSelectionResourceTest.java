package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.ArticleSelection;
import com.intermarche.edge.service.NodeEchelonService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminArticleSelectionResource}, targeting 100% branch
 * coverage.
 * <p>
 * A Qute-backed CRUD resource over the {@link ArticleSelection} static finders,
 * which resolve to {@link PanacheEntityBase} under plain {@code mvn test} and
 * are intercepted with {@link org.mockito.Mockito#mockStatic}; {@code persist()}
 * on a created selection is neutralised with
 * {@link org.mockito.Mockito#mockConstruction}. No database is booted.
 * <p>
 * Branch enumeration: {@code list} covers the name filter (blank, matching,
 * not matching) and the edited selection (absent, found); {@code currentPdv}
 * the service absent and present arms; {@code save} the blank-name rejection,
 * the malformed-id create arm, the unknown-id rejection, the name-clash
 * rejection and the self-name allowance, and the create and update success
 * arms; {@code duplicate} the unknown, default-name, posted-name and clash
 * arms; {@code delete} the absent, unknown and success arms.
 */
class AdminArticleSelectionResourceTest {

    /** The JPQL of a point of sale's own list of a given name. */
    private static final String OWN = "name = ?1 and pdvCode = ?2";

    /** The JPQL of a shared list of a given name. */
    private static final String SHARED = "name = ?1 and pdvCode is null";

    /**
     * Builds a resource over a mocked list template, without a node service.
     *
     * @return the wired resource
     */
    private AdminArticleSelectionResource newResource() {
        AdminArticleSelectionResource resource = new AdminArticleSelectionResource();
        resource.adminArticleSelections = mock(Template.class);
        return resource;
    }

    /**
     * Builds a resource whose node is the given point of sale.
     *
     * @param pdv the point of sale
     * @return the wired resource
     */
    private AdminArticleSelectionResource resourceAt(String pdv) {
        AdminArticleSelectionResource resource = newResource();
        resource.nodeEchelonService = mock(NodeEchelonService.class);
        when(resource.nodeEchelonService.currentPdv()).thenReturn(pdv);
        return resource;
    }

    /**
     * Wires a template's chained {@code data(...)} to one self-returning instance.
     *
     * @param template the template mock to wire
     * @return the instance the chain returns
     */
    private TemplateInstance wire(Template template) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a selection with a fixed id, name and criteria.
     *
     * @param id the id
     * @param name the name
     * @param criteria the stored criteria
     * @return the selection
     */
    private ArticleSelection sel(Long id, String name, String criteria) {
        ArticleSelection selection = new ArticleSelection();
        selection.id = id;
        selection.name = name;
        selection.criteria = criteria;
        return selection;
    }

    /**
     * {@code list} renders the shared selections when no name filter is typed
     * and no selection is edited (blank-filter and edit-absent arms).
     */
    @Test
    void listRendersSelections() {
        AdminArticleSelectionResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticleSelections);
        ArticleSelection selection = sel(1L, "Épicerie", "type=UNIT");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.list("pdvCode is null order by name")).thenReturn(List.of(selection));
            assertEquals(instance, resource.list("saved", true, null, null));
        }
        verify(resource.adminArticleSelections).data("selections", List.of(selection));
        verify(instance).data(eq("criteriaViews"), any());
        verify(instance).data("edited", null);
        verify(instance).data("notice", "saved");
        verify(instance).data("noticeOk", true);
    }

    /**
     * {@code list} keeps only the point of sale's lists whose name contains the
     * typed text, and prefills the edited one (matching, not-matching and
     * edit-found arms).
     */
    @Test
    void listFiltersByNameAndEdits() {
        AdminArticleSelectionResource resource = resourceAt("PDV1");
        TemplateInstance instance = wire(resource.adminArticleSelections);
        ArticleSelection kept = sel(1L, "Épicerie fine", "type=UNIT");
        ArticleSelection dropped = sel(2L, "Bazar", "");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.list("pdvCode is null or pdvCode = ?1 order by name", "PDV1"))
                    .thenReturn(List.of(kept, dropped));
            panache.when(() -> ArticleSelection.findById(1L)).thenReturn(kept);
            assertEquals(instance, resource.list(null, false, " FINE ", 1L));
        }
        verify(resource.adminArticleSelections).data("selections", List.of(kept));
        verify(instance).data("edited", kept);
        verify(instance).data("q", "FINE");
        verify(instance).data("pdv", "PDV1");
    }

    /**
     * {@code currentPdv} answers null without a node service and the node's
     * point of sale with one.
     */
    @Test
    void currentPdvFollowsTheNode() {
        assertNull(newResource().currentPdv());
        assertEquals("PDV9", resourceAt("PDV9").currentPdv());
    }

    /**
     * {@code save} rejects a blank name (name-empty arm of the guard).
     */
    @Test
    void saveRejectsBlankName() {
        AdminArticleSelectionResource resource = newResource();
        Response response = resource.save(new MultivaluedHashMap<>());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} rejects a posted id matching no selection (id-non-null,
     * selection-null arm).
     */
    @Test
    void saveRejectsUnknownId() {
        AdminArticleSelectionResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "7");
        form.putSingle("name", "Épicerie");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.findById(7L)).thenReturn(null);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code save} rejects a name already carried by another selection of the
     * point of sale (clash-non-null, distinct-entity arm).
     */
    @Test
    void saveRejectsNameClash() {
        AdminArticleSelectionResource resource = resourceAt("PDV1");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("name", "Épicerie");
        ArticleSelection other = sel(3L, "Épicerie", "");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ArticleSelection> clash = query(other);
            panache.when(() -> ArticleSelection.find(OWN, "Épicerie", "PDV1")).thenReturn(clash);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code save} creates a selection of the point of sale with the
     * normalised criteria query string (id-null create arm, persist).
     */
    @Test
    void saveCreates() {
        AdminArticleSelectionResource resource = resourceAt("PDV1");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("name", "  Pesés actifs  ");
        form.putSingle("type", "WEIGHT");
        form.putSingle("status", "ACTIVE");
        form.putSingle("priceMax", "5");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.find(OWN, "Pesés actifs", "PDV1")).thenReturn(query(null));
            panache.when(() -> ArticleSelection.find(SHARED, "Pesés actifs")).thenReturn(query(null));
            try (MockedConstruction<ArticleSelection> construction =
                         mockConstruction(ArticleSelection.class)) {
                Response response = resource.save(form);
                assertEquals(303, response.getStatus());
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                ArticleSelection built = construction.constructed().get(0);
                assertEquals("Pesés actifs", built.name);
                assertEquals("type=WEIGHT&status=ACTIVE&priceMax=5", built.criteria);
                assertEquals("PDV1", built.pdvCode);
                verify(built).persist();
            }
        }
    }

    /**
     * {@code save} with a malformed id falls through to a create (parseId
     * catch-arm).
     */
    @Test
    void saveWithMalformedIdCreates() {
        AdminArticleSelectionResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "abc");
        form.putSingle("name", "Tous");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.find(SHARED, "Tous")).thenReturn(query(null));
            try (MockedConstruction<ArticleSelection> construction =
                         mockConstruction(ArticleSelection.class)) {
                Response response = resource.save(form);
                assertEquals(303, response.getStatus());
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                ArticleSelection built = construction.constructed().get(0);
                assertEquals("", built.criteria);
                verify(built).persist();
            }
        }
    }

    /**
     * {@code save} with a blank id falls through to a create (parseId isBlank
     * arm).
     */
    @Test
    void saveWithBlankIdCreates() {
        AdminArticleSelectionResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "   ");
        form.putSingle("name", "Tous");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.find(SHARED, "Tous")).thenReturn(query(null));
            try (MockedConstruction<ArticleSelection> construction =
                         mockConstruction(ArticleSelection.class)) {
                Response response = resource.save(form);
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                verify(construction.constructed().get(0)).persist();
            }
        }
    }

    /**
     * {@code save} updates an existing selection when its id resolves and the
     * name is unchanged (id-valid found arm, self-name allowance, update arm).
     */
    @Test
    void saveUpdatesExisting() {
        AdminArticleSelectionResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "5");
        form.putSingle("name", "Épicerie");
        form.putSingle("forbidden", "yes");
        ArticleSelection existing = sel(5L, "Épicerie", "");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.findById(5L)).thenReturn(existing);
            panache.when(() -> ArticleSelection.find(SHARED, "Épicerie")).thenReturn(query(existing));
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("forbidden=yes", existing.criteria);
    }

    /**
     * {@code duplicate} refuses an id that names no selection (unknown arm).
     */
    @Test
    void duplicateRejectsUnknown() {
        Response response = newResource().duplicate(new MultivaluedHashMap<>());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code duplicate} copies the criteria under "Copie de" the original's name
     * when no name is posted, for the node's point of sale (default-name arm).
     */
    @Test
    void duplicateCopiesUnderDefaultName() {
        AdminArticleSelectionResource resource = resourceAt("PDV1");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "5");
        ArticleSelection original = sel(5L, "Épicerie", "priceMin=1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.findById(5L)).thenReturn(original);
            panache.when(() -> ArticleSelection.find(OWN, "Copie de Épicerie", "PDV1")).thenReturn(query(null));
            panache.when(() -> ArticleSelection.find(SHARED, "Copie de Épicerie")).thenReturn(query(null));
            try (MockedConstruction<ArticleSelection> construction = mockConstruction(ArticleSelection.class)) {
                Response response = resource.duplicate(form);
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                ArticleSelection copy = construction.constructed().get(0);
                assertEquals("Copie de Épicerie", copy.name);
                assertEquals("priceMin=1", copy.criteria);
                assertEquals("PDV1", copy.pdvCode);
                verify(copy).persist();
            }
        }
    }

    /**
     * {@code duplicate} refuses a posted name another list already carries
     * (posted-name and clash arms).
     */
    @Test
    void duplicateRejectsClashingName() {
        AdminArticleSelectionResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "5");
        form.putSingle("name", "Bazar");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.findById(5L)).thenReturn(sel(5L, "Épicerie", ""));
            panache.when(() -> ArticleSelection.find(eq(SHARED), eq("Bazar"))).thenReturn(query(sel(6L, "Bazar", "")));
            Response response = resource.duplicate(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code delete} redirects red when the id resolves to nothing (selection
     * null arm).
     */
    @Test
    void deleteRejectsUnknown() {
        AdminArticleSelectionResource resource = newResource();
        Response response = resource.delete(new MultivaluedHashMap<>());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code delete} removes the selection and redirects green (found arm).
     */
    @Test
    void deleteRemovesSelection() {
        AdminArticleSelectionResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "5");
        ArticleSelection existing = mock(ArticleSelection.class);
        existing.name = "Épicerie";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.findById(5L)).thenReturn(existing);
            Response response = resource.delete(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        verify(existing).delete();
    }

    /**
     * Builds a single-result Panache query mock.
     *
     * @param result the first result to return, possibly null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    /**
     * BO-02-03-31: a stored perimeter reads back as labelled criteria: a known
     * name gets its label key, a coded value its value key, an attribute its
     * label, an encoded value is decoded, and an unknown name, an unknown
     * attribute code and a name without value are kept as they are; empty
     * pairs are skipped.
     */
    @Test
    void describeLabelsEveryStoredCriterion() {
        List<AdminArticleSelectionResource.CriterionView> views = AdminArticleSelectionResource.describe(
                "status=ACTIVE&&brand=Les+Bons&attr=BULKY&attr=ZZZ&mystery=1&forbidden=no&priceMin");
        assertEquals(7, views.size());
        assertEquals("t.statut", views.get(0).labelKey);
        assertEquals("t.actifs", views.get(0).valueKey);
        assertEquals("t.marque", views.get(1).labelKey);
        assertEquals("Les Bons", views.get(1).value);
        assertNull(views.get(1).valueKey);
        assertEquals("t.attribut", views.get(2).labelKey);
        assertEquals(com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog.def("BULKY").label(),
                views.get(2).value);
        assertEquals("ZZZ", views.get(3).value);
        assertNull(views.get(4).labelKey);
        assertEquals("mystery", views.get(4).name);
        assertEquals("t.autorisees", views.get(5).valueKey);
        assertEquals("t.p20-prix-de", views.get(6).labelKey);
        assertEquals("", views.get(6).value);
    }

    /**
     * A list taking every article, stored as null or blank, has no criterion
     * to show.
     */
    @Test
    void describeOfAnOpenListIsEmpty() {
        assertTrue(AdminArticleSelectionResource.describe(null).isEmpty());
        assertTrue(AdminArticleSelectionResource.describe("  ").isEmpty());
    }

    /**
     * Builds a query mock answering the given first result.
     *
     * @param result the first result
     * @return the query mock
     */
    private PanacheQuery<ArticleSelection> query(ArticleSelection result) {
        return mock(PanacheQuery.class, invocation ->
                "firstResult".equals(invocation.getMethod().getName()) ? result : null);
    }
}
