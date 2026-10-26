package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.sync.RegisterPresenceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardIndicatorService}.
 * <p>
 * Two things are tested apart: reading the administered list, which has to
 * survive anything an administrator can type (null, blank, unknown codes,
 * duplicates, spaces, a settings table that cannot be read at all), and
 * computing a value, which goes through one of the three arms of the
 * exhaustive switch — a journal count, the net cash moved, the registers
 * gone quiet. The entity manager is a Mockito mock fed pre-baked rows, and
 * the assertions bear absolute expected values, pre-formatted exactly as the
 * dashboard will print them.
 */
class DashboardIndicatorServiceTest {

    /** The settings mock of the service under test. */
    private final PosSettingsService settings = mock(PosSettingsService.class);

    /** The presence mock of the service under test. */
    private final RegisterPresenceService presence = mock(RegisterPresenceService.class);

    /** The entity-manager mock of the service under test. */
    private final EntityManager entityManager = mock(EntityManager.class);

    /**
     * Builds the service with its three collaborators wired to the mocks.
     *
     * @return a service ready under test
     */
    private DashboardIndicatorService service() {
        DashboardIndicatorService service = new DashboardIndicatorService();
        service.entityManager = entityManager;
        service.posSettingsService = settings;
        service.registerPresenceService = presence;
        return service;
    }

    /**
     * Creates a fresh {@link TypedQuery} mock, erasing the unchecked cast to a
     * single suppression point.
     *
     * @param <T> the query result type
     * @return a bare typed-query mock
     */
    @SuppressWarnings("unchecked")
    private <T> TypedQuery<T> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Wires the journal-count query to answer the given count.
     *
     * @param count the count the query returns, possibly null
     */
    private void stubEventCount(Long count) {
        TypedQuery<Long> query = typedQueryMock();
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(count);
        when(entityManager.createQuery(contains("from TechnicalEvent e"), eq(Long.class)))
                .thenReturn(query);
    }

    /**
     * Wires the cash-movement query to answer the given total.
     *
     * @param total the total the query returns, possibly null
     */
    private void stubMovedCash(BigDecimal total) {
        TypedQuery<BigDecimal> query = typedQueryMock();
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(total);
        when(entityManager.createQuery(contains("from CashMovement m"), eq(BigDecimal.class)))
                .thenReturn(query);
    }

    /**
     * Builds one offline-register presence.
     *
     * @param terminalId the silent register
     * @return the presence record
     */
    private RegisterPresenceService.Presence offline(String terminalId) {
        return new RegisterPresenceService.Presence(terminalId, LocalDateTime.now(), 12, false);
    }

    /** A blank parameter chooses nothing: the row is switched off. */
    @Test
    void selectedIsEmptyWhenTheParameterIsBlank() {
        when(settings.dashboardIndicators()).thenReturn("   ");
        assertTrue(service().selected().isEmpty());
    }

    /** A null parameter chooses nothing either. */
    @Test
    void selectedIsEmptyWhenTheParameterIsNull() {
        when(settings.dashboardIndicators()).thenReturn(null);
        assertTrue(service().selected().isEmpty());
    }

    /** A settings table that cannot be read leaves the row empty. */
    @Test
    void selectedIsEmptyWhenTheParameterCannotBeRead() {
        when(settings.dashboardIndicators()).thenThrow(new IllegalStateException("no db"));
        assertTrue(service().selected().isEmpty());
    }

    /** Only unknown codes choose nothing: nothing is substituted. */
    @Test
    void selectedIsEmptyWhenEveryCodeIsUnknown() {
        when(settings.dashboardIndicators()).thenReturn("chiffre-magique;autre-chose");
        assertTrue(service().selected().isEmpty());
    }

    /** The administered ORDER is the display order, not the catalog's. */
    @Test
    void selectedKeepsTheAdministeredOrder() {
        when(settings.dashboardIndicators()).thenReturn("offline-registers;endorsements");
        assertEquals(List.of(DashboardIndicator.OFFLINE_REGISTERS, DashboardIndicator.ENDORSEMENTS),
                service().selected());
    }

    /** An unknown code is skipped and the known ones around it stand. */
    @Test
    void selectedSkipsAnUnknownCodeAmongKnownOnes() {
        when(settings.dashboardIndicators()).thenReturn("duplicates;inconnu;training");
        assertEquals(List.of(DashboardIndicator.DUPLICATES, DashboardIndicator.TRAINING),
                service().selected());
    }

    /** A code named twice appears once, at its first rank. */
    @Test
    void selectedKeepsADuplicatedCodeOnce() {
        when(settings.dashboardIndicators()).thenReturn("training;duplicates;training");
        assertEquals(List.of(DashboardIndicator.TRAINING, DashboardIndicator.DUPLICATES),
                service().selected());
    }

    /** Spaces around a code are tolerated: the list is typed by hand. */
    @Test
    void selectedToleratesSpacesAroundCodes() {
        when(settings.dashboardIndicators()).thenReturn(" price-checks ; parked-tickets ");
        assertEquals(List.of(DashboardIndicator.PRICE_CHECKS, DashboardIndicator.PARKED_TICKETS),
                service().selected());
    }

    /** The catalog's own default list resolves to its four indicators. */
    @Test
    void selectedResolvesTheDefaultList() {
        when(settings.dashboardIndicators())
                .thenReturn("endorsements;age-checks;cash-movements;offline-registers");
        assertEquals(List.of(DashboardIndicator.ENDORSEMENTS, DashboardIndicator.AGE_CHECKS,
                        DashboardIndicator.CASH_MOVEMENTS, DashboardIndicator.OFFLINE_REGISTERS),
                service().selected());
    }

    /** An empty selection produces no tile at all. */
    @Test
    void indicatorsIsEmptyWhenNothingIsSelected() {
        when(settings.dashboardIndicators()).thenReturn("");
        assertTrue(service().indicators().isEmpty());
    }

    /** A journal indicator carries its count, its label and no suffix. */
    @Test
    void indicatorsCountJournalEvents() {
        when(settings.dashboardIndicators()).thenReturn("endorsements");
        stubEventCount(7L);
        List<Map<String, Object>> rows = service().indicators();
        assertEquals(1, rows.size());
        assertEquals("endorsements", rows.get(0).get("code"));
        assertEquals("Avals manager", rows.get(0).get("label"));
        assertEquals("7", rows.get(0).get("value"));
        assertEquals("", rows.get(0).get("unit"));
    }

    /** A journal count that answers nothing reads zero, not null. */
    @Test
    void indicatorsReadZeroWhenTheCountIsNull() {
        when(settings.dashboardIndicators()).thenReturn("age-checks");
        stubEventCount(null);
        assertEquals("0", service().indicators().get(0).get("value"));
    }

    /** The cash movement is a signed amount, French comma, two decimals. */
    @Test
    void indicatorsFormatTheMovedCash() {
        when(settings.dashboardIndicators()).thenReturn("cash-movements");
        stubMovedCash(new BigDecimal("-1234.5"));
        Map<String, Object> row = service().indicators().get(0);
        assertEquals("-1234,50", row.get("value"));
        assertEquals("€", row.get("unit"));
    }

    /** A cash total that answers nothing reads zero, not null. */
    @Test
    void indicatorsReadZeroWhenTheMovedCashIsNull() {
        when(settings.dashboardIndicators()).thenReturn("cash-movements");
        stubMovedCash(null);
        assertEquals("0,00", service().indicators().get(0).get("value"));
    }

    /** The disconnected registers are counted, not queried. */
    @Test
    void indicatorsCountTheOfflineRegisters() {
        when(settings.dashboardIndicators()).thenReturn("offline-registers");
        when(presence.offline()).thenReturn(List.of(offline("CAISSE-01"), offline("CAISSE-02")));
        Map<String, Object> row = service().indicators().get(0);
        assertEquals("2", row.get("value"));
        assertEquals("Caisses déconnectées", row.get("label"));
    }

    /** No disconnected register reads zero rather than nothing. */
    @Test
    void indicatorsReadZeroWhenEveryRegisterSpeaks() {
        when(settings.dashboardIndicators()).thenReturn("offline-registers");
        when(presence.offline()).thenReturn(List.of());
        assertEquals("0", service().indicators().get(0).get("value"));
    }

    /** The three arms coexist, in the administered order. */
    @Test
    void indicatorsMixTheThreeKinds() {
        when(settings.dashboardIndicators())
                .thenReturn("cash-movements;duplicates;offline-registers");
        stubMovedCash(new BigDecimal("40.00"));
        stubEventCount(3L);
        when(presence.offline()).thenReturn(List.of(offline("CAISSE-03")));
        List<Map<String, Object>> rows = service().indicators();
        assertEquals(3, rows.size());
        assertEquals("40,00", rows.get(0).get("value"));
        assertEquals("3", rows.get(1).get("value"));
        assertEquals("1", rows.get(2).get("value"));
    }

    /** Every catalog indicator can be selected and computed. */
    @Test
    void everyIndicatorCanBeComputed() {
        StringBuilder all = new StringBuilder();
        for (DashboardIndicator indicator : DashboardIndicator.values()) {
            if (all.length() > 0) {
                all.append(';');
            }
            all.append(indicator.code());
        }
        when(settings.dashboardIndicators()).thenReturn(all.toString());
        stubEventCount(1L);
        stubMovedCash(new BigDecimal("2.00"));
        when(presence.offline()).thenReturn(List.of());
        List<Map<String, Object>> rows = service().indicators();
        assertEquals(DashboardIndicator.values().length, rows.size());
        for (Map<String, Object> row : rows) {
            assertTrue(row.get("value") != null, "value of " + row.get("code"));
        }
    }

    /** The service hands the catalog's own metadata through, untouched. */
    @Test
    void indicatorsCarryTheCatalogMetadata() {
        when(settings.dashboardIndicators()).thenReturn("supervisor-calls");
        stubEventCount(0L);
        Map<String, Object> row = service().indicators().get(0);
        assertSame(DashboardIndicator.SUPERVISOR_CALLS.label(), row.get("label"));
        assertSame(DashboardIndicator.SUPERVISOR_CALLS.code(), row.get("code"));
    }

    /**
     * The open codes address any datum of the system (BO-04-03-03/-15): journal
     * events of a type, settlements of a method, revenue of a branch, movements
     * of a type; a duplicate is shown once and an unreadable code is ignored.
     */
    @Test
    void indicatorsReadTheOpenCodes() {
        when(settings.dashboardIndicators()).thenReturn(
                "event:price_modified;event:PRICE_MODIFIED;payment:cheque;family:10;movement:withdrawal;"
                        + "event:NOPE;payment:NOPE;movement:NOPE;other:1;:x;event:; ;endorsements");
        stubEventCount(3L);
        stubMovedCash(new BigDecimal("40"));
        TypedQuery<BigDecimal> payments = typedQueryMock();
        when(payments.setParameter(anyString(), any())).thenReturn(payments);
        when(payments.getSingleResult()).thenReturn(new BigDecimal("12.5"));
        when(entityManager.createQuery(contains("join t.payments p"), eq(BigDecimal.class))).thenReturn(payments);
        TypedQuery<BigDecimal> family = typedQueryMock();
        when(family.setParameter(anyString(), any())).thenReturn(family);
        when(family.getSingleResult()).thenReturn(null);
        when(entityManager.createQuery(contains("l.familyCode like :family"), eq(BigDecimal.class))).thenReturn(family);
        List<Map<String, Object>> rows = service().indicators();
        assertEquals(5, rows.size());
        assertEquals("Événements PRICE_MODIFIED", rows.get(0).get("label"));
        assertEquals("3", rows.get(0).get("value"));
        assertEquals("", rows.get(0).get("unit"));
        assertEquals("Règlements CHEQUE", rows.get(1).get("label"));
        assertEquals("12,50", rows.get(1).get("value"));
        assertEquals("€", rows.get(1).get("unit"));
        assertEquals("CA famille 10", rows.get(2).get("label"));
        assertEquals("0,00", rows.get(2).get("value"));
        assertEquals("Mouvements WITHDRAWAL", rows.get(3).get("label"));
        assertEquals("t.s21-ev-", rows.get(0).get("subjectPrefix"));
        assertEquals("PRICE_MODIFIED", rows.get(0).get("subject"));
        assertEquals("t.s21-pm-", rows.get(1).get("subjectPrefix"));
        assertEquals("CHEQUE", rows.get(1).get("subject"));
        assertEquals(null, rows.get(2).get("subject"));
        assertEquals("t.s21-mv-", rows.get(3).get("subjectPrefix"));
        assertEquals("WITHDRAWAL", rows.get(3).get("subject"));
        assertEquals("40,00", rows.get(3).get("value"));
        assertEquals("endorsements", rows.get(4).get("code"));
    }

    /**
     * A list that cannot be read, or none, shows no indicator.
     */
    @Test
    void indicatorsAreEmptyWhenTheListCannotBeRead() {
        when(settings.dashboardIndicators()).thenThrow(new IllegalStateException("no db"));
        assertTrue(service().indicators().isEmpty());
        org.mockito.Mockito.doReturn(null).when(settings).dashboardIndicators();
        assertTrue(service().tokens().isEmpty());
    }
}
