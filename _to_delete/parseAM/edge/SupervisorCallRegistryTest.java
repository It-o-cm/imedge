package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.ui.dashboard.SupervisorCallRegistry.Call;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SupervisorCallRegistry}.
 */
class SupervisorCallRegistryTest {

    /**
     * The Call constructor stores all fields verbatim and formats the
     * reception time as HH:mm (five characters, colon in the middle).
     */
    @Test
    void callConstructorStoresFieldsAndFormatsTime() {
        Call call = new Call(7L, "T1", "Alice", "price check", null);
        assertEquals(7L, call.id);
        assertEquals("T1", call.terminalId);
        assertEquals("Alice", call.operator);
        assertEquals("price check", call.reason);
        assertEquals("", call.message);
        assertEquals(5, call.time.length());
        assertEquals(':', call.time.charAt(2));
        Call withMessage = new Call(8L, "T1", "Alice", "Autre", "besoin d'un sac");
        assertEquals("besoin d'un sac", withMessage.message);
    }

    /**
     * A freshly created registry has no pending calls.
     */
    @Test
    void getPendingIsEmptyOnFreshRegistry() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        assertTrue(registry.getPending().isEmpty());
    }

    /**
     * add registers a call, assigning it the next sequence id and preserving
     * the supplied fields.
     */
    @Test
    void addRegistersCallWithSequencedId() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("T2", "Bob", "override", "");
        List<Call> pending = registry.getPending();
        assertEquals(1, pending.size());
        Call call = pending.get(0);
        assertEquals(1L, call.id);
        assertEquals("T2", call.terminalId);
        assertEquals("Bob", call.operator);
        assertEquals("override", call.reason);
    }

    /**
     * add does not deduplicate: two identical calls yield two distinct
     * entries with increasing ids, oldest first.
     */
    @Test
    void addKeepsOrderAndDoesNotDeduplicate() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("T3", "Carol", "same", "");
        registry.add("T3", "Carol", "same", "");
        List<Call> pending = registry.getPending();
        assertEquals(2, pending.size());
        assertEquals(1L, pending.get(0).id);
        assertEquals(2L, pending.get(1).id);
        assertNotEquals(pending.get(0).id, pending.get(1).id);
    }

    /**
     * getPending returns an immutable snapshot copy, unaffected by later
     * additions and rejecting mutation.
     */
    @Test
    void getPendingReturnsImmutableSnapshot() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("T4", "Dan", "help", "");
        List<Call> snapshot = registry.getPending();
        registry.add("T5", "Eve", "help2", "");
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new Call(99L, "X", "Y", "Z", null)));
    }

    /**
     * acknowledge removes only the call whose id matches, exercising the
     * true arm of the id predicate.
     */
    @Test
    void acknowledgeRemovesMatchingCall() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("T6", "Frank", "one", "");
        registry.add("T7", "Grace", "two", "");
        registry.acknowledge(1L);
        List<Call> pending = registry.getPending();
        assertEquals(1, pending.size());
        assertEquals(2L, pending.get(0).id);
    }

    /**
     * acknowledge with an unknown id leaves the pending list untouched,
     * exercising the false arm of the id predicate.
     */
    @Test
    void acknowledgeWithUnknownIdKeepsAllCalls() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("T8", "Heidi", "one", "");
        registry.acknowledge(999L);
        List<Call> pending = registry.getPending();
        assertEquals(1, pending.size());
        assertEquals(1L, pending.get(0).id);
    }

    /**
     * BO-04-02-07: a call filed with the priority of its administered type
     * carries it, and the historical four-argument filing carries none —
     * which is what a lane still reading the semicolon setting produces.
     */
    @Test
    void aCallCarriesThePriorityItWasFiledWith() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("T8", "Hugo", "Annulation", "", 3);
        registry.add("T9", "Ida", "Autre", "");
        assertEquals(3, registry.getPending().get(0).severity);
        assertEquals(0, registry.getPending().get(1).severity);
    }

    /**
     * BO-04-02-04 / BO-04-02-05: a call settled at the back office is marked
     * with what became of it and KEPT on the board when the shop asked for
     * that; the refusal wording is kept beside it.
     */
    @Test
    void aSettledCallIsMarkedAndKept() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("T10", "Jo", "Annulation", "");
        long id = registry.getPending().get(0).id;
        SupervisorCallRegistry.Call settled = registry.settle(id,
                SupervisorCallRegistry.CallStatus.REFUSED, "Geste non autorise", true);
        assertEquals(SupervisorCallRegistry.CallStatus.REFUSED, settled.status);
        assertEquals("Geste non autorise", settled.refusalReason);
        assertEquals(1, registry.getPending().size());
    }

    /**
     * BO-04-02-04: a shop that asked for settled demands to disappear sees
     * them leave the board the moment they are settled; a null wording is
     * kept as an empty one rather than as a null the screen would print.
     */
    @Test
    void aSettledCallLeavesTheBoardWhenTheShopAsked() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("T11", "Kim", "Prix", "");
        long id = registry.getPending().get(0).id;
        SupervisorCallRegistry.Call settled = registry.settle(id,
                SupervisorCallRegistry.CallStatus.TO_HANDLE_AT_TILL, null, false);
        assertEquals("", settled.refusalReason);
        assertTrue(registry.getPending().isEmpty());
    }

    /**
     * An id nothing answers settles nothing and says so.
     */
    @Test
    void settlingAnUnknownCallAnswersNothing() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        assertNull(registry.settle(999L, SupervisorCallRegistry.CallStatus.REFUSED, "x", true));
    }

    /**
     * BO-04-02-03: a supervisor settling at the till marks EVERY open call
     * of that lane — two calls before the supervisor arrived are one need —
     * ignoring the case of the identifier, and leaves the other lanes alone.
     */
    @Test
    void settlingAtTheTillMarksEveryCallOfThatLane() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("CAISSE1", "Lea", "Annulation", "");
        registry.add("CAISSE1", "Lea", "Prix", "");
        registry.add("CAISSE2", "Max", "Autre", "");
        assertEquals(2, registry.settleAtTill("caisse1", true));
        assertEquals(SupervisorCallRegistry.CallStatus.HANDLED_AT_TILL,
                registry.getPending().get(0).status);
        assertEquals(SupervisorCallRegistry.CallStatus.HANDLED_AT_TILL,
                registry.getPending().get(1).status);
        assertEquals(SupervisorCallRegistry.CallStatus.PENDING,
                registry.getPending().get(2).status);
    }

    /**
     * BO-04-02-03: a call already settled is not settled a second time, a
     * lane nobody named settles nothing, and a shop that asked for them to
     * disappear sees them go.
     */
    @Test
    void settlingAtTheTillTouchesOnlyWhatIsStillOpen() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("CAISSE1", "Lea", "Annulation", "");
        long id = registry.getPending().get(0).id;
        registry.settle(id, SupervisorCallRegistry.CallStatus.REFUSED, "non", true);
        assertEquals(0, registry.settleAtTill("CAISSE1", true));
        assertEquals(SupervisorCallRegistry.CallStatus.REFUSED,
                registry.getPending().get(0).status);
        assertEquals(0, registry.settleAtTill(null, true));
        assertEquals(0, registry.settleAtTill("   ", true));
        registry.add("CAISSE3", "Noa", "Autre", "");
        assertEquals(1, registry.settleAtTill("CAISSE3", false));
        assertEquals(1, registry.getPending().size());
    }

    /**
     * A new call is PENDING and every status names itself for the board.
     */
    @Test
    void aNewCallIsPendingAndEveryStatusHasALabel() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("T12", "Oli", "Autre", "");
        assertEquals(SupervisorCallRegistry.CallStatus.PENDING,
                registry.getPending().get(0).status);
        assertEquals("", registry.getPending().get(0).refusalReason);
        for (SupervisorCallRegistry.CallStatus status
                : SupervisorCallRegistry.CallStatus.values()) {
            assertNotEquals(null, status.getLabel());
            assertFalse(status.getLabel().isBlank());
        }
    }
}
