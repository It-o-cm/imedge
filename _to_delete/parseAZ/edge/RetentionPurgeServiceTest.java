package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.util.DateTimeProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetentionPurgeService}.
 * <p>
 * Plain JUnit 5 + Mockito, no Quarkus and no database: the service is built with
 * {@code new}, its two collaborators ({@link PosSettingsService} and the
 * {@link EntityManager}) are Mockito mocks assigned to the package-private
 * {@code @Inject} fields, and the generic sweep's JPQL is verified by stubbing
 * {@link EntityManager#createQuery(String)} on the exact query strings the
 * mechanism builds. The clock is frozen with {@code mockStatic(DateTimeProvider)}
 * so every cutoff is an absolute expected value.
 * <p>
 * Branch enumeration (every arm exercised): {@code applyAgeDays} — the
 * unlimited arm (zero and negative), the {@code dateOnly} arm and its
 * {@code LocalDateTime} counterpart, the null-guard arm and the guarded arm, the
 * uncapped bulk arm and the capped arm (its empty and its non-empty legs);
 * {@code applyKeepLatest} — the unlimited arm, the guarded and null-guard select
 * arms, the within-count arm and the over-count arm; {@code trace} — the
 * nothing-deleted arm and the deleted arm (with a date bound and without);
 * {@code bindGuardParams} — the empty and non-empty loops; {@code sweep} — the
 * all-inert run (both dispatch arms via their skip), one working policy, and the
 * failure-caught arm.
 */
class RetentionPurgeServiceTest {

    /** A frozen moment, so every computed cutoff is an absolute expected value. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 15, 22, 0);

    /**
     * Builds a service whose collaborators are mocks.
     *
     * @return the wired service
     */
    private RetentionPurgeService newService() {
        RetentionPurgeService service = new RetentionPurgeService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.entityManager = mock(EntityManager.class);
        return service;
    }

    /**
     * Builds a query mock that answers a result list.
     *
     * @param ids the ids the query resolves
     * @return the query mock
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Query listQuery(List<Long> ids) {
        Query query = mock(Query.class);
        when(query.getResultList()).thenReturn((List) ids);
        return query;
    }

    /**
     * Builds a query mock that answers a delete count.
     *
     * @param count the number of rows the delete removes
     * @return the query mock
     */
    private Query deleteQuery(int count) {
        Query query = mock(Query.class);
        when(query.executeUpdate()).thenReturn(count);
        return query;
    }

    /**
     * An age policy targeting a {@code LocalDateTime} column with a parameterised
     * reference-safety guard and no cap.
     *
     * @return the policy
     */
    private RetentionPurgeService.Policy bulkPolicy() {
        return new RetentionPurgeService.Policy("T-BULK", "ventes", "Ticket", "closingDate", false,
                "e.status = ?2", List.of(Ticket.TicketStatus.CLOSED), "k.bulk", null,
                RetentionPurgeService.Mode.AGE_DAYS);
    }

    /**
     * A guarded age policy deletes, in one bulk statement, the rows older than
     * the cutoff, binding the cutoff and each guard parameter, and reports the
     * count.
     */
    @Test
    void anAgePolicyDeletesInBulkWithItsGuard() {
        RetentionPurgeService service = newService();
        RetentionPurgeService.Policy policy = bulkPolicy();
        Query delete = deleteQuery(4);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            when(service.entityManager.createQuery(
                    "delete from Ticket e where e.closingDate < ?1 and (e.status = ?2)"))
                    .thenReturn(delete);
            assertEquals(4, service.applyAgeDays(policy, 30));
        }
        verify(delete).setParameter(1, NOW.minusDays(30));
        verify(delete).setParameter(2, Ticket.TicketStatus.CLOSED);
        verify(delete).executeUpdate();
    }

    /**
     * A date-only policy builds a {@code LocalDate} cutoff and, with no guard,
     * omits the guard clause and binds no guard parameter.
     */
    @Test
    void aDateOnlyPolicyBuildsADateCutoffAndNoGuard() {
        RetentionPurgeService service = newService();
        RetentionPurgeService.Policy policy = new RetentionPurgeService.Policy("T-DATE", "promotions",
                "Campaign", "validTo", true, null, List.of(), "k.date", null,
                RetentionPurgeService.Mode.AGE_DAYS);
        Query delete = deleteQuery(2);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            when(service.entityManager.createQuery("delete from Campaign e where e.validTo < ?1"))
                    .thenReturn(delete);
            assertEquals(2, service.applyAgeDays(policy, 10));
        }
        verify(delete).setParameter(1, LocalDate.of(2026, 1, 15).minusDays(10));
        verify(delete, never()).setParameter(2, Ticket.TicketStatus.CLOSED);
    }

    /**
     * A zero delay leaves the policy inert: nothing is read, nothing is deleted —
     * the delivered default that ships the feature without destroying a thing.
     */
    @Test
    void aZeroDelayDeletesNothing() {
        RetentionPurgeService service = newService();
        assertEquals(0, service.applyAgeDays(bulkPolicy(), 0));
        verifyNoInteractions(service.entityManager);
    }

    /**
     * A negative delay, as unreadable as it is, is read as unlimited retention
     * rather than a window running backwards.
     */
    @Test
    void aNegativeDelayDeletesNothing() {
        RetentionPurgeService service = newService();
        assertEquals(0, service.applyAgeDays(bulkPolicy(), -5));
        verifyNoInteractions(service.entityManager);
    }

    /**
     * A capped policy selects the oldest ids up to the cap and deletes exactly
     * those, so a large backlog cannot block the end of period.
     */
    @Test
    void aCappedPolicyDeletesOnlyUpToTheCap() {
        RetentionPurgeService service = newService();
        RetentionPurgeService.Policy policy = new RetentionPurgeService.Policy("T-CAP", "promotions",
                "Campaign", "validTo", false, null, List.of(), "k.cap", "k.capValue",
                RetentionPurgeService.Mode.AGE_DAYS);
        when(service.posSettingsService.retentionSetting("k.capValue")).thenReturn(3);
        Query select = listQuery(List.of(1L, 2L));
        Query delete = deleteQuery(2);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            when(service.entityManager.createQuery(
                    "select e.id from Campaign e where e.validTo < ?1 order by e.validTo asc"))
                    .thenReturn(select);
            when(service.entityManager.createQuery("delete from Campaign e where e.id in ?1"))
                    .thenReturn(delete);
            assertEquals(2, service.applyAgeDays(policy, 20));
        }
        verify(select).setParameter(1, NOW.minusDays(20));
        verify(select).setMaxResults(3);
        verify(delete).setParameter(1, List.of(1L, 2L));
    }

    /**
     * A capped policy that finds nothing over the cap deletes nothing and never
     * issues a delete.
     */
    @Test
    void aCappedPolicyWithNothingOverTheCapDeletesNothing() {
        RetentionPurgeService service = newService();
        RetentionPurgeService.Policy policy = new RetentionPurgeService.Policy("T-CAP", "promotions",
                "Campaign", "validTo", false, null, List.of(), "k.cap", "k.capValue",
                RetentionPurgeService.Mode.AGE_DAYS);
        when(service.posSettingsService.retentionSetting("k.capValue")).thenReturn(3);
        Query select = listQuery(List.of());
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            when(service.entityManager.createQuery(
                    "select e.id from Campaign e where e.validTo < ?1 order by e.validTo asc"))
                    .thenReturn(select);
            assertEquals(0, service.applyAgeDays(policy, 20));
        }
        verify(service.entityManager, never()).createQuery("delete from Campaign e where e.id in ?1");
    }

    /**
     * A bulk delete that removes no row still returns zero and leaves no trace —
     * the nothing-deleted arm of the trace.
     */
    @Test
    void aBulkDeleteThatRemovesNothingReturnsZero() {
        RetentionPurgeService service = newService();
        Query delete = deleteQuery(0);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            when(service.entityManager.createQuery(
                    "delete from Ticket e where e.closingDate < ?1 and (e.status = ?2)"))
                    .thenReturn(delete);
            assertEquals(0, service.applyAgeDays(bulkPolicy(), 30));
        }
        verify(delete).executeUpdate();
    }

    /**
     * A guarded keep-latest policy keeps the most recent rows and deletes the
     * older overflow, binding the guard parameter from the first index.
     */
    @Test
    void aKeepLatestPolicyDeletesTheOverflow() {
        RetentionPurgeService service = newService();
        RetentionPurgeService.Policy policy = new RetentionPurgeService.Policy("T-KEEP", "rapports",
                "TechnicalEvent", "eventDate", false, "e.eventType = ?1",
                List.of(TechnicalEvent.EventType.X_REPORT_PRINTED), "k.keep", null,
                RetentionPurgeService.Mode.KEEP_LATEST);
        Query select = listQuery(List.of(10L, 9L, 8L, 7L));
        Query delete = deleteQuery(2);
        when(service.entityManager.createQuery(
                "select e.id from TechnicalEvent e where e.eventType = ?1 order by e.eventDate desc"))
                .thenReturn(select);
        when(service.entityManager.createQuery("delete from TechnicalEvent e where e.id in ?1"))
                .thenReturn(delete);
        assertEquals(2, service.applyKeepLatest(policy, 2));
        verify(select).setParameter(1, TechnicalEvent.EventType.X_REPORT_PRINTED);
        verify(delete).setParameter(1, List.of(8L, 7L));
    }

    /**
     * A keep-latest policy with no guard omits the {@code where} clause, and when
     * the kept count already covers the rows it deletes nothing.
     */
    @Test
    void aKeepLatestPolicyWithinTheKeptCountDeletesNothing() {
        RetentionPurgeService service = newService();
        RetentionPurgeService.Policy policy = new RetentionPurgeService.Policy("T-KEEP2", "rapports",
                "TechnicalEvent", "eventDate", false, null, List.of(), "k.keep2", null,
                RetentionPurgeService.Mode.KEEP_LATEST);
        Query select = listQuery(List.of(10L));
        when(service.entityManager.createQuery(
                "select e.id from TechnicalEvent e order by e.eventDate desc"))
                .thenReturn(select);
        assertEquals(0, service.applyKeepLatest(policy, 2));
        verify(service.entityManager, never()).createQuery("delete from TechnicalEvent e where e.id in ?1");
    }

    /**
     * A zero kept count leaves the count-based policy inert.
     */
    @Test
    void aZeroKeptCountDeletesNothing() {
        RetentionPurgeService service = newService();
        RetentionPurgeService.Policy policy = new RetentionPurgeService.Policy("T-KEEP2", "rapports",
                "TechnicalEvent", "eventDate", false, null, List.of(), "k.keep2", null,
                RetentionPurgeService.Mode.KEEP_LATEST);
        assertEquals(0, service.applyKeepLatest(policy, 0));
        verifyNoInteractions(service.entityManager);
    }

    /**
     * With every parameter at zero the sweep touches no table: both dispatch arms
     * are taken (age and keep-latest policies alike) and each returns inert.
     */
    @Test
    void anAllInertSweepTouchesNothing() {
        RetentionPurgeService service = newService();
        when(service.posSettingsService.retentionSetting(anyString())).thenReturn(0);
        assertEquals(0, service.sweep());
        verifyNoInteractions(service.entityManager);
    }

    /**
     * BO-09-03-02: the automatic end-of-period editions have their own
     * retention, armed like any other — the documents they keep are the one
     * table of this node that grows by itself.
     */
    @Test
    void theScheduledEditionsHaveTheirOwnRetention() {
        RetentionPurgeService service = newService();
        when(service.posSettingsService.retentionSetting(anyString())).thenReturn(0);
        when(service.posSettingsService.retentionSetting("retention.scheduled-reports-days"))
                .thenReturn(90);
        Query delete = deleteQuery(4);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            when(service.entityManager.createQuery(
                    "delete from ScheduledReportRun e where e.generatedAt < ?1"))
                    .thenReturn(delete);
            assertEquals(4, service.sweep());
        }
        verify(delete).setParameter(1, NOW.minusDays(90));
    }

    /**
     * A single configured policy runs while the others stay inert, and the sweep
     * reports its count.
     */
    @Test
    void aSweepRunsTheConfiguredPolicyOnly() {
        RetentionPurgeService service = newService();
        when(service.posSettingsService.retentionSetting(anyString())).thenReturn(0);
        when(service.posSettingsService.retentionSetting("retention.alerts-days")).thenReturn(5);
        Query delete = deleteQuery(3);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            when(service.entityManager.createQuery(
                    "delete from TechnicalEvent e where e.eventDate < ?1 and (e.eventType = ?2)"))
                    .thenReturn(delete);
            assertEquals(3, service.sweep());
        }
        verify(delete).setParameter(1, NOW.minusDays(5));
        verify(delete).setParameter(2, TechnicalEvent.EventType.SUPERVISOR_CALLED);
    }

    /**
     * A policy that fails is logged and skipped, and the sweep does not take the
     * whole end-of-period purge down with it.
     */
    @Test
    void aFailingPolicyIsSkipped() {
        RetentionPurgeService service = newService();
        when(service.posSettingsService.retentionSetting(anyString()))
                .thenThrow(new IllegalStateException("base indisponible"));
        assertEquals(0, service.sweep());
        verifyNoInteractions(service.entityManager);
    }
}
