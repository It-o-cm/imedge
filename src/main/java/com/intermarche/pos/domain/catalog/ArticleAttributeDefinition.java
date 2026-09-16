package com.intermarche.pos.domain.catalog;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;
import com.intermarche.pos.domain.BaseEntity;

/**
 * An administered article attribute definition (BO-02-03-32): a custom
 * attribute an administrator declares so it can then be attached to article
 * fiches. It complements — never replaces — the code-level
 * {@link com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog}:
 * <ul>
 *   <li>the catalog holds the WELL-KNOWN attributes the register acts upon
 *       (VAT exemption, discount ban, …); those are wired to sale behaviour in
 *       code and cannot be created from a screen;</li>
 *   <li>this entity holds the DECLARATIVE attributes an administrator adds at
 *       runtime; they carry no sale behaviour, only a label, and are captured
 *       on the fiche as free text.</li>
 * </ul>
 * Both kinds are stored in the same open {@link Product#attributes} map keyed
 * by {@code code}, so a declared attribute is distributed by the draw exactly
 * like any other article datum (referential, store side of the frontier). This
 * is referential configuration, not per-register local state.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "article_attribute_definitions",
        uniqueConstraints = @UniqueConstraint(columnNames = "code"))
@Cacheable
public class ArticleAttributeDefinition extends BaseEntity {

    /**
     * The attribute code — the key under which the value is stored in
     * {@link Product#attributes} and the payload map. Unique across the
     * referential.
     */
    @Column(name = "code", nullable = false, length = 50, unique = true)
    @NotBlank(message = "Attribute code is mandatory")
    public String code;

    /** The operator-facing label rendered on the fiche and the admin list. */
    @Column(name = "label", nullable = false, length = 100)
    @NotBlank(message = "Attribute label is mandatory")
    public String label;

    /**
     * Lists every declared attribute definition, ordered by code, for the admin
     * list and the fiche rendering.
     *
     * @return the definitions in code order (never null)
     */
    public static List<ArticleAttributeDefinition> listAllOrdered() {
        return list("order by code");
    }

    /**
     * Finds a declared attribute definition by its unique code.
     *
     * @param code the attribute code
     * @return the definition, or null when no definition carries that code
     */
    public static ArticleAttributeDefinition findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * The change-detection checksum over the declaring fields.
     *
     * @return the checksum of code and label
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label);
    }
}
