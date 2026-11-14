package com.intermarche.edge.domain.people;

import com.intermarche.edge.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.List;
import java.util.Objects;

/**
 * One right to change one datum of the article fiche, granted to one profile
 * (BO-02-03-33).
 *
 * <p>The rule the rows express is restrictive and opt-in: a datum NO row names
 * is changed by every account the ARTICLE screen lets save, which is how the
 * fiche behaved before; a datum some rows name is changed only by the accounts
 * holding one of those profiles — the administrator always excepted. A shop
 * that administers nothing therefore keeps its fiche as it was, and a shop
 * that wants the VAT code reserved to the accounting profile names that one
 * datum for that one profile.
 */
@Entity
@Table(name = "article_field_rights",
        uniqueConstraints = @UniqueConstraint(columnNames = {"profile_id", "field_code"}))
public class ArticleFieldRight extends BaseEntity {

    /** The profile granted the right. */
    @Column(name = "profile_id", nullable = false)
    public Long profileId;

    /** The datum of the fiche, an {@code ArticleField} name. */
    @Column(name = "field_code", nullable = false, length = 40)
    public String fieldCode;

    /**
     * Lists every right, by datum then profile.
     *
     * @return the rights
     */
    public static List<ArticleFieldRight> listAllOrdered() {
        return list("order by fieldCode, profileId");
    }

    /**
     * Hashes the business fields, for the change detection of the base entity.
     *
     * @return the checksum of this row
     */
    @Override
    public int getChecksum() {
        return Objects.hash(profileId, fieldCode);
    }
}
