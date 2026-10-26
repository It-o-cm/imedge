package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.payment.FidelityPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.sale.SalesChannel;
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
import java.util.Set;

/**
 * ÉTAT DE TVA DU RÈGLEMENT FIDÉLITÉ ({@code BO-06-07-02}): per VAT code, the
 * sales and their tax beside the share of them settled by the loyalty purse,
 * with its own base and tax, and the two settlements the requirement compares:
 * the store's decagnottage (« Remise fidélité », the purse spent at the till)
 * and the site's (« ECom Fid Site », the purse spent on a drive or site order,
 * the tickets the e-commerce channel brought).
 *
 * <p>THE LOYALTY SETTLEMENT IS APPORTIONED, because it has no VAT code of its
 * own: a purse pays a basket, not a rate. It is split across the codes of the
 * ticket it settled, pro rata of what each code weighs in that ticket's total
 * including tax — the only split that keeps the sum of the parts equal to the
 * amount actually settled. A ticket settled by no purse contributes to the
 * sales columns and nothing to the loyalty ones.
 *
 * <p>Two levels of detail: the SUMMARY, one row per code, and the detail PER
 * TRANSACTION, one row per ticket and code, which the transaction range — from
 * one ticket number to another — narrows to the tickets to check. The frame's
 * total row closes both, every column summed.
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

        /** The ticket of a detailed row, empty in the summary. */
        private String ticket = "";

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

        /** The share settled by the purse at the till, including tax. */
        private BigDecimal store = BigDecimal.ZERO;

        /** The share settled by the purse on the site, including tax. */
        private BigDecimal site = BigDecimal.ZERO;

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
     * @return the columns the requirement enumerates, then the two settlements compared
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("ticket", "N° transaction"),
                ReportColumn.text("code", "Code TVA"),
                ReportColumn.text("label", "Libellé du code TVA"),
                ReportColumn.amount("salesTtc", "Ventes TTC"),
                ReportColumn.amount("vat", "Montant TVA"),
                ReportColumn.amount("salesHt", "Ventes HT"),
                ReportColumn.amount("fidelityHt", "Règlement fidélité HT"),
                ReportColumn.amount("fidelityTtc", "Règlement fidélité TTC"),
                ReportColumn.amount("fidelityVat", "TVA fidélité"),
                ReportColumn.amount("store", "Remise fidélité (magasin) TTC"),
                ReportColumn.amount("site", "ECom Fid Site TTC"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the loyalty settlement including tax
     */
    @Override
    public int chartColumn() {
        return 7;
    }

    /**
     * {@inheritDoc}
     *
     * @return the code's label
     */
    @Override
    public int chartLabelColumn() {
        return 2;
    }

    /**
     * {@inheritDoc}
     *
     * @return what the detail option is called on the screen
     */
    @Override
    public String optionLabel() {
        return "Niveau de détail";
    }

    /**
     * {@inheritDoc}
     *
     * @return the summary and the detail per transaction
     */
    @Override
    public List<String> optionChoices() {
        return List.of("SUMMARY:Résumé", "DETAIL:Par transaction");
    }

    /**
     * {@inheritDoc}
     *
     * @return what the transaction range is called on the screen
     */
    @Override
    public String boundLabel() {
        return "Tranche de transactions (n° de ticket)";
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers and the island
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND);
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per VAT code, or per ticket and code in the detail
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        boolean detailed = criteria.detailed();
        Map<String, Row> rowsByKey = new LinkedHashMap<>();
        for (Ticket ticket : tickets(criteria)) {
            if (criteria.withinBounds(ticket.ticketNumber)) {
                accumulate(rowsByKey, ticket, detailed);
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Row row : rowsByKey.values()) {
            rows.add(render(row));
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
        // BO-03-09-01: the registers chosen, and the deactivated ones left out.
        jpql.append(criteria.terminalFilter("t.terminalId"));
        jpql.append(" order by t.closingDate");
        var query = entityManager.createQuery(jpql.toString(), Ticket.class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        return query.getResultList();
    }

    /**
     * Adds one ticket to the running totals, apportioning its loyalty
     * settlement across the VAT codes it carries.
     *
     * @param rows the running totals, keyed by ticket and rate
     * @param ticket the closed ticket
     * @param detailed whether each ticket keeps rows of its own
     */
    private void accumulate(Map<String, Row> rows, Ticket ticket, boolean detailed) {
        VatBreakdown breakdown = new VatBreakdown();
        for (TicketLine line : ticket.lines) {
            if (!line.cancelled) {
                breakdown.add(line.vatRate, line.totalPrice);
            }
        }
        BigDecimal settled = fidelitySettlement(ticket);
        BigDecimal basis = breakdown.getTotalIncludingTax();
        boolean site = ticket.salesChannel == SalesChannel.ECOMMERCE;
        String prefix = detailed ? ticket.ticketNumber : "";
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            Row row = rows.computeIfAbsent(prefix + "\u0000" + bucket.rate, key -> describe(bucket.rate, prefix));
            row.salesIncludingTax = row.salesIncludingTax.add(bucket.totalIncludingTax);
            row.salesExcludingTax = row.salesExcludingTax.add(bucket.totalExcludingTax);
            row.vat = row.vat.add(bucket.vatAmount);
            if (settled.signum() > 0 && basis.signum() > 0) {
                BigDecimal share = settled.multiply(bucket.totalIncludingTax).divide(basis, MONEY, RoundingMode.HALF_UP);
                if (site) {
                    row.site = row.site.add(share);
                } else {
                    row.store = row.store.add(share);
                }
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
     * @param ticket the ticket of a detailed row, empty in the summary
     * @return the row, with its code and label filled
     */
    private Row describe(BigDecimal rate, String ticket) {
        Row row = new Row();
        row.ticket = ticket == null ? "" : ticket;
        VatRate regime = rate == null ? null : VatRate.findByRate(rate);
        if (regime != null) {
            row.code = regime.number == null ? "" : String.valueOf(regime.number);
            row.label = regime.label == null ? "" : regime.label;
        }
        if (row.label.isEmpty() && rate != null) {
            row.label = rate.multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " %";
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
        BigDecimal fidelityIncludingTax = row.store.add(row.site);
        BigDecimal fidelityExcludingTax = row.salesIncludingTax.signum() == 0
                ? BigDecimal.ZERO
                : fidelityIncludingTax.multiply(row.salesExcludingTax)
                        .divide(row.salesIncludingTax, MONEY, RoundingMode.HALF_UP);
        return Arrays.asList(row.ticket, row.code, row.label,
                row.salesIncludingTax, row.vat, row.salesExcludingTax,
                fidelityExcludingTax, fidelityIncludingTax,
                fidelityIncludingTax.subtract(fidelityExcludingTax), row.store, row.site);
    }
}
