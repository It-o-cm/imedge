package com.intermarche.edge.ui.deploy;

import com.intermarche.edge.domain.deploy.ApplicationRelease;
import com.intermarche.edge.domain.deploy.DeploymentLot;
import com.intermarche.edge.domain.deploy.DeploymentSelection;
import com.intermarche.edge.domain.deploy.DeploymentTarget;
import com.intermarche.edge.domain.deploy.NodeStatus;
import com.intermarche.edge.domain.store.NodeEchelon;
import com.intermarche.edge.service.deploy.AppVersion;
import com.intermarche.edge.service.deploy.DeployPayloads;
import com.intermarche.edge.service.deploy.DeploymentPlanner;
import com.intermarche.edge.service.deploy.NodeStatusService;
import com.intermarche.edge.ui.report.ReportColumn;
import com.intermarche.edge.ui.supervision.ConsoleFilter;
import com.intermarche.edge.ui.supervision.StatusChart;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles and drives the DEPLOYMENT CONSOLE (BO-08-03-01 to -06,
 * BO-08-03-09/10, BO-08-04-02).
 *
 * <p>Publishing a release, composing a lot — its version, its pilot flag,
 * its window {@code jj/mm/aaaa hh:mn} to {@code jj/mm/aaaa hh:mn}, its
 * targets selected one by one, by echelon, by point-of-sale attribute, by
 * logical rule on the registers, from an imported list or by recalling a
 * saved selection — launching and closing it, forcing a failed target, and
 * following it all: the list of targets with their state and their
 * prerequisites, the OK/KO chart per group, the chart of the versions in
 * service, the export.
 *
 * <p>Every gesture answers a French notice, or null when it went well and
 * the caller words the success itself.
 */
@ApplicationScoped
public class DeploymentConsoleService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DeploymentConsoleService.class);

    /** The window's format, the requirement's {@code jj/mm/aaaa hh:mn}. */
    public static final DateTimeFormatter WINDOW = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    /** The release kinds. */
    static final List<String> KINDS = List.of("MAJOR", "MINOR", "PATCH");

    /** The deployment authority. */
    @Inject
    DeploymentPlanner deploymentPlanner;

    /** The node statuses and their offline tolerance. */
    @Inject
    NodeStatusService nodeStatusService;

    /** One target, with its state and prerequisites. */
    public static class TargetRow {
        /** The target's id. */
        public Long id;
        /** The lot's code. */
        public String lotCode;
        /** The lot's product. */
        public String product;
        /** The lot's version. */
        public String lotVersion;
        /** The point of sale. */
        public String pdv;
        /** Its enseigne. */
        public String enseigne;
        /** Its country. */
        public String country;
        /** REGISTER, STORE or CENTRAL. */
        public String kind;
        /** The node's identifier. */
        public String nodeId;
        /** The version the node runs, null when it never reported. */
        public String currentVersion;
        /** The target's state. */
        public String state;
        /** Whether the node runs the lot's version. */
        public boolean ok;
        /** Whether the update failed or was rolled back. */
        public boolean ko;
        /** What the node said. */
        public String message;
        /** Whether the node reported recently. */
        public boolean online;
        /** Whether the prerequisites hold. */
        public boolean prerequisiteOk;
        /** The prerequisite verdict, French. */
        public String prerequisite;
    }

    /** One version in service and how many nodes run it. */
    public static class VersionCount {
        /** IMEDGE or IMPOS. */
        public String product;
        /** The version. */
        public String version;
        /** How many nodes. */
        public long count;
    }

    /** What the console shows. */
    public static class ConsoleData {
        /** The filter in force. */
        public ConsoleFilter filter;
        /** Every lot. */
        public List<DeploymentLot> lots = new ArrayList<>();
        /** Every release. */
        public List<ApplicationRelease> releases = new ArrayList<>();
        /** The targets kept. */
        public List<TargetRow> rows = new ArrayList<>();
        /** The OK/KO chart per group. */
        public String chart = "";
        /** The chart of the versions in service. */
        public String versionChart = "";
        /** The versions in service. */
        public List<VersionCount> versions = new ArrayList<>();
        /** How many targets run their version. */
        public long okCount;
        /** How many failed. */
        public long koCount;
        /** How many are still waiting. */
        public long pendingCount;
    }

    /** What the page of one lot shows. */
    public static class LotView {
        /** The lot. */
        public DeploymentLot lot;
        /** Its release, null when it is not published. */
        public ApplicationRelease release;
        /** Its targets with their prerequisites. */
        public List<TargetRow> rows = new ArrayList<>();
        /** The saved selections, for the recall. */
        public List<DeploymentSelection> selections = new ArrayList<>();
        /** The window's start, formatted. */
        public String windowStart;
        /** The window's end, formatted. */
        public String windowEnd;
        /** How many targets meet their prerequisites. */
        public long readyCount;
        /** How many do not. */
        public long blockedCount;
    }

    /**
     * The console of every lot (BO-08-03-06).
     *
     * @param filter the filter: lot, product, status and organisation view
     * @return the console
     */
    @Transactional
    public ConsoleData console(ConsoleFilter filter) {
        LOGGER.info("Entering method console");
        ConsoleData data = new ConsoleData();
        data.filter = filter;
        data.lots = DeploymentLot.listOrdered();
        data.releases = ApplicationRelease.listOrdered();
        Map<String, NodeEchelon> echelons = new HashMap<>();
        Map<String, long[]> groups = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (DeploymentLot lot : data.lots) {
            if (!ConsoleFilter.keeps(filter.lot, lot.code) || !ConsoleFilter.keeps(filter.product, lot.product)) {
                continue;
            }
            for (DeploymentTarget target : DeploymentTarget.listByLot(lot.id)) {
                TargetRow row = row(lot, target, echelons, now);
                if (filter.keepsStatus(row.ok) && filter.keepsEchelon(row.country, row.enseigne, row.pdv)) {
                    data.rows.add(row);
                    long[] counts = groups.computeIfAbsent(filter.groupOf(row.country, row.enseigne, row.pdv),
                            g -> new long[2]);
                    counts[row.ok ? 0 : 1]++;
                    data.okCount += row.ok ? 1 : 0;
                    data.koCount += row.ko ? 1 : 0;
                    data.pendingCount += row.ok || row.ko ? 0 : 1;
                }
            }
        }
        List<StatusChart.Bar> bars = new ArrayList<>();
        for (Map.Entry<String, long[]> group : groups.entrySet()) {
            bars.add(new StatusChart.Bar(group.getKey(), group.getValue()[0], group.getValue()[1]));
        }
        data.chart = StatusChart.svg(bars);
        Map<String, VersionCount> versions = new LinkedHashMap<>();
        for (NodeStatus status : NodeStatus.listOrdered()) {
            NodeEchelon echelon = echelon(echelons, status.pdvNumber);
            String product = DeploymentPlanner.productOf(status.kind);
            if (status.version != null && ConsoleFilter.keeps(filter.product, product)
                    && filter.keepsEchelon(echelon.country, echelon.enseigne, status.pdvNumber)) {
                VersionCount count = versions.computeIfAbsent(product + " " + status.version, k -> new VersionCount());
                count.product = product;
                count.version = status.version;
                count.count++;
            }
        }
        List<StatusChart.Bar> versionBars = new ArrayList<>();
        for (Map.Entry<String, VersionCount> version : versions.entrySet()) {
            data.versions.add(version.getValue());
            versionBars.add(new StatusChart.Bar(version.getKey(), version.getValue().count, 0));
        }
        data.versionChart = StatusChart.svg(versionBars);
        return data;
    }

    /**
     * The page of one lot, with the prerequisites of each target (BO-08-03-01).
     *
     * @param code the lot's code
     * @return the page's data, or null when no lot carries that code
     */
    @Transactional
    public LotView lot(String code) {
        DeploymentLot lot = DeploymentLot.findByCode(code);
        if (lot == null) {
            return null;
        }
        LotView view = new LotView();
        view.lot = lot;
        view.release = ApplicationRelease.findRelease(lot.product, lot.version);
        view.selections = DeploymentSelection.listOrdered();
        view.windowStart = WINDOW.format(lot.windowStart);
        view.windowEnd = WINDOW.format(lot.windowEnd);
        Map<String, NodeEchelon> echelons = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (DeploymentTarget target : DeploymentTarget.listByLot(lot.id)) {
            TargetRow row = row(lot, target, echelons, now);
            view.rows.add(row);
            view.readyCount += row.prerequisiteOk ? 1 : 0;
            view.blockedCount += row.prerequisiteOk ? 0 : 1;
        }
        return view;
    }

    /**
     * Builds the line of one target, its prerequisites checked.
     *
     * @param lot the lot
     * @param target the target
     * @param echelons the places already resolved
     * @param now the present moment
     * @return the line
     */
    TargetRow row(DeploymentLot lot, DeploymentTarget target, Map<String, NodeEchelon> echelons,
                  LocalDateTime now) {
        TargetRow row = new TargetRow();
        NodeEchelon echelon = echelon(echelons, target.pdvNumber);
        NodeStatus status = NodeStatus.findByKey(target.nodeKey);
        row.id = target.id;
        row.lotCode = lot.code;
        row.product = lot.product;
        row.lotVersion = lot.version;
        row.pdv = target.pdvNumber;
        row.enseigne = echelon.enseigne;
        row.country = echelon.country;
        row.kind = target.kind;
        row.nodeId = target.nodeId;
        row.state = target.state.name();
        row.ok = target.state == DeploymentTarget.State.APPLIED;
        row.ko = target.state == DeploymentTarget.State.FAILED || target.state == DeploymentTarget.State.ROLLED_BACK;
        row.message = target.message;
        row.currentVersion = status == null ? null : status.version;
        row.online = status != null && status.online(now, nodeStatusService.toleranceSeconds());
        row.prerequisite = prerequisite(lot, status, row.online);
        row.prerequisiteOk = row.prerequisite.equals("Prérequis respectés");
        return row;
    }

    /**
     * Checks the prerequisites of one target: the version level and whether
     * the node is online (BO-08-03-01).
     *
     * @param lot the lot
     * @param status the node's status, null when it never reported
     * @param online whether it reported recently
     * @return the verdict, French
     */
    static String prerequisite(DeploymentLot lot, NodeStatus status, boolean online) {
        if (status == null || status.version == null) {
            return "Nœud jamais vu : version inconnue";
        }
        if (!AppVersion.isLower(status.version, lot.version)) {
            return "Déjà à jour";
        }
        if (lot.minVersion != null && AppVersion.isLower(status.version, lot.minVersion)) {
            return "Version trop ancienne (minimum " + lot.minVersion + ")";
        }
        return online ? "Prérequis respectés" : "Hors ligne : mise à jour au prochain démarrage";
    }

    /**
     * Publishes a release.
     *
     * @param product IMEDGE or IMPOS
     * @param version the version
     * @param kind MAJOR, MINOR or PATCH
     * @param notes what it brings
     * @param defective whether its package fails its self-test
     * @return the refusal, or null when published
     */
    @Transactional
    public String createRelease(String product, String version, String kind, String notes, boolean defective) {
        if (!ApplicationRelease.IMEDGE.equals(product) && !ApplicationRelease.IMPOS.equals(product)) {
            return "Produit inconnu.";
        }
        if (version == null || !version.trim().matches("[0-9][0-9A-Za-z.\\-]{0,39}")) {
            return "Version illisible.";
        }
        if (ApplicationRelease.findRelease(product, version.trim()) != null) {
            return "Cette version est déjà publiée.";
        }
        ApplicationRelease release = new ApplicationRelease();
        release.product = product;
        release.version = version.trim();
        release.releaseKind = KINDS.contains(kind) ? kind : "MINOR";
        release.notes = notes == null || notes.isBlank() ? null : notes.trim();
        release.defective = defective;
        release.createdAt = LocalDateTime.now();
        release.persist();
        return null;
    }

    /**
     * Creates a lot, as a draft.
     *
     * @param code the lot's code
     * @param label what it is for
     * @param product IMEDGE or IMPOS
     * @param version the version it distributes, which must be published
     * @param pilot whether it is a pilot phase
     * @param minVersion the lowest version a node must run, possibly blank
     * @param start the window's start, {@code jj/mm/aaaa hh:mn}
     * @param end the window's end, {@code jj/mm/aaaa hh:mn}
     * @param createdBy who creates it
     * @return the refusal, or null when created
     */
    @Transactional
    public String createLot(String code, String label, String product, String version, boolean pilot,
                            String minVersion, String start, String end, String createdBy) {
        if (code == null || !code.trim().matches("[A-Za-z0-9_\\-]{1,40}")) {
            return "Code de lot illisible.";
        }
        if (DeploymentLot.findByCode(code.trim()) != null) {
            return "Ce code de lot existe déjà.";
        }
        String trimmedVersion = version == null ? "" : version.trim();
        if (ApplicationRelease.findRelease(product, trimmedVersion) == null) {
            return "Version non publiée pour ce produit.";
        }
        LocalDateTime[] window = window(start, end);
        if (window == null) {
            return "Période illisible : attendu jj/mm/aaaa hh:mn, la fin après le début.";
        }
        DeploymentLot lot = new DeploymentLot();
        lot.code = code.trim();
        lot.label = label == null || label.isBlank() ? null : label.trim();
        lot.product = product;
        lot.version = trimmedVersion;
        lot.pilot = pilot;
        lot.minVersion = minVersion == null || minVersion.isBlank() ? null : minVersion.trim();
        lot.windowStart = window[0];
        lot.windowEnd = window[1];
        lot.createdBy = createdBy;
        lot.createdAt = LocalDateTime.now();
        lot.persist();
        return null;
    }

    /**
     * Changes the window of a lot (BO-08-03-04).
     *
     * @param code the lot's code
     * @param start the window's start
     * @param end the window's end
     * @return the refusal, or null when changed
     */
    @Transactional
    public String updateWindow(String code, String start, String end) {
        DeploymentLot lot = openLot(code);
        if (lot == null) {
            return "Lot introuvable ou clôturé.";
        }
        LocalDateTime[] window = window(start, end);
        if (window == null) {
            return "Période illisible : attendu jj/mm/aaaa hh:mn, la fin après le début.";
        }
        lot.windowStart = window[0];
        lot.windowEnd = window[1];
        lot.persist();
        return null;
    }

    /**
     * Adds targets to a lot (BO-08-03-02, BO-08-03-03).
     *
     * @param code the lot's code
     * @param mode UNIT, ECHELON, ATTRIBUTE, IMPORT or RECALL
     * @param level for ECHELON: COUNTRY or ENSEIGNE; for ATTRIBUTE: ADHERENT or VERSION
     * @param value the point of sale, the echelon or attribute code, or the selection's name
     * @param rule ALL, EVEN or ODD: the registers of a whole point of sale
     * @param lines the imported list, one line per target
     * @return the outcome, French
     */
    @Transactional
    public String addTargets(String code, String mode, String level, String value, String rule, String lines) {
        DeploymentLot lot = openLot(code);
        if (lot == null) {
            return "Lot introuvable ou clôturé.";
        }
        List<String> selection = selectionLines(lot, mode, level, value, lines);
        if (selection == null) {
            return "Sélection introuvable.";
        }
        DeploymentPlanner.Resolution resolution = deploymentPlanner.resolve(lot.product, selection,
                rule == null ? DeploymentPlanner.RULE_ALL : rule);
        int added = 0;
        for (DeploymentPlanner.TargetRef ref : resolution.targets()) {
            if (DeploymentTarget.findKey(lot.id, ref.key()) == null) {
                DeploymentTarget target = new DeploymentTarget();
                target.lotId = lot.id;
                target.nodeKey = ref.key();
                target.kind = ref.kind();
                target.pdvNumber = ref.pdvNumber();
                target.nodeId = ref.nodeId();
                target.updatedAt = LocalDateTime.now();
                target.persist();
                added++;
            }
        }
        return added + " cible(s) ajoutée(s)" + (resolution.unknown().isEmpty() ? "."
                : ", inconnu(s) : " + String.join(", ", resolution.unknown()) + ".");
    }

    /**
     * Turns a selection gesture into target lines.
     *
     * @param lot the lot
     * @param mode the selection mode
     * @param level the echelon or attribute
     * @param value the gesture's value
     * @param lines the imported list
     * @return the lines, or null when a recalled selection does not exist
     */
    private List<String> selectionLines(DeploymentLot lot, String mode, String level, String value,
                                        String lines) {
        String text = value == null ? "" : value;
        return switch (mode == null ? "" : mode) {
            case "ECHELON", "ATTRIBUTE" -> deploymentPlanner.linesOf(level == null ? "" : level, text, lot.product);
            case "IMPORT" -> Arrays.asList((lines == null ? "" : lines).split("\\R"));
            case "RECALL" -> {
                DeploymentSelection saved = DeploymentSelection.findByName(text.trim());
                yield saved == null ? null : Arrays.asList(saved.lines.split("\\R"));
            }
            default -> List.of(text);
        };
    }

    /**
     * Removes one target from a lot.
     *
     * @param code the lot's code
     * @param id the target
     * @return the refusal, or null when removed
     */
    @Transactional
    public String removeTarget(String code, Long id) {
        DeploymentLot lot = openLot(code);
        DeploymentTarget target = lot == null ? null : DeploymentTarget.findById(id);
        if (target == null || !lot.id.equals(target.lotId)) {
            return "Cible introuvable.";
        }
        target.delete();
        return null;
    }

    /**
     * Saves the targets of a lot as a named selection, for a later recall.
     *
     * @param code the lot's code
     * @param name the selection's name
     * @param createdBy who saves it
     * @return the refusal, or null when saved
     */
    @Transactional
    public String saveSelection(String code, String name, String createdBy) {
        DeploymentLot lot = DeploymentLot.findByCode(code);
        if (lot == null || name == null || name.isBlank()) {
            return "Lot ou nom de sélection manquant.";
        }
        List<String> lines = new ArrayList<>();
        for (DeploymentTarget target : DeploymentTarget.listByLot(lot.id)) {
            lines.add(switch (target.kind) {
                case DeployPayloads.REGISTER -> target.pdvNumber + ";" + target.nodeId;
                case DeployPayloads.CENTRAL -> DeploymentPlanner.CENTRAL_LINE;
                default -> target.pdvNumber;
            });
        }
        if (lines.isEmpty()) {
            return "Le lot n'a aucune cible à enregistrer.";
        }
        DeploymentSelection selection = DeploymentSelection.findByName(name.trim());
        if (selection == null) {
            selection = new DeploymentSelection();
            selection.name = name.trim();
        }
        selection.lines = String.join("\n", lines);
        selection.createdBy = createdBy;
        selection.createdAt = LocalDateTime.now();
        selection.persist();
        return null;
    }

    /**
     * Launches a draft lot: its targets receive its version from its window's
     * start.
     *
     * @param code the lot's code
     * @return the refusal, or null when launched
     */
    @Transactional
    public String schedule(String code) {
        DeploymentLot lot = openLot(code);
        if (lot == null) {
            return "Lot introuvable ou clôturé.";
        }
        if (DeploymentTarget.listByLot(lot.id).isEmpty()) {
            return "Le lot n'a aucune cible.";
        }
        lot.status = DeploymentLot.Status.SCHEDULED;
        lot.persist();
        return null;
    }

    /**
     * Closes a lot: it distributes nothing any more.
     *
     * @param code the lot's code
     * @return the refusal, or null when closed
     */
    @Transactional
    public String close(String code) {
        DeploymentLot lot = openLot(code);
        if (lot == null) {
            return "Lot introuvable ou clôturé.";
        }
        lot.status = DeploymentLot.Status.CLOSED;
        lot.persist();
        return null;
    }

    /**
     * Forces the update of one target, or of every failed target of the lot
     * when no target is named (BO-08-04-02): a new token travels to the node,
     * which resets its attempts and runs at once, window or not.
     *
     * @param code the lot's code
     * @param id the target, or null for every failed or rolled-back target
     * @return how many targets were forced, or -1 when the lot is closed or unknown
     */
    @Transactional
    public int force(String code, Long id) {
        DeploymentLot lot = openLot(code);
        if (lot == null) {
            return -1;
        }
        int forced = 0;
        for (DeploymentTarget target : DeploymentTarget.listByLot(lot.id)) {
            boolean failed = target.state == DeploymentTarget.State.FAILED
                    || target.state == DeploymentTarget.State.ROLLED_BACK;
            if (id == null ? failed : id.equals(target.id)) {
                target.forceToken = UUID.randomUUID().toString();
                target.state = DeploymentTarget.State.PENDING;
                target.message = "Forçage demandé";
                target.updatedAt = LocalDateTime.now();
                target.persist();
                forced++;
            }
        }
        return forced;
    }

    /**
     * The columns of the export.
     *
     * @return the columns
     */
    public static List<ReportColumn> columns() {
        return List.of(ReportColumn.text("lot", "Lot"), ReportColumn.text("product", "Produit"),
                ReportColumn.text("version", "Version cible"), ReportColumn.text("pdv", "PDV"),
                ReportColumn.text("enseigne", "Enseigne"), ReportColumn.text("country", "Pays"),
                ReportColumn.text("kind", "Type"), ReportColumn.text("node", "Nœud"),
                ReportColumn.text("current", "Version en service"), ReportColumn.text("state", "État"),
                ReportColumn.text("status", "Statut"), ReportColumn.text("message", "Message"));
    }

    /**
     * The rows of the export.
     *
     * @param data the console
     * @return the rows
     */
    public static List<List<Object>> lines(ConsoleData data) {
        List<List<Object>> lines = new ArrayList<>();
        for (TargetRow row : data.rows) {
            lines.add(List.of(row.lotCode, row.product, row.lotVersion, text(row.pdv), text(row.enseigne),
                    text(row.country), row.kind, row.nodeId, text(row.currentVersion), row.state,
                    row.ok ? "OK" : row.ko ? "KO" : "EN ATTENTE", text(row.message)));
        }
        return lines;
    }

    /**
     * Reads a lot that may still be changed.
     *
     * @param code the lot's code
     * @return the lot, or null when unknown or closed
     */
    private DeploymentLot openLot(String code) {
        DeploymentLot lot = DeploymentLot.findByCode(code);
        return lot == null || lot.status == DeploymentLot.Status.CLOSED ? null : lot;
    }

    /**
     * Reads a window.
     *
     * @param start its start, {@code jj/mm/aaaa hh:mn}
     * @param end its end, {@code jj/mm/aaaa hh:mn}
     * @return the two moments, or null when unreadable or the end is not after the start
     */
    static LocalDateTime[] window(String start, String end) {
        try {
            LocalDateTime from = LocalDateTime.parse(start.trim(), WINDOW);
            LocalDateTime to = LocalDateTime.parse(end.trim(), WINDOW);
            return to.isAfter(from) ? new LocalDateTime[]{from, to} : null;
        } catch (DateTimeParseException | NullPointerException unreadable) {
            return null;
        }
    }

    /**
     * Places a point of sale in the organisation, once per page.
     *
     * @param cache the places already resolved
     * @param pdv the point of sale, possibly null
     * @return its place
     */
    static NodeEchelon echelon(Map<String, NodeEchelon> cache, String pdv) {
        return cache.computeIfAbsent(pdv == null ? "" : pdv, NodeEchelon::of);
    }

    /**
     * An export cell for an optional text.
     *
     * @param value the value, possibly null
     * @return the value, empty when null
     */
    static String text(String value) {
        return value == null ? "" : value;
    }
}
