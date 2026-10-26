package com.intermarche.edge.service.campaign;

import com.intermarche.edge.domain.campaign.Campaign;
import com.intermarche.edge.domain.util.SeedFeedProjector;
import com.intermarche.edge.service.sync.EngineFeedService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.RoundingMode;

/**
 * Projects the administered campaigns into the {@code BUDGET_GROUPS} engine
 * feed: the budget view the engine is allowed to know (code, period, ceilings,
 * {@code perStore}) — never the label, the notes or the associations.
 *
 * <p>It writes the feed through {@link EngineFeedService#store}, so the parcel
 * then rides the ordinary chain (pulled by the register, delivered to the engine
 * BEFORE the OFFERS feed, supervision) with no code specific to this feed
 * anywhere downstream. The store node is the source of the ENGINE_FEEDS:
 * projecting on every campaign save keeps the engine's budget view in step with
 * the back office. It is also the {@link SeedFeedProjector} the dev seed
 * refreshes once it has written its demonstration campaign.
 */
@ApplicationScoped
public class CampaignFeedProjector implements SeedFeedProjector {

    /** Logger for this projector. */
    private static final Logger LOGGER = Logger.getLogger(CampaignFeedProjector.class);

    /** The feed code this projector writes. */
    static final String FEED_CODE = "BUDGET_GROUPS";

    /** The pipe-delimited header line the engine reads. */
    static final String HEADER = "CODE|VALID_FROM|VALID_TO|MAX_AMOUNT|MAX_APPLICATIONS|PER_STORE";

    /** The engine-feed store this projector delivers into. */
    @Inject
    EngineFeedService engineFeedService;

    /**
     * Renders every campaign to the {@code BUDGET_GROUPS} feed content and
     * stores it, replacing the previous version.
     *
     * @return the stored version (SHA-256 hex)
     */
    @Override
    @Transactional
    public String project() {
        LOGGER.info("Entering method project");
        String content = render();
        String version = engineFeedService.store(FEED_CODE, content);
        LOGGER.info("Exiting method project");
        return version;
    }

    /**
     * Builds the pipe-delimited feed content: the header followed by one row
     * per campaign, in code order.
     *
     * @return the feed content, verbatim
     */
    String render() {
        LOGGER.info("Entering method render");
        StringBuilder builder = new StringBuilder(HEADER);
        for (Campaign campaign : Campaign.listAllOrdered()) {
            builder.append('\n').append(row(campaign));
        }
        LOGGER.info("Exiting method render");
        return builder.toString();
    }

    /**
     * Renders one campaign as a pipe-delimited row, empty cells for open bounds
     * and unset ceilings.
     *
     * @param campaign the campaign to render
     * @return the row line, without a trailing newline
     */
    private String row(Campaign campaign) {
        String validFrom = campaign.validFrom == null ? "" : campaign.validFrom.toString();
        String validTo = campaign.validTo == null ? "" : campaign.validTo.toString();
        String maxAmount = campaign.maxAmount == null ? ""
                : campaign.maxAmount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        String maxApplications = campaign.maxApplications == null ? ""
                : String.valueOf(campaign.maxApplications);
        return campaign.code + "|" + validFrom + "|" + validTo + "|" + maxAmount + "|"
                + maxApplications + "|" + (campaign.perStore ? "true" : "false");
    }
}
