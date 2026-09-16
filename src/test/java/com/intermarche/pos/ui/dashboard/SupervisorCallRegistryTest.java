package com.intermarche.pos.ui.dashboard;

import com.intermarche.pos.ui.dashboard.SupervisorCallRegistry.Call;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Call call = new Call(7L, "T1", "Alice", "price check");
        assertEquals(7L, call.id);
        assertEquals("T1", call.terminalId);
        assertEquals("Alice", call.operator);
        assertEquals("price check", call.reason);
        assertEquals(5, call.time.length());
        assertEquals(':', call.time.charAt(2));
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
        registry.add("T2", "Bob", "override");
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
        registry.add("T3", "Carol", "same");
        registry.add("T3", "Carol", "same");
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
        registry.add("T4", "Dan", "help");
        List<Call> snapshot = registry.getPending();
        registry.add("T5", "Eve", "help2");
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new Call(99L, "X", "Y", "Z")));
    }

    /**
     * acknowledge removes only the call whose id matches, exercising the
     * true arm of the id predicate.
     */
    @Test
    void acknowledgeRemovesMatchingCall() {
        SupervisorCallRegistry registry = new SupervisorCallRegistry();
        registry.add("T6", "Frank", "one");
        registry.add("T7", "Grace", "two");
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
        registry.add("T8", "Heidi", "one");
        registry.acknowledge(999L);
        List<Call> pending = registry.getPending();
        assertEquals(1, pending.size());
        assertEquals(1L, pending.get(0).id);
    }
}
