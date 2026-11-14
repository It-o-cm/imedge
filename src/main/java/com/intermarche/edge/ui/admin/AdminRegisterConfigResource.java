package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.DocumentTemplate;
import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.EchelonSetting;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.setting.StoreImage;
import com.intermarche.edge.domain.store.CashMenu;
import com.intermarche.edge.domain.store.CheckoutIsland;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.TpvType;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.EchelonSettingService;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
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
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The REGISTER CONFIGURATION screen ({@code /admin/register-config}): the
 * parameters of one point of contact or of one island, and the GROUPED
 * configuration of a set of registers (BO-03-09, BO-03-10, BO-03-12-03).
 *
 * <p>NO PARALLEL STORE. Every value written here is an echelon parameter posed
 * at the {@link EchelonLevel#CHECKOUT_POINT} level for one register or at the
 * {@link EchelonLevel#ISLAND} level for an island, exactly what the echelon
 * screen poses by hand: the store node resolves it for each register through
 * the chain point &gt; island &gt; PDV &gt; enseigne &gt; country, and the register
 * receives the result in its SETTINGS. This screen only adds what the echelon
 * screen lacks — the parameters of a register gathered on one page, offered as
 * lists to choose from (the header and footer templates, the logos, the cash
 * menus, the closed values), and the application of one value to a group of
 * registers chosen by island, by type, by list or all together.
 *
 * <p>An empty value is not a value: it removes the one posed at that level, so
 * the register inherits again. A date of effect programs the change for a
 * later day without touching the registers before it (BO-03-12-03).
 */
@Path("/admin/register-config")
public class AdminRegisterConfigResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminRegisterConfigResource.class);

    /** The redirect target, equal to the resource path. */
    private static final String PAGE = "/admin/register-config";

    /** The parameter mirrored onto the checkout point's own column. */
    static final String MENU_KEY = "menu.code";

    /**
     * The parameters of a point of contact, in the order the page shows them
     * (BO-03-09-02 to -31, BO-03-10-09).
     */
    static final List<String> KEYS = List.of(
            "ticket.header-template", "ticket.footer-template", "ticket.vat-table",
            "print.logo-code", "ticket.barcode-enabled", "ticket.barcode-format", MENU_KEY,
            "queue.single-enabled", "queue.single-url",
            "hardware.drawer.enabled", "hardware.drawer.address", "hardware.scale.enabled",
            "hardware.scanner.enabled", "hardware.scanner.driver", "hardware.scanner.name-patterns",
            "hardware.scanner.opos-device", "hardware.scanner2.enabled",
            "hardware.scanner2.name-patterns", "hardware.printer.enabled", "hardware.printer.type",
            "hardware.printer.speed", "hardware.printer.code-page", "hardware.printer.paper-width",
            "hardware.printer.cheque-flip", "printer2.enabled", "printer2.format",
            "printer2.connection", "printer2.address", "payment.terminal-type",
            "payment.terminal-address", "terminal.island-label", "terminal.pdv-label-enabled",
            "terminal.pdv-label", "catalina.enabled");

    /** How the page shows the day a programmed value applies. */
    static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** The ways a grouped configuration chooses its registers. */
    static final List<String> TARGETS = List.of("ISLAND", "TYPE", "LIST", "ALL");

    /** The page template. */
    @Inject
    @Location("admin-register-config")
    Template adminRegisterConfig;

    /** The training base data (BO-03-10-18). */
    @Inject
    com.intermarche.edge.service.TrainingDataService trainingDataService;

    /** The echelon engine the values are posed through. */
    @Inject
    EchelonSettingService echelonSettings;

    /**
     * One choice of a list field.
     *
     * @param value the stored value, empty for "inherited"
     * @param label what the list shows
     */
    public record Choice(String value, String label) {
    }

    /**
     * One parameter as the page shows it at one level.
     *
     * @param key the catalog key
     * @param label the French label
     * @param hint the French explanation
     * @param kind BOOL, CHOICE, INT or TEXT
     * @param value the CURRENT value posed at this level, empty when inherited
     * @param choices the values a list offers, empty for a free field
     * @param pending the value programmed at this level, empty for a programmed
     *        removal or when nothing is programmed (BO-03-12-03)
     * @param pendingDate the day the programmed value applies (dd/MM/yyyy), empty
     *        when nothing is programmed
     */
    public record Field(String key, String label, String hint, String kind, String value,
                        List<Choice> choices, String pending, String pendingDate) {

        /**
         * Tells whether the field is drawn as a list.
         *
         * @return true for a BOOL or a CHOICE field
         */
        public boolean isList() {
            return "BOOL".equals(kind) || "CHOICE".equals(kind);
        }
    }

    /**
     * Shows the page: the grouped form, the points and the islands, and the
     * parameters of the selected point or island.
     *
     * @param level the selected level, CHECKOUT_POINT or ISLAND, or null
     * @param code the selected terminal or island code, or null
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.REGISTER_CONFIG)
    @Transactional
    public TemplateInstance page(@QueryParam("level") String level, @QueryParam("code") String code,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method page with level: " + level + ", code: " + code);
        EchelonLevel selected = level(level);
        boolean known = selected != null && exists(selected, code);
        List<Field> fields = known ? fields(selected, code.trim()) : List.of();
        LOGGER.info("Exiting method page");
        return adminRegisterConfig.data("points", CheckoutPoint.listAllOrdered())
                .data("islands", CheckoutIsland.list("order by code"))
                .data("tpvTypes", TpvType.values())
                .data("keys", groupKeys())
                .data("targets", TARGETS)
                .data("selectedLevel", known ? selected.name() : null)
                .data("selectedCode", known ? code.trim() : null)
                .data("fields", fields)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Saves the parameters of one point or one island: every posted value is
     * posed at that level, every emptied one removed (BO-03-09, BO-03-10).
     *
     * @param form the posted form (level, code, effectiveDate, v.&lt;key&gt;)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/save")
    @Authenticated
    @FeatureAccess(value = Feature.REGISTER_CONFIG, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        EchelonLevel level = level(AdminForms.text(form, "level"));
        String code = AdminForms.text(form, "code");
        if (level == null || !exists(level, code)) {
            LOGGER.info("Exiting method save");
            return AdminForms.redirect(PAGE, "Caisse ou îlot inconnu.", false);
        }
        String rawDate = AdminForms.text(form, "effectiveDate");
        LocalDate effective = AdminForms.day(rawDate);
        if (rawDate != null && effective == null) {
            LOGGER.info("Exiting method save");
            return AdminForms.redirect(back(level, code), "Date d'effet invalide (attendu AAAA-MM-JJ).", false);
        }
        Map<String, String> posted = new LinkedHashMap<>();
        for (String key : KEYS) {
            if (form.containsKey("v." + key)) {
                String value = AdminForms.text(form, "v." + key);
                String refusal = refusal(key, value);
                if (refusal != null) {
                    LOGGER.info("Exiting method save");
                    return AdminForms.redirect(back(level, code), refusal, false);
                }
                posted.put(key, value);
            }
        }
        for (Map.Entry<String, String> entry : posted.entrySet()) {
            pose(level, code, entry.getKey(), entry.getValue(), effective);
        }
        LOGGER.info("Exiting method save");
        return AdminForms.redirect(back(level, code), "Configuration enregistrée.", true);
    }

    /**
     * Applies one value to a GROUP of registers (BO-03-10): the island's own
     * level for an island, each checkout point's level for a type — narrowed
     * to an island when one is named, which is the grouping rule —, a list or
     * the whole park.
     *
     * @param form the posted form (key, value, target, island, tpvType,
     *        terminals, effectiveDate)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/group")
    @Authenticated
    @FeatureAccess(value = Feature.REGISTER_CONFIG, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response group(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method group with form: " + form);
        String key = AdminForms.text(form, "key");
        if (key == null || !KEYS.contains(key)) {
            LOGGER.info("Exiting method group");
            return AdminForms.redirect(PAGE, "Paramètre inconnu.", false);
        }
        String value = AdminForms.text(form, "value");
        String refusal = refusal(key, value);
        if (refusal != null) {
            LOGGER.info("Exiting method group");
            return AdminForms.redirect(PAGE, refusal, false);
        }
        String rawDate = AdminForms.text(form, "effectiveDate");
        LocalDate effective = AdminForms.day(rawDate);
        if (rawDate != null && effective == null) {
            LOGGER.info("Exiting method group");
            return AdminForms.redirect(PAGE, "Date d'effet invalide (attendu AAAA-MM-JJ).", false);
        }
        String target = AdminForms.text(form, "target");
        String island = AdminForms.text(form, "island");
        if ("ISLAND".equals(target)) {
            if (island == null || CheckoutIsland.findByCode(island) == null) {
                LOGGER.info("Exiting method group");
                return AdminForms.redirect(PAGE, "Îlot inconnu.", false);
            }
            pose(EchelonLevel.ISLAND, island, key, value, effective);
            LOGGER.info("Exiting method group");
            return AdminForms.redirect(PAGE, "Configuration appliquée à l'îlot " + island + ".", true);
        }
        if (target == null || !TARGETS.contains(target)) {
            LOGGER.info("Exiting method group");
            return AdminForms.redirect(PAGE, "Cible de configuration inconnue.", false);
        }
        List<CheckoutPoint> points = targets(target, island, AdminForms.text(form, "tpvType"),
                AdminForms.text(form, "terminals"));
        for (CheckoutPoint point : points) {
            pose(EchelonLevel.CHECKOUT_POINT, point.terminalId, key, value, effective);
        }
        LOGGER.info("Exiting method group");
        return AdminForms.redirect(PAGE, "Configuration appliquée à " + points.size() + " caisse(s).",
                !points.isEmpty());
    }

    /**
     * Installs the base data a point of sale trains on before it opens
     * (BO-03-10-18): the training nomenclature, its test articles and the
     * generic register user.
     *
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/training")
    @Authenticated
    @FeatureAccess(value = Feature.REGISTER_CONFIG, option = CrudOption.CREATE)
    @Transactional
    public Response training() {
        LOGGER.info("Entering method training");
        int created = trainingDataService.install();
        LOGGER.info("Exiting method training");
        return AdminForms.redirect(PAGE, "Données de formation installées : " + created
                + " élément(s) créé(s).", true);
    }

    /**
     * Chooses the registers of a grouped configuration.
     *
     * @param target TYPE, LIST or ALL
     * @param island the island narrowing a TYPE target, or null
     * @param tpvType the type of a TYPE target, or null
     * @param terminals the identifiers of a LIST target, or null
     * @return the chosen points, in terminal order
     */
    List<CheckoutPoint> targets(String target, String island, String tpvType, String terminals) {
        List<CheckoutPoint> chosen = new ArrayList<>();
        List<String> listed = split(terminals);
        TpvType type = TpvType.fromName(tpvType);
        for (CheckoutPoint point : CheckoutPoint.listAllOrdered()) {
            boolean match = switch (target) {
                case "TYPE" -> type != null && point.tpvType == type
                        && (island == null || island.equals(point.islandCode));
                case "LIST" -> listed.contains(point.terminalId);
                default -> true;
            };
            if (match) {
                chosen.add(point);
            }
        }
        return chosen;
    }

    /**
     * Splits a typed list of terminal identifiers on semicolons, commas and
     * spaces.
     *
     * @param raw the typed list, or null
     * @return the identifiers, empty when none was typed
     */
    static List<String> split(String raw) {
        List<String> parts = new ArrayList<>();
        if (raw == null) {
            return parts;
        }
        for (String part : raw.split("[;,\\s]+")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    /**
     * Poses or removes one value at one level; the menu posed on a point is
     * also written on the point's own column, which the register reads first
     * (BO-03-10-09). A value dated for a later day is PROGRAMMED next to the
     * current one, which keeps applying until then: the column is left alone
     * and written by the echelon engine on that day (BO-03-12-03).
     *
     * @param level the level
     * @param code the terminal or island code
     * @param key the catalog key
     * @param value the value, or null to inherit again
     * @param effective the date of effect, or null for now
     */
    void pose(EchelonLevel level, String code, String key, String value, LocalDate effective) {
        if (value == null) {
            echelonSettings.clear(level, code, key, effective);
        } else {
            echelonSettings.set(level, code, key, value, effective);
        }
        boolean now = effective == null || !effective.isAfter(LocalDate.now());
        if (now && level == EchelonLevel.CHECKOUT_POINT && MENU_KEY.equals(key)) {
            CheckoutPoint point = CheckoutPoint.findByTerminal(code);
            if (point != null) {
                point.menuTemplateCode = value;
            }
        }
    }

    /**
     * Checks a value against what its parameter accepts.
     *
     * @param key the catalog key
     * @param value the posted value, or null for "inherit"
     * @return the refusal to show, or null when the value is accepted
     */
    String refusal(String key, String value) {
        if (value == null) {
            return null;
        }
        Field field = field(key, null, null);
        if ("INT".equals(field.kind())) {
            try {
                Integer.parseInt(value);
                return null;
            } catch (NumberFormatException e) {
                return "Valeur invalide pour « " + key + " » : nombre entier attendu.";
            }
        }
        if (!field.isList()) {
            return null;
        }
        for (Choice choice : field.choices()) {
            if (choice.value().equals(value)) {
                return null;
            }
        }
        return "Valeur invalide pour « " + key + " ».";
    }

    /**
     * Builds the fields of one level, with the values posed there.
     *
     * @param level the level
     * @param code the terminal or island code
     * @return the fields, in page order
     */
    List<Field> fields(EchelonLevel level, String code) {
        List<Field> fields = new ArrayList<>();
        for (String key : KEYS) {
            fields.add(field(key, level, code));
        }
        return fields;
    }

    /**
     * Builds one field.
     *
     * @param key the catalog key
     * @param level the level whose value is read, or null for none
     * @param code the terminal or island code, or null
     * @return the field
     */
    Field field(String key, EchelonLevel level, String code) {
        PosSettingsService.Def def = catalogDef(key);
        String value = "";
        String pending = "";
        String pendingDate = "";
        if (level != null) {
            EchelonSetting row = EchelonSetting.findValue(level, code, key);
            value = row == null || row.settingValue == null ? "" : row.settingValue;
            if (row != null && row.isProgrammed()) {
                pending = row.pendingValue == null ? "" : row.pendingValue;
                pendingDate = row.effectiveDate.format(DAY);
            }
        }
        List<Choice> choices = choices(key, def.type());
        String kind;
        if (def.type() == PosSettingsService.Type.BOOL) {
            kind = "BOOL";
        } else if (!choices.isEmpty()) {
            kind = "CHOICE";
        } else {
            kind = def.type() == PosSettingsService.Type.INT ? "INT" : "TEXT";
        }
        return new Field(key, def.label(), def.hint(), kind, value, choices, pending, pendingDate);
    }

    /**
     * The values a list field offers, "inherited" first.
     *
     * @param key the catalog key
     * @param type its catalog type
     * @return the choices, empty for a free field
     */
    List<Choice> choices(String key, PosSettingsService.Type type) {
        List<Choice> choices = new ArrayList<>();
        if (type == PosSettingsService.Type.BOOL) {
            choices.add(new Choice("", "Hérité"));
            choices.add(new Choice("true", "Activé"));
            choices.add(new Choice("false", "Désactivé"));
            return choices;
        }
        switch (key) {
            case "ticket.header-template" -> templates(choices, DocumentTemplate.DocumentType.TICKET_HEADER);
            case "ticket.footer-template" -> templates(choices, DocumentTemplate.DocumentType.TICKET_FOOTER);
            case "print.logo-code" -> {
                choices.add(new Choice("", "Hérité"));
                for (StoreImage image : StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO)) {
                    choices.add(new Choice(image.code, image.code + " — " + image.label));
                }
            }
            case MENU_KEY -> {
                choices.add(new Choice("", "Hérité"));
                for (CashMenu menu : CashMenu.listActive()) {
                    choices.add(new Choice(menu.code, menu.code + " — " + menu.label));
                }
            }
            default -> {
                for (String allowed : PosSettingsService.closedValues(key)) {
                    if (!allowed.isEmpty()) {
                        if (choices.isEmpty()) {
                            choices.add(new Choice("", "Hérité"));
                        }
                        choices.add(new Choice(allowed, allowed));
                    }
                }
            }
        }
        return choices;
    }

    /**
     * Adds the active templates of one banner type to a list.
     *
     * @param choices the list being built
     * @param type the banner type
     */
    private void templates(List<Choice> choices, DocumentTemplate.DocumentType type) {
        choices.add(new Choice("", "Hérité"));
        for (DocumentTemplate template : DocumentTemplate.listActiveFor(type)) {
            choices.add(new Choice(template.code, template.code + " — " + template.label));
        }
    }

    /**
     * Finds the catalog entry of one of the page's keys.
     *
     * @param key a key of {@link #KEYS}
     * @return the catalog entry
     */
    static PosSettingsService.Def catalogDef(String key) {
        return PosSettingsService.CATALOG.stream().filter(def -> def.key().equals(key))
                .findFirst().orElseThrow();
    }

    /**
     * The parameters the grouped form offers, key to French label.
     *
     * @return the labels, in page order
     */
    Map<String, String> groupKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        for (String key : KEYS) {
            keys.put(key, catalogDef(key).label());
        }
        return keys;
    }

    /**
     * Tells whether the point or the island named exists.
     *
     * @param level CHECKOUT_POINT or ISLAND
     * @param code the terminal or island code, or null
     * @return true when the back office administers it
     */
    boolean exists(EchelonLevel level, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return level == EchelonLevel.ISLAND
                ? CheckoutIsland.findByCode(code) != null
                : CheckoutPoint.findByTerminal(code) != null;
    }

    /**
     * Reads the level of the page: only the two levels of the shop are
     * administered here.
     *
     * @param raw the posted or queried level, or null
     * @return CHECKOUT_POINT, ISLAND, or null for anything else
     */
    static EchelonLevel level(String raw) {
        if ("CHECKOUT_POINT".equals(raw)) {
            return EchelonLevel.CHECKOUT_POINT;
        }
        return "ISLAND".equals(raw) ? EchelonLevel.ISLAND : null;
    }

    /**
     * The page of one level, to come back to after a save.
     *
     * @param level the level
     * @param code the terminal or island code
     * @return the path
     */
    private static String back(EchelonLevel level, String code) {
        return PAGE + "?level=" + level.name() + "&code="
                + java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8);
    }
}
