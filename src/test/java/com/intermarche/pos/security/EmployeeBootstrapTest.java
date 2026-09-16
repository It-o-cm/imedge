package com.intermarche.pos.security;

import com.intermarche.pos.domain.people.Employee;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link EmployeeBootstrap}.
 * <p>
 * Under plain {@code mvn test} the static Panache counter resolves to
 * {@link PanacheEntityBase} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the created employee is
 * intercepted with {@code mockConstruction} so no real persistence runs.
 * Every leg of the guards has its case: password absent on an empty table
 * (the unusable-node error) and on a populated one, password blank, table
 * already populated, and the happy path.
 */
class EmployeeBootstrapTest {

    /**
     * Builds a bootstrap wired with the given configuration values.
     *
     * @param password the optional bootstrap password
     * @return the configured bootstrap under test
     */
    private EmployeeBootstrap bootstrap(Optional<String> password) {
        EmployeeBootstrap bootstrap = new EmployeeBootstrap();
        bootstrap.bootstrapUsername = "admin";
        bootstrap.bootstrapPassword = password;
        bootstrap.bootstrapEmail = "admin@pos.local";
        return bootstrap;
    }

    /**
     * An absent password disables the bootstrap (first guard,
     * {@code isEmpty} leg): no employee is constructed. The table being
     * empty as well, the node is unusable and the startup error arm is
     * taken.
     */
    @Test
    void onStartWithoutPasswordOnEmptyTableDoesNothing() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Employee> created = mockConstruction(Employee.class)) {
            mocked.when(Employee::count).thenReturn(0L);
            bootstrap(Optional.empty()).onStart(new StartupEvent());
            assertEquals(0, created.constructed().size());
        }
    }

    /**
     * An absent password on a POPULATED table is the normal state of a
     * register node, whose employees arrive by the referential pull: no
     * employee is constructed and nothing is reported (the false arm of
     * the unusable-node check).
     */
    @Test
    void onStartWithoutPasswordOnPopulatedTableDoesNothing() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Employee> created = mockConstruction(Employee.class)) {
            mocked.when(Employee::count).thenReturn(7L);
            bootstrap(Optional.empty()).onStart(new StartupEvent());
            assertEquals(0, created.constructed().size());
        }
    }

    /**
     * A blank password disables the bootstrap the same way (first guard,
     * {@code isBlank} leg).
     */
    @Test
    void onStartWithBlankPasswordDoesNothing() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Employee> created = mockConstruction(Employee.class)) {
            mocked.when(Employee::count).thenReturn(0L);
            bootstrap(Optional.of("  ")).onStart(new StartupEvent());
            assertEquals(0, created.constructed().size());
        }
    }

    /**
     * A populated employee table blocks the bootstrap (second guard true
     * arm): once any account exists, the table is never touched again.
     */
    @Test
    void onStartWithExistingEmployeesDoesNothing() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Employee> created = mockConstruction(Employee.class)) {
            mocked.when(Employee::count).thenReturn(3L);
            bootstrap(Optional.of("s3cret")).onStart(new StartupEvent());
            assertEquals(0, created.constructed().size());
        }
    }

    /**
     * The happy path: empty table and configured password create the ADMIN
     * employee — badge, login, hashed PIN, role, active, forced password
     * change — hand the secret to the back-office setter, and persist.
     */
    @Test
    void onStartOnEmptyTableCreatesTheBootstrapAdministrator() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Employee> created = mockConstruction(Employee.class)) {
            mocked.when(Employee::count).thenReturn(0L);
            bootstrap(Optional.of("s3cret")).onStart(new StartupEvent());
            assertEquals(1, created.constructed().size());
            Employee admin = created.constructed().get(0);
            assertEquals("00000000", admin.badgeId);
            assertEquals("admin", admin.loginName);
            assertNotNull(admin.password);
            assertTrue(admin.password.startsWith("$2"));
            assertEquals("Bootstrap", admin.firstName);
            assertEquals("Administrator", admin.lastName);
            assertEquals("admin@pos.local", admin.email);
            assertEquals(Employee.EmployeeRole.ADMIN, admin.role);
            assertTrue(admin.active);
            assertTrue(admin.mustChangePassword);
            verify(admin).setBackOfficePassword("s3cret");
            verify(admin).persist();
        }
    }
}
