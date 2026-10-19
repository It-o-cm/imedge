package com.intermarche.pos.service;

import com.intermarche.pos.domain.payment.StoredValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;

/**
 * The register's RETENTION SWEEP over the instruments it issued — gift cards,
 * credit notes, vouchers carrying a number (BO-10-08-13, -14 and -15).
 *
 * <p>The store node has its own sweep over its own tables; these rows are not
 * among them, because an instrument lives on the register that issued it. The
 * delays are administered in the SAME catalogue and descend by the same
 * diffusion, so a paramétreur sets them once for the shop.
 *
 * <p>THE THREE CASES ARE NOT THE SAME CASE, which is why the requirement names
 * three delays: an instrument whose validity passed without anybody using it is
 * a loss the shop may want to keep sight of; one that was used is settled; one
 * that never carried a date is kept on its age alone. A delay of zero is the
 * absence of a delay and not a delay of nothing — nothing is deleted.
 *
 * <p>Run at the CLOSE OF A SESSION and nowhere else: the register has no
 * scheduler, and the close is the one moment it is certainly not selling.
 */
@ApplicationScoped
public class StoredValueRetentionService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(StoredValueRetentionService.class);

    /** The administered delays. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Deletes every instrument the administered delays have made stale.
     *
     * @return the number of rows deleted, zero when nothing is administered
     */
    @Transactional
    public long sweep() {
        LOGGER.info("Entering method sweep");
        long deleted = 0;
        deleted += deleteExpiredUnused();
        deleted += deleteExpiredUsed();
        deleted += deleteUndated();
        if (deleted > 0) {
            LOGGER.infof("Purge des codes-barres : %d instrument(s) supprimé(s)", deleted);
        }
        LOGGER.info("Exiting method sweep");
        return deleted;
    }

    /**
     * Deletes the instruments whose validity passed and that were never
     * redeemed (BO-10-08-13).
     *
     * @return the number of rows deleted
     */
    private long deleteExpiredUnused() {
        LocalDateTime limit = limitOf(posSettingsService.barcodeExpiredUnusedDays());
        if (limit == null) {
            return 0;
        }
        return StoredValue.delete(
                "validUntil is not null and validUntil < ?1 and exhaustedAt is null", limit);
    }

    /**
     * Deletes the dated instruments that have been exhausted (BO-10-08-14).
     *
     * @return the number of rows deleted
     */
    private long deleteExpiredUsed() {
        LocalDateTime limit = limitOf(posSettingsService.barcodeExpiredUsedDays());
        if (limit == null) {
            return 0;
        }
        return StoredValue.delete(
                "validUntil is not null and exhaustedAt is not null and exhaustedAt < ?1", limit);
    }

    /**
     * Deletes the instruments that never carried a validity date, on their age
     * alone (BO-10-08-15).
     *
     * @return the number of rows deleted
     */
    private long deleteUndated() {
        LocalDateTime limit = limitOf(posSettingsService.barcodeUndatedDays());
        if (limit == null) {
            return 0;
        }
        return StoredValue.delete("validUntil is null and issuedAt < ?1", limit);
    }

    /**
     * The instant before which a row of this family is stale.
     *
     * @param days the administered delay in days
     * @return the limit, or null when no delay is administered
     */
    private LocalDateTime limitOf(int days) {
        return days <= 0 ? null : LocalDateTime.now().minusDays(days);
    }
}
