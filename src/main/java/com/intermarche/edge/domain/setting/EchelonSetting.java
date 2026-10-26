package com.intermarche.edge.domain.setting;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.List;
import java.util.Objects;
import com.intermarche.edge.domain.BaseEntity;

/**
 * One back-office parameter posed at an echelon (BO-02-05-04, BO-03-12-07):
 * the value of a {@link com.intermarche.edge.service.PosSettingsService} catalog
 * key set on a {@link EchelonLevel#COUNTRY}, a {@link EchelonLevel#ENSEIGNE} or
 * a {@link EchelonLevel#PDV}. It is the echelon counterpart of the node-local
 * {@link PosSetting}: same key space, but scoped to an organisation echelon
 * instead of to a single node.
 * <p>
 * Held on the CENTRAL node. A node resolves a key from the bottom up — its own
 * local {@link PosSetting} override first, then the PDV, ENSEIGNE and COUNTRY
 * rows in that order — so a value posed at the enseigne reaches every PDV of
 * the enseigne unless one overrides it, and a local override survives because
 * it lives in a different table the echelon distribution never touches.
 * <p>
 * The triplet (level, echelonCode, settingKey) is unique: one row per key per
 * echelon. Absence of a row means "inherit from the level above".
 * <p>
 * A row carries up to TWO values (BO-03-12-03, BO-03-10): the CURRENT one
 * ({@link #settingValue}, null when the echelon only programs a change) and a
 * PENDING one programmed for a later day ({@link #pendingValue} from
 * {@link #effectiveDate}). Until that day the current value keeps applying —
 * or, without one, the inherited value — and from that day the pending value
 * does: a programmed change never replaces the value in force before its date.
 * A null pending value with a date programs the removal of the current value.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "echelon_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_echelon_setting",
                columnNames = {"echelon_level", "echelon_code", "setting_key"}),
        indexes = {
                @Index(name = "idx_echelon_setting_key", columnList = "setting_key")
        }
)
@Cacheable
public class EchelonSetting extends BaseEntity {

    /** The echelon level this value is posed at. */
    @Enumerated(EnumType.STRING)
    @Column(name = "echelon_level", nullable = false, length = 16)
    public EchelonLevel level;

    /**
     * The code of the echelon at that level: a {@link Country#code}, an
     * {@link Enseigne#code} or a {@link Pdv#pdvNumber}.
     */
    @Column(name = "echelon_code", nullable = false, length = 32)
    public String echelonCode;

    /** The catalog key (e.g. "display.show-ean"), same key space as {@link PosSetting}. */
    @Column(name = "setting_key", nullable = false, length = 64)
    public String settingKey;

    /**
     * The CURRENT value, as text, typed by the catalog entry: the one in force
     * until a programmed change comes into effect. Null when the echelon only
     * carries a programmed value, which then inherits until its date.
     */
    @Column(name = "setting_value", length = 4000)
    public String settingValue;

    /**
     * The PENDING value programmed for {@link #effectiveDate} (BO-03-12-03/04),
     * or null: with a date, a null pending value programs the removal of the
     * current value (the echelon inherits again from that day).
     */
    @Column(name = "pending_value", length = 4000)
    public String pendingValue;

    /**
     * The day the pending value takes effect (BO-03-12-03/04): a programmed
     * change is distributed at once, but the resolution keeps the current value
     * until the day comes, so it applies on its own on every node without a
     * central scheduler. Null means nothing is programmed.
     * <p>
     * This is CENTRAL data, part of the ECHELON_SETTINGS pull — it is not a
     * piece of node-local operational state, so it travels with the value.
     */
    @Column(name = "effective_date")
    public java.time.LocalDate effectiveDate;

    /**
     * Tells whether a change is programmed on this row.
     *
     * @return true when a pending value (or removal) waits for its date
     */
    public boolean isProgrammed() {
        return effectiveDate != null;
    }

    /**
     * Returns the value in force on a given day: the pending value once its
     * date has come, the current value before it.
     *
     * @param day the reference day
     * @return the value in force that day, or null when the echelon inherits
     */
    public String valueAsOf(java.time.LocalDate day) {
        if (effectiveDate != null && !effectiveDate.isAfter(day)) {
            return pendingValue;
        }
        return settingValue;
    }

    /**
     * Finds the value posed for a key at a precise echelon.
     *
     * @param level the echelon level
     * @param echelonCode the echelon code at that level
     * @param key the catalog key
     * @return the row, or null when that echelon does not override the key
     */
    public static EchelonSetting findValue(EchelonLevel level, String echelonCode, String key) {
        return find("level = ?1 and echelonCode = ?2 and settingKey = ?3",
                level, echelonCode, key).firstResult();
    }

    /**
     * Lists every value posed at one echelon, ordered by key.
     *
     * @param level the echelon level
     * @param echelonCode the echelon code at that level
     * @return the rows of that echelon, never null
     */
    public static List<EchelonSetting> listForEchelon(EchelonLevel level, String echelonCode) {
        return list("level = ?1 and echelonCode = ?2 order by settingKey", level, echelonCode);
    }

    /**
     * Lists every value posed for a key at a given level across all echelons —
     * the raw material of the "which echelons personalised this key" view
     * (BO-03-12-07).
     *
     * @param level the echelon level
     * @param key the catalog key
     * @return the rows carrying that key at that level, never null
     */
    public static List<EchelonSetting> listForKeyAtLevel(EchelonLevel level, String key) {
        return list("level = ?1 and settingKey = ?2 order by echelonCode", level, key);
    }

    /**
     * Lists the rows whose programmed change has come into effect on a day —
     * the ones to fold into their current value.
     *
     * @param day the reference day
     * @return the due rows, never null
     */
    public static List<EchelonSetting> listDue(java.time.LocalDate day) {
        return list("effectiveDate <= ?1", day);
    }

    /**
     * Lists the rows carrying a change programmed after a day, the nearest
     * date first (BO-03-12-03/04, the "Paramètres datés").
     *
     * @param day the reference day
     * @return the programmed rows, never null
     */
    public static List<EchelonSetting> listProgrammed(java.time.LocalDate day) {
        return list("effectiveDate > ?1 order by effectiveDate, echelonCode, settingKey", day);
    }

    /**
     * Hashes the business fields for change detection.
     *
     * @return the checksum of the echelon setting
     */
    @Override
    public int getChecksum() {
        return Objects.hash(level, echelonCode, settingKey, settingValue, pendingValue, effectiveDate);
    }
}
