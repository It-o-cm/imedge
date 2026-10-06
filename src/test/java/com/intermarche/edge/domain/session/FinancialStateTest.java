package com.intermarche.edge.domain.session;

import com.intermarche.edge.domain.people.Employee;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FinancialState}.
 *
 * <p>Plain POJO tests: the signature, the line attachment and the checksum are
 * pure in-memory logic, so no Panache and no Quarkus context are booted. The
 * signature's canonical string drives the branch enumeration — every {@code ?:}
 * guard and both operands of the compound cashier-login guard are exercised
 * against SHA-256 values captured out of band.
 */
class FinancialStateTest {

    /**
     * Builds a cashier employee carrying the given login.
     *
     * @param login the login name, or null
     * @return the employee
     */
    private Employee cashier(String login) {
        Employee employee = new Employee();
        employee.loginName = login;
        return employee;
    }

    /**
     * {@code computeSignature} hashes a fully populated state — every nullable
     * guard taking its non-null arm, the cashier present with a login — to the
     * captured SHA-256, lower-case and 64 hex characters long.
     */
    @Test
    void computeSignatureHashesAFullyPopulatedState() {
        FinancialState state = new FinancialState();
        state.stateType = FinancialState.StateType.Z;
        state.scope = FinancialState.StateScope.CASHIER;
        state.periodKind = FinancialState.PeriodKind.DAY;
        state.cashier = cashier("alice");
        state.sequence = 7L;
        state.fromInstant = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
        state.toInstant = LocalDateTime.of(2026, 1, 1, 20, 0, 0);
        state.grossTurnover = new BigDecimal("1234.5600");
        state.netTurnover = new BigDecimal("1200.0000");
        state.customerCount = 42;
        state.itemCount = 100;
        state.previousSignature = "abc123";
        assertEquals("ed1e15beea2bfec1ee9e25b38e77e1cee0a915286a6e9811282a3510a7273b8d",
                state.computeSignature());
    }

    /**
     * {@code computeSignature} hashes a state whose every nullable field is null —
     * every guard taking its empty-string arm, the cashier absent — to the
     * captured SHA-256 (the null arms, and the left operand of the cashier guard).
     */
    @Test
    void computeSignatureHashesAnEmptyState() {
        FinancialState state = new FinancialState();
        assertEquals("130bd60b3a5c8cae5cac1e05efe1e34919a98c170573a15d2f9d596de00d11e0",
                state.computeSignature());
    }

    /**
     * {@code computeSignature} treats a cashier whose login is null as no login
     * (the right operand of the compound guard), matching the captured SHA-256
     * of a store-scope week chain.
     */
    @Test
    void computeSignatureTreatsANullLoginAsNoLogin() {
        FinancialState state = new FinancialState();
        state.stateType = FinancialState.StateType.Z;
        state.scope = FinancialState.StateScope.STORE;
        state.periodKind = FinancialState.PeriodKind.WEEK;
        state.cashier = cashier(null);
        state.sequence = 3L;
        state.fromInstant = LocalDateTime.of(2026, 1, 5, 0, 0, 0);
        state.toInstant = LocalDateTime.of(2026, 1, 12, 0, 0, 0);
        assertEquals("791d49a8e4650326361de12ef430a243bf560eff19efa43d435ade749046cae5",
                state.computeSignature());
    }

    /**
     * {@code computeSignature} is content-sensitive: changing one salient field
     * (the sequence) yields a different signature, so the chain commits to the
     * frozen figures.
     */
    @Test
    void computeSignatureChangesWithContent() {
        FinancialState base = new FinancialState();
        base.stateType = FinancialState.StateType.Z;
        base.scope = FinancialState.StateScope.STORE;
        base.periodKind = FinancialState.PeriodKind.DAY;
        base.sequence = 1L;
        base.fromInstant = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        base.toInstant = LocalDateTime.of(2026, 1, 2, 0, 0, 0);
        String first = base.computeSignature();
        base.sequence = 2L;
        assertNotEquals(first, base.computeSignature());
    }

    /**
     * {@code addLine} attaches the line and back-links it to the state, keeping
     * the association consistent on both ends.
     */
    @Test
    void addLineAttachesAndBackLinks() {
        FinancialState state = new FinancialState();
        FinancialStateLine line = new FinancialStateLine();
        state.addLine(line);
        assertEquals(1, state.lines.size());
        assertSame(line, state.lines.get(0));
        assertSame(state, line.state);
    }

    /**
     * {@code getChecksum} is equal for two states carrying the same salient
     * fields and differs when one of them changes, so change detection holds.
     */
    @Test
    void getChecksumReflectsSalientFields() {
        FinancialState a = new FinancialState();
        a.stateType = FinancialState.StateType.Z;
        a.scope = FinancialState.StateScope.STORE;
        a.periodKind = FinancialState.PeriodKind.DAY;
        a.sequence = 1L;
        a.netTurnover = new BigDecimal("10.0000");
        a.signature = "sig";
        FinancialState b = new FinancialState();
        b.stateType = FinancialState.StateType.Z;
        b.scope = FinancialState.StateScope.STORE;
        b.periodKind = FinancialState.PeriodKind.DAY;
        b.sequence = 1L;
        b.netTurnover = new BigDecimal("10.0000");
        b.signature = "sig";
        assertEquals(a.getChecksum(), b.getChecksum());
        b.sequence = 2L;
        assertNotEquals(a.getChecksum(), b.getChecksum());
    }

    /**
     * The three enumerations expose exactly the values the model names, so a
     * persisted string column round-trips to the right constant.
     */
    @Test
    void enumerationsExposeTheModelledValues() {
        assertEquals(2, FinancialState.StateType.values().length);
        assertEquals(2, FinancialState.StateScope.values().length);
        assertEquals(3, FinancialState.PeriodKind.values().length);
        assertTrue(FinancialState.StateType.valueOf("X") == FinancialState.StateType.X);
    }
}
