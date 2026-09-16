package com.intermarche.pos.service.sync;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Where this node's upstream store node lives.
 * <p>
 * The address is read by BOTH ends of the synchronization — the outbox that
 * pushes documents to the store, and the pull loop that fetches referentials
 * from it — so it is held here rather than on either of them. Before this
 * class existed the pull loop read the address off the outbox, which made the
 * common half of the synchronization depend on its register half: a
 * dependency in the wrong direction, and the one thing that would have
 * prevented splitting the two into separate modules.
 * <p>
 * A blank or absent {@code pos.sync.store-url} means the same thing
 * everywhere: this node has no store node, so the synchronization is off. The
 * trailing slash is dropped once, here, so no caller has to think about it.
 */
@ApplicationScoped
public class SyncEndpoints {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SyncEndpoints.class);

    /** URL of the store node; absent or blank = synchronization disabled. */
    @ConfigProperty(name = "pos.sync.store-url")
    Optional<String> storeUrl;

    /**
     * Default constructor, used by CDI; the address is field-injected.
     */
    public SyncEndpoints() {
        this.storeUrl = Optional.empty();
    }

    /**
     * Builds an instance addressing the given store node, for callers that
     * wire their own object graph instead of letting CDI do it.
     *
     * @param url the store node URL, or null for none
     * @return an instance answering for that URL
     */
    public static SyncEndpoints of(String url) {
        SyncEndpoints endpoints = new SyncEndpoints();
        endpoints.storeUrl = Optional.ofNullable(url);
        return endpoints;
    }

    /**
     * Indicates whether a store node is configured on this node.
     *
     * @return true when a non-blank store URL is configured
     */
    public boolean hasStoreUrl() {
        LOGGER.info("Entering method hasStoreUrl");
        LOGGER.info("Exiting method hasStoreUrl");
        return storeUrl.isPresent() && !storeUrl.get().isBlank();
    }

    /**
     * Returns the configured store node URL without a trailing slash.
     *
     * @return the store URL, or an empty string when none is configured
     */
    public String storeUrl() {
        LOGGER.info("Entering method storeUrl");
        if (!hasStoreUrl()) {
            LOGGER.info("Exiting method storeUrl");
            return "";
        }
        String url = storeUrl.get().trim();
        LOGGER.info("Exiting method storeUrl");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
