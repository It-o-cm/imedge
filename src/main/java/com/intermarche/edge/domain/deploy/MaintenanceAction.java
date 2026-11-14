package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One REMOTE MAINTENANCE ORDER for one node, and what became of it
 * (BO-08-04-17).
 *
 * <p>The take-over of a point of sale from the back office is a list of
 * ORDERS, not a remote desktop: the node is asked to restart its payment
 * terminal, reset a peripheral, pull its referentials, check its version...
 * It collects the order with its next report answer, carries it out and says
 * how it went in the report after. Every order is KEPT with who issued it,
 * when, why, when it was handed to the node and what the node answered:
 * the traceability the requirement asks for is this table.
 *
 * <p>An order issued at the central node for a register travels through the
 * store node, which keeps its own copy under the same token; the store sends
 * the outcome back up once ({@link #forwarded}).
 */
@Entity
@Table(name = "maintenance_action")
public class MaintenanceAction extends PanacheEntity {

    /** What a node can be asked to do. */
    public enum ActionType {
        /** Restart the payment terminal of a register (relance TPV). */
        TERMINAL_RESET("Relance du TPV", true, false),
        /** Reset the ticket printer of a register. */
        PRINTER_RESET("Réinitialisation de l'imprimante", true, false),
        /** Reset the scanner of a register. */
        SCANNER_RESET("Réinitialisation du scanner", true, false),
        /** Probe every peripheral of a register again. */
        PERIPHERAL_PROBE("Contrôle des périphériques", true, false),
        /** Pull the referentials now. */
        REFERENTIAL_PULL("Relance du tirage des référentiels", true, true),
        /** Deliver the engine feeds now (store node). */
        ENGINE_FEED_DELIVERY("Relance de la livraison des flux moteur", false, true),
        /** Replay the failed deposited integrations now (store or central node). */
        INTEGRATION_RETRY("Relance des intégrations en erreur", false, true),
        /** Compare the version with the deployment now and update when due. */
        UPDATE_CHECK("Contrôle de mise à jour", true, true);

        /** The operator-facing label (French). */
        private final String label;
        /** Whether a register can carry it out. */
        private final boolean register;
        /** Whether a store or central node can carry it out. */
        private final boolean edge;

        /**
         * Declares an order type.
         *
         * @param label the French label
         * @param register whether a register carries it out
         * @param edge whether a store or central node carries it out
         */
        ActionType(String label, boolean register, boolean edge) {
            this.label = label;
            this.register = register;
            this.edge = edge;
        }

        /**
         * Returns the operator-facing label.
         *
         * @return the French label
         */
        public String getLabel() {
            return label;
        }

        /**
         * Tells whether a node of a kind can carry this order out.
         *
         * @param kind REGISTER, STORE or CENTRAL
         * @return true when the node kind supports it
         */
        public boolean appliesTo(String kind) {
            return "REGISTER".equals(kind) ? register : edge;
        }
    }

    /** Where an order stands. */
    public enum Status {
        /** Waiting for the node's next report. */
        PENDING,
        /** Handed to the node with a report answer. */
        SENT,
        /** Carried out. */
        DONE,
        /** The node could not, or would not, carry it out. */
        FAILED
    }

    /** The order's token, the same on every node it crosses. */
    @Column(name = "action_uid", nullable = false, unique = true, length = 64)
    public String actionUid;

    /** The target node's key. */
    @Column(name = "node_key", nullable = false, length = 64)
    public String nodeKey;

    /** The target node's kind. */
    @Column(name = "kind", nullable = false, length = 10)
    public String kind;

    /** The target node's identifier. */
    @Column(name = "node_id", nullable = false, length = 40)
    public String nodeId;

    /** The target node's point of sale. */
    @Column(name = "pdv_number", length = 5)
    public String pdvNumber;

    /** What the node is asked to do. */
    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 24)
    public ActionType actionType;

    /** Who issued it. */
    @Column(name = "issued_by", length = 80)
    public String issuedBy;

    /** Why. */
    @Column(name = "reason", length = 200)
    public String reason;

    /** CENTRAL or STORE: the echelon that issued it. */
    @Column(name = "origin", nullable = false, length = 10)
    public String origin;

    /** Where it stands. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    public Status status = Status.PENDING;

    /** What the node answered. */
    @Column(name = "result", length = 300)
    public String result;

    /** When it was issued. */
    @Column(name = "issued_at", nullable = false)
    public LocalDateTime issuedAt;

    /** When it was handed to the node. */
    @Column(name = "sent_at")
    public LocalDateTime sentAt;

    /** When the node carried it out. */
    @Column(name = "done_at")
    public LocalDateTime doneAt;

    /** Whether a store node already sent the outcome of a central order back up. */
    @Column(name = "forwarded", nullable = false)
    public boolean forwarded;

    /**
     * Finds an order by its token.
     *
     * @param uid the token
     * @return the order, or null
     */
    public static MaintenanceAction findByUid(String uid) {
        return find("actionUid", uid).firstResult();
    }

    /**
     * The orders waiting for one node, oldest first.
     *
     * @param nodeKey the node's key
     * @return the pending orders
     */
    public static List<MaintenanceAction> pendingFor(String nodeKey) {
        return list("nodeKey = ?1 and status = ?2 order by issuedAt", nodeKey, Status.PENDING);
    }

    /**
     * The outcomes of central orders not yet sent back up.
     *
     * @return the orders carried out and not forwarded
     */
    public static List<MaintenanceAction> unforwardedOutcomes() {
        return list("origin = ?1 and forwarded = false and status in ?2", "CENTRAL",
                List.of(Status.DONE, Status.FAILED));
    }

    /**
     * The most recent orders, newest first.
     *
     * @param limit how many rows to read
     * @return the orders
     */
    public static List<MaintenanceAction> latest(int limit) {
        return find("order by issuedAt desc, id desc").page(0, limit).list();
    }
}
