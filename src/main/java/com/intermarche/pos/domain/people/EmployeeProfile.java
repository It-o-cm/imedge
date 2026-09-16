package com.intermarche.pos.domain.people;

import io.quarkus.panache.common.Sort;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.List;
import java.util.Objects;
import com.intermarche.pos.domain.BaseEntity;

/**
 * The binding of a {@link Profile} to an {@link Employee} at one echelon scope
 * (BO-01-03-02 assign a profile, BO-01-03-03 assign several).
 * <p>
 * A separate link rather than a many-to-many join because the binding carries
 * its own datum — the {@link #echelonScope} — which is the "&times; echelon"
 * of the profile model: the same profile can be granted to an account on one
 * point of sale and withheld on another. The scope is kept deliberately
 * simple against what the suite actually models today: {@link #GLOBAL} (every
 * node) or a point-of-sale {@link Store#code}. The country/enseigne tree the
 * AO describes is an open topology arbitrage; when it lands, the scope
 * becomes a level-plus-code and {@link #appliesTo(String)} grows a
 * containment test — the shape here does not have to change for that.
 * <p>
 * Stored by ids, not object references, on purpose: a binding is a small,
 * frequently read row on the authorization hot path, and it is resolved
 * against ids the identity already carries; a lazy association would drag two
 * entity graphs into every permission check.
 */
@Entity
@Table(name = "employee_profiles",
        indexes = {
                @Index(name = "idx_emp_profile_employee", columnList = "employee_id")
        }
)
public class EmployeeProfile extends BaseEntity {

    /** The scope value that matches every node, whatever its point of sale. */
    public static final String GLOBAL = "*";

    /** The id of the bound employee. */
    @Column(name = "employee_id", nullable = false)
    public Long employeeId;

    /** The id of the bound profile. */
    @Column(name = "profile_id", nullable = false)
    public Long profileId;

    /**
     * The echelon this binding applies to: {@link #GLOBAL} for every node, or
     * a {@link Store#code} to confine it to one point of sale.
     */
    @Column(name = "echelon_scope", nullable = false, length = 20)
    public String echelonScope = GLOBAL;

    /**
     * Indicates whether this binding applies on a node whose point-of-sale
     * code is the given one.
     * <p>
     * A {@link #GLOBAL} (or absent) scope applies everywhere; any other scope
     * applies only where it equals the node's code. A null node code (a node
     * with no store row yet) therefore satisfies a global binding but no
     * confined one.
     *
     * @param nodePdv the point-of-sale code of the current node, or null
     * @return true when this binding is in force on that node
     */
    public boolean appliesTo(String nodePdv) {
        if (echelonScope == null || GLOBAL.equals(echelonScope)) {
            return true;
        }
        return echelonScope.equals(nodePdv);
    }

    /**
     * Lists the bindings of an employee, newest first for a stable display.
     *
     * @param employeeId the employee's id
     * @return the bindings, never null
     */
    public static List<EmployeeProfile> forEmployee(Long employeeId) {
        return list("employeeId", Sort.by("id"), employeeId);
    }

    /**
     * Tests whether an employee already holds a binding to a profile at a
     * given scope — the guard against a duplicate assignment.
     *
     * @param employeeId the employee's id
     * @param profileId the profile's id
     * @param echelonScope the echelon scope
     * @return true when such a binding already exists
     */
    public static boolean exists(Long employeeId, Long profileId, String echelonScope) {
        return count("employeeId = ?1 and profileId = ?2 and echelonScope = ?3",
                employeeId, profileId, echelonScope) > 0;
    }

    /**
     * Hashes the three business columns of the binding.
     *
     * @return the checksum of the binding
     */
    @Override
    public int getChecksum() {
        return Objects.hash(employeeId, profileId, echelonScope);
    }
}
