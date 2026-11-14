package com.intermarche.edge.domain.people;

import com.intermarche.edge.domain.store.NodeEchelon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EchelonScope} (BO-01-01-03, BO-01-02-03, BO-01-03-04),
 * targeting 100% branch coverage.
 *
 * <p>Branch enumeration — normalize: null, blank, {@code *}, a bare code, an
 * unknown kind, the ALL kind with a code, an empty code, a type of TPV (upper
 * cased), another kind; kindOf/codeOf: null, blank, {@code *}, invalid,
 * valid; parseList/invalidParts: null and mixed lists; join: null, empty,
 * filled; coversNode: every kind, with and without a node, matching or not;
 * admitsNode and admitsRegister: no scope, register-level only, matching and
 * non-matching node-level scopes, TPV and type matches and misses; label: every
 * kind and an invalid scope.
 */
class EchelonScopeTest {

    /** A node of PDV 07039, enseigne ITM, country FR. */
    private static final NodeEchelon NODE = new NodeEchelon("07039", "ITM", "FR");

    /**
     * The canonical forms: the whole organisation, a bare code read as a PDV,
     * a kind in any case, a TPV type upper cased.
     */
    @Test
    void scopesNormalizeToTheirCanonicalForm() {
        assertNull(EchelonScope.normalize(null));
        assertNull(EchelonScope.normalize("  "));
        assertEquals("*", EchelonScope.normalize(" * "));
        assertEquals("PDV:07039", EchelonScope.normalize("07039"));
        assertEquals("ENSEIGNE:ITM", EchelonScope.normalize("enseigne: ITM"));
        assertEquals("TPV_TYPE:SELF_CHECKOUT_NCR", EchelonScope.normalize("tpv_type:self_checkout_ncr"));
        assertEquals("TPV:POS01", EchelonScope.normalize("TPV:POS01"));
    }

    /**
     * An unknown kind, the ALL kind with a code, and an empty code are no scope.
     */
    @Test
    void malformedScopesAreRefused() {
        assertNull(EchelonScope.normalize("REGION:IDF"));
        assertNull(EchelonScope.normalize("ALL:x"));
        assertNull(EchelonScope.normalize("PDV: "));
    }

    /**
     * A scope's kind and code, the whole organisation for null, blank and
     * {@code *}, nothing for an invalid scope.
     */
    @Test
    void kindAndCodeAreRead() {
        assertEquals(EchelonScope.Kind.ALL, EchelonScope.kindOf(null));
        assertEquals(EchelonScope.Kind.ALL, EchelonScope.kindOf(" "));
        assertEquals(EchelonScope.Kind.ALL, EchelonScope.kindOf("*"));
        assertNull(EchelonScope.kindOf("REGION:IDF"));
        assertEquals(EchelonScope.Kind.COUNTRY, EchelonScope.kindOf("COUNTRY:FR"));
        assertEquals("", EchelonScope.codeOf("REGION:IDF"));
        assertEquals("", EchelonScope.codeOf("*"));
        assertEquals("07039", EchelonScope.codeOf("07039"));
    }

    /**
     * A stored list keeps the valid scopes once, in their order; the refused
     * parts are named; an empty list joins into nothing.
     */
    @Test
    void listsAreSplitCheckedAndJoined() {
        assertEquals(List.of(), EchelonScope.parseList(null));
        assertEquals(List.of("PDV:07039", "ENSEIGNE:ITM"),
                EchelonScope.parseList("07039; ENSEIGNE:ITM,\nPDV:07039;REGION:IDF;;"));
        assertEquals(List.of(), EchelonScope.invalidParts(null));
        assertEquals(List.of("REGION:IDF"), EchelonScope.invalidParts("PDV:1; ;REGION:IDF"));
        assertNull(EchelonScope.join(null));
        assertNull(EchelonScope.join(List.of()));
        assertEquals("PDV:1;TPV:POS01", EchelonScope.join(List.of("PDV:1", "TPV:POS01")));
    }

    /**
     * Countries, enseignes, points of sale and the whole organisation are node
     * level; the register kinds and an invalid scope are not.
     */
    @Test
    void nodeLevelKindsAreTold() {
        assertTrue(EchelonScope.isNodeLevel("*"));
        assertTrue(EchelonScope.isNodeLevel("PDV:1"));
        assertFalse(EchelonScope.isNodeLevel("TPV_TYPE:CASH_REGISTER"));
        assertFalse(EchelonScope.isNodeLevel("TPV:POS01"));
        assertFalse(EchelonScope.isNodeLevel("REGION:IDF"));
    }

    /**
     * Each kind covers the node it names; the register kinds cover every
     * node; an invalid scope or an unknown node covers nothing it names.
     */
    @Test
    void scopesCoverTheNodesTheyName() {
        assertFalse(EchelonScope.coversNode("REGION:IDF", NODE));
        assertTrue(EchelonScope.coversNode("*", null));
        assertTrue(EchelonScope.coversNode("TPV_TYPE:CASH_REGISTER", null));
        assertTrue(EchelonScope.coversNode("TPV:POS09", null));
        assertTrue(EchelonScope.coversNode("COUNTRY:FR", NODE));
        assertFalse(EchelonScope.coversNode("COUNTRY:BE", NODE));
        assertFalse(EchelonScope.coversNode("COUNTRY:FR", null));
        assertTrue(EchelonScope.coversNode("ENSEIGNE:ITM", NODE));
        assertFalse(EchelonScope.coversNode("ENSEIGNE:ITM", null));
        assertFalse(EchelonScope.coversNode("ENSEIGNE:SUPER", NODE));
        assertTrue(EchelonScope.coversNode("PDV:07039", NODE));
        assertFalse(EchelonScope.coversNode("PDV:07039", null));
        assertFalse(EchelonScope.coversNode("PDV:07040", NODE));
    }

    /**
     * No node-level scope admits everywhere; otherwise one must cover the node.
     */
    @Test
    void aNodeAdmitsTheHoldersItBelongsTo() {
        assertTrue(EchelonScope.admitsNode(null, NODE));
        assertTrue(EchelonScope.admitsNode(List.of("TPV:POS01"), NODE));
        assertTrue(EchelonScope.admitsNode(List.of("PDV:1", "ENSEIGNE:ITM"), NODE));
        assertFalse(EchelonScope.admitsNode(List.of("PDV:1", "COUNTRY:BE"), NODE));
    }

    /**
     * A register admits a holder its node admits, and among the register
     * scopes one naming it or its type; a holder without register scope signs
     * in on every register.
     */
    @Test
    void aRegisterAdmitsTheHoldersAttachedToIt() {
        assertFalse(EchelonScope.admitsRegister(List.of("PDV:1"), NODE, "POS01", "CASH_REGISTER"));
        assertTrue(EchelonScope.admitsRegister(null, NODE, "POS01", null));
        assertTrue(EchelonScope.admitsRegister(List.of("PDV:07039"), NODE, "POS01", null));
        assertTrue(EchelonScope.admitsRegister(List.of("TPV:POS02", "TPV:POS01"), NODE, "POS01", null));
        assertTrue(EchelonScope.admitsRegister(List.of("TPV_TYPE:SELF_CHECKOUT_NCR"), NODE, "SCO1",
                "SELF_CHECKOUT_NCR"));
        assertFalse(EchelonScope.admitsRegister(List.of("TPV_TYPE:SELF_CHECKOUT_NCR", "TPV:POS02"), NODE,
                "POS01", "CASH_REGISTER"));
    }

    /**
     * Every kind reads in French; an invalid scope reads as typed.
     */
    @Test
    void scopesAreLabelled() {
        assertEquals("REGION:IDF", EchelonScope.label("REGION:IDF"));
        assertEquals("Tous les échelons", EchelonScope.label(null));
        assertEquals("Pays FR", EchelonScope.label("COUNTRY:FR"));
        assertEquals("Enseigne ITM", EchelonScope.label("ENSEIGNE:ITM"));
        assertEquals("PDV 07039", EchelonScope.label("07039"));
        assertEquals("Type de TPV CASH_REGISTER", EchelonScope.label("TPV_TYPE:CASH_REGISTER"));
        assertEquals("TPV POS01", EchelonScope.label("TPV:POS01"));
    }
}
