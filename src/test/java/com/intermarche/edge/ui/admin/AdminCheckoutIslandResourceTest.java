package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Menu;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.store.CheckoutIsland;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PromotionChannel;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminCheckoutIslandResource}.
 * <p>
 * Plain JUnit 5 + Mockito, no Quarkus and no database. The resource is built
 * with {@code new}, its template is a mock, and the Panache static finders
 * resolve to {@link PanacheEntityBase}, intercepted with
 * {@link org.mockito.Mockito#mockStatic}. Every redirect asserts its one-shot
 * notice; every branch of the save and delete guards is exercised
 * (BO-03-10-01/02/03/04/08/10).
 */
class AdminCheckoutIslandResourceTest {

    /**
     * Builds a resource with a mocked template.
     *
     * @return the resource
     */
    private AdminCheckoutIslandResource newResource() {
        AdminCheckoutIslandResource resource = new AdminCheckoutIslandResource();
        resource.adminCheckoutIslands = mock(Template.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the template to one self-returning
     * instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminCheckoutIslandResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminCheckoutIslands.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
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
     * Wraps a first-result in a Panache query mock.
     *
     * @param row the row the query resolves, or null
     * @param <T> the row type
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> query(T row) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * The screen is declared in the parametrage menu (BO-03-10).
     */
    @Test
    void theScreenIsDeclaredInTheMenu() {
        Feature feature = Feature.CHECKOUT_ISLAND;
        assertEquals("checkout_island", feature.getKey());
        assertEquals("/admin/checkout-islands", feature.getPath());
        assertEquals(Menu.PARAMETRAGE, feature.getMenu());
    }

    /**
     * The page lists the islands and the PDVs it offers.
     */
    @Test
    void islandsPageListsTheIslands() {
        AdminCheckoutIslandResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        CheckoutIsland island = new CheckoutIsland();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutIsland.list("order by code")).thenReturn(List.of(island));
            panache.when(() -> Pdv.list("order by pdvNumber")).thenReturn(List.of());
            assertSame(instance, resource.islandsPage(null, true));
            verify(resource.adminCheckoutIslands).data("islands", List.of(island));
        }
    }

    /**
     * A blank code is refused (first guard).
     */
    @Test
    void saveRefusesABlankCode() {
        Response response = newResource().saveIsland(form());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * A PDV the store does not carry is refused (the unknown-PDV guard).
     */
    @Test
    void saveRefusesAnUnknownPdv() {
        AdminCheckoutIslandResource resource = newResource();
        PanacheQuery<Pdv> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Pdv.find("pdvNumber", "99999")).thenReturn(absent);
            Response response = resource.saveIsland(form("code", "AVANT", "pdvNumber", "99999"));
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * An absent code inserts an active island, its channel taken from the form.
     */
    @Test
    void saveInsertsAnActiveIsland() {
        AdminCheckoutIslandResource resource = newResource();
        PanacheQuery<CheckoutIsland> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CheckoutIsland> created = mockConstruction(CheckoutIsland.class)) {
            panache.when(() -> CheckoutIsland.find("code", "AVANT")).thenReturn(absent);
            Response response = resource.saveIsland(form("code", " AVANT ", "label", "Ligne avant",
                    "promotionChannel", "WEB", "changeGivingAllowed", "on", "active", "on"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            CheckoutIsland island = created.constructed().get(0);
            assertEquals("AVANT", island.code);
            assertEquals("Ligne avant", island.label);
            assertEquals(PromotionChannel.WEB, island.promotionChannel);
            assertTrue(island.changeGivingAllowed);
            assertTrue(island.active);
            verify(island, times(1)).persist();
        }
    }

    /**
     * A PDV the store DOES carry is accepted (the second arm of the PDV guard),
     * and the island is attached to it.
     */
    @Test
    void saveAcceptsAKnownPdv() {
        AdminCheckoutIslandResource resource = newResource();
        Pdv pdv = new Pdv();
        pdv.pdvNumber = "12345";
        PanacheQuery<Pdv> found = query(pdv);
        PanacheQuery<CheckoutIsland> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CheckoutIsland> created = mockConstruction(CheckoutIsland.class)) {
            panache.when(() -> Pdv.find("pdvNumber", "12345")).thenReturn(found);
            panache.when(() -> CheckoutIsland.find("code", "AVANT")).thenReturn(absent);
            Response response = resource.saveIsland(form("code", "AVANT", "pdvNumber", "12345", "active", "on"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertEquals("12345", created.constructed().get(0).pdvNumber);
        }
    }

    /**
     * An unknown channel falls back to the point of sale (the {@code fromName}
     * null arm), and an absent {@code active} deactivates the island AND
     * propagates to its registers (BO-03-10-03).
     */
    @Test
    void saveUpdatesDefaultsTheChannelAndPropagatesDeactivation() {
        AdminCheckoutIslandResource resource = newResource();
        CheckoutIsland stored = new CheckoutIsland();
        stored.code = "AVANT";
        stored.active = true;
        CheckoutPoint point = mock(CheckoutPoint.class);
        point.active = true;
        PanacheQuery<CheckoutIsland> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutIsland.find("code", "AVANT")).thenReturn(present);
            panache.when(() -> CheckoutPoint.list("islandCode = ?1 and active = true", "AVANT"))
                    .thenReturn(List.of(point));
            Response response = resource.saveIsland(form("code", "AVANT", "label", "Comptoir",
                    "promotionChannel", "NOPE"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("Comptoir", stored.label);
        assertEquals(PromotionChannel.POINT_OF_SALE, stored.promotionChannel);
        assertFalse(stored.active);
        assertFalse(point.active);
        verify(point, times(1)).persist();
    }

    /**
     * Deleting an unknown island is refused.
     */
    @Test
    void deleteRefusesAnUnknownIsland() {
        AdminCheckoutIslandResource resource = newResource();
        PanacheQuery<CheckoutIsland> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutIsland.find("code", "AVANT")).thenReturn(absent);
            Response response = resource.deleteIsland(form("code", "AVANT"));
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * Deleting an island with registers still attached is refused (BO-03-10-02).
     */
    @Test
    void deleteRefusesAnIslandWithAttachedRegisters() {
        AdminCheckoutIslandResource resource = newResource();
        CheckoutIsland stored = mock(CheckoutIsland.class);
        PanacheQuery<CheckoutIsland> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutIsland.find("code", "AVANT")).thenReturn(present);
            panache.when(() -> CheckoutPoint.count("islandCode = ?1", "AVANT")).thenReturn(2L);
            Response response = resource.deleteIsland(form("code", "AVANT"));
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            verify(stored, org.mockito.Mockito.never()).delete();
        }
    }

    /**
     * Deleting an unattached island removes it.
     */
    @Test
    void deleteRemovesAnUnattachedIsland() {
        AdminCheckoutIslandResource resource = newResource();
        CheckoutIsland stored = mock(CheckoutIsland.class);
        PanacheQuery<CheckoutIsland> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutIsland.find("code", "AVANT")).thenReturn(present);
            panache.when(() -> CheckoutPoint.count("islandCode = ?1", "AVANT")).thenReturn(0L);
            Response response = resource.deleteIsland(form("code", "AVANT"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            verify(stored, times(1)).delete();
        }
    }

    /**
     * BO-03-10-06: an island is excluded from Planexa when the box is ticked;
     * an island saved without a label is named by its code.
     */
    @Test
    void saveExcludesFromPlanexaAndNamesAnUnlabelledIsland() {
        AdminCheckoutIslandResource resource = newResource();
        PanacheQuery<CheckoutIsland> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CheckoutIsland> created = mockConstruction(CheckoutIsland.class)) {
            panache.when(() -> CheckoutIsland.find("code", "DRIVE")).thenReturn(absent);
            assertTrue(resource.saveIsland(form("code", "DRIVE", "planexaExcluded", "on", "active", "on"))
                    .getLocation().toString().contains("noticeOk=true"));
            CheckoutIsland island = created.constructed().get(0);
            assertTrue(island.planexaExcluded);
            assertEquals("DRIVE", island.label);
        }
    }
}
