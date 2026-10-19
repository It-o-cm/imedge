package com.intermarche.edge.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ServiceProvider} (BO-03-11-01 to 06), targeting 100%
 * branch coverage.
 *
 * <p>Branch enumeration, leg by leg. {@code hasCredential}: null, blank, set —
 * a credential made of spaces authenticates nothing and must not read as
 * configured. {@code isCalledDuringSale}: null phase, the out-of-sale phase, each
 * selling phase. {@code isReachable}: out of service, no address, a blank address,
 * no credential, and the nominal case — four refusals that do not mean the same
 * thing. {@code findByCode}: null, blank, unknown, found. The three catalogs keep
 * their labels.
 */
class ServiceProviderTest {

    /**
     * Builds a provider with an address and a credential.
     *
     * @param endpoint the address, possibly null
     * @param authKey the credential, possibly null
     * @param active whether it is in service
     * @return the provider
     */
    private ServiceProvider provider(String endpoint, String authKey, boolean active) {
        ServiceProvider serviceProvider = new ServiceProvider();
        serviceProvider.code = "GIFT";
        serviceProvider.endpoint = endpoint;
        serviceProvider.authKey = authKey;
        serviceProvider.active = active;
        return serviceProvider;
    }

    /** A credential is stored, or it is not — and spaces are not. */
    @Test
    void aCredentialMadeOfSpacesIsNoCredential() {
        Assertions.assertFalse(provider("https://x", null, true).hasCredential());
        Assertions.assertFalse(provider("https://x", "   ", true).hasCredential());
        Assertions.assertTrue(provider("https://x", "k", true).hasCredential());
    }

    /** A provider is called during a sale at the three selling moments, never else. */
    @Test
    void onlyTheThreeSellingMomentsAreCallsDuringASale() {
        ServiceProvider serviceProvider = provider("https://x", "k", true);
        serviceProvider.callPhase = null;
        Assertions.assertFalse(serviceProvider.isCalledDuringSale());
        serviceProvider.callPhase = ServiceProvider.CallPhase.AUCUNE;
        Assertions.assertFalse(serviceProvider.isCalledDuringSale());
        serviceProvider.callPhase = ServiceProvider.CallPhase.AU_SCAN;
        Assertions.assertTrue(serviceProvider.isCalledDuringSale());
        serviceProvider.callPhase = ServiceProvider.CallPhase.AU_TOTAL;
        Assertions.assertTrue(serviceProvider.isCalledDuringSale());
        serviceProvider.callPhase = ServiceProvider.CallPhase.FIN_PAIEMENT;
        Assertions.assertTrue(serviceProvider.isCalledDuringSale());
    }

    /**
     * Reachable means in service, addressed AND credentialed: each of the four
     * refusals on its own.
     */
    @Test
    void reachableRefusesOnEachLeg() {
        Assertions.assertFalse(provider("https://x", "k", false).isReachable());
        Assertions.assertFalse(provider(null, "k", true).isReachable());
        Assertions.assertFalse(provider("   ", "k", true).isReachable());
        Assertions.assertFalse(provider("https://x", null, true).isReachable());
        Assertions.assertTrue(provider("https://x", "k", true).isReachable());
    }

    /** A blank or absent code finds nothing, without reaching the database. */
    @Test
    void findByCodeRefusesABlankCode() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Assertions.assertNull(ServiceProvider.findByCode(null));
            Assertions.assertNull(ServiceProvider.findByCode("   "));
            panache.verifyNoInteractions();
        }
    }

    /** A code is trimmed before the lookup, and an unknown one resolves to null. */
    @Test
    @SuppressWarnings("unchecked")
    void findByCodeTrimsAndResolves() {
        ServiceProvider found = provider("https://x", "k", true);
        PanacheQuery<ServiceProvider> hit = mock(PanacheQuery.class);
        when(hit.firstResult()).thenReturn(found);
        PanacheQuery<ServiceProvider> miss = mock(PanacheQuery.class);
        when(miss.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ServiceProvider.find("code", "GIFT")).thenReturn(hit);
            panache.when(() -> ServiceProvider.find("code", "GHOST")).thenReturn(miss);
            Assertions.assertSame(found, ServiceProvider.findByCode("  GIFT  "));
            Assertions.assertNull(ServiceProvider.findByCode("GHOST"));
        }
    }

    /** The active providers of one kind are read in code order. */
    @Test
    void activeProvidersOfATypeAreReadInCodeOrder() {
        ServiceProvider row = provider("https://x", "k", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ServiceProvider.list(
                            "providerType = ?1 and active = true order by code",
                            ServiceProvider.ProviderType.DEMATERIALISE))
                    .thenReturn(List.of(row));
            Assertions.assertEquals(List.of(row), ServiceProvider.listActiveOfType(
                    ServiceProvider.ProviderType.DEMATERIALISE));
        }
    }

    /** The whole declaration is read in code order. */
    @Test
    void everyProviderIsReadInCodeOrder() {
        ServiceProvider row = provider("https://x", "k", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ServiceProvider.list("order by code")).thenReturn(List.of(row));
            Assertions.assertEquals(List.of(row), ServiceProvider.listAllOrdered());
        }
    }

    /** Every catalog entry carries an operator-facing label. */
    @Test
    void everyCatalogEntryIsLabelled() {
        for (ServiceProvider.ProviderType type : ServiceProvider.ProviderType.values()) {
            Assertions.assertFalse(type.getLabel().isBlank(), type.name());
        }
        for (ServiceProvider.ProviderProtocol protocol
                : ServiceProvider.ProviderProtocol.values()) {
            Assertions.assertFalse(protocol.getLabel().isBlank(), protocol.name());
        }
        for (ServiceProvider.CallPhase phase : ServiceProvider.CallPhase.values()) {
            Assertions.assertFalse(phase.getLabel().isBlank(), phase.name());
        }
    }

    /** The defaults of a fresh declaration are the safe ones. */
    @Test
    void aFreshDeclarationDefaultsToSafeValues() {
        ServiceProvider fresh = new ServiceProvider();
        Assertions.assertEquals(ServiceProvider.ProviderType.DEMATERIALISE, fresh.providerType);
        Assertions.assertEquals(ServiceProvider.ProviderProtocol.HTTP_JSON, fresh.protocol);
        // Out of sale by default: a provider is not put on the checkout path by
        // forgetting to say otherwise.
        Assertions.assertEquals(ServiceProvider.CallPhase.AUCUNE, fresh.callPhase);
        Assertions.assertTrue(fresh.active);
        Assertions.assertFalse(fresh.hasCredential());
    }
}
