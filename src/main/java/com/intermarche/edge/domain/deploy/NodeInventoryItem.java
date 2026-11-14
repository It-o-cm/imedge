package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.List;

/**
 * One line of the HARDWARE AND SOFTWARE INVENTORY of a node, as reported
 * (BO-08-04-20).
 *
 * <p>What the register says it is made of: its declared checkout-point
 * equipment, the peripherals bound to it and their state, its processor and
 * memory, its operating system, its Java runtime and its application
 * version. Replaced whole at every report — an inventory is a photograph.
 */
@Entity
@Table(name = "node_inventory_item")
public class NodeInventoryItem extends PanacheEntity {

    /** The node's key. */
    @Column(name = "node_key", nullable = false, length = 64)
    public String nodeKey;

    /** The node's point of sale. */
    @Column(name = "pdv_number", length = 5)
    public String pdvNumber;

    /** The node's identifier. */
    @Column(name = "node_id", nullable = false, length = 40)
    public String nodeId;

    /** HARDWARE, PERIPHERAL or SOFTWARE. */
    @Column(name = "category", nullable = false, length = 12)
    public String category;

    /** What the line describes. */
    @Column(name = "name", nullable = false, length = 60)
    public String name;

    /** Its value. */
    @Column(name = "detail", length = 200)
    public String detail;

    /**
     * Every inventory line, by point of sale, node, category and name.
     *
     * @return the rows, possibly empty
     */
    public static List<NodeInventoryItem> listOrdered() {
        return list("order by pdvNumber, nodeId, category, name");
    }

    /**
     * The inventory of one node.
     *
     * @param nodeKey the node's key
     * @return its lines, by category and name
     */
    public static List<NodeInventoryItem> listByNode(String nodeKey) {
        return list("nodeKey = ?1 order by category, name", nodeKey);
    }

    /**
     * Forgets the inventory of one node before its new report is written.
     *
     * @param nodeKey the node's key
     * @return how many rows were removed
     */
    public static long deleteByNode(String nodeKey) {
        return delete("nodeKey", nodeKey);
    }
}
