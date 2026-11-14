package com.intermarche.edge.domain.setting;

import com.intermarche.edge.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;

/**
 * A RETURN TYPE administered in the back office and distributed to the
 * registers ({@code BO-03-13-01} to {@code -04}, {@code -11}, {@code -12}).
 *
 * <p>Until here a return type was an entry of a list of the
 * {@code RETURN_TYPE} kind, or of the semicolon-separated {@code return.types}
 * setting before it: a label and option markers typed as text. That is enough
 * to draw a key and not enough to administer a type — nothing could say which
 * of its options were set without parsing a string, and a type could not be
 * kept switched off without being retyped later.
 *
 * <p>So the type becomes a referential of its own, with its own screen and its
 * own distribution domain: a CODE the refunds and the return lines are filed
 * under, a LABEL the cashier reads, one column per option the register honours
 * and an ACTIVE flag. The register offers the active types; the list and the
 * setting remain its fallback while the shop administers no type here, so a
 * shop that never opens the new screen keeps exactly the keys it had.
 */
@Entity
@Table(name = "return_type_definitions",
        indexes = {
                @Index(name = "idx_return_type_code", columnList = "code")
        }
)
public class ReturnTypeDefinition extends BaseEntity {

    /** The type's code, unique, the key the refunds and the distribution use. */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    @NotBlank
    public String code;

    /** What the key is called in front of the cashier. */
    @Column(name = "label", nullable = false, length = 80)
    @NotBlank
    public String label;

    /**
     * The TICKET mode ({@code BO-03-13-04}): the key opens a dedicated return
     * ticket on the original sale, which must be identified — scanned, or
     * keyed by its number and its date — before anything is given back.
     * False: the article is taken back inside the sale in progress.
     */
    @Column(name = "ticket_mode", nullable = false)
    public boolean ticketMode;

    /** No supervisor endorsement for this type ({@code BO-03-13-08}). */
    @Column(name = "endorsement_exempt", nullable = false)
    public boolean endorsementExempt;

    /** Usable when the original ticket was not found ({@code BO-03-13-06}). */
    @Column(name = "origin_optional", nullable = false)
    public boolean originOptional;

    /** Each returned line has its price confirmed ({@code BO-03-13-10}). */
    @Column(name = "price_confirmed", nullable = false)
    public boolean priceConfirmed;

    /**
     * The type is a DÉCONSIGNE ({@code BO-03-13-11}): what is returned under
     * it feeds the deposit report.
     */
    @Column(name = "deposit_return", nullable = false)
    public boolean depositReturn;

    /**
     * The code of the RETURN barcode range attached to the type
     * ({@code BO-03-13-12}), or null: a code of that range is taken back under
     * this type, with its permissions, and no supervisor is called.
     */
    @Column(name = "range_code", length = 40)
    public String rangeCode;

    /** The rank the key is drawn at, lowest first. */
    @Column(name = "display_order", nullable = false)
    public int displayOrder;

    /** Whether the register offers the type ({@code BO-03-13-03}). */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * Finds a type by its code.
     *
     * @param code the code looked for, possibly null
     * @return the type, or null when no type carries that code
     */
    public static ReturnTypeDefinition findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim()).firstResult();
    }

    /**
     * Lists every administered type, active or not, in display order.
     *
     * @return the types, possibly empty
     */
    public static List<ReturnTypeDefinition> findAllOrdered() {
        return list("order by displayOrder, code");
    }

    /**
     * Lists the types the register offers, in display order.
     *
     * @return the active types, possibly empty
     */
    public static List<ReturnTypeDefinition> findActive() {
        return list("active = true order by displayOrder, code");
    }

    /**
     * Hashes the business fields, for the change detection of the base entity.
     *
     * @return the checksum of this row
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, ticketMode, endorsementExempt, originOptional,
                priceConfirmed, depositReturn, rangeCode, displayOrder, active);
    }
}
