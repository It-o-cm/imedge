package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ÉTAT TVA PAR NOMENCLATURE (BO-06-07-01): per node of the nomenclature and per
 * VAT rate — code and label of the rate — the net sales, the quantity sold and
 * the tax, broken by RAYON (code and label, then a subtotal).
 *
 * <p>The LEVEL is chosen — activities, rayons, families, sous-familles, or the
 * family each line was sold under — and the scope with the family criteria of
 * the frame (a code keeps itself and everything below it). « Il doit être
 * possible d'éditer uniquement la synthèse »: the SYNTHESIS option edits one
 * row per rate, the declaration figures, without the nomenclature.
 *
 * <p>The fuel station's turnover is EXCLUDED whenever the shop names its
 * station families ({@code report.station-families}): a station's sales are
 * declared apart. Virtual lots are counted article by article, as the register
 * rings them: each component of a lot is a line of its own, with its own family
 * and its own rate, so the lot's tax falls where it belongs.
 *
 * <p>The tax is DERIVED from the inclusive total and the rate carried by each
 * line, rounded at the BUCKET and not at the line: the tax of a hundred lines at
 * 5,5 % is the tax of their sum, which is the rule a fiscal statement is read
 * against.
 */
@ApplicationScoped
public class VatByFamilyReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(VatByFamilyReport.class);

    /** The option value editing the synthesis only. */
    static final String SYNTHESIS = "SYNTHESIS";

    /** The option value editing the family each line was sold under. */
    static final String SOLD = "SOLD";

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /** The parameters, for the station families. */
    @Inject PosSettingsService posSettingsService;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "vat-by-family";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État TVA par nomenclature";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-07-01";
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
                ReportColumn.text("rayon", "Code rayon"),
                ReportColumn.text("rayonLabel", "Libellé du rayon"),
                ReportColumn.text("code", "Code"),
                ReportColumn.text("label", "Libellé"),
                ReportColumn.text("vatCode", "Code du taux de TVA"),
                ReportColumn.text("vatLabel", "Libellé du taux de TVA"),
                ReportColumn.percent("rate", "Taux de TVA"),
                ReportColumn.amount("including", "Ventes nettes"),
                ReportColumn.count("quantity", "Quantité vendue"),
                ReportColumn.amount("excluding", "Montant HT"),
                ReportColumn.amount("vat", "Montant TVA"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the net sales
     */
    @Override
    public int chartColumn() {
        return 7;
    }

    /**
     * {@inheritDoc}
     *
     * @return the node's label
     */
    @Override
    public int chartLabelColumn() {
        return 3;
    }

    /**
     * {@inheritDoc}
     *
     * @return the rayon: a subtotal closes each rayon
     */
    @Override
    public String breakKey() {
        return "rayon";
    }

    /**
     * {@inheritDoc}
     *
     * @return what the level option is called on the screen
     */
    @Override
    public String optionLabel() {
        return "Niveau";
    }

    /**
     * {@inheritDoc}
     *
     * @return the family sold, the four levels and the synthesis
     */
    @Override
    public List<String> optionChoices() {
        return List.of(SOLD + ":Famille de vente", "0:Activités", "1:Rayons", "2:Familles",
                "3:Sous-familles", SYNTHESIS + ":Synthèse par taux uniquement");
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers, the island and the article criteria
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.ARTICLES);
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return the stated level and the station exclusion
     */
    @Override
    public List<String> preamble(ReportCriteria criteria) {
        List<String> stations = posSettingsService.reportStationFamilies();
        return stations.isEmpty() ? List.of()
                : List.of("Chiffre d'affaires de la station exclu : " + String.join(", ", stations));
    }

    /** One bucket: a node and a rate. */
    static final class Bucket {

        /** The rayon. */
        String rayon;

        /** The node. */
        String code;

        /** The line's own family label, for a node without label. */
        String label;

        /** The rate. */
        BigDecimal rate;

        /** The regime number. */
        Integer number;

        /** The inclusive total. */
        BigDecimal including = BigDecimal.ZERO;

        /** The quantity. */
        BigDecimal quantity = BigDecimal.ZERO;
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per node and rate, by rayon, node then rate
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        String option = criteria.optionOr(SOLD);
        boolean synthesis = SYNTHESIS.equals(option);
        int level = level(option);
        NomenclatureLookup nomenclature = NomenclatureLookup.current();
        int rayonRank = nomenclature.rayonRank();
        List<String> stations = posSettingsService.reportStationFamilies();
        var query = entityManager.createQuery(
                        "select l.familyCode, l.familyLabel, l.vatRate, l.totalPrice, l.quantity, l.vatNumber,"
                                + " t.closingDate from Ticket t join t.lines l"
                                + " where t.status = :closed and t.closingDate >= :from"
                                + " and t.closingDate < :to and l.cancelled = false"
                                // BO-02-03-41: what is sold on behalf of a third party
                                // is the third party's VAT, not the store's.
                                + " and l.thirdPartySale = false"
                                + criteria.terminalFilter("t.terminalId") + criteria.lineFilter("l"), Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        criteria.bindLines(query);
        Map<String, Bucket> buckets = new TreeMap<>();
        for (Object[] line : query.getResultList()) {
            String family = line[0] == null ? "—" : (String) line[0];
            if (!criteria.inHours((LocalDateTime) line[6]) || station(family, stations)) {
                continue;
            }
            BigDecimal rate = line[2] == null ? BigDecimal.ZERO : (BigDecimal) line[2];
            String rayon = synthesis ? "Synthèse" : line[0] == null ? "—" : nomenclature.ancestor(family, rayonRank);
            String node = synthesis ? "" : level < 0 || line[0] == null ? family : nomenclature.ancestor(family, level);
            String key = rayon + "\u0000" + node + "\u0000" + rate.toPlainString() + "\u0000" + line[5];
            Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
            bucket.rayon = rayon;
            bucket.code = node;
            bucket.label = level < 0 && line[1] != null ? (String) line[1] : null;
            bucket.rate = rate;
            bucket.number = (Integer) line[5];
            bucket.including = bucket.including.add(line[3] == null ? BigDecimal.ZERO : (BigDecimal) line[3]);
            bucket.quantity = bucket.quantity.add(line[4] == null ? BigDecimal.ZERO : (BigDecimal) line[4]);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Bucket bucket : buckets.values()) {
            BigDecimal excluding = excluding(bucket.including, bucket.rate);
            VatRate regime = VatRate.resolve(bucket.rate, bucket.number);
            rows.add(Arrays.asList(
                    bucket.rayon,
                    synthesis ? "" : nomenclature.label(bucket.rayon),
                    bucket.code,
                    bucket.label != null ? bucket.label : nomenclature.label(bucket.code),
                    regime == null || regime.number == null ? "" : String.valueOf(regime.number),
                    regime == null || regime.label == null ? "" : regime.label,
                    bucket.rate.multiply(BigDecimal.valueOf(100)),
                    bucket.including,
                    bucket.quantity.setScale(0, RoundingMode.HALF_UP).longValue(),
                    excluding,
                    bucket.including.subtract(excluding)));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Reads the chosen nomenclature level.
     *
     * @param option the option
     * @return the 0-based level, or -1 for the family sold and the synthesis
     */
    static int level(String option) {
        return switch (option) {
            case "0", "1", "2", "3" -> Integer.parseInt(option);
            default -> -1;
        };
    }

    /**
     * Whether a family belongs to the fuel station.
     *
     * @param family the family code
     * @param stations the station codes
     * @return true when the family is a station code or lies below one
     */
    static boolean station(String family, List<String> stations) {
        for (String code : stations) {
            if (family.startsWith(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the tax from an inclusive amount.
     *
     * @param including the amount including tax
     * @param rate the rate, as a fraction of one
     * @return the amount excluding tax, at two decimals
     */
    private BigDecimal excluding(BigDecimal including, BigDecimal rate) {
        BigDecimal divisor = BigDecimal.ONE.add(rate);
        if (divisor.signum() == 0) {
            // A rate of exactly minus one would divide by zero. It cannot come
            // from a catalogue, but a report reads what the database holds, not
            // what the catalogue should have written.
            return including;
        }
        return including.divide(divisor, 2, RoundingMode.HALF_UP);
    }
}
