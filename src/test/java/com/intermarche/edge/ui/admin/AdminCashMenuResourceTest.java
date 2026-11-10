package com.intermarche.edge.ui.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.store.CashFunction;
import com.intermarche.edge.domain.store.CashMenu;
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

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Unit tests for {@link AdminCashMenuResource} (BO-03-10-16/17): the page with
 * and without a model to edit, the save's refusal, insert and update arms, the
 * delete's unknown and known arms, and the joining of the ticked values.
 */
class AdminCashMenuResourceTest {

    /**
     * Builds a resource with a mocked template whose chain returns one instance.
     *
     * @return the resource
     */
    private AdminCashMenuResource newResource() {
        AdminCashMenuResource resource = new AdminCashMenuResource();
        resource.i18n = mock(BackOfficeMessages.class);
        when(resource.i18n.text(any())).thenAnswer(call -> "~" + call.getArgument(0));
        when(resource.i18n.get(anyString())).thenAnswer(call -> "#" + call.getArgument(0));
        resource.adminCashMenus = mock(Template.class);
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminCashMenus.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return resource;
    }

    /**
     * Builds a query answering one row.
     *
     * @param row the row, or null
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<CashMenu> query(CashMenu row) {
        PanacheQuery<CashMenu> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /** The screen is declared in the parametrage menu. */
    @Test
    void theScreenIsDeclared() {
        assertEquals("/admin/cash-menus", Feature.CASH_MENU.getPath());
    }

    /** The page renders with a model to edit and without one. */
    @Test
    void pageRendersWithAndWithoutAModel() {
        AdminCashMenuResource resource = newResource();
        CashMenu menu = new CashMenu();
        menu.menuKeys = "articles";
        PanacheQuery<CashMenu> found = query(menu);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashMenu.find("code", "SLS")).thenReturn(found);
            panache.when(() -> CashMenu.list("order by code")).thenReturn(List.of(menu));
            assertTrue(resource.page("SLS", null, true) != null);
            assertTrue(resource.page(null, "x", false) != null);
            verify(resource.adminCashMenus, org.mockito.Mockito.times(2)).data("menus", List.of(menu));
        }
    }

    /** A model without code or label is refused. */
    @Test
    void saveRefusesWithoutCodeOrLabel() {
        AdminCashMenuResource resource = newResource();
        MultivaluedMap<String, String> noLabel = new MultivaluedHashMap<>();
        noLabel.putSingle("code", "SLS");
        assertTrue(resource.save(new MultivaluedHashMap<>()).getLocation().toString().contains("noticeOk=false"));
        assertTrue(resource.save(noLabel).getLocation().toString().contains("noticeOk=false"));
    }

    /** A new model is filled then persisted, values normalised and bounded. */
    @Test
    void saveInsertsANewModel() {
        AdminCashMenuResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("code", "SLS");
        form.putSingle("label", "Libre-service");
        form.putSingle("active", "on");
        form.put("menuKey", Arrays.asList("articles", " ", "reglement"));
        form.put("hidden", List.of("ABANDON"));
        form.putSingle("keyColumns", "9");
        form.putSingle("cashierScreen", "large");
        form.putSingle("customerScreen", "TOTAL_ONLY");
        form.putSingle("ticketModel", "COMPACT");
        PanacheQuery<CashMenu> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashMenu> created = mockConstruction(CashMenu.class)) {
            panache.when(() -> CashMenu.find("code", "SLS")).thenReturn(absent);
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            CashMenu menu = created.constructed().get(0);
            assertEquals("SLS", menu.code);
            assertEquals("Libre-service", menu.label);
            assertTrue(menu.active);
            assertEquals("articles;reglement", menu.menuKeys);
            assertEquals("ABANDON", menu.hiddenFunctions);
            assertEquals(4, menu.keyColumns);
            assertEquals("LARGE", menu.cashierScreen);
            assertEquals("TOTAL_ONLY", menu.customerScreen);
            assertEquals("COMPACT", menu.ticketModel);
            verify(menu).persist();
        }
    }

    /** An existing model is updated in place, never persisted twice; nothing ticked reads as all. */
    @Test
    void saveUpdatesAnExistingModel() {
        AdminCashMenuResource resource = newResource();
        CashMenu stored = mock(CashMenu.class);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("code", "SLS");
        form.putSingle("label", "Caisse");
        form.putSingle("keyColumns", "-3");
        PanacheQuery<CashMenu> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashMenu.find("code", "SLS")).thenReturn(present);
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=true"));
        }
        assertNull(stored.menuKeys);
        assertNull(stored.hiddenFunctions);
        assertEquals(0, stored.keyColumns);
        assertEquals(false, stored.active);
        verify(stored, never()).persist();
    }

    /**
     * A posted layout is stored sanitised and supersedes the bar keys ticked:
     * the standard tabs it shows are written there.
     */
    @Test
    void saveStoresTheLayout() {
        AdminCashMenuResource resource = newResource();
        CashMenu stored = mock(CashMenu.class);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("code", "SLS");
        form.putSingle("label", "Caisse");
        form.put("menuKey", List.of("autre"));
        form.putSingle("layout", "{\"tabs\":[{\"id\":\"x1\",\"labels\":{\"fr\":\"PROMO\"},\"keys\":[{\"fn\":\"SEARCH\"}]},"
                + "{\"id\":\"ticket\"},{\"id\":\"reglement\"}]}");
        PanacheQuery<CashMenu> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashMenu.find("code", "SLS")).thenReturn(present);
            resource.save(form);
        }
        assertEquals("{\"tabs\":[{\"id\":\"x1\",\"labels\":{\"fr\":\"PROMO\"},\"keys\":[{\"fn\":\"SEARCH\"}]},"
                + "{\"id\":\"ticket\"},{\"id\":\"reglement\"}]}", stored.layout);
        assertEquals("ticket;reglement", stored.menuKeys);
    }

    /** An unreadable layout is none: the bar keys ticked decide. */
    @Test
    void saveIgnoresAnUnreadableLayout() {
        AdminCashMenuResource resource = newResource();
        CashMenu stored = mock(CashMenu.class);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("code", "SLS");
        form.putSingle("label", "Caisse");
        form.put("menuKey", List.of("autre"));
        form.putSingle("layout", "{broken");
        PanacheQuery<CashMenu> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashMenu.find("code", "SLS")).thenReturn(present);
            resource.save(form);
        }
        assertNull(stored.layout);
        assertEquals("autre", stored.menuKeys);
    }

    /**
     * The editor of a new model starts on the standard layout and two columns,
     * with the labels in the operator's language and every text of the editor.
     */
    @Test
    void editorDataOfANewModel() {
        AdminCashMenuResource resource = newResource();
        String data = resource.editorData(null);
        assertTrue(data.contains("\"layout\":{\"tabs\":[{\"id\":\"articles\""));
        assertTrue(data.contains("\"CANCEL_LINE\":\"~Annuler la ligne\""));
        assertTrue(data.contains("\"articles\":\"~Articles\""));
        assertTrue(data.contains("\"blue\":\"#1f5fd6\""));
        assertTrue(data.contains("\"blue\":\"#t.k19-ml-couleur-blue\""));
        assertTrue(data.contains("\"removeTab\":\"#t.k19-ml-removeTab\""));
        assertTrue(data.contains("\"columns\":2"));
        assertTrue(data.contains("\"maxTabs\":9"));
    }

    /**
     * The editor of a model starts on its layout and its columns, bounded; a
     * label cannot close the script element it sits in.
     */
    @Test
    void editorDataOfAModel() {
        AdminCashMenuResource resource = newResource();
        CashMenu menu = new CashMenu();
        menu.keyColumns = 9;
        menu.layout = "{\"tabs\":[{\"id\":\"x1\",\"labels\":{\"fr\":\"</script>\"}}]}";
        String data = resource.editorData(menu);
        assertTrue(data.contains("\"layout\":{\"tabs\":[{\"id\":\"x1\",\"labels\":{\"fr\":\"\\u003c/script>\"}}]}"));
        assertFalse(data.contains("</script>"));
        assertTrue(data.contains("\"columns\":4"));
        menu.keyColumns = 3;
        assertTrue(resource.editorData(menu).contains("\"columns\":3"));
    }

    /**
     * A serialisation failure is turned into an {@link IllegalStateException}
     * carrying the editor data and the Jackson cause. No real editor data can
     * make Jackson fail, so the codec is swapped for one that throws, the
     * defensive catch of {@code editorData()} is driven, and the original codec
     * is restored.
     */
    @Test
    void editorDataWrapsASerialisationFailure() {
        AdminCashMenuResource resource = newResource();
        ObjectMapper original = AdminCashMenuResource.MAPPER;
        ObjectMapper failing = mock(ObjectMapper.class);
        JsonProcessingException boom = new JsonProcessingException("boom") {
        };
        try {
            when(failing.writeValueAsString(any())).thenThrow(boom);
            AdminCashMenuResource.MAPPER = failing;
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> resource.editorData(null));
            assertEquals("Unwritable editor data", error.getMessage());
            assertSame(boom, error.getCause());
        } catch (JsonProcessingException checked) {
            throw new AssertionError("stubbing never runs the checked body", checked);
        } finally {
            AdminCashMenuResource.MAPPER = original;
        }
    }

    /** An unknown model cannot be deleted; a known one is. */
    @Test
    void deleteRefusesTheUnknownAndDeletesTheKnown() {
        AdminCashMenuResource resource = newResource();
        CashMenu stored = mock(CashMenu.class);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("code", "SLS");
        PanacheQuery<CashMenu> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashMenu.find("code", "SLS")).thenReturn(present);
            assertTrue(resource.delete(form).getLocation().toString().contains("noticeOk=true"));
        }
        verify(stored).delete();
        assertTrue(resource.delete(new MultivaluedHashMap<>()).getLocation().toString().contains("noticeOk=false"));
    }

    /** Joining keeps the non-blank values; none, or a null list, gives null. */
    @Test
    void joinedKeepsTheTickedValues() {
        assertEquals("A;B", AdminCashMenuResource.joined(Arrays.asList(" A ", null, "", "B")));
        assertNull(AdminCashMenuResource.joined(null));
        assertNull(AdminCashMenuResource.joined(List.of(" ")));
        assertSame(CashFunction.ABANDON, CashFunction.fromName("ABANDON"));
    }
}
