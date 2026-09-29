package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.domain.sync.RefState;
import com.intermarche.pos.domain.sync.ScheduledDelivery;
import com.intermarche.pos.service.sync.EngineFeedDeliveryService;
import com.intermarche.pos.service.sync.EngineFeedService;
import com.intermarche.pos.service.sync.RefPullService;
import com.intermarche.pos.service.sync.ScheduledDeliveryService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Read-only assembly of the SYNC SUPERVISION view (lot C2): the three outbound
 * flows of this node laid side by side so a supervisor sees, without a query,
 * where the register stands against the store and the local engine.
 * <ul>
 *   <li>ENGINE FEEDS — the verbatim parcels delivered to the local valuation
 *       engine, with their acknowledged version and last delivery error
 *       (BO-08-01-07/08/16, BO-08-04-08).</li>
 *   <li>REFERENTIAL PULL — the per-domain applied fingerprints and the last
 *       successful pull cycle (BO-08-04-04, BO-08-03-08).</li>
 *   <li>STORE OUTBOX — the push backlog grouped by kind, with the worst
 *       attempt count and last error (BO-08-04-03, BO-11-02-05).</li>
 * </ul>
 * <p>
 * The node knows ONLY itself: this is the local outbound state, never a
 * consolidated view of other registers (single-node invariant). Everything is
 * pre-formatted here so the template stays a dumb renderer — the dashboard
 * doctrine.
 * <p>
 * Placement: {@code ui.supervision} — this assembly serves the supervision
 * screen alone, not another service, so it belongs beside its resource rather
 * than in {@code service}.
 */
@ApplicationScoped
public class SyncSupervisionService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SyncSupervisionService.class);

    /** How a version or fingerprint hash is shortened for the screen. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");

    /** The engine feed keeper (states of the verbatim parcels). */
    @Inject
    EngineFeedService engineFeedService;

    /** The referential pull loop (last successful cycle, cadence). */
    @Inject
    RefPullService refPullService;

    /** The engine feed delivery loop (cadence). */
    @Inject
    EngineFeedDeliveryService engineFeedDeliveryService;

    /** The deferred deliveries keeper (the "Planifications" section). */
    @Inject
    ScheduledDeliveryService scheduledDeliveryService;

    /** The store outbox (push backlog). */
    @Inject
    SyncOutboxService syncOutboxService;

    /** The store-push cadence, surfaced read-only (BO-08-03-08). */
    @ConfigProperty(name = "pos.sync.interval-seconds", defaultValue = "10")
    long pushIntervalSeconds;

    /** One engine-feed row, flattened and pre-formatted for the screen. */
    public static class FeedRow {
        /** The feed code (PRODUCTS, FAMILIES, OFFERS...). */
        public String code;
        /** The stored version, shortened to twelve characters. */
        public String version;
        /** The acknowledged version, shortened, or a dash when none. */
        public String appliedVersion;
        /** Whether the engine acknowledged the stored version. */
        public boolean applied;
        /** The last delivery error toward the engine, or null when clean. */
        public String lastError;
        /** When the stored version reached this node. */
        public String receivedAt;
        /** When the engine acknowledged, or a dash when it never did. */
        public String appliedAt;
    }

    /** One deferred-delivery row, pre-formatted for the "Planifications" section. */
    public static class ScheduledRow {
        /** The booking identifier (cancel target). */
        public Long id;
        /** The target feed code, or a "every feed" label when it covers all. */
        public String feedCode;
        /** The booked minute, formatted. */
        public String scheduledAt;
        /** The administrator who booked it. */
        public String createdBy;
        /** The lifecycle state (PENDING, DONE, FAILED). */
        public String status;
        /** Whether the booking is still PENDING (so it may be cancelled). */
        public boolean pending;
        /** The failure reason when FAILED, otherwise null. */
        public String message;
    }

    /** One referential-pull row: a domain and its last applied fingerprint. */
    public static class PullRow {
        /** The referential domain (FAMILIES, PRODUCTS...). */
        public String domain;
        /** The last applied fingerprint, shortened. */
        public String fingerprint;
        /** When the snapshot was applied. */
        public String appliedAt;
        /**
         * The last integration failure of this domain, or null when it applied.
         *
         * <p>A pull domain that failed used to leave no trace here: a
         * {@code RefState} row is written only on success. The domains that are
         * also engine feeds carried their error on the feed row above; the ten
         * that are pull-only — CUSTOMERS, the clients en compte, among them —
         * carried none.
         */
        public String lastError;
    }

    /** The whole assembled view. */
    public static class SyncView {
        /** The engine-feed states, in catalog order. */
        public List<FeedRow> feeds = new ArrayList<>();
        /** The referential-pull states, ordered by domain. */
        public List<PullRow> pulls = new ArrayList<>();
        /** The store-outbox backlog rows, in drain order. */
        public List<SyncOutboxService.BacklogRow> backlog = new ArrayList<>();
        /** The deferred deliveries, newest booking first. */
        public List<ScheduledRow> schedules = new ArrayList<>();
        /** The catalog feed codes, for the "schedule a delivery" form. */
        public List<String> feedCodes = new ArrayList<>();
        /** When the last full pull cycle succeeded, or a dash when none. */
        public String lastPull;
        /** The referential-pull cadence, in seconds. */
        public long pullSeconds;
        /** The engine-feed delivery cadence, in seconds. */
        public long deliverySeconds;
        /** The store-push cadence, in seconds. */
        public long pushSeconds;
        /** Whether any engine feed carries a delivery error (red flag). */
        public boolean engineError;
        /** The total number of outbox rows awaiting push. */
        public long outboxCount;
        /** Whether the node is fully synchronized (no error, empty backlog). */
        public boolean healthy;
    }

    /**
     * Builds the whole supervision view over this node's local sync state.
     *
     * @return the assembled, pre-formatted view
     */
    public SyncView build() {
        LOGGER.info("Entering method build");
        SyncView view = new SyncView();
        boolean engineError = false;
        for (EngineFeedService.FeedState state : engineFeedService.feedStates()) {
            FeedRow row = new FeedRow();
            row.code = state.code();
            row.version = shortHash(state.version());
            row.appliedVersion = state.appliedVersion() == null ? "—" : shortHash(state.appliedVersion());
            row.applied = state.applied();
            row.lastError = state.lastError();
            row.receivedAt = stamp(state.receivedAt());
            row.appliedAt = stamp(state.appliedAt());
            if (state.lastError() != null) {
                engineError = true;
            }
            view.feeds.add(row);
        }
        for (RefState state : RefState.<RefState>list("order by domain")) {
            PullRow row = new PullRow();
            row.domain = state.domain;
            row.fingerprint = shortHash(state.fingerprint);
            row.appliedAt = stamp(state.appliedAt);
            row.lastError = refPullService.getLastError(state.domain);
            view.pulls.add(row);
        }
        for (Map.Entry<String, String> failure : refPullService.getLastErrors().entrySet()) {
            boolean alreadyListed = false;
            for (PullRow listed : view.pulls) {
                if (failure.getKey().equals(listed.domain)) {
                    alreadyListed = true;
                }
            }
            if (!alreadyListed) {
                PullRow row = new PullRow();
                row.domain = failure.getKey();
                row.fingerprint = "—";
                row.appliedAt = "—";
                row.lastError = failure.getValue();
                view.pulls.add(row);
            }
        }
        for (ScheduledDelivery scheduled : scheduledDeliveryService.list()) {
            ScheduledRow row = new ScheduledRow();
            row.id = scheduled.id;
            row.feedCode = scheduled.feedCode == null ? "Tous les flux" : scheduled.feedCode;
            row.scheduledAt = stamp(scheduled.scheduledAt);
            row.createdBy = scheduled.createdBy;
            row.status = scheduled.status.name();
            row.pending = scheduled.status == ScheduledDelivery.Status.PENDING;
            row.message = scheduled.message;
            view.schedules.add(row);
        }
        for (EngineFeedService.FeedDef def : EngineFeedService.CATALOG) {
            view.feedCodes.add(def.code());
        }
        view.backlog = syncOutboxService.backlog();
        long outboxCount = 0;
        for (SyncOutboxService.BacklogRow row : view.backlog) {
            outboxCount += row.count();
        }
        view.outboxCount = outboxCount;
        view.lastPull = stamp(refPullService.getLastSuccessfulPull());
        view.pullSeconds = refPullService.getPullSeconds();
        view.deliverySeconds = engineFeedDeliveryService.getDeliverySeconds();
        view.pushSeconds = pushIntervalSeconds;
        view.engineError = engineError;
        view.healthy = !engineError && outboxCount == 0;
        LOGGER.info("Exiting method build");
        return view;
    }

    /**
     * Shortens a version or fingerprint hash to what the screen needs, without
     * assuming a fixed length (a hand-set fingerprint may be shorter than
     * twelve characters). Every caller passes a non-null, non-nullable-column
     * value; the one nullable case — an unacknowledged {@code appliedVersion} —
     * is handled by its caller with a dash before it ever reaches here.
     *
     * @param hash the hash, never null
     * @return its first twelve characters at most
     */
    private String shortHash(String hash) {
        return hash.substring(0, Math.min(12, hash.length()));
    }

    /**
     * Formats a timestamp for the screen, tolerating null.
     *
     * @param dateTime the timestamp, or null
     * @return the formatted stamp, or a dash when there is none
     */
    private String stamp(LocalDateTime dateTime) {
        return dateTime == null ? "—" : dateTime.format(STAMP);
    }
}
