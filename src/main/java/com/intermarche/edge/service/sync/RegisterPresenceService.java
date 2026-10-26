package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.sync.RegisterPresence;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Knows which registers are still speaking to this node
 * ({@code BO-04-03-05}, {@code BO-04-03-06}).
 *
 * <p>A register is ONLINE when it asked for its orders recently enough. The
 * threshold is administered rather than derived from the poll interval,
 * because the two belong to different people: the interval is a deployment
 * choice made once, the tolerance is a shop's judgement about when a silent
 * lane becomes a problem.
 *
 * <p>Silence is NOT an absence of row. A register that has never spoken to
 * this node has no presence row at all, and the node does not invent one:
 * what it reports is the lanes it knows about, which are the lanes that have
 * been plugged in at least once. The administered checkout points are the
 * other list, and the two are deliberately not merged — one says what the
 * shop declared, the other what the shop actually has running.
 */
@ApplicationScoped
public class RegisterPresenceService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RegisterPresenceService.class);

    /** The tolerance used when the administered one is absent or absurd. */
    static final int DEFAULT_OFFLINE_MINUTES = 3;

    /** The administered tolerance. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * One register and how long it has been silent.
     *
     * @param terminalId the register's identifier
     * @param lastSeenAt when it last spoke
     * @param silentMinutes how many minutes ago that was
     * @param online whether that is still within the administered tolerance
     */
    public record Presence(String terminalId, LocalDateTime lastSeenAt, long silentMinutes,
                           boolean online) {
    }

    /**
     * Records that a register just spoke to this node.
     *
     * <p>Called from the order poll, which every register makes on its own
     * cycle. Swallows nothing and decides nothing: it writes a timestamp.
     *
     * @param terminalId the register that spoke, possibly null or blank
     */
    @Transactional
    public void touch(String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return;
        }
        String typed = terminalId.trim();
        RegisterPresence presence = RegisterPresence.findByTerminal(typed);
        if (presence == null) {
            presence = new RegisterPresence();
            presence.terminalId = typed;
        }
        presence.lastSeenAt = LocalDateTime.now();
        presence.persist();
    }

    /**
     * Every register this node knows, with how long each has been silent, the
     * longest silent first.
     *
     * @return the presences, possibly empty
     */
    @Transactional
    public List<Presence> all() {
        LOGGER.info("Entering method all");
        LocalDateTime now = LocalDateTime.now();
        long tolerance = offlineMinutes();
        List<Presence> presences = new ArrayList<>();
        for (RegisterPresence row : RegisterPresence.<RegisterPresence>allByAge()) {
            long silent = row.lastSeenAt == null ? Long.MAX_VALUE
                    : java.time.Duration.between(row.lastSeenAt, now).toMinutes();
            presences.add(new Presence(row.terminalId, row.lastSeenAt, silent,
                    silent <= tolerance));
        }
        LOGGER.info("Exiting method all with " + presences.size() + " register(s)");
        return presences;
    }

    /**
     * The registers that have gone quiet ({@code BO-04-03-05}).
     *
     * @return the silent registers, the longest silent first
     */
    public List<Presence> offline() {
        LOGGER.info("Entering method offline");
        List<Presence> silent = new ArrayList<>();
        for (Presence presence : all()) {
            // BO-03-09-01: a register deactivated by the back office is not
            // expected to speak, and its silence is no alarm.
            if (!presence.online()
                    && !com.intermarche.edge.domain.store.CheckoutPoint.isDeactivated(presence.terminalId())) {
                silent.add(presence);
            }
        }
        LOGGER.info("Exiting method offline with " + silent.size() + " register(s)");
        return silent;
    }

    /**
     * How long a register may stay silent before the node calls it offline.
     *
     * <p>An absent or absurd tolerance answers three minutes rather than
     * zero: a tolerance of zero would declare every lane offline between two
     * polls, which is an alarm nobody would ever trust again.
     *
     * @return the tolerance in minutes, always strictly positive
     */
    public int offlineMinutes() {
        int minutes = posSettingsService.registerOfflineMinutes();
        return minutes > 0 ? minutes : DEFAULT_OFFLINE_MINUTES;
    }
}
