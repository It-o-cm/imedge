package com.intermarche.edge.domain.audit;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Parameters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One back-office gesture that changes something, as the administration log
 * keeps it (BO-01-01-01 « toutes les actions d'un Super Admin », BO-01-01-03
 * « logs dédiés »): who, when, on which screen, under which option, with
 * which validation, and how it ended.
 *
 * <p>Written by the access filter for EVERY modifying gesture of EVERY
 * screen, and for every refusal: the log does not depend on each screen
 * remembering to write it.
 */
@Entity
@Table(name = "admin_audit_entries",
        indexes = {
                @Index(name = "idx_admin_audit_at", columnList = "occurred_at"),
                @Index(name = "idx_admin_audit_login", columnList = "login_name")
        })
public class AdminAuditEntry extends PanacheEntity {

    /** How a gesture ended. */
    public enum Outcome {

        /** The screen carried the gesture out. */
        DONE,

        /** The screen refused the gesture (its own notice says why). */
        REFUSED,

        /** The access control refused the gesture: no right. */
        DENIED,

        /** The gesture waited for, or failed, its validation. */
        VALIDATION
    }

    /** When the gesture was made. */
    @Column(name = "occurred_at", nullable = false)
    public LocalDateTime occurredAt;

    /** The signed-in account's login. */
    @Column(name = "login_name", nullable = false, length = 80)
    public String loginName;

    /** The signed-in account's full name, as it read at that moment. */
    @Column(name = "full_name", length = 160)
    public String fullName;

    /** The key of the feature reached. */
    @Column(name = "feature_key", nullable = false, length = 40)
    public String featureKey;

    /** The option exercised (VIEW, CREATE, UPDATE, DELETE). */
    @Column(name = "crud_option", nullable = false, length = 20)
    public String option;

    /** The HTTP method and path of the gesture. */
    @Column(name = "request_path", nullable = false, length = 255)
    public String requestPath;

    /** The record the gesture named (its id or code), when it named one. */
    @Column(name = "target_ref", length = 80)
    public String targetRef;

    /** How the gesture ended. */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 12)
    public Outcome outcome;

    /** The notice the screen answered, or the reason of a refusal. */
    @Column(name = "detail", length = 500)
    public String detail;

    /** The login of the account that validated the gesture, when one did. */
    @Column(name = "validator_login", length = 80)
    public String validatorLogin;

    /** The point of sale of the node the gesture was made on. */
    @Column(name = "pdv", length = 20)
    public String pdv;

    /**
     * Lists the entries matching a filter, newest first.
     *
     * @param from the first moment kept, or null
     * @param to the moment after the last one kept, or null
     * @param loginName the login kept, or null for every account
     * @param featureKey the feature kept, or null for every screen
     * @return the entries
     */
    public static List<AdminAuditEntry> search(LocalDateTime from, LocalDateTime to,
                                               String loginName, String featureKey) {
        StringBuilder query = new StringBuilder("1 = 1");
        Parameters parameters = new Parameters();
        if (from != null) {
            query.append(" and occurredAt >= :from");
            parameters.and("from", from);
        }
        if (to != null) {
            query.append(" and occurredAt < :to");
            parameters.and("to", to);
        }
        if (loginName != null) {
            query.append(" and loginName = :login");
            parameters.and("login", loginName);
        }
        if (featureKey != null) {
            query.append(" and featureKey = :feature");
            parameters.and("feature", featureKey);
        }
        query.append(" order by occurredAt desc, id desc");
        return list(query.toString(), parameters);
    }
}
