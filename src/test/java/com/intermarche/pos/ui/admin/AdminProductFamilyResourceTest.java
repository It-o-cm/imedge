package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Unit tests for {@link AdminProductFamilyResource}, targeting 100% branch
 * coverage.
 * <p>
 * A Qute-backed admin resource over the {@link ProductFamily} tree. The
 * inherited Panache statics ({@code find}, {@code findById}, {@code count})
 * resolve to {@link PanacheEntityBase} under plain {@code mvn test} and are
 * intercepted with {@link org.mockito.Mockito#mockStatic}; the declared finders
 * {@code Product.findByEan/findByInternalCode/findByPlu} and
 * {@code ProductFamily.findByCode} are intercepted on their own class; a
 * constructed family's {@code persist()} is neutralised with
 * {@link org.mockito.Mockito#mockConstruction}. No database is booted.
 * <p>
 * Branch enumeration is documented on each test; every compound guard leg,
 * ternary arm and null guard of {@code save}, {@code delete}, {@code duplicate},
 * {@code addArticles}, {@code addAtLevelZero}, {@code removeArticle},
 * {@code toggleArticle} and the {@code collectProducts}/{@code resolveProduct}
 * helpers is covered on both arms.
 */
class AdminProductFamilyResourceTest {

    /**
     * Builds a resource over a mocked template.
     *
     * @return the wired resource
     */
    private AdminProductFamilyResource newResource() {
        AdminProductFamilyResource resource = new AdminProductFamilyResource();
        resource.adminArticleGroups = mock(Template.class);
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
     * Builds a real family with the given id and code and an empty member set.
     *
     * @param id the entity id, possibly null
     * @param code the family code
     * @return the configured family
     */
    private ProductFamily family(Long id, String code) {
        ProductFamily f = new ProductFamily();
        f.id = id;
        f.code = code;
        return f;
    }

    /**
     * Builds a real product with the given id, EAN and name (active by default).
     *
     * @param id the product id
     * @param ean the EAN, possibly null
     * @param name the commercial name
     * @return the configured product
     */
    private Product product(Long id, String ean, String name) {
        Product p = new Product();
        p.id = id;
        p.ean = ean;
        p.name = name;
        return p;
    }

    /**
     * Builds a single-result Panache query mock.
     *
     * @param result the first result to return, possibly null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<ProductFamily> firstQuery(ProductFamily result) {
        PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Builds a list-returning Panache query mock.
     *
     * @param results the list to return
     * @param <T> the entity type
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> listQuery(List<T> results) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(results);
        return query;
    }

    /**
     * Builds a form pre-filled from alternating key/value pairs.
     *
     * @param pairs the alternating keys and values
     * @return the populated form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.putSingle(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    // ----------------------------------------------------------------
    // list
    // ----------------------------------------------------------------

    /**
     * {@code list} with no edit id renders each group with its parent code (null
     * arm for a root, non-null arm for a child) and no edit panel.
     */
    @Test
    void listRendersTreeWithoutPanel() {
        AdminProductFamilyResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticleGroups);
        ProductFamily root = family(1L, "RACINE");
        ProductFamily child = family(2L, "ENFANT");
        child.products.add(product(9L, "1", "x"));
        ProductFamily unsaved = family(null, "SANS-ID");
        PanacheQuery<ProductFamily> all = listQuery(List.of(root, child, unsaved));
        PanacheQuery<ProductFamily> noParent = firstQuery(null);
        PanacheQuery<ProductFamily> childParent = firstQuery(root);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.find("order by code")).thenReturn(all);
            panache.when(() -> ProductFamily.find(
                    "select p from ProductFamily p join p.productFamilies c where c.id = ?1", 1L))
                    .thenReturn(noParent);
            panache.when(() -> ProductFamily.find(
                    "select p from ProductFamily p join p.productFamilies c where c.id = ?1", 2L))
                    .thenReturn(childParent);
            assertEquals(instance, resource.list("hello", true, null));
        }
        verify(instance).data("selected", null);
        verify(instance).data("members", List.of());
        verify(instance).data("notice", "hello");
        verify(instance).data("noticeOk", true);
    }

    /**
     * BO-03-01-01: the READ half of the CRUD — the rows handed to the template
     * really describe the tree.
     *
     * <p>The other {@code list} cases only assert the edit panel, so a
     * {@code list} that handed over an empty list, or rows without their parent
     * or member count, would leave them green. This one captures the
     * {@code groups} payload itself: three rows in code order, the child naming
     * its parent and counting its single member, the root naming none.
     */
    @Test
    void listHandsTheTemplateTheWholeTree() {
        AdminProductFamilyResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticleGroups);
        ProductFamily root = family(1L, "RACINE");
        ProductFamily child = family(2L, "ENFANT");
        child.products.add(product(9L, "1", "x"));
        ProductFamily unsaved = family(null, "SANS-ID");
        PanacheQuery<ProductFamily> all = listQuery(List.of(root, child, unsaved));
        PanacheQuery<ProductFamily> noParent = firstQuery(null);
        PanacheQuery<ProductFamily> childParent = firstQuery(root);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.find("order by code")).thenReturn(all);
            panache.when(() -> ProductFamily.find(
                    "select p from ProductFamily p join p.productFamilies c where c.id = ?1", 1L))
                    .thenReturn(noParent);
            panache.when(() -> ProductFamily.find(
                    "select p from ProductFamily p join p.productFamilies c where c.id = ?1", 2L))
                    .thenReturn(childParent);
            resource.list(null, true, null);
        }
        ArgumentCaptor<List<AdminProductFamilyResource.GroupRow>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(resource.adminArticleGroups).data(eq("groups"), captor.capture());
        List<AdminProductFamilyResource.GroupRow> rows = captor.getValue();
        assertEquals(3, rows.size());
        assertEquals("RACINE", rows.get(0).code);
        assertNull(rows.get(0).parentCode);
        assertEquals(0, rows.get(0).memberCount);
        assertEquals("ENFANT", rows.get(1).code);
        assertEquals("RACINE", rows.get(1).parentCode);
        assertEquals(1, rows.get(1).memberCount);
        assertEquals("SANS-ID", rows.get(2).code);
    }

    /**
     * {@code list} with an edit id resolving to a group renders its edit panel
     * with the members sorted by EAN (null-EAN and non-null-EAN arms of the
     * sort key).
     */
    @Test
    void listRendersSelectedPanel() {
        AdminProductFamilyResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticleGroups);
        ProductFamily selected = family(5L, "EPICERIE");
        Product noEan = product(11L, null, "sans ean");
        Product withEan = product(12L, "999", "avec ean");
        selected.products.add(withEan);
        selected.products.add(noEan);
        PanacheQuery<ProductFamily> all = listQuery(List.of(selected));
        PanacheQuery<ProductFamily> noParent = firstQuery(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.find("order by code")).thenReturn(all);
            panache.when(() -> ProductFamily.find(
                    "select p from ProductFamily p join p.productFamilies c where c.id = ?1", 5L))
                    .thenReturn(noParent);
            panache.when(() -> ProductFamily.findById(5L)).thenReturn(selected);
            assertEquals(instance, resource.list(null, false, 5L));
        }
        verify(instance).data("selected", selected);
        verify(instance).data("members", List.of(noEan, withEan));
    }

    /**
     * {@code list} with an edit id resolving to nothing renders no panel (edit id
     * non-null but {@code findById} null).
     */
    @Test
    void listEditUnknownShowsNoPanel() {
        AdminProductFamilyResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticleGroups);
        PanacheQuery<ProductFamily> all = listQuery(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.find("order by code")).thenReturn(all);
            panache.when(() -> ProductFamily.findById(99L)).thenReturn(null);
            assertEquals(instance, resource.list(null, true, 99L));
        }
        verify(instance).data("selected", null);
        verify(instance).data("members", List.of());
    }

    // ----------------------------------------------------------------
    // save — create
    // ----------------------------------------------------------------

    /**
     * {@code save} rejects a blank code on creation (code-empty arm).
     */
    @Test
    void createRejectsBlankCode() {
        AdminProductFamilyResource resource = newResource();
        Response response = resource.save(form("code", "   "));
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} rejects a code already used (count-positive arm).
     */
    @Test
    void createRejectsDuplicateCode() {
        AdminProductFamilyResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.count("code", "FRUITS")).thenReturn(1L);
            Response response = resource.save(form("code", "FRUITS"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code save} creates a root group when no parent is posted (parent-null arm)
     * and persists it.
     */
    @Test
    void createRootGroup() {
        AdminProductFamilyResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.count("code", "FRUITS")).thenReturn(0L);
            try (MockedConstruction<ProductFamily> construction = mockConstruction(ProductFamily.class)) {
                Response response = resource.save(form("code", "FRUITS", "description", "Fruits"));
                assertEquals(303, response.getStatus());
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                ProductFamily built = construction.constructed().get(0);
                assertEquals("FRUITS", built.code);
                assertEquals("Fruits", built.description);
                verify(built).persist();
            }
        }
    }

    /**
     * {@code save} rejects an unknown parent on creation (parent-non-null,
     * parent-not-found arm).
     */
    @Test
    void createRejectsUnknownParent() {
        AdminProductFamilyResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.count("code", "POMMES")).thenReturn(0L);
            panache.when(() -> ProductFamily.findById(7L)).thenReturn(null);
            try (MockedConstruction<ProductFamily> construction = mockConstruction(ProductFamily.class)) {
                Response response = resource.save(form("code", "POMMES", "parentId", "7"));
                assertEquals(303, response.getStatus());
                assertTrue(response.getLocation().toString().contains("noticeOk=false"));
                verify(construction.constructed().get(0)).persist();
            }
        }
    }

    /**
     * {@code save} creates a child group under a resolved parent (parent-found
     * arm), attaching it to the parent's sub-families.
     */
    @Test
    void createChildGroup() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily parent = family(7L, "ALIMENTAIRE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.count("code", "POMMES")).thenReturn(0L);
            panache.when(() -> ProductFamily.findById(7L)).thenReturn(parent);
            try (MockedConstruction<ProductFamily> construction = mockConstruction(ProductFamily.class)) {
                Response response = resource.save(form("code", "POMMES", "parentId", "7"));
                assertEquals(303, response.getStatus());
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                ProductFamily built = construction.constructed().get(0);
                assertTrue(parent.productFamilies.contains(built));
            }
        }
    }

    // ----------------------------------------------------------------
    // save — update
    // ----------------------------------------------------------------

    /**
     * {@code save} rejects an unknown id on update (family-not-found arm).
     */
    @Test
    void updateRejectsUnknownId() {
        AdminProductFamilyResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(3L)).thenReturn(null);
            Response response = resource.save(form("id", "3"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code save} updates the description without touching the tree when no
     * parent is posted (parent-null arm of update).
     */
    @Test
    void updateDescriptionOnly() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily existing = family(3L, "FRUITS");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(3L)).thenReturn(existing);
            Response response = resource.save(form("id", "3", "description", "Fruits frais"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("Fruits frais", existing.description);
    }

    /**
     * {@code save} clears the description when a blank value is posted
     * (blankToNull empty-arm).
     */
    @Test
    void updateBlankDescriptionClears() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily existing = family(3L, "FRUITS");
        existing.description = "Old";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(3L)).thenReturn(existing);
            Response response = resource.save(form("id", "3", "description", "   "));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals(null, existing.description);
    }

    /**
     * {@code save} rejects a group set as its own parent (parent-equals-self arm).
     */
    @Test
    void updateRejectsSelfParent() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily existing = family(3L, "FRUITS");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(3L)).thenReturn(existing);
            Response response = resource.save(form("id", "3", "parentId", "3"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code save} rejects an unknown parent on re-parent (parent-not-found arm of
     * update).
     */
    @Test
    void updateRejectsUnknownParent() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily existing = family(3L, "FRUITS");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(3L)).thenReturn(existing);
            panache.when(() -> ProductFamily.findById(8L)).thenReturn(null);
            Response response = resource.save(form("id", "3", "parentId", "8"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code save} re-parents a root group (current-parent-null arm): it attaches
     * to the new parent with no prior detach.
     */
    @Test
    void updateReparentsRoot() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily existing = family(3L, "POMMES");
        ProductFamily newParent = family(8L, "FRUITS");
        PanacheQuery<ProductFamily> noParent = firstQuery(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(3L)).thenReturn(existing);
            panache.when(() -> ProductFamily.findById(8L)).thenReturn(newParent);
            panache.when(() -> ProductFamily.find(
                    "select p from ProductFamily p join p.productFamilies c where c.id = ?1", 3L))
                    .thenReturn(noParent);
            Response response = resource.save(form("id", "3", "parentId", "8"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(newParent.productFamilies.contains(existing));
        }
    }

    /**
     * {@code save} re-parents a child group (current-parent-non-null arm): it
     * detaches from the old parent before attaching to the new one.
     */
    @Test
    void updateReparentsChild() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily existing = family(3L, "POMMES");
        ProductFamily oldParent = family(4L, "LEGUMES");
        oldParent.productFamilies.add(existing);
        ProductFamily newParent = family(8L, "FRUITS");
        PanacheQuery<ProductFamily> withParent = firstQuery(oldParent);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(3L)).thenReturn(existing);
            panache.when(() -> ProductFamily.findById(8L)).thenReturn(newParent);
            panache.when(() -> ProductFamily.find(
                    "select p from ProductFamily p join p.productFamilies c where c.id = ?1", 3L))
                    .thenReturn(withParent);
            Response response = resource.save(form("id", "3", "parentId", "8"));
            assertEquals(303, response.getStatus());
            assertFalse(oldParent.productFamilies.contains(existing));
            assertTrue(newParent.productFamilies.contains(existing));
        }
    }

    // ----------------------------------------------------------------
    // delete
    // ----------------------------------------------------------------

    /**
     * {@code delete} rejects an absent id (id-null arm).
     */
    @Test
    void deleteRejectsNullId() {
        AdminProductFamilyResource resource = newResource();
        Response response = resource.delete(new MultivaluedHashMap<>());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code delete} rejects an id resolving to nothing (id-non-null,
     * family-null arm).
     */
    @Test
    void deleteRejectsUnknownId() {
        AdminProductFamilyResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(6L)).thenReturn(null);
            Response response = resource.delete(form("id", "6"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code delete} removes a resolved group (found arm).
     */
    @Test
    void deleteRemovesGroup() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily existing = mock(ProductFamily.class);
        existing.code = "FRUITS";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(6L)).thenReturn(existing);
            Response response = resource.delete(form("id", "6"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        verify(existing).delete();
    }

    /**
     * {@code delete} treats a blank id as absent (parseId blank-arm), rejecting
     * with no lookup.
     */
    @Test
    void deleteTreatsBlankIdAsAbsent() {
        AdminProductFamilyResource resource = newResource();
        Response response = resource.delete(form("id", "   "));
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code delete} treats a malformed id as absent (parseId NumberFormatException
     * catch-arm).
     */
    @Test
    void deleteTreatsMalformedIdAsAbsent() {
        AdminProductFamilyResource resource = newResource();
        Response response = resource.delete(form("id", "abc"));
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    // ----------------------------------------------------------------
    // duplicate
    // ----------------------------------------------------------------

    /**
     * {@code duplicate} rejects an absent source id (sourceId-null arm).
     */
    @Test
    void duplicateRejectsNullSource() {
        AdminProductFamilyResource resource = newResource();
        Response response = resource.duplicate(new MultivaluedHashMap<>());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code duplicate} rejects a source id resolving to nothing (sourceId-non-null,
     * source-null arm).
     */
    @Test
    void duplicateRejectsUnknownSource() {
        AdminProductFamilyResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(2L)).thenReturn(null);
            Response response = resource.duplicate(form("sourceId", "2"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code duplicate} rejects a blank new code (newCode-empty arm).
     */
    @Test
    void duplicateRejectsBlankNewCode() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily source = family(2L, "FRUITS");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(2L)).thenReturn(source);
            Response response = resource.duplicate(form("sourceId", "2", "newCode", "  "));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code duplicate} rejects a new code already used (count-positive arm).
     */
    @Test
    void duplicateRejectsDuplicateCode() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily source = family(2L, "FRUITS");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(2L)).thenReturn(source);
            panache.when(() -> ProductFamily.count("code", "FRUITS2")).thenReturn(1L);
            Response response = resource.duplicate(form("sourceId", "2", "newCode", "FRUITS2"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code duplicate} copies the description, flags and members into a new
     * group and persists it (success arm).
     */
    @Test
    void duplicateCopiesGroup() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily source = family(2L, "FRUITS");
        source.description = "Fruits";
        source.flags = "SEASONAL";
        Product member = product(9L, "1", "pomme");
        source.products.add(member);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(2L)).thenReturn(source);
            panache.when(() -> ProductFamily.count("code", "FRUITS2")).thenReturn(0L);
            try (MockedConstruction<ProductFamily> construction = mockConstruction(ProductFamily.class,
                    (mockFamily, context) -> mockFamily.products = new HashSet<>())) {
                Response response = resource.duplicate(form("sourceId", "2", "newCode", "FRUITS2"));
                assertEquals(303, response.getStatus());
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                ProductFamily copy = construction.constructed().get(0);
                assertEquals("FRUITS2", copy.code);
                assertEquals("Fruits", copy.description);
                assertEquals("SEASONAL", copy.flags);
                assertTrue(copy.products.contains(member));
                verify(copy).persist();
            }
        }
    }

    // ----------------------------------------------------------------
    // addArticles
    // ----------------------------------------------------------------

    /**
     * {@code addArticles} rejects an absent group id (id-null arm).
     */
    @Test
    void addArticlesRejectsNullId() {
        AdminProductFamilyResource resource = newResource();
        Response response = resource.addArticles(new MultivaluedHashMap<>());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code addArticles} rejects a group id resolving to nothing (id-non-null,
     * family-null arm).
     */
    @Test
    void addArticlesRejectsUnknownId() {
        AdminProductFamilyResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(null);
            Response response = resource.addArticles(form("id", "10"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code addArticles} resolves codes by EAN, skips an empty token and an
     * unresolvable one, and attaches the found article (codes-present arm,
     * token-empty and token-non-empty arms, resolve-hit and resolve-miss arms).
     */
    @Test
    void addArticlesByCode() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily target = family(10L, "PROMO");
        Product found = product(9L, "EAN1", "pomme");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(target);
            products.when(() -> Product.findByEan("EAN1")).thenReturn(found);
            Response response = resource.addArticles(form("id", "10", "codes", ",EAN1,BADCODE"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(target.products.contains(found));
            assertEquals(1, target.products.size());
        }
    }

    /**
     * {@code addArticles} resolves a code by internal code and another by PLU
     * (resolve fall-through arms of {@code resolveProduct}).
     */
    @Test
    void addArticlesByInternalCodeAndPlu() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily target = family(10L, "PROMO");
        Product byInternal = product(1L, "A", "interne");
        Product byPlu = product(2L, "B", "pesé");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(target);
            products.when(() -> Product.findByInternalCode("INT")).thenReturn(byInternal);
            products.when(() -> Product.findByPlu("PLU9")).thenReturn(byPlu);
            Response response = resource.addArticles(form("id", "10", "codes", "INT PLU9"));
            assertEquals(303, response.getStatus());
            assertEquals(2, target.products.size());
        }
    }

    /**
     * {@code addArticles} adds every article of an EAN range (range-both-present
     * arm of the compound guard).
     */
    @Test
    void addArticlesByEanRange() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily target = family(10L, "PROMO");
        Product ranged = product(3L, "150", "milieu");
        PanacheQuery<Product> rangeQuery = listQuery(List.of(ranged));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(target);
            panache.when(() -> Product.find("ean >= ?1 and ean <= ?2 order by ean", "100", "200"))
                    .thenReturn(rangeQuery);
            Response response = resource.addArticles(form("id", "10", "eanFrom", "100", "eanTo", "200"));
            assertEquals(303, response.getStatus());
            assertTrue(target.products.contains(ranged));
        }
    }

    /**
     * {@code addArticles} ignores a half-specified range and, finding nothing,
     * reports an empty selection (range second-leg-false arm, attach-empty arm).
     */
    @Test
    void addArticlesRejectsEmptySelection() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily target = family(10L, "PROMO");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(target);
            Response response = resource.addArticles(form("id", "10", "eanFrom", "100"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            assertTrue(target.products.isEmpty());
        }
    }

    /**
     * {@code addArticles} copies the members of a named source group, skipping a
     * blank codes field (codes-blank arm, source-found arm).
     */
    @Test
    void addArticlesFromSourceGroup() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily target = family(10L, "PROMO");
        ProductFamily source = family(20L, "SRC");
        Product shared = product(9L, "9", "partagé");
        source.products.add(shared);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(target);
            families.when(() -> ProductFamily.findByCode("SRC")).thenReturn(source);
            Response response = resource.addArticles(form("id", "10", "codes", "   ", "sourceFamily", "SRC"));
            assertEquals(303, response.getStatus());
            assertTrue(target.products.contains(shared));
        }
    }

    /**
     * {@code addArticles} ignores an unknown source group and reports an empty
     * selection (source-not-found arm).
     */
    @Test
    void addArticlesUnknownSourceGroup() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily target = family(10L, "PROMO");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(target);
            families.when(() -> ProductFamily.findByCode("NOPE")).thenReturn(null);
            Response response = resource.addArticles(form("id", "10", "sourceFamily", "NOPE"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code addArticles} adds every article whose label matches (label-present
     * arm), case-insensitively.
     */
    @Test
    void addArticlesByLabel() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily target = family(10L, "PROMO");
        Product byLabel = product(4L, "77", "Pomme Golden");
        PanacheQuery<Product> labelQuery = listQuery(List.of(byLabel));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(target);
            panache.when(() -> Product.find("lower(name) like ?1 order by ean", "%pomme%"))
                    .thenReturn(labelQuery);
            Response response = resource.addArticles(form("id", "10", "label", "Pomme"));
            assertEquals(303, response.getStatus());
            assertTrue(target.products.contains(byLabel));
        }
    }

    // ----------------------------------------------------------------
    // addAtLevelZero
    // ----------------------------------------------------------------

    /**
     * {@code addAtLevelZero} creates the reserved container on first use
     * (container-null arm) and attaches the resolved article.
     */
    @Test
    void levelZeroCreatesContainer() {
        AdminProductFamilyResource resource = newResource();
        Product found = product(9L, "EAN1", "sac");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode(AdminProductFamilyResource.LEVEL_ZERO_CODE))
                    .thenReturn(null);
            products.when(() -> Product.findByEan("EAN1")).thenReturn(found);
            try (MockedConstruction<ProductFamily> construction = mockConstruction(ProductFamily.class,
                    (mockFamily, context) -> mockFamily.products = new HashSet<>())) {
                Response response = resource.addAtLevelZero(form("codes", "EAN1"));
                assertEquals(303, response.getStatus());
                assertTrue(response.getLocation().toString().contains("noticeOk=true"));
                ProductFamily container = construction.constructed().get(0);
                assertEquals(AdminProductFamilyResource.LEVEL_ZERO_CODE, container.code);
                assertTrue(container.products.contains(found));
                verify(container).persist();
            }
        }
    }

    /**
     * {@code addAtLevelZero} reuses the existing container (container-found arm).
     */
    @Test
    void levelZeroUsesExistingContainer() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily container = family(30L, AdminProductFamilyResource.LEVEL_ZERO_CODE);
        Product found = product(9L, "EAN1", "sac");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode(AdminProductFamilyResource.LEVEL_ZERO_CODE))
                    .thenReturn(container);
            products.when(() -> Product.findByEan("EAN1")).thenReturn(found);
            Response response = resource.addAtLevelZero(form("codes", "EAN1"));
            assertEquals(303, response.getStatus());
            assertTrue(container.products.contains(found));
        }
    }

    // ----------------------------------------------------------------
    // removeArticle
    // ----------------------------------------------------------------

    /**
     * {@code removeArticle} rejects an absent group id (id-null arm).
     */
    @Test
    void removeRejectsNullGroup() {
        AdminProductFamilyResource resource = newResource();
        Response response = resource.removeArticle(new MultivaluedHashMap<>());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code removeArticle} rejects a group id resolving to nothing (id-non-null,
     * family-null arm).
     */
    @Test
    void removeRejectsUnknownGroup() {
        AdminProductFamilyResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(null);
            Response response = resource.removeArticle(form("id", "10"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code removeArticle} rejects an absent product id (productId-null arm).
     */
    @Test
    void removeRejectsNullProduct() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily target = family(10L, "PROMO");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(target);
            Response response = resource.removeArticle(form("id", "10"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code removeArticle} rejects a product id resolving to nothing
     * (productId-non-null, product-null arm).
     */
    @Test
    void removeRejectsUnknownProduct() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily target = family(10L, "PROMO");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(target);
            panache.when(() -> Product.findById(9L)).thenReturn(null);
            Response response = resource.removeArticle(form("id", "10", "productId", "9"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code removeArticle} drops the article from the group's members (found arm).
     */
    @Test
    void removeDropsMember() {
        AdminProductFamilyResource resource = newResource();
        ProductFamily target = family(10L, "PROMO");
        Product member = product(9L, "9", "pomme");
        target.products.add(member);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(10L)).thenReturn(target);
            panache.when(() -> Product.findById(9L)).thenReturn(member);
            Response response = resource.removeArticle(form("id", "10", "productId", "9"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertFalse(target.products.contains(member));
        }
    }

    // ----------------------------------------------------------------
    // toggleArticle
    // ----------------------------------------------------------------

    /**
     * {@code toggleArticle} rejects an absent product id (productId-null arm).
     */
    @Test
    void toggleRejectsNullProduct() {
        AdminProductFamilyResource resource = newResource();
        Response response = resource.toggleArticle(form("id", "10"));
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code toggleArticle} rejects a product id resolving to nothing
     * (product-null arm).
     */
    @Test
    void toggleRejectsUnknownProduct() {
        AdminProductFamilyResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(9L)).thenReturn(null);
            Response response = resource.toggleArticle(form("id", "10", "productId", "9"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code toggleArticle} deactivates an active member (active-true arm).
     */
    @Test
    void toggleDeactivates() {
        AdminProductFamilyResource resource = newResource();
        Product member = product(9L, "9", "pomme");
        member.active = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(9L)).thenReturn(member);
            Response response = resource.toggleArticle(form("id", "10", "productId", "9"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertFalse(member.active);
        }
    }

    /**
     * {@code toggleArticle} reactivates a deactivated member (active-false arm)
     * and redirects to the list when no group id is posted (edit-id-null arm).
     */
    @Test
    void toggleReactivates() {
        AdminProductFamilyResource resource = newResource();
        Product member = product(9L, "9", "pomme");
        member.active = false;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(9L)).thenReturn(member);
            Response response = resource.toggleArticle(form("productId", "9"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(member.active);
        }
    }
}
