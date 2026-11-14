package com.intermarche.edge.ui.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The role this node plays in the suite, as the back-office pages name it:
 * the same binary serves the store nodes and the central node
 * ({@code pos.role}), and a page that names "the store node" on the central
 * node lies about where the operator stands. The layout reads this bean
 * ({@code inject:nodeRole}) for its title and its footer, and every page
 * naming the node does the same.
 */
@Named("nodeRole")
@ApplicationScoped
public class NodeRole {

    /** The configured role of this node: "store", "central" or "register". */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /**
     * Tells whether this node is the central node.
     *
     * @return true on the central node, false on a store node (or any other role)
     */
    public boolean isCentral() {
        return "central".equalsIgnoreCase(role);
    }
}
