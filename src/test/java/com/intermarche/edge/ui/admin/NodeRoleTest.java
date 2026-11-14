package com.intermarche.edge.ui.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link NodeRole}: the pages name the central node as central
 * and every other node as a store node.
 */
class NodeRoleTest {

    /**
     * Builds the bean over a configured role.
     *
     * @param role the configured {@code pos.role}
     * @return the bean
     */
    private NodeRole role(String role) {
        NodeRole bean = new NodeRole();
        bean.role = role;
        return bean;
    }

    /** The central role is recognised whatever its case. */
    @Test
    void theCentralRoleIsCentral() {
        assertTrue(role("central").isCentral());
        assertTrue(role("CENTRAL").isCentral());
    }

    /** A store, a register or an absent role is not central. */
    @Test
    void anyOtherRoleIsNotCentral() {
        assertFalse(role("store").isCentral());
        assertFalse(role("register").isCentral());
        assertFalse(role(null).isCentral());
    }
}
