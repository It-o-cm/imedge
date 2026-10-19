package com.intermarche.pos.domain.setting;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * ONE entry of an administered list ({@code BO-04-02-07},
 * {@code BO-04-03-12}).
 *
 * <p>An entry always carries a code, a label and a rank. What it carries
 * BESIDES depends on the kind of its list, and a column a kind does not use
 * is simply left alone:
 *
 * <ul>
 *   <li>{@code SERVICE_REQUEST} reads {@link #severity} — the priority the
 *       back office shows when the call lands.</li>
 *   <li>{@code TARE_CONTAINER} reads {@link #numericValue} — the weight of
 *       the container in kilograms.</li>
 *   <li>{@code RETURN_TYPE} reads {@link #options} — the markers {@code
 *       TICKET}, {@code NOENDORSE}, {@code NOORIGIN} and {@code PRICE}, the
 *       same four the administered string carried.</li>
 *   <li>{@code JUSTIFICATION} reads neither: a motive is a label.</li>
 * </ul>
 *
 * <p>One table rather than four, because the four lists differ by two
 * columns and nothing else: four tables would have meant four screens, four
 * distribution domains and four apply methods for one gesture — administer a
 * list of things the cashier taps.
 */
@Entity
@Table(name = "pos_list_items",
        indexes = {
                @Index(name = "idx_pos_list_item_list", columnList = "list_id")
        }
)
public class PosListItem extends BaseEntity {

    /** The list this entry belongs to. */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "list_id", nullable = false)
    public PosList posList;

    /** The entry's code, unique inside its list. */
    @Column(name = "code", nullable = false, length = 40)
    @NotBlank
    public String code;

    /** What the key is called in front of the cashier. */
    @Column(name = "label", nullable = false, length = 80)
    @NotBlank
    public String label;

    /** The rank the entry is drawn at, lowest first. */
    @Column(name = "display_order", nullable = false)
    public int displayOrder;

    /**
     * The priority of a service request, from 1 (lowest) upwards; 0 when the
     * kind of the list does not use it.
     */
    @Column(name = "severity", nullable = false)
    public int severity;

    /** The weight of a tare container in kilograms, or null when unused. */
    @Column(name = "numeric_value", precision = 10, scale = 3)
    public BigDecimal numericValue;

    /** The option markers of a return type, comma-separated, or null. */
    @Column(name = "options", length = 120)
    public String options;

    /** Whether the entry is offered at all. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * Hashes the business fields, for the change detection of the base entity.
     *
     * @return the checksum of this row
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, displayOrder, severity, numericValue, options, active);
    }
}
