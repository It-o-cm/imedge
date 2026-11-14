package com.intermarche.edge.service.export;

import com.intermarche.edge.domain.catalog.EegProvider;
import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.ui.admin.EegGateway;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The AUTOMATIC SENDING of the sale prices to the electronic shelf labels
 * (BO-11-03-05): every price created, changed or coming into force is sent, on
 * its own, to every active label provider of the point of sale — a store may
 * run several — without anybody pushing a list from the article screen.
 *
 * <p>Each provider keeps its OWN watermark: the instant of the last pass it
 * acknowledged. A provider that was down receives, at the next pass, every
 * price changed since it last answered, while the others are not sent them a
 * second time. A provider never acknowledged — the first pass after a start —
 * receives every current price, so a wall of labels is aligned on what the
 * tills charge as soon as the flow runs.
 *
 * <p>Subject to activation twice, as the requirement asks: the label flow
 * itself ({@code eeg.enabled}) and its automatic sending. Resolved per tick:
 * the switches and the cadence are read on every pass, and every database read
 * runs in a transaction of its own, the loop having none.
 */
@ApplicationScoped
public class EegPriceFlowService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EegPriceFlowService.class);

    /** How often the loop looks whether a pass is due, in seconds. */
    static final int TICK_SECONDS = 10;

    /** The administered switches and cadence. */
    @Inject
    PosSettingsService posSettingsService;

    /** The connectors of the label providers. */
    @Inject
    EegGateway eegGateway;

    /** The instant of the last pass each provider acknowledged, by provider code. */
    final Map<String, LocalDateTime> watermarks = new HashMap<>();

    /** When the last pass ran, null before the first. */
    LocalDateTime lastPass;

    /** The loop, null until the node has started. */
    private ScheduledExecutorService executor;

    /**
     * Starts the loop.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "eeg-prices");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tickSafely, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the loop.
     */
    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one tick, swallowing whatever it fails on.
     */
    void tickSafely() {
        try {
            tick(LocalDateTime.now());
        } catch (RuntimeException failure) {
            LOGGER.warnf("Envoi automatique des prix aux étiquettes impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Runs a pass when one is due: both switches on and the administered
     * cadence elapsed since the last pass.
     *
     * @param now the current instant
     * @return how many providers acknowledged the pass, or -1 when none was due
     */
    int tick(LocalDateTime now) {
        if (!posSettingsService.eegEnabled() || !posSettingsService.eegAutoPushEnabled()) {
            return -1;
        }
        if (lastPass != null && now.isBefore(lastPass.plusSeconds(posSettingsService.eegAutoPushSeconds()))) {
            return -1;
        }
        lastPass = now;
        return pass(now);
    }

    /**
     * Sends each active provider the prices changed since its watermark, every
     * current price to a provider never acknowledged, and moves the watermark
     * of each provider that took them.
     *
     * @param now the instant of the pass
     * @return how many providers acknowledged
     */
    int pass(LocalDateTime now) {
        int acknowledged = 0;
        for (EegProvider provider : activeProviders()) {
            LocalDateTime since = watermarks.get(provider.code);
            Map<String, BigDecimal> prices = pricesChangedSince(since, now);
            if (prices.isEmpty()) {
                watermarks.put(provider.code, now);
                continue;
            }
            if (eegGateway.push(provider, prices)) {
                watermarks.put(provider.code, now);
                acknowledged++;
                LOGGER.infof("Étiquettes %s : %d prix envoyé(s).", provider.code, prices.size());
            } else {
                LOGGER.warnf("Étiquettes %s : envoi de %d prix refusé, repris au prochain passage.",
                        provider.code, prices.size());
            }
        }
        return acknowledged;
    }

    /**
     * Reads the active label providers.
     *
     * @return the providers, in code order
     */
    List<EegProvider> activeProviders() {
        return QuarkusTransaction.requiringNew().call(EegProvider::listActive);
    }

    /**
     * Reads the current prices of the articles whose price moved since an
     * instant, or of every active article when there is none.
     *
     * @param since the watermark, or null for every current price
     * @param now the instant of the pass
     * @return the current tax-included prices by EAN, two decimals
     */
    Map<String, BigDecimal> pricesChangedSince(LocalDateTime since, LocalDateTime now) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Set<Long> productIds = new LinkedHashSet<>();
            if (since == null) {
                for (Product product : Product.<Product>list("active = true")) {
                    productIds.add(product.id);
                }
            } else {
                for (Price moved : Price.<Price>list("updatedAt > ?1"
                        + " or (startDateTime > ?1 and startDateTime <= ?2)"
                        + " or (endDateTime > ?1 and endDateTime <= ?2)", since, now)) {
                    productIds.add(moved.product.id);
                }
            }
            return currentPrices(productIds);
        });
    }

    /**
     * Resolves the current price of each article, active ones only.
     *
     * @param productIds the articles
     * @return the current tax-included prices by EAN, two decimals
     */
    Map<String, BigDecimal> currentPrices(Set<Long> productIds) {
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (Long productId : productIds) {
            Product product = Product.findById(productId);
            Price price = Price.findCurrentPrice(productId);
            if (product != null && product.active && product.ean != null
                    && price != null && price.priceIncludingTax != null) {
                prices.put(product.ean, price.priceIncludingTax.setScale(2, RoundingMode.HALF_UP));
            }
        }
        return prices;
    }
}
