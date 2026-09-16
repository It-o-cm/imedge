package com.intermarche.pos.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One BACK-OFFICE PARAMETER of the register (table {@code pos_settings}):
 * a key/value pair administered on the store node's back office
 * ({@code /admin/settings}) and DISTRIBUTED to the registers by the
 * referential pull (domain SETTINGS), exactly like products or prices.
 * <p>
 * The catalog of known keys — types, labels, defaults — lives in
 * {@link com.intermarche.pos.service.PosSettingsService}: this entity only
 * stores the OVERRIDDEN values; an absent row means "use the default".
 */
@Entity
@Table(name = "pos_settings")
public class PosSetting extends PanacheEntity {

    /** The parameter key (catalog key, e.g. "display.show-ean"). */
    @Column(name = "setting_key", length = 64, unique = true, nullable = false)
    public String settingKey;

    /** The parameter value, as text (typed by the catalog entry). */
    @Column(name = "setting_value", length = 255, nullable = false)
    public String settingValue;

    /**
     * Finds a setting row by its catalog key.
     *
     * @param key the catalog key
     * @return the row, or null when the default applies
     */
    public static PosSetting findByKey(String key) {
        return find("settingKey", key).firstResult();
    }
}
