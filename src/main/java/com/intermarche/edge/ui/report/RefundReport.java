package com.intermarche.edge.ui.report;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ÉTAT REMBOURSEMENT (BO-06-06-05): every refund of the period, with the
 * register that made it, the method it was paid back by and the ticket it
 * settles.
 *
 * <p>Read on the refunds themselves and not on the tickets: a refund is a
 * document of its own on this node — it carries its own number, its own status
 * and its own lines — which is what lets it be listed even when it was paid
 * back outside the sale that produced it.
 *
 * <p>Only CLOSED refunds are listed. An open refund is a screen a cashier has
 * not finished; counting it would state money returned that is still in the
 * drawer. Since lot 4 a refund is created closed, so the filter costs nothing
 * and guards the day that changes.
 */
@ApplicationScoped
public class RefundReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RefundReport.class);

    /** The persistence session the query runs on. */
    /**
     * The French names of the refund methods: a state never shows a bare
     * technical key (CASH, VOUCHER).
     */
    static final java.util.Map<String, String> METHOD_NAMES = java.util.Map.of(
            "CASH", "Espèces",
            "CARD", "Carte bancaire",
            "VOUCHER", "Bon d'achat",
            "LOYALTY", "Fidélité",
            "CREDIT", "Crédit client");

    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "refunds";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État remboursement";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-05";
    }

    /**
     * {@inheritDoc}
     *
     * @return the cashiers family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.CASHIERS;
    }

    /**
     * {@inheritDoc}
     *
     * @return the columns the requirement enumerates
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.dateTime("when", "Date et heure"),
                ReportColumn.text("terminal", "N° TPV"),
                ReportColumn.text("number", "N° remboursement"),
                ReportColumn.text("cashier", "Code caissière"),
                ReportColumn.text("supervisor", "Code superviseur"),
                ReportColumn.text("typeCode", "Code type de retour"),
                ReportColumn.text("typeLabel", "Type de retour"),
                ReportColumn.text("method", "Type de remboursement"),
                ReportColumn.text("reasonCode", "N° de la raison"),
                ReportColumn.text("reasonLabel", "Libellé de la raison"),
                ReportColumn.text("ticket", "Ticket d'origine"),
                ReportColumn.count("lines", "Nb d'articles"),
                ReportColumn.amount("amount", "Montant"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per validated refund, oldest first
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        StringBuilder jpql = new StringBuilder(
                "select r.creationDate, r.terminalId, r.refundNumber, r.refundMethod,"
                        + " (select t.ticketNumber from Ticket t where t.id = r.originalTicketId),"
                        + " size(r.lines), r.totalAmount,"
                        + " r.cashierBadgeId, r.supervisorBadgeId, r.returnTypeCode,"
                        + " r.returnTypeLabel, r.returnReasonCode, r.returnReason"
                        + " from Refund r where r.status = :closed"
                        + " and r.creationDate >= :from and r.creationDate < :to");
        // BO-03-09-01: the registers chosen, and the deactivated ones left out.
        jpql.append(criteria.terminalFilter("r.terminalId"));
        jpql.append(" order by r.creationDate");
        var query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("closed", com.intermarche.edge.domain.sale.Refund.RefundStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] line : query.getResultList()) {
            rows.add(Arrays.asList(
                    (LocalDateTime) line[0],
                    line[1] == null ? "" : line[1],
                    line[2] == null ? "" : line[2],
                    line[7] == null ? "" : line[7],
                    line[8] == null ? "" : line[8],
                    line[9] == null ? "" : line[9],
                    line[10] == null ? "" : line[10],
                    line[3] == null ? "" : methodLabel(String.valueOf(line[3])),
                    line[11] == null ? "" : line[11],
                    line[12] == null ? "" : line[12],
                    line[4] == null ? "" : String.valueOf(line[4]),
                    ((Number) line[5]).longValue(),
                    line[6] == null ? BigDecimal.ZERO : line[6]));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Names a refund method in French.
     *
     * @param method the method's technical key
     * @return its French name, the key itself for a method this state does not know
     */
    static String methodLabel(String method) {
        return METHOD_NAMES.getOrDefault(method, method);
    }
}
