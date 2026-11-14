package com.intermarche.edge.ui.report;

import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ÉTAT CONSIGNE / DÉCONSIGNE (BO-03-13-11): BOTH sides of the deposits of
 * the period, each movement on its row and both sides totalled.
 *
 * <ul>
 *   <li>CONSIGNE — a deposit charged: a sale line of a deposit article (the
 *       EAN codes of parameter {@code deposit.article-eans});</li>
 *   <li>DÉCONSIGNE — a deposit given back, whichever way it came: a deposit
 *       article taken back on a ticket; a return taken under a return type
 *       administered as a déconsigne — a refund of a dedicated return ticket
 *       or a return line inside a sale (BO-03-13-12); a deposit-voucher line,
 *       the negative line a scanned deposit voucher or the DÉCONSIGNATION
 *       key adds.</li>
 * </ul>
 *
 * <p>A return type is a déconsigne when the return-type referential says so,
 * and — for a shop still on the administered lists — when its list entry
 * carries the {@code DECONSIGNE} marker. Only CLOSED tickets and refunds are
 * counted, for the reason {@link RefundReport} states: an open document is
 * money still in the drawer.
 */
@ApplicationScoped
public class DepositReturnReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DepositReturnReport.class);

    /** The option marker of a listed return type that is a déconsigne. */
    static final String MARKER = "DECONSIGNE";

    /** The nature of a deposit charged. */
    static final String CONSIGNE = "Consigne";

    /** The nature of a deposit article taken back. */
    static final String ARTICLE_BACK = "Reprise article consigné";

    /** The nature of a return taken under a déconsigne type. */
    static final String TYPED_RETURN = "Retour déconsigne";

    /** The nature of a deposit-voucher line. */
    static final String VOUCHER = "Bon de déconsigne";

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /** The administered deposit articles. */
    @Inject PosSettingsService posSettingsService;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "deposit-returns";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État consigne / déconsigne";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-03-13-11";
    }

    /**
     * {@inheritDoc}
     *
     * @return the cashiers family, beside the refund state
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.CASHIERS;
    }

    /**
     * {@inheritDoc}
     *
     * @return the columns of a deposit movement, the two sides totalled
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.dateTime("when", "Date et heure"),
                ReportColumn.text("terminal", "N° TPV"),
                ReportColumn.text("number", "N° de document"),
                ReportColumn.text("nature", "Nature"),
                ReportColumn.text("code", "Code"),
                ReportColumn.text("label", "Libellé"),
                ReportColumn.amount("consigne", "Consigne"),
                ReportColumn.amount("deconsigne", "Déconsigne"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per deposit movement of the period, oldest first
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        Set<String> typeCodes = deconsigneCodes();
        Set<String> eans = new LinkedHashSet<>(posSettingsService.depositArticleEans());
        List<List<Object>> rows = new ArrayList<>();
        refundRows(criteria, typeCodes, rows);
        lineRows(criteria, typeCodes, eans, rows);
        rows.sort(Comparator.comparing(row -> (LocalDateTime) row.get(0)));
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * The codes of the return types that are déconsignes: the referential's,
     * then the listed ones carrying the marker.
     *
     * @return the codes, possibly empty
     */
    Set<String> deconsigneCodes() {
        Set<String> codes = new LinkedHashSet<>(entityManager.createQuery(
                "select d.code from ReturnTypeDefinition d where d.depositReturn = true", String.class)
                .getResultList());
        codes.addAll(entityManager.createQuery(
                "select i.code from PosListItem i where i.posList.kind = :kind"
                        + " and upper(i.options) like :marker", String.class)
                .setParameter("kind", com.intermarche.edge.domain.setting.PosList.Kind.RETURN_TYPE)
                .setParameter("marker", "%" + MARKER + "%")
                .getResultList());
        return codes;
    }

    /**
     * Adds the refunds of the period filed under a déconsigne type.
     *
     * @param criteria the edition parameters
     * @param typeCodes the déconsigne type codes
     * @param rows the rows to add to
     */
    private void refundRows(ReportCriteria criteria, Set<String> typeCodes, List<List<Object>> rows) {
        if (typeCodes.isEmpty()) {
            return;
        }
        StringBuilder jpql = new StringBuilder(
                "select r.creationDate, r.terminalId, r.refundNumber, r.returnTypeCode,"
                        + " r.returnTypeLabel, r.totalAmount"
                        + " from Refund r where r.status = :closed"
                        + " and r.creationDate >= :from and r.creationDate < :to"
                        + " and r.returnTypeCode in :codes");
        if (!criteria.terminals.isEmpty()) {
            jpql.append(" and r.terminalId in :terminals");
        }
        var query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("closed", com.intermarche.edge.domain.sale.Refund.RefundStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to())
                .setParameter("codes", typeCodes);
        if (!criteria.terminals.isEmpty()) {
            query.setParameter("terminals", criteria.terminals);
        }
        for (Object[] line : query.getResultList()) {
            rows.add(row(line[0], line[1], line[2], TYPED_RETURN, line[3], line[4],
                    BigDecimal.ZERO, abs(line[5])));
        }
    }

    /**
     * Adds the ticket lines of the period that move a deposit: deposit
     * articles sold or taken back, returns under a déconsigne type,
     * deposit-voucher lines.
     *
     * @param criteria the edition parameters
     * @param typeCodes the déconsigne type codes
     * @param eans the deposit article codes
     * @param rows the rows to add to
     */
    private void lineRows(ReportCriteria criteria, Set<String> typeCodes, Set<String> eans,
                          List<List<Object>> rows) {
        StringBuilder jpql = new StringBuilder(
                "select t.creationDate, t.terminalId, t.ticketNumber, l.ean, l.productLabel,"
                        + " l.totalPrice, l.returnLine, l.deposit, l.returnTypeCode, l.returnTypeLabel"
                        + " from Ticket t join t.lines l where t.status = :closed"
                        + " and t.creationDate >= :from and t.creationDate < :to"
                        + " and l.cancelled = false and (l.deposit = true or l.ean in :eans)");
        if (!criteria.terminals.isEmpty()) {
            jpql.append(" and t.terminalId in :terminals");
        }
        var query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("closed", com.intermarche.edge.domain.sale.Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to())
                .setParameter("eans", eans.isEmpty() ? Set.of("") : eans);
        if (!criteria.terminals.isEmpty()) {
            query.setParameter("terminals", criteria.terminals);
        }
        for (Object[] line : query.getResultList()) {
            List<Object> row = lineRow(line, typeCodes, eans);
            if (row != null) {
                rows.add(row);
            }
        }
    }

    /**
     * Classifies one ticket line, the deposit article first, then the typed
     * return, then the deposit voucher.
     *
     * @param line the selected columns
     * @param typeCodes the déconsigne type codes
     * @param eans the deposit article codes
     * @return the row, or null when the line moves no deposit
     */
    private List<Object> lineRow(Object[] line, Set<String> typeCodes, Set<String> eans) {
        BigDecimal total = line[5] == null ? BigDecimal.ZERO : (BigDecimal) line[5];
        boolean returnLine = Boolean.TRUE.equals(line[6]);
        boolean deposit = Boolean.TRUE.equals(line[7]);
        if (eans.contains(line[3])) {
            return total.signum() >= 0
                    ? row(line[0], line[1], line[2], CONSIGNE, line[3], line[4], total, BigDecimal.ZERO)
                    : row(line[0], line[1], line[2], ARTICLE_BACK, line[3], line[4], BigDecimal.ZERO, total.negate());
        }
        if (returnLine && typeCodes.contains(line[8])) {
            return row(line[0], line[1], line[2], TYPED_RETURN, line[8], line[9], BigDecimal.ZERO, total.negate());
        }
        if (deposit && !returnLine) {
            return row(line[0], line[1], line[2], VOUCHER, line[3], line[4], BigDecimal.ZERO, total.negate());
        }
        return null;
    }

    /**
     * Builds one row, an absent text shown empty.
     *
     * @param when the date of the document
     * @param terminal the register, or null
     * @param number the document number, or null
     * @param nature the nature of the movement
     * @param code the type code or the article code, or null
     * @param label the type or article label, or null
     * @param consigne the deposit charged
     * @param deconsigne the deposit given back
     * @return the row
     */
    private static List<Object> row(Object when, Object terminal, Object number, String nature,
                                    Object code, Object label, BigDecimal consigne, BigDecimal deconsigne) {
        return Arrays.asList((LocalDateTime) when, text(terminal), text(number), nature,
                text(code), text(label), consigne, deconsigne);
    }

    /**
     * Renders an absent text as empty.
     *
     * @param value the value, possibly null
     * @return the value, or the empty string
     */
    private static Object text(Object value) {
        return value == null ? "" : value;
    }

    /**
     * The absolute value of an amount, an absent one being zero.
     *
     * @param amount the amount, possibly null
     * @return its absolute value
     */
    private static BigDecimal abs(Object amount) {
        return amount == null ? BigDecimal.ZERO : ((BigDecimal) amount).abs();
    }
}
