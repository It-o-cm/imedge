package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.PosList;
import com.intermarche.pos.domain.setting.PosListItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the ADMINISTERED LISTS the register taps ({@code BO-04-02-07},
 * {@code BO-04-03-12}) — the service-request reasons, the justifications, the
 * return types and the tare containers.
 *
 * <p>ONE RULE, APPLIED FOUR TIMES: when the store node has distributed a list
 * of the kind asked for, that list is the answer; when it has not, the
 * semicolon-separated setting the register has always read is the answer. So
 * a shop that never opens the new screen keeps exactly the behaviour it had,
 * and a shop that opens it stops editing strings.
 *
 * <p>This service, and not {@link PosSettingsService}, holds the resolution:
 * the settings service answers from the parameter table alone and is injected
 * nearly everywhere, and teaching it to read a referential would have made
 * every one of its callers depend on the entity.
 */
@ApplicationScoped
public class PosListService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PosListService.class);

    /** The register function the expense justifications are bound to. */
    public static final String FUNCTION_EXPENSE = "EXPENSE";

    /** The register function the line-cancellation justifications are bound to. */
    public static final String FUNCTION_CANCEL_LINE = "CANCEL_LINE";

    /** The administered parameters, read when no list has been distributed. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * ONE service-request type offered on the supervisor-call screen
     * ({@code BO-04-02-07}).
     *
     * @param code the type's code, which the back office files the call under
     * @param label what the key is called in front of the cashier
     * @param severity the priority the back office shows, 0 when none is set
     */
    public record ServiceRequestType(String code, String label, int severity) {
    }

    /**
     * The service-request types offered on the supervisor-call screen
     * ({@code BO-04-02-07}).
     *
     * <p>Falls back to the semicolon-separated
     * {@code supervisor.service-reasons}, each reason then being its own code
     * and carrying no severity.
     *
     * @return the types, in display order, possibly empty
     */
    public List<ServiceRequestType> serviceRequestTypes() {
        LOGGER.info("Entering method serviceRequestTypes");
        List<ServiceRequestType> types = new ArrayList<>();
        List<PosListItem> items = itemsOf(PosList.Kind.SERVICE_REQUEST, null);
        if (items != null) {
            for (PosListItem item : items) {
                types.add(new ServiceRequestType(item.code, item.label, item.severity));
            }
            LOGGER.info("Exiting method serviceRequestTypes");
            return types;
        }
        for (String raw : posSettingsService.supervisorServiceReasons().split(";")) {
            String reason = raw.trim();
            if (!reason.isEmpty()) {
                types.add(new ServiceRequestType(reason, reason, 0));
            }
        }
        LOGGER.info("Exiting method serviceRequestTypes");
        return types;
    }

    /**
     * The justifications offered for one register function
     * ({@code BO-04-03-12}).
     *
     * <p>Falls back to the semicolon-separated {@code cash.movement-reasons}
     * for the EXPENSE function ALONE: that key names the motives of a drawer
     * expense and nothing else, so answering it to another gesture would
     * offer "Achat de timbres" as the reason for a cancellation. Any other
     * function with no list offers nothing.
     *
     * @param function the function code asked about, possibly null
     * @return the justifications, in display order, possibly empty
     */
    public List<String> justifications(String function) {
        LOGGER.info("Entering method justifications with function: " + function);
        List<String> labels = new ArrayList<>();
        for (Justification justification : justificationsOf(function)) {
            labels.add(justification.label());
        }
        LOGGER.info("Exiting method justifications");
        return labels;
    }

    /**
     * ONE justification offered for a register gesture ({@code BO-04-03-12}),
     * with the CODE a back-office state reports it under
     * ({@code BO-06-06-04}).
     *
     * @param code the justification's code, which the states group by
     * @param label what the key is called in front of the cashier
     */
    public record Justification(String code, String label) {
    }

    /**
     * The justifications offered for one register function, each carrying its
     * code ({@code BO-04-03-12}, {@code BO-06-06-04}).
     *
     * <p>Falls back to the semicolon-separated {@code cash.movement-reasons}
     * for the EXPENSE function ALONE, exactly as {@link #justifications}
     * describes; a fallen-back reason is its own code, there being nothing
     * else to use — the same reading the service-request types already make
     * of their own fallback.
     *
     * @param function the function code asked about, possibly null
     * @return the justifications, in display order, possibly empty
     */
    public List<Justification> justificationsOf(String function) {
        LOGGER.info("Entering method justificationsOf with function: " + function);
        List<Justification> justifications = new ArrayList<>();
        List<PosListItem> items = itemsOf(PosList.Kind.JUSTIFICATION, function);
        if (items != null) {
            for (PosListItem item : items) {
                justifications.add(new Justification(item.code, item.label));
            }
            LOGGER.info("Exiting method justificationsOf");
            return justifications;
        }
        if (FUNCTION_EXPENSE.equalsIgnoreCase(function == null ? "" : function.trim())) {
            for (String reason : posSettingsService.cashMovementReasons()) {
                justifications.add(new Justification(reason, reason));
            }
        }
        LOGGER.info("Exiting method justificationsOf");
        return justifications;
    }

    /**
     * The justification carrying a code, for one register function, or null
     * when the function offers none under that code ({@code BO-06-06-04}).
     *
     * <p>Matched case-insensitively BY CODE, like the value a screen posts
     * back: what travels between the chooser and the gesture is the code, and
     * the administered list is what it means.
     *
     * @param function the function code asked about
     * @param code the code to resolve, possibly null
     * @return the justification, or null when nothing carries that code
     */
    public Justification justificationOf(String function, String code) {
        LOGGER.info("Entering method justificationOf with function: " + function + ", code: " + code);
        if (code == null || code.isBlank()) {
            LOGGER.info("Exiting method justificationOf");
            return null;
        }
        String typed = code.trim();
        for (Justification candidate : justificationsOf(function)) {
            if (candidate.code() != null && candidate.code().equalsIgnoreCase(typed)) {
                LOGGER.info("Exiting method justificationOf");
                return candidate;
            }
        }
        LOGGER.info("Exiting method justificationOf");
        return null;
    }

    /**
     * The return types offered on the return screen ({@code LC-05-04-01}).
     *
     * <p>Falls back to the semicolon-separated {@code return.types}. The four
     * option markers are the same on both roads, so a type reads the same
     * whichever one it came by.
     *
     * @return the return types, in display order, possibly empty
     */
    public List<PosSettingsService.ReturnType> returnTypes() {
        LOGGER.info("Entering method returnTypes");
        List<PosListItem> items = itemsOf(PosList.Kind.RETURN_TYPE, null);
        if (items == null) {
            LOGGER.info("Exiting method returnTypes");
            return posSettingsService.returnTypes();
        }
        List<PosSettingsService.ReturnType> types = new ArrayList<>();
        for (PosListItem item : items) {
            boolean dedicated = false;
            boolean exempt = false;
            boolean originOptional = false;
            boolean priceConfirmed = false;
            if (item.options != null) {
                for (String option : item.options.split(",")) {
                    String name = option.trim();
                    if ("TICKET".equalsIgnoreCase(name)) {
                        dedicated = true;
                    } else if ("NOENDORSE".equalsIgnoreCase(name)) {
                        exempt = true;
                    } else if ("NOORIGIN".equalsIgnoreCase(name)) {
                        originOptional = true;
                    } else if ("PRICE".equalsIgnoreCase(name)) {
                        priceConfirmed = true;
                    }
                }
            }
            types.add(new PosSettingsService.ReturnType(item.label, dedicated, exempt,
                    originOptional, priceConfirmed));
        }
        LOGGER.info("Exiting method returnTypes");
        return types;
    }

    /**
     * The administered return type carrying a label, or null when none does
     * ({@code BO-03-13-06/-08/-10}).
     *
     * <p>Matched case-insensitively BY LABEL, like the key the operator taps:
     * the label is what the register keeps on its state, and the list is what
     * it means.
     *
     * @param label the label to resolve, possibly null
     * @return the type, or null when nothing carries that label
     */
    public PosSettingsService.ReturnType returnType(String label) {
        LOGGER.info("Entering method returnType with label: " + label);
        if (label == null || label.isBlank()) {
            LOGGER.info("Exiting method returnType");
            return null;
        }
        String typed = label.trim();
        for (PosSettingsService.ReturnType candidate : returnTypes()) {
            if (candidate.label().equalsIgnoreCase(typed)) {
                LOGGER.info("Exiting method returnType");
                return candidate;
            }
        }
        LOGGER.info("Exiting method returnType");
        return null;
    }

    /**
     * The containers offered for a one-off tare ({@code LC-14-01-04}).
     *
     * <p>Falls back to the semicolon-separated {@code balance.tare-containers}.
     * An entry carrying no weight is dropped rather than weighed as zero: a
     * container that takes nothing off is a container nobody meant to create.
     *
     * @return the tare containers, in display order, possibly empty
     */
    public List<PosSettingsService.TareContainer> tareContainers() {
        LOGGER.info("Entering method tareContainers");
        List<PosListItem> items = itemsOf(PosList.Kind.TARE_CONTAINER, null);
        if (items == null) {
            LOGGER.info("Exiting method tareContainers");
            return posSettingsService.tareContainers();
        }
        List<PosSettingsService.TareContainer> containers = new ArrayList<>();
        for (PosListItem item : items) {
            BigDecimal weight = item.numericValue;
            if (weight == null || weight.signum() < 0) {
                continue;
            }
            containers.add(new PosSettingsService.TareContainer(item.code, item.label, weight));
        }
        LOGGER.info("Exiting method tareContainers");
        return containers;
    }

    /**
     * The ACTIVE entries of the distributed lists of one kind, or null when
     * the node has distributed no list of that kind — which is what tells the
     * caller to fall back on the administered string.
     *
     * <p>Several lists of the same kind are concatenated in code order: a shop
     * grouping its justifications under two headings offers both, which is the
     * grouping the requirement asks for.
     *
     * @param kind the kind looked for
     * @param function the register function to filter on, or null for no filter
     * @return the entries, or null when no list of that kind exists
     */
    private List<PosListItem> itemsOf(PosList.Kind kind, String function) {
        List<PosList> lists = PosList.findActiveByKind(kind);
        if (lists == null || lists.isEmpty()) {
            return null;
        }
        List<PosListItem> items = new ArrayList<>();
        boolean matched = false;
        for (PosList list : lists) {
            if (function != null && !list.servesFunction(function)) {
                continue;
            }
            matched = true;
            for (PosListItem item : list.items) {
                if (item.active) {
                    items.add(item);
                }
            }
        }
        return matched ? items : null;
    }
}
