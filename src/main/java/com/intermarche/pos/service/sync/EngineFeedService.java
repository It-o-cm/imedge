package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.sync.EngineFeed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Keeper of the verbatim engine feeds on this POS node: stores the sealed
 * parcels ({@link EngineFeed}), knows the feed catalog (codes, delivery
 * order, engine import paths) and records the engine's acknowledgements.
 * <p>
 * The catalog order IS the delivery order — shared referentials first,
 * engine-specific feeds last — so a promotion can never reach the engine
 * before the products it references (application-order doctrine of the
 * single-import-line design).
 * <p>
 * Placement: {@code service.sync} — feeds are stored by the CSV imports,
 * shipped by the referential pull and delivered by the delivery loop; this
 * service serves those services, never a screen.
 */
@ApplicationScoped
public class EngineFeedService {

    private static final Logger LOGGER = Logger.getLogger(EngineFeedService.class);

    /**
     * One catalog entry: a feed code and the import path of the local
     * valuation engine that ingests it.
     *
     * @param code the feed code
     * @param enginePath the imvaluation import endpoint path
     */
    public record FeedDef(String code, String enginePath) {
    }

    /**
     * The feed catalog, in DELIVERY ORDER (shared referentials before the
     * engine-specific feeds; offers last, they reference everything else).
     */
    public static final List<FeedDef> CATALOG = List.of(
            new FeedDef("STORES", "/stores/import"),
            new FeedDef("STORE_GROUPS", "/store-groups/import"),
            new FeedDef("PRODUCTS", "/products/import"),
            new FeedDef("FAMILIES", "/product-families/import"),
            new FeedDef("CATEGORY_STORAGES", "/product-category-storages/import"),
            new FeedDef("PRICES", "/prices/import"),
            new FeedDef("OFFERS", "/offers/import"));

    /**
     * Finds a catalog entry by feed code.
     *
     * @param code the feed code
     * @return the catalog entry, or empty for an unknown code
     */
    public static Optional<FeedDef> catalogEntry(String code) {
        LOGGER.info("Entering method catalogEntry with code: " + code);
        LOGGER.info("Exiting method catalogEntry");
        return CATALOG.stream().filter(def -> def.code().equals(code)).findFirst();
    }

    /**
     * Immutable snapshot of one feed awaiting delivery, detached from the
     * persistence context so the delivery thread can use it freely.
     *
     * @param code the feed code
     * @param enginePath the engine import endpoint path
     * @param content the raw CSV content to POST
     * @param version the version being delivered
     * @param lastError the last recorded delivery error, or null
     */
    public record PendingFeed(String code, String enginePath, String content,
                              String version, String lastError) {
    }

    /**
     * The engine sync state of one feed, flattened for a supervision screen:
     * the stored version, the version the engine last acknowledged, whether
     * the two match, the last delivery error, and the two timestamps. This is
     * the read-only projection the sync supervision page renders per catalog
     * code (BO-08-01-07/08/16, BO-08-04-08); it carries no content.
     *
     * @param code the feed code
     * @param version the stored version (SHA-256 hex)
     * @param appliedVersion the last version the engine acknowledged, or null
     * @param applied whether the stored version equals the acknowledged one
     * @param lastError the last delivery error toward the engine, or null
     * @param receivedAt when the stored version reached this node
     * @param appliedAt when the engine acknowledged the applied version, or null
     */
    public record FeedState(String code, String version, String appliedVersion,
                            boolean applied, String lastError,
                            LocalDateTime receivedAt, LocalDateTime appliedAt) {
    }

    /**
     * Returns the engine sync state of every stored feed, in CATALOG order:
     * the read-only projection the supervision screen renders (BO-08-01-07/08/16,
     * BO-08-04-08). A catalog code this node never received is skipped (null
     * arm), exactly as the historical {@code /feeds/import/status} endpoint
     * skips it. Transactional so the read has a valid persistence session on
     * whatever thread calls it.
     *
     * @return the per-feed states, in catalog order, never null
     */
    @Transactional
    public List<FeedState> feedStates() {
        LOGGER.info("Entering method feedStates");
        List<FeedState> states = new java.util.ArrayList<>();
        for (FeedDef def : CATALOG) {
            EngineFeed feed = EngineFeed.findByCode(def.code());
            if (feed == null) {
                continue;
            }
            states.add(new FeedState(feed.code, feed.version, feed.appliedVersion,
                    feed.version.equals(feed.appliedVersion), feed.lastError,
                    feed.receivedAt, feed.appliedAt));
        }
        LOGGER.info("Exiting method feedStates");
        return states;
    }

    /**
     * Returns the feeds whose stored version the engine has not
     * acknowledged yet, in CATALOG (delivery) order, as detached
     * snapshots. Transactional so the delivery thread — which has no
     * request context of its own — gets a valid persistence session.
     *
     * @return the pending feeds, empty when the engine is up to date
     */
    @Transactional
    public List<PendingFeed> pendingFeeds() {
        LOGGER.info("Entering method pendingFeeds");
        List<PendingFeed> pending = new java.util.ArrayList<>();
        for (FeedDef def : CATALOG) {
            EngineFeed feed = EngineFeed.findByCode(def.code());
            if (feed == null || feed.version.equals(feed.appliedVersion)) continue;
            pending.add(new PendingFeed(feed.code, def.enginePath(),
                    feed.content, feed.version, feed.lastError));
        }
        LOGGER.info("Exiting method pendingFeeds");
        return pending;
    }

    /**
     * Stores (or refreshes) the verbatim content of a feed on this node.
     * The version is the SHA-256 of the content: storing the same file
     * twice changes nothing, and a changed file changes the version by
     * construction — that is what wakes both the pull (store to register)
     * and the delivery loop (register to engine).
     *
     * @param code the feed code, already validated against the catalog
     * @param content the raw CSV content, verbatim
     * @return the stored version (SHA-256 hex)
     */
    @Transactional
    public String store(String code, String content) {
        LOGGER.info("Entering method store with code: " + code + ", content: " + content);
        String version = sha256(content);
        EngineFeed feed = EngineFeed.findByCode(code);
        if (feed == null) {
            feed = new EngineFeed();
            feed.code = code;
        } else if (version.equals(feed.version)) {
            LOGGER.info("Exiting method store");
            return version;
        }
        feed.content = content;
        feed.version = version;
        feed.receivedAt = LocalDateTime.now();
        feed.persist();
        LOGGER.infof("Flux moteur %s enregistré (version %s)", code, version.substring(0, 12));
        LOGGER.info("Exiting method store");
        return version;
    }

    /**
     * Records the engine's acknowledgement of a delivered version and
     * clears the delivery error.
     *
     * @param code the feed code
     * @param version the version the engine just ingested
     */
    @Transactional
    public void markApplied(String code, String version) {
        LOGGER.info("Entering method markApplied with code: " + code + ", version: " + version);
        EngineFeed feed = EngineFeed.findByCode(code);
        if (feed == null) { LOGGER.info("Exiting method markApplied"); return; }
        feed.appliedVersion = version;
        feed.appliedAt = LocalDateTime.now();
        feed.lastError = null;
        feed.persist();
        LOGGER.info("Exiting method markApplied");
    }

    /**
     * Records a delivery failure toward the engine, truncated to the
     * column size.
     *
     * @param code the feed code
     * @param error the failure message
     */
    @Transactional
    public void markError(String code, String error) {
        LOGGER.info("Entering method markError with code: " + code + ", error: " + error);
        EngineFeed feed = EngineFeed.findByCode(code);
        if (feed == null) { LOGGER.info("Exiting method markError"); return; }
        feed.lastError = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        feed.persist();
        LOGGER.info("Exiting method markError");
    }

    /**
     * Computes the SHA-256 of a content, hex encoded.
     *
     * @param content the content to hash
     * @return the 64-character hex digest
     */
    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
