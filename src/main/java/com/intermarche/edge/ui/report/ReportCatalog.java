package com.intermarche.edge.ui.report;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The REGISTRY of reports: every {@link ReportDefinition} bean, indexed by code
 * and grouped by family.
 *
 * <p>Discovered, not enumerated. A report is in the catalog because a bean
 * implements the interface, exactly as a scan handler is in the chain because a
 * bean implements it — so adding a report never means editing a list that
 * somebody will forget to edit.
 */
@ApplicationScoped
public class ReportCatalog {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReportCatalog.class);

    /** Every report bean the container found. */
    @Inject Instance<ReportDefinition> discovered;

    /** The reports indexed by code, in display order. */
    private final Map<String, ReportDefinition> byCode = new LinkedHashMap<>();

    /**
     * Sorts the discovered reports by family then by requirement code and
     * indexes them.
     *
     * <p>By requirement code inside a family, not by title: the person checking
     * the tender reads the questionnaire in that order, and the list should let
     * them tick it off line by line.
     */
    @PostConstruct
    void index() {
        LOGGER.info("Entering method index");
        List<ReportDefinition> all = new ArrayList<>();
        for (ReportDefinition definition : discovered) {
            all.add(definition);
        }
        all.sort(Comparator.comparing((ReportDefinition d) -> d.family().ordinal())
                .thenComparing(ReportDefinition::requirement));
        for (ReportDefinition definition : all) {
            byCode.put(definition.code(), definition);
        }
        LOGGER.info("Exiting method index with " + byCode.size() + " report(s)");
    }

    /**
     * Returns every report, family by family, in display order.
     *
     * @return the reports, never null
     */
    public List<ReportDefinition> all() {
        return List.copyOf(byCode.values());
    }

    /**
     * Returns the reports of one family.
     *
     * @param family the family to list
     * @return its reports, in display order, possibly empty
     */
    public List<ReportDefinition> of(ReportFamily family) {
        List<ReportDefinition> selected = new ArrayList<>();
        for (ReportDefinition definition : byCode.values()) {
            if (definition.family() == family) {
                selected.add(definition);
            }
        }
        return selected;
    }

    /**
     * Finds a report by its code.
     *
     * @param code the code carried by the URL, possibly unknown or null
     * @return the report, or null when no report carries that code
     */
    public ReportDefinition find(String code) {
        return code == null ? null : byCode.get(code);
    }

    /**
     * Returns the families that actually hold a report, so the list never shows
     * an empty heading.
     *
     * @return the non-empty families, in declaration order
     */
    public List<ReportFamily> families() {
        List<ReportFamily> used = new ArrayList<>();
        for (ReportFamily family : ReportFamily.values()) {
            if (!of(family).isEmpty()) {
                used.add(family);
            }
        }
        return used;
    }
}
