package com.intermarche.pos.domain.people;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.intermarche.pos.domain.setting.Feature;
import com.intermarche.pos.domain.BaseEntity;

/**
 * A PROFILE: a named, reusable bundle of {@link ProfileGrant grants}, each a
 * (functionality &times; option) pair (BO-01-03-01, BO-01-04-01/02).
 * <p>
 * A profile is the composable unit that replaces the four fixed employee
 * roles for the back office: it is created, modified and deleted from the
 * administration screen without a redeployment, and it is bound to employees
 * — one or several per employee — by {@link EmployeeProfile} at a given
 * echelon (BO-01-03-02/03). The right an account actually holds is the union
 * of the grants of the profiles bound to it and applicable to the node it
 * signs in on; {@link com.intermarche.pos.service.PermissionService} does the
 * union, this entity only answers for itself.
 * <p>
 * A profile is administered CENTRALLY in the AO's model; in this suite it is
 * administered on the node where the back office runs and evaluated on that
 * same node, so it sits — like the point-of-sale row and the parameters —
 * outside the local/register frontier concerns of {@link Employee}. Its
 * checksum hashes the name and the grant set so a pull that ever carries it
 * can detect a change; today nothing pushes it.
 */
@Entity
@Table(name = "profiles")
public class Profile extends BaseEntity {

    /** The unique, operator-chosen name of the profile. */
    @Column(nullable = false, unique = true, length = 80)
    @NotBlank(message = "Profile name is mandatory")
    public String name;

    /** A free-text description of what the profile is for. */
    @Column(length = 255)
    public String description;

    /** The grants that make up the profile — never null, possibly empty. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "profile_grants", joinColumns = @JoinColumn(name = "profile_id"))
    public List<ProfileGrant> grants = new ArrayList<>();

    /**
     * Returns the grant addressing a given feature and option, if the profile
     * holds it.
     *
     * @param feature the feature to look up
     * @param option the option to look up
     * @return the matching grant, or null when the profile does not hold it
     */
    public ProfileGrant grant(Feature feature, CrudOption option) {
        for (ProfileGrant candidate : grants) {
            if (candidate.matches(feature, option)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Indicates whether the profile grants a given feature and option.
     *
     * @param feature the feature to test
     * @param option the option to test
     * @return true when a matching grant exists
     */
    public boolean allows(Feature feature, CrudOption option) {
        return grant(feature, option) != null;
    }

    /**
     * Finds a profile by its unique name.
     *
     * @param name the profile name to search for
     * @return the profile, or null when none bears the name
     */
    public static Profile findByName(String name) {
        return find("name", name).firstResult();
    }

    /**
     * Lists every profile, ordered by name for a stable administration list.
     *
     * @return the profiles in name order, never null
     */
    public static List<Profile> listAllOrdered() {
        return listAll(io.quarkus.panache.common.Sort.by("name"));
    }

    /**
     * Hashes the name and the grant set: a change to either is a change to the
     * profile.
     *
     * @return the checksum of the profile's business content
     */
    @Override
    public int getChecksum() {
        return Objects.hash(name, description, grants);
    }
}
