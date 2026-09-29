package com.intermarche.pos.service.campaign;

import com.intermarche.pos.domain.campaign.Campaign;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CampaignService}.
 * <p>
 * The Panache statics ({@code Campaign.listAll}, {@code Campaign.find}) are
 * intercepted with {@code mockStatic(PanacheEntityBase.class)} and the {@code
 * persist()} of created rows is neutralised with {@code
 * mockConstruction(Campaign.class)} (whose field initialisers do not run, so the
 * offer-code list is set on the constructed mock). Every branch of {@code
 * validate}, {@code isOfferClaimedElsewhere}, {@code create}, {@code update}
 * and {@code duplicate} is exercised with absolute expected values.
 */
class CampaignServiceTest {

    /** The service under test. */
    private final CampaignService service = new CampaignService();

    /**
     * Builds a plain, valid campaign with a bounded period and both ceilings.
     *
     * @param code the campaign code
     * @return the campaign
     */
    private Campaign valid(String code) {
        Campaign campaign = new Campaign();
        campaign.code = code;
        campaign.label = "Label";
        campaign.validFrom = LocalDate.of(2026, 1, 1);
        campaign.validTo = LocalDate.of(2026, 2, 1);
        campaign.maxAmount = new BigDecimal("100.00");
        campaign.maxApplications = 50;
        campaign.perStore = false;
        return campaign;
    }

    /**
     * A well-formed campaign with no offer collisions passes validation.
     */
    @Test
    void validateAcceptsAValidCampaign() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            assertTrue(service.validate(valid("PROMO1")).isEmpty());
        }
    }

    /**
     * A null code is rejected (code-required, blank guard first leg).
     */
    @Test
    void validateRejectsNullCode() {
        Campaign campaign = valid("PROMO1");
        campaign.code = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            assertTrue(service.validate(campaign).contains("code.required"));
        }
    }

    /**
     * A blank code is rejected (code-required, blank guard second leg).
     */
    @Test
    void validateRejectsBlankCode() {
        Campaign campaign = valid("PROMO1");
        campaign.code = "   ";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            assertTrue(service.validate(campaign).contains("code.required"));
        }
    }

    /**
     * A start that is not before the exclusive end is an incoherent period.
     */
    @Test
    void validateRejectsIncoherentPeriod() {
        Campaign campaign = valid("PROMO1");
        campaign.validFrom = LocalDate.of(2026, 2, 1);
        campaign.validTo = LocalDate.of(2026, 1, 1);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            assertTrue(service.validate(campaign).contains("period.invalid"));
        }
    }

    /**
     * A campaign open on the left (null start) skips the period check and is
     * accepted (the validFrom-null arm).
     */
    @Test
    void validateAcceptsOpenStart() {
        Campaign campaign = valid("PROMO1");
        campaign.validFrom = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            assertTrue(service.validate(campaign).isEmpty());
        }
    }

    /**
     * A campaign open on the right (null end) skips the period check and is
     * accepted (the validTo-null arm).
     */
    @Test
    void validateAcceptsOpenEnd() {
        Campaign campaign = valid("PROMO1");
        campaign.validTo = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            assertTrue(service.validate(campaign).isEmpty());
        }
    }

    /**
     * A non-positive amount ceiling is rejected.
     */
    @Test
    void validateRejectsNonPositiveAmount() {
        Campaign campaign = valid("PROMO1");
        campaign.maxAmount = BigDecimal.ZERO;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            assertTrue(service.validate(campaign).contains("amount.invalid"));
        }
    }

    /**
     * A non-positive applications ceiling is rejected.
     */
    @Test
    void validateRejectsNonPositiveApplications() {
        Campaign campaign = valid("PROMO1");
        campaign.maxApplications = 0;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            assertTrue(service.validate(campaign).contains("applications.invalid"));
        }
    }

    /**
     * An offer already claimed by another campaign is rejected, naming the
     * offer.
     */
    @Test
    void validateRejectsOfferClaimedElsewhere() {
        Campaign campaign = valid("PROMO1");
        campaign.offerCodes = new ArrayList<>(List.of("OFF1"));
        Campaign other = valid("OTHER");
        other.offerCodes = new ArrayList<>(List.of("OFF1"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of(other));
            assertTrue(service.validate(campaign).contains("offer.claimed:OFF1"));
        }
    }

    /**
     * The offer-claim check skips the campaign's own row (same-code continue
     * arm) and reports free.
     */
    @Test
    void isOfferClaimedElsewhereSkipsSelf() {
        Campaign self = valid("SELF");
        self.offerCodes = new ArrayList<>(List.of("OFF1"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of(self));
            assertFalse(service.isOfferClaimedElsewhere("OFF1", "SELF"));
        }
    }

    /**
     * The offer-claim check reads a campaign whose code is null (the
     * null-code-not-continued arm) and still finds the claim.
     */
    @Test
    void isOfferClaimedElsewhereHandlesNullOtherCode() {
        Campaign other = valid("PROMO1");
        other.code = null;
        other.offerCodes = new ArrayList<>(List.of("OFF1"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of(other));
            assertTrue(service.isOfferClaimedElsewhere("OFF1", "SELF"));
        }
    }

    /**
     * The offer-claim check reports free when the other campaign claims a
     * different offer (the contains-false arm).
     */
    @Test
    void isOfferClaimedElsewhereFreeWhenOtherClaimsAnotherOffer() {
        Campaign other = valid("OTHER");
        other.offerCodes = new ArrayList<>(List.of("OFF9"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Campaign::listAll).thenReturn(List.of(other));
            assertFalse(service.isOfferClaimedElsewhere("OFF1", "SELF"));
        }
    }

    /**
     * {@code create} persists a valid campaign and returns it.
     */
    @Test
    void createPersistsAValidCampaign() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Campaign> mc = mockConstruction(Campaign.class,
                     (m, ctx) -> m.offerCodes = new ArrayList<>())) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            Campaign candidate = new Campaign();
            candidate.code = "PROMO1";
            Campaign result = service.create(candidate);
            verify(candidate).persist();
            assertSame(candidate, result);
        }
    }

    /**
     * {@code create} throws on an invalid candidate and persists nothing.
     */
    @Test
    void createThrowsOnInvalidCandidate() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Campaign> mc = mockConstruction(Campaign.class,
                     (m, ctx) -> m.offerCodes = new ArrayList<>())) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            Campaign candidate = new Campaign();
            candidate.code = null;
            assertThrows(IllegalArgumentException.class, () -> service.create(candidate));
            verify(candidate, never()).persist();
        }
    }

    /**
     * {@code update} persists a valid campaign.
     */
    @Test
    void updatePersistsAValidCampaign() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Campaign> mc = mockConstruction(Campaign.class,
                     (m, ctx) -> m.offerCodes = new ArrayList<>())) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            Campaign campaign = new Campaign();
            campaign.code = "PROMO1";
            service.update(campaign);
            verify(campaign).persist();
        }
    }

    /**
     * {@code update} throws on an invalid campaign and persists nothing.
     */
    @Test
    void updateThrowsOnInvalidCampaign() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Campaign> mc = mockConstruction(Campaign.class,
                     (m, ctx) -> m.offerCodes = new ArrayList<>())) {
            panache.when(Campaign::listAll).thenReturn(List.of());
            Campaign campaign = new Campaign();
            campaign.code = null;
            assertThrows(IllegalArgumentException.class, () -> service.update(campaign));
            verify(campaign, never()).persist();
        }
    }

    /**
     * {@code duplicate} throws when the source campaign is unknown.
     */
    @Test
    void duplicateThrowsOnUnknownSource() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Campaign> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Campaign.find("code", "SRC")).thenReturn(query);
            assertThrows(IllegalArgumentException.class,
                    () -> service.duplicate("SRC", "COPY", false, "-2"));
        }
    }

    /**
     * {@code duplicate} without offers copies the fields under a new code and
     * carries no offer association.
     */
    @Test
    void duplicateCopiesWithoutOffers() {
        Campaign source = valid("SRC");
        source.offerCodes = new ArrayList<>(List.of("OFF1"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Campaign> mc = mockConstruction(Campaign.class,
                     (m, ctx) -> m.offerCodes = new ArrayList<>())) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Campaign> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(source);
            panache.when(() -> Campaign.find("code", "SRC")).thenReturn(query);
            panache.when(Campaign::listAll).thenReturn(List.of());
            Campaign copy = service.duplicate("SRC", "COPY", false, "-2");
            assertEquals("COPY", copy.code);
            assertEquals(source.maxAmount, copy.maxAmount);
            assertTrue(copy.offerCodes.isEmpty());
            verify(copy).persist();
        }
    }

    /**
     * {@code duplicate} with offers carries the associations across under
     * suffixed codes.
     */
    @Test
    void duplicateCopiesWithSuffixedOffers() {
        Campaign source = valid("SRC");
        source.offerCodes = new ArrayList<>(List.of("OFF1"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Campaign> mc = mockConstruction(Campaign.class,
                     (m, ctx) -> m.offerCodes = new ArrayList<>())) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Campaign> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(source);
            panache.when(() -> Campaign.find("code", "SRC")).thenReturn(query);
            panache.when(Campaign::listAll).thenReturn(List.of());
            Campaign copy = service.duplicate("SRC", "COPY", true, "-2");
            assertEquals(List.of("OFF1-2"), copy.offerCodes);
            verify(copy).persist();
        }
    }
}
