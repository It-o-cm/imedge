package com.intermarche.pos.domain.setting;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How one article group is DRAWN on the direct-entry touch grid.
 * <p>
 * These four settings used to live on {@code ProductFamily} itself, and that
 * was the wrong owner. A family is a classification: it is imported from the
 * gestion commerciale, replaced wholesale at every integration, and the same
 * article may hang under several of them. Whether a group is pinned, how big
 * its button is and in what order it comes are none of those things — they are
 * administered in the back office, they describe a screen, and no commercial
 * system emits them. Holding both on one row meant that re-importing the
 * nomenclature erased the shop's touch configuration, and that the touch
 * screen could not be reorganized without writing into data the gestion
 * commerciale owns.
 * <p>
 * The link is the family CODE, not its database id: the code is the upsert key
 * of the nomenclature everywhere else in the house, and it survives a family
 * being deleted and re-imported.
 * <p>
 * A group with no row here is not an error. It draws with the defaults below —
 * not pinned, normal size, rank zero, no volume — which is exactly how an
 * unconfigured group behaved before. The register keeps its own behaviour when
 * nothing administers the key; the same fallback doctrine as the tender and
 * document referentials.
 */
@Entity
@Table(name = "touch_group_settings",
        uniqueConstraints = @UniqueConstraint(columnNames = "family_code"))
public class TouchGroupSetting extends BaseEntity {

    /** The default touch size, applied to any group with no administered row. */
    public static final String DEFAULT_BUTTON_SIZE = "NORMAL";

    /** The code of the article group this row configures (upsert key). */
    @Column(name = "family_code", nullable = false, length = 50, unique = true)
    @NotBlank(message = "Family code is mandatory")
    public String familyCode;

    /**
     * Whether this group is PINNED (BO-03-01-07): a pinned group stays shown at
     * the register whatever the cashier's navigation — it is prepended to every
     * page of the group grid and to every drilled-down level. Bounded to at
     * most four pinned groups by the back office.
     */
    @Column(name = "pinned", nullable = false)
    public boolean pinned = false;

    /**
     * The touch SIZE of this group (BO-03-01-08): one of {@code SMALL},
     * {@code NORMAL} or {@code LARGE}, rendered as a differently sized button.
     * Never null; an unknown value renders as the normal size.
     */
    @Column(name = "button_size", length = 10, nullable = false)
    public String buttonSize = DEFAULT_BUTTON_SIZE;

    /**
     * The custom rank of this group in the grid (BO-03-01-11), honoured when
     * the display-order mode is {@code CUSTOM}: lower comes first, ties broken
     * by description.
     */
    @Column(name = "display_order", nullable = false)
    public int displayOrder = 0;

    /**
     * The sales volume of this group (BO-03-01-13), honoured when the
     * display-order mode is {@code VOLUME}: higher comes first, ties broken by
     * description. Fed from the gestion commerciale or set in the back office;
     * the register does not aggregate sales into it.
     */
    @Column(name = "sales_volume", nullable = false)
    public long salesVolume = 0L;

    /**
     * Default constructor for JPA.
     */
    public TouchGroupSetting() {
    }

    /**
     * Builds a row carrying the defaults for a group nothing administers.
     * <p>
     * It is NOT persisted: it exists so a reader can treat the administered and
     * the unadministered group the same way, without a null check at every
     * call site.
     *
     * @param familyCode the group code the defaults stand for
     * @return an unpersisted row holding the default drawing
     */
    public static TouchGroupSetting defaults(String familyCode) {
        TouchGroupSetting setting = new TouchGroupSetting();
        setting.familyCode = familyCode;
        return setting;
    }

    /**
     * Finds the row configuring one group.
     *
     * @param familyCode the group code
     * @return the administered row, or null when the group is not administered
     */
    public static TouchGroupSetting findByFamilyCode(String familyCode) {
        return find("familyCode", familyCode).firstResult();
    }

    /**
     * Lists every administered row, in group-code order.
     *
     * @return the administered rows
     */
    public static List<TouchGroupSetting> listAllOrdered() {
        return list("order by familyCode");
    }

    /**
     * Loads the whole touch configuration at once, indexed by group code.
     * <p>
     * The touch grid needs the setting of every group it draws on one page;
     * reading them one by one would turn a screen into as many queries as
     * there are tiles.
     *
     * @return the administered rows by group code, never null
     */
    public static Map<String, TouchGroupSetting> byFamilyCode() {
        Map<String, TouchGroupSetting> byCode = new HashMap<>();
        for (TouchGroupSetting setting : listAllOrdered()) {
            byCode.put(setting.familyCode, setting);
        }
        return byCode;
    }

    /**
     * Returns the row configuring a group, or the defaults when none does.
     *
     * @param byCode the configuration indexed by group code, possibly null
     * @param familyCode the group code looked up, possibly null
     * @return the administered row, or an unpersisted row of defaults
     */
    public static TouchGroupSetting orDefaults(Map<String, TouchGroupSetting> byCode,
                                               String familyCode) {
        TouchGroupSetting setting = byCode == null ? null : byCode.get(familyCode);
        return setting == null ? defaults(familyCode) : setting;
    }

    /**
     * How many groups are pinned right now (the bound the back office opposes).
     *
     * @return the number of administered rows carrying the pinned flag
     */
    public static long countPinned() {
        return count("pinned", true);
    }

    /**
     * The fingerprint input of this row for the referential export.
     *
     * @return a hash of the code and the four administered values
     */
    @Override
    public int getChecksum() {
        return Objects.hash(familyCode, pinned, buttonSize, displayOrder, salesVolume);
    }
}
