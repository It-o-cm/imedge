package com.intermarche.edge.domain.util;

/**
 * An engine feed projected from rows the {@link DataInitializer} seeds, to be
 * refreshed once the seed is written — on the store node, the campaigns'
 * {@code BUDGET_GROUPS} feed.
 *
 * <p>Declared here, next to the seeder both nodes share, so the seeder never
 * names a store-only service: the store node implements it, the register
 * declares no implementation and its seed projects nothing.
 */
public interface SeedFeedProjector {

    /**
     * Renders the feed from the current rows and stores it, replacing the
     * previous version.
     *
     * @return the stored version
     */
    String project();
}
