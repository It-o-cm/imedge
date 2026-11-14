package com.intermarche.edge.service;

import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.setting.TouchGroupSetting;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the SALES VOLUME of the articles and of the groups of articles
 * from the sales the shop's registers pushed (BO-03-01-13).
 *
 * <p>The direct-entry groups of the registers can be ordered best seller
 * first; the order is only as good as the volume it reads, and a volume typed
 * by hand in the back office is a guess. This node holds every closed ticket
 * of every register of the shop, so it is the one place where the volume can
 * be COUNTED: the quantity sold of each article over the administered window
 * ({@code touch.sales-volume-days}), cancelled and returned lines left out.
 *
 * <p>The article's volume travels with the article in the PRODUCTS domain; a
 * group's volume — the sum of its articles' and of its sub-groups' — is
 * written on its touch configuration and travels in the TOUCH domain. Both
 * are computed once per end of period and on demand from the touch screen:
 * a volume that moved at every sale would make every register pull the whole
 * catalogue at every sale.
 */
@ApplicationScoped
public class SalesVolumeService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SalesVolumeService.class);

    /** The administered window. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Recomputes every article's and every group's sales volume over the
     * administered window ending on a day.
     *
     * @param today the last day of the window
     * @return the number of articles sold over the window
     */
    @Transactional
    public int refresh(LocalDate today) {
        LOGGER.info("Entering method refresh with today: " + today);
        List<Object[]> rows = Panache.getEntityManager().createQuery(
                "select l.ean, sum(l.quantity) from Ticket t join t.lines l"
                        + " where t.status = ?1 and t.closingDate >= ?2 and l.cancelled = false"
                        + " and l.returnLine = false and l.ean is not null group by l.ean", Object[].class)
                .setParameter(1, Ticket.TicketStatus.CLOSED)
                .setParameter(2, today.minusDays(posSettingsService.touchSalesVolumeDays() - 1L).atStartOfDay())
                .getResultList();
        Map<String, Long> volumes = new HashMap<>();
        for (Object[] row : rows) {
            BigDecimal quantity = (BigDecimal) row[1];
            volumes.put((String) row[0], quantity == null ? 0L
                    : quantity.setScale(0, RoundingMode.HALF_UP).longValue());
        }
        Product.update("salesVolume = 0 where salesVolume <> 0");
        for (Map.Entry<String, Long> volume : volumes.entrySet()) {
            Product.update("salesVolume = ?1 where ean = ?2", volume.getValue(), volume.getKey());
        }
        for (ProductFamily group : ProductFamily.<ProductFamily>listAll()) {
            writeGroupVolume(group, groupVolume(group, volumes));
        }
        LOGGER.info("Exiting method refresh (" + volumes.size() + " article(s))");
        return volumes.size();
    }

    /**
     * The volume of a group: its articles' and those of every group under it,
     * each article and each group counted once however many paths lead to it.
     *
     * @param group the group
     * @param volumes the articles' volumes, by EAN
     * @return the group's volume
     */
    private long groupVolume(ProductFamily group, Map<String, Long> volumes) {
        long total = 0L;
        Set<ProductFamily> walked = new HashSet<>();
        Set<String> counted = new HashSet<>();
        Deque<ProductFamily> pending = new ArrayDeque<>(List.of(group));
        while (!pending.isEmpty()) {
            ProductFamily current = pending.pop();
            if (!walked.add(current)) {
                continue;
            }
            for (Product product : current.products) {
                if (counted.add(product.ean)) {
                    total += volumes.getOrDefault(product.ean, 0L);
                }
            }
            pending.addAll(current.productFamilies);
        }
        return total;
    }

    /**
     * Writes a group's volume on its touch configuration, creating the
     * configuration — with every other value at its default — only when there
     * is a volume to carry.
     *
     * @param group the group
     * @param volume its volume
     */
    private void writeGroupVolume(ProductFamily group, long volume) {
        TouchGroupSetting setting = TouchGroupSetting.findByFamilyCode(group.code);
        if (setting != null) {
            setting.salesVolume = volume;
            return;
        }
        if (volume > 0) {
            TouchGroupSetting created = TouchGroupSetting.defaults(group.code);
            created.salesVolume = volume;
            created.persist();
        }
    }
}
