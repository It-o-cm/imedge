package com.intermarche.pos.domain.people;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.Objects;
import com.intermarche.pos.domain.setting.Feature;

/**
 * One line of a {@link Profile}: the right to use a {@link Feature} under one
 * {@link CrudOption}, optionally subject to a validation (BO-01-04-03) and,
 * when so, by another profile acting as a third party (BO-01-04-05).
 * <p>
 * Stored as an {@code @ElementCollection} of its owning profile — a grant has
 * no identity of its own, it only exists as part of the bundle a profile is.
 * The feature is kept by its stable {@link Feature#getKey() key} rather than
 * as an enum column, so a grant survives a re-ordering of the enum and a
 * stored key that no longer resolves is simply an inert line (a feature the
 * code no longer serves cannot be exercised anyway).
 */
@Embeddable
public class ProfileGrant {

    /** The stable key of the granted feature ({@link Feature#getKey()}). */
    @Column(name = "feature_key", nullable = false, length = 40)
    public String featureKey;

    /** The option granted on the feature. */
    @Enumerated(EnumType.STRING)
    @Column(name = "crud_option", nullable = false, length = 20)
    public CrudOption option;

    /**
     * Whether exercising this grant requires a validation before it takes
     * effect (BO-01-04-03).
     */
    @Column(name = "requires_validation", nullable = false)
    public boolean requiresValidation = false;

    /**
     * The id of the profile whose holder validates this grant when a
     * validation is required (BO-01-04-05, the supervisor third party), or
     * null when the validation is not delegated to a specific profile.
     */
    @Column(name = "validating_profile_id")
    public Long validatingProfileId;

    /**
     * Builds an empty grant — required by JPA.
     */
    public ProfileGrant() {
    }

    /**
     * Builds a fully specified grant.
     *
     * @param featureKey the stable key of the granted feature
     * @param option the option granted on the feature
     * @param requiresValidation whether the grant is subject to a validation
     * @param validatingProfileId the validating profile's id, or null
     */
    public ProfileGrant(String featureKey, CrudOption option,
                        boolean requiresValidation, Long validatingProfileId) {
        this.featureKey = featureKey;
        this.option = option;
        this.requiresValidation = requiresValidation;
        this.validatingProfileId = validatingProfileId;
    }

    /**
     * Indicates whether this grant addresses a given feature and option.
     *
     * @param feature the feature to test
     * @param wanted the option to test
     * @return true when both the feature key and the option match
     */
    public boolean matches(Feature feature, CrudOption wanted) {
        return feature != null && feature.getKey().equals(featureKey) && option == wanted;
    }

    /**
     * Compares two grants by their business content (feature, option and
     * validation), ignoring nothing — two grants are equal when they express
     * the same right under the same validation.
     *
     * @param o the object to compare
     * @return true when the two grants express the same right
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ProfileGrant that = (ProfileGrant) o;
        return requiresValidation == that.requiresValidation
                && Objects.equals(featureKey, that.featureKey)
                && option == that.option
                && Objects.equals(validatingProfileId, that.validatingProfileId);
    }

    /**
     * Returns a hash consistent with {@link #equals(Object)}.
     *
     * @return the business hash of this grant
     */
    @Override
    public int hashCode() {
        return Objects.hash(featureKey, option, requiresValidation, validatingProfileId);
    }
}
