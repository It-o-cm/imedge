package com.intermarche.edge.ui.journal;

import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JournalService}.
 * <p>
 * The pure query builders ({@code buildTicketQuery}, {@code buildEventQuery})
 * are exercised directly, with no {@link EntityManager} in sight: each optional
 * criterion is verified on both arms (present → clause added, absent → nothing)
 * and the PLU/reduction range helpers on their min-only, max-only and
 * both-present shapes. The execution methods ({@code search},
 * {@code searchEvents}, {@code buildDetail}, {@code exportCsv}) run against a
 * mocked entity manager whose typed queries return pre-baked rows: each search
 * stubs its count query as well, since the list is now paged and always states
 * its total. Absolute expected values throughout; no database, no Quarkus
 * context.
 */
class JournalServiceTest {

    /**
     * Builds a service whose entity manager is the given mock.
     *
     * @param entityManager the mocked entity manager
     * @return the wired service
     */
    private JournalService serviceWith(EntityManager entityManager) {
        JournalService service = new JournalService();
        service.entityManager = entityManager;
        service.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        if (org.mockito.Mockito.mockingDetails(entityManager).isMock()) {
            TypedQuery<Object[]> vat = selfQuery();
            when(entityManager.createQuery(org.mockito.ArgumentMatchers.startsWith("select t.id, l.vatRate"),
                    eq(Object[].class))).thenReturn(vat);
            when(vat.getResultList()).thenReturn(List.of());
            TypedQuery<TechnicalEvent> events = selfQuery();
            when(entityManager.createQuery(org.mockito.ArgumentMatchers.startsWith(
                    "select e from TechnicalEvent e where e.ticketNumber"), eq(TechnicalEvent.class))).thenReturn(events);
            when(events.getResultList()).thenReturn(List.of());
            TypedQuery<com.intermarche.edge.domain.sale.Invoice> documents = selfQuery();
            when(entityManager.createQuery(org.mockito.ArgumentMatchers.startsWith("select d from Invoice d"),
                    eq(com.intermarche.edge.domain.sale.Invoice.class))).thenReturn(documents);
            when(documents.getResultList()).thenReturn(List.of());
        }
        return service;
    }

    /**
     * Creates a fresh {@link TypedQuery} mock whose {@code setParameter} and
     * {@code setMaxResults} return the query itself.
     *
     * @param <T> the query result type
     * @return the self-returning typed-query mock
     */
    @SuppressWarnings("unchecked")
    private <T> TypedQuery<T> selfQuery() {
        TypedQuery<T> query = mock(TypedQuery.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.setFirstResult(anyInt())).thenReturn(query);
        return query;
    }

    /**
     * Stubs the count query of a mocked entity manager.
     *
     * @param entityManager the mocked entity manager
     * @param total the total the count query must return
     */
    private void stubCount(EntityManager entityManager, long total) {
        TypedQuery<Long> query = selfQuery();
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(query);
        when(query.getSingleResult()).thenReturn(total);
    }

    // --------------------------------------------------
    // buildTicketQuery
    // --------------------------------------------------

    /**
     * An empty criteria produces only the base finalized-documents clause
     * (every optional criterion on its absent arm).
     */
    @Test
    void emptyCriteriaProducesOnlyBaseClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalQuery query = service.buildTicketQuery(new JournalCriteria());
        assertEquals(" where t.status in :statuses", query.whereClause());
        assertEquals(List.of(Ticket.TicketStatus.CLOSED, Ticket.TicketStatus.CANCELLED),
                query.parameters().get("statuses"));
        assertEquals(1, query.parameters().size());
    }

    /**
     * A criteria with every field set adds every clause and binds every
     * parameter (every optional criterion on its present arm, all eight flags).
     */
    @Test
    void fullCriteriaAddsEveryClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.text = "lait";
        criteria.cashierMin = "100";
        criteria.cashierMax = "200";
        criteria.terminalMin = "C01";
        criteria.terminalMax = "C09";
        criteria.txMin = "A";
        criteria.txMax = "Z";
        criteria.amountMin = new BigDecimal("1.00");
        criteria.amountMax = new BigDecimal("9.00");
        criteria.methods.add("CARD");
        criteria.dateFrom = LocalDateTime.of(2026, 8, 1, 0, 0);
        criteria.dateTo = LocalDateTime.of(2026, 8, 31, 23, 59);
        criteria.pluMin = "40";
        criteria.pluMax = "50";
        criteria.familyMin = "FRUITS";
        criteria.familyMax = "LEGUMES";
        criteria.vatRate = new BigDecimal("0.2000");
        criteria.reductionMin = new BigDecimal("1.00");
        criteria.reductionMax = new BigDecimal("5.00");
        criteria.authMin = "100000";
        criteria.authMax = "999999";
        criteria.refundPluMin = "40";
        criteria.refundPluMax = "60";
        criteria.refundAmountMin = new BigDecimal("2.00");
        criteria.refundAmountMax = new BigDecimal("20.00");
        criteria.cancelPluMin = "70";
        criteria.cancelPluMax = "80";
        criteria.cancelAmountMin = new BigDecimal("3.00");
        criteria.cancelAmountMax = new BigDecimal("30.00");
        criteria.flags.add(JournalCriteria.Flag.CANCELLED);
        criteria.flags.add(JournalCriteria.Flag.CANCELLED_ARTICLE);
        criteria.flags.add(JournalCriteria.Flag.DISCOUNT);
        criteria.flags.add(JournalCriteria.Flag.RETURN);
        criteria.flags.add(JournalCriteria.Flag.NEGATIVE);
        criteria.flags.add(JournalCriteria.Flag.ZERO_PRICE);
        criteria.flags.add(JournalCriteria.Flag.UNKNOWN_ITEM);
        criteria.flags.add(JournalCriteria.Flag.VOUCHER);
        criteria.flags.add(JournalCriteria.Flag.CARD);
        criteria.flags.add(JournalCriteria.Flag.DEGRADED);
        criteria.flags.add(JournalCriteria.Flag.DEGRADED_MANUAL);
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        Map<String, Object> params = query.parameters();
        assertTrue(where.contains("lower(t.ticketNumber) like :text"));
        assertEquals("%lait%", params.get("text"));
        assertTrue(where.contains("t.cashier.badgeId >= :cashierMin"));
        assertTrue(where.contains("t.cashier.badgeId <= :cashierMax"));
        assertTrue(where.contains("t.terminalId >= :terminalMin"));
        assertTrue(where.contains("t.terminalId <= :terminalMax"));
        assertTrue(where.contains("t.ticketNumber >= :txMin"));
        assertTrue(where.contains("t.ticketNumber <= :txMax"));
        assertTrue(where.contains("t.totalIncludingTax >= :amountMin"));
        assertTrue(where.contains("t.totalIncludingTax <= :amountMax"));
        assertTrue(where.contains("type(p) in :methodTypes"));
        assertEquals(List.of(CardPayment.class), params.get("methodTypes"));
        assertTrue(where.contains("t.creationDate >= :dateFrom"));
        assertTrue(where.contains("t.creationDate <= :dateTo"));
        assertTrue(where.contains("coalesce(l.plu, pr.plu) >= :pluMin and coalesce(l.plu, pr.plu) <= :pluMax"));
        assertTrue(where.contains("l.familyCode >= :familyMin and l.familyCode <= :familyMax"));
        // Sold-article searches now exclude the cancelled witnesses (lot C4):
        // every line-scoped sold criterion carries the l.cancelled = false guard.
        assertTrue(where.contains("l.cancelled = false and coalesce(l.plu, pr.plu) >= :pluMin"));
        assertTrue(where.contains("l.cancelled = false and l.familyCode >= :familyMin"));
        assertTrue(where.contains("l.cancelled = false and l.vatRate = :vatRate"));
        assertTrue(where.contains("l.cancelled = false and lower(l.productLabel) like :text"));
        assertTrue(where.contains("l.cancelled = false and l.modifierType in :reductionTypes"));
        assertTrue(where.contains("l.cancelled = false and l.totalPrice = 0"));
        assertTrue(where.contains("l.cancelled = false and l.product is null and l.deposit = false"));
        assertTrue(where.contains("ol.id = rl.originalLineId and ol.cancelled = false"));
        // The cancelled-article criterion (BO-04-01-16): one exists over the
        // cancelled witness, both ranges bearing on the same line.
        assertTrue(where.contains("exists (select l from t.lines l left join l.product pr where l.cancelled = true"
                + " and coalesce(l.plu, pr.plu) >= :cancelPluMin and coalesce(l.plu, pr.plu) <= :cancelPluMax"
                + " and l.totalPrice >= :cancelAmountMin and l.totalPrice <= :cancelAmountMax)"));
        assertEquals("70", params.get("cancelPluMin"));
        assertEquals("80", params.get("cancelPluMax"));
        assertEquals(new BigDecimal("3.00"), params.get("cancelAmountMin"));
        assertEquals(new BigDecimal("30.00"), params.get("cancelAmountMax"));
        // The bare "annulation article" flag matches any ticket bearing one.
        assertTrue(where.contains("exists (select l from t.lines l where l.cancelled = true)"));
        assertEquals("FRUITS", params.get("familyMin"));
        assertEquals("LEGUMES", params.get("familyMax"));
        assertTrue(where.contains("l.vatRate = :vatRate"));
        // REMISE compared on the LINE effect (modifierValue * quantity), DISCOUNT on the raw %.
        assertTrue(where.contains("(case when l.modifierType = :remiseType then l.modifierValue * l.quantity else l.modifierValue end) >= :reductionMin"));
        assertTrue(where.contains("(case when l.modifierType = :remiseType then l.modifierValue * l.quantity else l.modifierValue end) <= :reductionMax"));
        assertTrue(where.contains("t.status = :cancelled"));
        assertTrue(where.contains("t.globalDiscountApplied is not null"));
        assertTrue(where.contains("from Refund r where r.originalTicketId = t.id"));
        assertTrue(where.contains("t.totalIncludingTax < 0"));
        assertTrue(where.contains("l.totalPrice = 0"));
        assertTrue(where.contains("l.product is null and l.deposit = false"));
        assertEquals(CardPayment.class, params.get("cardType"));
        assertTrue(params.containsKey("voucherType"));
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber is not null"));
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber >= :authMin"));
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber <= :authMax"));
        assertEquals("100000", params.get("authMin"));
        assertEquals("999999", params.get("authMax"));
        assertTrue(where.contains("treat(p as CardPayment).degradedMode = true"));
        assertTrue(where.contains("exists (select rl from Refund r join r.lines rl, TicketLine ol"
                + " left join ol.product opr"
                + " where r.originalTicketId = t.id and ol.id = rl.originalLineId"));
        assertTrue(where.contains("coalesce(ol.plu, opr.plu) >= :refundPluMin"));
        assertTrue(where.contains("coalesce(ol.plu, opr.plu) <= :refundPluMax"));
        assertTrue(where.contains("rl.price * rl.quantity >= :refundAmountMin"));
        assertTrue(where.contains("rl.price * rl.quantity <= :refundAmountMax"));
        assertEquals("40", params.get("refundPluMin"));
        assertEquals("60", params.get("refundPluMax"));
        assertEquals(new BigDecimal("2.00"), params.get("refundAmountMin"));
        assertEquals(new BigDecimal("20.00"), params.get("refundAmountMax"));
    }

    /**
     * A card authorization lower bound alone appends the {@code >=} half only,
     * guarded by {@code is not null}, without the {@code <=} half
     * (BO-04-01-08, first-bound arm).
     */
    @Test
    void authMinOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.authMin = "100000";
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber is not null"));
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber >= :authMin)"));
        assertFalse(where.contains("authMax"));
        assertEquals("100000", query.parameters().get("authMin"));
    }

    /**
     * A card authorization upper bound alone appends the {@code <=} half only
     * (BO-04-01-08, second-bound arm).
     */
    @Test
    void authMaxOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.authMax = "999999";
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber <= :authMax)"));
        assertFalse(where.contains("authMin"));
        assertEquals("999999", query.parameters().get("authMax"));
    }

    /**
     * No authorization bound adds no authorization clause (both-null early
     * return arm of the builder).
     */
    @Test
    void noAuthorizationBoundAddsNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalQuery query = service.buildTicketQuery(new JournalCriteria());
        assertFalse(query.whereClause().contains("authorizationNumber"));
        assertFalse(query.parameters().containsKey("authMin"));
        assertFalse(query.parameters().containsKey("authMax"));
    }

    /**
     * The DEGRADED flag alone adds the degraded-mode existence clause
     * (BO-04-01-47), and the DEGRADED_MANUAL flag is absent.
     */
    @Test
    void degradedFlagAddsDegradedClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.flags.add(JournalCriteria.Flag.DEGRADED);
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("treat(p as CardPayment).degradedMode = true"));
    }

    /**
     * The DEGRADED_MANUAL flag alone adds the same degraded-mode clause
     * (BO-04-01-49): the register's only degraded mode is manual, so both
     * criteria match the same rows.
     */
    @Test
    void degradedManualFlagAddsDegradedClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.flags.add(JournalCriteria.Flag.DEGRADED_MANUAL);
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("treat(p as CardPayment).degradedMode = true"));
    }

    /**
     * Neither degraded flag adds no degraded-mode clause (both absent arms).
     */
    @Test
    void noDegradedFlagAddsNoDegradedClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        String where = service.buildTicketQuery(new JournalCriteria()).whereClause();
        assertFalse(where.contains("degradedMode"));
    }

    /**
     * A PLU lower bound alone appends the {@code >=} half only (first-bound
     * arm, no {@code and}).
     */
    @Test
    void pluMinOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.pluMin = "40";
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("coalesce(l.plu, pr.plu) >= :pluMin)"));
        assertFalse(where.contains("pluMax"));
    }

    /**
     * A PLU upper bound alone appends the {@code <=} half only (the {@code and}
     * separator is skipped: {@code !first} false arm).
     */
    @Test
    void pluMaxOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.pluMax = "50";
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("coalesce(l.plu, pr.plu) <= :pluMax)"));
        assertFalse(where.contains("pluMin"));
    }

    /**
     * A family lower bound alone appends the {@code >=} half only (BO-04-01-11,
     * first-bound arm, no {@code and}).
     */
    @Test
    void familyMinOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.familyMin = "FRUITS";
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("l.familyCode >= :familyMin)"));
        assertFalse(where.contains("familyMax"));
        assertEquals("FRUITS", service.buildTicketQuery(criteria).parameters().get("familyMin"));
    }

    /**
     * A family upper bound alone appends the {@code <=} half only (the
     * {@code and} separator is skipped: {@code !first} false arm).
     */
    @Test
    void familyMaxOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.familyMax = "LEGUMES";
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("l.familyCode <= :familyMax)"));
        assertFalse(where.contains("familyMin"));
    }

    /**
     * No family bound adds no family clause (both-null early-return arm).
     */
    @Test
    void noFamilyBoundAddsNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        assertFalse(service.buildTicketQuery(new JournalCriteria()).whereClause().contains("familyCode"));
    }

    /**
     * A cancelled-article PLU lower bound and an amount upper bound alone append
     * their two halves only, over the cancelled-witness {@code exists}
     * (BO-04-01-16, cancelPluMin present / cancelPluMax absent / cancelAmountMin
     * absent / cancelAmountMax present).
     */
    @Test
    void cancelPluMinAndAmountMaxOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.cancelPluMin = "70";
        criteria.cancelAmountMax = new BigDecimal("30.00");
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("exists (select l from t.lines l left join l.product pr where l.cancelled = true"
                + " and coalesce(l.plu, pr.plu) >= :cancelPluMin and l.totalPrice <= :cancelAmountMax)"));
        assertFalse(where.contains("cancelPluMax"));
        assertFalse(where.contains("cancelAmountMin"));
        assertEquals("70", query.parameters().get("cancelPluMin"));
        assertEquals(new BigDecimal("30.00"), query.parameters().get("cancelAmountMax"));
    }

    /**
     * A cancelled-article PLU upper bound and an amount lower bound alone append
     * their two halves only (BO-04-01-16, cancelPluMin absent / cancelPluMax
     * present / cancelAmountMin present / cancelAmountMax absent).
     */
    @Test
    void cancelPluMaxAndAmountMinOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.cancelPluMax = "80";
        criteria.cancelAmountMin = new BigDecimal("3.00");
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("exists (select l from t.lines l left join l.product pr where l.cancelled = true"
                + " and coalesce(l.plu, pr.plu) <= :cancelPluMax and l.totalPrice >= :cancelAmountMin)"));
        assertFalse(where.contains("cancelPluMin"));
        assertFalse(where.contains("cancelAmountMax"));
        assertEquals("80", query.parameters().get("cancelPluMax"));
        assertEquals(new BigDecimal("3.00"), query.parameters().get("cancelAmountMin"));
    }

    /**
     * No cancelled-article bound adds no cancelled-witness clause (four-null
     * early-return arm), and with no CANCELLED_ARTICLE flag either, no
     * {@code l.cancelled = true} predicate appears at all.
     */
    @Test
    void noCancelledArticleBoundAddsNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalQuery query = service.buildTicketQuery(new JournalCriteria());
        assertFalse(query.whereClause().contains("cancelPluMin"));
        assertFalse(query.whereClause().contains("l.cancelled = true"));
    }

    /**
     * A cancelled-article amount lower bound alone still opens the
     * cancelled-witness {@code exists} (BO-04-01-16): the four-null early-return
     * falls through on its {@code cancelAmountMin == null} false arm, with no
     * PLU clause.
     */
    @Test
    void cancelAmountMinOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.cancelAmountMin = new BigDecimal("3.00");
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("l.cancelled = true and l.totalPrice >= :cancelAmountMin)"));
        assertFalse(where.contains("cancelPlu"));
        assertFalse(where.contains("cancelAmountMax"));
    }

    /**
     * A cancelled-article amount upper bound alone appends its half only
     * (BO-04-01-16, the {@code cancelAmountMax == null} false arm).
     */
    @Test
    void cancelAmountMaxOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.cancelAmountMax = new BigDecimal("30.00");
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("l.cancelled = true and l.totalPrice <= :cancelAmountMax)"));
        assertFalse(where.contains("cancelAmountMin"));
    }

    /**
     * The CANCELLED_ARTICLE flag alone lists every ticket bearing an article
     * cancellation (BO-04-01-16), with no range parameter bound.
     */
    @Test
    void cancelledArticleFlagMatchesAnyCancelledLine() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.flags.add(JournalCriteria.Flag.CANCELLED_ARTICLE);
        JournalQuery query = service.buildTicketQuery(criteria);
        assertTrue(query.whereClause().contains("exists (select l from t.lines l where l.cancelled = true)"));
        assertFalse(query.parameters().containsKey("cancelPluMin"));
    }

    /**
     * A refunded-article PLU lower bound and an amount upper bound alone append
     * their two halves only, over the refund-join {@code exists} (BO-04-01-23,
     * pluMin present / pluMax absent / amountMin absent / amountMax present).
     */
    @Test
    void refundPluMinAndAmountMaxOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.refundPluMin = "40";
        criteria.refundAmountMax = new BigDecimal("20.00");
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("r.originalTicketId = t.id and ol.id = rl.originalLineId"));
        assertTrue(where.contains("coalesce(ol.plu, opr.plu) >= :refundPluMin"));
        assertTrue(where.contains("rl.price * rl.quantity <= :refundAmountMax"));
        assertFalse(where.contains("refundPluMax"));
        assertFalse(where.contains("refundAmountMin"));
        assertEquals("40", query.parameters().get("refundPluMin"));
        assertEquals(new BigDecimal("20.00"), query.parameters().get("refundAmountMax"));
    }

    /**
     * A refunded-article PLU upper bound and an amount lower bound alone append
     * their two halves only (BO-04-01-23, pluMin absent / pluMax present /
     * amountMin present / amountMax absent).
     */
    @Test
    void refundPluMaxAndAmountMinOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.refundPluMax = "60";
        criteria.refundAmountMin = new BigDecimal("2.00");
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("coalesce(ol.plu, opr.plu) <= :refundPluMax"));
        assertTrue(where.contains("rl.price * rl.quantity >= :refundAmountMin"));
        assertFalse(where.contains("refundPluMin"));
        assertFalse(where.contains("refundAmountMax"));
        assertEquals("60", query.parameters().get("refundPluMax"));
        assertEquals(new BigDecimal("2.00"), query.parameters().get("refundAmountMin"));
    }

    /**
     * No refunded-article bound adds no refund clause (four-null early-return
     * arm of the builder).
     */
    @Test
    void noRefundArticleBoundAddsNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalQuery query = service.buildTicketQuery(new JournalCriteria());
        assertFalse(query.whereClause().contains("rl.originalLineId"));
        assertFalse(query.parameters().containsKey("refundPluMin"));
    }

    /**
     * A refunded-article amount lower bound alone still opens the refund-join
     * {@code exists} (BO-04-01-23): the early-return condition falls through on
     * its {@code refundAmountMin == null} false arm, with no PLU clause.
     */
    @Test
    void refundAmountMinOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.refundAmountMin = new BigDecimal("2.00");
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("rl.price * rl.quantity >= :refundAmountMin)"));
        assertFalse(where.contains("ol.plu"));
        assertFalse(where.contains("refundAmountMax"));
    }

    /**
     * A refunded-article amount upper bound alone opens the refund-join
     * {@code exists} (BO-04-01-23): the early-return condition falls through on
     * its {@code refundAmountMax == null} false arm.
     */
    @Test
    void refundAmountMaxOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.refundAmountMax = new BigDecimal("20.00");
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("rl.price * rl.quantity <= :refundAmountMax)"));
        assertFalse(where.contains("refundAmountMin"));
    }

    /**
     * A reduction lower bound alone appends only the {@code >=} half.
     */
    @Test
    void reductionMinOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.reductionMin = new BigDecimal("1.00");
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("(case when l.modifierType = :remiseType then l.modifierValue * l.quantity else l.modifierValue end) >= :reductionMin)"));
        assertFalse(where.contains("reductionMax"));
    }

    /**
     * A reduction upper bound alone appends only the {@code <=} half.
     */
    @Test
    void reductionMaxOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.reductionMax = new BigDecimal("5.00");
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("(case when l.modifierType = :remiseType then l.modifierValue * l.quantity else l.modifierValue end) <= :reductionMax)"));
        assertFalse(where.contains("reductionMin"));
    }

    /**
     * A method set that resolves to no known type adds no payment clause
     * (unknown key skipped, resulting type list empty).
     */
    @Test
    void unknownMethodAddsNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.methods.add("BITCOIN");
        JournalQuery query = service.buildTicketQuery(criteria);
        assertFalse(query.whereClause().contains("methodTypes"));
        assertFalse(query.parameters().containsKey("methodTypes"));
    }

    // --------------------------------------------------
    // buildEventQuery
    // --------------------------------------------------

    /**
     * An empty criteria produces no functional where clause (all absent arms).
     */
    @Test
    void emptyEventCriteriaProducesNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalQuery query = service.buildEventQuery(new JournalCriteria());
        assertEquals("", query.whereClause());
        assertTrue(query.parameters().isEmpty());
    }

    /**
     * A full functional criteria adds every clause (all present arms).
     */
    @Test
    void fullEventCriteriaAddsEveryClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.eventTypes.add("SESSION_CLOSED");
        criteria.cashierMin = "100";
        criteria.cashierMax = "200";
        criteria.terminalMin = "C01";
        criteria.terminalMax = "C09";
        criteria.dateFrom = LocalDateTime.of(2026, 8, 1, 0, 0);
        criteria.dateTo = LocalDateTime.of(2026, 8, 31, 0, 0);
        criteria.text = "Z";
        JournalQuery query = service.buildEventQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("e.eventType in :eventTypes"));
        assertEquals(List.of(TechnicalEvent.EventType.SESSION_CLOSED),
                query.parameters().get("eventTypes"));
        assertTrue(where.contains("e.operatorBadgeId >= :cashierMin"));
        assertTrue(where.contains("e.operatorBadgeId <= :cashierMax"));
        assertEquals("100", query.parameters().get("cashierMin"));
        assertEquals("200", query.parameters().get("cashierMax"));
        assertTrue(where.contains("e.terminalId >= :terminalMin"));
        assertTrue(where.contains("e.terminalId <= :terminalMax"));
        assertTrue(where.contains("e.eventDate >= :dateFrom"));
        assertTrue(where.contains("e.eventDate <= :dateTo"));
        assertTrue(where.contains("lower(e.detail) like :text"));
        assertEquals("%z%", query.parameters().get("text"));
    }

    /**
     * An event-type set that resolves to no known type adds no type clause
     * (unknown name skipped, resulting list empty).
     */
    @Test
    void unknownEventTypeAddsNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.eventTypes.add("NOPE");
        JournalQuery query = service.buildEventQuery(criteria);
        assertEquals("", query.whereClause());
        assertFalse(query.parameters().containsKey("eventTypes"));
    }

    // --------------------------------------------------
    // buildMovementQuery
    // --------------------------------------------------

    /**
     * An empty criteria produces no movements where clause (all absent arms).
     */
    @Test
    void emptyMovementCriteriaProducesNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalQuery query = service.buildMovementQuery(new JournalCriteria());
        assertEquals("", query.whereClause());
        assertTrue(query.parameters().isEmpty());
    }

    /**
     * A full movements criteria adds every clause (all present arms): the
     * selected types, the cashier range matched on the movement's own badge, the
     * terminal range and the date range.
     */
    @Test
    void fullMovementCriteriaAddsEveryClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.movementTypes.add(CashMovement.MovementType.WITHDRAWAL);
        criteria.movementTypes.add(CashMovement.MovementType.DECLARATION);
        criteria.cashierMin = "100";
        criteria.cashierMax = "200";
        criteria.terminalMin = "C01";
        criteria.terminalMax = "C09";
        criteria.dateFrom = LocalDateTime.of(2026, 8, 1, 0, 0);
        criteria.dateTo = LocalDateTime.of(2026, 8, 31, 0, 0);
        JournalQuery query = service.buildMovementQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("m.type in :movementTypes"));
        assertEquals(List.of(CashMovement.MovementType.WITHDRAWAL, CashMovement.MovementType.DECLARATION),
                query.parameters().get("movementTypes"));
        assertTrue(where.contains("m.cashier.badgeId >= :cashierMin"));
        assertTrue(where.contains("m.cashier.badgeId <= :cashierMax"));
        assertEquals("100", query.parameters().get("cashierMin"));
        assertEquals("200", query.parameters().get("cashierMax"));
        assertTrue(where.contains("m.terminalId >= :terminalMin"));
        assertTrue(where.contains("m.terminalId <= :terminalMax"));
        assertTrue(where.contains("m.movementDate >= :dateFrom"));
        assertTrue(where.contains("m.movementDate <= :dateTo"));
    }

    // --------------------------------------------------
    // search / searchEvents / searchMovements / export
    // --------------------------------------------------

    /**
     * {@code search} maps projected rows, formats the datetime and amount, and
     * turns a null amount into "0,00" (formatAmount null arm); the ascending
     * direction is applied (descending false arm), the first page is read from
     * offset 0 and the page carries the counted total.
     */
    @Test
    void searchMapsRowsAscending() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        Object[] normal = {5L, "C04", "C04-00000123", "12341234",
                LocalDateTime.of(2026, 8, 31, 14, 30), new BigDecimal("12.50"), 3, false};
        Object[] nullAmount = {6L, "C04", "C04-00000124", "12341234",
                LocalDateTime.of(2026, 8, 31, 15, 0), null, 0, true};
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.<Object[]>of(normal, nullAmount));
        stubCount(em, 2L);
        JournalService service = serviceWith(em);
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        JournalPage<JournalRow> page = service.search(new JournalCriteria());
        org.mockito.Mockito.verify(em, org.mockito.Mockito.times(2)).createQuery(jpql.capture(), eq(Object[].class));
        assertTrue(jpql.getAllValues().get(0).contains("order by t.creationDate asc, t.id asc"));
        org.mockito.Mockito.verify(query).setFirstResult(0);
        org.mockito.Mockito.verify(query).setMaxResults(JournalService.PAGE_SIZE);
        assertEquals(2, page.rows.size());
        assertEquals(2L, page.total);
        assertEquals(1, page.number);
        assertEquals(5L, page.rows.get(0).id);
        assertEquals("31/08/2026", page.rows.get(0).date);
        assertEquals("14:30", page.rows.get(0).time);
        assertEquals("12,50", page.rows.get(0).amount);
        assertEquals("N", page.rows.get(0).trainingMode);
        assertEquals("0,00", page.rows.get(1).amount);
        assertEquals("N", page.rows.get(0).autonomous);
        assertEquals("O", page.rows.get(1).autonomous);
        assertEquals("/admin/journal/ticket/5", page.rows.get(0).detailUrl);
    }

    /**
     * {@code search} counts the matching documents with a count query built on
     * the same where clause as the list.
     */
    @Test
    void searchCountsOverTheSameWhereClause() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 0L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.text = "lait";
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        service.search(criteria);
        org.mockito.Mockito.verify(em).createQuery(jpql.capture(), eq(Long.class));
        assertTrue(jpql.getValue().startsWith("select count(t.id) from Ticket t where "));
        assertTrue(jpql.getValue().contains("like :text"));
    }

    /**
     * {@code search} applies the requested column and descending direction
     * (descending true arm, non-default sort).
     */
    @Test
    void searchDescendingUsesRequestedSort() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 0L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.sort = "amount";
        criteria.descending = true;
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        assertTrue(service.search(criteria).rows.isEmpty());
        org.mockito.Mockito.verify(em).createQuery(jpql.capture(), eq(Object[].class));
        assertTrue(jpql.getValue().contains("order by t.totalIncludingTax desc, t.id asc"));
    }

    /**
     * {@code search} reads the requested page from its offset when that page
     * exists (clampPage min arm, requested below the page count).
     */
    @Test
    void searchReadsTheRequestedPage() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 250L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.page = 2;
        JournalPage<JournalRow> page = service.search(criteria);
        org.mockito.Mockito.verify(query).setFirstResult(JournalService.PAGE_SIZE);
        assertEquals(2, page.number);
        assertEquals(250L, page.total);
        assertEquals(3, page.getPageCount());
    }

    /**
     * {@code search} clamps a page beyond the last one onto the last page
     * (clampPage min arm, requested above the page count).
     */
    @Test
    void searchClampsPageBeyondTheLastOne() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 150L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.page = 9;
        JournalPage<JournalRow> page = service.search(criteria);
        org.mockito.Mockito.verify(query).setFirstResult(JournalService.PAGE_SIZE);
        assertEquals(2, page.number);
    }

    /**
     * {@code search} clamps a non-positive page onto the first one (clampPage
     * requested-below-one arm).
     */
    @Test
    void searchClampsNonPositivePage() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 150L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.page = 0;
        JournalPage<JournalRow> page = service.search(criteria);
        org.mockito.Mockito.verify(query).setFirstResult(0);
        assertEquals(1, page.number);
    }

    /**
     * {@code searchEvents} maps the technical events to formatted rows.
     */
    @Test
    void searchEventsMapsRows() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<TechnicalEvent> query = selfQuery();
        TechnicalEvent event = new TechnicalEvent();
        event.terminalId = "C04";
        event.eventType = TechnicalEvent.EventType.SESSION_CLOSED;
        event.eventDate = LocalDateTime.of(2026, 8, 31, 18, 0);
        event.detail = "Z report";
        event.operatorBadgeId = "12341234";
        when(em.createQuery(anyString(), eq(TechnicalEvent.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(event));
        stubCount(em, 1L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.eventTypes.add("SESSION_CLOSED");
        JournalPage<JournalEventRow> page = service.searchEvents(criteria);
        assertEquals(1, page.rows.size());
        assertEquals(1L, page.total);
        assertEquals(1, page.number);
        assertEquals("C04", page.rows.get(0).terminal);
        assertEquals("12341234", page.rows.get(0).cashier);
        assertEquals("SESSION_CLOSED", page.rows.get(0).type);
        assertEquals("31/08/2026", page.rows.get(0).date);
        assertEquals("18:00", page.rows.get(0).time);
        assertEquals("Z report", page.rows.get(0).detail);
        org.mockito.Mockito.verify(query).setFirstResult(0);
        org.mockito.Mockito.verify(query).setMaxResults(JournalService.PAGE_SIZE);
    }

    /**
     * {@code searchEvents} reads the requested page of events from its offset
     * and counts the whole result set.
     */
    @Test
    void searchEventsReadsTheRequestedPage() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<TechnicalEvent> query = selfQuery();
        when(em.createQuery(anyString(), eq(TechnicalEvent.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 320L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.page = 3;
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        JournalPage<JournalEventRow> page = service.searchEvents(criteria);
        org.mockito.Mockito.verify(em).createQuery(jpql.capture(), eq(Long.class));
        assertEquals("select count(e.id) from TechnicalEvent e", jpql.getValue());
        org.mockito.Mockito.verify(query).setFirstResult(2 * JournalService.PAGE_SIZE);
        assertEquals(3, page.number);
        assertEquals(320L, page.total);
    }

    /**
     * {@code searchMovements} maps the cash movements to formatted rows: a first
     * movement with a cashier, amount, reason and endorsement (all non-null
     * arms), and a second with a null cashier, null amount, null reason and null
     * endorsement (all null arms → empty strings, formatAmount "0,00"). Ordered
     * most-recent-first, first page read from offset 0 with the counted total.
     */
    @Test
    void searchMovementsMapsRows() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<CashMovement> query = selfQuery();
        CashMovement withCashier = new CashMovement();
        withCashier.terminalId = "C04";
        Employee cashier = new Employee();
        cashier.badgeId = "12341234";
        withCashier.cashier = cashier;
        withCashier.type = CashMovement.MovementType.WITHDRAWAL;
        withCashier.movementDate = LocalDateTime.of(2026, 8, 31, 18, 0);
        withCashier.amount = new BigDecimal("150.00");
        withCashier.reason = "Prelevement coffre";
        withCashier.endorsedBy = "11111111";
        CashMovement bare = new CashMovement();
        bare.terminalId = "C05";
        bare.cashier = null;
        bare.type = CashMovement.MovementType.DECLARATION;
        bare.movementDate = LocalDateTime.of(2026, 8, 31, 19, 30);
        bare.amount = null;
        bare.reason = null;
        bare.endorsedBy = null;
        when(em.createQuery(anyString(), eq(CashMovement.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(withCashier, bare));
        stubCount(em, 2L);
        JournalService service = serviceWith(em);
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        JournalPage<JournalMovementRow> page = service.searchMovements(new JournalCriteria());
        org.mockito.Mockito.verify(em).createQuery(jpql.capture(), eq(CashMovement.class));
        assertTrue(jpql.getValue().contains("order by m.movementDate desc, m.id asc"));
        assertEquals(2, page.rows.size());
        assertEquals(2L, page.total);
        assertEquals(1, page.number);
        assertEquals("C04", page.rows.get(0).terminal);
        assertEquals("12341234", page.rows.get(0).cashier);
        assertEquals("WITHDRAWAL", page.rows.get(0).type);
        assertEquals("31/08/2026", page.rows.get(0).date);
        assertEquals("18:00", page.rows.get(0).time);
        assertEquals("150,00", page.rows.get(0).amount);
        assertEquals("Prelevement coffre", page.rows.get(0).reason);
        assertEquals("11111111", page.rows.get(0).endorsedBy);
        assertEquals("", page.rows.get(1).cashier);
        assertEquals("DECLARATION", page.rows.get(1).type);
        assertEquals("0,00", page.rows.get(1).amount);
        assertEquals("", page.rows.get(1).reason);
        assertEquals("", page.rows.get(1).endorsedBy);
        org.mockito.Mockito.verify(query).setFirstResult(0);
        org.mockito.Mockito.verify(query).setMaxResults(JournalService.PAGE_SIZE);
    }

    /**
     * {@code searchMovements} reads the requested page of movements from its
     * offset and counts the whole result set over the same where clause.
     */
    @Test
    void searchMovementsReadsTheRequestedPage() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<CashMovement> query = selfQuery();
        when(em.createQuery(anyString(), eq(CashMovement.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 320L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.page = 3;
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        JournalPage<JournalMovementRow> page = service.searchMovements(criteria);
        org.mockito.Mockito.verify(em).createQuery(jpql.capture(), eq(Long.class));
        assertEquals("select count(m.id) from CashMovement m", jpql.getValue());
        org.mockito.Mockito.verify(query).setFirstResult(2 * JournalService.PAGE_SIZE);
        assertEquals(3, page.number);
        assertEquals(320L, page.total);
    }

    /**
     * {@code exportCsv} renders the header and one line per row, reproducing
     * the list columns. A partial first chunk ends the read (short-chunk arm),
     * and the export never goes through a count: it covers the whole set.
     */
    @Test
    void exportCsvReproducesTheList() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        Object[] row = {5L, "C04", "C04-00000123", "12341234",
                LocalDateTime.of(2026, 8, 31, 14, 30), new BigDecimal("12.50"), 3, false};
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.<Object[]>of(row));
        JournalService service = serviceWith(em);
        String csv = service.exportCsv(new JournalCriteria(), java.util.function.UnaryOperator.identity());
        assertEquals("\uFEFFTPV;Transaction;Caissière;Date;Heure;Montant;Articles;École;Autonome;TVA\n"
                + "C04;C04-00000123;12341234;31/08/2026;14:30;12,50;3;N;N;\n", csv);
        org.mockito.Mockito.verify(query).setMaxResults(JournalService.EXPORT_CHUNK);
        org.mockito.Mockito.verify(query).setFirstResult(0);
    }

    /**
     * {@code exportCsv} keeps reading while the chunks come back full, so an
     * extract larger than one window is complete rather than truncated
     * (full-chunk arm, then the exhausted arm).
     */
    @Test
    void exportCsvReadsEveryChunk() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        Object[] first = {5L, "C04", "C04-00000123", "12341234",
                LocalDateTime.of(2026, 8, 31, 14, 30), new BigDecimal("12.50"), 3, false};
        Object[] second = {6L, "C05", "C05-00000456", "12341234",
                LocalDateTime.of(2026, 8, 31, 15, 45), new BigDecimal("7.00"), 1, false};
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(
                List.<Object[]>of(first), List.<Object[]>of(second), List.<Object[]>of());
        JournalService service = serviceWith(em);
        String csv = service.exportCsv(new JournalCriteria(), 1, java.util.function.UnaryOperator.identity());
        assertEquals("\uFEFFTPV;Transaction;Caissière;Date;Heure;Montant;Articles;École;Autonome;TVA\n"
                + "C04;C04-00000123;12341234;31/08/2026;14:30;12,50;3;N;N;\n"
                + "C05;C05-00000456;12341234;31/08/2026;15:45;7,00;1;N;N;\n", csv);
        org.mockito.Mockito.verify(query).setFirstResult(0);
        org.mockito.Mockito.verify(query).setFirstResult(1);
        org.mockito.Mockito.verify(query).setFirstResult(2);
    }

    /**
     * {@code exportCsv} on a result set matching nothing renders the header
     * alone (empty first chunk arm).
     */
    @Test
    void exportCsvOnEmptyResultRendersHeaderOnly() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        JournalService service = serviceWith(em);
        assertEquals("\uFEFFTPV;Transaction;Caissière;Date;Heure;Montant;Articles;École;Autonome;TVA\n",
                service.exportCsv(new JournalCriteria(), java.util.function.UnaryOperator.identity()));
    }

    /**
     * {@code exportCsv} writes its headings in the page's language — here the
     * Dutch bundle — the values staying as the screen writes them.
     */
    @Test
    void exportCsvHeadingsFollowThePageLanguage() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        JournalService service = serviceWith(em);
        com.intermarche.edge.ui.admin.BackOfficeMessages dutch = com.intermarche.edge.ui.admin.BackOfficeMessages.in("nl");
        String csv = service.exportCsv(new JournalCriteria(), dutch::text);
        assertEquals("\uFEFFTPV;" + dutch.text("Transaction") + ";" + dutch.text("Caissière") + ";"
                + dutch.text("Date") + ";" + dutch.text("Heure") + ";" + dutch.text("Montant") + ";"
                + dutch.text("Articles") + ";" + dutch.text("École") + ";" + dutch.text("Autonome") + ";"
                + dutch.text("TVA") + "\n", csv);
        assertFalse(csv.contains("Caissière"));
        assertFalse(csv.contains("Heure"));
    }

    // --------------------------------------------------
    // buildDetail
    // --------------------------------------------------

    /**
     * {@code buildDetail} returns null when the id matches no row (find-null
     * arm).
     */
    @Test
    void buildDetailReturnsNullWhenAbsent() {
        EntityManager em = mock(EntityManager.class);
        when(em.find(Ticket.class, 99L)).thenReturn(null);
        JournalService service = serviceWith(em);
        assertNull(service.buildDetail(99L));
    }

    /**
     * {@code buildDetail} materializes a ticket with a cashier, store and
     * fidelity card (non-null arms), one formatted line and one payment.
     */
    @Test
    void buildDetailMaterializesFullTicket() {
        EntityManager em = mock(EntityManager.class);
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-00000001";
        ticket.terminalId = "C04";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.creationDate = LocalDateTime.of(2026, 8, 31, 10, 0);
        Employee cashier = new Employee();
        cashier.firstName = "Jean";
        cashier.lastName = "Dupont";
        cashier.badgeId = "12341234";
        ticket.cashier = cashier;
        Store store = new Store();
        store.name = "IM Lyon";
        ticket.store = store;
        ticket.fidelityCard = "CARTE1";
        ticket.totalIncludingTax = new BigDecimal("3.00");
        ticket.totalExcludingTax = new BigDecimal("2.85");
        ticket.totalVat = new BigDecimal("0.15");
        TicketLine line = new TicketLine();
        line.lineNumber = 1;
        line.productLabel = "Lait";
        line.ean = "3001";
        line.plu = "42";
        line.quantity = new BigDecimal("2.000");
        line.totalPrice = new BigDecimal("3.00");
        line.modifierLabel = "REMISE";
        ticket.lines.add(line);
        TicketLine witness = new TicketLine();
        witness.lineNumber = 2;
        witness.productLabel = "Annulé";
        witness.quantity = new BigDecimal("1.000");
        witness.totalPrice = new BigDecimal("2.00");
        witness.cancelled = true;
        ticket.lines.add(witness);
        ticket.payments.add(new CardPayment(new BigDecimal("3.00")));
        when(em.find(Ticket.class, 1L)).thenReturn(ticket);
        JournalService service = serviceWith(em);
        JournalTicketDetail detail = service.buildDetail(1L);
        assertEquals("C04-00000001", detail.number);
        assertEquals("CLOSED", detail.status);
        assertEquals("31/08/2026", detail.date);
        assertEquals("10:00", detail.time);
        assertEquals("Jean Dupont", detail.cashier);
        assertEquals("12341234", detail.cashierBadge);
        assertEquals("IM Lyon", detail.store);
        assertEquals("CARTE1", detail.fidelityCard);
        assertEquals("3,00", detail.totalIncludingTax);
        assertEquals("2,85", detail.totalExcludingTax);
        assertEquals("0,15", detail.totalVat);
        assertEquals(2, detail.lines.size());
        assertEquals("2", detail.lines.get(0).quantity);
        assertEquals("REMISE", detail.lines.get(0).modifier);
        assertFalse(detail.lines.get(0).cancelled);
        assertTrue(detail.lines.get(1).cancelled);
        assertEquals(1, detail.payments.size());
        assertEquals("CARD", detail.payments.get(0).method);
        assertEquals("3,00", detail.payments.get(0).amount);
        assertNull(detail.payments.get(0).guaranteeAuthorization);
        assertNull(detail.payments.get(0).guaranteeProvider);
        assertTrue(detail.fidelityGroups.isEmpty());
    }

    /**
     * BO-03-02-24: the detail of a ticket paid by a guaranteed cheque shows the
     * authorization number and the provider synced from the register; a cheque
     * no service guaranteed shows none.
     */
    @Test
    void buildDetailShowsTheChequeGuarantee() {
        EntityManager em = mock(EntityManager.class);
        Ticket ticket = ticketWithFidelity();
        com.intermarche.edge.domain.payment.ChequePayment guaranteed =
                new com.intermarche.edge.domain.payment.ChequePayment(new BigDecimal("2.00"));
        guaranteed.guaranteeAuthorization = "GAR000001";
        guaranteed.guaranteeProvider = "SIMULATEUR";
        ticket.payments.add(guaranteed);
        ticket.payments.add(new com.intermarche.edge.domain.payment.ChequePayment(new BigDecimal("1.00")));
        when(em.find(Ticket.class, 1L)).thenReturn(ticket);
        JournalTicketDetail detail = serviceWith(em).buildDetail(1L);
        assertEquals("CHEQUE", detail.payments.get(0).method);
        assertEquals("GAR000001", detail.payments.get(0).guaranteeAuthorization);
        assertEquals("SIMULATEUR", detail.payments.get(0).guaranteeProvider);
        assertNull(detail.payments.get(1).guaranteeAuthorization);
        assertNull(detail.payments.get(1).guaranteeProvider);
    }

    /**
     * Builds a minimal CLOSED ticket carrying the given loyalty lines, for the
     * journal grouping tests.
     *
     * @param lines the frozen loyalty lines
     * @return the ticket
     */
    private Ticket ticketWithFidelity(com.intermarche.edge.domain.sale.TicketFidelityLine... lines) {
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-00000009";
        ticket.terminalId = "C04";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.creationDate = LocalDateTime.of(2026, 8, 31, 10, 0);
        ticket.totalIncludingTax = new BigDecimal("3.00");
        ticket.totalExcludingTax = new BigDecimal("2.85");
        ticket.totalVat = new BigDecimal("0.15");
        for (com.intermarche.edge.domain.sale.TicketFidelityLine line : lines) {
            ticket.fidelityLines.add(line);
        }
        return ticket;
    }

    /**
     * A ticket predating the referential — its lines carrying no advantage type —
     * yields a single empty-header group holding the flat list, so the journal
     * shows it exactly as before the grouping existed.
     */
    @Test
    void buildDetailGroupsUntypedFidelityLinesFlat() {
        EntityManager em = mock(EntityManager.class);
        Ticket ticket = ticketWithFidelity(
                new com.intermarche.edge.domain.sale.TicketFidelityLine("R1", "Cagnotte socle", new BigDecimal("0.28")),
                new com.intermarche.edge.domain.sale.TicketFidelityLine("R2", "Bon plan", new BigDecimal("0.12")));
        when(em.find(Ticket.class, 1L)).thenReturn(ticket);
        JournalTicketDetail detail = serviceWith(em).buildDetail(1L);
        assertEquals(1, detail.fidelityGroups.size());
        assertEquals("", detail.fidelityGroups.get(0).header);
        assertEquals(2, detail.fidelityGroups.get(0).advantages.size());
        assertEquals("Cagnotte socle", detail.fidelityGroups.get(0).advantages.get(0).label);
        assertEquals("0,28", detail.fidelityGroups.get(0).advantages.get(0).amount);
    }

    /**
     * A ticket whose loyalty-line collection is null — a sale ingested from an
     * older register — yields an empty group list (the null arm of the guard).
     */
    @Test
    void buildDetailWithNullFidelityLinesYieldsNoGroups() {
        EntityManager em = mock(EntityManager.class);
        Ticket ticket = ticketWithFidelity();
        ticket.fidelityLines = null;
        when(em.find(Ticket.class, 1L)).thenReturn(ticket);
        JournalTicketDetail detail = serviceWith(em).buildDetail(1L);
        assertTrue(detail.fidelityGroups.isEmpty());
    }

    /**
     * A ticket whose advantage lines are ALL typed leaves no untyped tail (the
     * false arm of the untyped-tail guard): the groups are exactly the typed ones.
     */
    @Test
    void buildDetailGroupsTypedFidelityLinesWithoutAnUntypedTail() {
        EntityManager em = mock(EntityManager.class);
        Ticket ticket = ticketWithFidelity(
                new com.intermarche.edge.domain.sale.TicketFidelityLine("R1", "Avantage jour", new BigDecimal("0.30"),
                        "A", "Avantages", 1, null, null),
                new com.intermarche.edge.domain.sale.TicketFidelityLine("R2", "Bonus soir", new BigDecimal("0.20"),
                        "B", "Bonus", 2, null, null));
        when(em.find(Ticket.class, 1L)).thenReturn(ticket);
        JournalTicketDetail detail = serviceWith(em).buildDetail(1L);
        assertEquals(2, detail.fidelityGroups.size());
        assertEquals("Avantages", detail.fidelityGroups.get(0).header);
        assertEquals("Bonus", detail.fidelityGroups.get(1).header);
    }

    /**
     * A ticket with typed advantage lines groups them by type, orders the groups
     * by the received order (the null-order group last, header falling back to
     * the type code when its label is null), and appends the untyped lines flat
     * in a trailing empty-header group.
     */
    @Test
    void buildDetailGroupsTypedFidelityLinesAndOrders() {
        EntityManager em = mock(EntityManager.class);
        Ticket ticket = ticketWithFidelity(
                new com.intermarche.edge.domain.sale.TicketFidelityLine("R2", "Bonus soir", new BigDecimal("0.20"),
                        "B", "Bonus", 2, null, null),
                new com.intermarche.edge.domain.sale.TicketFidelityLine("R1", "Avantage jour", new BigDecimal("0.30"),
                        "A", "Avantages", 1, null, null),
                new com.intermarche.edge.domain.sale.TicketFidelityLine("R3", "Sans ordre", new BigDecimal("0.10"),
                        "Z", null, null, null, null),
                new com.intermarche.edge.domain.sale.TicketFidelityLine("R4", "Ligne libre", new BigDecimal("0.05")));
        when(em.find(Ticket.class, 1L)).thenReturn(ticket);
        JournalTicketDetail detail = serviceWith(em).buildDetail(1L);
        assertEquals(4, detail.fidelityGroups.size());
        assertEquals("Avantages", detail.fidelityGroups.get(0).header);
        assertEquals("Bonus", detail.fidelityGroups.get(1).header);
        assertEquals("Z", detail.fidelityGroups.get(2).header);
        assertEquals("", detail.fidelityGroups.get(3).header);
        assertEquals("Ligne libre", detail.fidelityGroups.get(3).advantages.get(0).label);
    }

    /**
     * {@code buildDetail} tolerates a null cashier, store and fidelity card
     * (null arms), and a line with a null quantity (formatQuantity null arm).
     */
    @Test
    void buildDetailToleratesNullAssociations() {
        EntityManager em = mock(EntityManager.class);
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-00000002";
        ticket.terminalId = "C04";
        ticket.status = Ticket.TicketStatus.CANCELLED;
        ticket.creationDate = LocalDateTime.of(2026, 8, 31, 11, 0);
        ticket.cashier = null;
        ticket.store = null;
        ticket.fidelityCard = null;
        ticket.totalIncludingTax = null;
        ticket.totalExcludingTax = null;
        ticket.totalVat = null;
        TicketLine line = new TicketLine();
        line.lineNumber = 1;
        line.productLabel = "Inconnu";
        line.ean = null;
        line.plu = null;
        line.quantity = null;
        line.totalPrice = new BigDecimal("0.00");
        line.modifierLabel = null;
        ticket.lines.add(line);
        when(em.find(Ticket.class, 2L)).thenReturn(ticket);
        JournalService service = serviceWith(em);
        JournalTicketDetail detail = service.buildDetail(2L);
        assertEquals("", detail.cashier);
        assertEquals("", detail.cashierBadge);
        assertEquals("", detail.store);
        assertEquals("", detail.fidelityCard);
        assertEquals("0,00", detail.totalIncludingTax);
        assertEquals("", detail.lines.get(0).quantity);
        assertEquals("", detail.lines.get(0).ean);
        assertTrue(detail.payments.isEmpty());
    }

    /**
     * The sold-article PLU search resolves the EFFECTIVE PLU (BO-04-01-10): a
     * LEFT JOIN to the product and a {@code coalesce(l.plu, pr.plu)} so an
     * article carrying a catalog PLU but sold outside weighing still matches.
     */
    @Test
    void pluRangeCoalescesLineAndCatalogPlu() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.pluMin = "40";
        criteria.pluMax = "50";
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("select l from t.lines l left join l.product pr"));
        assertTrue(where.contains("coalesce(l.plu, pr.plu) >= :pluMin"));
        assertTrue(where.contains("coalesce(l.plu, pr.plu) <= :pluMax"));
    }

    /**
     * The before-discount amount range (BO-04-01-05) bears on
     * {@code totalIncludingTax + globalDiscountApplied}, both bounds present.
     */
    @Test
    void grossAmountRangeBothBounds() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.grossMin = new BigDecimal("10.00");
        criteria.grossMax = new BigDecimal("90.00");
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("(t.totalIncludingTax + coalesce(t.globalDiscountApplied, 0)) >= :grossMin"));
        assertTrue(where.contains("(t.totalIncludingTax + coalesce(t.globalDiscountApplied, 0)) <= :grossMax"));
        assertEquals(new BigDecimal("10.00"), query.parameters().get("grossMin"));
        assertEquals(new BigDecimal("90.00"), query.parameters().get("grossMax"));
    }

    /**
     * The before-discount amount range is absent when neither bound is set
     * (both absent arms).
     */
    @Test
    void grossAmountRangeAbsent() {
        JournalService service = serviceWith(mock(EntityManager.class));
        String where = service.buildTicketQuery(new JournalCriteria()).whereClause();
        assertFalse(where.contains("globalDiscountApplied, 0"));
    }

    /**
     * The time-of-day range (BO-04-01-09) extracts the hour of the creation
     * date, both bounds present, binding the integer hours.
     */
    @Test
    void hourRangeBothBounds() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.hourFrom = 12;
        criteria.hourTo = 14;
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("extract(hour from t.creationDate) >= :hourFrom"));
        assertTrue(where.contains("extract(hour from t.creationDate) <= :hourTo"));
        assertEquals(12, query.parameters().get("hourFrom"));
        assertEquals(14, query.parameters().get("hourTo"));
    }

    /**
     * The time-of-day range is absent when neither bound is set (both absent arms).
     */
    @Test
    void hourRangeAbsent() {
        JournalService service = serviceWith(mock(EntityManager.class));
        String where = service.buildTicketQuery(new JournalCriteria()).whereClause();
        assertFalse(where.contains("extract(hour from t.creationDate)"));
    }

    /**
     * The family multi-select (BO-04-01-11) adds a line-scoped {@code in} on the
     * snapshotted family code, binding the selected codes, when non-empty; and
     * nothing when empty.
     */
    @Test
    void familiesMultiSelectBothArms() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.families.add("FRUITS");
        criteria.families.add("BOULANGERIE");
        JournalQuery query = service.buildTicketQuery(criteria);
        assertTrue(query.whereClause().contains(
                "l.cancelled = false and l.familyCode in :families"));
        assertEquals(List.of("FRUITS", "BOULANGERIE"), query.parameters().get("families"));
        assertFalse(service.buildTicketQuery(new JournalCriteria()).whereClause()
                .contains("l.familyCode in :families"));
    }

    /**
     * The manual-reduction search binds REMISE and DISCOUNT only (BO-04-01-26),
     * so a FORCE_PRICE override is excluded.
     */
    @Test
    void reductionRestrictedToRemiseAndDiscount() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.reductionMin = new BigDecimal("1.00");
        JournalQuery query = service.buildTicketQuery(criteria);
        assertEquals(List.of(com.intermarche.edge.ui.PriceModType.REMISE,
                        com.intermarche.edge.ui.PriceModType.DISCOUNT),
                query.parameters().get("reductionTypes"));
    }

    /**
     * The CARD "total monétique" flag (BO-04-01-46) also matches a backup-monetics
     * payment, binding the backup payment type alongside the card type.
     */
    @Test
    void cardFlagIncludesBackupPayment() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.flags.add(JournalCriteria.Flag.CARD);
        JournalQuery query = service.buildTicketQuery(criteria);
        assertTrue(query.whereClause().contains(
                "type(p) = :cardType or type(p) = :backupType"));
        assertEquals(com.intermarche.edge.domain.payment.BackupPayment.class,
                query.parameters().get("backupType"));
    }

    /**
     * The cash-movement amount range (BO-04-01-33) narrows on the movement
     * amount, both bounds present; and nothing when neither is set.
     */
    @Test
    void movementAmountRangeBothArms() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.movementAmountMin = new BigDecimal("50.00");
        criteria.movementAmountMax = new BigDecimal("500.00");
        JournalQuery query = service.buildMovementQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("m.amount >= :movementAmountMin"));
        assertTrue(where.contains("m.amount <= :movementAmountMax"));
        assertEquals(new BigDecimal("50.00"), query.parameters().get("movementAmountMin"));
        assertFalse(service.buildMovementQuery(new JournalCriteria()).whereClause()
                .contains("m.amount"));
    }

    /** LC-07-04: a cash settlement has no card entry mode but keeps its payment nature and amount in the journal. */
    @Test
    void cashPaymentHasNoCardEntryMode() {
        var ticket = new Ticket();
        ticket.ticketNumber = "T1";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.creationDate = java.time.LocalDateTime.of(2026, 10, 20, 12, 0);
        ticket.payments.add(new com.intermarche.edge.domain.payment.CashPayment(new BigDecimal("3.00"), new BigDecimal("5.00")));
        var em = mock(EntityManager.class);
        when(em.find(Ticket.class, 1L)).thenReturn(ticket);
        var detail = serviceWith(em).buildDetail(1L);
        assertEquals(1, detail.payments.size());
        assertEquals("CASH", detail.payments.getFirst().method);
        assertEquals("3,00", detail.payments.getFirst().amount);
        assertNull(detail.payments.getFirst().entryMode);
    }


    // --------------------------------------------------
    // Lot 21, group S
    // --------------------------------------------------

    /**
     * Builds the ticket where clause of a criteria.
     *
     * @param criteria the criteria
     * @return the query
     */
    private JournalQuery ticketQuery(JournalCriteria criteria) {
        return serviceWith(mock(EntityManager.class)).buildTicketQuery(criteria);
    }

    /**
     * Every ticket-sourced event adds its clause, with and without its amount
     * range (BO-04-01-14).
     */
    @Test
    void everyEventAddsItsClause() {
        for (JournalEvent event : JournalEvent.values()) {
            JournalCriteria bare = new JournalCriteria();
            bare.event = event;
            JournalQuery plain = ticketQuery(bare);
            JournalCriteria ranged = new JournalCriteria();
            ranged.event = event;
            ranged.eventAmountMin = BigDecimal.ONE;
            ranged.eventAmountMax = BigDecimal.TEN;
            JournalQuery bounded = ticketQuery(ranged);
            boolean ownSource = event == JournalEvent.TRAINING || event == JournalEvent.NO_SALE;
            assertEquals(ownSource, plain.whereClause().equals(" where t.status in :statuses"), event.name());
            boolean expectBounds = !ownSource && event.isAmountRange();
            assertEquals(expectBounds, bounded.whereClause().contains(">= :eventAmountMin"), event.name());
            assertEquals(expectBounds, bounded.parameters().containsKey("eventAmountMax"), event.name());
        }
        JournalCriteria cancelled = new JournalCriteria();
        cancelled.event = JournalEvent.CANCELLED;
        assertEquals(Ticket.TicketStatus.CANCELLED, ticketQuery(cancelled).parameters().get("eventCancelled"));
        JournalCriteria forced = new JournalCriteria();
        forced.event = JournalEvent.FORCED_PRICE;
        assertEquals(com.intermarche.edge.ui.PriceModType.FORCE_PRICE, ticketQuery(forced).parameters().get("eventForced"));
        JournalCriteria discount = new JournalCriteria();
        discount.event = JournalEvent.DISCOUNT;
        assertTrue(ticketQuery(discount).parameters().containsKey("eventReductions"));
        JournalCriteria voucher = new JournalCriteria();
        voucher.event = JournalEvent.STORE_VOUCHER;
        assertEquals(com.intermarche.edge.domain.payment.VoucherPayment.class,
                ticketQuery(voucher).parameters().get("eventPaymentType"));
        JournalCriteria coupon = new JournalCriteria();
        coupon.event = JournalEvent.VENDOR_COUPON;
        assertEquals(com.intermarche.edge.domain.payment.SogecPayment.class,
                ticketQuery(coupon).parameters().get("eventPaymentType"));
        JournalCriteria offline = new JournalCriteria();
        offline.event = JournalEvent.OFFLINE;
        offline.eventAmountMin = BigDecimal.ONE;
        assertEquals(" where t.status in :statuses and t.offline = true", ticketQuery(offline).whereClause());
    }

    /**
     * One bound of the event amount alone is applied alone.
     */
    @Test
    void eventAmountBoundsApplyAlone() {
        JournalCriteria low = new JournalCriteria();
        low.event = JournalEvent.FORCED_PRICE;
        low.eventAmountMin = BigDecimal.ONE;
        JournalQuery lowQuery = ticketQuery(low);
        assertTrue(lowQuery.whereClause().contains("l.totalPrice >= :eventAmountMin"));
        assertFalse(lowQuery.whereClause().contains(":eventAmountMax"));
        JournalCriteria high = new JournalCriteria();
        high.event = JournalEvent.FORCED_PRICE;
        high.eventAmountMax = BigDecimal.TEN;
        JournalQuery highQuery = ticketQuery(high);
        assertTrue(highQuery.whereClause().contains("l.totalPrice <= :eventAmountMax"));
        assertFalse(highQuery.parameters().containsKey("eventAmountMin"));
    }

    /**
     * The settlement cancellation is searched by flag, amount bounds or method,
     * each alone (BO-04-01-19).
     */
    @Test
    void paymentCancellationCriteria() {
        JournalCriteria flag = new JournalCriteria();
        flag.flags.add(JournalCriteria.Flag.PAYMENT_CANCELLED);
        assertTrue(ticketQuery(flag).whereClause().contains("e.eventType = :paymentCancelledType"));
        JournalCriteria low = new JournalCriteria();
        low.paymentCancelMin = BigDecimal.ONE;
        assertTrue(ticketQuery(low).whereClause().contains("e.amount >= :paymentCancelMin"));
        JournalCriteria high = new JournalCriteria();
        high.paymentCancelMax = BigDecimal.TEN;
        assertTrue(ticketQuery(high).whereClause().contains("e.amount <= :paymentCancelMax"));
        JournalCriteria method = new JournalCriteria();
        method.paymentCancelMethod = "CHEQUE";
        JournalQuery query = ticketQuery(method);
        assertTrue(query.whereClause().contains("e.code = :paymentCancelMethod"));
        assertEquals(TechnicalEvent.EventType.PAYMENT_CANCELLED, query.parameters().get("paymentCancelledType"));
        assertFalse(ticketQuery(new JournalCriteria()).whereClause().contains("paymentCancelledType"));
    }

    /**
     * The unknown codes are searched by range bounds, each alone (BO-04-01-34);
     * the flag also finds a code scanned that rang nothing.
     */
    @Test
    void unknownCodeCriteria() {
        JournalCriteria low = new JournalCriteria();
        low.unknownCodeMin = "200";
        assertTrue(ticketQuery(low).whereClause().contains("u.code >= :unknownCodeMin"));
        JournalCriteria high = new JournalCriteria();
        high.unknownCodeMax = "300";
        JournalQuery query = ticketQuery(high);
        assertTrue(query.whereClause().contains("u.code <= :unknownCodeMax"));
        assertEquals(TechnicalEvent.EventType.UNKNOWN_ARTICLE_SCANNED, query.parameters().get("unknownRangeType"));
        JournalCriteria flag = new JournalCriteria();
        flag.flags.add(JournalCriteria.Flag.UNKNOWN_ITEM);
        assertEquals(TechnicalEvent.EventType.UNKNOWN_ARTICLE_SCANNED, ticketQuery(flag).parameters().get("unknownType"));
    }

    /**
     * The self-scanning flags and the secondary monetics server (BO-04-01-41/42/43/48).
     */
    @Test
    void selfScanningAndSecondaryServerFlags() {
        JournalCriteria criteria = new JournalCriteria();
        criteria.flags.add(JournalCriteria.Flag.SELF_SCAN);
        criteria.flags.add(JournalCriteria.Flag.SELF_SCAN_CONTROLLED);
        criteria.flags.add(JournalCriteria.Flag.SELF_SCAN_VERIFIED);
        criteria.flags.add(JournalCriteria.Flag.SECONDARY_SERVER);
        JournalService service = serviceWith(mock(EntityManager.class));
        when(service.posSettingsService.secondaryMoneticServers()).thenReturn(List.of("2"));
        JournalQuery query = service.buildTicketQuery(criteria);
        assertTrue(query.whereClause().contains("t.selfScanning = true"));
        assertTrue(query.whereClause().contains("t.selfScanControlled = true"));
        assertTrue(query.whereClause().contains("t.selfScanVerified = true"));
        assertTrue(query.whereClause().contains("p.moneticServer in :secondaryServers"));
        assertEquals(List.of("2"), query.parameters().get("secondaryServers"));
        JournalService none = serviceWith(mock(EntityManager.class));
        when(none.posSettingsService.secondaryMoneticServers()).thenReturn(List.of());
        JournalCriteria secondary = new JournalCriteria();
        secondary.flags.add(JournalCriteria.Flag.SECONDARY_SERVER);
        assertTrue(none.buildTicketQuery(secondary).whereClause().endsWith("and 1 = 0"));
    }

    /**
     * The VAT ventilation of the listed tickets, several rates joined
     * (BO-04-01-55).
     */
    @Test
    void searchWritesTheVatOfEachTicket() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        Object[] row = {5L, "C04", "T5", null, LocalDateTime.of(2026, 8, 31, 14, 30), new BigDecimal("12.50"), 3, true};
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.<Object[]>of(row));
        stubCount(em, 1L);
        JournalService service = serviceWith(em);
        TypedQuery<Object[]> vat = selfQuery();
        when(em.createQuery(org.mockito.ArgumentMatchers.startsWith("select t.id, l.vatRate"), eq(Object[].class)))
                .thenReturn(vat);
        when(vat.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{5L, new BigDecimal("0.0550"), new BigDecimal("5.00")},
                new Object[]{5L, new BigDecimal("0.2000"), new BigDecimal("6.00")}));
        JournalRow mapped = service.search(new JournalCriteria()).rows.get(0);
        assertEquals("5,5 % HT 4,74 TVA 0,26 | 20 % HT 5,00 TVA 1,00", mapped.vat);
        assertEquals("O", mapped.autonomous);
        assertEquals("", mapped.cashier);
    }

    /**
     * The rate keyed in the form is a percent, the lines carry a fraction; a
     * keyed fraction is kept (BO-04-01-55).
     */
    @Test
    void vatFractionReadsTheKeyedPercent() {
        assertEquals(new BigDecimal("0.20"), JournalService.vatFraction(new BigDecimal("20")));
        assertEquals(new BigDecimal("0.055"), JournalService.vatFraction(new BigDecimal("5.5")));
        assertEquals(new BigDecimal("0.2"), JournalService.vatFraction(new BigDecimal("0.2")));
        assertEquals(BigDecimal.ONE, JournalService.vatFraction(BigDecimal.ONE));
    }

    /**
     * A rate or an amount the line does not carry reads as zero.
     */
    @Test
    void vatRowReadsNullsAsZero() {
        JournalService.VatRow row = JournalService.vatRow(null, null);
        assertEquals("0", row.rate());
        assertEquals("0,00", row.base());
        assertEquals("0,00", row.tax());
        assertEquals("0,00", row.total());
    }

    /**
     * The training sales are listed from their own table, « Mode école » = O,
     * by register and period (BO-04-01-14/50); they export the same way.
     */
    @Test
    void trainingSalesAreListedAndExported() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<com.intermarche.edge.domain.sale.TrainingTicket> query = selfQuery();
        com.intermarche.edge.domain.sale.TrainingTicket dated = new com.intermarche.edge.domain.sale.TrainingTicket();
        dated.id = 3L;
        dated.terminalId = "C04";
        dated.cashierLogin = "jdupont";
        dated.issuedAt = LocalDateTime.of(2026, 10, 25, 9, 15);
        dated.itemCount = 2;
        dated.totalIncludingTax = new BigDecimal("4");
        com.intermarche.edge.domain.sale.TrainingTicket undated = new com.intermarche.edge.domain.sale.TrainingTicket();
        undated.id = 4L;
        com.intermarche.edge.domain.sale.TrainingTicket badgeless = new com.intermarche.edge.domain.sale.TrainingTicket();
        badgeless.id = 5L;
        badgeless.cashierLogin = "stagiaire";
        com.intermarche.edge.domain.sale.TrainingTicket unknown = new com.intermarche.edge.domain.sale.TrainingTicket();
        unknown.id = 6L;
        unknown.cashierLogin = "parti";
        when(em.createQuery(anyString(), eq(com.intermarche.edge.domain.sale.TrainingTicket.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(dated, undated, badgeless, unknown));
        stubCount(em, 4L);
        JournalService service = serviceWith(em);
        TypedQuery<Object[]> holders = selfQuery();
        when(holders.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{"jdupont", "12341234"}, new Object[]{"stagiaire", null}));
        when(em.createQuery(org.mockito.ArgumentMatchers.startsWith("select e.loginName, e.badgeId"), eq(Object[].class)))
                .thenReturn(holders);
        JournalCriteria criteria = new JournalCriteria();
        criteria.event = JournalEvent.TRAINING;
        criteria.terminalMin = "C01";
        criteria.terminalMax = "C09";
        criteria.dateFrom = LocalDateTime.of(2026, 10, 1, 0, 0);
        criteria.dateTo = LocalDateTime.of(2026, 10, 31, 0, 0);
        criteria.cashierMin = "1";
        criteria.cashierMax = "2";
        criteria.amountMin = BigDecimal.ONE;
        criteria.amountMax = BigDecimal.TEN;
        JournalPage<JournalRow> page = service.search(criteria);
        assertEquals("12341234", page.rows.get(0).cashier);
        assertEquals("stagiaire", page.rows.get(2).cashier);
        assertEquals("parti", page.rows.get(3).cashier);
        assertEquals("O", page.rows.get(0).trainingMode);
        assertEquals("25/10/2026", page.rows.get(0).date);
        assertEquals("09:15", page.rows.get(0).time);
        assertEquals("4,00", page.rows.get(0).amount);
        assertEquals("/admin/journal/training/3", page.rows.get(0).detailUrl);
        assertEquals("", page.rows.get(1).date);
        assertEquals("", page.rows.get(1).time);
        assertEquals("", page.rows.get(1).cashier);
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(em).createQuery(jpql.capture(), eq(Long.class));
        assertTrue(jpql.getValue().contains("x.terminalId >= :terminalMin and x.terminalId <= :terminalMax"
                + " and x.issuedAt >= :dateFrom and x.issuedAt <= :dateTo"
                + " and x.cashierLogin in (select e.loginName from Employee e where e.badgeId >= :cashierMin)"
                + " and x.cashierLogin in (select e.loginName from Employee e where e.badgeId <= :cashierMax)"
                + " and x.totalIncludingTax >= :amountMin and x.totalIncludingTax <= :amountMax"));
        when(query.getResultList()).thenReturn(List.of(dated), List.of());
        assertTrue(service.exportCsv(criteria, 1, java.util.function.UnaryOperator.identity()).contains("C04;;12341234;25/10/2026;09:15;4,00;2;O;N;\n"));
        JournalCriteria unbounded = new JournalCriteria();
        unbounded.event = JournalEvent.TRAINING;
        when(query.getResultList()).thenReturn(List.of());
        assertTrue(service.search(unbounded).rows.isEmpty());
    }

    /**
     * The drawers opened outside a sale are listed from the journal by their
     * range criteria (BO-04-01-14 « pas de vente »); they export the same way.
     */
    @Test
    void drawersOpenedOutsideASaleAreListedAndExported() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<TechnicalEvent> query = selfQuery();
        TechnicalEvent opened = new TechnicalEvent();
        opened.id = 9L;
        opened.terminalId = "C04";
        opened.operatorBadgeId = "1";
        opened.eventType = TechnicalEvent.EventType.DRAWER_FORCED_OPEN;
        opened.eventDate = LocalDateTime.of(2026, 10, 25, 11, 0);
        TechnicalEvent numbered = new TechnicalEvent();
        numbered.id = 10L;
        numbered.terminalId = "C04";
        numbered.eventType = TechnicalEvent.EventType.DRAWER_FORCED_OPEN;
        numbered.eventDate = LocalDateTime.of(2026, 10, 25, 11, 5);
        numbered.ticketNumber = "T9";
        numbered.amount = new BigDecimal("2");
        when(em.createQuery(anyString(), eq(TechnicalEvent.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(opened, numbered));
        stubCount(em, 2L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.event = JournalEvent.NO_SALE;
        criteria.cashierMin = "1";
        criteria.eventAmountMin = BigDecimal.ONE;
        JournalPage<JournalRow> page = service.search(criteria);
        assertEquals("", page.rows.get(0).transaction);
        assertEquals("0,00", page.rows.get(0).amount);
        assertEquals("T9", page.rows.get(1).transaction);
        assertEquals("1", page.rows.get(0).cashier);
        assertEquals("", page.rows.get(1).cashier);
        assertEquals("2,00", page.rows.get(1).amount);
        assertNull(page.rows.get(0).detailUrl);
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(em).createQuery(jpql.capture(), eq(Long.class));
        assertTrue(jpql.getValue().contains("e.eventType in :eventTypes"));
        assertTrue(jpql.getValue().contains("e.amount >= :eventAmountMin"));
        when(query.getResultList()).thenReturn(List.of(numbered), List.of());
        assertTrue(service.exportCsv(criteria, 1, java.util.function.UnaryOperator.identity()).contains("C04;T9;;25/10/2026;11:05;2,00;0;N;N;\n"));
    }

    /**
     * The functional journal searches the amount and the code events bear,
     * and shows them (BO-04-01-19/34).
     */
    @Test
    void functionalJournalSearchesAmountsAndCodes() {
        JournalCriteria criteria = new JournalCriteria();
        criteria.eventAmountMin = BigDecimal.ONE;
        criteria.eventAmountMax = BigDecimal.TEN;
        criteria.eventCode = "CHEQUE";
        JournalQuery query = serviceWith(mock(EntityManager.class)).buildEventQuery(criteria);
        assertEquals(" where e.amount >= :eventAmountMin and e.amount <= :eventAmountMax and e.code = :eventCode",
                query.whereClause());
        EntityManager em = mock(EntityManager.class);
        TypedQuery<TechnicalEvent> typed = selfQuery();
        TechnicalEvent cancelled = new TechnicalEvent();
        cancelled.terminalId = "C04";
        cancelled.eventType = TechnicalEvent.EventType.PAYMENT_CANCELLED;
        cancelled.eventDate = LocalDateTime.of(2026, 10, 25, 11, 0);
        cancelled.code = "CHEQUE";
        cancelled.amount = new BigDecimal("15");
        cancelled.ticketNumber = "T1";
        TechnicalEvent bare = new TechnicalEvent();
        bare.terminalId = "C04";
        bare.eventType = TechnicalEvent.EventType.SESSION_OPENED;
        bare.eventDate = LocalDateTime.of(2026, 10, 25, 8, 0);
        when(em.createQuery(anyString(), eq(TechnicalEvent.class))).thenReturn(typed);
        when(typed.getResultList()).thenReturn(List.of(cancelled, bare));
        stubCount(em, 2L);
        JournalPage<JournalEventRow> page = serviceWith(em).searchEvents(new JournalCriteria());
        assertEquals("CHEQUE", page.rows.get(0).code);
        assertEquals("15,00", page.rows.get(0).amount);
        assertEquals("T1", page.rows.get(0).ticketNumber);
        assertEquals("", page.rows.get(1).code);
        assertEquals("", page.rows.get(1).amount);
        assertEquals("", page.rows.get(1).ticketNumber);
    }

    /**
     * The detail states the VAT by rate, the self-scanning, the offline sale,
     * the unknown codes, the cancelled settlements and the documents issued
     * (BO-04-01-14/19/34/41/42/43/50/53/55).
     */
    @Test
    void buildDetailStatesTheLot21Facts() {
        EntityManager em = mock(EntityManager.class);
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "T7";
        ticket.terminalId = "C04";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.creationDate = LocalDateTime.of(2026, 10, 25, 10, 0);
        ticket.selfScanning = true;
        ticket.selfScanControlled = true;
        ticket.selfScanVerified = true;
        ticket.offline = true;
        TicketLine reduced = new TicketLine();
        reduced.productLabel = "Lait";
        reduced.quantity = BigDecimal.ONE;
        reduced.totalPrice = new BigDecimal("1.055");
        reduced.vatRate = new BigDecimal("0.0550");
        TicketLine untaxed = new TicketLine();
        untaxed.productLabel = "Timbre";
        untaxed.quantity = BigDecimal.ONE;
        untaxed.totalPrice = new BigDecimal("1.00");
        TicketLine priceless = new TicketLine();
        priceless.productLabel = "Rien";
        priceless.quantity = BigDecimal.ONE;
        TicketLine cancelled = new TicketLine();
        cancelled.productLabel = "Annulé";
        cancelled.quantity = BigDecimal.ONE;
        cancelled.totalPrice = new BigDecimal("9");
        cancelled.cancelled = true;
        ticket.lines.addAll(List.of(reduced, untaxed, priceless, cancelled));
        when(em.find(Ticket.class, 7L)).thenReturn(ticket);
        JournalService service = serviceWith(em);
        TechnicalEvent unknown = new TechnicalEvent();
        unknown.eventType = TechnicalEvent.EventType.UNKNOWN_ARTICLE_SCANNED;
        unknown.code = "2000000000001";
        TechnicalEvent payment = new TechnicalEvent();
        payment.eventType = TechnicalEvent.EventType.PAYMENT_CANCELLED;
        payment.code = "CHEQUE";
        payment.amount = new BigDecimal("15");
        TypedQuery<TechnicalEvent> events = selfQuery();
        when(events.getResultList()).thenReturn(List.of(unknown, payment));
        when(em.createQuery(org.mockito.ArgumentMatchers.startsWith("select e from TechnicalEvent e where e.ticketNumber"),
                eq(TechnicalEvent.class))).thenReturn(events);
        com.intermarche.edge.domain.sale.Invoice invoice = new com.intermarche.edge.domain.sale.Invoice();
        invoice.id = 3L;
        invoice.documentNumber = "F-1";
        TypedQuery<com.intermarche.edge.domain.sale.Invoice> documents = selfQuery();
        when(documents.getResultList()).thenReturn(List.of(invoice));
        when(em.createQuery(org.mockito.ArgumentMatchers.startsWith("select d from Invoice d"),
                eq(com.intermarche.edge.domain.sale.Invoice.class))).thenReturn(documents);
        JournalTicketDetail detail = service.buildDetail(7L);
        assertEquals(2, detail.vatRows.size());
        assertEquals("0", detail.vatRows.get(0).rate());
        assertEquals("5,5", detail.vatRows.get(1).rate());
        assertEquals("1,00", detail.vatRows.get(1).base());
        assertEquals("0,06", detail.vatRows.get(1).tax());
        assertEquals("O", detail.autonomous);
        assertEquals("N", detail.trainingMode);
        assertTrue(detail.selfScanControlled);
        assertTrue(detail.selfScanVerified);
        assertTrue(detail.offline);
        assertEquals(List.of("2000000000001"), detail.unknownCodes);
        assertEquals(List.of("CHEQUE 15,00"), detail.cancelledPayments);
        assertEquals("F-1", detail.documents.get(0).number);
        assertEquals("FACTURE", detail.documents.get(0).title);
        assertEquals(3L, detail.documents.get(0).id);
        ticket.selfScanning = false;
        assertEquals("N", service.buildDetail(7L).autonomous);
    }

    /**
     * An invoice reprinted as a duplicate counts one more print and is
     * journalled by the reprint rule the reports share, and restates its sale
     * (BO-04-01-53, BO-06-04-03).
     */
    @Test
    void duplicateRestatesTheDocument() {
        EntityManager em = mock(EntityManager.class);
        JournalService service = serviceWith(em);
        service.invoiceDuplicateService = mock(com.intermarche.edge.ui.report.InvoiceDuplicateService.class);
        org.mockito.Mockito.doAnswer(call -> {
            com.intermarche.edge.domain.sale.Invoice reprinted = call.getArgument(0);
            reprinted.printCount++;
            return reprinted.printCount - 1;
        }).when(service.invoiceDuplicateService).countReprint(any());
        assertNull(service.duplicate(1L));
        com.intermarche.edge.domain.sale.Invoice invoice = new com.intermarche.edge.domain.sale.Invoice();
        invoice.documentNumber = "F-1";
        invoice.documentType = com.intermarche.edge.domain.sale.DocumentType.BON_LIVRAISON;
        invoice.terminalId = "C04";
        invoice.ticketNumber = "T7";
        invoice.issueDate = LocalDateTime.of(2026, 10, 25, 10, 0);
        invoice.printCount = 1;
        invoice.customerName = "ACME";
        invoice.customerAddress.streetLine1 = "1 rue";
        invoice.customerAddress.postalCode = " ";
        invoice.customerAddress.city = "Lyon";
        invoice.totalIncludingTax = new BigDecimal("12");
        Ticket ticket = new Ticket();
        TicketLine sold = new TicketLine();
        sold.productLabel = "Lait";
        sold.quantity = new BigDecimal("2.000");
        sold.totalPrice = new BigDecimal("12");
        sold.vatRate = new BigDecimal("0.2000");
        TicketLine bare = new TicketLine();
        bare.productLabel = "Sac";
        TicketLine cancelled = new TicketLine();
        cancelled.productLabel = "Annulé";
        cancelled.cancelled = true;
        ticket.lines.addAll(List.of(sold, bare, cancelled));
        invoice.ticket = ticket;
        when(em.find(com.intermarche.edge.domain.sale.Invoice.class, 3L)).thenReturn(invoice);
        JournalService.Duplicate duplicate = service.duplicate(3L);
        assertEquals(2, duplicate.printCount());
        assertEquals("BON DE LIVRAISON", duplicate.title());
        assertEquals("25/10/2026 10:00", duplicate.issuedAt());
        assertEquals("1 rue Lyon", duplicate.customerAddress());
        assertEquals(2, duplicate.lines().size());
        assertEquals("2", duplicate.lines().get(0).quantity());
        assertEquals("", duplicate.lines().get(1).quantity());
        assertEquals("0,00", duplicate.lines().get(1).total());
        assertEquals(2, duplicate.vatRows().size());
        assertEquals("20", duplicate.vatRows().get(1).rate());
        assertEquals("10,00", duplicate.vatRows().get(1).base());
        assertEquals("2,00", duplicate.vatRows().get(1).tax());
        assertEquals("12,00", duplicate.totalIncludingTax());
        org.mockito.Mockito.verify(service.invoiceDuplicateService).countReprint(invoice);
        com.intermarche.edge.domain.sale.Invoice orphan = new com.intermarche.edge.domain.sale.Invoice();
        orphan.documentNumber = "F-2";
        orphan.customerAddress = null;
        invoice.customerAddress.streetLine1 = null;
        assertEquals("Lyon", service.duplicate(3L).customerAddress());
        when(em.find(com.intermarche.edge.domain.sale.Invoice.class, 4L)).thenReturn(orphan);
        JournalService.Duplicate lone = service.duplicate(4L);
        assertEquals("", lone.issuedAt());
        assertEquals("", lone.customerAddress());
        assertTrue(lone.lines().isEmpty());
        assertEquals("FACTURE", lone.title());
    }
}
