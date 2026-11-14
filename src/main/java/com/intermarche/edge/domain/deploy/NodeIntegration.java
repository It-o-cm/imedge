package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The last state of ONE referential integration on ONE node, as reported
 * (BO-08-01-17, BO-08-01-19).
 *
 * <p>One row per node and domain, replaced at every report: a store node's
 * deposited feeds (articles, nomenclature, customers, VAT, offers...) and a
 * register's pulled domains. It is what the integration console counts OK
 * and KO, and what the central node answers when asked whether a point of
 * sale has received a referential.
 */
@Entity
@Table(name = "node_integration")
public class NodeIntegration extends PanacheEntity {

    /** The node's key. */
    @Column(name = "node_key", nullable = false, length = 64)
    public String nodeKey;

    /** The node's kind. */
    @Column(name = "kind", nullable = false, length = 10)
    public String kind;

    /** The node's identifier. */
    @Column(name = "node_id", nullable = false, length = 40)
    public String nodeId;

    /** The node's point of sale. */
    @Column(name = "pdv_number", length = 5)
    public String pdvNumber;

    /** The referential domain or feed code. */
    @Column(name = "domain", nullable = false, length = 30)
    public String domain;

    /** APPLIED or FAILED. */
    @Column(name = "outcome", nullable = false, length = 10)
    public String outcome;

    /** When it was last integrated. */
    @Column(name = "integrated_at")
    public LocalDateTime integratedAt;

    /** What to remember of it. */
    @Column(name = "detail", length = 300)
    public String detail;

    /**
     * The integrations of one node.
     *
     * @param nodeKey the node's key
     * @return its rows, possibly empty
     */
    public static List<NodeIntegration> listByNode(String nodeKey) {
        return list("nodeKey = ?1 order by domain", nodeKey);
    }

    /**
     * Every integration, by point of sale, node and domain.
     *
     * @return the rows, possibly empty
     */
    public static List<NodeIntegration> listOrdered() {
        return list("order by pdvNumber, kind, nodeId, domain");
    }

    /**
     * Forgets the integrations of one node before its new report is written.
     *
     * @param nodeKey the node's key
     * @return how many rows were removed
     */
    public static long deleteByNode(String nodeKey) {
        return delete("nodeKey", nodeKey);
    }
}
