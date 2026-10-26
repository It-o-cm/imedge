package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.sync.RegisterCommand;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegisterCommandService}.
 * <p>
 * The order book is pure Panache: every static finder of
 * {@link RegisterCommand} resolves to {@link PanacheEntityBase} under a plain
 * unit test and is intercepted with {@link org.mockito.Mockito#mockStatic},
 * while the row written by {@code issue} has its {@code persist()} neutralized
 * with {@link org.mockito.Mockito#mockConstruction}. No Quarkus context and no
 * database is booted.
 * <p>
 * Branch enumeration (every arm exercised): {@code issue} covers the
 * already-pending arm and the fresh arm; {@code acknowledge} covers the three
 * legs of {@code command == null || command.consumedAt != null} — unknown
 * token, order already stamped, and both false — plus the stamping arm;
 * {@code pending} and {@code pendingCount} are branchless and are asserted on
 * the exact query each one issues.
 */
class RegisterCommandServiceTest {

    /** The query {@code issue} runs to find an order already waiting. */
    private static final String PENDING_OF_KIND =
            "terminalId = ?1 and commandType = ?2 and consumedAt is null";

    /** The query {@code RegisterCommand.pendingFor} runs. */
    private static final String PENDING_FOR =
            "terminalId = ?1 and consumedAt is null order by issuedAt";

    /** The query {@code RegisterCommand.countPending} runs. */
    private static final String COUNT_PENDING = "terminalId = ?1 and consumedAt is null";

    /** The service under test. */
    private final RegisterCommandService service = new RegisterCommandService();

    /**
     * Stubs the lookup {@code issue} performs.
     *
     * @param panache the active PanacheEntityBase mock
     * @param existing the order to resolve, or null when none waits
     */
    @SuppressWarnings("unchecked")
    private void stubPendingOfKind(MockedStatic<PanacheEntityBase> panache, RegisterCommand existing) {
        PanacheQuery<RegisterCommand> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(existing);
        panache.when(() -> RegisterCommand.find(PENDING_OF_KIND, "POS07",
                RegisterCommand.CommandType.CLOSE_SESSION)).thenReturn(query);
    }

    /**
     * An order left for a register that has none waiting is created, stamped
     * with a fresh token and persisted.
     */
    @Test
    void issueWritesAFreshOrder() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<RegisterCommand> construction = mockConstruction(RegisterCommand.class)) {
            stubPendingOfKind(panache, null);
            RegisterCommand command = service.issue("POS07",
                    RegisterCommand.CommandType.CLOSE_SESSION, "Marie Curie", "fin de service");
            assertEquals(1, construction.constructed().size());
            assertSame(construction.constructed().get(0), command);
            assertNotNull(command.commandUid);
            assertEquals("POS07", command.terminalId);
            assertEquals(RegisterCommand.CommandType.CLOSE_SESSION, command.commandType);
            assertEquals("Marie Curie", command.issuedBy);
            assertEquals("fin de service", command.reason);
            assertNotNull(command.issuedAt);
            verify(command).persist();
        }
    }

    /**
     * A second request for the same register and the same kind hands back the
     * order already waiting and writes nothing: the button pressed twice does
     * not close the register twice.
     */
    @Test
    void issueIsIdempotentPerRegisterAndKind() {
        RegisterCommand existing = mock(RegisterCommand.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<RegisterCommand> construction = mockConstruction(RegisterCommand.class)) {
            stubPendingOfKind(panache, existing);
            RegisterCommand command = service.issue("POS07",
                    RegisterCommand.CommandType.CLOSE_SESSION, "Marie Curie", "fin de service");
            assertSame(existing, command);
            assertTrue(construction.constructed().isEmpty());
            verify(existing, never()).persist();
        }
    }

    /**
     * The pending orders of a register are read with the oldest-first query.
     */
    @Test
    @SuppressWarnings("unchecked")
    void pendingReadsTheRegisterOrdersOldestFirst() {
        RegisterCommand waiting = mock(RegisterCommand.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<RegisterCommand> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(waiting));
            panache.when(() -> RegisterCommand.find(PENDING_FOR, "POS07")).thenReturn(query);
            List<RegisterCommand> commands = service.pending("POS07");
            assertEquals(1, commands.size());
            assertSame(waiting, commands.get(0));
        }
    }

    /**
     * A register with nothing waiting gets an empty list, not a null.
     */
    @Test
    @SuppressWarnings("unchecked")
    void pendingIsEmptyWhenNothingWaits() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<RegisterCommand> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of());
            panache.when(() -> RegisterCommand.find(PENDING_FOR, "POS07")).thenReturn(query);
            assertTrue(service.pending("POS07").isEmpty());
        }
    }

    /**
     * An acknowledgement carrying a token the node does not know is answered
     * false rather than with an exception (first leg of the guard).
     */
    @Test
    @SuppressWarnings("unchecked")
    void acknowledgeIgnoresAnUnknownToken() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<RegisterCommand> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> RegisterCommand.find("commandUid", "ghost")).thenReturn(query);
            assertFalse(service.acknowledge("ghost"));
        }
    }

    /**
     * An order acknowledged twice keeps its FIRST stamp and is answered false
     * (second leg of the guard).
     */
    @Test
    @SuppressWarnings("unchecked")
    void acknowledgeKeepsTheFirstStamp() {
        RegisterCommand command = mock(RegisterCommand.class);
        LocalDateTime firstStamp = LocalDateTime.of(2026, 9, 16, 20, 14);
        command.consumedAt = firstStamp;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<RegisterCommand> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(command);
            panache.when(() -> RegisterCommand.find("commandUid", "u-1")).thenReturn(query);
            assertFalse(service.acknowledge("u-1"));
            assertEquals(firstStamp, command.consumedAt);
            verify(command, never()).persist();
        }
    }

    /**
     * A pending order presented once is stamped and persisted (both legs of the
     * guard false).
     */
    @Test
    @SuppressWarnings("unchecked")
    void acknowledgeStampsAPendingOrder() {
        RegisterCommand command = mock(RegisterCommand.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<RegisterCommand> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(command);
            panache.when(() -> RegisterCommand.find("commandUid", "u-1")).thenReturn(query);
            assertTrue(service.acknowledge("u-1"));
            assertNotNull(command.consumedAt);
            verify(command).persist();
        }
    }

    /**
     * The screen's badge counts the orders still waiting for one register.
     */
    @Test
    void pendingCountReadsTheWaitingOrders() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RegisterCommand.count(COUNT_PENDING, "POS07")).thenReturn(1L);
            assertEquals(1L, service.pendingCount("POS07"));
        }
    }

    /**
     * A register with no order waiting counts zero.
     */
    @Test
    void pendingCountIsZeroWhenNothingWaits() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RegisterCommand.count(COUNT_PENDING, "POS02")).thenReturn(0L);
            assertEquals(0L, service.pendingCount("POS02"));
        }
    }

    /**
     * Whether an order of one kind is pending for a register: one counted is
     * pending, none is not.
     */
    @Test
    void isPendingCountsTheOrdersOfAKind() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RegisterCommand.count(PENDING_OF_KIND, "POS07",
                    RegisterCommand.CommandType.VERIFY_SYNC)).thenReturn(1L);
            assertTrue(service.isPending("POS07", RegisterCommand.CommandType.VERIFY_SYNC));
            assertFalse(service.isPending("POS07", RegisterCommand.CommandType.PURGE_PARKED_TICKETS));
        }
    }
}
