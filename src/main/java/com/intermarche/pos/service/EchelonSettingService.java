package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.EchelonLevel;
import com.intermarche.pos.domain.setting.EchelonSetting;
import com.intermarche.pos.domain.store.Enseigne;
import com.intermarche.pos.domain.store.Pdv;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The ECHELON INHERITANCE engine (BO-02-05-04, BO-03-12-07): given a point de
 * vente, it resolves the back-office parameters it inherits by walking the
 * organisation tree from the bottom up — the PDV's own value, then its
 * enseigne's default, then its country's — the more specific echelon winning.
 * <p>
 * It knows nothing of a node's own local overrides: those live in
 * {@link com.intermarche.pos.domain.setting.PosSetting} and are layered on top by
 * {@link PosSettingsService}, which is exactly why a local override survives a
 * fresh resolution of the echelon defaults (they never share a row). This
 * service is the "central defaults" half; {@code PosSettingsService} is the
 * "node override wins" half.
 * <p>
 * Lives in {@code pos.service} because it serves another service
 * ({@link PosSettingsService}) and a back-office screen, not a single UI area.
 */
@ApplicationScoped
public class EchelonSettingService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EchelonSettingService.class);

    /**
     * Resolves every inherited parameter of a point de vente into a single
     * key-to-value map, the more specific echelon overriding the less specific
     * (COUNTRY overlaid by ENSEIGNE overlaid by PDV). Node-local overrides are
     * NOT included — that layer is {@link PosSettingsService}'s.
     *
     * @param pdvNumber the point-of-vente number, or null on a node with none
     * @return the inherited values, empty when the PDV is unknown or unattached
     */
    public Map<String, String> resolveForPdv(String pdvNumber) {
        LOGGER.info("Entering method resolveForPdv with pdvNumber: " + pdvNumber);
        LOGGER.info("Exiting method resolveForPdv");
        return resolveForPdv(pdvNumber, LocalDate.now());
    }

    /**
     * Resolves every inherited parameter of a point de vente AS OF a given day,
     * so a value whose effect date is still in the future is ignored until the
     * day comes (BO-03-12-03/04). The public entry uses today; this overload is
     * the testable, deterministic core.
     *
     * @param pdvNumber the point-of-vente number, or null on a node with none
     * @param asOf the reference day used to gate the effect date
     * @return the inherited values in effect that day, empty when the PDV is
     *         unknown or unattached
     */
    Map<String, String> resolveForPdv(String pdvNumber, LocalDate asOf) {
        Map<String, String> merged = new HashMap<>();
        if (pdvNumber == null) {
            return merged;
        }
        Pdv pdv = Pdv.findByNumber(pdvNumber);
        if (pdv == null) {
            return merged;
        }
        String enseigneCode = pdv.enseigneCode;
        String countryCode = null;
        if (enseigneCode != null) {
            Enseigne enseigne = Enseigne.findByCode(enseigneCode);
            if (enseigne != null) {
                countryCode = enseigne.countryCode;
            }
        }
        if (countryCode != null) {
            overlay(merged, EchelonLevel.COUNTRY, countryCode, asOf);
        }
        if (enseigneCode != null) {
            overlay(merged, EchelonLevel.ENSEIGNE, enseigneCode, asOf);
        }
        overlay(merged, EchelonLevel.PDV, pdvNumber, asOf);
        return merged;
    }

    /**
     * Resolves one inherited key for a point de vente.
     *
     * @param pdvNumber the point-of-vente number, or null
     * @param key the catalog key
     * @return the inherited value, empty when no echelon posed it
     */
    public Optional<String> resolve(String pdvNumber, String key) {
        LOGGER.info("Entering method resolve with pdvNumber: " + pdvNumber + ", key: " + key);
        LOGGER.info("Exiting method resolve");
        return Optional.ofNullable(resolveForPdv(pdvNumber).get(key));
    }

    /**
     * Overlays the rows posed at one echelon onto the accumulator, each key
     * overwriting whatever a less specific echelon left there.
     *
     * @param merged the accumulator being built bottom-agnostic then overlaid
     * @param level the echelon level to overlay
     * @param code the echelon code at that level
     * @param asOf the reference day: a row dated after it is not yet in effect
     */
    private void overlay(Map<String, String> merged, EchelonLevel level, String code, LocalDate asOf) {
        for (EchelonSetting row : EchelonSetting.listForEchelon(level, code)) {
            if (row.effectiveDate == null || !row.effectiveDate.isAfter(asOf)) {
                merged.put(row.settingKey, row.settingValue);
            }
        }
    }

    /**
     * Poses (upserts) a value for a key at an echelon — the admin save path.
     *
     * @param level the echelon level
     * @param echelonCode the echelon code at that level
     * @param key the catalog key
     * @param value the value to store
     */
    @Transactional
    public void set(EchelonLevel level, String echelonCode, String key, String value) {
        LOGGER.info("Entering method set with level: " + level + ", echelonCode: " + echelonCode + ", key: " + key + ", value: " + value);
        set(level, echelonCode, key, value, null);
        LOGGER.info("Exiting method set");
    }

    /**
     * Poses (upserts) a value for a key at an echelon WITH an effect date — the
     * admin save path when a change is scheduled for a future day
     * (BO-03-12-03/04). A null date means immediate effect.
     *
     * @param level the echelon level
     * @param echelonCode the echelon code at that level
     * @param key the catalog key
     * @param value the value to store
     * @param effectiveDate the day the value takes effect, or null for now
     */
    @Transactional
    public void set(EchelonLevel level, String echelonCode, String key, String value,
                    LocalDate effectiveDate) {
        LOGGER.info("Entering method set with level: " + level + ", echelonCode: " + echelonCode + ", key: " + key + ", value: " + value + ", effectiveDate: " + effectiveDate);
        EchelonSetting row = EchelonSetting.findValue(level, echelonCode, key);
        if (row == null) {
            row = new EchelonSetting();
            row.level = level;
            row.echelonCode = echelonCode;
            row.settingKey = key;
            row.settingValue = value;
            row.effectiveDate = effectiveDate;
            row.persist();
        } else {
            row.settingValue = value;
            row.effectiveDate = effectiveDate;
        }
        LOGGER.info("Exiting method set");
    }

    /**
     * Removes a value posed at an echelon, so it reverts to inheriting the
     * level above — the admin "clear override" path.
     *
     * @param level the echelon level
     * @param echelonCode the echelon code at that level
     * @param key the catalog key
     * @return true when a row existed and was removed, false when there was none
     */
    @Transactional
    public boolean clear(EchelonLevel level, String echelonCode, String key) {
        LOGGER.info("Entering method clear with level: " + level + ", echelonCode: " + echelonCode + ", key: " + key);
        EchelonSetting row = EchelonSetting.findValue(level, echelonCode, key);
        if (row == null) {
            LOGGER.info("Exiting method clear");
            return false;
        }
        row.delete();
        LOGGER.info("Exiting method clear");
        return true;
    }

    /**
     * Migrates every PDV-level echelon setting from an old point-de-vente number
     * to a new one (BO-02-05-01): renaming a PDV changes its five-digit number,
     * and the rows posed at the PDV echelon are keyed by that number — without
     * this migration they would be orphaned, silently reverting the PDV to its
     * enseigne defaults. The new number is guaranteed free of PDV rows by the
     * caller (the rename is refused when the target number already exists), so
     * no unique-key collision can occur.
     *
     * @param oldNumber the PDV's former number
     * @param newNumber the PDV's new number
     * @return the number of echelon settings re-keyed
     */
    @Transactional
    public int migratePdv(String oldNumber, String newNumber) {
        LOGGER.info("Entering method migratePdv with oldNumber: " + oldNumber + ", newNumber: " + newNumber);
        int migrated = 0;
        for (EchelonSetting row : EchelonSetting.listForEchelon(EchelonLevel.PDV, oldNumber)) {
            row.echelonCode = newNumber;
            migrated++;
        }
        LOGGER.info("Exiting method migratePdv");
        return migrated;
    }

    /**
     * Lists the PDVs of an enseigne that carry their OWN value for a key — the
     * central view of which points de vente personalised a parameter away from
     * the enseigne default (BO-03-12-07).
     *
     * @param enseigneCode the enseigne code
     * @param key the catalog key
     * @return the personalised PDVs, ordered by number, never null
     */
    public List<Pdv> personalizedPdvs(String enseigneCode, String key) {
        LOGGER.info("Entering method personalizedPdvs with enseigneCode: " + enseigneCode + ", key: " + key);
        List<Pdv> pdvs = Pdv.listByEnseigne(enseigneCode);
        if (pdvs.isEmpty()) {
            LOGGER.info("Exiting method personalizedPdvs");
            return List.of();
        }
        Set<String> overridden = new HashSet<>();
        for (EchelonSetting row : EchelonSetting.listForKeyAtLevel(EchelonLevel.PDV, key)) {
            overridden.add(row.echelonCode);
        }
        List<Pdv> personalised = new ArrayList<>();
        for (Pdv pdv : pdvs) {
            if (overridden.contains(pdv.pdvNumber)) {
                personalised.add(pdv);
            }
        }
        LOGGER.info("Exiting method personalizedPdvs");
        return personalised;
    }
}
