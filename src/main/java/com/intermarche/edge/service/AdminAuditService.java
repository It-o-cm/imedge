package com.intermarche.edge.service;

import com.intermarche.edge.domain.audit.AdminAuditEntry;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Writes the administration log (BO-01-01-01, BO-01-01-03): one entry per
 * modifying back-office gesture and per refusal.
 *
 * <p>Each entry is written in a transaction of its own, so a gesture whose
 * transaction rolls back still leaves its trace, and a log that cannot be
 * written never undoes the gesture it describes.
 */
@ApplicationScoped
public class AdminAuditService {

    private static final Logger LOGGER = Logger.getLogger(AdminAuditService.class);

    /** The longest detail kept. */
    static final int DETAIL_LENGTH = 500;

    /** The longest reference kept. */
    static final int REF_LENGTH = 80;

    /** The longest path kept. */
    static final int PATH_LENGTH = 255;

    /** Where this node sits in the organisation. */
    @Inject
    NodeEchelonService nodeEchelonService;

    /**
     * Records one gesture.
     *
     * @param loginName the signed-in account's login
     * @param feature the feature reached
     * @param option the option exercised
     * @param requestPath the HTTP method and path
     * @param targetRef the record named, or null
     * @param outcome how it ended
     * @param detail the notice or the reason, or null
     * @param validatorLogin the validating account's login, or null
     */
    public void record(String loginName, Feature feature, CrudOption option, String requestPath,
                       String targetRef, AdminAuditEntry.Outcome outcome, String detail,
                       String validatorLogin) {
        try {
            QuarkusTransaction.requiringNew().run(() -> persist(loginName, feature, option,
                    requestPath, targetRef, outcome, detail, validatorLogin));
        } catch (RuntimeException e) {
            LOGGER.warnf("Journal d'administration non écrit pour %s sur %s : %s",
                    loginName, requestPath, e.getMessage());
        }
    }

    /**
     * Builds and persists the entry, inside the transaction opened by
     * {@link #record}.
     *
     * @param loginName the signed-in account's login
     * @param feature the feature reached
     * @param option the option exercised
     * @param requestPath the HTTP method and path
     * @param targetRef the record named, or null
     * @param outcome how it ended
     * @param detail the notice or the reason, or null
     * @param validatorLogin the validating account's login, or null
     */
    void persist(String loginName, Feature feature, CrudOption option, String requestPath,
                 String targetRef, AdminAuditEntry.Outcome outcome, String detail,
                 String validatorLogin) {
        AdminAuditEntry entry = new AdminAuditEntry();
        entry.occurredAt = DateTimeProvider.now();
        entry.loginName = loginName;
        Employee account = Employee.findLogin(loginName);
        entry.fullName = account == null ? null : account.getFullName();
        entry.featureKey = feature.getKey();
        entry.option = option.name();
        entry.requestPath = cut(requestPath, PATH_LENGTH);
        entry.targetRef = cut(targetRef, REF_LENGTH);
        entry.outcome = outcome;
        entry.detail = cut(detail, DETAIL_LENGTH);
        entry.validatorLogin = validatorLogin;
        entry.pdv = nodeEchelonService == null ? null : nodeEchelonService.currentPdv();
        entry.persist();
    }

    /**
     * Cuts a text to the length its column keeps.
     *
     * @param value the text, possibly null
     * @param length the longest length kept
     * @return the text, cut, or null
     */
    static String cut(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }
}
