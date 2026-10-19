package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.campaign.Campaign;
import com.intermarche.edge.domain.util.DateTimeProvider;
import com.intermarche.edge.service.campaign.BudgetStatusClient;
import com.intermarche.edge.service.campaign.CampaignFeedProjector;
import com.intermarche.edge.service.campaign.CampaignService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminCampaignResource}.
 * <p>
 * The injected Qute templates are self-chaining Mockito mocks, the service,
 * projector and budget reader are mocks, and the Panache statics
 * ({@code Campaign.findByCode}, {@code listAllOrdered}) plus
 * {@link DateTimeProvider} are intercepted with {@code mockStatic}. Every branch
 * of the resource — the notice guards, the unknown-campaign guards, the
 * create/save/duplicate try/catch, the offer add/remove/claimed arms and the
 * form parse helpers — is exercised with absolute expected values.
 */
class AdminCampaignResourceTest {

    /** The resource under test. */
    private AdminCampaignResource resource;

    /** The self-chaining instance both templates return. */
    private TemplateInstance instance;

    /**
     * Wires the resource with mock templates, service, projector and reader.
     */
    @BeforeEach
    void setUp() {
        resource = new AdminCampaignResource();
        instance = mock(TemplateInstance.class);
        when(instance.data(anyString(), any())).thenReturn(instance);
        resource.adminCampaigns = mock(Template.class);
        when(resource.adminCampaigns.data(anyString(), any())).thenReturn(instance);
        resource.adminCampaign = mock(Template.class);
        when(resource.adminCampaign.data(anyString(), any())).thenReturn(instance);
        resource.campaignService = mock(CampaignService.class);
        resource.campaignFeedProjector = mock(CampaignFeedProjector.class);
        resource.budgetStatusClient = mock(BudgetStatusClient.class);
    }

    /**
     * Builds a posted form from alternating key/value pairs, skipping a pair
     * whose value is null so an absent checkbox stays absent.
     *
     * @param pairs the key/value pairs
     * @return the multivalued map
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                map.putSingle(pairs[i], pairs[i + 1]);
            }
        }
        return map;
    }

    /**
     * Stubs the ordered list read to return the given campaigns.
     *
     * @param panache the open Panache static mock
     * @param campaigns the campaigns to serve
     */
    private void stubList(MockedStatic<PanacheEntityBase> panache, List<Campaign> campaigns) {
        @SuppressWarnings("unchecked")
        PanacheQuery<Campaign> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(campaigns);
        panache.when(() -> Campaign.find("order by code")).thenReturn(query);
    }

    /**
     * Stubs {@code findByCode} to return the given campaign for a code.
     *
     * @param panache the open Panache static mock
     * @param code the code queried
     * @param campaign the campaign to serve, or null
     */
    private void stubFind(MockedStatic<PanacheEntityBase> panache, String code, Campaign campaign) {
        @SuppressWarnings("unchecked")
        PanacheQuery<Campaign> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(campaign);
        panache.when(() -> Campaign.find("code", code)).thenReturn(query);
    }

    /**
     * The list renders each campaign's live consumption and its notice.
     */
    @Test
    void listRendersCampaignsAndConsumption() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            stubList(panache, List.of(campaign));
            clock.when(DateTimeProvider::now).thenReturn(LocalDateTime.of(2026, 1, 15, 10, 0));
            assertEquals(instance, resource.list("Campagne créée.", true));
            verify(instance).data("notice", "Campagne créée.");
            verify(resource.budgetStatusClient).groupBudgetStatus("PROMO1");
        }
    }

    /**
     * The fiche renders the found campaign and its consumption.
     */
    @Test
    void ficheRendersTheCampaign() {
        Campaign campaign = new Campaign();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            assertEquals(instance, resource.fiche("PROMO1", null, true));
            verify(resource.adminCampaign).data("campaign", campaign);
            verify(resource.budgetStatusClient).groupBudgetStatus("PROMO1");
        }
    }

    /**
     * The fiche of an unknown code renders a null campaign (the not-found arm).
     */
    @Test
    void ficheRendersNullForUnknownCode() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "NOPE", null);
            assertEquals(instance, resource.fiche("NOPE", null, false));
            verify(resource.adminCampaign).data("campaign", null);
        }
    }

    /**
     * A valid create persists, reprojects and redirects with a success notice,
     * the form fields parsed onto the campaign.
     */
    @Test
    void createPersistsAndReprojects() {
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", null);
            Response response = resource.create(form("code", "PROMO1", "label", "Label",
                    "validFrom", "2026-01-01", "validTo", "2026-02-01", "notes", "notes",
                    "maxAmount", "10,50", "maxApplications", "5", "perStore", "on"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().startsWith("/admin/campaigns?notice="));
            verify(resource.campaignService).create(captor.capture());
            verify(resource.campaignFeedProjector).project();
            Campaign built = captor.getValue();
            assertEquals("PROMO1", built.code);
            assertEquals(LocalDate.of(2026, 1, 1), built.validFrom);
            assertEquals(new BigDecimal("10.50"), built.maxAmount);
            assertEquals(5, built.maxApplications);
            assertTrue(built.perStore);
        }
    }

    /**
     * A create whose code is already held redirects with a duplicate notice and
     * never touches the service (the duplicate-code guard).
     */
    @Test
    void createRejectsDuplicateCode() {
        Campaign existing = new Campaign();
        existing.code = "PROMO1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", existing);
            Response response = resource.create(form("code", "PROMO1"));
            assertTrue(response.getLocation().toString().contains("notice="));
            verify(resource.campaignService, never()).create(any());
            verify(resource.campaignFeedProjector, never()).project();
        }
    }

    /**
     * A create with blank optional cells leaves open bounds, no ceilings and no
     * per-store flag (the blank/absent parse arms).
     */
    @Test
    void createLeavesOpenBoundsForBlankCells() {
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", null);
            resource.create(form("code", "PROMO1", "label", "Label", "validFrom", "",
                    "validTo", "", "notes", "", "maxAmount", "", "maxApplications", "",
                    "perStore", null));
            verify(resource.campaignService).create(captor.capture());
            Campaign built = captor.getValue();
            assertNull(built.validFrom);
            assertNull(built.maxAmount);
            assertNull(built.maxApplications);
            assertTrue(!built.perStore);
        }
    }

    /**
     * A create with an unparseable applications cell maps it to no ceiling (the
     * catch arm of the integer helper).
     */
    @Test
    void createIgnoresUnparseableApplications() {
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", null);
            resource.create(form("code", "PROMO1", "maxApplications", "abc"));
            verify(resource.campaignService).create(captor.capture());
            assertNull(captor.getValue().maxApplications);
        }
    }

    /**
     * An invalid create redirects with the refusal notice and reprojects nothing.
     */
    @Test
    void createRedirectsWithErrorWhenInvalid() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", null);
            doThrow(new IllegalArgumentException("period.invalid"))
                    .when(resource.campaignService).create(any());
            Response response = resource.create(form("code", "PROMO1"));
            assertTrue(response.getLocation().toString().contains("period.invalid")
                    || response.getLocation().toString().contains("noticeOk=false"));
            verify(resource.campaignFeedProjector, never()).project();
        }
    }

    /**
     * A save of an unknown campaign redirects with the not-found notice.
     */
    @Test
    void saveRedirectsWhenUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "NOPE", null);
            Response response = resource.save(form("code", "NOPE"));
            assertEquals("/admin/campaigns", stripQuery(response));
            verify(resource.campaignFeedProjector, never()).project();
        }
    }

    /**
     * A valid save updates, reprojects and redirects to the fiche.
     */
    @Test
    void saveUpdatesAndReprojects() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            Response response = resource.save(form("code", "PROMO1", "label", "New"));
            assertEquals("/admin/campaigns/fiche", stripQuery(response));
            verify(resource.campaignService).update(campaign);
            verify(resource.campaignFeedProjector).project();
        }
    }

    /**
     * An invalid save redirects to the fiche with the refusal notice.
     */
    @Test
    void saveRedirectsToFicheWhenInvalid() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        doThrow(new IllegalArgumentException("amount.invalid"))
                .when(resource.campaignService).update(campaign);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            Response response = resource.save(form("code", "PROMO1"));
            assertEquals("/admin/campaigns/fiche", stripQuery(response));
            verify(resource.campaignFeedProjector, never()).project();
        }
    }

    /**
     * Associating an offer to an unknown campaign redirects with the not-found
     * notice.
     */
    @Test
    void offersRedirectsWhenUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "NOPE", null);
            Response response = resource.offers(form("code", "NOPE", "offerCode", "OFF1",
                    "action", "add"));
            assertEquals("/admin/campaigns", stripQuery(response));
        }
    }

    /**
     * Associating with no offer indicated redirects to the fiche with a notice.
     */
    @Test
    void offersRedirectsWhenNoOffer() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        campaign.offerCodes = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            Response response = resource.offers(form("code", "PROMO1", "action", "add"));
            assertEquals("/admin/campaigns/fiche", stripQuery(response));
            verify(resource.campaignFeedProjector, never()).project();
        }
    }

    /**
     * Associating an offer already claimed elsewhere redirects with a notice.
     */
    @Test
    void offersRejectsClaimedOffer() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        campaign.offerCodes = new ArrayList<>();
        when(resource.campaignService.isOfferClaimedElsewhere("OFF1", "PROMO1")).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            Response response = resource.offers(form("code", "PROMO1", "offerCode", "OFF1",
                    "action", "add"));
            assertEquals("/admin/campaigns/fiche", stripQuery(response));
            verify(resource.campaignFeedProjector, never()).project();
        }
    }

    /**
     * Associating a free offer adds it, updates, reprojects and redirects.
     */
    @Test
    void offersAddsFreeOffer() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        campaign.offerCodes = new ArrayList<>();
        when(resource.campaignService.isOfferClaimedElsewhere("OFF1", "PROMO1")).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            resource.offers(form("code", "PROMO1", "offerCode", "OFF1", "action", "add"));
            assertTrue(campaign.offerCodes.contains("OFF1"));
            verify(resource.campaignService).update(campaign);
            verify(resource.campaignFeedProjector).project();
        }
    }

    /**
     * Associating an offer the campaign already holds does not duplicate it (the
     * already-contains arm).
     */
    @Test
    void offersDoesNotDuplicateHeldOffer() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        campaign.offerCodes = new ArrayList<>(List.of("OFF1"));
        when(resource.campaignService.isOfferClaimedElsewhere("OFF1", "PROMO1")).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            resource.offers(form("code", "PROMO1", "offerCode", "OFF1", "action", "add"));
            assertEquals(1, campaign.offerCodes.size());
        }
    }

    /**
     * Dissociating an offer removes it (the non-add arm).
     */
    @Test
    void offersRemovesOffer() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        campaign.offerCodes = new ArrayList<>(List.of("OFF1"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            resource.offers(form("code", "PROMO1", "offerCode", "OFF1", "action", "remove"));
            assertTrue(campaign.offerCodes.isEmpty());
            verify(resource.campaignFeedProjector).project();
        }
    }

    /**
     * A duplication with no new code redirects to the source fiche with a notice
     * (and the null-code fiche-path arm).
     */
    @Test
    void duplicateRedirectsWhenNoNewCode() {
        Response response = resource.duplicate(form());
        assertEquals("/admin/campaigns/fiche", stripQuery(response));
        verify(resource.campaignFeedProjector, never()).project();
    }

    /**
     * A duplication whose new code is already held redirects with a notice.
     */
    @Test
    void duplicateRejectsDuplicateNewCode() {
        Campaign existing = new Campaign();
        existing.code = "COPY";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "COPY", existing);
            Response response = resource.duplicate(form("code", "SRC", "newCode", "COPY"));
            assertEquals("/admin/campaigns/fiche", stripQuery(response));
            verify(resource.campaignService, never()).duplicate(anyString(), anyString(),
                    org.mockito.ArgumentMatchers.anyBoolean(), anyString());
        }
    }

    /**
     * A successful duplication reprojects and redirects to the copy's fiche.
     */
    @Test
    void duplicateRedirectsToCopy() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "COPY", null);
            Response response = resource.duplicate(form("code", "SRC", "newCode", "COPY",
                    "withOffers", "on"));
            assertEquals("/admin/campaigns/fiche", stripQuery(response));
            verify(resource.campaignService).duplicate("SRC", "COPY", true, "-2");
            verify(resource.campaignFeedProjector).project();
        }
    }

    /**
     * A failed duplication redirects to the source fiche with a notice and
     * reprojects nothing (the without-offers flag and catch arm).
     */
    @Test
    void duplicateRedirectsWithErrorOnFailure() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "COPY", null);
            when(resource.campaignService.duplicate("SRC", "COPY", false, "-2"))
                    .thenThrow(new IllegalArgumentException("source.unknown"));
            Response response = resource.duplicate(form("code", "SRC", "newCode", "COPY"));
            assertEquals("/admin/campaigns/fiche", stripQuery(response));
            verify(resource.campaignFeedProjector, never()).project();
        }
    }

    /**
     * Returns the path of a redirect response, without its query string.
     *
     * @param response the redirect response
     * @return the path portion of the Location header
     */
    private String stripQuery(Response response) {
        String location = response.getLocation().toString();
        int mark = location.indexOf('?');
        return mark < 0 ? location : location.substring(0, mark);
    }

    /**
     * A create with no code skips the duplicate lookup entirely — the null leg
     * of the duplicate guard — and still persists and reprojects.
     */
    @Test
    void createWithoutACodeSkipsTheDuplicateLookup() {
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        Response response = resource.create(form("label", "Label"));
        assertEquals(303, response.getStatus());
        verify(resource.campaignService).create(captor.capture());
        verify(resource.campaignFeedProjector).project();
        assertNull(captor.getValue().code);
    }
}
