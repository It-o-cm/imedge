package com.intermarche.edge.service;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.EchelonScope;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.EmployeeProfile;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.Profile;
import com.intermarche.edge.domain.people.ProfileGrant;
import com.intermarche.edge.domain.store.NodeEchelon;
import com.intermarche.edge.domain.store.Store;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.EnumSet;
import java.util.Set;

/**
 * Answers the one question the administered profiles exist to answer: may
 * THIS signed-in account use THIS functionality under THIS option, on the
 * point of sale this node is?
 * <p>
 * This is the dynamic authorization layer that replaces the four fixed roles
 * for the back office (BO-01-03, BO-01-04). It reads the account's
 * {@link EmployeeProfile} bindings, keeps only those in force on the local
 * node (their {@link EmployeeProfile#appliesTo(String) echelon} matches the
 * node's {@link Store#code}), and grants the request as soon as one bound
 * {@link Profile} holds a matching {@link ProfileGrant}. A profile created in
 * the interface therefore authorizes or refuses a precise feature on a
 * precise point of sale with no redeployment.
 * <p>
 * Two deliberate boundaries:
 * <ul>
 *   <li>The {@link Employee.EmployeeRole#ADMIN} is a SUPERUSER and bypasses
 *       the profile lookup entirely — the mirror of the "ADMIN implies
 *       MANAGER" implication the identity provider already applies. Without
 *       it the seed administrator, who owns no profile, would be locked out
 *       of the very screen that assigns profiles.</li>
 *   <li>This layer governs EVERY back-office screen (BO-01-04-01): each
 *       handler declares the feature and the option it exercises, and the
 *       access filter asks this service before letting it run. The
 *       {@link Employee.EmployeeRole#MANAGER} keeps, as a baseline, the
 *       screens the fixed roles used to open to it (journal, supervision, end
 *       of day, reports), so a store that never administered a profile works
 *       as it did.</li>
 *   <li>An account placed on echelons (BO-01-01-03) reaches the back office
 *       of a node only when one of its node-level scopes covers it, and a
 *       profile assignment is in force only on the echelon it names
 *       (BO-01-03-04).</li>
 * </ul>
 * <p>
 * Reads run in their own transaction ({@code QuarkusTransaction.requiringNew})
 * for the same reason the identity provider does: an authorization check can
 * fire outside an active request transaction.
 */
@ApplicationScoped
public class PermissionService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PermissionService.class);

    /**
     * The screens a MANAGER opened under the fixed roles, kept as its baseline
     * so the generalized control takes nothing away from it.
     */
    static final Set<Feature> MANAGER_BASELINE =
            EnumSet.of(Feature.JOURNAL, Feature.SUPERVISION, Feature.DAY_END, Feature.REPORT);

    /** Where this node sits in the organisation. */
    @Inject
    NodeEchelonService nodeEchelonService;

    /** Reads whether a record exists, for the saving gestures that both create and modify. */
    @Inject
    EntityManager entityManager;

    /**
     * The outcome of an authorization question: whether it is allowed and,
     * when allowed, whether exercising it is still subject to a validation
     * (BO-01-04-03) and by which profile (BO-01-04-05).
     */
    public static final class Decision {

        /** Whether the request is authorized. */
        public final boolean allowed;

        /** Whether the authorized action still requires a validation. */
        public final boolean requiresValidation;

        /** The validating profile's id, or null when not delegated. */
        public final Long validatingProfileId;

        /**
         * Builds a decision.
         *
         * @param allowed whether the request is authorized
         * @param requiresValidation whether a validation is still required
         * @param validatingProfileId the validating profile's id, or null
         */
        private Decision(boolean allowed, boolean requiresValidation, Long validatingProfileId) {
            this.allowed = allowed;
            this.requiresValidation = requiresValidation;
            this.validatingProfileId = validatingProfileId;
        }

        /**
         * Builds a refusal.
         *
         * @return a denied decision
         */
        static Decision deny() {
            return new Decision(false, false, null);
        }

        /**
         * Builds an unconditional grant (the superuser path).
         *
         * @return an allowed decision with no validation
         */
        static Decision allow() {
            return new Decision(true, false, null);
        }

        /**
         * Builds a grant carrying the validation terms of the matched grant.
         *
         * @param grant the matched grant
         * @return an allowed decision reflecting the grant's validation terms
         */
        static Decision allow(ProfileGrant grant) {
            return new Decision(true, grant.requiresValidation, grant.validatingProfileId);
        }

        /**
         * Indicates whether the request is authorized.
         *
         * @return true when allowed
         */
        public boolean isAllowed() {
            return allowed;
        }
    }

    /**
     * Decides an authorization question for a signed-in account.
     *
     * @param loginName the login name of the signed-in account
     * @param feature the feature being reached
     * @param option the option being exercised
     * @return the decision
     */
    public Decision decide(String loginName, Feature feature, CrudOption option) {
        LOGGER.info("Entering method decide with loginName: " + loginName + ", feature: " + feature + ", option: " + option);
        LOGGER.info("Exiting method decide");
        return QuarkusTransaction.requiringNew().call(() -> decideInTransaction(loginName, feature, option));
    }

    /**
     * Convenience shortcut returning only the allow/deny verdict.
     *
     * @param loginName the login name of the signed-in account
     * @param feature the feature being reached
     * @param option the option being exercised
     * @return true when the request is authorized
     */
    public boolean isAllowed(String loginName, Feature feature, CrudOption option) {
        LOGGER.info("Entering method isAllowed with loginName: " + loginName + ", feature: " + feature + ", option: " + option);
        LOGGER.info("Exiting method isAllowed");
        return decide(loginName, feature, option).isAllowed();
    }

    /**
     * Resolves the decision against the database — the body run inside the
     * transaction opened by {@link #decide(String, Feature, CrudOption)}.
     *
     * @param loginName the login name of the signed-in account
     * @param feature the feature being reached
     * @param option the option being exercised
     * @return the decision
     */
    Decision decideInTransaction(String loginName, Feature feature, CrudOption option) {
        Employee employee = Employee.findActiveLogin(loginName);
        if (employee == null) {
            return Decision.deny();
        }
        if (employee.role == Employee.EmployeeRole.ADMIN) {
            return Decision.allow();
        }
        NodeEchelon node = currentNode();
        if (!EchelonScope.admitsNode(EchelonScope.parseList(employee.echelonScopes), node)) {
            return Decision.deny();
        }
        for (EmployeeProfile binding : EmployeeProfile.forEmployee(employee.id)) {
            if (!binding.appliesTo(node)) {
                continue;
            }
            Profile profile = Profile.findById(binding.profileId);
            if (profile == null) {
                continue;
            }
            ProfileGrant grant = profile.grant(feature, option);
            if (grant != null) {
                return Decision.allow(grant);
            }
        }
        if (employee.role == Employee.EmployeeRole.MANAGER && MANAGER_BASELINE.contains(feature)) {
            return Decision.allow();
        }
        return Decision.deny();
    }

    /**
     * Tells whether an account may administer another one (BO-01-01-02,
     * BO-01-02-10): an ADMIN administers every account, any other account only
     * accounts of a level strictly below its own and never an ADMIN.
     *
     * @param actor the administering account, possibly null
     * @param target the administered account, possibly null for a new one
     * @return true when the actor may create, modify, reset or delete the target
     */
    public boolean outranks(Employee actor, Employee target) {
        if (actor == null) {
            return false;
        }
        if (actor.role == Employee.EmployeeRole.ADMIN) {
            return true;
        }
        if (target == null) {
            return true;
        }
        return target.role != Employee.EmployeeRole.ADMIN && actor.hierarchyLevel > target.hierarchyLevel;
    }

    /**
     * Tells whether an account may hand a profile out (BO-01-03-03): an ADMIN
     * any profile, any other account only profiles of its own level or below.
     *
     * @param actor the assigning account, possibly null
     * @param profile the profile, possibly null
     * @return true when the actor may assign or withdraw the profile
     */
    public boolean mayAssign(Employee actor, Profile profile) {
        if (actor == null || profile == null) {
            return false;
        }
        return actor.role == Employee.EmployeeRole.ADMIN || profile.hierarchyLevel <= actor.hierarchyLevel;
    }

    /**
     * Checks the validation a gesture asks for (BO-01-04-03, BO-01-04-05).
     *
     * <p>Without a validating profile the actor confirms with their own
     * password. With one, another account must confirm with its login and
     * password, and that account must hold the validating profile on this
     * node (an ADMIN validates everything).
     *
     * @param actorLogin the login of the account making the gesture
     * @param validatorLogin the login typed for the validator, or null
     * @param password the password typed, possibly null
     * @param validatingProfileId the validating profile, or null for a self-confirmation
     * @return the login of the account that validated, or null when the validation fails
     */
    public String validate(String actorLogin, String validatorLogin, String password,
                           Long validatingProfileId) {
        if (password == null || password.isEmpty()) {
            return null;
        }
        return QuarkusTransaction.requiringNew().call(
                () -> validateInTransaction(actorLogin, validatorLogin, password, validatingProfileId));
    }

    /**
     * The body of {@link #validate}, run inside its transaction.
     *
     * @param actorLogin the login of the account making the gesture
     * @param validatorLogin the login typed for the validator, or null
     * @param password the password typed
     * @param validatingProfileId the validating profile, or null
     * @return the validating login, or null
     */
    String validateInTransaction(String actorLogin, String validatorLogin, String password,
                                 Long validatingProfileId) {
        if (validatingProfileId == null) {
            Employee actor = Employee.findActiveLogin(actorLogin);
            return actor != null && actor.verifyBackOfficePassword(password) ? actor.loginName : null;
        }
        if (validatorLogin == null || validatorLogin.isBlank()) {
            return null;
        }
        Employee actor = Employee.findActiveLogin(actorLogin);
        Employee validator = Employee.findActiveLogin(validatorLogin.trim());
        if (validator == null || (actor != null && validator.id.equals(actor.id))
                || !validator.verifyBackOfficePassword(password)) {
            return null;
        }
        return holdsProfile(validator, validatingProfileId) ? validator.loginName : null;
    }

    /**
     * Tells whether an account holds a profile on this node.
     *
     * @param employee the account
     * @param profileId the profile
     * @return true for an ADMIN, or when a binding of that profile is in force here
     */
    boolean holdsProfile(Employee employee, Long profileId) {
        if (employee.role == Employee.EmployeeRole.ADMIN) {
            return true;
        }
        NodeEchelon node = currentNode();
        if (!EchelonScope.admitsNode(EchelonScope.parseList(employee.echelonScopes), node)) {
            return false;
        }
        for (EmployeeProfile binding : EmployeeProfile.forEmployee(employee.id)) {
            if (profileId.equals(binding.profileId) && binding.appliesTo(node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a record already exists, so that a saving gesture keyed on
     * a business code is known to modify rather than create.
     *
     * @param entity the entity class
     * @param property the property holding the code
     * @param value the posted code
     * @return true when a row carries that code
     */
    public boolean recordExists(Class<?> entity, String property, String value) {
        return QuarkusTransaction.requiringNew().call(() -> entityManager
                .createQuery("select count(e) from " + entity.getSimpleName() + " e where e."
                        + property + " = :value", Long.class)
                .setParameter("value", value)
                .getSingleResult() > 0);
    }

    /**
     * Returns where this node sits in the organisation; a service assembled
     * without the echelon service (unit tests) falls back on the store code.
     *
     * @return the node's place, every part possibly null
     */
    private NodeEchelon currentNode() {
        if (nodeEchelonService != null) {
            return nodeEchelonService.current();
        }
        Store store = Store.<Store>findAll().firstResult();
        return new NodeEchelon(store == null ? null : store.code, null, null);
    }
}
