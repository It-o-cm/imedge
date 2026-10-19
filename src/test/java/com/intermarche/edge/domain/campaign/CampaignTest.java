package com.intermarche.edge.domain.campaign;

import com.intermarche.edge.domain.util.DateTimeProvider;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Campaign}.
 * <p>
 * The Panache static finders are intercepted with {@code
 * mockStatic(PanacheEntityBase.class)} since the entity is not enhanced under
 * plain {@code mvn test}; {@link DateTimeProvider} is frozen for the day-reading
 * status. Every branch of the finders, {@code hasBudget} and {@code status} is
 * exercised with absolute expected values.
 */
class CampaignTest {

    /**
     * Builds a campaign with the given code and both ceilings, for the checksum
     * and finder cases.
     *
     * @param code the campaign code
     * @return the campaign
     */
    private Campaign campaign(String code) {
        Campaign campaign = new Campaign();
        campaign.code = code;
        campaign.label = "Label " + code;
        campaign.validFrom = LocalDate.of(2026, 1, 1);
        campaign.validTo = LocalDate.of(2026, 2, 1);
        campaign.notes = "notes";
        campaign.maxAmount = new BigDecimal("100.00");
        campaign.maxApplications = 50;
        campaign.perStore = true;
        campaign.offerCodes = List.of("OFF1", "OFF2");
        return campaign;
    }

    /**
     * {@code findByCode} returns null at once for a null code, without querying.
     */
    @Test
    void findByCodeReturnsNullForNullCode() {
        assertNull(Campaign.findByCode(null));
    }

    /**
     * {@code findByCode} queries on a non-null code and returns the first row.
     */
    @Test
    void findByCodeReturnsTheQueriedCampaign() {
        Campaign expected = campaign("PROMO1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Campaign> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Campaign.find("code", "PROMO1")).thenReturn(query);
            assertSame(expected, Campaign.findByCode("PROMO1"));
        }
    }

    /**
     * {@code listAllOrdered} queries every campaign in code order.
     */
    @Test
    void listAllOrderedQueriesInCodeOrder() {
        List<Campaign> expected = List.of(campaign("A"), campaign("B"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Campaign> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(expected);
            panache.when(() -> Campaign.find("order by code")).thenReturn(query);
            assertEquals(expected, Campaign.listAllOrdered());
        }
    }

    /**
     * {@code status} reads the period against the frozen current day.
     */
    @Test
    void statusReadsThePeriodAgainstToday() {
        Campaign campaign = campaign("PROMO1");
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(LocalDateTime.of(2026, 1, 15, 10, 0));
            assertEquals(CampaignStatus.RUNNING, campaign.status());
        }
    }

    /**
     * {@code hasBudget} is true when an amount ceiling is set.
     */
    @Test
    void hasBudgetTrueWithAmountCeiling() {
        Campaign campaign = new Campaign();
        campaign.maxAmount = new BigDecimal("10.00");
        assertTrue(campaign.hasBudget());
    }

    /**
     * {@code hasBudget} is true when only an applications ceiling is set (the
     * amount-null, applications-non-null arm).
     */
    @Test
    void hasBudgetTrueWithApplicationsCeilingOnly() {
        Campaign campaign = new Campaign();
        campaign.maxApplications = 5;
        assertTrue(campaign.hasBudget());
    }

    /**
     * {@code hasBudget} is false when neither ceiling is set.
     */
    @Test
    void hasBudgetFalseWithoutAnyCeiling() {
        assertFalse(new Campaign().hasBudget());
    }

    /**
     * {@code getChecksum} hashes the salient administered fields.
     */
    @Test
    void getChecksumHashesTheSalientFields() {
        Campaign campaign = campaign("PROMO1");
        int expected = Objects.hash(campaign.code, campaign.label, campaign.validFrom,
                campaign.validTo, campaign.notes, campaign.maxAmount,
                campaign.maxApplications, campaign.perStore, campaign.offerCodes);
        assertEquals(expected, campaign.getChecksum());
    }
}
