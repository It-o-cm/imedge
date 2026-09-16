package com.intermarche.pos.security;

import com.intermarche.pos.domain.people.Employee;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Creates the initial administrator employee when the employee table is
 * empty — the production bootstrap of a ROOT node (the same pattern as
 * imvaluation's and imfid's {@code UserBootstrap}).
 * <p>
 * Authentication reads the {@code employees} table, and the dev/test seed
 * ({@code DataInitializer}) does not exist in a production build: a freshly
 * installed root node would otherwise leave nobody able to sign in — and the
 * import endpoints it must serve first are {@code @RolesAllowed("ADMIN")}.
 * <p>
 * Differences from the sibling bootstraps, both deliberate:
 * <ul>
 *   <li>The password property is OPTIONAL rather than required: a REGISTER
 *       node receives its employees through the referential pull and must
 *       boot with the property unset. No variable = no account = nobody can
 *       sign in on a virgin node — never a guessable default.</li>
 *   <li>The register PIN of the bootstrap account is the hash of the same
 *       secret, because the column is mandatory; a non-numeric secret is
 *       untypable on the lane numpad, so the account effectively opens the
 *       back office only, until a real PIN is administered.</li>
 * </ul>
 * The bootstrap only ever runs when NO employee exists at all: once any
 * account has been created, this class never touches the table again, and in
 * particular never resets a password an administrator has changed.
 */
@Singleton
public class EmployeeBootstrap {

    /** The class logger. */
    private static final Logger LOGGER = Logger.getLogger(EmployeeBootstrap.class);

    /** Login name of the account created on an empty database. */
    @ConfigProperty(name = "pos.bootstrap.admin.username", defaultValue = "admin")
    String bootstrapUsername;

    /**
     * Clear-text password of the account created on an empty database, or
     * absent/blank for no bootstrap at all. Deliberately without a default:
     * a fallback value compiled into the application would grant
     * administrator access to anyone who reads the source. Supplied per
     * environment (the {@code POS_ADMIN_PASSWORD} variable in production,
     * on the root node only).
     */
    @ConfigProperty(name = "pos.bootstrap.admin.password")
    Optional<String> bootstrapPassword;

    /** E-mail address of the account created on an empty database. */
    @ConfigProperty(name = "pos.bootstrap.admin.email", defaultValue = "admin@pos.local")
    String bootstrapEmail;

    /**
     * Creates the initial administrator if a bootstrap password is
     * configured and the employee table holds no account.
     * <p>
     * A node that boots with NO employee and NO bootstrap password is a
     * node nobody can sign in to. That combination used to be silent, and
     * the failure only surfaced later as a rejected login; it is now
     * reported as an error at startup, naming the variable to set, so the
     * cause is in the boot log rather than in a guess.
     *
     * @param event the application startup event
     */
    @Transactional
    void onStart(@Observes StartupEvent event) {
        if (bootstrapPassword.isEmpty() || bootstrapPassword.get().isBlank()) {
            if (Employee.count() == 0) {
                LOGGER.error("No employee in the database and no bootstrap password configured:"
                        + " NOBODY can sign in on this node. Set POS_ADMIN_PASSWORD"
                        + " (pos.bootstrap.admin.password) and restart, or feed the"
                        + " employees through the referential pull.");
            }
            return;
        }
        if (Employee.count() > 0) {
            return;
        }
        String secret = bootstrapPassword.get();
        Employee admin = new Employee();
        admin.badgeId = "00000000";
        admin.loginName = bootstrapUsername;
        admin.password = Employee.hashPassword(secret);
        admin.firstName = "Bootstrap";
        admin.lastName = "Administrator";
        admin.email = bootstrapEmail;
        admin.role = Employee.EmployeeRole.ADMIN;
        admin.active = true;
        admin.setBackOfficePassword(secret);
        // The password comes from the configuration, so it is known outside
        // the account: the first sign-in is confined to the password screen.
        admin.mustChangePassword = true;
        admin.persist();
        LOGGER.warnf("No employee found: created bootstrap administrator '%s'."
                + " Change its password before exposing this instance.", bootstrapUsername);
    }
}
