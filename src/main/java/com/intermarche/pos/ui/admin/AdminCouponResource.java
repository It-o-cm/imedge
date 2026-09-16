package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.barcode.AlertLevel;
import com.intermarche.pos.domain.barcode.CouponControl;
import com.intermarche.pos.domain.barcode.CouponField;
import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.service.CouponPatternService;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The back office's BARCODE RANGES page ({@code /admin/coupons}): the ranges
 * the till recognises, described by position rather than by regular expression
 * (BO-03-06).
 *
 * <p>A range is stated in plain terms — a literal head, a total length, a
 * character kind — and each value it carries is a POSITION: price, quantity,
 * point of sale number, start and end dates, time, ticket, sequence and
 * register numbers, minimum ticket total. {@link CouponPatternService} then
 * writes the recognition pattern the scan chain already runs on, so nothing
 * downstream of this page changes.
 *
 * <p>A range is refused rather than half-saved: the POSTED description is
 * checked before anything is written, and the stored row is only touched once
 * it holds together.
 *
 * <p>Same admin cycle as the other back-office pages: POST &rarr; 303 &rarr; a
 * one-shot notice, {@code @RolesAllowed("ADMIN")} throughout.
 */
@Path("/admin/coupons")
public class AdminCouponResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminCouponResource.class);

    /** The barcode ranges page template. */
    @Inject
    @Location("admin-coupons")
    Template adminCoupons;

    /** The generator turning an administered range into its patterns. */
    @Inject
    CouponPatternService couponPatterns;

    /**
     * Shows the ranges page: every type, its administered positions, and the
     * catalogs the forms offer.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    public TemplateInstance couponsPage(@QueryParam("notice") String notice,
                                        @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method couponsPage with notice: " + notice + ", noticeOk: " + noticeOk);
        LOGGER.info("Exiting method couponsPage");
        return adminCoupons.data("types", CouponType.<CouponType>find("order by priority, code").list())
                .data("articleRanges", com.intermarche.pos.domain.barcode.ArticleBarcodeRange
                        .<com.intermarche.pos.domain.barcode.ArticleBarcodeRange>listAll(
                                io.quarkus.panache.common.Sort.by("priority").and("code")))
                .data("valueSources", com.intermarche.pos.domain.barcode.ArticleBarcodeRange.ValueSource.values())
                .data("islands", com.intermarche.pos.domain.store.CheckoutIsland
                        .<com.intermarche.pos.domain.store.CheckoutIsland>find("order by code").list())
                .data("roles", CouponField.Role.values())
                .data("kinds", CouponField.Kind.values())
                .data("formats", CouponField.DateFormat.values())
                .data("currencies", CouponField.PriceCurrency.values())
                .data("sources", CouponType.AmountSource.values())
                .data("controlKinds", CouponControl.Kind.values())
                .data("levels", AlertLevel.values())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Upserts a range by its code and regenerates its patterns.
     *
     * @param form the posted form (code, label, prefix, codeLength, codeKind,
     *        amountSource, priority, active, depositLine)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/type")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveType(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveType with form: " + form);
        String code = trimmed(form, "code");
        if (code.isEmpty()) {
            LOGGER.info("Exiting method saveType");
            return redirect("Le code de la plage est obligatoire.", false);
        }
        CouponType.AmountSource source = parseSource(trimmed(form, "amountSource"));
        if (source == null) {
            LOGGER.info("Exiting method saveType");
            return redirect("Origine du montant inconnue.", false);
        }
        CouponField.Kind kind = parseKind(trimmed(form, "codeKind"));
        if (kind == null) {
            LOGGER.info("Exiting method saveType");
            return redirect("Type de caractères inconnu.", false);
        }
        Integer codeLength = positiveOrNull(trimmed(form, "codeLength"));
        String prefix = blankToNull(trimmed(form, "prefix"));
        CouponType existing = CouponType.find("code", code).firstResult();
        List<CouponField> kept = existing == null || existing.fields == null
                ? List.of()
                : new ArrayList<>(existing.fields);
        List<String> problems = couponPatterns.validate(prefix, codeLength, source, kept);
        if (!problems.isEmpty()) {
            LOGGER.info("Exiting method saveType");
            return redirect(problems.get(0), false);
        }
        CouponType type = existing;
        if (type == null) {
            type = new CouponType();
            type.code = code;
            type.persist();
        }
        type.label = trimmed(form, "label");
        type.amountSource = source;
        type.prefix = prefix;
        type.codeLength = codeLength;
        type.codeKind = kind;
        type.priority = intOr(trimmed(form, "priority"), 100);
        type.active = form.getFirst("active") != null;
        type.depositLine = form.getFirst("depositLine") != null;
        // BO-03-06-12 : un prix entièrement composé de 9 vaut « montant inconnu ».
        type.manualAmountOnAllNines = form.getFirst("manualAmountOnAllNines") != null;
        // BO-03-06-07 : les îlots où la plage est acceptée, vide = partout.
        type.islandCodes = blankToNull(trimmed(form, "islandCodes"));
        couponPatterns.regenerate(type);
        LOGGER.info("Exiting method saveType");
        return redirect(existing == null ? "Plage créée." : "Plage enregistrée.", true);
    }

    /**
     * Upserts one administered position of a range and regenerates its patterns.
     *
     * @param form the posted form (code, role, offsetPosition, fieldLength,
     *        kind, decimals, dateFormat, currency)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/field")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveField(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveField with form: " + form);
        CouponType type = CouponType.find("code", trimmed(form, "code")).firstResult();
        if (type == null) {
            LOGGER.info("Exiting method saveField");
            return redirect("Plage inconnue.", false);
        }
        CouponField.Role role = parseRole(trimmed(form, "role"));
        if (role == null) {
            LOGGER.info("Exiting method saveField");
            return redirect("Rôle de champ inconnu.", false);
        }
        CouponField.Kind kind = parseKind(trimmed(form, "kind"));
        if (kind == null) {
            LOGGER.info("Exiting method saveField");
            return redirect("Type de caractères inconnu.", false);
        }
        CouponField posted = new CouponField();
        posted.role = role;
        posted.kind = kind;
        posted.offsetPosition = intOr(trimmed(form, "offsetPosition"), -1);
        posted.fieldLength = intOr(trimmed(form, "fieldLength"), 0);
        posted.decimals = positiveOrZeroOrNull(trimmed(form, "decimals"));
        posted.dateFormat = parseFormat(trimmed(form, "dateFormat"));
        posted.currency = parseCurrency(trimmed(form, "currency"));
        if (type.fields == null) {
            type.fields = new ArrayList<>();
        }
        List<CouponField> prospect = new ArrayList<>(type.fields);
        prospect.removeIf(field -> field != null && field.role == role);
        prospect.add(posted);
        List<String> problems = couponPatterns.validate(type.prefix, type.codeLength,
                type.amountSource, prospect);
        if (!problems.isEmpty()) {
            LOGGER.info("Exiting method saveField");
            return redirect(problems.get(0), false);
        }
        posted.couponType = type;
        type.fields.removeIf(field -> field != null && field.role == role);
        type.fields.add(posted);
        couponPatterns.regenerate(type);
        LOGGER.info("Exiting method saveField");
        return redirect("Champ " + role.name() + " enregistré.", true);
    }

    /**
     * Upserts one administered control of a range.
     *
     * @param form the posted form (code, kind, level, message)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/control")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveControl(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveControl with form: " + form);
        CouponType type = CouponType.find("code", trimmed(form, "code")).firstResult();
        if (type == null) {
            LOGGER.info("Exiting method saveControl");
            return redirect("Plage inconnue.", false);
        }
        CouponControl.Kind kind = parseControlKind(trimmed(form, "kind"));
        if (kind == null) {
            LOGGER.info("Exiting method saveControl");
            return redirect("Contrôle inconnu.", false);
        }
        if (type.controls == null) {
            type.controls = new ArrayList<>();
        }
        type.controls.removeIf(control -> control != null && control.kind == kind);
        CouponControl control = new CouponControl();
        control.couponType = type;
        control.kind = kind;
        control.level = AlertLevel.of(trimmed(form, "level"));
        control.message = blankToNull(trimmed(form, "message"));
        type.controls.add(control);
        LOGGER.info("Exiting method saveControl");
        return redirect("Contrôle " + kind.name() + " enregistré.", true);
    }

    /**
     * Removes one administered control of a range.
     *
     * @param form the posted form (code, kind)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/control/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteControl(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteControl with form: " + form);
        CouponType type = CouponType.find("code", trimmed(form, "code")).firstResult();
        if (type == null) {
            LOGGER.info("Exiting method deleteControl");
            return redirect("Plage inconnue.", false);
        }
        CouponControl.Kind kind = parseControlKind(trimmed(form, "kind"));
        if (kind == null) {
            LOGGER.info("Exiting method deleteControl");
            return redirect("Contrôle inconnu.", false);
        }
        boolean removed = type.controls != null
                && type.controls.removeIf(control -> control != null && control.kind == kind);
        if (!removed) {
            LOGGER.info("Exiting method deleteControl");
            return redirect("Aucun contrôle " + kind.name() + " sur cette plage.", false);
        }
        LOGGER.info("Exiting method deleteControl");
        return redirect("Contrôle " + kind.name() + " supprimé.", true);
    }

    /**
     * Removes one administered position of a range and regenerates its patterns.
     *
     * @param form the posted form (code, role)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/field/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteField(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteField with form: " + form);
        CouponType type = CouponType.find("code", trimmed(form, "code")).firstResult();
        if (type == null) {
            LOGGER.info("Exiting method deleteField");
            return redirect("Plage inconnue.", false);
        }
        CouponField.Role role = parseRole(trimmed(form, "role"));
        if (role == null) {
            LOGGER.info("Exiting method deleteField");
            return redirect("Rôle de champ inconnu.", false);
        }
        boolean removed = type.fields != null
                && type.fields.removeIf(field -> field != null && field.role == role);
        if (!removed) {
            LOGGER.info("Exiting method deleteField");
            return redirect("Aucun champ " + role.name() + " sur cette plage.", false);
        }
        couponPatterns.regenerate(type);
        LOGGER.info("Exiting method deleteField");
        return redirect("Champ " + role.name() + " supprimé.", true);
    }

    /**
     * Upserts an ARTICLE barcode range by its code and regenerates its
     * recognition pattern (BO-03-06-02/03/04/05/10).
     *
     * <p>The POSTED description is checked before anything is written: a range
     * whose segments do not hold together is refused whole rather than stored
     * half-formed, exactly as a voucher range is.
     *
     * @param form the posted form (code, label, prefix, codeLength, codeKind,
     *        articlePosition, articleLength, valueSource, valuePosition,
     *        valueLength, valueDecimals, currency, checkDigit, priority, active)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/article-range")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveArticleRange(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveArticleRange with form: " + form);
        String code = trimmed(form, "code");
        if (code.isEmpty()) {
            LOGGER.info("Exiting method saveArticleRange");
            return redirect("Le code de la plage article est obligatoire.", false);
        }
        CouponField.Kind kind = parseKind(trimmed(form, "codeKind"));
        if (kind == null) {
            LOGGER.info("Exiting method saveArticleRange");
            return redirect("Type de caractères inconnu.", false);
        }
        com.intermarche.pos.domain.barcode.ArticleBarcodeRange.ValueSource source =
                parseValueSource(trimmed(form, "valueSource"));
        if (source == null) {
            LOGGER.info("Exiting method saveArticleRange");
            return redirect("Nature de la valeur inconnue.", false);
        }
        com.intermarche.pos.domain.barcode.ArticleBarcodeRange existing =
                com.intermarche.pos.domain.barcode.ArticleBarcodeRange.find("code", code).firstResult();
        com.intermarche.pos.domain.barcode.ArticleBarcodeRange posted =
                new com.intermarche.pos.domain.barcode.ArticleBarcodeRange();
        posted.code = code;
        posted.label = trimmed(form, "label");
        posted.prefix = blankToNull(trimmed(form, "prefix"));
        posted.codeLength = intOr(trimmed(form, "codeLength"), 0);
        posted.codeKind = kind;
        posted.articlePosition = intOr(trimmed(form, "articlePosition"), -1);
        posted.articleLength = intOr(trimmed(form, "articleLength"), 0);
        posted.valueSource = source;
        posted.valuePosition = intOr(trimmed(form, "valuePosition"), -1);
        posted.valueLength = intOr(trimmed(form, "valueLength"), 0);
        posted.valueDecimals = intOr(trimmed(form, "valueDecimals"), 2);
        CouponField.PriceCurrency currency = parseCurrency(trimmed(form, "currency"));
        posted.currency = currency == null ? CouponField.PriceCurrency.EUR : currency;
        posted.checkDigit = form.getFirst("checkDigit") != null;
        posted.priority = intOr(trimmed(form, "priority"), 100);
        posted.active = form.getFirst("active") != null;

        List<String> problems = couponPatterns.validateRange(posted);
        if (!problems.isEmpty()) {
            LOGGER.info("Exiting method saveArticleRange");
            return redirect(problems.get(0), false);
        }
        com.intermarche.pos.domain.barcode.ArticleBarcodeRange range = existing;
        if (range == null) {
            range = posted;
            range.persist();
        } else {
            range.label = posted.label;
            range.prefix = posted.prefix;
            range.codeLength = posted.codeLength;
            range.codeKind = posted.codeKind;
            range.articlePosition = posted.articlePosition;
            range.articleLength = posted.articleLength;
            range.valueSource = posted.valueSource;
            range.valuePosition = posted.valuePosition;
            range.valueLength = posted.valueLength;
            range.valueDecimals = posted.valueDecimals;
            range.currency = posted.currency;
            range.checkDigit = posted.checkDigit;
            range.priority = posted.priority;
            range.active = posted.active;
        }
        couponPatterns.regenerateRange(range);
        LOGGER.info("Exiting method saveArticleRange");
        return redirect(existing == null ? "Plage article créée." : "Plage article enregistrée.", true);
    }

    /**
     * Deletes an ARTICLE barcode range by its code.
     *
     * @param form the posted form (code)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/article-range/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteArticleRange(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteArticleRange with form: " + form);
        com.intermarche.pos.domain.barcode.ArticleBarcodeRange range =
                com.intermarche.pos.domain.barcode.ArticleBarcodeRange
                        .find("code", trimmed(form, "code")).firstResult();
        if (range == null) {
            LOGGER.info("Exiting method deleteArticleRange");
            return redirect("Plage article inconnue.", false);
        }
        range.delete();
        LOGGER.info("Exiting method deleteArticleRange");
        return redirect("Plage article supprimée.", true);
    }

    /**
     * Upserts a CHECKOUT ISLAND by its code (BO-03-06-07, BO-03-06-50).
     *
     * @param form the posted form (code, label, terminalIds, active)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/island")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveIsland(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveIsland with form: " + form);
        String code = trimmed(form, "code");
        if (code.isEmpty()) {
            LOGGER.info("Exiting method saveIsland");
            return redirect("Le code de l'îlot est obligatoire.", false);
        }
        com.intermarche.pos.domain.store.CheckoutIsland existing =
                com.intermarche.pos.domain.store.CheckoutIsland.find("code", code).firstResult();
        com.intermarche.pos.domain.store.CheckoutIsland island = existing;
        if (island == null) {
            island = new com.intermarche.pos.domain.store.CheckoutIsland();
            island.code = code;
            island.persist();
        }
        island.label = trimmed(form, "label");
        island.terminalIds = blankToNull(trimmed(form, "terminalIds"));
        island.active = form.getFirst("active") != null;
        LOGGER.info("Exiting method saveIsland");
        return redirect(existing == null ? "Îlot créé." : "Îlot enregistré.", true);
    }

    /**
     * Deletes a checkout island by its code.
     *
     * @param form the posted form (code)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/island/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteIsland(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteIsland with form: " + form);
        com.intermarche.pos.domain.store.CheckoutIsland island =
                com.intermarche.pos.domain.store.CheckoutIsland
                        .find("code", trimmed(form, "code")).firstResult();
        if (island == null) {
            LOGGER.info("Exiting method deleteIsland");
            return redirect("Îlot inconnu.", false);
        }
        island.delete();
        LOGGER.info("Exiting method deleteIsland");
        return redirect("Îlot supprimé.", true);
    }

    /**
     * Parses the nature of the value segment, tolerating an unknown or blank
     * value.
     *
     * @param raw the posted name
     * @return the nature, or null when unrecognised
     */
    private com.intermarche.pos.domain.barcode.ArticleBarcodeRange.ValueSource parseValueSource(String raw) {
        for (com.intermarche.pos.domain.barcode.ArticleBarcodeRange.ValueSource source
                : com.intermarche.pos.domain.barcode.ArticleBarcodeRange.ValueSource.values()) {
            if (source.name().equals(raw)) {
                return source;
            }
        }
        return null;
    }

    /**
     * Parses a control kind name, tolerating an unknown or blank value.
     *
     * @param raw the posted name
     * @return the kind, or null when unrecognised
     */
    private CouponControl.Kind parseControlKind(String raw) {
        for (CouponControl.Kind kind : CouponControl.Kind.values()) {
            if (kind.name().equals(raw)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * Parses an amount source name, tolerating an unknown or blank value.
     *
     * @param raw the posted name
     * @return the source, or null when unrecognised
     */
    private CouponType.AmountSource parseSource(String raw) {
        for (CouponType.AmountSource source : CouponType.AmountSource.values()) {
            if (source.name().equals(raw)) {
                return source;
            }
        }
        return null;
    }

    /**
     * Parses a character kind name, tolerating an unknown or blank value.
     *
     * @param raw the posted name
     * @return the kind, or null when unrecognised
     */
    private CouponField.Kind parseKind(String raw) {
        for (CouponField.Kind kind : CouponField.Kind.values()) {
            if (kind.name().equals(raw)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * Parses a field role name, tolerating an unknown or blank value.
     *
     * @param raw the posted name
     * @return the role, or null when unrecognised
     */
    private CouponField.Role parseRole(String raw) {
        for (CouponField.Role role : CouponField.Role.values()) {
            if (role.name().equals(raw)) {
                return role;
            }
        }
        return null;
    }

    /**
     * Parses a date or time layout name, an absent layout being legitimate.
     *
     * @param raw the posted name
     * @return the layout, or null when absent or unrecognised
     */
    private CouponField.DateFormat parseFormat(String raw) {
        for (CouponField.DateFormat format : CouponField.DateFormat.values()) {
            if (format.name().equals(raw)) {
                return format;
            }
        }
        return null;
    }

    /**
     * Parses a currency name, an absent currency meaning euros.
     *
     * @param raw the posted name
     * @return the currency, or null when absent or unrecognised
     */
    private CouponField.PriceCurrency parseCurrency(String raw) {
        for (CouponField.PriceCurrency currency : CouponField.PriceCurrency.values()) {
            if (currency.name().equals(raw)) {
                return currency;
            }
        }
        return null;
    }

    /**
     * Reads a posted whole number, falling back when it is absent or malformed.
     *
     * @param raw the posted value
     * @param fallback the value to use when nothing readable was posted
     * @return the parsed number, or the fallback
     */
    private int intOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Reads a posted strictly positive number, an absent value being legitimate.
     *
     * @param raw the posted value
     * @return the number, or null when absent, malformed or not positive
     */
    private Integer positiveOrNull(String raw) {
        int parsed = intOr(raw, -1);
        return parsed > 0 ? parsed : null;
    }

    /**
     * Reads a posted zero-or-positive number, an absent value being legitimate.
     *
     * @param raw the posted value
     * @return the number, or null when absent, malformed or negative
     */
    private Integer positiveOrZeroOrNull(String raw) {
        int parsed = intOr(raw, -1);
        return parsed >= 0 ? parsed : null;
    }

    /**
     * Reads a posted field, trimmed, never null.
     *
     * @param form the posted form
     * @param key the field name
     * @return the trimmed value, or the empty string when absent
     */
    private String trimmed(MultivaluedMap<String, String> form, String key) {
        String raw = form.getFirst(key);
        return raw == null ? "" : raw.trim();
    }

    /**
     * Maps an empty string to null, leaving any other value untouched.
     *
     * @param value the value to normalise
     * @return null when the value is empty, the value otherwise
     */
    private String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    /**
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/coupons?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
