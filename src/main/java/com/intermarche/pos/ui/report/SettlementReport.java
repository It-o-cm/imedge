package com.intermarche.pos.ui.report;

import com.intermarche.pos.domain.payment.PaymentTypes;
import com.intermarche.pos.domain.payment.TenderDefinition;
import com.intermarche.pos.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * ÉTAT DES RÈGLEMENTS (BO-06-06-09): per settlement method, its functional
 * identifier, its administered label, the number of settlements and their net
 * value, with the report's total.
 *
 * <p>Counted on CLOSED tickets only. A parked cart carries no settlement and an
 * abandoned one carries settlements that were undone: adding either would state
 * a takings figure the safe will not match.
 *
 * <p>The method's LABEL comes from the administered tender table when the store
 * declared it (BO-03-02), and falls back to the technical key otherwise —
 * a settlement taken through a method nobody administered still has to appear,
 * or the total would silently drop it.
 */
@ApplicationScoped
public class SettlementReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SettlementReport.class);

    /** The persistence session the aggregate query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "settlements";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État des règlements";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-09";
    }

    /**
     * {@inheritDoc}
     *
     * @return the settlements family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.SETTLEMENTS;
    }

    /**
     * {@inheritDoc}
     *
     * @return the four columns of the state
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("key", "Type"),
                ReportColumn.text("id", "Identifiant"),
                ReportColumn.text("label", "Libellé du mode"),
                ReportColumn.count("count", "Nombre"),
                ReportColumn.amount("amount", "Valeur nette"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the amount column, the one worth drawing
     */
    @Override
    public int chartColumn() {
        return 4;
    }

    /**
     * {@inheritDoc}
     *
     * @return the label column, so each bar is named by its method
     */
    @Override
    public int chartLabelColumn() {
        return 2;
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per settlement method used over the period
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        StringBuilder jpql = new StringBuilder(
                "select type(p), count(p), sum(p.amount) from Ticket t join t.payments p"
                        + " where t.status = :closed and t.closingDate >= :from and t.closingDate < :to");
        if (!criteria.terminals.isEmpty()) {
            jpql.append(" and t.terminalId in :terminals");
        }
        jpql.append(" group by type(p)");
        var query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (!criteria.terminals.isEmpty()) {
            query.setParameter("terminals", criteria.terminals);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] line : query.getResultList()) {
            String key = keyOf(line[0]);
            TenderDefinition tender = TenderDefinition.find("code", key).firstResult();
            rows.add(List.of(
                    key,
                    tender == null || tender.functionalId == null ? "" : tender.functionalId,
                    tender == null || tender.label == null || tender.label.isBlank() ? key : tender.label,
                    ((Number) line[1]).longValue(),
                    line[2] == null ? BigDecimal.ZERO : (BigDecimal) line[2]));
        }
        // Sorted on the value, descending: the reader of a settlement state is
        // looking for what the day was made of, and that is the order that
        // answers them without reading every line.
        rows.sort((a, b) -> ((BigDecimal) b.get(4)).compareTo((BigDecimal) a.get(4)));
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Resolves the discriminator key of a payment entity class.
     *
     * <p>Read back from {@link PaymentTypes}, the registry the whole system
     * names its methods by, rather than from the class's simple name: the key is
     * what the session report, the sync payloads and the tender table all use,
     * and deriving it from a class name would silently diverge the day a class is
     * renamed.
     *
     * @param type the entity class the query returned
     * @return the discriminator key, or the class's simple name when unknown
     */
    private String keyOf(Object type) {
        for (String key : PaymentTypes.keys()) {
            if (PaymentTypes.forKey(key) == type) {
                return key;
            }
        }
        return type instanceof Class<?> clazz ? clazz.getSimpleName() : String.valueOf(type);
    }
}
