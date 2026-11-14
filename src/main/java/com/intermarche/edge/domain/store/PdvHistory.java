package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One change of the ORGANISATIONAL IDENTITY of a point of sale
 * (BO-02-05-01, BO-02-05-02, BO-02-05-05): its number, its enseigne or its
 * adhérent, what it was, what it became and when.
 *
 * <p>The history is what keeps the past readable. The data a point of sale
 * produced — its reports, its supervision, its deployment traces — are filed
 * under the number it carried THEN and stay there: renumbering a PDV does not
 * rewrite them, and this table says which PDV a former number belonged to and
 * until when. The same goes for an enseigne: a PDV moved to another enseigne
 * keeps its past, and the history tells under which enseigne each period was
 * produced.
 *
 * <p>A former number is never handed to another point of sale: the data filed
 * under it would otherwise be read as the new holder's.
 */
@Entity
@Table(name = "pdv_history",
        indexes = {
                @Index(name = "idx_pdv_history_pdv", columnList = "pdv_id"),
                @Index(name = "idx_pdv_history_old", columnList = "change_kind, old_value")
        })
public class PdvHistory extends PanacheEntity {

    /** What changed. */
    public enum Kind {

        /** The five-digit number (BO-02-05-01). */
        NUMBER,

        /** The enseigne the PDV is attached to (BO-02-05-02). */
        ENSEIGNE,

        /** The adhérent operating the PDV (BO-02-05-05). */
        ADHERENT
    }

    /** By which road the change came. */
    public enum Source {

        /** The echelons screen of the back office. */
        SCREEN,

        /** The import of the points of sale (BO-02-05-03). */
        IMPORT
    }

    /** The database id of the point of sale. */
    @Column(name = "pdv_id", nullable = false)
    public Long pdvId;

    /** What changed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_kind", nullable = false, length = 10)
    public Kind kind;

    /** The value before the change, or null when there was none. */
    @Column(name = "old_value", length = 32)
    public String oldValue;

    /** The value after the change, or null when it was cleared. */
    @Column(name = "new_value", length = 32)
    public String newValue;

    /** When the change was made. */
    @Column(name = "changed_at", nullable = false)
    public LocalDateTime changedAt;

    /** By which road the change came. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    public Source source;

    /**
     * Lists the changes of one point of sale, most recent first.
     *
     * @param pdvId the database id of the point of sale
     * @return the changes, never null
     */
    public static List<PdvHistory> listForPdv(Long pdvId) {
        return list("pdvId = ?1 order by changedAt desc, id desc", pdvId);
    }

    /**
     * Finds the point of sale that formerly carried a number.
     *
     * @param number the five-digit number, possibly null
     * @return the database id of its former holder, or null when no point of
     *         sale ever left that number
     */
    public static Long formerHolderOf(String number) {
        if (number == null) {
            return null;
        }
        PdvHistory change = find("kind = ?1 and oldValue = ?2", Kind.NUMBER, number).firstResult();
        return change == null ? null : change.pdvId;
    }
}
