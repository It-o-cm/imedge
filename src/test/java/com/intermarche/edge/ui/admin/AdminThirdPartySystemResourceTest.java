package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Menu;
import com.intermarche.edge.domain.sale.SalesChannel;
import com.intermarche.edge.domain.sale.ThirdPartySystem;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.store.Store;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * Unit tests for {@link AdminThirdPartySystemResource} (BO-07-01-01): the
 * screen lists the systems; a save declares a system with its token or
 * updates it, refusing a bad code and an unknown point of sale; a token is
 * regenerated for a known system only.
 */
class AdminThirdPartySystemResourceTest {

    /**
     * A posted form.
     *
     * @param pairs alternating keys and values
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
     * A query answering one row.
     *
     * @param row the row, or null
     * @param <T> its type
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> query(T row) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * The notice a redirect carries, decoded.
     *
     * @param response the redirect
     * @return its query string
     */
    private String notice(Response response) {
        return URLDecoder.decode(response.getLocation().getQuery(), StandardCharsets.UTF_8);
    }

    /**
     * The screen is declared in the supervision menu.
     */
    @Test
    void theScreenIsDeclaredInTheMenu() {
        assertEquals("third_party_system", Feature.THIRD_PARTY_SYSTEM.getKey());
        assertEquals("/admin/third-party-systems", Feature.THIRD_PARTY_SYSTEM.getPath());
        assertEquals(Menu.SUPERVISION, Feature.THIRD_PARTY_SYSTEM.getMenu());
        assertEquals("Systèmes tiers", Feature.THIRD_PARTY_SYSTEM.getLabel());
    }

    /**
     * The page hands the systems, the two channels and the stores over.
     */
    @Test
    void thePageListsTheSystems() {
        AdminThirdPartySystemResource resource = new AdminThirdPartySystemResource();
        resource.adminThirdPartySystems = mock(Template.class);
        TemplateInstance page = mock(TemplateInstance.class);
        when(resource.adminThirdPartySystems.data(anyString(), any())).thenReturn(page);
        when(page.data(anyString(), any())).thenReturn(page);
        ThirdPartySystem system = new ThirdPartySystem();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> ThirdPartySystem.list("order by code")).thenReturn(List.of(system));
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertSame(page, resource.systemsPage("ok", true));
        }
        verify(resource.adminThirdPartySystems).data("systems", List.of(system));
        verify(page).data("channels", List.of(SalesChannel.ECOMMERCE, SalesChannel.MOBILITY));
    }

    /**
     * A missing or malformed code is refused, and so is an unknown point of
     * sale; nothing is created.
     */
    @Test
    void aBadCodeOrPointOfSaleIsRefused() {
        AdminThirdPartySystemResource resource = new AdminThirdPartySystemResource();
        assertTrue(notice(resource.save(form())).contains("Code de système tiers invalide."));
        assertTrue(notice(resource.save(form("code", "SITE WEB"))).contains("noticeOk=false"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ThirdPartySystem> created = mockConstruction(ThirdPartySystem.class)) {
            PanacheQuery<Store> none = query(null);
            ms.when(() -> Store.find("code", "99999")).thenReturn(none);
            assertTrue(notice(resource.save(form("code", "WEB", "pdvNumber", "99999")))
                    .contains("Point de vente inconnu : 99999"));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * A new system is declared with its fields — its label the code when none
     * is given, an unknown channel read as e-commerce — and a fresh token,
     * then persisted.
     */
    @Test
    void aNewSystemIsDeclaredWithItsToken() {
        AdminThirdPartySystemResource resource = new AdminThirdPartySystemResource();
        PanacheQuery<ThirdPartySystem> absent = query(null);
        PanacheQuery<Store> store = query(new Store());
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ThirdPartySystem> created = mockConstruction(ThirdPartySystem.class)) {
            ms.when(() -> ThirdPartySystem.find("code", "WEB")).thenReturn(absent);
            ms.when(() -> Store.find("code", "07039")).thenReturn(store);
            Response response = resource.save(form("code", " WEB ", "channel", "STORE", "pdvNumber", "07039",
                    "terminalId", " WEB1 ", "active", "on"));
            assertTrue(notice(response).contains("Système tiers déclaré."));
            ThirdPartySystem system = created.constructed().get(0);
            assertEquals("WEB", system.code);
            assertEquals("WEB", system.label);
            assertEquals(SalesChannel.ECOMMERCE, system.channel);
            assertEquals("07039", system.pdvNumber);
            assertEquals("WEB1", system.terminalId);
            assertTrue(system.active);
            assertEquals(32, system.token.length());
            verify(system).persist();
        }
    }

    /**
     * An existing system is updated in place, its token kept, and not
     * persisted again.
     */
    @Test
    void anExistingSystemIsUpdated() {
        AdminThirdPartySystemResource resource = new AdminThirdPartySystemResource();
        ThirdPartySystem existing = mock(ThirdPartySystem.class);
        existing.token = "kept";
        PanacheQuery<ThirdPartySystem> found = query(existing);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> ThirdPartySystem.find("code", "MOB")).thenReturn(found);
            Response response = resource.save(form("code", "MOB", "label", "Appli", "channel", "MOBILITY"));
            assertTrue(notice(response).contains("Système tiers enregistré."));
        }
        assertEquals("Appli", existing.label);
        assertEquals(SalesChannel.MOBILITY, existing.channel);
        assertNull(existing.pdvNumber);
        assertFalse(existing.active);
        assertEquals("kept", existing.token);
        verify(existing, never()).persist();
    }

    /**
     * A known system receives a new token; an unknown one is refused.
     */
    @Test
    void aTokenIsRegeneratedForAKnownSystem() {
        AdminThirdPartySystemResource resource = new AdminThirdPartySystemResource();
        ThirdPartySystem system = new ThirdPartySystem();
        system.token = "old";
        PanacheQuery<ThirdPartySystem> found = query(system);
        PanacheQuery<ThirdPartySystem> absent = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> ThirdPartySystem.find("code", "WEB")).thenReturn(found);
            ms.when(() -> ThirdPartySystem.find("code", "NONE")).thenReturn(absent);
            assertTrue(notice(resource.regenerate(form("code", "WEB"))).contains("Nouveau jeton généré"));
            assertNotEquals("old", system.token);
            assertTrue(notice(resource.regenerate(form("code", "NONE"))).contains("Système tiers inconnu."));
        }
        assertNotEquals(AdminThirdPartySystemResource.newToken(), AdminThirdPartySystemResource.newToken());
    }
}
