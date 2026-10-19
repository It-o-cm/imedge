package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.payment.FidelityPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sale.VatBreakdown;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ÉTAT DE TVA DU RÈGLEMENT FIDÉLITÉ ({@code BO-06-07-02}): per VAT code, the
 * sales and their tax beside the share of them settled by the loyalty purse,
 * with its own base and tax.
 *
 * <p>THE LOYALTY SETTLEMENT IS APPORTIONED, because it has no VAT code of its
 * own: a purse pays a basket, not a rate. It is split across the codes of the
 * ticket it settled, pro rata of what each code weighs in that ticket's total
 * including tax — the only split that keeps the sum of the parts equal to the
 * amount actually settled, which is what an accountant reconciles against.
 * A ticket settled by no purse contributes to the sales columns and nothing
 * to the loyalty ones.
 *
 * <p>The TOTAL row is produced by the report and not by the screen, because
 * the requirement asks for a total on EVERY column and a percentage column
 * cannot be summed — there is none here for that reason.
 *
 * <p>What is NOT here: the per-transaction detail level and the comparison
 * with the e-commerce loyalty settlement. The first is the same figures at a
 * finer grain and will come with the transaction selector; the second names a
 * channel this suite does not yet integrate.
 */
@ApplicationScoped
public class FidelityVatReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FidelityVatReport.class);

    /** The scale every apportioned amount is rounded to. */
    private static final int MONEY = 2;

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * One VAT code's running totals.
     */
    private static final class Row {

        /** The regime's code, or an empty string when none carries the rate. */
        private String code = "";

        /** The regime's label, or an empty string. */
        private String label = "";

        /** Sales including tax. */
        private BigDecimal salesIncludingTax = BigDecimal.ZERO;

        /** Sales excluding tax. */
        private BigDecimal salesExcludingTax = BigDecimal.ZERO;

        /** The tax on those sales. */
        private BigDecimal vat = BigDecimal.ZERO;

        /** The share of them settled by the loyalty purse, including tax. */
        private BigDecimal fidelityIncludingTax = BigDecimal.ZERO;

        /**
         * Builds an empty row.
         */
        private Row() {
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "fidelity-vat";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "TVA du règlement fidélité";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-07-02";
    }

    /**
     * {@inheritDoc}
     *
     * @return the tax family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.TAX;
    }

    /**
     * {@inheritDoc}
     *
     * @return the columns the requirement enumerates
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("code", "Code TVA"),
                ReportColumn.text("label", "Libellé du code TVA"),
                ReportColumn.amount("salesTtc", "Ventes TTC"),
                ReportColumn.amount("vat", "Montant TVA"),
                ReportColumn.amount("salesHt", "Ventes HT"),
                ReportColumn.amount("fidelityHt", "Règlement fidélité HT"),
                ReportColumn.amount("fidelityTtc", "Règlement fidélité TTC"),
                ReportColumn.amount("fidelityVat", "TVA fidélité"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per VAT code, then a total row
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        Map<BigDecimal, Row> byRate = new LinkedHashMap<>();
        for (Ticket ticket : tickets(criteria)) {
            accumulate(byRate, ticket);
        }
        List<List<Object>> rows = new ArrayList<>();
        Row total = new Row();
        total.code = "";
        total.label = "Total";
        for (Row row : byRate.values()) {
            rows.add(render(row));
            total.salesIncludingTax = total.salesIncludingTax.add(row.salesIncludingTax);
            total.salesExcludingTax = total.salesExcludingTax.add(row.salesExcludingTax);
            total.vat = total.vat.add(row.vat);
            total.fidelityIncludingTax = total.fidelityIncludingTax.add(row.fidelityIncludingTax);
        }
        if (!rows.isEmpty()) {
            rows.add(render(total));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * The closed tickets of the edition period, filtered on the registers the
     * operator selected.
     *
     * @param criteria the edition parameters
     * @return the tickets, oldest first
     */
    private List<Ticket> tickets(ReportCriteria criteria) {
        StringBuilder jpql = new StringBuilder(
                "select t from Ticket t where t.status = :closed"
                        + " and t.closingDate >= :from and t.closingDate < :to");
        if (!criteria.terminals.isEmpty()) {
            jpql.append(" and t.terminalId in :terminals");
        }
        jpql.append(" order by t.closingDate");
        var query = entityManager.createQuery(jpql.toString(), Ticket.class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (!criteria.terminals.isEmpty()) {
            query.setParameter("terminals", criteria.terminals);
        }
        return query.getResultList();
    }

    /**
     * Adds one ticket to the running totals, apportioning its loyalty
     * settlement across the VAT codes it carries.
     *
     * @param byRate the running totals, keyed by rate
     * @param ticket the closed ticket
     */
    private void accumulate(Map<BigDecimal, Row> byRate, Ticket ticket) {
        VatBreakdown breakdown = new VatBreakdown();
        for (TicketLine line : ticket.lines) {
            if (!line.cancelled) {
                breakdown.add(line.vatRate, line.totalPrice);
            }
        }
        BigDecimal settled = fidelitySettlement(ticket);
        BigDecimal basis = breakdown.getTotalIncludingTax();
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            Row row = byRate.computeIfAbsent(bucket.rate, rate -> describe(rate));
            row.salesIncludingTax = row.salesIncludingTax.add(bucket.totalIncludingTax);
            row.salesExcludingTax = row.salesExcludingTax.add(bucket.totalExcludingTax);
            row.vat = row.vat.add(bucket.vatAmount);
            if (settled.signum() > 0 && basis.signum() > 0) {
                row.fidelityIncludingTax = row.fidelityIncludingTax.add(
                        settled.multiply(bucket.totalIncludingTax)
                                .divide(basis, MONEY, RoundingMode.HALF_UP));
            }
        }
    }

    /**
     * What the loyalty purse settled on a ticket.
     *
     * @param ticket the closed ticket
     * @return the settled amount, zero when no purse paid
     */
    private BigDecimal fidelitySettlement(Ticket ticket) {
        BigDecimal settled = BigDecimal.ZERO;
        for (TicketPayment payment : ticket.payments) {
            if (payment instanceof FidelityPayment && payment.amount != null) {
                settled = settled.add(payment.amount);
            }
        }
        return settled;
    }

    /**
     * Opens a row for a rate, naming the regime that carries it.
     *
     * @param rate the rate the lines were taxed at
     * @return the row, with its code and label filled
     */
    private Row describe(BigDecimal rate) {
        Row row = new Row();
        VatRate regime = rate == null ? null : VatRate.findByRate(rate);
        if (regime != null) {
            row.code = regime.number == null ? "" : String.valueOf(regime.number);
            row.label = regime.label == null ? "" : regime.label;
        }
        if (row.label.isEmpty() && rate != null) {
            row.label = rate.multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP).toPlainString() + " %";
        }
        return row;
    }

    /**
     * Renders one row, deriving the loyalty base and tax from its settled
     * amount including tax and the rate of the code it belongs to.
     *
     * @param row the accumulated row
     * @return the printable cells
     */
    private List<Object> render(Row row) {
        BigDecimal fidelityExcludingTax = row.salesIncludingTax.signum() == 0
                ? BigDecimal.ZERO
                : row.fidelityIncludingTax.multiply(row.salesExcludingTax)
                        .divide(row.salesIncludingTax, MONEY, RoundingMode.HALF_UP);
        return Arrays.asList(row.code, row.label,
                row.salesIncludingTax, row.vat, row.salesExcludingTax,
                fidelityExcludingTax, row.fidelityIncludingTax,
                row.fidelityIncludingTax.subtract(fidelityExcludingTax));
    }
}
