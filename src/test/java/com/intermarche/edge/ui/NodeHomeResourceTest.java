package com.intermarche.edge.ui;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link NodeHomeResource}, the root of the store node.
 * <p>
 * The class holds one gesture and no collaborator: nothing to mock, nothing to
 * boot. What is worth asserting is the exact target and the exact status — the
 * whole point of the class is that {@code http://…:8070} lands somewhere
 * instead of answering 404, and a 200 or a 302 here would each be a different
 * contract for the browser.
 */
class NodeHomeResourceTest {

    /**
     * The root sends the operator to the back office, and says so with a 303:
     * the address of the node alone is enough, {@code /admin} never has to be
     * typed.
     */
    @Test
    void theRootSendsTheOperatorToTheBackOffice() {
        Response response = new NodeHomeResource().root();
        assertEquals(303, response.getStatus());
        assertEquals("/admin", response.getLocation().toString());
    }

    /**
     * The landing is the back office's OWN root, not one of its screens: that
     * is where the per-role routing already lives, and naming a screen here
     * would fork the decision in two places.
     */
    @Test
    void theLandingIsTheBackOfficeRootAndNotAScreen() {
        assertEquals("/admin", NodeHomeResource.LANDING);
    }
}
