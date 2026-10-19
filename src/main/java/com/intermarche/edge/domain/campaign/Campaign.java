package com.intermarche.edge.domain.campaign;

import com.intermarche.edge.domain.BaseEntity;
import com.intermarche.edge.domain.util.DateTimeProvider;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A campaign of generosity, administered in the store node's back office and
 * projected to the valuation engine as its budget group (BUDGET_GROUPS feed).
 *
 * <p>The store node is the BO maître here: the campaign's label, notes,
 * association and duplication are business truth that never descends to the
 * engine — the engine knows only the campaign's BUDGET PROJECTION (code, period,
 * ceilings, {@code perStore}). The consumption of a campaign is NOT held here:
 * it is read live from the engine (GraphQL {@code budgetStatus}/
 * {@code groupBudgetStatus}), this row keeps no counter of its own.
 *
 * <p>The period is a half-open interval: {@code validFrom} is inclusive,
 * {@code validTo} exclusive, and a null bound is open (see {@link CampaignStatus}).
 * A budget is expressed by an amount ceiling in euros and/or an applications
 * ceiling; at least one of the two must be set when a budget is entered — that
 * rule lives in the validation service, not the entity.
 *
 * <p>{@code offerCodes} is the set of offer codes this campaign claims. An offer
 * belongs to at most one campaign; that invariant is enforced by the validation
 * service across campaigns, since the offers themselves are authored on the
 * engine side and reach the node only as an opaque OFFERS feed.
 */
@Entity
@Table(name = "campaigns")
public class Campaign extends BaseEntity {

    /** The campaign's unique business code, its identity toward the engine. */
    @Column(name = "code", nullable = false, unique = true)
    public String code;

    /** The human label shown in the back office; never sent to the engine. */
    @Column(name = "label")
    public String label;

    /** The inclusive start of the period, or null when open on the left. */
    @Column(name = "valid_from")
    public LocalDate validFrom;

    /** The exclusive end of the period, or null when open on the right. */
    @Column(name = "valid_to")
    public LocalDate validTo;

    /** Free operator notes; back-office only, never sent to the engine. */
    @Column(name = "notes", length = 2000)
    public String notes;

    /** The amount ceiling in euros (scale 2), or null when uncapped by amount. */
    @Column(name = "max_amount")
    public BigDecimal maxAmount;

    /** The applications ceiling (a count), or null when uncapped by count. */
    @Column(name = "max_applications")
    public Integer maxApplications;

    /** Whether the ceilings apply per point of sale rather than globally. */
    @Column(name = "per_store", nullable = false)
    public boolean perStore;

    /** The offer codes this campaign claims; each offer belongs to at most one campaign. */
    @ElementCollection
    @CollectionTable(name = "campaign_offer_codes",
            joinColumns = @JoinColumn(name = "campaign_id"))
    @Column(name = "offer_code")
    public List<String> offerCodes = new ArrayList<>();

    /**
     * Finds a campaign by its unique code.
     *
     * @param code the campaign code, possibly null
     * @return the campaign, or null when no row carries that code
     */
    public static Campaign findByCode(String code) {
        if (code == null) {
            return null;
        }
        return find("code", code).firstResult();
    }

    /**
     * Lists every campaign, ordered by code, for the back-office list screen.
     *
     * @return the campaigns, never null
     */
    public static List<Campaign> listAllOrdered() {
        return Campaign.<Campaign>find("order by code").list();
    }

    /**
     * The status of this campaign on the current day.
     *
     * @return upcoming, running or finished, read from the period against today
     */
    public CampaignStatus status() {
        return CampaignStatus.of(validFrom, validTo, DateTimeProvider.now().toLocalDate());
    }

    /**
     * Tells whether this campaign carries a budget (at least one ceiling).
     *
     * @return true when an amount or an applications ceiling is set
     */
    public boolean hasBudget() {
        return maxAmount != null || maxApplications != null;
    }

    /**
     * Computes the change-detection checksum from the salient business fields.
     *
     * @return the hash of the campaign's administered fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, validFrom, validTo, notes,
                maxAmount, maxApplications, perStore, offerCodes);
    }
}
