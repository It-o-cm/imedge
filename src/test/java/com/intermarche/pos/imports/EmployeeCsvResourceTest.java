package com.intermarche.pos.imports;

import com.intermarche.pos.domain.people.Employee;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmployeeCsvResource}.
 * <p>
 * Plain JUnit + Mockito: the entity manager is reached through a mocked
 * {@link Panache} static, and the update paths work on REAL {@link Employee}
 * instances carrying real BCrypt hashes — the idempotence contract of this
 * importer is "verify the secret against the hash", so the tests exercise the
 * verification itself, never a mocked shortcut of it.
 */
class EmployeeCsvResourceTest {

    /** The canonical header of the full feed, ACTIVE included. */
    private static final String[] FULL_HEADER = {"BADGE_ID", "LOGIN", "PIN", "FIRST_NAME",
            "LAST_NAME", "EMAIL", "ROLE", "BACK_OFFICE_PASSWORD", "ACTIVE"};

    /** The header without the optional columns. */
    private static final String[] BARE_HEADER = {"BADGE_ID", "LOGIN", "PIN", "FIRST_NAME",
            "LAST_NAME", "EMAIL", "ROLE"};

    /**
     * Builds a header-bound row over the given header.
     *
     * @param header the header names, in cell order
     * @param cells the raw cells of the row
     * @return the header-bound line
     */
    private static ImporterCsvResource.LineData line(String[] header, String[] cells) {
        java.util.Map<String, Integer> index = new java.util.LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) index.put(header[i], i);
        return new ImporterCsvResource.LineData(1, index, cells, header[0]);
    }

    /**
     * Builds a stored employee with real hashes for the update tests.
     *
     * @param pin the clear-text PIN the stored hash verifies
     * @param backOffice the clear-text back-office password, or null
     * @return the stored employee
     */
    private static Employee stored(String pin, String backOffice) {
        Employee employee = new Employee();
        employee.id = 42L;
        employee.badgeId = "11111111";
        employee.loginName = "mcurie";
        employee.firstName = "Marie";
        employee.lastName = "Curie";
        employee.email = "marie.curie@test.com";
        employee.role = Employee.EmployeeRole.MANAGER;
        employee.active = true;
        employee.password = Employee.hashPassword(pin);
        employee.setBackOfficePassword(backOffice);
        employee.mustChangePassword = false;
        return employee;
    }

    /**
     * The canonical row matching {@link #stored(String, String)} exactly.
     *
     * @param pin the clear-text PIN cell
     * @param backOffice the back-office password cell (empty = leave alone)
     * @return the cells of the row
     */
    private static String[] matchingCells(String pin, String backOffice) {
        return new String[]{"11111111", "mcurie", pin, "Marie", "Curie",
                "marie.curie@test.com", "MANAGER", backOffice, "true"};
    }

    /**
     * Wraps a string as a UTF-8 input stream.
     *
     * @param content the CSV content
     * @return the input stream
     */
    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code importEmployees} delegates to the base importer with the badge
     * key; a header-only body produces a zero-count JSON with a 200 status.
     */
    @Test
    void importEmployeesDelegatesToBaseImporter() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Response response = resource.importEmployees(stream("BADGE_ID|LOGIN|PIN|FIRST_NAME|LAST_NAME|EMAIL|ROLE\n"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    /**
     * {@code feedCode} stays null: employees are never captured for the
     * engine — secrets do not leave the POS chain.
     */
    @Test
    void feedCodeCapturesNothing() {
        assertNull(new EmployeeCsvResource().feedCode());
    }

    /**
     * A new badge creates the employee ({@code employee == null} true arm):
     * identity fields fed, PIN hashed (BCrypt), back-office password set with
     * the forced change, ACTIVE read from its declared column, persisted,
     * created counter incremented.
     */
    @Test
    void processLineLogicCreatesEmployeeWithSecrets() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line(FULL_HEADER, matchingCells("1111", "secret-bo")),
                    new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
            verify(em).persist(captor.capture());
            Employee created = captor.getValue();
            assertEquals("11111111", created.badgeId);
            assertEquals("mcurie", created.loginName);
            assertEquals("Marie", created.firstName);
            assertEquals("Curie", created.lastName);
            assertEquals("marie.curie@test.com", created.email);
            assertEquals(Employee.EmployeeRole.MANAGER, created.role);
            assertTrue(created.active);
            assertTrue(created.verifyPassword("1111"));
            assertTrue(created.verifyBackOfficePassword("secret-bo"));
            assertTrue(created.mustChangePassword);
        }
    }

    /**
     * A creation without the optional columns keeps the entity defaults
     * (active true, no back-office password, no forced change) — the
     * back-office arm and the ACTIVE arm both absent.
     */
    @Test
    void processLineLogicCreatesEmployeeWithoutOptionalColumns() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line(BARE_HEADER, new String[]{"22222222", "aeinstein",
                    "2222", "Albert", "Einstein", "albert@test.com", "PICKER"}), new HashMap<>(), counters);
            ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
            verify(em).persist(captor.capture());
            Employee created = captor.getValue();
            assertTrue(created.active);
            assertNull(created.backOfficePassword);
            assertFalse(created.mustChangePassword);
            assertTrue(created.verifyPassword("2222"));
        }
    }

    /**
     * A creation with an empty PIN cell is that line's definitive error
     * ({@code pin == null} true arm): the register cannot authenticate a
     * PIN-less employee, and an empty cell must never hash "".
     */
    @Test
    void processLineLogicRefusesCreationWithoutPin() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        assertThrows(IllegalArgumentException.class, () -> resource.processLineLogic(
                line(FULL_HEADER, matchingCells("", "")), new HashMap<>(), new int[]{0, 0}));
    }

    /**
     * Re-importing the exact same row is a strict no-op (identity equal,
     * PIN verifies, back-office cell empty): no counter moves, the forced
     * change is NOT re-armed — the idempotence contract.
     */
    @Test
    void processLineLogicIsIdempotentOnUnchangedRow() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            resource.processLineLogic(line(FULL_HEADER, matchingCells("1111", "")), map, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            assertFalse(employee.mustChangePassword);
        }
    }

    /**
     * A different PIN on a known badge re-hashes the PIN and counts one
     * update ({@code pinChanged} true arm), without touching the back-office
     * secret.
     */
    @Test
    void processLineLogicUpdatesChangedPin() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            resource.processLineLogic(line(FULL_HEADER, matchingCells("9999", "")), map, counters);
            assertEquals(1, counters[1]);
            assertTrue(employee.verifyPassword("9999"));
            assertTrue(employee.verifyBackOfficePassword("secret-bo"));
            assertFalse(employee.mustChangePassword);
        }
    }

    /**
     * A different back-office password re-hashes it and re-arms the forced
     * change ({@code backOfficeChanged} true arm).
     */
    @Test
    void processLineLogicUpdatesChangedBackOfficePassword() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            resource.processLineLogic(line(FULL_HEADER, matchingCells("1111", "new-bo")), map, counters);
            assertEquals(1, counters[1]);
            assertTrue(employee.verifyBackOfficePassword("new-bo"));
            assertTrue(employee.mustChangePassword);
        }
    }

    /**
     * A changed identity field (the role here) re-feeds the identity and
     * counts one update ({@code identityDiffers} true arm) while both
     * verified secrets stay untouched.
     */
    @Test
    void processLineLogicUpdatesChangedIdentity() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            String[] cells = matchingCells("1111", "");
            cells[6] = "CASHIER";
            resource.processLineLogic(line(FULL_HEADER, cells), map, counters);
            assertEquals(1, counters[1]);
            assertEquals(Employee.EmployeeRole.CASHIER, employee.role);
            assertTrue(employee.verifyPassword("1111"));
            assertFalse(employee.mustChangePassword);
        }
    }

    /**
     * A declared ACTIVE column that differs from the stored flag is an
     * identity change (the ACTIVE leg of {@code identityDiffers}).
     */
    @Test
    void processLineLogicUpdatesChangedActiveFlag() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            String[] cells = matchingCells("1111", "");
            cells[8] = "false";
            resource.processLineLogic(line(FULL_HEADER, cells), map, counters);
            assertEquals(1, counters[1]);
            assertFalse(employee.active);
        }
    }

    /**
     * {@code processChunkWithFallback} short-circuits to an empty map for an
     * empty chunk ({@code parsedLines.isEmpty()} true arm): no bulk fetch is
     * issued and the returned index is empty.
     */
    @Test
    void processChunkWithFallbackReturnsEmptyMapForEmptyChunk() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Map<String, Object> context = resource.processChunkWithFallback(
                new ArrayList<>(), new HashSet<>(), new int[]{0, 0}, new ArrayList<>());
        assertTrue(context.isEmpty());
    }

    /**
     * {@code processChunkWithFallback} bulk-fetches the existing employees and
     * indexes them by badge ({@code parsedLines.isEmpty()} false arm, the
     * loop body entered): the returned map binds each stored badge to its
     * entity.
     */
    @Test
    void processChunkWithFallbackIndexesExistingEmployees() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(line(FULL_HEADER, matchingCells("1111", "")));
        Set<String> targetCodes = new HashSet<>();
        targetCodes.add("11111111");
        Employee employee = stored("1111", "secret-bo");
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> Employee.list("badgeId IN ?1", targetCodes)).thenReturn(List.of(employee));
            Map<String, Object> context = resource.processChunkWithFallback(lines, targetCodes, new int[]{0, 0}, new ArrayList<>());
            assertEquals(1, context.size());
            assertSame(employee, context.get("11111111"));
        }
    }

    /**
     * {@code findEntityForLine} looks the employee up fresh by badge for the
     * 1-by-1 fallback and returns the first match.
     */
    @Test
    void findEntityForLineLooksUpEmployeeByBadge() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        @SuppressWarnings("unchecked")
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> Employee.find("badgeId", "11111111")).thenReturn(query);
            assertSame(employee, resource.findEntityForLine(line(FULL_HEADER, matchingCells("1111", ""))));
        }
    }

    /**
     * An empty PIN cell on an UPDATE keeps the stored hash ({@code pin == null}
     * arm of {@code pinChanged}): the register keeps authenticating with the
     * old PIN, nothing else changes, no counter moves.
     */
    @Test
    void processLineLogicKeepsStoredPinWhenPinCellEmptyOnUpdate() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            resource.processLineLogic(line(FULL_HEADER, matchingCells("", "")), map, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            assertTrue(employee.verifyPassword("1111"));
        }
    }

    /**
     * A back-office password cell that VERIFIES against the stored hash is a
     * no-op ({@code backOffice != null} true, {@code !verify} false — the
     * unmissed leg of {@code backOfficeChanged}): the forced change is not
     * re-armed and no counter moves.
     */
    @Test
    void processLineLogicKeepsBackOfficeWhenProvidedSecretVerifies() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            resource.processLineLogic(line(FULL_HEADER, matchingCells("1111", "secret-bo")), map, counters);
            assertEquals(0, counters[1]);
            assertFalse(employee.mustChangePassword);
            assertTrue(employee.verifyBackOfficePassword("secret-bo"));
        }
    }

    /**
     * A changed LOGIN is an identity change (the LOGIN leg of
     * {@code identityDiffers} returning true): the row is re-fed and counted
     * once.
     */
    @Test
    void processLineLogicUpdatesChangedLogin() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            String[] cells = matchingCells("1111", "");
            cells[1] = "mcurie2";
            resource.processLineLogic(line(FULL_HEADER, cells), map, counters);
            assertEquals(1, counters[1]);
            assertEquals("mcurie2", employee.loginName);
        }
    }

    /**
     * A changed FIRST_NAME is an identity change (the FIRST_NAME leg of
     * {@code identityDiffers} returning true, LOGIN equal so the earlier leg
     * falls through): the row is re-fed and counted once.
     */
    @Test
    void processLineLogicUpdatesChangedFirstName() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            String[] cells = matchingCells("1111", "");
            cells[3] = "Maria";
            resource.processLineLogic(line(FULL_HEADER, cells), map, counters);
            assertEquals(1, counters[1]);
            assertEquals("Maria", employee.firstName);
        }
    }

    /**
     * A changed LAST_NAME is an identity change (the LAST_NAME leg of
     * {@code identityDiffers} returning true, the two earlier legs equal): the
     * row is re-fed and counted once.
     */
    @Test
    void processLineLogicUpdatesChangedLastName() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            String[] cells = matchingCells("1111", "");
            cells[4] = "Sklodowska";
            resource.processLineLogic(line(FULL_HEADER, cells), map, counters);
            assertEquals(1, counters[1]);
            assertEquals("Sklodowska", employee.lastName);
        }
    }

    /**
     * A changed EMAIL is an identity change (the EMAIL leg of
     * {@code identityDiffers} returning true, the three earlier legs equal):
     * the row is re-fed and counted once.
     */
    @Test
    void processLineLogicUpdatesChangedEmail() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            String[] cells = matchingCells("1111", "");
            cells[5] = "marie@nobel.org";
            resource.processLineLogic(line(FULL_HEADER, cells), map, counters);
            assertEquals(1, counters[1]);
            assertEquals("marie@nobel.org", employee.email);
        }
    }

    /**
     * An UPDATE whose header does NOT declare ACTIVE never compares the flag
     * (the {@code data.has(COL_ACTIVE)} false arm of {@code identityDiffers}):
     * with every declared field equal the row is a strict no-op and the
     * stored ACTIVE flag is left alone.
     */
    @Test
    void processLineLogicIgnoresActiveWhenColumnAbsentOnUpdate() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        Employee employee = stored("1111", "secret-bo");
        Map<String, Object> map = new HashMap<>();
        map.put("11111111", employee);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(42L)).thenReturn(employee);
            resource.processLineLogic(line(BARE_HEADER, new String[]{"11111111", "mcurie", "1111",
                    "Marie", "Curie", "marie.curie@test.com", "MANAGER"}), map, counters);
            assertEquals(0, counters[1]);
            assertTrue(employee.active);
        }
    }

    /**
     * A row whose ROLE cell is absent (index beyond the line's cells, so
     * {@code cell} sees a null value — the {@code value == null} true arm)
     * makes {@code parseRole} throw ({@code raw == null} true arm): the line
     * is that row's definitive error and no employee is created.
     */
    @Test
    void processLineLogicRefusesCreationWithMissingRole() {
        EmployeeCsvResource resource = new EmployeeCsvResource();
        int[] counters = {0, 0};
        ImporterCsvResource.LineData data = line(BARE_HEADER, new String[]{"33333333", "newlogin",
                "2222", "First", "Last", "new@test.com"});
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(data, new HashMap<>(), counters));
        assertEquals("Missing ROLE for badge 33333333", error.getMessage());
        assertEquals(0, counters[0]);
    }
}
