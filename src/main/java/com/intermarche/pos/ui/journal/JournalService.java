package com.intermarche.pos.ui.journal;

import com.intermarche.pos.domain.payment.PaymentTypes;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.payment.CardPayment;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.payment.TicketPayment;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.payment.VoucherPayment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Read side of the electronic journal (BO-04-01, lot 4). It produces nothing:
 * it opens the consolidated data the transactional outbox already remitted —
 * tickets, lines, payments, refunds and {@link TechnicalEvent} — to a
 * multi-criteria search, a ticket detail, a functional-event search and a CSV
 * export that reproduces the list to the character (BO-04-01-50).
 * <p>
 * Runs over this node's own database, so it becomes the STORE journal when
 * opened on the consolidated node — the same node-local posture as the
 * supervision dashboard. Aggregation-free reads go through JPQL on the
 * {@link EntityManager} (the same choice the dashboard made): the query is
 * assembled from a {@link JournalCriteria} by pure builders — every criterion
 * is one AND-ed condition, absent criteria contribute nothing (BO-04-01-13,
 * the free combination) — and executed here one page at a time, each search
 * preceded by its own {@code count}, so a free search over a year of tickets
 * neither exhausts memory nor drops rows in silence: the screen always states
 * how many documents matched and which slice of them it shows.
 */
@ApplicationScoped
public class JournalService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(JournalService.class);

    /** The number of rows one page of a result list shows. */
    static final int PAGE_SIZE = 100;

    /** The number of rows one export query reads at a time. */
    static final int EXPORT_CHUNK = 1000;

    /** French date format for the list and export. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** French time format for the list and export. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    @Inject
    EntityManager entityManager;

    // --------------------------------------------------
    // Transactional journal
    // --------------------------------------------------

    /**
     * Builds the {@code where} clause of the transactional search from the
     * criteria. The document scope is fixed to finalized documents (CLOSED and
     * CANCELLED): drafts are never consolidated. Every other criterion is
     * optional and simply narrows the set.
     *
     * @param criteria the parsed search criteria
     * @return the assembled query fragment and its parameters
     */
    JournalQuery buildTicketQuery(JournalCriteria criteria) {
        JournalQuery query = new JournalQuery();
        query.and("t.status in :statuses");
        query.bind("statuses", List.of(Ticket.TicketStatus.CLOSED, Ticket.TicketStatus.CANCELLED));
        if (criteria.text != null) {
            query.and("(lower(t.ticketNumber) like :text or lower(t.fidelityCard) like :text"
                    + " or exists (select l from t.lines l where l.cancelled = false"
                    + " and lower(l.productLabel) like :text))");
            query.bind("text", "%" + criteria.text.toLowerCase() + "%");
        }
        if (criteria.cashierMin != null) {
            query.and("t.cashier.badgeId >= :cashierMin");
            query.bind("cashierMin", criteria.cashierMin);
        }
        if (criteria.cashierMax != null) {
            query.and("t.cashier.badgeId <= :cashierMax");
            query.bind("cashierMax", criteria.cashierMax);
        }
        if (criteria.terminalMin != null) {
            query.and("t.terminalId >= :terminalMin");
            query.bind("terminalMin", criteria.terminalMin);
        }
        if (criteria.terminalMax != null) {
            query.and("t.terminalId <= :terminalMax");
            query.bind("terminalMax", criteria.terminalMax);
        }
        if (criteria.txMin != null) {
            query.and("t.ticketNumber >= :txMin");
            query.bind("txMin", criteria.txMin);
        }
        if (criteria.txMax != null) {
            query.and("t.ticketNumber <= :txMax");
            query.bind("txMax", criteria.txMax);
        }
        if (criteria.amountMin != null) {
            query.and("t.totalIncludingTax >= :amountMin");
            query.bind("amountMin", criteria.amountMin);
        }
        if (criteria.amountMax != null) {
            query.and("t.totalIncludingTax <= :amountMax");
            query.bind("amountMax", criteria.amountMax);
        }
        if (criteria.grossMin != null) {
            query.and("(t.totalIncludingTax + coalesce(t.globalDiscountApplied, 0)) >= :grossMin");
            query.bind("grossMin", criteria.grossMin);
        }
        if (criteria.grossMax != null) {
            query.and("(t.totalIncludingTax + coalesce(t.globalDiscountApplied, 0)) <= :grossMax");
            query.bind("grossMax", criteria.grossMax);
        }
        if (criteria.hourFrom != null) {
            query.and("extract(hour from t.creationDate) >= :hourFrom");
            query.bind("hourFrom", criteria.hourFrom);
        }
        if (criteria.hourTo != null) {
            query.and("extract(hour from t.creationDate) <= :hourTo");
            query.bind("hourTo", criteria.hourTo);
        }
        if (!criteria.methods.isEmpty()) {
            List<Class<?>> types = new ArrayList<>();
            for (String key : criteria.methods) {
                Class<?> type = PaymentTypes.forKey(key);
                if (type != null) {
                    types.add(type);
                }
            }
            if (!types.isEmpty()) {
                query.and("exists (select p from t.payments p where type(p) in :methodTypes)");
                query.bind("methodTypes", types);
            }
        }
        if (criteria.dateFrom != null) {
            query.and("t.creationDate >= :dateFrom");
            query.bind("dateFrom", criteria.dateFrom);
        }
        if (criteria.dateTo != null) {
            query.and("t.creationDate <= :dateTo");
            query.bind("dateTo", criteria.dateTo);
        }
        appendPluRange(query, criteria);
        appendFamilyRange(query, criteria);
        if (criteria.vatRate != null) {
            query.and("exists (select l from t.lines l where l.cancelled = false and l.vatRate = :vatRate)");
            query.bind("vatRate", criteria.vatRate);
        }
        appendReductionRange(query, criteria);
        appendAuthorizationRange(query, criteria);
        appendRefundArticleRange(query, criteria);
        appendCancelledArticleRange(query, criteria);
        appendFlags(query, criteria);
        return query;
    }

    /**
     * Appends the article PLU range as a single line-scoped {@code exists}, so
     * both bounds bear on the same line rather than on any two lines.
     *
     * @param query the query under construction
     * @param criteria the parsed criteria
     */
    private void appendPluRange(JournalQuery query, JournalCriteria criteria) {
        if (criteria.pluMin == null && criteria.pluMax == null) {
            return;
        }
        // The sold-article PLU search bears on sold lines only: a cancelled
        // article (lot C4) is a journal witness, not a sale, so it never
        // matches BO-04-01-10 — hence the leading {@code l.cancelled = false}.
        // BO-04-01-10: the EFFECTIVE PLU is the line's own weighing PLU when it
        // has one, else the catalog PLU of the product it references — so an
        // article carrying a catalog PLU but SOLD OUTSIDE weighing (l.plu null)
        // is no longer invisible. A LEFT JOIN keeps unknown-product lines rather
        // than dropping them, and coalesce picks the line PLU over the catalog.
        StringBuilder inner = new StringBuilder(
                "exists (select l from t.lines l left join l.product pr where l.cancelled = false");
        if (criteria.pluMin != null) {
            inner.append(" and coalesce(l.plu, pr.plu) >= :pluMin");
            query.bind("pluMin", criteria.pluMin);
        }
        if (criteria.pluMax != null) {
            inner.append(" and coalesce(l.plu, pr.plu) <= :pluMax");
            query.bind("pluMax", criteria.pluMax);
        }
        inner.append(")");
        query.and(inner.toString());
    }

    /**
     * Appends the nomenclature range (BO-04-01-11) as a single line-scoped
     * {@code exists} on the family code snapshotted at sale time, so both bounds
     * bear on the same line. A single family is expressed as an equal min and
     * max; a "plage de familles" as a span of codes. The comparison is on the
     * line's own {@code familyCode} snapshot, never on the current referential,
     * which is the whole point of the snapshot.
     *
     * @param query the query under construction
     * @param criteria the parsed criteria
     */
    private void appendFamilyRange(JournalQuery query, JournalCriteria criteria) {
        // BO-04-01-11: the multi-select complement of the range — several
        // explicitly picked families ORed as one line-scoped {@code in}, on the
        // line's own snapshotted family code. Independent of the range: a search
        // may pose either, both, or neither.
        if (!criteria.families.isEmpty()) {
            query.and("exists (select l from t.lines l where l.cancelled = false"
                    + " and l.familyCode in :families)");
            query.bind("families", new ArrayList<>(criteria.families));
        }
        if (criteria.familyMin == null && criteria.familyMax == null) {
            return;
        }
        // Sold lines only: a cancelled article (lot C4) never matches the
        // nomenclature search, hence the leading {@code l.cancelled = false}.
        StringBuilder inner = new StringBuilder("exists (select l from t.lines l where l.cancelled = false");
        if (criteria.familyMin != null) {
            inner.append(" and l.familyCode >= :familyMin");
            query.bind("familyMin", criteria.familyMin);
        }
        if (criteria.familyMax != null) {
            inner.append(" and l.familyCode <= :familyMax");
            query.bind("familyMax", criteria.familyMax);
        }
        inner.append(")");
        query.and(inner.toString());
    }

    /**
     * Appends the refunded-article criterion (BO-04-01-23): the ticket carries a
     * refund whose refunded line gives back an article whose PLU falls in the
     * requested range and whose refunded amount falls in the requested amount
     * range. The refunded article's PLU is read on the original ticket line the
     * refund line references ({@code rl.originalLineId} → {@code ol.id}, the
     * consolidated line id the ingestion resolved), so the PLU snapshot is the
     * one the article was SOLD under. The amount compared is the refunded line
     * total ({@code price} × {@code quantity}, tax included) — the money given
     * back for that article. Every bound is optional; any subset narrows the
     * same single-refund-line {@code exists}.
     *
     * @param query the query under construction
     * @param criteria the parsed criteria
     */
    private void appendRefundArticleRange(JournalQuery query, JournalCriteria criteria) {
        if (criteria.refundPluMin == null && criteria.refundPluMax == null
                && criteria.refundAmountMin == null && criteria.refundAmountMax == null) {
            return;
        }
        // BO-04-01-23: same effective-PLU correction on the ORIGINAL line the
        // refund gives back — its weighing PLU, else the catalog PLU of the
        // product it referenced, via a LEFT JOIN so a null-product line stays.
        StringBuilder inner = new StringBuilder(
                "exists (select rl from Refund r join r.lines rl, TicketLine ol"
                        + " left join ol.product opr"
                        + " where r.originalTicketId = t.id and ol.id = rl.originalLineId"
                        + " and ol.cancelled = false");
        if (criteria.refundPluMin != null) {
            inner.append(" and coalesce(ol.plu, opr.plu) >= :refundPluMin");
            query.bind("refundPluMin", criteria.refundPluMin);
        }
        if (criteria.refundPluMax != null) {
            inner.append(" and coalesce(ol.plu, opr.plu) <= :refundPluMax");
            query.bind("refundPluMax", criteria.refundPluMax);
        }
        if (criteria.refundAmountMin != null) {
            inner.append(" and rl.price * rl.quantity >= :refundAmountMin");
            query.bind("refundAmountMin", criteria.refundAmountMin);
        }
        if (criteria.refundAmountMax != null) {
            inner.append(" and rl.price * rl.quantity <= :refundAmountMax");
            query.bind("refundAmountMax", criteria.refundAmountMax);
        }
        inner.append(")");
        query.and(inner.toString());
    }

    /**
     * Appends the manual-reduction criterion (BO-04-01-26): at least one line
     * carries a manual gesture, optionally within an applied-amount range.
     *
     * @param query the query under construction
     * @param criteria the parsed criteria
     */
    private void appendReductionRange(JournalQuery query, JournalCriteria criteria) {
        if (criteria.reductionMin == null && criteria.reductionMax == null) {
            return;
        }
        // Sold lines only: a discounted line later cancelled (lot C4) is not a
        // manual reduction that stood on the ticket, so it is excluded here.
        // BO-04-01-26: the "réduction manuelle" search is REMISE and DISCOUNT
        // only — a FORCE_PRICE is a price override, not a reduction, and must
        // not be swept in by a bare "modifierType is not null".
        //
        // A REMISE modifierValue is now a UNIT discount on the register wire, so
        // the reduction that stood on the LINE is modifierValue * quantity; a
        // DISCOUNT modifierValue is a percentage and is compared as-is. The CASE
        // yields the comparable magnitude per type so one range fits both.
        String magnitude = "(case when l.modifierType = :remiseType"
                + " then l.modifierValue * l.quantity else l.modifierValue end)";
        StringBuilder inner = new StringBuilder(
                "exists (select l from t.lines l where l.cancelled = false and l.modifierType in :reductionTypes");
        query.bind("reductionTypes",
                List.of(com.intermarche.pos.ui.PriceModType.REMISE, com.intermarche.pos.ui.PriceModType.DISCOUNT));
        query.bind("remiseType", com.intermarche.pos.ui.PriceModType.REMISE);
        if (criteria.reductionMin != null) {
            inner.append(" and ").append(magnitude).append(" >= :reductionMin");
            query.bind("reductionMin", criteria.reductionMin);
        }
        if (criteria.reductionMax != null) {
            inner.append(" and ").append(magnitude).append(" <= :reductionMax");
            query.bind("reductionMax", criteria.reductionMax);
        }
        inner.append(")");
        query.and(inner.toString());
    }

    /**
     * Appends the cancelled-article criterion (BO-04-01-16): the ticket bears at
     * least one CANCELLED line whose PLU falls in the requested range and whose
     * line total falls in the requested amount range. Both ranges are optional;
     * any subset narrows the same single-cancelled-line {@code exists}, so the
     * two bounds bear on the SAME cancelled article rather than on any two. The
     * {@code l.cancelled = true} guard is the mirror image of the sold-article
     * searches: those exclude cancelled lines, this one requires one — the
     * gisement campaign lot C4 opened by keeping the witness on the line. A
     * bare "annulation article" with no bound is served by the
     * {@link JournalCriteria.Flag#CANCELLED_ARTICLE} flag instead.
     *
     * @param query the query under construction
     * @param criteria the parsed criteria
     */
    private void appendCancelledArticleRange(JournalQuery query, JournalCriteria criteria) {
        if (criteria.cancelPluMin == null && criteria.cancelPluMax == null
                && criteria.cancelAmountMin == null && criteria.cancelAmountMax == null) {
            return;
        }
        // BO-04-01-16: same effective-PLU correction as the sold-article search
        // — the cancelled article's own weighing PLU, else its catalog PLU.
        StringBuilder inner = new StringBuilder(
                "exists (select l from t.lines l left join l.product pr where l.cancelled = true");
        if (criteria.cancelPluMin != null) {
            inner.append(" and coalesce(l.plu, pr.plu) >= :cancelPluMin");
            query.bind("cancelPluMin", criteria.cancelPluMin);
        }
        if (criteria.cancelPluMax != null) {
            inner.append(" and coalesce(l.plu, pr.plu) <= :cancelPluMax");
            query.bind("cancelPluMax", criteria.cancelPluMax);
        }
        if (criteria.cancelAmountMin != null) {
            inner.append(" and l.totalPrice >= :cancelAmountMin");
            query.bind("cancelAmountMin", criteria.cancelAmountMin);
        }
        if (criteria.cancelAmountMax != null) {
            inner.append(" and l.totalPrice <= :cancelAmountMax");
            query.bind("cancelAmountMax", criteria.cancelAmountMax);
        }
        inner.append(")");
        query.and(inner.toString());
    }

    /**
     * Appends the card authorization-number range (BO-04-01-08): at least one
     * card payment on the ticket carries an authorization number within the
     * requested bounds. The {@code treat} downcast restricts the correlated
     * sub-select to card payments, and the {@code is not null} guard excludes
     * degraded acceptances (which reach no monetique and hold no number), so a
     * bound never silently matches them.
     *
     * @param query the query under construction
     * @param criteria the parsed criteria
     */
    private void appendAuthorizationRange(JournalQuery query, JournalCriteria criteria) {
        if (criteria.authMin == null && criteria.authMax == null) {
            return;
        }
        StringBuilder inner = new StringBuilder(
                "exists (select p from t.payments p where treat(p as CardPayment).authorizationNumber is not null");
        if (criteria.authMin != null) {
            inner.append(" and treat(p as CardPayment).authorizationNumber >= :authMin");
            query.bind("authMin", criteria.authMin);
        }
        if (criteria.authMax != null) {
            inner.append(" and treat(p as CardPayment).authorizationNumber <= :authMax");
            query.bind("authMax", criteria.authMax);
        }
        inner.append(")");
        query.and(inner.toString());
    }

    /**
     * Appends one condition per selected boolean flag
     * (BO-04-01-14/25/31/32/34/46/47/49).
     *
     * @param query the query under construction
     * @param criteria the parsed criteria
     */
    private void appendFlags(JournalQuery query, JournalCriteria criteria) {
        if (criteria.flags.contains(JournalCriteria.Flag.CANCELLED)) {
            query.and("t.status = :cancelled");
            query.bind("cancelled", Ticket.TicketStatus.CANCELLED);
        }
        if (criteria.flags.contains(JournalCriteria.Flag.DISCOUNT)) {
            query.and("t.globalDiscountApplied is not null and t.globalDiscountApplied > 0");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.RETURN)) {
            query.and("exists (select r from Refund r where r.originalTicketId = t.id)");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.NEGATIVE)) {
            query.and("t.totalIncludingTax < 0");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.ZERO_PRICE)) {
            query.and("exists (select l from t.lines l where l.cancelled = false and l.totalPrice = 0)");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.UNKNOWN_ITEM)) {
            query.and("exists (select l from t.lines l"
                    + " where l.cancelled = false and l.product is null and l.deposit = false)");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.CANCELLED_ARTICLE)) {
            query.and("exists (select l from t.lines l where l.cancelled = true)");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.VOUCHER)) {
            query.and("exists (select p from t.payments p where type(p) = :voucherType)");
            query.bind("voucherType", VoucherPayment.class);
        }
        if (criteria.flags.contains(JournalCriteria.Flag.CARD)) {
            // BO-04-01-46: the "total monétique" counts a backup-monetics
            // settlement as a card payment too — somebody else's monetics
            // authorized it, but it is still a card acceptance, not cash.
            query.and("exists (select p from t.payments p where type(p) = :cardType or type(p) = :backupType)");
            query.bind("cardType", CardPayment.class);
            query.bind("backupType", com.intermarche.pos.domain.payment.BackupPayment.class);
        }
        if (criteria.flags.contains(JournalCriteria.Flag.DEGRADED)) {
            query.and("exists (select p from t.payments p where treat(p as CardPayment).degradedMode = true)");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.DEGRADED_MANUAL)) {
            query.and("exists (select p from t.payments p where treat(p as CardPayment).degradedMode = true)");
        }
    }

    /**
     * Runs the transactional search and returns the requested page of rows,
     * with the total number of matching documents. Ordering is the requested
     * column (BO-04-01-51) with a stable id tiebreak. The journal is a control
     * surface: the count is always taken, so the list can state what it is not
     * showing rather than truncating in silence.
     *
     * @param criteria the parsed search criteria, carrying the requested page
     * @return the requested page of rows and the total row count
     */
    public JournalPage<JournalRow> search(JournalCriteria criteria) {
        LOGGER.info("Entering method search with criteria: " + criteria);
        JournalQuery query = buildTicketQuery(criteria);
        long total = count("select count(t.id) from Ticket t" + query.whereClause(), query);
        int number = clampPage(criteria.page, total, PAGE_SIZE);
        List<JournalRow> rows = ticketRows(criteria, query, (number - 1) * PAGE_SIZE, PAGE_SIZE);
        LOGGER.info("Exiting method search");
        return new JournalPage<>(rows, number, PAGE_SIZE, total);
    }

    /**
     * Reads one window of the transactional list and maps the projected rows to
     * {@link JournalRow}.
     *
     * @param criteria the parsed criteria, read for the sort column and direction
     * @param query the already-built where clause and its parameters
     * @param offset the 0-based index of the first row to read
     * @param limit the maximum number of rows to read
     * @return the mapped rows of that window
     */
    private List<JournalRow> ticketRows(JournalCriteria criteria, JournalQuery query,
                                        int offset, int limit) {
        JournalSort sort = JournalSort.fromKey(criteria.sort);
        String direction = criteria.descending ? "desc" : "asc";
        String jpql = "select t.id, t.terminalId, t.ticketNumber, t.cashier.badgeId,"
                + " t.creationDate, t.totalIncludingTax, t.itemCount from Ticket t"
                + query.whereClause()
                + " order by " + sort.path + " " + direction + ", t.id asc";
        TypedQuery<Object[]> typed = entityManager.createQuery(jpql, Object[].class);
        bind(typed, query);
        typed.setFirstResult(offset);
        typed.setMaxResults(limit);
        List<JournalRow> rows = new ArrayList<>();
        for (Object[] row : typed.getResultList()) {
            LocalDateTime creation = (LocalDateTime) row[4];
            rows.add(new JournalRow(
                    (Long) row[0],
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    creation.format(DATE),
                    creation.format(TIME),
                    formatAmount((BigDecimal) row[5]),
                    (Integer) row[6],
                    "N",
                    "N"));
        }
        return rows;
    }

    /**
     * Runs a count query over an already-built where clause.
     *
     * @param jpql the complete count query
     * @param query the built query carrying the parameters to bind
     * @return the number of matching rows
     */
    private long count(String jpql, JournalQuery query) {
        TypedQuery<Long> typed = entityManager.createQuery(jpql, Long.class);
        bind(typed, query);
        return typed.getSingleResult();
    }

    /**
     * Binds every parameter of a built query onto a typed query.
     *
     * @param typed the query to bind the parameters on
     * @param query the built query carrying the parameters
     */
    private void bind(TypedQuery<?> typed, JournalQuery query) {
        for (Map.Entry<String, Object> entry : query.parameters().entrySet()) {
            typed.setParameter(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Clamps a requested page number into the pages the result set actually
     * has, so a stale link or a hand-typed page shows the last page rather than
     * an empty list.
     *
     * @param requested the requested 1-based page number
     * @param total the total number of matching rows
     * @param size the page size
     * @return the page number to read, between 1 and the page count
     */
    private int clampPage(int requested, long total, int size) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, JournalPage.pageCount(total, size));
    }

    /**
     * Loads a ticket and materializes it into a fully-formatted
     * {@link JournalTicketDetail}, or null when the id matches no row. The
     * lazy lines and payments are read here, inside the request session, so
     * the detail template never touches an unfetched association.
     *
     * @param id the ticket database id
     * @return the materialized detail, or null
     */
    public JournalTicketDetail buildDetail(Long id) {
        LOGGER.info("Entering method buildDetail with id: " + id);
        Ticket ticket = entityManager.find(Ticket.class, id);
        if (ticket == null) {
            LOGGER.info("Exiting method buildDetail");
            return null;
        }
        List<JournalTicketDetail.Line> lines = new ArrayList<>();
        for (TicketLine line : ticket.lines) {
            lines.add(new JournalTicketDetail.Line(
                    line.lineNumber,
                    line.productLabel,
                    line.ean,
                    line.plu,
                    formatQuantity(line.quantity),
                    formatAmount(line.totalPrice),
                    line.modifierLabel,
                    line.cancelled));
        }
        List<JournalTicketDetail.Payment> payments = new ArrayList<>();
        for (TicketPayment payment : ticket.payments) {
            payments.add(new JournalTicketDetail.Payment(
                    payment.getMethodKey(),
                    formatAmount(payment.amount)));
        }
        LOGGER.info("Exiting method buildDetail");
        return new JournalTicketDetail(
                ticket.ticketNumber,
                ticket.terminalId,
                ticket.status.name(),
                ticket.creationDate.format(DATE),
                ticket.creationDate.format(TIME),
                ticket.cashier != null ? ticket.cashier.getFullName() : "",
                ticket.cashier != null ? ticket.cashier.badgeId : "",
                ticket.store != null ? ticket.store.name : "",
                ticket.fidelityCard != null ? ticket.fidelityCard : "",
                formatAmount(ticket.totalIncludingTax),
                formatAmount(ticket.totalExcludingTax),
                formatAmount(ticket.totalVat),
                lines,
                payments,
                fidelityGroups(ticket));
    }

    /**
     * Builds the grouped loyalty advantage zone of a ticket for the journal
     * detail: the frozen advantage lines grouped by advantage type and ordered
     * by the received {@code advantageTypeOrder} (BO-03-03-25), the stored labels
     * kept verbatim.
     * <p>
     * A ticket predating the referential — no line carrying a type — yields a
     * SINGLE group with an empty header holding the flat list, so it shows as it
     * did before; a ticket with no advantage line yields an empty list. Untyped
     * lines mixed with typed ones fall into a trailing empty-header group.
     *
     * @param ticket the sale whose loyalty zone is described
     * @return the groups, never null
     */
    private List<JournalTicketDetail.FidelityGroup> fidelityGroups(Ticket ticket) {
        List<com.intermarche.pos.domain.sale.TicketFidelityLine> allLines =
                ticket.fidelityLines == null ? List.of() : ticket.fidelityLines;
        List<JournalTicketDetail.FidelityGroup> result = new ArrayList<>();
        if (allLines.isEmpty()) {
            return result;
        }
        boolean anyTyped = false;
        for (com.intermarche.pos.domain.sale.TicketFidelityLine line : allLines) {
            if (line.advantageType != null) {
                anyTyped = true;
                break;
            }
        }
        if (!anyTyped) {
            List<JournalTicketDetail.FidelityAdvantage> flat = new ArrayList<>();
            for (com.intermarche.pos.domain.sale.TicketFidelityLine line : allLines) {
                flat.add(new JournalTicketDetail.FidelityAdvantage(line.getLabel(), formatAmount(line.amount)));
            }
            result.add(new JournalTicketDetail.FidelityGroup("", flat));
            return result;
        }
        java.util.LinkedHashMap<String,
                List<com.intermarche.pos.domain.sale.TicketFidelityLine>> groups =
                new java.util.LinkedHashMap<>();
        List<com.intermarche.pos.domain.sale.TicketFidelityLine> untyped = new ArrayList<>();
        for (com.intermarche.pos.domain.sale.TicketFidelityLine line : allLines) {
            if (line.advantageType == null) {
                untyped.add(line);
            } else {
                groups.computeIfAbsent(line.advantageType, key -> new ArrayList<>()).add(line);
            }
        }
        List<String> orderedKeys = new ArrayList<>(groups.keySet());
        orderedKeys.sort(java.util.Comparator.comparingInt(key -> {
            Integer order = groups.get(key).get(0).advantageTypeOrder;
            return order == null ? Integer.MAX_VALUE : order;
        }));
        for (String key : orderedKeys) {
            List<com.intermarche.pos.domain.sale.TicketFidelityLine> groupLines = groups.get(key);
            String header = groupLines.get(0).advantageTypeLabel;
            List<JournalTicketDetail.FidelityAdvantage> advantages = new ArrayList<>();
            for (com.intermarche.pos.domain.sale.TicketFidelityLine line : groupLines) {
                advantages.add(new JournalTicketDetail.FidelityAdvantage(line.getLabel(), formatAmount(line.amount)));
            }
            result.add(new JournalTicketDetail.FidelityGroup(header == null ? key : header, advantages));
        }
        if (!untyped.isEmpty()) {
            List<JournalTicketDetail.FidelityAdvantage> flat = new ArrayList<>();
            for (com.intermarche.pos.domain.sale.TicketFidelityLine line : untyped) {
                flat.add(new JournalTicketDetail.FidelityAdvantage(line.getLabel(), formatAmount(line.amount)));
            }
            result.add(new JournalTicketDetail.FidelityGroup("", flat));
        }
        return result;
    }

    // --------------------------------------------------
    // Functional journal
    // --------------------------------------------------

    /**
     * Builds the {@code where} clause of the functional-event search.
     * <p>
     * The cashier range (BO-04-01-27/28/29/30) reuses the same
     * {@code cashierMin}/{@code cashierMax} criteria as the transactional tab,
     * matched here against {@link TechnicalEvent#operatorBadgeId} — the
     * first-class badge column, badge compared to badge. The operator-security
     * events (register lock/unlock, password change and failure) name their
     * operator by that column, so the range narrows a selected action to a band
     * of cashiers exactly as the questionnaire asks. Comparing the range against
     * the free-text {@code detail} column instead would match any detail that
     * happens to sort between the bounds for the sixteen other event types — a
     * defect, not a shortcut. An event with no operator has a null badge, which
     * never matches a range, so it is excluded rather than wrongly returned.
     *
     * @param criteria the parsed criteria
     * @return the assembled query fragment and its parameters
     */
    JournalQuery buildEventQuery(JournalCriteria criteria) {
        JournalQuery query = new JournalQuery();
        if (!criteria.eventTypes.isEmpty()) {
            List<TechnicalEvent.EventType> types = new ArrayList<>();
            for (String name : criteria.eventTypes) {
                TechnicalEvent.EventType type = EventTypes.forName(name);
                if (type != null) {
                    types.add(type);
                }
            }
            if (!types.isEmpty()) {
                query.and("e.eventType in :eventTypes");
                query.bind("eventTypes", types);
            }
        }
        if (criteria.cashierMin != null) {
            query.and("e.operatorBadgeId >= :cashierMin");
            query.bind("cashierMin", criteria.cashierMin);
        }
        if (criteria.cashierMax != null) {
            query.and("e.operatorBadgeId <= :cashierMax");
            query.bind("cashierMax", criteria.cashierMax);
        }
        if (criteria.terminalMin != null) {
            query.and("e.terminalId >= :terminalMin");
            query.bind("terminalMin", criteria.terminalMin);
        }
        if (criteria.terminalMax != null) {
            query.and("e.terminalId <= :terminalMax");
            query.bind("terminalMax", criteria.terminalMax);
        }
        if (criteria.dateFrom != null) {
            query.and("e.eventDate >= :dateFrom");
            query.bind("dateFrom", criteria.dateFrom);
        }
        if (criteria.dateTo != null) {
            query.and("e.eventDate <= :dateTo");
            query.bind("dateTo", criteria.dateTo);
        }
        if (criteria.text != null) {
            query.and("lower(e.detail) like :text");
            query.bind("text", "%" + criteria.text.toLowerCase() + "%");
        }
        return query;
    }

    /**
     * Runs the functional-event search, most recent first, and returns the
     * requested page with the total number of matching events — the same
     * no-silent-truncation posture as the transactional list.
     *
     * @param criteria the parsed criteria, carrying the requested page
     * @return the requested page of event rows and the total row count
     */
    public JournalPage<JournalEventRow> searchEvents(JournalCriteria criteria) {
        LOGGER.info("Entering method searchEvents with criteria: " + criteria);
        JournalQuery query = buildEventQuery(criteria);
        long total = count("select count(e.id) from TechnicalEvent e" + query.whereClause(), query);
        int number = clampPage(criteria.page, total, PAGE_SIZE);
        String jpql = "select e from TechnicalEvent e" + query.whereClause()
                + " order by e.eventDate desc, e.id asc";
        TypedQuery<TechnicalEvent> typed = entityManager.createQuery(jpql, TechnicalEvent.class);
        bind(typed, query);
        typed.setFirstResult((number - 1) * PAGE_SIZE);
        typed.setMaxResults(PAGE_SIZE);
        List<JournalEventRow> rows = new ArrayList<>();
        for (TechnicalEvent event : typed.getResultList()) {
            rows.add(new JournalEventRow(
                    event.terminalId,
                    event.operatorBadgeId,
                    event.eventType.name(),
                    event.eventDate.format(DATE),
                    event.eventDate.format(TIME),
                    event.detail));
        }
        LOGGER.info("Exiting method searchEvents");
        return new JournalPage<>(rows, number, PAGE_SIZE, total);
    }

    // --------------------------------------------------
    // Movements journal
    // --------------------------------------------------

    /**
     * Builds the {@code where} clause of the cash-movements search (the third
     * tab, BO-04-01-12/33/35/36/37/40/44).
     * <p>
     * The selected movement types (BO-04-01-12/33/35/36/37/40/44, one criterion
     * per type) narrow the set to the requested kinds; several selected types OR
     * together, exactly like the payment methods and event types of the other
     * tabs. The cashier range reuses the same {@code cashierMin}/{@code cashierMax}
     * criteria as the two other tabs, matched here against the movement's own
     * cashier badge — badge compared to badge — which serves the
     * "(plage de N° caissière)" of BO-04-01-33/36/37; a movement with no cashier
     * has a null badge, which never matches a range, so it is excluded rather
     * than wrongly returned. The terminal range and the date range narrow the
     * movements the same way they narrow tickets. Every criterion is optional and
     * simply ANDs in.
     *
     * @param criteria the parsed criteria
     * @return the assembled query fragment and its parameters
     */
    JournalQuery buildMovementQuery(JournalCriteria criteria) {
        JournalQuery query = new JournalQuery();
        if (!criteria.movementTypes.isEmpty()) {
            query.and("m.type in :movementTypes");
            query.bind("movementTypes", new ArrayList<>(criteria.movementTypes));
        }
        if (criteria.movementAmountMin != null) {
            query.and("m.amount >= :movementAmountMin");
            query.bind("movementAmountMin", criteria.movementAmountMin);
        }
        if (criteria.movementAmountMax != null) {
            query.and("m.amount <= :movementAmountMax");
            query.bind("movementAmountMax", criteria.movementAmountMax);
        }
        if (criteria.cashierMin != null) {
            query.and("m.cashier.badgeId >= :cashierMin");
            query.bind("cashierMin", criteria.cashierMin);
        }
        if (criteria.cashierMax != null) {
            query.and("m.cashier.badgeId <= :cashierMax");
            query.bind("cashierMax", criteria.cashierMax);
        }
        if (criteria.terminalMin != null) {
            query.and("m.terminalId >= :terminalMin");
            query.bind("terminalMin", criteria.terminalMin);
        }
        if (criteria.terminalMax != null) {
            query.and("m.terminalId <= :terminalMax");
            query.bind("terminalMax", criteria.terminalMax);
        }
        if (criteria.dateFrom != null) {
            query.and("m.movementDate >= :dateFrom");
            query.bind("dateFrom", criteria.dateFrom);
        }
        if (criteria.dateTo != null) {
            query.and("m.movementDate <= :dateTo");
            query.bind("dateTo", criteria.dateTo);
        }
        return query;
    }

    /**
     * Runs the cash-movements search, most recent first, and returns the
     * requested page with the total number of matching movements — the same
     * no-silent-truncation posture as the other two tabs.
     *
     * @param criteria the parsed criteria, carrying the requested page
     * @return the requested page of movement rows and the total row count
     */
    public JournalPage<JournalMovementRow> searchMovements(JournalCriteria criteria) {
        LOGGER.info("Entering method searchMovements with criteria: " + criteria);
        JournalQuery query = buildMovementQuery(criteria);
        long total = count("select count(m.id) from CashMovement m" + query.whereClause(), query);
        int number = clampPage(criteria.page, total, PAGE_SIZE);
        String jpql = "select m from CashMovement m" + query.whereClause()
                + " order by m.movementDate desc, m.id asc";
        TypedQuery<CashMovement> typed = entityManager.createQuery(jpql, CashMovement.class);
        bind(typed, query);
        typed.setFirstResult((number - 1) * PAGE_SIZE);
        typed.setMaxResults(PAGE_SIZE);
        List<JournalMovementRow> rows = new ArrayList<>();
        for (CashMovement movement : typed.getResultList()) {
            rows.add(new JournalMovementRow(
                    movement.terminalId,
                    movement.cashier != null ? movement.cashier.badgeId : null,
                    movement.type.name(),
                    movement.movementDate.format(DATE),
                    movement.movementDate.format(TIME),
                    formatAmount(movement.amount),
                    movement.reason,
                    movement.endorsedBy));
        }
        LOGGER.info("Exiting method searchMovements");
        return new JournalPage<>(rows, number, PAGE_SIZE, total);
    }

    // --------------------------------------------------
    // Export
    // --------------------------------------------------

    /**
     * Renders the transactional list as a semicolon-separated CSV reproducing
     * the columns of {@link JournalRow} exactly (BO-04-01-50, export à
     * l'identique). Runs the same query as the screen so the two never diverge,
     * but over the WHOLE result set rather than the displayed page: an extract
     * an auditor works from is complete or it is worthless.
     *
     * @param criteria the parsed search criteria
     * @return the CSV document
     */
    public String exportCsv(JournalCriteria criteria) {
        LOGGER.info("Entering method exportCsv with criteria: " + criteria);
        LOGGER.info("Exiting method exportCsv");
        return exportCsv(criteria, EXPORT_CHUNK);
    }

    /**
     * Renders the CSV export, reading the result set in windows of the given
     * size so a large extract never materializes a single unbounded query.
     *
     * @param criteria the parsed search criteria
     * @param chunkSize the number of rows read per query, strictly positive
     * @return the CSV document covering every matching row
     */
    String exportCsv(JournalCriteria criteria, int chunkSize) {
        JournalQuery query = buildTicketQuery(criteria);
        StringBuilder csv = new StringBuilder(
                "TPV;Transaction;Caissiere;Date;Heure;Montant;Articles;Ecole;Autonome\n");
        int offset = 0;
        List<JournalRow> chunk = ticketRows(criteria, query, offset, chunkSize);
        while (!chunk.isEmpty()) {
            for (JournalRow row : chunk) {
                csv.append(row.terminal).append(';')
                        .append(row.transaction).append(';')
                        .append(row.cashier).append(';')
                        .append(row.date).append(';')
                        .append(row.time).append(';')
                        .append(row.amount).append(';')
                        .append(row.itemCount).append(';')
                        .append(row.trainingMode).append(';')
                        .append(row.autonomous).append('\n');
            }
            if (chunk.size() < chunkSize) {
                break;
            }
            offset += chunkSize;
            chunk = ticketRows(criteria, query, offset, chunkSize);
        }
        return csv.toString();
    }

    /**
     * Formats an amount for the list and export (2 decimals, French comma).
     *
     * @param amount the amount, or null
     * @return the formatted amount, "0,00" for a null amount
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "0,00";
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",");
    }

    /**
     * Formats a quantity for the detail (French comma, trailing zeros
     * stripped).
     *
     * @param quantity the quantity, or null
     * @return the formatted quantity, an empty string for a null quantity
     */
    private String formatQuantity(BigDecimal quantity) {
        if (quantity == null) {
            return "";
        }
        return quantity.stripTrailingZeros().toPlainString().replace(".", ",");
    }
}
