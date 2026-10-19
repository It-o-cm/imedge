package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.payment.Currency;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The CURRENCY administration screen ({@code /admin/currencies}) — the rates a
 * foreign or legacy currency is converted to euros with, administered on the
 * store node and distributed to the registers through the {@code CURRENCIES}
 * referential pull.
 *
 * <p>This is what makes the franc rate administrable rather than hard-coded
 * (EXP-22, LC-02-05-02): a currency {@code FRF} with {@code euroPerUnit}
 * {@code 6.55957} entered here descends to every register, where a barcode range
 * priced in francs reads it to convert. Modelled on {@link AdminVatRateResource}
 * and {@link AdminTenderResource} — the same admin cycle: a POST upserts by code,
 * returns a 303 with a one-shot notice, and the register receives only the
 * distributed rows.
 *
 * <p>Held assumption (named as such): the barcode-range franc conversion reads a
 * {@code Currency} row keyed {@code FRF}; the caisse side that performs that read
 * is the mirror campaign's work and is signalled, not built here.
 */
@Path("/admin/currencies")
public class AdminCurrencyResource {

    /** Who is signed in, named in the rate history (BO-03-02-40). */
    @Inject
    io.quarkus.security.identity.SecurityIdentity identity;

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminCurrencyResource.class);

    /** The redirect target, equal to the resource path. */
    private static final String PAGE = "/admin/currencies";

    /** The currencies page template. */
    @Inject
    @Location("admin-currencies")
    Template adminCurrencies;

    /**
     * Shows the currencies page.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Transactional
    public TemplateInstance currenciesPage(@QueryParam("notice") String notice,
                                           @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method currenciesPage with notice: " + notice + ", noticeOk: " + noticeOk);
        List<Currency> currencies = Currency.list("order by displayOrder, code");
        // BO-03-02-40: the history of each currency, newest first, by code —
        // the page shows it under the currency it belongs to.
        java.util.Map<String, java.util.List<
                com.intermarche.edge.domain.payment.CurrencyRateHistory>> history =
                new java.util.LinkedHashMap<>();
        for (Currency currency : currencies) {
            history.put(currency.code,
                    com.intermarche.edge.domain.payment.CurrencyRateHistory.listFor(currency.code));
        }
        LOGGER.info("Exiting method currenciesPage");
        return adminCurrencies.data("currencies", currencies)
                .data("history", history)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Upserts a currency by its code (EXP-22).
     *
     * @param form the posted form (code, label, symbol, euroPerUnit, displayOrder, active)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveCurrency(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveCurrency with form: " + form);
        String code = AdminForms.text(form, "code");
        if (code == null) {
            LOGGER.info("Exiting method saveCurrency");
            return AdminForms.redirect(PAGE, "Le code de la devise est obligatoire.", false);
        }
        String isoCode = code.toUpperCase();
        BigDecimal rate = AdminForms.amount(AdminForms.text(form, "euroPerUnit"));
        if (rate == null || rate.signum() <= 0) {
            LOGGER.info("Exiting method saveCurrency");
            return AdminForms.redirect(PAGE, "Le taux (euros pour une unité) doit être un nombre strictement positif.", false);
        }
        Currency existing = Currency.find("code", isoCode).firstResult();
        Currency currency = existing;
        if (currency == null) {
            currency = new Currency();
            currency.code = isoCode;
            currency.persist();
        }
        currency.label = AdminForms.text(form, "label");
        currency.symbol = AdminForms.text(form, "symbol");
        // BO-03-02-40: the history is written BEFORE the rate is replaced, and
        // only when it really changes — a history padded with rows that say the
        // same figure hides the three that do not.
        com.intermarche.edge.domain.payment.CurrencyRateHistory.record(
                isoCode, currency.euroPerUnit, rate, operatorName());
        currency.euroPerUnit = rate;
        // BO-03-02-39: the tender this currency's change is given in; empty = euros.
        currency.changeTenderCode = AdminForms.text(form, "changeTenderCode");
        currency.displayOrder = AdminForms.integer(AdminForms.text(form, "displayOrder"), 0);
        currency.active = AdminForms.checkbox(form, "active");
        LOGGER.info("Exiting method saveCurrency");
        return AdminForms.redirect(PAGE, existing == null ? "Devise créée." : "Devise enregistrée.", true);
    }

    /**
     * Deletes a currency by its code.
     *
     * @param form the posted form (code)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteCurrency(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteCurrency with form: " + form);
        String code = AdminForms.text(form, "code");
        Currency currency = code == null ? null : Currency.<Currency>find("code", code.toUpperCase()).firstResult();
        if (currency == null) {
            LOGGER.info("Exiting method deleteCurrency");
            return AdminForms.redirect(PAGE, "Devise inconnue.", false);
        }
        currency.delete();
        LOGGER.info("Exiting method deleteCurrency");
        return AdminForms.redirect(PAGE, "Devise supprimée.", true);
    }

    /**
     * Who is signed in, for the rate history (BO-03-02-40).
     *
     * <p>A name and not an identifier: the history is read by a person
     * justifying a till, not by a program.
     *
     * @return the signed-in name, or null when the context carries none
     */
    private String operatorName() {
        return identity == null || identity.getPrincipal() == null
                ? null : identity.getPrincipal().getName();
    }
}
