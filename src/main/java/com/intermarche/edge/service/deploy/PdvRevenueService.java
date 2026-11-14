package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.PdvRevenue;
import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The REVENUE of the points of sale, carried up the report cycle
 * (BO-02-05-05): a store node states the revenue of its last days in its
 * report, and the central node files it per point of sale and per day, which
 * is what its report of the revenue by point of sale and by adhérent reads.
 */
@ApplicationScoped
public class PdvRevenueService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PdvRevenueService.class);

    /** How many days, today included, a store restates at every report. */
    static final int DAYS_REPORTED = 7;

    /** The persistence session the sums run on. */
    @Inject
    EntityManager entityManager;

    /**
     * Sums this store's closed sales of its last days, day by day, today
     * included.
     *
     * @param today the reference day
     * @return one entry per day, oldest first
     */
    public List<DeployPayloads.DayRevenue> dayRevenues(LocalDate today) {
        LOGGER.info("Entering method dayRevenues with today: " + today);
        List<DeployPayloads.DayRevenue> revenues = new ArrayList<>();
        for (int back = DAYS_REPORTED - 1; back >= 0; back--) {
            LocalDate day = today.minusDays(back);
            Object[] sum = entityManager.createQuery(
                            "select coalesce(sum(t.totalIncludingTax), 0), count(t)"
                                    + " from com.intermarche.edge.domain.sale.Ticket t"
                                    + " where t.status = :status and t.closingDate >= :from and t.closingDate < :to",
                            Object[].class)
                    .setParameter("status", Ticket.TicketStatus.CLOSED)
                    .setParameter("from", day.atStartOfDay())
                    .setParameter("to", day.plusDays(1).atStartOfDay())
                    .getSingleResult();
            DeployPayloads.DayRevenue revenue = new DeployPayloads.DayRevenue();
            revenue.day = day.toString();
            revenue.revenue = (BigDecimal) sum[0];
            revenue.tickets = ((Number) sum[1]).longValue();
            revenues.add(revenue);
        }
        LOGGER.info("Exiting method dayRevenues");
        return revenues;
    }

    /**
     * Files the revenue a store reported for its point of sale, a day already
     * filed being restated.
     *
     * @param pdvNumber the point of sale of the store, possibly null
     * @param revenues the days it reported, possibly null
     * @return how many days were filed
     */
    @Transactional
    public int record(String pdvNumber, List<DeployPayloads.DayRevenue> revenues) {
        LOGGER.info("Entering method record with pdvNumber: " + pdvNumber);
        if (pdvNumber == null || revenues == null) {
            LOGGER.info("Exiting method record: nothing to file");
            return 0;
        }
        int filed = 0;
        for (DeployPayloads.DayRevenue revenue : revenues) {
            LocalDate day = day(revenue.day);
            if (day == null) {
                continue;
            }
            PdvRevenue row = PdvRevenue.findDay(pdvNumber, day);
            boolean created = row == null;
            if (created) {
                row = new PdvRevenue();
                row.pdvNumber = pdvNumber;
                row.day = day;
            }
            row.revenue = revenue.revenue == null ? BigDecimal.ZERO : revenue.revenue;
            row.tickets = revenue.tickets;
            row.receivedAt = LocalDateTime.now();
            if (created) {
                row.persist();
            }
            filed++;
        }
        LOGGER.info("Exiting method record");
        return filed;
    }

    /**
     * Reads an ISO day, an unreadable one counting as none.
     *
     * @param value the day, possibly null
     * @return the day, or null
     */
    private LocalDate day(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException unreadable) {
            return null;
        }
    }
}
