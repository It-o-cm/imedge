package com.intermarche.pos.service.campaign;

import com.intermarche.pos.domain.campaign.Campaign;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The business rules of campaign administration: validation, creation, edition
 * and duplication (GB-01-06-15).
 *
 * <p>It holds the two invariants the spec pins on the campaign: a coherent
 * period and the "an offer belongs to at most one campaign" rule. The rule is
 * enforced ACROSS campaigns here rather than on the entity because it is a
 * uniqueness constraint over the whole table, not a property of one row.
 *
 * <p>The service never reads or writes consumption — that is the engine's, read
 * live through {@link BudgetStatusClient}. It only administers the campaign's
 * definition, which is what the store node owns.
 */
@ApplicationScoped
public class CampaignService {

    /** Logger for this service. */
    private static final Logger LOGGER = Logger.getLogger(CampaignService.class);

    /**
     * Validates a campaign candidate and returns the list of error keys, empty
     * when the candidate is valid.
     *
     * <p>Rules: the code must be present; a bounded period must have its start
     * strictly before its (exclusive) end; a stated amount ceiling must be
     * strictly positive; a stated applications ceiling must be strictly
     * positive; and no offer code the candidate claims may already be claimed
     * by a DIFFERENT campaign.
     *
     * @param candidate the campaign to validate
     * @return the error keys, never null, empty when valid
     */
    public List<String> validate(Campaign candidate) {
        LOGGER.info("Entering method validate");
        List<String> errors = new ArrayList<>();
        if (candidate.code == null || candidate.code.isBlank()) {
            errors.add("code.required");
        }
        if (candidate.validFrom != null && candidate.validTo != null
                && !candidate.validFrom.isBefore(candidate.validTo)) {
            errors.add("period.invalid");
        }
        if (candidate.maxAmount != null && candidate.maxAmount.signum() <= 0) {
            errors.add("amount.invalid");
        }
        if (candidate.maxApplications != null && candidate.maxApplications <= 0) {
            errors.add("applications.invalid");
        }
        for (String offerCode : candidate.offerCodes) {
            if (isOfferClaimedElsewhere(offerCode, candidate.code)) {
                errors.add("offer.claimed:" + offerCode);
            }
        }
        LOGGER.info("Exiting method validate");
        return errors;
    }

    /**
     * Tells whether an offer code is already claimed by a campaign other than
     * the given one.
     *
     * @param offerCode the offer code to test
     * @param selfCode the code of the campaign that may legitimately claim it,
     *        or null when the candidate has no code yet
     * @return true when a DIFFERENT campaign already claims the offer
     */
    public boolean isOfferClaimedElsewhere(String offerCode, String selfCode) {
        LOGGER.info("Entering method isOfferClaimedElsewhere with offerCode: " + offerCode);
        for (Campaign other : Campaign.<Campaign>listAll()) {
            if (other.code != null && other.code.equals(selfCode)) {
                continue;
            }
            if (other.offerCodes.contains(offerCode)) {
                LOGGER.info("Exiting method isOfferClaimedElsewhere: claimed");
                return true;
            }
        }
        LOGGER.info("Exiting method isOfferClaimedElsewhere: free");
        return false;
    }

    /**
     * Validates then persists a new campaign.
     *
     * @param candidate the campaign to create
     * @return the persisted campaign
     * @throws IllegalArgumentException when validation fails, its message the
     *         error keys joined by a comma
     */
    @Transactional
    public Campaign create(Campaign candidate) {
        LOGGER.info("Entering method create");
        List<String> errors = validate(candidate);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(",", errors));
        }
        candidate.persist();
        LOGGER.info("Exiting method create");
        return candidate;
    }

    /**
     * Validates then persists edits to a managed campaign.
     *
     * @param campaign the campaign carrying its edited fields
     * @throws IllegalArgumentException when validation fails, its message the
     *         error keys joined by a comma
     */
    @Transactional
    public void update(Campaign campaign) {
        LOGGER.info("Entering method update");
        List<String> errors = validate(campaign);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(",", errors));
        }
        campaign.persist();
        LOGGER.info("Exiting method update");
    }

    /**
     * Duplicates a campaign (GB-01-06-15): copies every field to a new code,
     * optionally carrying the rattached offers across under suffixed codes.
     *
     * @param sourceCode the code of the campaign to copy
     * @param newCode the mandatory code of the copy
     * @param withOffers whether to copy the offer associations too
     * @param offerSuffix the suffix appended to each copied offer code, used
     *        only when {@code withOffers} is true
     * @return the persisted copy
     * @throws IllegalArgumentException when the source is unknown or validation
     *         of the copy fails
     */
    @Transactional
    public Campaign duplicate(String sourceCode, String newCode, boolean withOffers,
            String offerSuffix) {
        LOGGER.info("Entering method duplicate with sourceCode: " + sourceCode
                + ", newCode: " + newCode);
        Campaign source = Campaign.findByCode(sourceCode);
        if (source == null) {
            throw new IllegalArgumentException("source.unknown");
        }
        Campaign copy = new Campaign();
        copy.code = newCode;
        copy.label = source.label;
        copy.validFrom = source.validFrom;
        copy.validTo = source.validTo;
        copy.notes = source.notes;
        copy.maxAmount = source.maxAmount;
        copy.maxApplications = source.maxApplications;
        copy.perStore = source.perStore;
        if (withOffers) {
            for (String offerCode : source.offerCodes) {
                copy.offerCodes.add(offerCode + offerSuffix);
            }
        }
        return create(copy);
    }
}
