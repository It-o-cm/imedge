package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Menu;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.setting.ServiceProvider;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminProviderResource} (BO-03-11-01 to 06).
 *
 * <p>Plain JUnit 5 + Mockito, no Quarkus and no database.
 *
 * <p>Branch enumeration, leg by leg. {@code saveProvider}: a blank code, an
 * unknown type, an unknown protocol, an unknown call phase — each refusal on its
 * own — then a creation and an update, the checkbox read on both arms.
 * {@code applyCredential}: a posted credential is written, an EMPTY field leaves
 * the stored one alone, which is the behaviour the page depends on.
 * {@code clearCredential} and {@code deleteProvider}: unknown provider and
 * nominal. And the credential is never handed to the page.
 */
class AdminProviderResourceTest {

    /**
     * Builds a resource with a mocked template.
     *
     * @return the resource
     */
    private AdminProviderResource newResource() {
        AdminProviderResource resource = new AdminProviderResource();
        resource.adminProviders = mock(Template.class);
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
     * Builds a form carrying a whole valid declaration, plus the given extras.
     *
     * @param extras the alternating extra keys and values
     * @return the form
     */
    private MultivaluedMap<String, String> validForm(String... extras) {
        MultivaluedMap<String, String> posted = form("code", "GIFT",
                "label", "Cartes cadeaux",
                "providerType", "DEMATERIALISE",
                "protocol", "NEXO_RETAILER",
                "callPhase", "AU_SCAN",
                "endpoint", "https://gift.example",
                "articleSelection", "CARTES",
                "documentTemplateCode", "TKT",
                "active", "on");
        for (int i = 0; i < extras.length; i += 2) {
            posted.putSingle(extras[i], extras[i + 1]);
        }
        return posted;
    }

    /**
     * Wraps a first-result in a Panache query mock.
     *
     * @param row the provider the query resolves, or null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<ServiceProvider> query(ServiceProvider row) {
        PanacheQuery<ServiceProvider> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /** Tells whether a redirect carries a failure notice. */
    private boolean refused(Response response) {
        return response.getLocation().toString().contains("noticeOk=false");
    }

    /** The screen is declared in the settings menu (BO-03-11-01). */
    @Test
    void theScreenIsDeclaredInTheMenu() {
        Feature feature = Feature.SERVICE_PROVIDER;
        assertEquals("service_provider", feature.getKey());
        assertEquals("/admin/providers", feature.getPath());
        assertEquals(Menu.PARAMETRAGE, feature.getMenu());
    }

    /**
     * The page lists the declaration and the three catalogs — and hands the page NO
     * credential, which is the contract of this screen.
     */
    @Test
    void theProvidersPageListsTheDeclarationWithoutAnyCredential() {
        AdminProviderResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminProviders.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        ServiceProvider row = new ServiceProvider();
        row.code = "GIFT";
        row.authKey = "secret";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ServiceProvider.list("order by code")).thenReturn(List.of(row));
            assertSame(instance, resource.providersPage(null, "true"));
            verify(resource.adminProviders).data("providers", List.of(row));
            verify(instance).data("types", ServiceProvider.ProviderType.values());
            verify(instance).data("protocols", ServiceProvider.ProviderProtocol.values());
            verify(instance).data("phases", ServiceProvider.CallPhase.values());
            verify(instance, never()).data("authKey", "secret");
        }
    }

    /** Save refuses a blank code, an unknown type, protocol and call phase. */
    @Test
    void saveRefusesItsGuards() {
        AdminProviderResource resource = newResource();
        assertTrue(refused(resource.saveProvider(form())));
        assertTrue(refused(resource.saveProvider(form("code", "GIFT",
                "providerType", "NOPE"))));
        assertTrue(refused(resource.saveProvider(form("code", "GIFT",
                "providerType", "DEMATERIALISE", "protocol", "NOPE"))));
        assertTrue(refused(resource.saveProvider(form("code", "GIFT",
                "providerType", "DEMATERIALISE", "protocol", "NEXO_RETAILER",
                "callPhase", "NOPE"))));
    }

    /** A valid declaration is created with everything the form carried. */
    @Test
    void saveCreatesTheDeclaration() {
        AdminProviderResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ServiceProvider> created =
                        mockConstruction(ServiceProvider.class)) {
            PanacheQuery<ServiceProvider> queryGiftAbsent = query(null);
            panache.when(() -> ServiceProvider.find("code", "GIFT")).thenReturn(queryGiftAbsent);
            Response response = resource.saveProvider(validForm("authKey", "k-123"));
            assertFalse(refused(response));
            ServiceProvider provider = created.constructed().get(0);
            assertEquals("GIFT", provider.code);
            assertEquals("Cartes cadeaux", provider.label);
            assertEquals(ServiceProvider.ProviderType.DEMATERIALISE, provider.providerType);
            assertEquals(ServiceProvider.ProviderProtocol.NEXO_RETAILER, provider.protocol);
            assertEquals(ServiceProvider.CallPhase.AU_SCAN, provider.callPhase);
            assertEquals("https://gift.example", provider.endpoint);
            assertEquals("CARTES", provider.articleSelection);
            assertEquals("TKT", provider.documentTemplateCode);
            assertEquals("k-123", provider.authKey);
            assertTrue(provider.active);
            verify(provider, times(1)).persist();
        }
    }

    /** Reusing the code updates the row in place, and nothing is persisted twice. */
    @Test
    void saveUpdatesAnExistingDeclaration() {
        AdminProviderResource resource = newResource();
        ServiceProvider existing = new ServiceProvider();
        existing.code = "GIFT";
        existing.authKey = "old";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ServiceProvider> created =
                        mockConstruction(ServiceProvider.class)) {
            PanacheQuery<ServiceProvider> queryGiftFound = query(existing);
            panache.when(() -> ServiceProvider.find("code", "GIFT")).thenReturn(queryGiftFound);
            Response response = resource.saveProvider(validForm("label", "Cartes", "authKey",
                    "k-456"));
            assertFalse(refused(response));
            assertTrue(created.constructed().isEmpty());
            assertEquals("Cartes", existing.label);
            assertEquals("k-456", existing.authKey);
        }
    }

    /**
     * An EMPTY credential field leaves the stored key alone: the form cannot show
     * it, so an operator correcting a label posts an empty field every time, and
     * taking that literally would disconnect the provider.
     */
    @Test
    void anEmptyCredentialFieldKeepsTheStoredKey() {
        AdminProviderResource resource = newResource();
        ServiceProvider existing = new ServiceProvider();
        existing.code = "GIFT";
        existing.authKey = "k-original";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ServiceProvider> queryGiftFound = query(existing);
            panache.when(() -> ServiceProvider.find("code", "GIFT")).thenReturn(queryGiftFound);
            resource.saveProvider(validForm("authKey", ""));
            assertEquals("k-original", existing.authKey);
            resource.saveProvider(validForm("authKey", "   "));
            assertEquals("k-original", existing.authKey);
        }
    }

    /** An unchecked box takes the provider out of service. */
    @Test
    void anUncheckedBoxTakesTheProviderOutOfService() {
        AdminProviderResource resource = newResource();
        ServiceProvider existing = new ServiceProvider();
        existing.code = "GIFT";
        MultivaluedMap<String, String> posted = validForm();
        posted.remove("active");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ServiceProvider> queryGiftFound = query(existing);
            panache.when(() -> ServiceProvider.find("code", "GIFT")).thenReturn(queryGiftFound);
            resource.saveProvider(posted);
            assertFalse(existing.active);
        }
    }

    /** Clearing the credential needs a known provider, and empties the key. */
    @Test
    void clearingTheCredentialEmptiesTheKey() {
        AdminProviderResource resource = newResource();
        ServiceProvider existing = new ServiceProvider();
        existing.code = "GIFT";
        existing.authKey = "k-123";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ServiceProvider> queryGhostAbsent = query(null);
            panache.when(() -> ServiceProvider.find("code", "GHOST")).thenReturn(queryGhostAbsent);
            assertTrue(refused(resource.clearCredential(form("code", "GHOST"))));
            PanacheQuery<ServiceProvider> queryGiftFound = query(existing);
            panache.when(() -> ServiceProvider.find("code", "GIFT")).thenReturn(queryGiftFound);
            assertFalse(refused(resource.clearCredential(form("code", "GIFT"))));
            assertNull(existing.authKey);
        }
    }

    /** Delete needs a known provider, and removes the row. */
    @Test
    void deleteRemovesTheDeclaration() {
        AdminProviderResource resource = newResource();
        ServiceProvider existing = mock(ServiceProvider.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ServiceProvider> queryGhostAbsent = query(null);
            panache.when(() -> ServiceProvider.find("code", "GHOST")).thenReturn(queryGhostAbsent);
            assertTrue(refused(resource.deleteProvider(form("code", "GHOST"))));
            PanacheQuery<ServiceProvider> queryGiftFound = query(existing);
            panache.when(() -> ServiceProvider.find("code", "GIFT")).thenReturn(queryGiftFound);
            assertFalse(refused(resource.deleteProvider(form("code", "GIFT"))));
            verify(existing, times(1)).delete();
        }
    }
    /** BO-03-08-06/07: both absent key legs refuse substitutions before querying the referential. */
    @Test
    void providerMessagesRequireBothKeys() {
        AdminProviderResource resource = newResource();
        assertTrue(refused(resource.saveProviderMessage(form("messageCode", "4"))));
        assertTrue(refused(resource.saveProviderMessage(form("providerCode", "GIFT"))));
    }

    /** BO-03-08-06/07: substitutions create, update and delete without losing provider-specific wording. */
    @Test
    void providerMessagesFollowTheirOwnCrudCycle() {
        AdminProviderResource resource = newResource();
        var message = mock(com.intermarche.edge.domain.setting.ProviderMessage.class);
        PanacheQuery<com.intermarche.edge.domain.setting.ProviderMessage> lookup = mock(PanacheQuery.class);
        try (var base = mockStatic(PanacheEntityBase.class);
             var created = mockConstruction(com.intermarche.edge.domain.setting.ProviderMessage.class)) {
            base.when(() -> PanacheEntityBase.find("providerCode = ?1 and messageCode = ?2", "GIFT", "4")).thenReturn(lookup);
            assertFalse(refused(resource.saveProviderMessage(form("providerCode", "GIFT", "messageCode", "4", "screenText", "WAIT", "ticketText", "PAPER", "messageActive", "on"))));
            var inserted = created.constructed().get(0);
            assertEquals("GIFT", inserted.providerCode);
            assertEquals("4", inserted.messageCode);
            assertEquals("WAIT", inserted.screenText);
            assertEquals("PAPER", inserted.ticketText);
            assertTrue(inserted.active);
            verify(inserted).persist();
            when(lookup.firstResult()).thenReturn(message);
            assertFalse(refused(resource.saveProviderMessage(form("providerCode", "GIFT", "messageCode", "4", "screenText", "READY"))));
            assertEquals("READY", message.screenText);
            assertNull(message.ticketText);
            assertFalse(message.active);
            assertFalse(refused(resource.deleteProviderMessage(form("providerCode", "GIFT", "messageCode", "4"))));
            verify(message).delete();
            when(lookup.firstResult()).thenReturn(null);
            assertTrue(refused(resource.deleteProviderMessage(form("providerCode", "GIFT", "messageCode", "4"))));
        }
    }

    /** BO-03-11: an explicitly failed notice is passed to the provider page without becoming a success. */
    @Test
    void failedNoticeRemainsFailedOnTheProviderPage() {
        var resource = newResource();
        var view = mock(TemplateInstance.class, org.mockito.Mockito.RETURNS_SELF);
        when(resource.adminProviders.data(anyString(), any())).thenReturn(view);
        try (var base = mockStatic(PanacheEntityBase.class)) {
            assertSame(view, resource.providersPage("FAILED", "false"));
        }
        verify(view).data("noticeOk", false);
    }

}
