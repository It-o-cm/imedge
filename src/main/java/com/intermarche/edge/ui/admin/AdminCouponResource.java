package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.barcode.AlertLevel;
import com.intermarche.edge.domain.barcode.CouponControl;
import com.intermarche.edge.domain.barcode.CouponField;
import com.intermarche.edge.domain.barcode.CouponType;
import com.intermarche.edge.service.CouponPatternService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
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
 * one-shot notice, {@code @FeatureAccess} throughout (BO-01-04-01).
 */
@Path("/admin/coupons")
public class AdminCouponResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminCouponResource.class);

    /**
     * The instruments a register issues under a range (BO-03-06-30 to -38): the
     * kinds of its stored-value registry.
     */
    static final List<String> ISSUED_KINDS = List.of("CREDIT_NOTE", "GIFT_CARD");

    /**
     * The tenders a scanned code may be settled as (BO-03-06-08): the purchase
     * voucher and the paper meal voucher, the two a barcode carries.
     */
    static final List<String> SCANNED_TENDERS = List.of("VOUCHER", "TR");

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
    @Authenticated
    @FeatureAccess(value = Feature.COUPON)
    public TemplateInstance couponsPage(@QueryParam("notice") String notice,
                                        @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method couponsPage with notice: " + notice + ", noticeOk: " + noticeOk);
        LOGGER.info("Exiting method couponsPage");
        return adminCoupons.data("types", CouponType.<CouponType>find("order by priority, code").list())
                .data("articleRanges", com.intermarche.edge.domain.barcode.ArticleBarcodeRange
                        .<com.intermarche.edge.domain.barcode.ArticleBarcodeRange>listAll(
                                io.quarkus.panache.common.Sort.by("priority").and("code")))
                .data("valueSources", com.intermarche.edge.domain.barcode.ArticleBarcodeRange.ValueSource.values())
                .data("purposes", com.intermarche.edge.domain.barcode.ArticleBarcodeRange.Purpose.values())
                .data("functions", com.intermarche.edge.domain.barcode.RegisterFunction.values())
                .data("tenders", com.intermarche.edge.domain.payment.TenderDefinition
                        .<com.intermarche.edge.domain.payment.TenderDefinition>find(
                                "code in ?1 order by functionalId", SCANNED_TENDERS).list())
                .data("issuedKinds", ISSUED_KINDS)
                .data("cardProtocols", CouponType.CARD_PROTOCOLS)
                .data("islands", com.intermarche.edge.domain.store.CheckoutIsland
                        .<com.intermarche.edge.domain.store.CheckoutIsland>find("order by code").list())
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
     *        amountSource, priority, active, depositLine, tenderCode, issuedKind)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/type")
    @Authenticated
    @FeatureAccess(value = Feature.COUPON, option = CrudOption.CREATE, updateKey = "code", entity = com.intermarche.edge.domain.barcode.CouponType.class)
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
        // BO-03-06-08: the tender the range is settled as, among the two a scan settles.
        String tenderCode = blankToNull(trimmed(form, "tenderCode"));
        if (tenderCode != null && !SCANNED_TENDERS.contains(tenderCode)) {
            LOGGER.info("Exiting method saveType");
            return redirect("Moyen de paiement inconnu pour une plage scannée.", false);
        }
        // BO-03-06-30 to -38: issuing an instrument under the range needs a registry
        // range whose length is administered, the code being composed from it.
        String issuedKind = blankToNull(trimmed(form, "issuedKind"));
        if (issuedKind != null && (!ISSUED_KINDS.contains(issuedKind)
                || source != CouponType.AmountSource.REGISTRY || codeLength == null)) {
            LOGGER.info("Exiting method saveType");
            return redirect("Seule une plage REGISTRY de longueur administrée émet des avoirs ou des cartes cadeau.", false);
        }
        // BO-03-02-37: the protocol of a prepaid card range, chosen from a list.
        String cardProtocol = blankToNull(trimmed(form, "cardProtocol"));
        if (cardProtocol != null && !CouponType.CARD_PROTOCOLS.contains(cardProtocol)) {
            LOGGER.info("Exiting method saveType");
            return redirect("Protocole de carte inconnu.", false);
        }
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
        type.tenderCode = tenderCode;
        type.issuedKind = issuedKind;
        type.cardProtocol = cardProtocol;
        couponPatterns.regenerate(type);
        // Persisted once described: the insert carries the state the row had
        // when it was persisted, and its NOT NULL columns are filled only here.
        if (existing == null) {
            type.persist();
        }
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
    @Authenticated
    @FeatureAccess(value = Feature.COUPON, option = CrudOption.UPDATE)
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
    @Authenticated
    @FeatureAccess(value = Feature.COUPON, option = CrudOption.UPDATE)
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
    @Authenticated
    @FeatureAccess(value = Feature.COUPON, option = CrudOption.DELETE)
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
    @Authenticated
    @FeatureAccess(value = Feature.COUPON, option = CrudOption.DELETE)
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
     *        valueLength, valueDecimals, currency, checkDigit, priority, active,
     *        promotionCode, lookupUrl, functionCode, promotionType, lookupField)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/article-range")
    @Authenticated
    @FeatureAccess(value = Feature.COUPON, option = CrudOption.CREATE, updateKey = "code", entity = com.intermarche.edge.domain.barcode.ArticleBarcodeRange.class)
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
        com.intermarche.edge.domain.barcode.ArticleBarcodeRange.ValueSource source =
                parseValueSource(trimmed(form, "valueSource"));
        if (source == null) {
            LOGGER.info("Exiting method saveArticleRange");
            return redirect("Nature de la valeur inconnue.", false);
        }
        com.intermarche.edge.domain.barcode.ArticleBarcodeRange.Purpose purpose =
                parsePurpose(trimmed(form, "purpose"));
        if (purpose == null) {
            LOGGER.info("Exiting method saveArticleRange");
            return redirect("Destination de la plage inconnue.", false);
        }
        // BO-03-06-43: what a defined promotion is worth — a percentage or an
        // amount read on the code — or nothing for a promotion named only.
        String promotionType = blankToNull(trimmed(form, "promotionType"));
        if (promotionType != null
                && !com.intermarche.edge.domain.barcode.ArticleBarcodeRange.PROMOTION_PERCENT.equals(promotionType)
                && !com.intermarche.edge.domain.barcode.ArticleBarcodeRange.PROMOTION_AMOUNT.equals(promotionType)) {
            LOGGER.info("Exiting method saveArticleRange");
            return redirect("Type de promotion inconnu.", false);
        }
        com.intermarche.edge.domain.barcode.ArticleBarcodeRange existing =
                com.intermarche.edge.domain.barcode.ArticleBarcodeRange.find("code", code).firstResult();
        com.intermarche.edge.domain.barcode.ArticleBarcodeRange posted =
                new com.intermarche.edge.domain.barcode.ArticleBarcodeRange();
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
        posted.purpose = purpose;
        // BO-03-06-43: the promotion a PROMOTION range names. Blank becomes
        // null rather than an empty string, so an incomplete row is visibly
        // incomplete and the register refuses to arm it.
        posted.promotionCode = blankToNull(trimmed(form, "promotionCode"));
        // BO-03-06-41: the third-party service an EXTERNAL_LOOKUP range asks.
        posted.lookupUrl = blankToNull(trimmed(form, "lookupUrl"));
        // BO-03-06-59: the register function a FUNCTION range calls; blank leaves
        // the code to carry the function's number.
        posted.functionCode = blankToNull(trimmed(form, "functionCode"));
        posted.promotionType = promotionType;
        // BO-03-06-41: the field of the partner's answer the register reads.
        posted.lookupField = blankToNull(trimmed(form, "lookupField"));
        posted.priority = intOr(trimmed(form, "priority"), 100);
        posted.active = form.getFirst("active") != null;

        List<String> problems = couponPatterns.validateRange(posted);
        if (!problems.isEmpty()) {
            LOGGER.info("Exiting method saveArticleRange");
            return redirect(problems.get(0), false);
        }
        com.intermarche.edge.domain.barcode.ArticleBarcodeRange range = existing;
        if (range == null) {
            range = posted;
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
            range.purpose = posted.purpose;
            range.promotionCode = posted.promotionCode;
            range.lookupUrl = posted.lookupUrl;
            range.functionCode = posted.functionCode;
            range.promotionType = posted.promotionType;
            range.lookupField = posted.lookupField;
            range.priority = posted.priority;
            range.active = posted.active;
        }
        couponPatterns.regenerateRange(range);
        // Persisted once described, its pattern included: the insert carries the
        // state the row had when it was persisted.
        if (existing == null) {
            range.persist();
        }
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
    @Authenticated
    @FeatureAccess(value = Feature.COUPON, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteArticleRange(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteArticleRange with form: " + form);
        com.intermarche.edge.domain.barcode.ArticleBarcodeRange range =
                com.intermarche.edge.domain.barcode.ArticleBarcodeRange
                        .find("code", trimmed(form, "code")).firstResult();
        if (range == null) {
            LOGGER.info("Exiting method deleteArticleRange");
            return redirect("Plage article inconnue.", false);
        }
        range.delete();
        LOGGER.info("Exiting method deleteArticleRange");
        return redirect("Plage article supprimée.", true);
    }

    // The checkout-island CRUD moved to its own back-office screen
    // (AdminCheckoutIslandResource, /admin/checkout-islands) when the island was
    // promoted to a settings-bearing object with a PDV link, a promotion channel
    // and its register relation carried by CheckoutPoint (BO-03-10). The coupon
    // screen still READS the islands — a coupon range is accepted or refused by
    // island code — so the list is kept on the page (see settingsPage/islands
    // data), but it no longer edits them here.

    /**
     * Parses the nature of the value segment, tolerating an unknown or blank
     * value.
     *
     * @param raw the posted name
     * @return the nature, or null when unrecognised
     */
    /**
     * Reads the posted range destination, the ARTICLE default when nothing was
     * posted (BO-03-06-56/-60/-61).
     *
     * @param raw the posted value, possibly empty
     * @return the destination, or null when the posted value names none
     */
    private com.intermarche.edge.domain.barcode.ArticleBarcodeRange.Purpose parsePurpose(String raw) {
        if (raw == null || raw.isEmpty()) {
            return com.intermarche.edge.domain.barcode.ArticleBarcodeRange.Purpose.ARTICLE;
        }
        for (com.intermarche.edge.domain.barcode.ArticleBarcodeRange.Purpose purpose
                : com.intermarche.edge.domain.barcode.ArticleBarcodeRange.Purpose.values()) {
            if (purpose.name().equals(raw)) {
                return purpose;
            }
        }
        return null;
    }

    private com.intermarche.edge.domain.barcode.ArticleBarcodeRange.ValueSource parseValueSource(String raw) {
        for (com.intermarche.edge.domain.barcode.ArticleBarcodeRange.ValueSource source
                : com.intermarche.edge.domain.barcode.ArticleBarcodeRange.ValueSource.values()) {
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
