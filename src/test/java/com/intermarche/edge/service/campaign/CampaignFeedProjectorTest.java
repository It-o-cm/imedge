package com.intermarche.edge.service.campaign;

import com.intermarche.edge.domain.campaign.Campaign;
import com.intermarche.edge.service.sync.EngineFeedService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CampaignFeedProjector}.
 * <p>
 * The Panache ordered read is intercepted with {@code
 * mockStatic(PanacheEntityBase.class)} and the {@link EngineFeedService} is a
 * Mockito mock assigned to the injection field. The rendered content is asserted
 * verbatim, covering the empty-cell arms of every open bound and unset ceiling
 * and both {@code perStore} arms.
 */
class CampaignFeedProjectorTest {

    /** The projector under test. */
    private final CampaignFeedProjector projector = new CampaignFeedProjector();

    /**
     * Builds a fully-populated campaign row.
     *
     * @return the campaign
     */
    private Campaign populated() {
        Campaign campaign = new Campaign();
        campaign.code = "A";
        campaign.validFrom = LocalDate.of(2026, 1, 1);
        campaign.validTo = LocalDate.of(2026, 2, 1);
        campaign.maxAmount = new BigDecimal("100.00");
        campaign.maxApplications = 50;
        campaign.perStore = true;
        return campaign;
    }

    /**
     * Builds a bare campaign row: open bounds, no ceilings, not per store.
     *
     * @return the campaign
     */
    private Campaign bare() {
        Campaign campaign = new Campaign();
        campaign.code = "B";
        campaign.perStore = false;
        return campaign;
    }

    /**
     * Stubs the ordered campaign read to return the given campaigns.
     *
     * @param panache the open Panache static mock
     * @param campaigns the campaigns to serve, in order
     */
    private void stubOrdered(MockedStatic<PanacheEntityBase> panache, List<Campaign> campaigns) {
        @SuppressWarnings("unchecked")
        PanacheQuery<Campaign> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(campaigns);
        panache.when(() -> Campaign.find("order by code")).thenReturn(query);
    }

    /**
     * {@code render} writes the header then one pipe-delimited row per campaign,
     * with empty cells for open bounds and unset ceilings and the amount at
     * scale 2.
     */
    @Test
    void renderWritesHeaderAndRows() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOrdered(panache, List.of(populated(), bare()));
            String content = projector.render();
            assertEquals(CampaignFeedProjector.HEADER
                    + "\nA|2026-01-01|2026-02-01|100.00|50|true"
                    + "\nB|||||false", content);
        }
    }

    /**
     * {@code render} writes only the header when there is no campaign.
     */
    @Test
    void renderWritesHeaderOnlyWhenNoCampaign() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOrdered(panache, List.of());
            assertEquals(CampaignFeedProjector.HEADER, projector.render());
        }
    }

    /**
     * {@code project} stores the rendered content under the BUDGET_GROUPS code
     * and returns the stored version.
     */
    @Test
    void projectStoresTheRenderedContent() {
        projector.engineFeedService = mock(EngineFeedService.class);
        when(projector.engineFeedService.store(eq("BUDGET_GROUPS"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("v1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOrdered(panache, List.of(populated()));
            String version = projector.project();
            assertEquals("v1", version);
            verify(projector.engineFeedService).store("BUDGET_GROUPS",
                    CampaignFeedProjector.HEADER + "\nA|2026-01-01|2026-02-01|100.00|50|true");
        }
    }
}
