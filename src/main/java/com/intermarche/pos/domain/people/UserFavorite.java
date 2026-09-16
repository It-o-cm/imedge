package com.intermarche.pos.domain.people;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import com.intermarche.pos.domain.BaseEntity;

/**
 * A user's decision to HIDE one {@link Feature} that their profile otherwise
 * allows (BO-01-04-08, "favoris").
 * <p>
 * The requirement is a personal convenience, not a right: within the limits
 * of what their profiles grant, an account may declutter its interface by
 * hiding features, menus or report types. A hidden feature is DISPLAY-only —
 * it never widens nor narrows what
 * {@link com.intermarche.pos.service.PermissionService} authorizes, it only
 * removes an entry the operator does not want to see. Hence its own table,
 * keyed by employee id and feature key, rather than a flag folded into the
 * authorization model: the two must not be confused.
 * <p>
 * The absence of a row is the normal state (nothing hidden); one row per
 * hidden feature is added when the operator hides it and removed when they
 * restore it.
 */
@Entity
@Table(name = "user_favorites",
        indexes = {
                @Index(name = "idx_user_favorite_employee", columnList = "employee_id")
        }
)
public class UserFavorite extends BaseEntity {

    /** The id of the employee whose interface this preference belongs to. */
    @Column(name = "employee_id", nullable = false)
    public Long employeeId;

    /** The stable key of the hidden feature ({@link Feature#getKey()}). */
    @Column(name = "feature_key", nullable = false, length = 40)
    public String featureKey;

    /**
     * Returns the keys of the features an employee has hidden.
     *
     * @param employeeId the employee's id
     * @return the set of hidden feature keys, empty when nothing is hidden
     */
    public static Set<String> hiddenKeys(Long employeeId) {
        List<UserFavorite> rows = list("employeeId", employeeId);
        return rows.stream().map(row -> row.featureKey).collect(Collectors.toSet());
    }

    /**
     * Removes every hidden-feature row of an employee — the clear step before
     * the screen re-writes the current selection.
     *
     * @param employeeId the employee's id
     * @return the number of rows removed
     */
    public static long clearFor(Long employeeId) {
        return delete("employeeId", employeeId);
    }

    /**
     * Hashes the two business columns of the preference.
     *
     * @return the checksum of the preference
     */
    @Override
    public int getChecksum() {
        return Objects.hash(employeeId, featureKey);
    }
}
