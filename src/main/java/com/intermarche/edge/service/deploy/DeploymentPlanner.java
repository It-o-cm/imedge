package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.ApplicationRelease;
import com.intermarche.edge.domain.deploy.DeploymentLot;
import com.intermarche.edge.domain.deploy.DeploymentTarget;
import com.intermarche.edge.domain.deploy.NodeStatus;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The DEPLOYMENT AUTHORITY of this node: which version each node must run,
 * how a selection of points of sale and registers becomes a list of targets,
 * and how the nodes' reports move their targets along
 * (BO-08-03-02/03/04/05/06/09/10).
 *
 * <p>The central node is the authority of the organisation. A store node
 * with no central node is the authority of its own line, with the same
 * lots — the planner works on whatever lots the node holds.
 *
 * <p>VERSION SWITCHING. A node runs the version of the most recent launched
 * lot that names it and whose window has started; a node no lot names is
 * told nothing and keeps its version. That is the whole pilot phase
 * (BO-08-03-09/10): a pilot lot names a group of points of sale, so their
 * store nodes — or their registers — receive the version, and the others,
 * named by no lot, never do.
 */
@ApplicationScoped
public class DeploymentPlanner {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DeploymentPlanner.class);

    /** Every register of a point of sale. */
    public static final String RULE_ALL = "ALL";

    /** The registers whose number is even. */
    public static final String RULE_EVEN = "EVEN";

    /** The registers whose number is odd. */
    public static final String RULE_ODD = "ODD";

    /** The line naming the central brick in a selection. */
    public static final String CENTRAL_LINE = "CENTRAL";

    /**
     * One node a selection names.
     *
     * @param kind REGISTER, STORE or CENTRAL
     * @param pdvNumber its point of sale, null for the central brick
     * @param nodeId its identifier
     */
    public record TargetRef(String kind, String pdvNumber, String nodeId) {

        /**
         * The node's organisation-wide key.
         *
         * @return the key
         */
        public String key() {
            return DeployPayloads.nodeKey(kind, pdvNumber, nodeId);
        }
    }

    /**
     * What a selection resolved to.
     *
     * @param targets the nodes named, without duplicates
     * @param unknown the lines that named nothing known
     */
    public record Resolution(List<TargetRef> targets, List<String> unknown) {
    }

    /**
     * The product a node kind runs.
     *
     * @param kind REGISTER, STORE or CENTRAL
     * @return IMPOS for a register, IMEDGE otherwise
     */
    public static String productOf(String kind) {
        return DeployPayloads.REGISTER.equals(kind) ? ApplicationRelease.IMPOS : ApplicationRelease.IMEDGE;
    }

    /**
     * The version one node must run, when a launched lot names it.
     *
     * @param kind the node's kind
     * @param pdvNumber the node's point of sale
     * @param nodeId the node's identifier
     * @return the target, or null when no started lot names the node
     */
    @Transactional
    public DeployPayloads.Target targetFor(String kind, String pdvNumber, String nodeId) {
        String product = productOf(kind);
        String key = DeployPayloads.nodeKey(kind, pdvNumber, nodeId);
        LocalDateTime now = LocalDateTime.now();
        for (DeploymentLot lot : DeploymentLot.listScheduled(product)) {
            DeploymentTarget entry = lot.started(now) ? DeploymentTarget.findKey(lot.id, key) : null;
            ApplicationRelease release = entry == null ? null : ApplicationRelease.findRelease(product, lot.version);
            if (release != null) {
                DeployPayloads.Target target = new DeployPayloads.Target();
                target.kind = kind;
                target.pdvNumber = pdvNumber;
                target.nodeId = nodeId;
                target.product = product;
                target.version = lot.version;
                target.checksum = release.checksum();
                target.inWindow = lot.inWindow(now);
                target.forceToken = entry.forceToken;
                target.lotCode = lot.code;
                return target;
            }
        }
        return null;
    }

    /**
     * The package of a release this node publishes.
     *
     * @param product IMEDGE or IMPOS
     * @param version the version
     * @return the package content, or null when this node holds no such release
     */
    @Transactional
    public String packageContent(String product, String version) {
        ApplicationRelease release = ApplicationRelease.findRelease(product, version);
        return release == null ? null : release.packageContent();
    }

    /**
     * Moves the lot entries of a node along with what the node reported.
     *
     * @param status the node's freshly written status
     */
    @Transactional
    public void recordProgress(NodeStatus status) {
        for (DeploymentTarget entry : DeploymentTarget.listByNode(status.nodeKey)) {
            DeploymentLot lot = DeploymentLot.findById(entry.lotId);
            if (lot == null) {
                continue;
            }
            entry.reportedVersion = status.version;
            DeploymentTarget.State state = stateOf(lot, status);
            if (state != null) {
                entry.state = state;
                entry.message = state == DeploymentTarget.State.APPLIED ? null : status.updateMessage;
                entry.updatedAt = LocalDateTime.now();
            }
            entry.persist();
        }
    }

    /**
     * Reads the state of a lot entry out of a node's status.
     *
     * @param lot the lot
     * @param status the node's status
     * @return the new state, or null when the report says nothing about this lot
     */
    static DeploymentTarget.State stateOf(DeploymentLot lot, NodeStatus status) {
        if (lot.version.equals(status.version)) {
            return DeploymentTarget.State.APPLIED;
        }
        if (!lot.version.equals(status.updateTarget) || status.updateState == null) {
            return null;
        }
        return switch (status.updateState) {
            case "FAILED" -> DeploymentTarget.State.FAILED;
            case "ROLLED_BACK" -> DeploymentTarget.State.ROLLED_BACK;
            case "DOWNLOADING", "VERIFYING", "INSTALLING" -> DeploymentTarget.State.IN_PROGRESS;
            default -> null;
        };
    }

    /**
     * Resolves the lines of a selection into nodes (BO-08-03-02/03).
     *
     * <p>A line is a point-of-sale number, {@code PDV;REGISTER} for one
     * register, or {@code CENTRAL} for the central brick. For the register
     * product, a point of sale alone stands for its registers — all, the even
     * ones or the odd ones, by the logical rule; for the store product it
     * stands for its store node.
     *
     * @param product IMEDGE or IMPOS
     * @param lines the selection lines
     * @param rule {@link #RULE_ALL}, {@link #RULE_EVEN} or {@link #RULE_ODD}
     * @return the nodes named and the lines that named nothing
     */
    @Transactional
    public Resolution resolve(String product, List<String> lines, String rule) {
        LOGGER.info("Entering method resolve with " + lines.size() + " line(s)");
        Set<TargetRef> targets = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        boolean registers = ApplicationRelease.IMPOS.equals(product);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            List<TargetRef> named = registers ? registerTargets(line, rule) : edgeTargets(line);
            if (named.isEmpty()) {
                unknown.add(line);
            }
            targets.addAll(named);
        }
        return new Resolution(new ArrayList<>(targets), unknown);
    }

    /**
     * The store node or central brick a line names.
     *
     * @param line the selection line
     * @return the node, or nothing when the line names no known point of sale
     */
    private List<TargetRef> edgeTargets(String line) {
        if (CENTRAL_LINE.equalsIgnoreCase(line)) {
            return List.of(new TargetRef(DeployPayloads.CENTRAL, null, CENTRAL_LINE));
        }
        String pdv = line.split(";")[0].trim();
        return knownPdv(pdv) ? List.of(new TargetRef(DeployPayloads.STORE, pdv, pdv)) : List.of();
    }

    /**
     * The registers a line names.
     *
     * @param line the selection line
     * @param rule the logical rule applied to a whole point of sale
     * @return the registers, possibly none
     */
    private List<TargetRef> registerTargets(String line, String rule) {
        String[] parts = line.split(";");
        String pdv = parts[0].trim();
        List<TargetRef> named = new ArrayList<>();
        if (parts.length > 1) {
            if (knownPdv(pdv)) {
                named.add(new TargetRef(DeployPayloads.REGISTER, pdv, parts[1].trim()));
            }
            return named;
        }
        for (NodeStatus register : NodeStatus.listByKind(DeployPayloads.REGISTER)) {
            if (pdv.equals(register.pdvNumber) && matches(register.nodeId, rule)) {
                named.add(new TargetRef(DeployPayloads.REGISTER, pdv, register.nodeId));
            }
        }
        return named;
    }

    /**
     * Whether a register identifier passes a logical rule.
     *
     * @param nodeId the register identifier ({@code C04}, {@code POS01}...)
     * @param rule the rule
     * @return true when the rule keeps it; a register without a number passes
     *         only {@link #RULE_ALL}
     */
    static boolean matches(String nodeId, String rule) {
        if (!RULE_EVEN.equals(rule) && !RULE_ODD.equals(rule)) {
            return true;
        }
        String digits = nodeId.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return false;
        }
        boolean even = (digits.charAt(digits.length() - 1) - '0') % 2 == 0;
        return RULE_EVEN.equals(rule) == even;
    }

    /**
     * Whether a point of sale is known to this node: in the central registry
     * or by a node that reported from it.
     *
     * @param pdv the point-of-sale number
     * @return true when known
     */
    private boolean knownPdv(String pdv) {
        if (Pdv.findByNumber(pdv) != null) {
            return true;
        }
        for (NodeStatus status : NodeStatus.listOrdered()) {
            if (pdv.equals(status.pdvNumber)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The point-of-sale lines of an echelon or an attribute
     * (BO-08-03-02: "groupée sur chaque échelon", "groupée sur les attributs").
     *
     * @param level COUNTRY, ENSEIGNE, ADHERENT or VERSION
     * @param code the country, enseigne or adhérent code, or the installed version
     * @param product the product, for the VERSION attribute
     * @return one line per point of sale (or register, for a version), possibly none
     */
    @Transactional
    public List<String> linesOf(String level, String code, String product) {
        Set<String> lines = new LinkedHashSet<>();
        String wanted = code == null ? "" : code.trim();
        switch (level) {
            case "COUNTRY" -> {
                for (Enseigne enseigne : Enseigne.listAllOrdered()) {
                    if (wanted.equals(enseigne.countryCode)) {
                        for (Pdv pdv : Pdv.listByEnseigne(enseigne.code)) {
                            lines.add(pdv.pdvNumber);
                        }
                    }
                }
            }
            case "ENSEIGNE" -> {
                for (Pdv pdv : Pdv.listByEnseigne(wanted)) {
                    lines.add(pdv.pdvNumber);
                }
            }
            case "ADHERENT" -> {
                for (Pdv pdv : Pdv.listAllOrdered()) {
                    if (wanted.equals(pdv.adherentCode)) {
                        lines.add(pdv.pdvNumber);
                    }
                }
            }
            default -> {
                boolean registers = ApplicationRelease.IMPOS.equals(product);
                for (NodeStatus status : NodeStatus.listByKind(registers ? DeployPayloads.REGISTER
                        : DeployPayloads.STORE)) {
                    if (wanted.equals(status.version)) {
                        lines.add(registers ? status.pdvNumber + ";" + status.nodeId : status.pdvNumber);
                    }
                }
            }
        }
        return new ArrayList<>(lines);
    }
}
