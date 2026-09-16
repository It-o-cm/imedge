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
 * A saved, reusable article selection (BO-02-03-31): a named grouping of
 * articles defined by search criteria, meant to restrict the perimeter of a
 * report or an export. The selection stores its criteria as the canonical
 * query string produced by {@code ArticleSearchCriteria}, so applying a
 * selection is exactly re-running that search — the same multi-criteria engine
 * the fiche list uses (BO-02-03-30), never a second, divergent one.
 * <p>
 * The stored form is a query string rather than a frozen list of article ids:
 * the selection is a live perimeter (an article integrated later that matches
 * the criteria joins the selection at the next view), which is what a reusable
 * grouping for reports and exports must be. Referential configuration, store
 * side of the frontier.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "article_selections",
        uniqueConstraints = @UniqueConstraint(columnNames = "name"))
@Cacheable
public class ArticleSelection extends BaseEntity {

    /** The operator-facing name of the selection. Unique across the referential. */
    @Column(name = "name", nullable = false, length = 100, unique = true)
    @NotBlank(message = "Selection name is mandatory")
    public String name;

    /**
     * The selection criteria, stored as the canonical query string of
     * {@code ArticleSearchCriteria} (e.g. {@code type=WEIGHT&status=ACTIVE}).
     * Empty when the selection matches every article.
     */
    @Column(name = "criteria", length = 500)
    public String criteria;

    /**
     * Lists every saved selection, ordered by name, for the admin list and the
     * fiche-list selection picker.
     *
     * @return the selections in name order (never null)
     */
    public static List<ArticleSelection> listAllOrdered() {
        return list("order by name");
    }

    /**
     * Finds a saved selection by its unique name.
     *
     * @param name the selection name
     * @return the selection, or null when no selection carries that name
     */
    public static ArticleSelection findByName(String name) {
        return find("name", name).firstResult();
    }

    /**
     * The change-detection checksum over the defining fields.
     *
     * @return the checksum of name and criteria
     */
    @Override
    public int getChecksum() {
        return Objects.hash(name, criteria);
    }
}
