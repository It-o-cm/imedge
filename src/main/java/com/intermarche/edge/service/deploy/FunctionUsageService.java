package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.FunctionUsage;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Counts the use of every register and back-office FUNCTION, for the
 * statistics of the central dashboard (BO-08-02-02).
 *
 * <p>Three gestures: count one use on this node, hand the counters of the
 * last days to the report that carries them upstream, and fold the counters a
 * downstream node reported into this node's table. Folding REPLACES the count
 * held for the same day, node, channel and function: a counter is the
 * reporting node's truth, so a report received twice counts once.
 */
@ApplicationScoped
public class FunctionUsageService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FunctionUsageService.class);

    /** The longest function code the table holds. */
    static final int CODE_LENGTH = 120;

    /** The administered activation of the counting. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Counts one use of a function on a node, unless the counting is disabled.
     *
     * @param nodeId the node the function was used on
     * @param pdvNumber its point of sale, possibly null
     * @param channel {@link FunctionUsage#REGISTER} or {@link FunctionUsage#BACK_OFFICE}
     * @param function the function used
     */
    @Transactional
    public void record(String nodeId, String pdvNumber, String channel, String function) {
        if (!posSettingsService.functionUsageEnabled()) {
            return;
        }
        String code = function.length() > CODE_LENGTH ? function.substring(0, CODE_LENGTH) : function;
        LocalDate today = LocalDate.now();
        FunctionUsage row = FunctionUsage.findKey(today, nodeId, channel, code);
        if (row == null) {
            row = new FunctionUsage();
            row.usageDay = today;
            row.nodeId = nodeId;
            row.pdvNumber = pdvNumber;
            row.channel = channel;
            row.functionCode = code;
        }
        row.useCount++;
        row.persist();
    }

    /**
     * The counters of the last days, for the report sent upstream.
     *
     * @param days how many days back, today included as day zero
     * @return the counters as they travel
     */
    @Transactional
    public List<DeployPayloads.Usage> recent(int days) {
        List<DeployPayloads.Usage> usages = new ArrayList<>();
        for (FunctionUsage row : FunctionUsage.since(LocalDate.now().minusDays(days))) {
            DeployPayloads.Usage usage = new DeployPayloads.Usage();
            usage.day = row.usageDay.toString();
            usage.nodeId = row.nodeId;
            usage.pdvNumber = row.pdvNumber;
            usage.channel = row.channel;
            usage.function = row.functionCode;
            usage.count = row.useCount;
            usages.add(usage);
        }
        return usages;
    }

    /**
     * Folds the counters a downstream node reported into this node's table.
     *
     * @param usages the reported counters
     */
    @Transactional
    public void merge(List<DeployPayloads.Usage> usages) {
        LOGGER.info("Entering method merge with " + usages.size() + " counter(s)");
        for (DeployPayloads.Usage usage : usages) {
            LocalDate day = LocalDate.parse(usage.day);
            FunctionUsage row = FunctionUsage.findKey(day, usage.nodeId, usage.channel, usage.function);
            if (row == null) {
                row = new FunctionUsage();
                row.usageDay = day;
                row.nodeId = usage.nodeId;
                row.channel = usage.channel;
                row.functionCode = usage.function;
            }
            row.pdvNumber = usage.pdvNumber;
            row.useCount = usage.count;
            row.persist();
        }
        LOGGER.info("Exiting method merge");
    }
}
