package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.ArticleSelection;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * Branch enumeration: {@code save} covers the blank-name rejection, the
 * malformed-id create arm, the unknown-id rejection, the name-clash rejection
 * and the self-name allowance, and the create and update success arms;
 * {@code delete} covers the absent, unknown and success arms.
 */
class AdminArticleSelectionResourceTest {

    /**
     * Builds a resource over a mocked list template.
     *
     * @return the wired resource
     */
    private AdminArticleSelectionResource newResource() {
        AdminArticleSelectionResource resource = new AdminArticleSelectionResource();
        resource.adminArticleSelections = mock(Template.class);
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
     * {@code list} renders the ordered selections with the option catalogs and
     * the notice fields.
     */
    @Test
    void listRendersSelections() {
        AdminArticleSelectionResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticleSelections);
        ArticleSelection selection = sel(1L, "Épicerie", "type=UNIT");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.list("order by name")).thenReturn(List.of(selection));
            assertEquals(instance, resource.list("saved", true));
        }
        verify(resource.adminArticleSelections).data("selections", List.of(selection));
        verify(instance).data("notice", "saved");
        verify(instance).data("noticeOk", true);
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
     * {@code save} rejects a name already carried by another selection
     * (clash-non-null, distinct-entity arm).
     */
    @Test
    void saveRejectsNameClash() {
        AdminArticleSelectionResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("name", "Épicerie");
        ArticleSelection other = sel(3L, "Épicerie", "");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ArticleSelection> clash = query(other);
            panache.when(() -> ArticleSelection.find("name", "Épicerie")).thenReturn(clash);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code save} creates a selection with the normalised criteria query string
     * when no id is posted and no clash exists (id-null create arm, persist).
     */
    @Test
    void saveCreates() {
        AdminArticleSelectionResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("name", "  Pesés actifs  ");
        form.putSingle("type", "WEIGHT");
        form.putSingle("status", "ACTIVE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ArticleSelection> noClash = query(null);
            panache.when(() -> ArticleSelection.find("name", "Pesés actifs")).thenReturn(noClash);
            try (MockedConstruction<ArticleSelection> construction =
                         mockConstruction(ArticleSelection.class)) {
                Response response = resource.save(form);
                assertEquals(303, response.getStatus());
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                ArticleSelection built = construction.constructed().get(0);
                assertEquals("Pesés actifs", built.name);
                assertEquals("type=WEIGHT&status=ACTIVE", built.criteria);
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
            PanacheQuery<ArticleSelection> noClash = query(null);
            panache.when(() -> ArticleSelection.find("name", "Tous")).thenReturn(noClash);
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
            PanacheQuery<ArticleSelection> noClash = query(null);
            panache.when(() -> ArticleSelection.find("name", "Tous")).thenReturn(noClash);
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
            PanacheQuery<ArticleSelection> self = query(existing);
            panache.when(() -> ArticleSelection.findById(5L)).thenReturn(existing);
            panache.when(() -> ArticleSelection.find("name", "Épicerie")).thenReturn(self);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("forbidden=yes", existing.criteria);
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
    private PanacheQuery<ArticleSelection> query(ArticleSelection result) {
        PanacheQuery<ArticleSelection> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }
}
