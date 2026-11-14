package com.intermarche.edge.service.export;

import com.intermarche.edge.domain.catalog.EegProvider;
import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.ui.admin.EegGateway;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EegPriceFlowService} (BO-11-03-05), targeting 100%
 * branch coverage.
 *
 * <p>Branch enumeration. {@code tick}: the label flow off, the automatic
 * sending off, the first pass, a pass not yet due and one due again.
 * {@code pass}: a provider never acknowledged receiving every price, one
 * acknowledged receiving the changes, one with nothing to receive, one
 * refusing — its watermark kept for the next pass. The database reads: every
 * active article or the moved prices, and each refusal of the current price
 * (unknown article, inactive, no EAN, no price, no amount). The loop: start,
 * stop twice, a failing tick swallowed.
 */
class EegPriceFlowServiceTest {

    /** The instant of the passes below. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 9, 0);

    /** The prices served for a watermark, keyed by its text ("null" for none). */
    private final Map<String, Map<String, BigDecimal>> served = new HashMap<>();

    /**
     * Builds a label provider.
     *
     * @param code its code
     * @return the provider
     */
    private EegProvider provider(String code) {
        EegProvider provider = new EegProvider();
        provider.code = code;
        provider.label = code;
        return provider;
    }

    /**
     * Wires a service over the given providers, both switches on, serving the
     * prices of {@link #served}.
     *
     * @param providers the active providers
     * @return the service
     */
    private EegPriceFlowService service(List<EegProvider> providers) {
        EegPriceFlowService service = new EegPriceFlowService() {
            @Override
            List<EegProvider> activeProviders() {
                return providers;
            }

            @Override
            Map<String, BigDecimal> pricesChangedSince(LocalDateTime since, LocalDateTime now) {
                return served.getOrDefault(String.valueOf(since), Map.of());
            }
        };
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.eegEnabled()).thenReturn(true);
        when(service.posSettingsService.eegAutoPushEnabled()).thenReturn(true);
        when(service.posSettingsService.eegAutoPushSeconds()).thenReturn(300);
        service.eegGateway = mock(EegGateway.class);
        return service;
    }

    /**
     * A provider never acknowledged receives every current price; once
     * acknowledged, it receives the changes since; a pass with nothing changed
     * moves its watermark without a call; a refusal keeps the watermark, so
     * the next pass sends again.
     */
    @Test
    void eachProviderReceivesWhatItMissed() {
        EegProvider wall = provider("WALL");
        EegProvider down = provider("DOWN");
        EegPriceFlowService service = service(List.of(wall, down));
        served.put("null", Map.of("3250390000001", new BigDecimal("1.99")));
        when(service.eegGateway.push(wall, Map.of("3250390000001", new BigDecimal("1.99")))).thenReturn(true);
        Assertions.assertEquals(1, service.pass(NOW));
        Assertions.assertEquals(NOW, service.watermarks.get("WALL"));
        Assertions.assertNull(service.watermarks.get("DOWN"));
        LocalDateTime later = NOW.plusMinutes(5);
        served.put(NOW.toString(), Map.of("3250390000002", new BigDecimal("2.50")));
        when(service.eegGateway.push(wall, Map.of("3250390000002", new BigDecimal("2.50")))).thenReturn(true);
        Assertions.assertEquals(1, service.pass(later));
        Assertions.assertEquals(later, service.watermarks.get("WALL"));
        Assertions.assertEquals(0, service.pass(later.plusMinutes(5)));
        Assertions.assertEquals(later.plusMinutes(5), service.watermarks.get("WALL"));
        verify(service.eegGateway, never()).push(wall, Map.of());
    }

    /**
     * A pass runs with the label flow and its automatic sending on, at first
     * and once the cadence has elapsed — never before.
     */
    @Test
    void aPassRunsOnlyWhenDue() {
        EegPriceFlowService service = service(List.of());
        Assertions.assertEquals(0, service.tick(NOW));
        Assertions.assertEquals(-1, service.tick(NOW.plusSeconds(299)));
        Assertions.assertEquals(0, service.tick(NOW.plusSeconds(300)));
        EegPriceFlowService off = service(List.of());
        when(off.posSettingsService.eegEnabled()).thenReturn(false);
        Assertions.assertEquals(-1, off.tick(NOW));
        EegPriceFlowService manual = service(List.of());
        when(manual.posSettingsService.eegAutoPushEnabled()).thenReturn(false);
        Assertions.assertEquals(-1, manual.tick(NOW));
        Assertions.assertNull(manual.lastPass);
    }

    /** The loop starts, stops twice harmlessly, and survives a failing tick. */
    @Test
    void theLoopNeverDies() {
        EegPriceFlowService service = service(List.of());
        when(service.posSettingsService.eegEnabled()).thenThrow(new IllegalStateException("boom"));
        Assertions.assertDoesNotThrow(service::tickSafely);
        service.onStart(null);
        Assertions.assertDoesNotThrow(service::stop);
        Assertions.assertDoesNotThrow(service::stop);
        Assertions.assertDoesNotThrow(new EegPriceFlowService()::stop);
        EegPriceFlowService quiet = service(List.of());
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

    /**
     * Builds an article.
     *
     * @param id its id
     * @param ean its EAN, possibly null
     * @param active whether it is sold
     * @return the article
     */
    private Product product(long id, String ean, boolean active) {
        Product product = new Product();
        product.id = id;
        product.ean = ean;
        product.active = active;
        return product;
    }

    /**
     * Builds a price of an article.
     *
     * @param product the article
     * @param amount the tax-included amount, possibly null
     * @return the price
     */
    private Price price(Product product, String amount) {
        Price price = new Price();
        price.product = product;
        price.priceIncludingTax = amount == null ? null : new BigDecimal(amount);
        return price;
    }

    /**
     * Stubs the current price lookup of an article.
     *
     * @param panache the Panache static mock
     * @param productId the article's id
     * @param price the current price, possibly null
     */
    @SuppressWarnings("unchecked")
    private void stubCurrent(MockedStatic<PanacheEntityBase> panache, long productId, Price price) {
        PanacheQuery<Price> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(price);
        panache.when(() -> Price.find(anyString(), org.mockito.ArgumentMatchers.eq(productId), any()))
                .thenReturn(query);
    }

    /**
     * Without a watermark every active article is priced, the price rounded to
     * the cent; with one, only the articles whose price moved — created or
     * changed, coming into force or out of it — are.
     */
    @Test
    void theChangedPricesAreReadInATransaction() {
        Product sold = product(1L, "3250390000001", true);
        Product moved = product(2L, "3250390000002", true);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            panache.when(() -> Product.list("active = true")).thenReturn(List.of(sold));
            panache.when(() -> Price.list("updatedAt > ?1 or (startDateTime > ?1 and startDateTime <= ?2)"
                    + " or (endDateTime > ?1 and endDateTime <= ?2)", NOW.minusHours(1), NOW))
                    .thenReturn(List.of(price(moved, "2.5"), price(moved, "2.5")));
            panache.when(() -> Product.findById(1L)).thenReturn(sold);
            panache.when(() -> Product.findById(2L)).thenReturn(moved);
            stubCurrent(panache, 1L, price(sold, "1.999"));
            stubCurrent(panache, 2L, price(moved, "2.5"));
            EegPriceFlowService service = new EegPriceFlowService();
            Assertions.assertEquals(Map.of("3250390000001", new BigDecimal("2.00")),
                    service.pricesChangedSince(null, NOW));
            Assertions.assertEquals(Map.of("3250390000002", new BigDecimal("2.50")),
                    service.pricesChangedSince(NOW.minusHours(1), NOW));
        }
    }

    /**
     * An unknown article, an inactive one, one without an EAN, one without a
     * current price and one whose price states no amount are never sent.
     */
    @Test
    void theArticlesThatCannotBeLabelledAreLeftOut() {
        Product inactive = product(2L, "B", false);
        Product noEan = product(3L, null, true);
        Product noPrice = product(4L, "D", true);
        Product noAmount = product(5L, "E", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(null);
            panache.when(() -> Product.findById(2L)).thenReturn(inactive);
            panache.when(() -> Product.findById(3L)).thenReturn(noEan);
            panache.when(() -> Product.findById(4L)).thenReturn(noPrice);
            panache.when(() -> Product.findById(5L)).thenReturn(noAmount);
            stubCurrent(panache, 1L, null);
            stubCurrent(panache, 2L, price(inactive, "1.00"));
            stubCurrent(panache, 3L, price(noEan, "1.00"));
            stubCurrent(panache, 4L, null);
            stubCurrent(panache, 5L, price(noAmount, null));
            Set<Long> ids = new LinkedHashSet<>(List.of(1L, 2L, 3L, 4L, 5L));
            Assertions.assertEquals(Map.of(), new EegPriceFlowService().currentPrices(ids));
        }
    }

    /** The active providers are read in a transaction. */
    @Test
    void theProvidersAreReadInATransaction() {
        List<EegProvider> providers = List.of(provider("WALL"));
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
                MockedStatic<EegProvider> eeg = mockStatic(EegProvider.class)) {
            inPlace(tx);
            eeg.when(EegProvider::listActive).thenReturn(providers);
            Assertions.assertSame(providers, new EegPriceFlowService().activeProviders());
        }
    }
}
