package com.intermarche.pos.domain.people;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.mindrot.jbcrypt.BCrypt;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import com.intermarche.pos.domain.BaseEntity;

/**
 * Entity representing an employee working in a store.
 * <p>
 * This class handles authentication (badge ID/PIN) and authorization (role).
 * <p>
 * Semantic contract:
 * <ul>
 *   <li>TWO CREDENTIALS, two doors, one staff file. The 4-digit PIN
 *       ({@code password}) opens the REGISTER and nothing else; the
 *       back-office password ({@code backOfficePassword}) opens the
 *       BACK OFFICE and the machine surfaces, and nothing else. Neither
 *       is accepted at the other's door. An employee with no back-office
 *       password simply has no back office — which is the normal state of
 *       a cashier.</li>
 *   <li>Authentication at the register: badge scan resolves the account,
 *       the 4-digit PIN is verified against the bcrypt hash stored in
 *       {@code password}. The same PIN check guards login and manager
 *       endorsements, and shares one lockout: {@code failedAttempts} /
 *       {@code lockedUntil} (pos.auth.max-attempts,
 *       pos.auth.lockout-minutes).</li>
 *   <li>The lockout pair is LOCAL operational state: the centralized
 *       referential pull upserts every business field (PIN hash included —
 *       any cashier can badge on any register) but never touches these two,
 *       so a lock on one register neither spreads nor resets. The
 *       back-office password and its forced-change flag are local for the
 *       SAME reason: the back office is administered on the node where it
 *       runs, and a password someone changed there has no business being
 *       pushed onto every register.</li>
 *   <li>Roles: MANAGER and ADMIN validate endorsements; deactivation, not
 *       deletion, removes an employee (historical documents reference
 *       them).</li>
 * </ul>
 */
@Entity
@Table(name = "employees",
        indexes = {
                @Index(name = "idx_employee_badge", columnList = "badge_id", unique = true)
        }
)
@Cacheable
public class Employee extends BaseEntity {

    /**
     * Enumeration representing the role of an employee within the supermarket.
     */
    public enum EmployeeRole {
        /**
         * Administrator with full access to the system.
         */
        ADMIN,
        /**
         * Store manager with read-only access to reports and basic management capabilities.
         */
        MANAGER,
        /**
         * Employee responsible for picking orders for the Drive/E-commerce.
         */
        PICKER,
        /**
         * Cashier operating the Point of Sale (POS) system.
         */
        CASHIER
    }

    // --------------------------------------------------
    // Identification
    // --------------------------------------------------

    /**
     * The employee's first name.
     */
    @Column(name = "first_name", nullable = false)
    @NotBlank(message = "First name is mandatory")
    public String firstName;

    /**
     * The employee's last name.
     */
    @Column(name = "last_name", nullable = false)
    @NotBlank(message = "Last name is mandatory")
    public String lastName;

    /**
     * The employee's login name.
     */
    @Column(name = "login_name", nullable = false)
    @NotBlank(message = "Login name is mandatory")
    public String loginName;

    /**
     * The hashed 4-digit PIN of the employee — the REGISTER credential.
     * <p>
     * Kept under its historical name because it is what the whole register
     * chain reads; the back-office credential is
     * {@link #backOfficePassword}, a different secret behind a different
     * door.
     * <p>
     * Note: This should store the hash (e.g., BCrypt), never the plain text PIN.
     */
    @Column(nullable = false)
    @NotBlank(message = "Password is mandatory")
    @Size(min = 60) // Typical size for BCrypt hash
    public String password;

    /**
     * The email address of the employee.
     */
    @Column(name = "email", unique = true, nullable = false)
    @Email(message = "Email should be valid")
    @NotBlank(message = "Email is mandatory")
    public String email;

    // --------------------------------------------------
    // Role Attachment
    // --------------------------------------------------

    /**
     * The role assigned to the employee.
     * Determines permissions for accessing Backoffice, Mobile Apps, etc.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull(message = "Role is mandatory")
    public EmployeeRole role;

    // --------------------------------------------------
    // Operational Data
    // --------------------------------------------------

    /**
     * The unique identifier of the physical RFID badge.
     * <p>
     * This is the primary login credential.
     */
    @Column(name = "badge_id", unique = true, length = 20)
    @NotBlank(message = "Badge ID is mandatory")
    public String badgeId;

    /**
     * Personal display theme of this employee (data-theme value), or null to
     * follow the store's theme. Referential data: travels in the phase 6
     * pull like the other employee fields.
     */
    @jakarta.persistence.Column(name = "theme", length = 20)
    public String theme;

    // --------------------------------------------------
    // Status
    // --------------------------------------------------

    /**
     * Flag indicating if the employee is currently active.
     * <p>
     * If false, the employee cannot log in to any system.
     */
    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    // --------------------------------------------------
    // PIN lockout (phase 2)
    // --------------------------------------------------

    /**
     * The number of consecutive failed PIN attempts since the last success.
     */
    @Column(name = "failed_attempts", nullable = false)
    public int failedAttempts = 0;

    /**
     * The timestamp until which the account is locked after repeated PIN
     * failures, or null when not locked.
     */
    @Column(name = "locked_until")
    public LocalDateTime lockedUntil;

    // --------------------------------------------------
    // Back-office credential (local state, like the lockout)
    // --------------------------------------------------

    /**
     * The hashed back-office password, or null when the employee has no
     * back-office access at all.
     * <p>
     * A conventional password, not a PIN: it travels over HTTP, it is typed
     * on a keyboard, and it is the credential the login form and the HTTP
     * Basic clients present. Null is the normal state of a cashier — the
     * absence of a value IS the absence of the right, so no extra flag is
     * needed to keep the back office shut.
     * <p>
     * Never carried by the referential pull: see the class contract.
     */
    @Column(name = "bo_password", length = 100)
    public String backOfficePassword;

    /**
     * Whether the back-office password must be replaced before anything
     * else can be reached.
     * <p>
     * Set whenever the password comes from somewhere other than its owner —
     * the seed today, an administrator's reset tomorrow — so a password
     * that is known outside the account cannot survive the first sign-in.
     * <p>
     * Never carried by the referential pull: see the class contract.
     */
    @Column(name = "must_change_password", nullable = false)
    public boolean mustChangePassword = false;

    /**
     * Indicates whether the account is currently locked out after repeated
     * PIN failures.
     *
     * @return true if a lockout is active at this moment
     */
    public boolean isCurrentlyLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    // --------------------------------------------------
    // Implementation of Account Interface
    // --------------------------------------------------

    /**
     * Returns the badge ID as the username for authentication.
     *
     * @return the badge ID.
     */
    public String getLoginName() {
        return loginName;
    }

    /**
     * Returns the hashed password for authentication verification.
     *
     * @return the password hash.
     */
    public String getPassword() {
        return password;
    }

    /**
     * Returns a set containing the single role assigned to the employee.
     *
     * @return a set with the role name.
     */
    public Set<String> getRoles() {
        if (role == null) {
            return Set.of();
        }
        return Set.of(role.name());
    }

    /**
     * Indicates whether the employee may reach the back office at all.
     *
     * @return true when a back-office password has been set
     */
    public boolean hasBackOfficeAccess() {
        return backOfficePassword != null && !backOfficePassword.isBlank();
    }

    /**
     * Sets the back-office password, hashing it.
     *
     * @param rawPassword the clear-text password, or null to withdraw
     *                    back-office access entirely
     */
    public void setBackOfficePassword(String rawPassword) {
        this.backOfficePassword = rawPassword == null ? null : hashPassword(rawPassword);
    }

    /**
     * Verifies a presented back-office password against the stored hash.
     * <p>
     * Returns false when no back-office password is set: an account without
     * one is not a back-office account, and no input can make it one.
     *
     * @param rawPassword the clear-text password presented
     * @return true when it matches
     */
    public boolean verifyBackOfficePassword(String rawPassword) {
        if (!hasBackOfficeAccess() || rawPassword == null) {
            return false;
        }
        return BCrypt.checkpw(rawPassword, this.backOfficePassword);
    }

    /**
     * Checks if the employee account is active.
     *
     * @return true if active, false otherwise.
     */
    public boolean isEnabled() {
        return active;
    }

    /**
     * Returns the database ID as the subject for the JWT.
     *
     * @return the ID as a string.
     */
    public String getSubjectId() {
        return String.valueOf(id);
    }

    /**
     * Returns the full name of the employee.
     *
     * @return "FirstName LastName".
     */
    public String getFullName() {
        return firstName + " " + lastName;
    }

    // --------------------------------------------------
    // Implementation of new Account methods
    // --------------------------------------------------

    /**
     * Returns the email address of the employee.
     *
     * @return the email address.
     */
    public String getEmail() {
        return this.email;
    }

    /**
     * Sets the password for the employee.
     *
     * @param password the new password to be set
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns a string representation of the account for logging.
     *
     * @return a descriptive string.
     */
    public String present() {
        return String.format("Employee[%s] %s (%s)", this.badgeId, this.getFullName(), this.email);
    }

    /**
     * Méthode utilitaire statique pour hacher un mot de passe avant de l'assigner.
     * À utiliser lors de la création d'un employé.
     */
    public static String hashPassword(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    /**
     *
     * @param rawPassword
     * @return
     */
    public boolean verifyPassword(String rawPassword) {
        if (this.password == null || rawPassword == null) return false;
        return BCrypt.checkpw(rawPassword, this.password);
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds an employee by their physical badge ID.
     *
     * @param loginInfo The badge ID or loginName to search for.
     * @return The Employee or null if not found.
     */
    public static Employee findLogin(String loginInfo) {
        return find("badgeId = ?1 or loginName = ?1", loginInfo)
                .firstResult();
    }

    /**
     * Finds an active employee by their physical badge ID.
     * Combines badge lookup with active status check.
     *
     * @param loginInfo The badge ID or loginName to search for.
     * @return The active Employee or null.
     */
    public static Employee findActiveLogin(String loginInfo) {
        return find("(badgeId = ?1 or loginName = ?1) and active = true", loginInfo).firstResult();
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the entity's current state.
     * <p>
     * Includes the password reset fields in the calculation to detect changes.
     * <p>
     * Deliberately EXCLUDES the back-office credential: that field is local
     * state, outside the referential exchange, and hashing it here would
     * make a local password change look like a referential divergence.
     *
     * @return the calculated checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(firstName, lastName, password, role, badgeId, active, email);
    }
}