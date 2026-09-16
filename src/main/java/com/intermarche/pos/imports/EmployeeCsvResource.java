package com.intermarche.pos.imports;

import com.intermarche.pos.domain.people.Employee;
import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * REST endpoint for bulk importing or updating Employees from a CSV stream —
 * the missing referential import: every other domain (stores, products,
 * families, prices) already has one, and the production initialization of a
 * root node needs employees the same way (the bootstrap administrator exists
 * to authenticate THIS very call on a virgin node).
 * <p>
 * Consumed columns (resolved by header name; unknown columns ignored):
 * BADGE_ID (key), LOGIN, PIN, FIRST_NAME, LAST_NAME, EMAIL, ROLE, and
 * optionally BACK_OFFICE_PASSWORD and ACTIVE (absent column = untouched on
 * update, entity default on create).
 * <p>
 * Secrets policy:
 * <ul>
 *   <li>PIN and back-office password arrive in clear text and are hashed at
 *       import (the same trust boundary as the rest of the ADMIN-only
 *       referential feeds). An empty PIN cell on an UPDATE keeps the stored
 *       hash; on a CREATE it is an error (the register cannot authenticate a
 *       PIN-less employee).</li>
 *   <li>Idempotence is checked by VERIFYING the incoming secret against the
 *       stored hash, never by comparing hashes (BCrypt salts differ at every
 *       hashing): re-importing the same file changes nothing — in particular
 *       it never re-forces a password change an administrator already
 *       performed.</li>
 *   <li>A (re)set back-office password forces the change at first sign-in,
 *       exactly like the seed and the bootstrap do.</li>
 * </ul>
 * Place in the architecture: like every referential import, its proper home
 * is the STORE node — EMPLOYEES is a domain of the referential pull, so the
 * imported employees reach every register within one pull period. This
 * importer captures NO engine feed ({@link #feedCode()} stays null): the
 * engine has no use for employees, and secrets never leave the POS chain.
 */
@Path("/employees/import")
@ApplicationScoped
@RunOnVirtualThread
public class EmployeeCsvResource extends ImporterCsvResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EmployeeCsvResource.class);

    /** Header name of the natural key: the 8-digit badge. */
    static final String COL_BADGE_ID = "BADGE_ID";
    /** Header name of the register/back-office login. */
    static final String COL_LOGIN = "LOGIN";
    /** Header name of the clear-text register PIN (hashed at import). */
    static final String COL_PIN = "PIN";
    /** Header name of the first name. */
    static final String COL_FIRST_NAME = "FIRST_NAME";
    /** Header name of the last name. */
    static final String COL_LAST_NAME = "LAST_NAME";
    /** Header name of the unique e-mail address. */
    static final String COL_EMAIL = "EMAIL";
    /** Header name of the {@link Employee.EmployeeRole} name. */
    static final String COL_ROLE = "ROLE";
    /** Header name of the OPTIONAL clear-text back-office password. */
    static final String COL_BACK_OFFICE_PASSWORD = "BACK_OFFICE_PASSWORD";
    /** Header name of the OPTIONAL active flag. */
    static final String COL_ACTIVE = "ACTIVE";

    /** The columns this importer cannot work without (secrets rules aside). */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_LOGIN, COL_PIN, COL_FIRST_NAME, COL_LAST_NAME, COL_EMAIL, COL_ROLE);

    /**
     * Imports or updates employees from a CSV stream. Delegates stream
     * reading, chunking and the staged fallback to the base class.
     *
     * @param inputStream the input stream containing CSV data
     * @return a JSON summary of created/updated counts and errors
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importEmployees(InputStream inputStream) {
        LOGGER.info("Entering method importEmployees with inputStream: " + inputStream);
        LOGGER.info("Exiting method importEmployees");
        return this.importCsvStream(inputStream, COL_BADGE_ID, REQUIRED_COLUMNS);
    }

    /**
     * Captures no engine feed: employees are a POS-chain domain — the engine
     * has no use for them and their secrets never leave the register chain.
     * They propagate to registers through the EMPLOYEES referential pull.
     *
     * @return null, always
     */
    @Override
    protected String feedCode() {
        return null;
    }

    /**
     * Bulk fetches the existing employees of the chunk by badge and indexes
     * them for the generic staging algorithm.
     *
     * @param parsedLines the list of data for the current chunk
     * @param targetCodes the set of unique badge ids in this chunk
     * @param counters an array of size 2 holding [createdCount, updatedCount]
     * @param errors the list collecting definitive error messages
     * @return the badge-to-employee map of existing rows
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        List<Employee> existing = Employee.list("badgeId IN ?1", targetCodes);
        Map<String, Object> employeeMap = new HashMap<>();
        for (Employee employee : existing) {
            employeeMap.put(employee.badgeId, employee);
        }
        return employeeMap;
    }

    /**
     * Creates or updates one employee from one CSV line. A new badge creates
     * the employee (PIN mandatory); a known badge is re-read fresh by id and
     * touched only when an identity field actually differs or a provided
     * secret does not verify against the stored hash — so re-importing an
     * unchanged file is a strict no-op.
     *
     * @param data the parsed CSV line data
     * @param entityMap the map of existing employees (badge to entity)
     * @param counters an array of size 2 holding [createdCount, updatedCount]
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        Employee employee = (Employee) entityMap.get(data.code);
        if (employee == null) {
            employee = new Employee();
            employee.badgeId = data.code;
            feedIdentity(data, employee);
            String pin = cell(data, COL_PIN);
            if (pin == null) {
                throw new IllegalArgumentException("Missing PIN for badge " + data.code);
            }
            employee.password = Employee.hashPassword(pin);
            String backOffice = backOfficePassword(data);
            if (backOffice != null) {
                employee.setBackOfficePassword(backOffice);
                // The password comes from the feed, so it is known outside
                // the account: first sign-in is confined to the password
                // screen — the seed and bootstrap rule.
                employee.mustChangePassword = true;
            }
            counters[0]++;
            Panache.getEntityManager().persist(employee);
        } else {
            employee = Employee.findById(employee.id);
            String pin = cell(data, COL_PIN);
            boolean pinChanged = pin != null && !employee.verifyPassword(pin);
            String backOffice = backOfficePassword(data);
            boolean backOfficeChanged = backOffice != null && !employee.verifyBackOfficePassword(backOffice);
            if (identityDiffers(data, employee) || pinChanged || backOfficeChanged) {
                feedIdentity(data, employee);
                if (pinChanged) {
                    employee.password = Employee.hashPassword(pin);
                }
                if (backOfficeChanged) {
                    employee.setBackOfficePassword(backOffice);
                    employee.mustChangePassword = true;
                }
                counters[1]++;
            }
        }
    }

    /**
     * Finds a fresh employee by badge for the generic 1-by-1 fallback.
     *
     * @param data the parsed CSV line data
     * @return the employee, or null when the badge is unknown
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return Employee.find("badgeId", data.code).firstResult();
    }

    /**
     * Populates the non-secret identity fields from the CSV line. The ACTIVE
     * flag is only touched when its column is declared, so a feed without it
     * keeps the entity default on create and the stored value on update.
     *
     * @param data the parsed CSV line data
     * @param employee the employee to populate
     */
    private void feedIdentity(LineData data, Employee employee) {
        employee.loginName = cell(data, COL_LOGIN);
        employee.firstName = cell(data, COL_FIRST_NAME);
        employee.lastName = cell(data, COL_LAST_NAME);
        employee.email = cell(data, COL_EMAIL);
        employee.role = parseRole(data);
        if (data.has(COL_ACTIVE)) {
            employee.active = safeParseBoolean(data, COL_ACTIVE);
        }
    }

    /**
     * Whether any non-secret identity field of the line differs from the
     * stored employee — field by field, because the ACTIVE comparison only
     * exists when its column is declared.
     *
     * @param data the parsed CSV line data
     * @param employee the stored employee
     * @return true when at least one identity field differs
     */
    private boolean identityDiffers(LineData data, Employee employee) {
        if (!Objects.equals(cell(data, COL_LOGIN), employee.loginName)) return true;
        if (!Objects.equals(cell(data, COL_FIRST_NAME), employee.firstName)) return true;
        if (!Objects.equals(cell(data, COL_LAST_NAME), employee.lastName)) return true;
        if (!Objects.equals(cell(data, COL_EMAIL), employee.email)) return true;
        if (parseRole(data) != employee.role) return true;
        if (data.has(COL_ACTIVE) && safeParseBoolean(data, COL_ACTIVE) != employee.active) return true;
        return false;
    }

    /**
     * Reads the optional back-office password cell: null when the column is
     * absent or the cell empty — meaning "leave the stored secret alone".
     *
     * @param data the parsed CSV line data
     * @return the clear-text back-office password, or null
     */
    private String backOfficePassword(LineData data) {
        return data.has(COL_BACK_OFFICE_PASSWORD) ? cell(data, COL_BACK_OFFICE_PASSWORD) : null;
    }

    /**
     * Reads one cell, normalizing an empty string to null: {@code safeGet}
     * trims but keeps empty cells as "", and this importer's whole secrets
     * policy ("empty means leave alone", "missing means error") reasons on
     * null — an empty PIN cell must never become the hash of "".
     *
     * @param data the parsed CSV line data
     * @param column the header name of the column
     * @return the trimmed cell value, or null when absent or empty
     */
    private String cell(LineData data, String column) {
        String value = safeGet(data, column);
        return (value == null || value.isEmpty()) ? null : value;
    }

    /**
     * Parses the mandatory role cell into an {@link Employee.EmployeeRole}.
     * A missing or unknown role throws, which the staged fallback records as
     * that line's definitive error.
     *
     * @param data the parsed CSV line data
     * @return the parsed role
     */
    private Employee.EmployeeRole parseRole(LineData data) {
        String raw = cell(data, COL_ROLE);
        if (raw == null) {
            throw new IllegalArgumentException("Missing ROLE for badge " + data.code);
        }
        return Employee.EmployeeRole.valueOf(raw.trim().toUpperCase());
    }
}
