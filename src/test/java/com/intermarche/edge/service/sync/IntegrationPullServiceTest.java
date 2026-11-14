package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.domain.sync.IntegrationDeposit;
import com.intermarche.edge.imports.EngineFeedRelayResource;
import com.intermarche.edge.service.ExternalRelayService;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IntegrationPullService} (BO-11-01-01), targeting 100%
 * branch coverage.
 *
 * <p>Branch enumeration. {@code tick}: a node that is not a store, no feed
 * administered, the first pull, a pull not yet due and one due again.
 * {@code pullNow}: a feed with and without its scheme, each of the four legs
 * of the unreadable answer (unreached, below 200, 300 and above, blank), a
 * file identical to the last integrated and a new one. The two database
 * reads: reachable and unreachable providers, a code never deposited and one
 * that was. The loop: start, stop twice, a failing tick swallowed.
 */
class IntegrationPullServiceTest {

    /** The instant every pull below runs at. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 9, 0);

    /** The last deposit content each code answers, for the wired service. */
    private final Map<String, String> lastContents = new HashMap<>();

    /**
     * Builds a reachable commercial service.
     *
     * @param code its code
     * @return the provider
     */
    private ServiceProvider commercial(String code) {
        ServiceProvider provider = new ServiceProvider();
        provider.code = code;
        provider.providerType = ServiceProvider.ProviderType.COMMERCIAL;
        provider.endpoint = "https://gc/" + code;
        provider.authKey = "key";
        return provider;
    }

    /**
     * Wires a service over the given feeds, providers and relay, bypassing the
     * two database reads.
     *
     * @param feeds the administered feeds
     * @param providers the commercial services in service
     * @param relay the relay mock
     * @return the service
     */
    private IntegrationPullService service(List<String> feeds, List<ServiceProvider> providers,
            ExternalRelayService relay) {
        IntegrationPullService service = new IntegrationPullService() {
            @Override
            List<ServiceProvider> commercialProviders() {
                return providers;
            }

            @Override
            String lastFingerprint(String code) {
                return lastContents.get(code);
            }
        };
        service.role = "store";
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.integrationPullFeeds()).thenReturn(feeds);
        when(service.posSettingsService.integrationPullMinutes()).thenReturn(60);
        service.externalRelayService = relay;
        service.engineFeedRelayResource = mock(EngineFeedRelayResource.class);
        when(service.engineFeedRelayResource.integrate(anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(Response.ok("{}").build());
        service.integrationRetryService = mock(IntegrationRetryService.class);
        when(service.integrationRetryService.record(anyString(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(deposit(IntegrationDeposit.Status.APPLIED));
        return service;
    }

    /**
     * Builds the deposit the retry service records, in one status.
     *
     * @param status the status of the deposit
     * @return the deposit
     */
    private IntegrationDeposit deposit(IntegrationDeposit.Status status) {
        IntegrationDeposit deposit = new IntegrationDeposit();
        deposit.status = status;
        return deposit;
    }

    /**
     * Stubs the relay's answer to one path.
     *
     * @param relay the relay mock
     * @param path the path asked
     * @param reached whether the provider answered
     * @param status the status it answered
     * @param body the body it answered
     */
    private void answer(ExternalRelayService relay, String path, boolean reached, int status, String body) {
        when(relay.relay(any(), eq("GET"), eq(path), isNull(), anyLong(), eq("key")))
                .thenReturn(new ExternalRelayService.RelayResult(reached, status, body));
    }

    /**
     * Every administered feed is read from every commercial service and
     * integrated as a deposit — the scheme of a nomenclature carried — while an
     * unreadable answer, on each of its four legs, counts as a failure, a file
     * the import did not apply in full counts as a failure too, and a file
     * identical to the last integrated is left alone.
     */
    @Test
    void theFeedsArePulledAndIntegrated() {
        ExternalRelayService relay = mock(ExternalRelayService.class);
        answer(relay, "/PRODUCTS", true, 200, "ean|label\n1|A");
        answer(relay, "/NOMENCLATURE", true, 200, "code|label\n1|R");
        answer(relay, "/CUSTOMERS", false, 0, "");
        answer(relay, "/VAT_RATES", true, 100, "x");
        answer(relay, "/FAMILIES", true, 404, "x");
        answer(relay, "/PRICES", true, 204, " ");
        answer(relay, "/STORES", true, 200, "same");
        lastContents.put("STORES", EngineFeedService.sha256("same"));
        lastContents.put("PRODUCTS", EngineFeedService.sha256("ean|label\n1|B"));
        IntegrationPullService service = service(List.of("products", "NOMENCLATURE=IMKT", "CUSTOMERS",
                "VAT_RATES", "FAMILIES", "PRICES", "STORES"), List.of(commercial("GC")), relay);
        when(service.integrationRetryService.record(eq("NOMENCLATURE"), any(), any(), any(), any(), anyString(),
                any())).thenReturn(deposit(IntegrationDeposit.Status.REJECTED));
        IntegrationPullService.Outcome outcome = service.pullNow(NOW);
        Assertions.assertEquals(new IntegrationPullService.Outcome(1, 1, 5), outcome);
        Assertions.assertEquals(NOW, service.lastPull);
        verify(service.engineFeedRelayResource).integrate("PRODUCTS", null, null, null, null, "ean|label\n1|A");
        verify(service.engineFeedRelayResource).integrate("NOMENCLATURE", null, "IMKT", "IMKT", null,
                "code|label\n1|R");
        verify(service.integrationRetryService).record(eq("PRODUCTS"), isNull(), isNull(), isNull(), isNull(),
                eq("ean|label\n1|A"), any());
        verify(service.engineFeedRelayResource, never()).integrate(eq("STORES"), any(), any(), any(), any(),
                anyString());
    }

    /**
     * A pull runs on a store node with feeds administered, at first and then
     * once the administered frequency has elapsed — never before, never on
     * another node.
     */
    @Test
    void aPullRunsOnlyWhenDue() {
        ExternalRelayService relay = mock(ExternalRelayService.class);
        IntegrationPullService service = service(List.of("PRODUCTS"), List.of(), relay);
        Assertions.assertNotNull(service.tick(NOW));
        Assertions.assertNull(service.tick(NOW.plusMinutes(59)));
        Assertions.assertNotNull(service.tick(NOW.plusMinutes(60)));
        IntegrationPullService register = service(List.of("PRODUCTS"), List.of(), relay);
        register.role = "register";
        Assertions.assertNull(register.tick(NOW));
        IntegrationPullService idle = service(List.of(), List.of(), relay);
        Assertions.assertNull(idle.tick(NOW));
        Assertions.assertNull(idle.lastPull);
    }

    /** The loop starts, stops twice harmlessly, and survives a failing tick. */
    @Test
    void theLoopNeverDies() {
        IntegrationPullService service = service(List.of("PRODUCTS"), List.of(), mock(ExternalRelayService.class));
        when(service.posSettingsService.integrationPullFeeds()).thenThrow(new IllegalStateException("boom"));
        Assertions.assertDoesNotThrow(service::tickSafely);
        service.onStart(null);
        Assertions.assertDoesNotThrow(service::stop);
        Assertions.assertDoesNotThrow(service::stop);
        Assertions.assertDoesNotThrow(new IntegrationPullService()::stop);
        IntegrationPullService quiet = service(List.of(), List.of(), mock(ExternalRelayService.class));
        Assertions.assertDoesNotThrow(quiet::tickSafely);
    }

    /**
     * Runs the transaction bodies the service hands to QuarkusTransaction.
     *
     * @param tx the QuarkusTransaction static mock
     */
    private void inPlace(MockedStatic<QuarkusTransaction> tx) {
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
        when(runner.call(any())).thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());
    }

    /** The commercial services are read in a transaction, the unreachable ones and the sales receivers left out. */
    @Test
    void theCommercialServicesAreReadInATransaction() {
        ServiceProvider reachable = commercial("GC");
        ServiceProvider deaf = commercial("GC2");
        deaf.endpoint = null;
        ServiceProvider stream = commercial("TFR");
        stream.protocol = ServiceProvider.ProviderProtocol.TFREMA;
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            panache.when(() -> ServiceProvider.list("providerType = ?1 and active = true order by code",
                    ServiceProvider.ProviderType.COMMERCIAL)).thenReturn(List.of(reachable, deaf, stream));
            Assertions.assertEquals(List.of(reachable), new IntegrationPullService().commercialProviders());
        }
    }

    /** The fingerprint of the last deposit of a code is read in a transaction, nothing when none was made. */
    @Test
    @SuppressWarnings("unchecked")
    void theLastDepositIsReadInATransaction() {
        IntegrationDeposit deposit = new IntegrationDeposit();
        deposit.contentHash = "fingerprint";
        PanacheQuery<IntegrationDeposit> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(deposit);
        PanacheQuery<IntegrationDeposit> none = mock(PanacheQuery.class);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            panache.when(() -> IntegrationDeposit.find("code = ?1 order by depositedAt desc, id desc", "PRODUCTS"))
                    .thenReturn(found);
            panache.when(() -> IntegrationDeposit.find("code = ?1 order by depositedAt desc, id desc", "PRICES"))
                    .thenReturn(none);
            IntegrationPullService service = new IntegrationPullService();
            Assertions.assertEquals("fingerprint", service.lastFingerprint("PRODUCTS"));
            Assertions.assertNull(service.lastFingerprint("PRICES"));
        }
    }

    /** The relay is asked at the provider's own address, with its key. */
    @Test
    void theProviderAddressIsAsked() {
        ExternalRelayService relay = mock(ExternalRelayService.class);
        answer(relay, "/PRODUCTS", true, 200, "x");
        service(List.of("PRODUCTS"), List.of(commercial("GC")), relay).pullNow(NOW);
        verify(relay).relay(eq(Optional.of("https://gc/GC")), eq("GET"), eq("/PRODUCTS"), isNull(), anyLong(),
                eq("key"));
    }
}
