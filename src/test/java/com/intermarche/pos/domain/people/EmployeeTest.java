package com.intermarche.pos.domain.people;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Employee}, targeting 100% branch coverage.
 * <p>
 * The branching methods are {@code isCurrentlyLocked} (the {@code lockedUntil}
 * null guard combined with the {@code isAfter} check), {@code getRoles} (the
 * {@code role} null guard) and {@code verifyPassword} (the two-operand null
 * guard), {@code hasBackOfficeAccess} (the {@code backOfficePassword} null guard
 * combined with the {@code isBlank} check), {@code setBackOfficePassword} (the
 * null-vs-hash ternary) and {@code verifyBackOfficePassword} (the two-operand
 * short-circuit guard). {@code findLogin} and {@code findActiveLogin} resolve the Panache
 * static finder, which under plain {@code mvn test} falls back to
 * {@link PanacheEntityBase}, so they are intercepted with
 * {@link org.mockito.Mockito#mockStatic}. {@code hashPassword} and the passing
 * arm of {@code verifyPassword} exercise the real BCrypt round-trip. The
 * remaining accessors, {@code present} and {@code getChecksum} are exercised for
 * line coverage. Every test is fully isolated and asserts absolute expected
 * values.
 */
class EmployeeTest {

    /**
     * Builds a mocked {@link PanacheQuery} whose {@code firstResult()} yields the
     * given employee, mirroring the finder's terminal call.
     *
     * @param employee the employee to return, or null for none
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Employee> query(Employee employee) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        return query;
    }

    /**
     * A fresh employee is active by default and has zero failed attempts via the
     * field initializers.
     */
    @Test
    void defaultsAreActiveAndUnlocked() {
        Employee employee = new Employee();
        Assertions.assertTrue(employee.active);
        Assertions.assertEquals(0, employee.failedAttempts);
        Assertions.assertNull(employee.lockedUntil);
    }

    /**
     * isCurrentlyLocked returns false when lockedUntil is null (first operand
     * false arm).
     */
    @Test
    void isCurrentlyLockedNullReturnsFalse() {
        Employee employee = new Employee();
        employee.lockedUntil = null;
        Assertions.assertFalse(employee.isCurrentlyLocked());
    }

    /**
     * isCurrentlyLocked returns true when lockedUntil is in the future (both
     * operands true).
     */
    @Test
    void isCurrentlyLockedFutureReturnsTrue() {
        Employee employee = new Employee();
        employee.lockedUntil = LocalDateTime.now().plusHours(1);
        Assertions.assertTrue(employee.isCurrentlyLocked());
    }

    /**
     * isCurrentlyLocked returns false when lockedUntil is in the past (first
     * operand true, second false).
     */
    @Test
    void isCurrentlyLockedPastReturnsFalse() {
        Employee employee = new Employee();
        employee.lockedUntil = LocalDateTime.now().minusHours(1);
        Assertions.assertFalse(employee.isCurrentlyLocked());
    }

    /**
     * getLoginName returns the loginName field.
     */
    @Test
    void getLoginNameReturnsLoginName() {
        Employee employee = new Employee();
        employee.loginName = "jdoe";
        Assertions.assertEquals("jdoe", employee.getLoginName());
    }

    /**
     * getPassword returns the password hash field.
     */
    @Test
    void getPasswordReturnsPassword() {
        Employee employee = new Employee();
        employee.password = "hash";
        Assertions.assertEquals("hash", employee.getPassword());
    }

    /**
     * getRoles returns an empty set when role is null (null-guard true arm).
     */
    @Test
    void getRolesNullReturnsEmptySet() {
        Employee employee = new Employee();
        employee.role = null;
        Assertions.assertEquals(Set.of(), employee.getRoles());
    }

    /**
     * getRoles returns the singleton role name when role is set (null-guard false
     * arm).
     */
    @Test
    void getRolesNonNullReturnsSingletonName() {
        Employee employee = new Employee();
        employee.role = Employee.EmployeeRole.CASHIER;
        Assertions.assertEquals(Set.of("CASHIER"), employee.getRoles());
    }

    /**
     * isEnabled mirrors the active flag when active.
     */
    @Test
    void isEnabledTrueWhenActive() {
        Employee employee = new Employee();
        employee.active = true;
        Assertions.assertTrue(employee.isEnabled());
    }

    /**
     * isEnabled mirrors the active flag when inactive.
     */
    @Test
    void isEnabledFalseWhenInactive() {
        Employee employee = new Employee();
        employee.active = false;
        Assertions.assertFalse(employee.isEnabled());
    }


    /**
     * getFullName concatenates first and last name with a single space.
     */
    @Test
    void getFullNameConcatenatesNames() {
        Employee employee = new Employee();
        employee.firstName = "Jane";
        employee.lastName = "Doe";
        Assertions.assertEquals("Jane Doe", employee.getFullName());
    }

    /**
     * getEmail returns the email field.
     */
    @Test
    void getEmailReturnsEmail() {
        Employee employee = new Employee();
        employee.email = "jane@example.com";
        Assertions.assertEquals("jane@example.com", employee.getEmail());
    }



    /**
     * hashPassword produces a BCrypt hash that verifyPassword accepts, exercising
     * the passing arm of the real BCrypt round-trip.
     */
    @Test
    void hashPasswordRoundTripsWithVerifyPassword() {
        Employee employee = new Employee();
        employee.password = Employee.hashPassword("1234");
        Assertions.assertTrue(employee.verifyPassword("1234"));
    }

    /**
     * verifyPassword returns false for a raw PIN that does not match the stored
     * hash (BCrypt.checkpw false arm).
     */
    @Test
    void verifyPasswordWrongPinReturnsFalse() {
        Employee employee = new Employee();
        employee.password = Employee.hashPassword("1234");
        Assertions.assertFalse(employee.verifyPassword("9999"));
    }

    /**
     * verifyPassword returns false when the stored hash is null (first operand
     * true arm, short-circuit).
     */
    @Test
    void verifyPasswordNullHashReturnsFalse() {
        Employee employee = new Employee();
        employee.password = null;
        Assertions.assertFalse(employee.verifyPassword("1234"));
    }

    /**
     * verifyPassword returns false when the raw PIN is null (first operand false,
     * second operand true arm).
     */
    @Test
    void verifyPasswordNullRawReturnsFalse() {
        Employee employee = new Employee();
        employee.password = "hash";
        Assertions.assertFalse(employee.verifyPassword(null));
    }

    /**
     * hasBackOfficeAccess returns false when no back-office password is stored
     * (first operand false arm, short-circuit).
     */
    @Test
    void hasBackOfficeAccessNullReturnsFalse() {
        Employee employee = new Employee();
        employee.backOfficePassword = null;
        Assertions.assertFalse(employee.hasBackOfficeAccess());
    }

    /**
     * hasBackOfficeAccess returns false when the stored back-office password is
     * blank (first operand true, isBlank true so the negated second operand is
     * false).
     */
    @Test
    void hasBackOfficeAccessBlankReturnsFalse() {
        Employee employee = new Employee();
        employee.backOfficePassword = "   ";
        Assertions.assertFalse(employee.hasBackOfficeAccess());
    }

    /**
     * hasBackOfficeAccess returns true when a non-blank back-office password is
     * stored (both operands true).
     */
    @Test
    void hasBackOfficeAccessNonBlankReturnsTrue() {
        Employee employee = new Employee();
        employee.backOfficePassword = "hash";
        Assertions.assertTrue(employee.hasBackOfficeAccess());
    }

    /**
     * setBackOfficePassword withdraws access by nulling the field when given null
     * (ternary null arm).
     */
    @Test
    void setBackOfficePasswordNullWithdrawsAccess() {
        Employee employee = new Employee();
        employee.backOfficePassword = "existing";
        employee.setBackOfficePassword(null);
        Assertions.assertNull(employee.backOfficePassword);
    }

    /**
     * setBackOfficePassword hashes a non-null clear-text password (ternary hash
     * arm), producing a stored value distinct from the raw input yet accepted by
     * verifyBackOfficePassword through the real BCrypt round-trip.
     */
    @Test
    void setBackOfficePasswordNonNullHashesAndVerifies() {
        Employee employee = new Employee();
        employee.setBackOfficePassword("s3cret");
        Assertions.assertNotEquals("s3cret", employee.backOfficePassword);
        Assertions.assertTrue(employee.verifyBackOfficePassword("s3cret"));
    }

    /**
     * verifyBackOfficePassword returns false when no back-office password is set
     * (first operand true after negation, short-circuit).
     */
    @Test
    void verifyBackOfficePasswordNoAccessReturnsFalse() {
        Employee employee = new Employee();
        employee.backOfficePassword = null;
        Assertions.assertFalse(employee.verifyBackOfficePassword("s3cret"));
    }

    /**
     * verifyBackOfficePassword returns false when a password exists but the raw
     * input is null (first operand false, second operand true).
     */
    @Test
    void verifyBackOfficePasswordNullRawReturnsFalse() {
        Employee employee = new Employee();
        employee.setBackOfficePassword("s3cret");
        Assertions.assertFalse(employee.verifyBackOfficePassword(null));
    }

    /**
     * verifyBackOfficePassword returns false when a non-null raw password does not
     * match the stored hash (both guard operands false, BCrypt.checkpw false arm).
     */
    @Test
    void verifyBackOfficePasswordWrongReturnsFalse() {
        Employee employee = new Employee();
        employee.setBackOfficePassword("s3cret");
        Assertions.assertFalse(employee.verifyBackOfficePassword("wrong"));
    }

    /**
     * verifyBackOfficePassword returns true when a non-null raw password matches
     * the stored hash (both guard operands false, BCrypt.checkpw true arm).
     */
    @Test
    void verifyBackOfficePasswordCorrectReturnsTrue() {
        Employee employee = new Employee();
        employee.setBackOfficePassword("s3cret");
        Assertions.assertTrue(employee.verifyBackOfficePassword("s3cret"));
    }

    /**
     * findLogin returns the query's first result when an account matches the badge
     * or login name.
     */
    @Test
    void findLoginReturnsMatch() {
        Employee found = new Employee();
        PanacheQuery<Employee> query = query(found);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.find("badgeId = ?1 or loginName = ?1", "B01")).thenReturn(query);
            Assertions.assertSame(found, Employee.findLogin("B01"));
        }
    }

    /**
     * findLogin returns null when no account matches.
     */
    @Test
    void findLoginReturnsNullWhenNoMatch() {
        PanacheQuery<Employee> query = query(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.find("badgeId = ?1 or loginName = ?1", "B99")).thenReturn(query);
            Assertions.assertNull(Employee.findLogin("B99"));
        }
    }

    /**
     * findActiveLogin returns the query's first result when an active account
     * matches.
     */
    @Test
    void findActiveLoginReturnsMatch() {
        Employee found = new Employee();
        PanacheQuery<Employee> query = query(found);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.find("(badgeId = ?1 or loginName = ?1) and active = true", "B01"))
                    .thenReturn(query);
            Assertions.assertSame(found, Employee.findActiveLogin("B01"));
        }
    }

    /**
     * findActiveLogin returns null when no active account matches.
     */
    @Test
    void findActiveLoginReturnsNullWhenNoMatch() {
        PanacheQuery<Employee> query = query(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.find("(badgeId = ?1 or loginName = ?1) and active = true", "B99"))
                    .thenReturn(query);
            Assertions.assertNull(Employee.findActiveLogin("B99"));
        }
    }

    /**
     * getChecksum returns the Objects.hash of the seven business fields.
     */
    @Test
    void getChecksumMatchesObjectsHash() {
        Employee employee = new Employee();
        employee.firstName = "Jane";
        employee.lastName = "Doe";
        employee.password = "hash";
        employee.role = Employee.EmployeeRole.MANAGER;
        employee.badgeId = "B01";
        employee.active = true;
        employee.email = "jane@example.com";
        int expected = Objects.hash("Jane", "Doe", "hash", Employee.EmployeeRole.MANAGER, "B01", true,
                "jane@example.com");
        Assertions.assertEquals(expected, employee.getChecksum());
    }

    /**
     * The EmployeeRole enum exposes exactly its four declared constants and
     * round-trips through valueOf.
     */
    @Test
    void employeeRoleEnumHasFourConstants() {
        Assertions.assertEquals(4, Employee.EmployeeRole.values().length);
        Assertions.assertEquals(Employee.EmployeeRole.ADMIN, Employee.EmployeeRole.valueOf("ADMIN"));
        Assertions.assertEquals(Employee.EmployeeRole.MANAGER, Employee.EmployeeRole.valueOf("MANAGER"));
        Assertions.assertEquals(Employee.EmployeeRole.PICKER, Employee.EmployeeRole.valueOf("PICKER"));
        Assertions.assertEquals(Employee.EmployeeRole.CASHIER, Employee.EmployeeRole.valueOf("CASHIER"));
    }
}
