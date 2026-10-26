package com.intermarche.edge.service;

import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.EchelonSetting;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
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
 * {@link com.intermarche.edge.domain.setting.PosSetting} and are layered on top by
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

    /** The parameter mirrored onto the checkout point's own menu column (BO-03-10-09). */
    static final String MENU_KEY = "menu.code";

    /** The catalog key the reference language of an echelon feeds (BO-02-05-04). */
    static final String LANGUAGE_KEY = "language.system";

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
        return resolveTraced(pdvNumber, asOf, null);
    }

    /**
     * Resolves the inherited parameters of a point of sale, noting where each
     * value comes from when asked to.
     *
     * <p>The LANGUAGE is inherited like the rest (BO-02-05-04): the reference
     * language of the country, then of the enseigne, is the PDV's system
     * language unless an echelon poses {@code language.system} itself, the more
     * specific winning — a PDV moved to a Dutch-speaking enseigne speaks Dutch
     * from its next pull (BO-02-05-02).
     *
     * @param pdvNumber the point-of-vente number, or null
     * @param asOf the reference day used to gate the effect date
     * @param origins where to note the echelon each value comes from, or null
     * @return the inherited values in effect that day
     */
    private Map<String, String> resolveTraced(String pdvNumber, LocalDate asOf, Map<String, String> origins) {
        Map<String, String> merged = new HashMap<>();
        if (pdvNumber == null) {
            return merged;
        }
        Pdv pdv = Pdv.findByNumber(pdvNumber);
        if (pdv == null) {
            return merged;
        }
        String enseigneCode = pdv.enseigneCode;
        Enseigne enseigne = enseigneCode == null ? null : Enseigne.findByCode(enseigneCode);
        String countryCode = enseigne == null ? null : enseigne.countryCode;
        if (countryCode != null) {
            Country country = Country.findByCode(countryCode);
            defaultLanguage(merged, country == null ? null : country.defaultLanguage,
                    "COUNTRY:" + countryCode, origins);
            overlay(merged, EchelonLevel.COUNTRY, countryCode, asOf, origins);
        }
        if (enseigneCode != null) {
            defaultLanguage(merged, enseigne == null ? null : enseigne.defaultLanguage,
                    "ENSEIGNE:" + enseigneCode, origins);
            overlay(merged, EchelonLevel.ENSEIGNE, enseigneCode, asOf, origins);
        }
        overlay(merged, EchelonLevel.PDV, pdvNumber, asOf, origins);
        return merged;
    }

    /**
     * Poses the reference language of an echelon as the system language
     * (BO-02-05-04), when the echelon carries one.
     *
     * @param merged the accumulator
     * @param language the echelon's reference language, possibly null
     * @param origin the echelon, as a scope
     * @param origins where to note the origin, or null
     */
    private void defaultLanguage(Map<String, String> merged, String language, String origin,
                                 Map<String, String> origins) {
        if (language == null || language.isBlank()) {
            return;
        }
        merged.put(LANGUAGE_KEY, language.trim());
        if (origins != null) {
            origins.put(LANGUAGE_KEY, origin);
        }
    }

    /**
     * One parameter a point of sale inherits, with the echelon it comes from
     * (BO-02-05-04).
     *
     * @param key the catalog key
     * @param value the value in effect
     * @param level the level of the echelon it is posed at (COUNTRY, ENSEIGNE, PDV)
     * @param code the code of that echelon
     */
    public record Inherited(String key, String value, String level, String code) {
    }

    /**
     * Lists what a point of sale inherits today and from where — the view of
     * the inheritance the echelons screen shows (BO-02-05-04).
     *
     * @param pdvNumber the point-of-vente number, possibly null
     * @return the inherited parameters, by key, empty when the PDV is unknown
     */
    public List<Inherited> inheritedFor(String pdvNumber) {
        LOGGER.info("Entering method inheritedFor with pdvNumber: " + pdvNumber);
        Map<String, String> origins = new HashMap<>();
        Map<String, String> values = resolveTraced(pdvNumber, LocalDate.now(), origins);
        List<Inherited> inherited = new ArrayList<>();
        for (String key : new java.util.TreeSet<>(values.keySet())) {
            String origin = origins.get(key);
            int colon = origin.indexOf(':');
            inherited.add(new Inherited(key, values.get(key), origin.substring(0, colon),
                    origin.substring(colon + 1)));
        }
        LOGGER.info("Exiting method inheritedFor");
        return inherited;
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
     * Resolves every inherited parameter of a single TERMINAL, walking the full
     * five-level chain COUNTRY &rarr; ENSEIGNE &rarr; PDV &rarr; ISLAND &rarr;
     * CHECKOUT_POINT, the more specific echelon overriding the less specific.
     *
     * <p>This is the geste the fifteen partial lines ask for: a value posed on
     * one terminal wins over its island's, which wins over the PDV's, and so on
     * down to the catalog default. The terminal's island is read from its
     * {@link CheckoutPoint}; a terminal with no checkout point carries no island
     * layer but still takes a value posed directly on its identifier at the
     * CHECKOUT_POINT level. Node-local overrides are NOT included — that layer is
     * {@link PosSettingsService}'s.
     *
     * @param pdvNumber the node's point-of-vente number, or null
     * @param terminalId the terminal identifier, or null for the PDV resolution alone
     * @return the values in effect for that terminal today, never null
     */
    public Map<String, String> resolveForTerminal(String pdvNumber, String terminalId) {
        LOGGER.info("Entering method resolveForTerminal with pdvNumber: " + pdvNumber + ", terminalId: " + terminalId);
        LOGGER.info("Exiting method resolveForTerminal");
        return resolveForTerminal(pdvNumber, terminalId, LocalDate.now());
    }

    /**
     * Resolves every inherited parameter of a terminal AS OF a given day — the
     * testable, deterministic core of {@link #resolveForTerminal(String, String)}.
     * A value whose effect date is still in the future is ignored until the day
     * comes (BO-03-12-03/04).
     *
     * @param pdvNumber the node's point-of-vente number, or null
     * @param terminalId the terminal identifier, or null for the PDV resolution alone
     * @param asOf the reference day used to gate the effect date
     * @return the values in effect for that terminal that day, never null
     */
    Map<String, String> resolveForTerminal(String pdvNumber, String terminalId, LocalDate asOf) {
        Map<String, String> merged = resolveForPdv(pdvNumber, asOf);
        if (terminalId == null) {
            return merged;
        }
        CheckoutPoint point = CheckoutPoint.findByTerminal(terminalId);
        if (point != null && point.islandCode != null) {
            overlay(merged, EchelonLevel.ISLAND, point.islandCode, asOf, null);
        }
        overlay(merged, EchelonLevel.CHECKOUT_POINT, terminalId, asOf, null);
        return merged;
    }

    /**
     * Overlays the rows posed at one echelon onto the accumulator, each key
     * overwriting whatever a less specific echelon left there. A row gives the
     * value in force that day: its pending value once the date has come, its
     * current value before; a row with no value in force leaves the inherited
     * one in place.
     *
     * @param merged the accumulator being built bottom-agnostic then overlaid
     * @param level the echelon level to overlay
     * @param code the echelon code at that level
     * @param asOf the reference day: a change programmed after it is not yet in effect
     * @param origins where to note the echelon of each value, or null
     */
    private void overlay(Map<String, String> merged, EchelonLevel level, String code, LocalDate asOf,
                         Map<String, String> origins) {
        for (EchelonSetting row : EchelonSetting.listForEchelon(level, code)) {
            String value = row.valueAsOf(asOf);
            if (value != null) {
                merged.put(row.settingKey, value);
                if (origins != null) {
                    origins.put(row.settingKey, level.name() + ":" + code);
                }
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
     * (BO-03-12-03/04). A null date, or a day already come, means immediate
     * effect.
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
        set(level, echelonCode, key, value, effectiveDate, LocalDate.now());
        LOGGER.info("Exiting method set");
    }

    /**
     * Poses a value AS OF a given day — the deterministic core of the save
     * path. An immediate value replaces the current one and leaves a programmed
     * change in place; a value dated after that day becomes the PENDING value
     * next to the current one, which keeps applying until the date. A dated
     * value equal to the current one programs nothing (a whole form re-posted
     * with a date leaves the untouched keys, and their programmed changes, as
     * they are).
     *
     * @param level the echelon level
     * @param echelonCode the echelon code at that level
     * @param key the catalog key
     * @param value the value to store
     * @param effectiveDate the day the value takes effect, or null for now
     * @param today the reference day deciding whether the date is to come
     */
    void set(EchelonLevel level, String echelonCode, String key, String value,
             LocalDate effectiveDate, LocalDate today) {
        boolean dated = effectiveDate != null && effectiveDate.isAfter(today);
        EchelonSetting row = EchelonSetting.findValue(level, echelonCode, key);
        if (row == null) {
            row = new EchelonSetting();
            row.level = level;
            row.echelonCode = echelonCode;
            row.settingKey = key;
            if (dated) {
                row.pendingValue = value;
                row.effectiveDate = effectiveDate;
            } else {
                row.settingValue = value;
            }
            row.persist();
        } else if (!dated) {
            row.settingValue = value;
        } else if (!value.equals(row.settingValue)) {
            row.pendingValue = value;
            row.effectiveDate = effectiveDate;
        }
    }

    /**
     * Removes the CURRENT value posed at an echelon, so it reverts to inheriting
     * the level above — the admin "clear override" path. A change programmed on
     * the key is kept: it still applies on its date.
     *
     * @param level the echelon level
     * @param echelonCode the echelon code at that level
     * @param key the catalog key
     * @return true when a current value existed and was removed, false when there was none
     */
    @Transactional
    public boolean clear(EchelonLevel level, String echelonCode, String key) {
        LOGGER.info("Entering method clear with level: " + level + ", echelonCode: " + echelonCode + ", key: " + key);
        EchelonSetting row = EchelonSetting.findValue(level, echelonCode, key);
        if (row == null || row.settingValue == null) {
            LOGGER.info("Exiting method clear");
            return false;
        }
        if (row.isProgrammed()) {
            row.settingValue = null;
        } else {
            row.delete();
        }
        LOGGER.info("Exiting method clear");
        return true;
    }

    /**
     * Removes the current value posed at an echelon on a given day: at once
     * when the date is null or already come, otherwise PROGRAMMED for that day
     * (the current value keeps applying until then).
     *
     * @param level the echelon level
     * @param echelonCode the echelon code at that level
     * @param key the catalog key
     * @param effectiveDate the day the removal takes effect, or null for now
     * @return true when a current value existed, false when there was none
     */
    @Transactional
    public boolean clear(EchelonLevel level, String echelonCode, String key, LocalDate effectiveDate) {
        LOGGER.info("Entering method clear with level: " + level + ", echelonCode: " + echelonCode + ", key: " + key + ", effectiveDate: " + effectiveDate);
        LOGGER.info("Exiting method clear");
        return clear(level, echelonCode, key, effectiveDate, LocalDate.now());
    }

    /**
     * Removes the current value AS OF a given day — the deterministic core of
     * {@link #clear(EchelonLevel, String, String, LocalDate)}.
     *
     * @param level the echelon level
     * @param echelonCode the echelon code at that level
     * @param key the catalog key
     * @param effectiveDate the day the removal takes effect, or null for now
     * @param today the reference day deciding whether the date is to come
     * @return true when a current value existed, false when there was none
     */
    boolean clear(EchelonLevel level, String echelonCode, String key, LocalDate effectiveDate,
                  LocalDate today) {
        if (effectiveDate == null || !effectiveDate.isAfter(today)) {
            return clear(level, echelonCode, key);
        }
        EchelonSetting row = EchelonSetting.findValue(level, echelonCode, key);
        if (row == null || row.settingValue == null) {
            return false;
        }
        row.pendingValue = null;
        row.effectiveDate = effectiveDate;
        return true;
    }

    /**
     * Lists the changes programmed after today, the nearest date first
     * (BO-03-12-03/04): each row shows its current and its pending value.
     *
     * @return the programmed rows, never null
     */
    public List<EchelonSetting> programmed() {
        return EchelonSetting.listProgrammed(LocalDate.now());
    }

    /**
     * Folds every change whose date has come into its current value — run once
     * a day by the store node, so the tables say what the resolution already
     * applies.
     *
     * @param today the reference day
     * @return the number of rows folded
     */
    @Transactional
    public int promoteDue(LocalDate today) {
        LOGGER.info("Entering method promoteDue with today: " + today);
        int folded = 0;
        for (EchelonSetting row : EchelonSetting.listDue(today)) {
            promote(row);
            folded++;
        }
        LOGGER.info("Exiting method promoteDue");
        return folded;
    }

    /**
     * Brings a programmed change into effect now: its pending value becomes
     * the current one.
     *
     * @param id the echelon value id
     * @return false when there is no such row or nothing is programmed on it
     */
    @Transactional
    public boolean advance(Long id) {
        LOGGER.info("Entering method advance with id: " + id);
        EchelonSetting row = EchelonSetting.findById(id);
        if (row == null || !row.isProgrammed()) {
            LOGGER.info("Exiting method advance");
            return false;
        }
        promote(row);
        LOGGER.info("Exiting method advance");
        return true;
    }

    /**
     * Cancels a programmed change: only the pending value goes, the current
     * value stays in force (and a row holding nothing else disappears, so the
     * echelon inherits as before).
     *
     * @param id the echelon value id
     * @return false when there is no such row or nothing is programmed on it
     */
    @Transactional
    public boolean cancel(Long id) {
        LOGGER.info("Entering method cancel with id: " + id);
        EchelonSetting row = EchelonSetting.findById(id);
        if (row == null || !row.isProgrammed()) {
            LOGGER.info("Exiting method cancel");
            return false;
        }
        if (row.settingValue == null) {
            row.delete();
        } else {
            row.pendingValue = null;
            row.effectiveDate = null;
        }
        LOGGER.info("Exiting method cancel");
        return true;
    }

    /**
     * Makes the pending value of a row its current value; a programmed removal
     * deletes the row. The menu of a checkout point is also written on the
     * point's own column, which the register reads first (BO-03-10-09).
     *
     * @param row the programmed row
     */
    private void promote(EchelonSetting row) {
        String value = row.pendingValue;
        if (value == null) {
            row.delete();
        } else {
            row.settingValue = value;
            row.pendingValue = null;
            row.effectiveDate = null;
        }
        if (row.level == EchelonLevel.CHECKOUT_POINT && MENU_KEY.equals(row.settingKey)) {
            CheckoutPoint point = CheckoutPoint.findByTerminal(row.echelonCode);
            if (point != null) {
                point.menuTemplateCode = value;
            }
        }
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
