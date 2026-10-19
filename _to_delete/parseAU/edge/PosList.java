package com.intermarche.edge.domain.setting;

import com.intermarche.edge.domain.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A NAMED LIST administered in the back office and distributed to the
 * registers ({@code BO-04-02-07}, {@code BO-04-03-12}).
 *
 * <p>Four lists the cashier taps were, until here, a SETTING holding a
 * semicolon-separated string: the service-request reasons
 * ({@code supervisor.service-reasons}), the expense justifications
 * ({@code cash.movement-reasons}), the return types ({@code return.types})
 * and the tare containers ({@code balance.tare-containers}). A string is
 * enough to draw four keys and not enough for anything else: it carries no
 * code, so nothing downstream can name an entry; no severity, so a back
 * office cannot sort the calls it receives; and no grouping, so the same
 * motives cannot serve two gestures under one heading.
 *
 * <p>So the string becomes a referential: a list has a CODE, a LABEL, a KIND
 * that says what it drives, and ITEMS. What the entry carries besides its
 * label depends on the kind, and the extra columns of {@link PosListItem} are
 * read by the kind that needs them — a severity for a service request, a
 * weight for a tare container, option markers for a return type.
 *
 * <p>THE SETTING IS NOT REMOVED, and that is deliberate: a register whose
 * node holds no list of a kind keeps reading the administered string, so a
 * shop that never opens this screen behaves exactly as before. The
 * referential wins the moment a list of that kind exists.
 */
@Entity
@Table(name = "pos_lists",
        indexes = {
                @Index(name = "idx_pos_list_code", columnList = "code"),
                @Index(name = "idx_pos_list_kind", columnList = "kind")
        }
)
public class PosList extends BaseEntity {

    /**
     * What a list drives, which is also what its items mean.
     */
    public enum Kind {

        /** Service requests a register sends to the back office (BO-04-02-07). */
        SERVICE_REQUEST("Demandes de service"),

        /** Justifications offered for a register gesture (BO-04-03-12). */
        JUSTIFICATION("Justifications"),

        /** Return types offered on the return screen (LC-05-04-01). */
        RETURN_TYPE("Types de retour"),

        /** Containers offered for a one-off tare (LC-14-01-04). */
        TARE_CONTAINER("Contenants de tare"),

        /** Reasons offered for a return (LC-05-05-01, BO-06-06-05). */
        RETURN_REASON("Motifs de retour");

        /** What the administration screen calls this kind. */
        private final String label;

        /**
         * Builds a kind with its screen label.
         *
         * @param label what the administration screen calls it
         */
        Kind(String label) {
            this.label = label;
        }

        /**
         * What the administration screen calls this kind.
         *
         * @return the screen label, never null
         */
        public String getLabel() {
            return label;
        }
    }

    /** The list's code, unique, the key the distribution upserts on. */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    @NotBlank
    public String code;

    /** The heading the list is grouped under, shown to the administrator. */
    @Column(name = "label", nullable = false, length = 80)
    @NotBlank
    public String label;

    /** What the list drives, never null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    public Kind kind;

    /**
     * The register functions this list serves ({@code BO-04-03-12}), their
     * codes separated by semicolons, or null when it serves them all.
     *
     * <p>Only a JUSTIFICATION list reads it: the same motives serve one
     * gesture or several, which is exactly what the requirement asks for —
     * "associée à une ou plusieurs fonctions". The function codes the
     * register knows are {@code EXPENSE} (a drawer expense), {@code
     * WITHDRAWAL}, {@code DEPOSIT}, {@code LINE_CANCEL}, {@code
     * TICKET_CANCEL}, {@code PRICE_OVERRIDE}, {@code DISCOUNT} and {@code
     * RETURN}; an unknown code names a gesture nothing asks about yet and is
     * simply never matched.
     */
    @Column(name = "functions", length = 200)
    public String functions;

    /** Whether the list is offered at all. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** The entries, in display order. */
    @OneToMany(mappedBy = "posList", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder, id")
    public List<PosListItem> items = new ArrayList<>();

    /**
     * Finds a list by its code.
     *
     * @param code the code looked for, possibly null
     * @return the list, or null when no list carries that code
     */
    public static PosList findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim()).firstResult();
    }

    /**
     * Lists the ACTIVE lists of one kind, in code order.
     *
     * @param kind the kind looked for
     * @return the matching lists, possibly empty
     */
    public static List<PosList> findActiveByKind(Kind kind) {
        return list("kind = ?1 and active = true order by code", kind);
    }

    /**
     * Whether this list serves a register function ({@code BO-04-03-12}): a
     * list naming no function serves them all, which is what a shop with one
     * list of motives wants.
     *
     * @param function the function code asked about, possibly null
     * @return true when the list serves it
     */
    public boolean servesFunction(String function) {
        if (functions == null || functions.isBlank()) {
            return true;
        }
        if (function == null || function.isBlank()) {
            return false;
        }
        for (String part : functions.split(";")) {
            if (part.trim().equalsIgnoreCase(function.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hashes the business fields, for the change detection of the base entity.
     *
     * @return the checksum of this row
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, kind, functions, active);
    }
}
