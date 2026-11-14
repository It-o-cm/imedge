package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.barcode.ArticleBarcodeRange;
import com.intermarche.edge.domain.people.Menu;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.setting.PosList;
import com.intermarche.edge.domain.setting.PosListItem;
import com.intermarche.edge.domain.setting.ReturnTypeDefinition;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminReturnTypeResource}, the return-type screen
 * (BO-03-13-01 to -04, -11, -12).
 *
 * <p>Plain JUnit 5 + Mockito: the referential's finders are intercepted on
 * {@link ReturnTypeDefinition}, the counts and the range list on
 * {@link PanacheEntityBase}, and the rows the screen creates by
 * {@code mockConstruction}. Branch enumeration: every guard of the save path
 * (no code, a code too long, no label, an unknown range), creation and update,
 * deletion of an unknown, a used and an unused type, the toggle both ways, the
 * migration creating, skipping and truncating, and the option readers on
 * every shape of marker.
 */
class AdminReturnTypeResourceTest {

    /**
     * Builds a resource with a mocked template and settings.
     *
     * @return the resource
     */
    private AdminReturnTypeResource newResource() {
        AdminReturnTypeResource resource = new AdminReturnTypeResource();
        resource.adminReturnTypes = mock(Template.class);
        resource.posSettingsService = mock(PosSettingsService.class);
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
     * Reads the decoded location of a redirect.
     *
     * @param response the redirect
     * @return its decoded location
     */
    private String location(Response response) {
        return URLDecoder.decode(response.getLocation().toString(), StandardCharsets.UTF_8);
    }

    /**
     * Stubs the RETURN barcode ranges a type may be attached to.
     *
     * @param panache the opened Panache mock
     * @param codes the range codes
     */
    private void ranges(MockedStatic<PanacheEntityBase> panache, String... codes) {
        List<ArticleBarcodeRange> ranges = new java.util.ArrayList<>();
        for (String code : codes) {
            ArticleBarcodeRange range = new ArticleBarcodeRange();
            range.code = code;
            ranges.add(range);
        }
        panache.when(() -> PanacheEntityBase.list("purpose = ?1 order by code", ArticleBarcodeRange.Purpose.REFUND))
                .thenReturn(ranges);
    }

    /**
     * The screen is declared in the parametrage menu.
     */
    @Test
    void theScreenIsDeclaredInTheMenu() {
        assertEquals("return_type", Feature.RETURN_TYPE.getKey());
        assertEquals("/admin/return-types", Feature.RETURN_TYPE.getPath());
        assertEquals(Menu.PARAMETRAGE, Feature.RETURN_TYPE.getMenu());
    }

    /**
     * The page lists each type with how many returns name it, and the
     * ranges a type may be attached to.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thePageListsTheTypesAndTheirUse() {
        AdminReturnTypeResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminReturnTypes.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        ReturnTypeDefinition type = new ReturnTypeDefinition();
        type.code = "ECH";
        try (MockedStatic<ReturnTypeDefinition> types = mockStatic(ReturnTypeDefinition.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            types.when(ReturnTypeDefinition::findAllOrdered).thenReturn(List.of(type));
            panache.when(() -> PanacheEntityBase.count("returnTypeCode", "ECH")).thenReturn(2L, 1L);
            ranges(panache, "TRI");
            assertSame(instance, resource.list("Type de retour créé.", true));
        }
        ArgumentCaptor<Object> rows = ArgumentCaptor.forClass(Object.class);
        verify(resource.adminReturnTypes).data(eq("rows"), rows.capture());
        List<AdminReturnTypeResource.Row> listed = (List<AdminReturnTypeResource.Row>) rows.getValue();
        assertSame(type, listed.get(0).type);
        assertEquals(3L, listed.get(0).used);
        verify(instance).data("ranges", List.of("TRI"));
        verify(instance).data("notice", "Type de retour créé.");
        verify(instance).data("noticeOk", true);
    }

    /**
     * Every guard of the save path refuses before anything is written.
     */
    @Test
    void saveRefusesWhatCannotBeAType() {
        AdminReturnTypeResource resource = newResource();
        assertTrue(location(resource.save(form())).contains("Le code du type de retour est obligatoire"));
        assertTrue(location(resource.save(form("code", "X".repeat(41), "label", "L")))
                .contains("Le code du type de retour est obligatoire"));
        assertTrue(location(resource.save(form("code", "ECH")))
                .contains("Le libellé du type de retour est obligatoire."));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ranges(panache, "TRI");
            Response unknown = resource.save(form("code", "ECH", "label", "Échange", "rangeCode", "AUTRE"));
            assertTrue(location(unknown).contains("Plage de codes-barres retour inconnue : AUTRE"));
            assertTrue(location(unknown).contains("noticeOk=false"));
        }
    }

    /**
     * A new code creates a type carrying every option posted, all set before
     * it is persisted; a known code updates it, unposted options turning off.
     */
    @Test
    void saveCreatesThenUpdates() {
        AdminReturnTypeResource resource = newResource();
        ReturnTypeDefinition existing = mock(ReturnTypeDefinition.class);
        existing.ticketMode = true;
        try (MockedStatic<ReturnTypeDefinition> types = mockStatic(ReturnTypeDefinition.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ReturnTypeDefinition> created = mockConstruction(ReturnTypeDefinition.class,
                     (mock, context) -> org.mockito.Mockito.doAnswer(invocation -> {
                         assertEquals("TRI", mock.code);
                         assertEquals("Machine de tri", mock.label);
                         return null;
                     }).when(mock).persist())) {
            ranges(panache, "PLAN");
            types.when(() -> ReturnTypeDefinition.findByCode("ECH")).thenReturn(existing);
            Response createdResponse = resource.save(form("code", " tri ", "label", "Machine de tri",
                    "ticketMode", "on", "endorsementExempt", "on", "originOptional", "on",
                    "priceConfirmed", "on", "depositReturn", "on", "rangeCode", "PLAN",
                    "displayOrder", "5", "active", "on"));
            assertTrue(location(createdResponse).contains("Type de retour créé."));
            ReturnTypeDefinition row = created.constructed().get(0);
            assertTrue(row.ticketMode && row.endorsementExempt && row.originOptional
                    && row.priceConfirmed && row.depositReturn && row.active);
            assertEquals("PLAN", row.rangeCode);
            assertEquals(5, row.displayOrder);
            verify(row).persist();
            Response updated = resource.save(form("code", "ech", "label", "Échange"));
            assertTrue(location(updated).contains("Type de retour enregistré."));
            assertFalse(existing.ticketMode);
            assertFalse(existing.active);
            assertNull(existing.rangeCode);
            assertEquals("Échange", existing.label);
            verify(existing).persist();
        }
    }

    /**
     * Deleting an unknown type says so; a type returns name is deactivated
     * and the count is told; an unused type is deleted.
     */
    @Test
    void deleteDeactivatesWhatReturnsName() {
        AdminReturnTypeResource resource = newResource();
        ReturnTypeDefinition used = mock(ReturnTypeDefinition.class);
        used.code = "USED";
        used.active = true;
        ReturnTypeDefinition free = mock(ReturnTypeDefinition.class);
        free.code = "FREE";
        try (MockedStatic<ReturnTypeDefinition> types = mockStatic(ReturnTypeDefinition.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            types.when(() -> ReturnTypeDefinition.findByCode("USED")).thenReturn(used);
            types.when(() -> ReturnTypeDefinition.findByCode("FREE")).thenReturn(free);
            panache.when(() -> PanacheEntityBase.count("returnTypeCode", "USED")).thenReturn(1L, 1L);
            panache.when(() -> PanacheEntityBase.count("returnTypeCode", "FREE")).thenReturn(0L);
            assertTrue(location(resource.delete(form("code", "NONE"))).contains("Type de retour inconnu."));
            assertTrue(location(resource.delete(form("code", "USED")))
                    .contains("Le type de retour USED est rattaché à 2 retour(s) : il a été désactivé et non supprimé."));
            assertFalse(used.active);
            verify(used).persist();
            verify(used, never()).delete();
            assertTrue(location(resource.delete(form("code", "FREE"))).contains("Type de retour supprimé."));
            verify(free).delete();
        }
    }

    /**
     * The toggle refuses an unknown type and switches a known one both ways.
     */
    @Test
    void toggleSwitchesTheType() {
        AdminReturnTypeResource resource = newResource();
        ReturnTypeDefinition type = mock(ReturnTypeDefinition.class);
        type.active = true;
        try (MockedStatic<ReturnTypeDefinition> types = mockStatic(ReturnTypeDefinition.class)) {
            types.when(() -> ReturnTypeDefinition.findByCode("ECH")).thenReturn(type);
            assertTrue(location(resource.toggle(form())).contains("Type de retour inconnu."));
            assertTrue(location(resource.toggle(form("code", "ECH"))).contains("Type de retour désactivé."));
            assertFalse(type.active);
            assertTrue(location(resource.toggle(form("code", "ECH"))).contains("Type de retour activé."));
            assertTrue(type.active);
        }
    }

    /**
     * The migration takes the list entries then the setting, skipping a code
     * that exists, reading every option and truncating a code too long.
     */
    @Test
    void importTakesOverTheListAndTheSetting() {
        AdminReturnTypeResource resource = newResource();
        PosList list = new PosList();
        PosListItem existing = new PosListItem();
        existing.code = "ECH";
        existing.label = "Échange";
        existing.active = true;
        PosListItem full = new PosListItem();
        full.code = "tri";
        full.label = "Tri";
        full.options = "TICKET,NOENDORSE,NOORIGIN,PRICE,DECONSIGNE,RANGE: PLAN";
        full.active = false;
        list.items.add(existing);
        list.items.add(full);
        when(resource.posSettingsService.returnTypes()).thenReturn(List.of(
                new PosSettingsService.ReturnType("L".repeat(45), true, true, true, true),
                new PosSettingsService.ReturnType("Simple", false),
                new PosSettingsService.ReturnType("ech", false)));
        try (MockedStatic<ReturnTypeDefinition> types = mockStatic(ReturnTypeDefinition.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ReturnTypeDefinition> created = mockConstruction(ReturnTypeDefinition.class)) {
            panache.when(() -> PanacheEntityBase.list("kind = ?1 order by code", PosList.Kind.RETURN_TYPE))
                    .thenReturn(List.of(list));
            types.when(() -> ReturnTypeDefinition.findByCode("ECH")).thenReturn(new ReturnTypeDefinition());
            Response response = resource.importExisting();
            assertTrue(location(response).contains("3 type(s) de retour repris de la liste et du paramètre."));
            ReturnTypeDefinition tri = created.constructed().get(1);
            assertEquals("TRI", tri.code);
            assertTrue(tri.ticketMode && tri.endorsementExempt && tri.originOptional
                    && tri.priceConfirmed && tri.depositReturn);
            assertFalse(tri.active);
            assertEquals("PLAN", tri.rangeCode);
            assertEquals(2, tri.displayOrder);
            ReturnTypeDefinition longOne = created.constructed().get(2);
            assertEquals(40, longOne.code.length());
            assertTrue(longOne.ticketMode && longOne.priceConfirmed && longOne.active);
            ReturnTypeDefinition simple = created.constructed().get(3);
            assertEquals("SIMPLE", simple.code);
            assertFalse(simple.ticketMode || simple.endorsementExempt || simple.originOptional || simple.priceConfirmed);
            assertNull(simple.rangeCode);
        }
    }

    /**
     * With everything already administered, the migration says it had
     * nothing to take.
     */
    @Test
    void importWithNothingNewSaysSo() {
        AdminReturnTypeResource resource = newResource();
        when(resource.posSettingsService.returnTypes()).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("kind = ?1 order by code", PosList.Kind.RETURN_TYPE))
                    .thenReturn(List.of());
            assertTrue(location(resource.importExisting()).contains("Aucun type de retour à reprendre."));
        }
    }

    /**
     * The option readers on every shape of marker.
     */
    @Test
    void theOptionReadersReadEveryShape() {
        assertFalse(AdminReturnTypeResource.marked(null, "TICKET"));
        assertTrue(AdminReturnTypeResource.marked("price, ticket ", "TICKET"));
        assertFalse(AdminReturnTypeResource.marked("PRICE", "TICKET"));
        assertNull(AdminReturnTypeResource.rangeOption(null));
        assertNull(AdminReturnTypeResource.rangeOption("RANGE:"));
        assertNull(AdminReturnTypeResource.rangeOption("TICKET"));
        assertEquals("X", AdminReturnTypeResource.rangeOption("TICKET, range:X"));
    }
}
