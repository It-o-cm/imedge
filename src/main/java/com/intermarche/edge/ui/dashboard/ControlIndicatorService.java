package com.intermarche.edge.ui.dashboard;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The real-time CONTROL INDICATORS of the point of sale (BO-04-03-01): the
 * day's revenue, its detail by department or family at the administered
 * level of the nomenclature, the average basket as a decimal quantity of
 * articles, the number of customers served, and the comparison with the same
 * moment of the same day a year before, in euros and in percent.
 * <p>
 * Shown on every back-office screen to the profiles granted the
 * {@code CONTROL_INDICATORS} feature, and on a page of their own.
 */
@ApplicationScoped
public class ControlIndicatorService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ControlIndicatorService.class);

    @Inject
    EntityManager entityManager;

    @Inject
    PosSettingsService posSettingsService;

    /**
     * Computes the indicators now.
     *
     * @return the formatted indicators; {@code enabled} false when the store
     *         switched the bar off
     */
    public Map<String, Object> data() {
        LOGGER.info("Entering method data");
        return data(LocalDateTime.now());
    }

    /**
     * Computes the indicators at a given moment.
     *
     * @param now the moment the day is read up to
     * @return the formatted indicators
     */
    Map<String, Object> data(LocalDateTime now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", posSettingsService.controlIndicatorsBar());
        LocalDateTime from = now.toLocalDate().atStartOfDay();
        Object[] day = (Object[]) entityManager.createQuery(
                        "select coalesce(sum(t.totalIncludingTax), 0), count(t) from Ticket t"
                                + " where t.status = :status and t.closingDate >= :from and t.closingDate <= :to")
                .setParameter("status", Ticket.TicketStatus.CLOSED)
                .setParameter("from", from)
                .setParameter("to", now)
                .getSingleResult();
        BigDecimal revenue = (BigDecimal) day[0];
        long customers = (Long) day[1];
        BigDecimal items = entityManager.createQuery(
                        "select coalesce(sum(l.quantity), 0) from Ticket t join t.lines l"
                                + " where t.status = :status and t.closingDate >= :from and t.closingDate <= :to"
                                + " and l.cancelled = false and l.totalPrice > 0", BigDecimal.class)
                .setParameter("status", Ticket.TicketStatus.CLOSED)
                .setParameter("from", from)
                .setParameter("to", now)
                .getSingleResult();
        BigDecimal lastYear = entityManager.createQuery(
                        "select coalesce(sum(t.totalIncludingTax), 0) from Ticket t"
                                + " where t.status = :status and t.closingDate >= :from and t.closingDate <= :to",
                        BigDecimal.class)
                .setParameter("status", Ticket.TicketStatus.CLOSED)
                .setParameter("from", from.minusYears(1))
                .setParameter("to", now.minusYears(1))
                .getSingleResult();
        data.put("revenue", euros(revenue));
        data.put("customers", customers);
        data.put("basketQuantity", customers == 0 ? "0,00"
                : items.divide(BigDecimal.valueOf(customers), 2, RoundingMode.HALF_UP).toPlainString().replace('.', ','));
        BigDecimal difference = revenue.subtract(lastYear);
        data.put("lastYearRevenue", euros(lastYear));
        data.put("difference", euros(difference));
        data.put("differencePercent", lastYear.signum() == 0 ? "—"
                : difference.multiply(BigDecimal.valueOf(100)).divide(lastYear, 1, RoundingMode.HALF_UP)
                        .toPlainString().replace('.', ','));
        data.put("detail", detail(from, now));
        LOGGER.info("Exiting method data");
        return data;
    }

    /**
     * The day's revenue by department or family (BO-04-03-01): grouped on the
     * family code of the sold lines cut to the code length of the administered
     * nomenclature level where the cut names a node, the strongest first, as
     * many as administered.
     *
     * @param from the start of the day
     * @param now the moment the day is read up to
     * @return the rows: code, label and revenue
     */
    List<Map<String, Object>> detail(LocalDateTime from, LocalDateTime now) {
        int length = codeLength(posSettingsService.controlRevenueLevel());
        var query = entityManager.createQuery(
                        "select l.familyCode, sum(l.totalPrice) from Ticket t join t.lines l"
                                + " where t.status = :status and t.closingDate >= :from and t.closingDate <= :to"
                                + " and l.cancelled = false and l.familyCode is not null"
                                + " group by l.familyCode order by sum(l.totalPrice) desc", Object[].class)
                .setParameter("status", Ticket.TicketStatus.CLOSED)
                .setParameter("from", from)
                .setParameter("to", now);
        List<Object[]> rows = query.getResultList();
        List<String> codes = new ArrayList<>();
        for (Object[] row : rows) {
            String code = (String) row[0];
            codes.add(code);
            if (length > 0 && code.length() > length) {
                codes.add(code.substring(0, length));
            }
        }
        Map<String, String> labels = labels(codes);
        // A family code is cut to the administered level only where the cut
        // names a node: a family coded outside the positional nomenclature
        // (EPICERIE) keeps its own code and label rather than a stump (EPIC).
        Map<String, BigDecimal> sums = new java.util.LinkedHashMap<>();
        for (Object[] row : rows) {
            String code = (String) row[0];
            String key = code;
            if (length > 0 && code.length() > length) {
                String cut = code.substring(0, length);
                key = labels.containsKey(cut) || !labels.containsKey(code) ? cut : code;
            }
            sums.merge(key, row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1], BigDecimal::add);
        }
        List<Map.Entry<String, BigDecimal>> ranked = new ArrayList<>(sums.entrySet());
        ranked.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> sum : ranked.subList(0, Math.min(ranked.size(),
                Math.max(0, posSettingsService.controlRevenueTop())))) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("code", sum.getKey());
            entry.put("label", labels.getOrDefault(sum.getKey(), sum.getKey()));
            entry.put("revenue", euros(sum.getValue()));
            detail.add(entry);
        }
        return detail;
    }

    /**
     * The code length of a nomenclature level, read on the first nomenclature
     * that declares that rank.
     *
     * @param rank the level's rank, 0 or less for the family as sold
     * @return the code length, 0 when the rank is not declared
     */
    int codeLength(int rank) {
        if (rank <= 0) {
            return 0;
        }
        List<Integer> lengths = entityManager.createQuery(
                        "select n.codeLength from NomenclatureLevel n where n.rank = :rank order by n.id",
                        Integer.class)
                .setParameter("rank", rank)
                .setMaxResults(1)
                .getResultList();
        return lengths.isEmpty() ? 0 : lengths.get(0);
    }

    /**
     * The labels of the nomenclature nodes the detail names.
     *
     * @param codes the codes
     * @return the labels by code
     */
    Map<String, String> labels(List<String> codes) {
        Map<String, String> labels = new HashMap<>();
        if (codes.isEmpty()) {
            return labels;
        }
        for (Object[] row : entityManager.createQuery(
                        "select f.code, f.description from ProductFamily f where f.code in :codes", Object[].class)
                .setParameter("codes", codes)
                .getResultList()) {
            if (row[1] != null) {
                labels.put((String) row[0], (String) row[1]);
            }
        }
        return labels;
    }

    /**
     * Writes an amount the French way.
     *
     * @param amount the amount
     * @return two decimals, a decimal comma
     */
    static String euros(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
