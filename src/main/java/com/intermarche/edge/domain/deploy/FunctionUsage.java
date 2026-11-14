package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.List;

/**
 * How many times one FUNCTION was used on one node on one day
 * (BO-08-02-02).
 *
 * <p>A COUNTER, not a journal: the statistics ask how often a function is
 * used, by point of sale and enseigne, not who pressed it at 10:42. One row
 * per day, node, channel and function, incremented in place — a register
 * that sells all day writes a few dozen rows, not thousands.
 *
 * <p>The register counts its own functions, the store node its back-office
 * functions; both travel up with the node reports, and the receiving node
 * REPLACES the count it holds for the same key, so a report sent twice counts
 * once.
 */
@Entity
@Table(name = "function_usage",
        indexes = @Index(name = "idx_function_usage_key",
                columnList = "usage_day, node_id, channel, function_code"))
public class FunctionUsage extends PanacheEntity {

    /** The register channel: a function of the selling screen. */
    public static final String REGISTER = "CAISSE";

    /** The back-office channel: a screen gesture of the back office. */
    public static final String BACK_OFFICE = "BACKOFFICE";

    /** The day counted. */
    @Column(name = "usage_day", nullable = false)
    public LocalDate usageDay;

    /** The node the function was used on. */
    @Column(name = "node_id", nullable = false, length = 40)
    public String nodeId;

    /** The point of sale of that node, null when unknown. */
    @Column(name = "pdv_number", length = 5)
    public String pdvNumber;

    /** {@link #REGISTER} or {@link #BACK_OFFICE}. */
    @Column(name = "channel", nullable = false, length = 12)
    public String channel;

    /** The function used: a register action path or a back-office feature gesture. */
    @Column(name = "function_code", nullable = false, length = 120)
    public String functionCode;

    /** How many times. */
    @Column(name = "use_count", nullable = false)
    public long useCount;

    /**
     * Finds the counter of one key.
     *
     * @param day the day
     * @param nodeId the node
     * @param channel the channel
     * @param functionCode the function
     * @return the counter, or null when the function was not used that day
     */
    public static FunctionUsage findKey(LocalDate day, String nodeId, String channel, String functionCode) {
        return find("usageDay = ?1 and nodeId = ?2 and channel = ?3 and functionCode = ?4",
                day, nodeId, channel, functionCode).firstResult();
    }

    /**
     * Every counter from a day on, oldest day first.
     *
     * @param from the first day included
     * @return the counters, possibly empty
     */
    public static List<FunctionUsage> since(LocalDate from) {
        return list("usageDay >= ?1 order by usageDay, nodeId, functionCode", from);
    }
}
