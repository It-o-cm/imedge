package com.intermarche.edge.domain.catalog;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;
import com.intermarche.edge.domain.BaseEntity;

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
 * A selection belongs to the POINT OF SALE it was created on (BO-02-03-31,
 * "un libellé par liste et par PDV"): {@link #pdvCode} is the PDV of the node
 * that saved it, and the screens offer a PDV its own lists, plus the lists
 * saved before the rule existed (no PDV), which every PDV reuses.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "article_selections",
        uniqueConstraints = @UniqueConstraint(columnNames = {"name", "pdv_code"}))
@Cacheable
public class ArticleSelection extends BaseEntity {

    /** The operator-facing name of the selection. Unique within its point of sale. */
    @Column(name = "name", nullable = false, length = 100)
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
     * The point of sale the selection belongs to, or null for a selection every
     * point of sale shares (BO-02-03-31).
     */
    @Column(name = "pdv_code", length = 20)
    public String pdvCode;

    /**
     * Lists the selections a point of sale works with: its own and the shared
     * ones, ordered by name.
     *
     * @param pdv the point of sale, or null to list the shared ones only
     * @return the selections
     */
    public static List<ArticleSelection> listForPdv(String pdv) {
        if (pdv == null) {
            return list("pdvCode is null order by name");
        }
        return list("pdvCode is null or pdvCode = ?1 order by name", pdv);
    }

    /**
     * Finds the selection of a given name a point of sale works with: its own
     * first, the shared one otherwise.
     *
     * @param name the selection name
     * @param pdv the point of sale, or null
     * @return the selection, or null
     */
    public static ArticleSelection findForPdv(String name, String pdv) {
        ArticleSelection own = pdv == null ? null
                : ArticleSelection.<ArticleSelection>find("name = ?1 and pdvCode = ?2", name, pdv).firstResult();
        return own != null ? own : ArticleSelection.<ArticleSelection>find("name = ?1 and pdvCode is null", name)
                .firstResult();
    }

    /**
     * The change-detection checksum over the defining fields.
     *
     * @return the checksum of name, criteria and point of sale
     */
    @Override
    public int getChecksum() {
        return Objects.hash(name, criteria, pdvCode);
    }
}
