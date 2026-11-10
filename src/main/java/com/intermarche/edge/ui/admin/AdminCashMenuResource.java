package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.store.CashFunction;
import com.intermarche.edge.domain.store.CashMenu;
import com.intermarche.edge.domain.store.MenuLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.security.FeatureAccess;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CASH MENU screen ({@code /admin/cash-menus}): the library of named menu
 * models the registers draw (BO-03-10-16, BO-03-10-17).
 *
 * <p>A model is created, edited and deleted here; it is ASSIGNED elsewhere —
 * on the checkout point, on the island, or at the PDV, enseigne or country
 * echelon through the {@code menu.code} parameter (BO-03-09-06, BO-03-10-09,
 * BO-03-10-18). The models reach the registers through the
 * {@code CASH_MENUS} referential.
 */
@Path("/admin/cash-menus")
public class AdminCashMenuResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminCashMenuResource.class);

    /** The redirect target, equal to the resource path. */
    private static final String PAGE = "/admin/cash-menus";

    /** The menu-bar keys with the French label the screen shows. */
    static final List<AdminRegisterConfigResource.Choice> MENU_KEY_LABELS = List.of(
            new AdminRegisterConfigResource.Choice("articles", "Articles"),
            new AdminRegisterConfigResource.Choice("caisse", "Caisse"),
            new AdminRegisterConfigResource.Choice("reglement", "Règlement"),
            new AdminRegisterConfigResource.Choice("ticket", "Ticket"),
            new AdminRegisterConfigResource.Choice("generosite", "Générosité"),
            new AdminRegisterConfigResource.Choice("tiroir", "Tiroir"),
            new AdminRegisterConfigResource.Choice("autre", "Autre"));

    /** The palette of the key layout, as the registers draw it. */
    static final Map<String, String> COLOR_HEX = Map.of("blue", "#1f5fd6", "green", "#12804a",
            "red", "#c6282f", "orange", "#e07a10", "yellow", "#e8c21c", "purple", "#7a3fc2",
            "teal", "#0f8a8a", "grey", "#6b7280");

    /**
     * The JSON codec of the editor's data. Package-private and non-final so a
     * unit test can swap in a mapper that fails, exercising the defensive
     * {@code editorData()} catch which no real editor data can reach.
     */
    static ObjectMapper MAPPER = new ObjectMapper();

    /** The labels of the screen, in the operator's language. */
    @Inject
    BackOfficeMessages i18n;

    /** The page template. */
    @Inject
    @Location("admin-cash-menus")
    Template adminCashMenus;

    /**
     * Shows the library and, when a code is given, the model to edit.
     *
     * @param code the model to edit, or null for a new one
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.CASH_MENU)
    @Transactional
    public TemplateInstance page(@QueryParam("code") String code, @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method page with code: " + code);
        CashMenu editing = CashMenu.findByCode(code);
        LOGGER.info("Exiting method page");
        return adminCashMenus.data("menus", CashMenu.listAllOrdered())
                .data("editing", editing)
                .data("menuKeys", MENU_KEY_LABELS)
                .data("shownKeys", editing == null ? List.of() : editing.menuKeyList())
                .data("functions", CashFunction.values())
                .data("hidden", editing == null ? List.of() : editing.hiddenFunctionList())
                .data("cashierScreens", CashMenu.CASHIER_SCREENS)
                .data("customerScreens", CashMenu.CUSTOMER_SCREENS)
                .data("ticketModels", CashMenu.TICKET_MODELS)
                .data("layoutData", editorData(editing))
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Upserts a model by its code (BO-03-10-16/17).
     *
     * @param form the posted form (code, label, active, menuKey multi-valued,
     *        hidden multi-valued, keyColumns, cashierScreen, customerScreen,
     *        ticketModel)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/save")
    @Authenticated
    @FeatureAccess(value = Feature.CASH_MENU, option = CrudOption.CREATE, updateKey = "code",
            entity = CashMenu.class)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        String code = AdminForms.text(form, "code");
        String label = AdminForms.text(form, "label");
        if (code == null || label == null) {
            LOGGER.info("Exiting method save");
            return AdminForms.redirect(PAGE, "Le code et le libellé du menu sont obligatoires.", false);
        }
        CashMenu existing = CashMenu.findByCode(code);
        CashMenu menu = existing == null ? new CashMenu() : existing;
        menu.code = code;
        menu.label = label;
        menu.active = AdminForms.checkbox(form, "active");
        // THE KEY LAYOUT supersedes the bar keys ticked: the standard tabs it
        // shows are still written there, for the registers that read only them.
        MenuLayout layout = MenuLayout.parse(AdminForms.text(form, "layout"));
        menu.layout = layout == null ? null : layout.toJson();
        menu.menuKeys = layout == null ? joined(form.get("menuKey")) : joined(layout.standardTabIds());
        menu.hiddenFunctions = joined(form.get("hidden"));
        menu.keyColumns = Math.max(0, Math.min(CashMenu.MAX_KEY_COLUMNS,
                AdminForms.integer(AdminForms.text(form, "keyColumns"), 0)));
        menu.cashierScreen = CashMenu.normalized(AdminForms.text(form, "cashierScreen"), CashMenu.CASHIER_SCREENS);
        menu.customerScreen = CashMenu.normalized(AdminForms.text(form, "customerScreen"),
                CashMenu.CUSTOMER_SCREENS);
        menu.ticketModel = CashMenu.normalized(AdminForms.text(form, "ticketModel"), CashMenu.TICKET_MODELS);
        if (existing == null) {
            menu.persist();
        }
        LOGGER.info("Exiting method save");
        return AdminForms.redirect(PAGE + "?code=" + java.net.URLEncoder.encode(code,
                java.nio.charset.StandardCharsets.UTF_8),
                existing == null ? "Menu caisse créé." : "Menu caisse enregistré.", true);
    }

    /**
     * Deletes a model by its code; the registers whose parameter still names it
     * then draw every key.
     *
     * @param form the posted form (code)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.CASH_MENU, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete with form: " + form);
        CashMenu menu = CashMenu.findByCode(AdminForms.text(form, "code"));
        if (menu == null) {
            LOGGER.info("Exiting method delete");
            return AdminForms.redirect(PAGE, "Menu caisse inconnu.", false);
        }
        menu.delete();
        LOGGER.info("Exiting method delete");
        return AdminForms.redirect(PAGE, "Menu caisse supprimé.", true);
    }

    /**
     * Joins the values of a multi-valued field with semicolons.
     *
     * @param values the posted values, possibly null
     * @return the list, or null when nothing was ticked
     */
    static String joined(List<String> values) {
        List<String> kept = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    kept.add(value.trim());
                }
            }
        }
        return kept.isEmpty() ? null : String.join(";", kept);
    }

    /**
     * Builds what the key layout editor reads: the layout of the model (the
     * standard one for a new model), the standard layout to restore, the
     * standard labels of the functions and tabs, the palette and the texts of
     * the editor, in the operator's language. The JSON is made safe to sit in
     * a script element.
     *
     * @param editing the model edited, or null for a new one
     * @return the JSON
     */
    String editorData(CashMenu editing) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layout", editing == null ? MenuLayout.standard() : editing.effectiveLayout());
        data.put("standard", MenuLayout.standard());
        Map<String, String> functions = new LinkedHashMap<>();
        for (CashFunction function : CashFunction.values()) {
            functions.put(function.name(), i18n.text(function.getLabel()));
        }
        data.put("functions", functions);
        Map<String, String> tabNames = new LinkedHashMap<>();
        for (AdminRegisterConfigResource.Choice choice : MENU_KEY_LABELS) {
            tabNames.put(choice.value(), i18n.text(choice.label()));
        }
        data.put("tabNames", tabNames);
        Map<String, String> colors = new LinkedHashMap<>();
        Map<String, String> colorNames = new LinkedHashMap<>();
        for (String color : MenuLayout.COLORS) {
            colors.put(color, COLOR_HEX.get(color));
            colorNames.put(color, i18n.get("t.k19-ml-couleur-" + color));
        }
        data.put("colors", colors);
        data.put("colorNames", colorNames);
        data.put("languages", MenuLayout.LANGUAGES);
        data.put("columns", editing == null || editing.keyColumns <= 0 ? 2
                : Math.min(CashMenu.MAX_KEY_COLUMNS, editing.keyColumns));
        data.put("maxTabs", MenuLayout.MAX_TABS);
        data.put("maxKeys", MenuLayout.MAX_KEYS);
        Map<String, String> texts = new LinkedHashMap<>();
        for (String text : List.of("addTab", "newTab", "newTabLabel", "noTab", "directTab", "emptyTab",
                "hiddenKey", "unplaced", "addKey", "keyProps", "tabProps", "labelRequired", "standardColor",
                "moveBefore", "moveAfter", "removeKey", "removeTab")) {
            texts.put(text, i18n.get("t.k19-ml-" + text));
        }
        data.put("texts", texts);
        try {
            return MAPPER.writeValueAsString(data).replace("<", "\\u003c");
        } catch (Exception e) {
            throw new IllegalStateException("Unwritable editor data", e);
        }
    }
}
