package com.intermarche.edge.service;

import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.util.DateTimeProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * The end-of-period DATA-RETENTION sweep (BO-10-08-03 to -26).
 *
 * <p>Fifteen requirements of the back-office retention sub-function, one
 * mechanism: a catalog of retention POLICIES and a single sweep that applies
 * them. A policy describes itself ONCE — the entity it targets, the date column
 * that dates a row, the parameter that carries the delay, the reference-safety
 * guard and an optional per-pass cap — and the sweep reads that description. It
 * holds no {@code if} per requirement: adding a retention is one {@link Policy}
 * entry in {@link #CATALOG}, not a new branch here.
 *
 * <h2>The design rules the requirements impose</h2>
 * <ul>
 *   <li><b>Zero means unlimited.</b> Every retention parameter ships at zero and
 *       zero disables its policy: delivering an active purge on an existing base
 *       would destroy data nobody asked to destroy. A negative or corrupt value
 *       resolves to zero too ({@link PosSettingsService#retentionSetting}), so an
 *       unreadable row can never start deleting on its own.</li>
 *   <li><b>A purge never breaks a reference.</b> The safety is written into the
 *       policy's {@code guard}: a parked ticket on an OPEN session, an invoice
 *       tied to an active customer, a deactivated article still referenced by a
 *       price or a ticket line, a customer with a credit balance — all are left
 *       in place by a {@code where} clause, not deleted and rolled back. A row a
 *       constraint would forbid is simply never selected.</li>
 *   <li><b>Every pass leaves a trace.</b> Each policy that deletes anything
 *       writes the table, the row count and the date bound to the technical log
 *       ({@link #trace}): an erased datum with no trace is indefensible, and this
 *       lot answers a GDPR expectation the workbook states line by line. The
 *       trace goes to the technical LOG rather than the {@link TechnicalEvent}
 *       journal on purpose: that journal is a class kept file-identical with the
 *       register node, so a new event type cannot be added on one side alone.</li>
 *   <li><b>A cap keeps the sweep from blocking the close.</b> A policy may carry
 *       a per-pass ceiling (BO-10-08-18): the sweep then deletes at most that
 *       many rows this pass, oldest first, and takes the rest at the next
 *       end of period.</li>
 * </ul>
 *
 * <p>The sweep hooks into {@link PeriodCloseService}'s end-of-period tick, not a
 * parallel scheduler: a purge running while the day is still closing would read
 * tables that are being written.
 */
@ApplicationScoped
public class RetentionPurgeService {

    /** Technical log of this class — also where each pass leaves its trace. */
    private static final Logger LOGGER = Logger.getLogger(RetentionPurgeService.class);

    /**
     * How a policy decides what is stale.
     * <ul>
     *   <li>{@link #AGE_DAYS} — delete rows whose date column is older than the
     *       parameter's number of days.</li>
     *   <li>{@link #KEEP_LATEST} — keep the parameter's number of most recent
     *       rows and delete the rest; the parameter is a COUNT, not days
     *       (BO-10-08-07).</li>
     * </ul>
     */
    public enum Mode { AGE_DAYS, KEEP_LATEST }

    /**
     * One retention policy, described once and read by the sweep.
     *
     * @param requirement the workbook requirement it answers (BO-10-08-xx)
     * @param tableLabel the human table name written to the trace
     * @param entityName the JPQL entity name the sweep deletes from
     * @param dateField the reference date column (JPQL path, alias {@code e} added
     *        by the sweep)
     * @param dateOnly true when {@code dateField} is a {@code LocalDate} rather
     *        than a {@code LocalDateTime}, so the cutoff is built as a date
     * @param guard the reference-safety {@code where} fragment (alias {@code e},
     *        positional params from {@code ?2} in AGE_DAYS and from {@code ?1} in
     *        KEEP_LATEST), or null when the date bound alone is enough
     * @param guardParams the values bound to the guard's positional parameters,
     *        in order; empty when the guard has none
     * @param paramKey the settings key carrying the delay (days) or the count
     * @param capKey the settings key carrying the per-pass ceiling, or null when
     *        the policy is uncapped
     * @param mode how the policy decides what is stale
     */
    public record Policy(String requirement, String tableLabel, String entityName,
                         String dateField, boolean dateOnly, String guard,
                         List<Object> guardParams, String paramKey, String capKey,
                         Mode mode) {
    }

    /**
     * The catalog of retention policies — one entry per promoted requirement.
     * The sweep iterates it; there is no per-requirement code anywhere else.
     */
    static final List<Policy> CATALOG = List.of(
        new Policy("BO-10-08-03", "ventes article", "Ticket", "closingDate", false,
                "e.status = ?2 "
                + "and e.id not in (select i.ticket.id from Invoice i where i.ticket is not null) "
                + "and e.id not in (select r.originalTicketId from Refund r where r.originalTicketId is not null)",
                List.of(Ticket.TicketStatus.CLOSED),
                "retention.sales-days", null, Mode.AGE_DAYS),
        new Policy("BO-10-08-07", "états caissières (rapports X)", "TechnicalEvent", "eventDate", false,
                "e.eventType = ?1",
                List.of(TechnicalEvent.EventType.X_REPORT_PRINTED),
                "retention.cashier-reports-count", null, Mode.KEEP_LATEST),
        new Policy("BO-10-08-08", "journal technique quotidien", "TechnicalEvent", "eventDate", false,
                "e.eventType not in ?2",
                List.of(List.of(TechnicalEvent.EventType.SUPERVISOR_CALLED,
                        TechnicalEvent.EventType.AUTH_LOCKED, TechnicalEvent.EventType.PASSWORD_FAILED,
                        TechnicalEvent.EventType.PASSWORD_CHANGED, TechnicalEvent.EventType.REGISTER_LOCKED,
                        TechnicalEvent.EventType.REGISTER_UNLOCKED)),
                "retention.daily-tables-days", null, Mode.AGE_DAYS),
        new Policy("BO-10-08-09", "journal d'accès utilisateurs", "TechnicalEvent", "eventDate", false,
                "e.eventType in ?2",
                List.of(List.of(TechnicalEvent.EventType.AUTH_LOCKED, TechnicalEvent.EventType.PASSWORD_FAILED,
                        TechnicalEvent.EventType.PASSWORD_CHANGED, TechnicalEvent.EventType.REGISTER_LOCKED,
                        TechnicalEvent.EventType.REGISTER_UNLOCKED)),
                "retention.access-journal-days", null, Mode.AGE_DAYS),
        new Policy("BO-10-08-11", "alertes (appels superviseur)", "TechnicalEvent", "eventDate", false,
                "e.eventType = ?2",
                List.of(TechnicalEvent.EventType.SUPERVISOR_CALLED),
                "retention.alerts-days", null, Mode.AGE_DAYS),
        new Policy("BO-10-08-12", "tickets en attente", "Ticket", "creationDate", false,
                "e.status = ?2 and (e.session is null or e.session.status <> ?3)",
                List.of(Ticket.TicketStatus.PARKED, CashSession.SessionStatus.OPEN),
                "retention.parked-tickets-days", null, Mode.AGE_DAYS),
        new Policy("BO-10-08-16", "factures / bons de livraison", "Invoice", "issueDate", false,
                "(e.customer is null or e.customer.blocked = true)",
                List.of(),
                "retention.invoices-days", null, Mode.AGE_DAYS),
        new Policy("BO-10-08-17", "promotions échues", "Campaign", "validTo", true,
                null, List.of(),
                "retention.expired-promotions-days", "retention.expired-promotions-cap", Mode.AGE_DAYS),
        new Policy("BO-10-08-24", "articles supprimés", "Product", "updatedAt", false,
                "e.active = false "
                + "and e.id not in (select pr.product.id from Price pr where pr.product is not null) "
                + "and e.id not in (select tl.product.id from TicketLine tl where tl.product is not null)",
                List.of(),
                "retention.deleted-articles-days", null, Mode.AGE_DAYS),
        new Policy("BO-10-08-10", "résultats d'intégration du référentiel", "RefApplyAudit",
                "appliedAt", false, null, List.of(),
                "retention.integration-results-days", null, Mode.AGE_DAYS),
        new Policy("BO-10-08-26", "clients", "AccountCustomer", "updatedAt", false,
                "e.creditBalance = 0 "
                + "and e.id not in (select i.customer.id from Invoice i where i.customer is not null)",
                List.of(),
                "retention.customers-days", null, Mode.AGE_DAYS),
        new Policy("BO-09-03-02", "\u00e9ditions automatiques de fin de p\u00e9riode",
                "ScheduledReportRun", "generatedAt", false, null, List.of(),
                "retention.scheduled-reports-days", null, Mode.AGE_DAYS),
        new Policy("BO-10-01-11", "commandes e-commerce archivées", "EcommerceOrder",
                "receivedAt", false, null, List.of(),
                "channel.ecommerce.archive-days", null, Mode.AGE_DAYS));

    /** Reads the administered retention values. */
    @Inject PosSettingsService posSettingsService;

    /** The persistence context the generic sweep builds its JPQL against. */
    @Inject EntityManager entityManager;

    /**
     * Applies every retention policy once, tolerating a failure per policy: a
     * lot that fails is logged and skipped, and the others still run — one bad
     * table must not block the whole end-of-period purge.
     *
     * @return the total number of rows deleted across all policies
     */
    @Transactional
    public int sweep() {
        LOGGER.info("Entering method sweep");
        int total = 0;
        for (Policy policy : CATALOG) {
            try {
                int value = fiscalFloor(policy, posSettingsService.retentionSetting(policy.paramKey()));
                int deleted = policy.mode() == Mode.KEEP_LATEST
                        ? applyKeepLatest(policy, value)
                        : applyAgeDays(policy, value);
                total += deleted;
            } catch (Exception failure) {
                LOGGER.warnf("Rétention %s impossible (%s): lot ignoré", policy.requirement(), failure.getMessage());
            }
        }
        LOGGER.info("Exiting method sweep with total " + total);
        return total;
    }

    /**
     * The policies that delete what the point of sale must keep online for the
     * fiscal years ({@code BO-05-01-06}): the sales and the invoices.
     */
    static final java.util.Set<String> FISCAL_POLICIES = java.util.Set.of("BO-10-08-03", "BO-10-08-16");

    /**
     * Raises the delay of a fiscal policy to the years kept online
     * ({@code BO-05-01-06}): a ticket or an invoice younger than
     * {@code fiscal.online-retention-years} is never purged, whatever delay the
     * retention parameter states. An unlimited retention (zero) stays unlimited,
     * and the other policies are left as administered.
     *
     * @param policy the policy
     * @param days the administered delay, in days
     * @return the delay the sweep applies
     */
    int fiscalFloor(Policy policy, int days) {
        if (days <= 0 || !FISCAL_POLICIES.contains(policy.requirement())) {
            return days;
        }
        java.time.LocalDate today = DateTimeProvider.now().toLocalDate();
        int floor = (int) java.time.temporal.ChronoUnit.DAYS.between(
                today.minusYears(posSettingsService.fiscalOnlineRetentionYears()), today);
        return Math.max(days, floor);
    }

    /**
     * Applies an age-based policy: deletes the rows whose date column is older
     * than the given number of days, honouring the reference-safety guard and
     * the optional per-pass cap.
     *
     * @param policy the policy to apply
     * @param days the retention in days; zero or negative disables the policy
     * @return the number of rows deleted
     */
    int applyAgeDays(Policy policy, int days) {
        LOGGER.info("Entering method applyAgeDays for " + policy.requirement());
        if (days <= 0) {
            LOGGER.info("Exiting method applyAgeDays: unlimited retention");
            return 0;
        }
        Object cutoff = policy.dateOnly()
                ? DateTimeProvider.now().toLocalDate().minusDays(days)
                : DateTimeProvider.now().minusDays(days);
        String where = "e." + policy.dateField() + " < ?1"
                + (policy.guard() == null ? "" : " and (" + policy.guard() + ")");
        int cap = policy.capKey() == null ? 0 : posSettingsService.retentionSetting(policy.capKey());
        int deleted;
        if (cap > 0) {
            Query select = entityManager.createQuery("select e.id from " + policy.entityName()
                    + " e where " + where + " order by e." + policy.dateField() + " asc");
            select.setParameter(1, cutoff);
            bindGuardParams(select, policy, 2);
            select.setMaxResults(cap);
            List<?> ids = select.getResultList();
            if (ids.isEmpty()) {
                LOGGER.info("Exiting method applyAgeDays: nothing over the cap");
                return 0;
            }
            Query delete = entityManager.createQuery("delete from " + policy.entityName() + " e where e.id in ?1");
            delete.setParameter(1, ids);
            deleted = delete.executeUpdate();
        } else {
            Query delete = entityManager.createQuery("delete from " + policy.entityName() + " e where " + where);
            delete.setParameter(1, cutoff);
            bindGuardParams(delete, policy, 2);
            deleted = delete.executeUpdate();
        }
        trace(policy, deleted, cutoff);
        LOGGER.info("Exiting method applyAgeDays with " + deleted);
        return deleted;
    }

    /**
     * Applies a count-based policy: keeps the given number of most recent rows
     * matching the guard and deletes the rest (BO-10-08-07).
     *
     * @param policy the policy to apply
     * @param keep how many recent rows to keep; zero or negative disables the policy
     * @return the number of rows deleted
     */
    int applyKeepLatest(Policy policy, int keep) {
        LOGGER.info("Entering method applyKeepLatest for " + policy.requirement());
        if (keep <= 0) {
            LOGGER.info("Exiting method applyKeepLatest: unlimited retention");
            return 0;
        }
        Query select = entityManager.createQuery("select e.id from " + policy.entityName() + " e"
                + (policy.guard() == null ? "" : " where " + policy.guard())
                + " order by e." + policy.dateField() + " desc");
        bindGuardParams(select, policy, 1);
        List<?> ids = select.getResultList();
        if (ids.size() <= keep) {
            LOGGER.info("Exiting method applyKeepLatest: within the kept count");
            return 0;
        }
        List<?> stale = ids.subList(keep, ids.size());
        Query delete = entityManager.createQuery("delete from " + policy.entityName() + " e where e.id in ?1");
        delete.setParameter(1, stale);
        int deleted = delete.executeUpdate();
        trace(policy, deleted, null);
        LOGGER.info("Exiting method applyKeepLatest with " + deleted);
        return deleted;
    }

    /**
     * Binds a policy's guard parameters to a query, in order, from a start index.
     *
     * @param query the query to bind onto
     * @param policy the policy carrying the guard parameters
     * @param startIndex the first positional index to bind to
     */
    private void bindGuardParams(Query query, Policy policy, int startIndex) {
        int index = startIndex;
        for (Object param : policy.guardParams()) {
            query.setParameter(index++, param);
        }
    }

    /**
     * Writes one pass's outcome to the technical log — the table, the number of
     * rows and the date bound — when it deleted anything; a pass that deleted
     * nothing leaves no trace.
     *
     * @param policy the policy that ran
     * @param rows the number of rows it deleted
     * @param dateBound the age cutoff, or null for a count-based policy
     */
    void trace(Policy policy, long rows, Object dateBound) {
        if (rows <= 0) {
            return;
        }
        LOGGER.infof("Rétention %s: %d ligne(s) supprimée(s) de « %s » (borne %s)",
                policy.requirement(), rows, policy.tableLabel(),
                dateBound == null ? "N plus récents conservés" : dateBound);
    }
}
