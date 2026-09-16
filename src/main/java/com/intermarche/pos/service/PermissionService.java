package com.intermarche.pos.service;

import com.intermarche.pos.domain.people.CrudOption;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.people.EmployeeProfile;
import com.intermarche.pos.domain.setting.Feature;
import com.intermarche.pos.domain.people.Profile;
import com.intermarche.pos.domain.people.ProfileGrant;
import com.intermarche.pos.domain.store.Store;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

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
 *   <li>This layer governs the NEW back-office surfaces (profiles, users,
 *       favorites) and coexists with the {@code @RolesAllowed} guards the
 *       rest of the application still carries. Generalizing it to supplant
 *       every static guard is a rework of the identity lot, not a change to
 *       make in cohabitation — see the campaign report's arbitrage.</li>
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
        String nodePdv = currentNodePdv();
        for (EmployeeProfile binding : EmployeeProfile.forEmployee(employee.id)) {
            if (!binding.appliesTo(nodePdv)) {
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
        return Decision.deny();
    }

    /**
     * Returns the point-of-sale code of the local node, or null when the node
     * has no store row yet.
     *
     * @return the local {@link Store#code}, or null
     */
    private String currentNodePdv() {
        Store store = Store.<Store>findAll().firstResult();
        return store == null ? null : store.code;
    }
}
