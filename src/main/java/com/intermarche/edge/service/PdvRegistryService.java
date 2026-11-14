package com.intermarche.edge.service;

import com.intermarche.edge.domain.store.CheckoutIsland;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PdvHistory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * The REGISTRY of the points of sale (BO-02-05-01, -02, -03, -05): the one
 * place a point of sale is created, renumbered, moved to another enseigne or
 * attached to another adhérent, whether by the echelons screen or by the
 * import of the organisation.
 *
 * <p>One place because the rules are the same whatever the road: the number
 * is exactly five digits and unique across every country and enseigne; a
 * number a point of sale has LEFT is never handed to another one, the data
 * produced under it staying attached to it; a renumbered point of sale keeps
 * its echelon parameters and its checkout configuration, while the data it
 * produced stay filed under the number of their day; and every change of
 * number, enseigne or adhérent is written to the history, so the past stays
 * readable.
 *
 * <p>General service: it serves a screen and an importer alike.
 */
@ApplicationScoped
public class PdvRegistryService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PdvRegistryService.class);

    /** Carries the echelon parameters of a renumbered point of sale over. */
    @Inject
    EchelonSettingService echelonSettings;

    /**
     * The outcome of a write: whether it was done, the French notice, and the
     * point of sale written.
     *
     * @param ok whether the write was done
     * @param message the operator-facing notice (French)
     * @param pdv the point of sale written, null when refused
     * @param created whether the point of sale was created by this write
     */
    public record Result(boolean ok, String message, Pdv pdv, boolean created) {
    }

    /**
     * Creates or updates a point of sale, renumbering it when an original
     * number other than the new one is given.
     *
     * @param number the five-digit number
     * @param original the number of the point of sale to renumber, or null/blank
     * @param name the display name, possibly empty
     * @param enseigneCode the enseigne it is attached to, or null
     * @param adherentCode the adhérent operating it, or null
     * @param active whether it is active
     * @param source by which road the change comes
     * @return the outcome
     */
    @Transactional
    public Result save(String number, String original, String name, String enseigneCode,
                       String adherentCode, boolean active, PdvHistory.Source source) {
        LOGGER.info("Entering method save with number: " + number + ", original: " + original);
        if (!Pdv.isValidNumber(number)) {
            LOGGER.info("Exiting method save: invalid number");
            return refused("Le numéro de PDV doit comporter exactement 5 chiffres.");
        }
        if (enseigneCode != null && Enseigne.findByCode(enseigneCode) == null) {
            LOGGER.info("Exiting method save: unknown enseigne");
            return refused("Enseigne inconnue : " + enseigneCode);
        }
        LocalDateTime now = LocalDateTime.now();
        Pdv pdv;
        boolean created = false;
        if (original != null && !original.isBlank() && !original.equals(number)) {
            pdv = Pdv.findByNumber(original);
            if (pdv == null) {
                LOGGER.info("Exiting method save: nothing to renumber");
                return refused("PDV à renommer introuvable.");
            }
            if (Pdv.findByNumber(number) != null) {
                LOGGER.info("Exiting method save: number taken");
                return refused("Ce numéro de PDV est déjà utilisé.");
            }
            if (heldByAnother(number, pdv.id)) {
                LOGGER.info("Exiting method save: former number of another PDV");
                return refused(formerNumberRefusal(number));
            }
            renumber(pdv, original, number, source, now);
        } else {
            pdv = Pdv.findByNumber(number);
            if (pdv == null) {
                if (heldByAnother(number, null)) {
                    LOGGER.info("Exiting method save: former number of another PDV");
                    return refused(formerNumberRefusal(number));
                }
                pdv = new Pdv();
                pdv.pdvNumber = number;
                created = true;
            }
        }
        if (!created) {
            record(pdv, PdvHistory.Kind.ENSEIGNE, pdv.enseigneCode, enseigneCode, source, now);
            record(pdv, PdvHistory.Kind.ADHERENT, pdv.adherentCode, adherentCode, source, now);
        }
        pdv.name = name == null ? "" : name;
        pdv.enseigneCode = enseigneCode;
        pdv.adherentCode = adherentCode;
        pdv.active = active;
        if (created) {
            // Persisted once filled: the name column refuses null at the insert.
            pdv.persist();
        }
        LOGGER.info("Exiting method save");
        return new Result(true, "Point de vente enregistré.", pdv, created);
    }

    /**
     * Creates or updates a country by its code, the import road
     * (BO-02-05-03): a blank name keeps the one held, a null language keeps
     * the one held.
     *
     * @param code the country code
     * @param name the display name, or null/blank to keep the one held
     * @param language the default language, or null to keep the one held
     * @return the country
     */
    @Transactional
    public Country upsertCountry(String code, String name, String language) {
        LOGGER.info("Entering method upsertCountry with code: " + code);
        Country country = Country.findByCode(code);
        boolean created = country == null;
        if (created) {
            country = new Country();
            country.code = code;
            country.name = code;
        }
        if (name != null && !name.isBlank()) {
            country.name = name;
        }
        if (language != null) {
            country.defaultLanguage = language.isBlank() ? null : language;
        }
        if (created) {
            country.persist();
        }
        LOGGER.info("Exiting method upsertCountry");
        return country;
    }

    /**
     * Creates or updates an enseigne by its code and attaches it to a country,
     * the import road (BO-02-05-03).
     *
     * @param code the enseigne code
     * @param name the display name, or null/blank to keep the one held
     * @param countryCode the country it belongs to, or null to keep the one held
     * @param language the default language, or null to keep the one held
     * @return the enseigne
     */
    @Transactional
    public Enseigne upsertEnseigne(String code, String name, String countryCode, String language) {
        LOGGER.info("Entering method upsertEnseigne with code: " + code);
        Enseigne enseigne = Enseigne.findByCode(code);
        boolean created = enseigne == null;
        if (created) {
            enseigne = new Enseigne();
            enseigne.code = code;
            enseigne.name = code;
        }
        if (name != null && !name.isBlank()) {
            enseigne.name = name;
        }
        if (countryCode != null) {
            enseigne.countryCode = countryCode;
        }
        if (language != null) {
            enseigne.defaultLanguage = language.isBlank() ? null : language;
        }
        if (created) {
            enseigne.persist();
        }
        LOGGER.info("Exiting method upsertEnseigne");
        return enseigne;
    }

    /**
     * Renumbers a point of sale: its number, the echelon parameters posed at
     * it and the islands and checkout points configured under it follow; the
     * data it produced do not, and the history keeps the former number.
     *
     * @param pdv the point of sale
     * @param original its current number
     * @param number its new number
     * @param source by which road the change comes
     * @param now the moment of the change
     */
    private void renumber(Pdv pdv, String original, String number, PdvHistory.Source source,
                          LocalDateTime now) {
        record(pdv, PdvHistory.Kind.NUMBER, original, number, source, now);
        pdv.pdvNumber = number;
        echelonSettings.migratePdv(original, number);
        CheckoutIsland.update("pdvNumber = ?1 where pdvNumber = ?2", number, original);
        CheckoutPoint.update("pdvNumber = ?1 where pdvNumber = ?2", number, original);
    }

    /**
     * Writes one change to the history, when the value really changes.
     *
     * @param pdv the point of sale
     * @param kind what changes
     * @param before the value before, possibly null
     * @param after the value after, possibly null
     * @param source by which road the change comes
     * @param now the moment of the change
     */
    private void record(Pdv pdv, PdvHistory.Kind kind, String before, String after,
                        PdvHistory.Source source, LocalDateTime now) {
        if (Objects.equals(before, after)) {
            return;
        }
        PdvHistory change = new PdvHistory();
        change.pdvId = pdv.id;
        change.kind = kind;
        change.oldValue = before;
        change.newValue = after;
        change.changedAt = now;
        change.source = source;
        change.persist();
        LOGGER.infof("PDV %s : %s %s -> %s", pdv.pdvNumber, kind, before, after);
    }

    /**
     * Tells whether a number was left by a point of sale other than the given
     * one.
     *
     * @param number the number
     * @param pdvId the point of sale allowed to take it back, or null
     * @return true when another point of sale formerly carried it
     */
    private boolean heldByAnother(String number, Long pdvId) {
        Long holder = PdvHistory.formerHolderOf(number);
        return holder != null && !holder.equals(pdvId);
    }

    /**
     * The refusal of a former number.
     *
     * @param number the number refused
     * @return the notice
     */
    private String formerNumberRefusal(String number) {
        return "Le numéro " + number + " a été porté par un autre point de vente :"
                + " les données produites sous ce numéro lui restent rattachées.";
    }

    /**
     * Builds a refusal.
     *
     * @param message the notice
     * @return the refused outcome
     */
    private Result refused(String message) {
        return new Result(false, message, null, false);
    }
}
