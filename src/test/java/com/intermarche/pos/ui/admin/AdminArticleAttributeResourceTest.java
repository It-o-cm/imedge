package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.ArticleAttributeDefinition;
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
 * Unit tests for {@link AdminArticleAttributeResource}, targeting 100% branch
 * coverage.
 * <p>
 * A Qute-backed CRUD resource over the {@link ArticleAttributeDefinition} static
 * finders, which resolve to {@link PanacheEntityBase} under plain
 * {@code mvn test} and are intercepted with {@link org.mockito.Mockito#mockStatic};
 * {@code persist()} on a created definition is neutralised with
 * {@link org.mockito.Mockito#mockConstruction}. No database is booted.
 * <p>
 * Branch enumeration: {@code save} covers the blank-code and blank-label
 * rejections, the malformed-id create arm, the unknown-id rejection, the
 * code-clash rejection and the self-code allowance, and the create and update
 * success arms; {@code delete} covers the absent, unknown and success arms.
 */
class AdminArticleAttributeResourceTest {

    /**
     * Builds a resource over a mocked list template.
     *
     * @return the wired resource
     */
    private AdminArticleAttributeResource newResource() {
        AdminArticleAttributeResource resource = new AdminArticleAttributeResource();
        resource.adminArticleAttributes = mock(Template.class);
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
     * Builds a definition with a fixed id, code and label.
     *
     * @param id the id
     * @param code the code
     * @param label the label
     * @return the definition
     */
    private ArticleAttributeDefinition def(Long id, String code, String label) {
        ArticleAttributeDefinition def = new ArticleAttributeDefinition();
        def.id = id;
        def.code = code;
        def.label = label;
        return def;
    }

    /**
     * {@code list} renders the ordered definitions with the notice fields.
     */
    @Test
    void listRendersDefinitions() {
        AdminArticleAttributeResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticleAttributes);
        ArticleAttributeDefinition def = def(1L, "ORGANIC", "Bio");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleAttributeDefinition.list("order by code"))
                    .thenReturn(List.of(def));
            assertEquals(instance, resource.list("saved", true));
        }
        verify(resource.adminArticleAttributes).data("definitions", List.of(def));
        verify(instance).data("notice", "saved");
        verify(instance).data("noticeOk", true);
    }

    /**
     * {@code save} rejects a blank code (code-empty arm of the guard).
     */
    @Test
    void saveRejectsBlankCode() {
        AdminArticleAttributeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("label", "Bio");
        Response response = resource.save(form);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} rejects a blank label (label-empty arm of the guard).
     */
    @Test
    void saveRejectsBlankLabel() {
        AdminArticleAttributeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("code", "ORGANIC");
        Response response = resource.save(form);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} rejects a posted id that matches no definition (id-non-null,
     * definition-null arm).
     */
    @Test
    void saveRejectsUnknownId() {
        AdminArticleAttributeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "7");
        form.putSingle("code", "ORGANIC");
        form.putSingle("label", "Bio");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleAttributeDefinition.findById(7L)).thenReturn(null);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code save} rejects a code already carried by another definition
     * (clash-non-null, distinct-entity arm).
     */
    @Test
    void saveRejectsCodeClash() {
        AdminArticleAttributeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("code", "organic");
        form.putSingle("label", "Bio");
        ArticleAttributeDefinition other = def(3L, "ORGANIC", "Autre");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ArticleAttributeDefinition> clash = query(other);
            panache.when(() -> ArticleAttributeDefinition.find("code", "ORGANIC")).thenReturn(clash);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code save} creates a definition when no id is posted and no clash exists
     * (id-null create arm, persist), from a malformed nothing — here a plain
     * create.
     */
    @Test
    void saveCreates() {
        AdminArticleAttributeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("code", "  organic  ");
        form.putSingle("label", "  Bio  ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ArticleAttributeDefinition> noClash = query(null);
            panache.when(() -> ArticleAttributeDefinition.find("code", "ORGANIC")).thenReturn(noClash);
            try (MockedConstruction<ArticleAttributeDefinition> construction =
                         mockConstruction(ArticleAttributeDefinition.class)) {
                Response response = resource.save(form);
                assertEquals(303, response.getStatus());
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                ArticleAttributeDefinition built = construction.constructed().get(0);
                assertEquals("ORGANIC", built.code);
                assertEquals("Bio", built.label);
                verify(built).persist();
            }
        }
    }

    /**
     * {@code save} with a malformed id falls through to a create (parseId
     * catch-arm), and no update path is taken.
     */
    @Test
    void saveWithMalformedIdCreates() {
        AdminArticleAttributeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "abc");
        form.putSingle("code", "ORGANIC");
        form.putSingle("label", "Bio");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ArticleAttributeDefinition> noClash = query(null);
            panache.when(() -> ArticleAttributeDefinition.find("code", "ORGANIC")).thenReturn(noClash);
            try (MockedConstruction<ArticleAttributeDefinition> construction =
                         mockConstruction(ArticleAttributeDefinition.class)) {
                Response response = resource.save(form);
                assertEquals(303, response.getStatus());
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                verify(construction.constructed().get(0)).persist();
            }
        }
    }

    /**
     * {@code save} with a blank id falls through to a create (parseId isBlank
     * arm).
     */
    @Test
    void saveWithBlankIdCreates() {
        AdminArticleAttributeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "   ");
        form.putSingle("code", "ORGANIC");
        form.putSingle("label", "Bio");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ArticleAttributeDefinition> noClash = query(null);
            panache.when(() -> ArticleAttributeDefinition.find("code", "ORGANIC")).thenReturn(noClash);
            try (MockedConstruction<ArticleAttributeDefinition> construction =
                         mockConstruction(ArticleAttributeDefinition.class)) {
                Response response = resource.save(form);
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                verify(construction.constructed().get(0)).persist();
            }
        }
    }

    /**
     * {@code save} updates an existing definition when its id resolves and the
     * code is unchanged (id-valid found arm, self-code allowance, update arm).
     */
    @Test
    void saveUpdatesExisting() {
        AdminArticleAttributeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "5");
        form.putSingle("code", "ORGANIC");
        form.putSingle("label", "Bio revu");
        ArticleAttributeDefinition existing = def(5L, "ORGANIC", "Bio");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ArticleAttributeDefinition> self = query(existing);
            panache.when(() -> ArticleAttributeDefinition.findById(5L)).thenReturn(existing);
            panache.when(() -> ArticleAttributeDefinition.find("code", "ORGANIC")).thenReturn(self);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("Bio revu", existing.label);
    }

    /**
     * {@code delete} redirects red when the id resolves to nothing (definition
     * null arm).
     */
    @Test
    void deleteRejectsUnknown() {
        AdminArticleAttributeResource resource = newResource();
        Response response = resource.delete(new MultivaluedHashMap<>());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code delete} removes the definition and redirects green (found arm).
     */
    @Test
    void deleteRemovesDefinition() {
        AdminArticleAttributeResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "5");
        ArticleAttributeDefinition existing = mock(ArticleAttributeDefinition.class);
        existing.code = "ORGANIC";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleAttributeDefinition.findById(5L)).thenReturn(existing);
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
    private PanacheQuery<ArticleAttributeDefinition> query(ArticleAttributeDefinition result) {
        PanacheQuery<ArticleAttributeDefinition> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }
}
