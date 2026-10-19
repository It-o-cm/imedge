package com.intermarche.pos.ui;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.store.Store;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.Unremovable;
import io.quarkus.qute.TemplateGlobal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Resolves the display theme of the register's screens (phase: multi-theme).
 * <p>
 * RESOLUTION CHAIN, most specific wins: the logged cashier's personal
 * preference ({@code Employee.theme}, referential data travelling in the
 * phase 6 pull) → the store's default ({@code Store.theme}, node-local) →
 * the built-in dark theme ({@code sombre}). The resolved name lands on
 * every {@code <body data-theme="...">} and the token overrides live in
 * {@code theme.html} — adding a theme is a token block there plus a value
 * in a store or employee row, no template changes.
 * <p>
 * Exposed to EVERY template as the Qute global {@code {posTheme}} (see
 * {@link Globals}), so no resource has to plumb it into its template data.
 * Package placement: consumed exclusively by the screens — ui root, like
 * the other cross-screen concerns.
 */
@ApplicationScoped
@Unremovable // Nothing injects this bean (the Qute global reaches it through
             // Arc programmatically), so without this annotation ArC's
             // unused-bean elimination removes it at build time and the
             // first themed rendering dies on an empty instance handle.
public class ThemeService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ThemeService.class);

    /** The built-in default theme name (the historical dark look). */
    public static final String DEFAULT_THEME = "sombre";

    /**
     * The selectable theme names, in display order — every entry must have
     * its token block in theme.html (the default needs none: it is :root).
     */
    public static final java.util.List<String> AVAILABLE_THEMES = java.util.List.of("sombre", "clair");

    @Inject
    PosState state;

    /** Administered settings — carries the training-mode theme (BO-10-07-08). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Resolves the current theme name for this register's screens.
     * <p>
     * BO-10-07-08: in TRAINING mode the administered training theme, when the
     * store names one, wins over the whole chain below — the school screen is
     * then visibly another screen than the sale one, and which one it is comes
     * from the back office rather than from the code. Left blank, training
     * keeps the ordinary resolution and the banner alone marks it.
     *
     * @return the data-theme value to render (never null)
     */
    public String currentTheme() {
        LOGGER.info("Entering method currentTheme");
        if (state.trainingMode) {
            String trainingTheme = posSettingsService.trainingTheme();
            if (trainingTheme != null && !trainingTheme.isBlank()) {
                LOGGER.info("Exiting method currentTheme");
                return trainingTheme.trim();
            }
        }
        if (state.auth != null && state.auth.operatorId != null) {
            Employee operator = Employee.findById(state.auth.operatorId);
            if (operator != null && operator.theme != null && !operator.theme.isBlank()) {
                LOGGER.info("Exiting method currentTheme");
                return operator.theme;
            }
        }
        Store store = Store.findAll().firstResult();
        if (store != null && store.theme != null && !store.theme.isBlank()) {
            LOGGER.info("Exiting method currentTheme");
            return store.theme;
        }
        LOGGER.info("Exiting method currentTheme");
        return DEFAULT_THEME;
    }

    /**
     * Persists the logged cashier's theme preference. A blank or unknown
     * value clears the preference (back to the store's default) — the
     * selector's "store default" choice posts an empty value on purpose.
     *
     * @param themeName the chosen theme, or blank to follow the store
     */
    @jakarta.transaction.Transactional
    public void setThemeForOperator(String themeName) {
        LOGGER.info("Entering method setThemeForOperator with themeName: " + themeName);
        if (state.auth == null || state.auth.operatorId == null) {
            LOGGER.info("Exiting method setThemeForOperator");
            return;
        }
        Employee operator = Employee.findById(state.auth.operatorId);
        if (operator == null) {
            LOGGER.info("Exiting method setThemeForOperator");
            return;
        }
        operator.theme = (themeName != null && AVAILABLE_THEMES.contains(themeName)) ? themeName : null;
        state.touch();
        LOGGER.info("Exiting method setThemeForOperator");
    }

    /**
     * Qute globals of the theming system.
     */
    @TemplateGlobal
    public static class Globals {

        /**
         * The birth-year threshold of the pending age check, available in
         * the sale template as {@code {ageCheckBirthYear}} — "né avant
         * <year>" reads faster at the till than mental arithmetic (phase:
         * age control; lives here with the other cross-screen Qute globals).
         *
         * @return the latest birth year an of-age customer can have
         */
        public static int ageCheckBirthYear() {
            try {
                InstanceHandle<com.intermarche.pos.ui.PosState> handle =
                        Arc.container().instance(com.intermarche.pos.ui.PosState.class);
                int threshold = handle.isAvailable() ? handle.get().ageCheck.threshold : 18;
                return java.time.LocalDate.now().getYear() - (threshold > 0 ? threshold : 18);
            } catch (Exception e) {
                return java.time.LocalDate.now().getYear() - 18;
            }
        }

        /**
         * True when the LOGGED operator holds a supervising role — drives
         * the connected-supervisor shortcut button of the endorsement modal
         * (LC-01-05-07). Resolved through Arc like {@code posTheme()}; any
         * trouble answers false (the modal then simply asks a credential).
         *
         * @return true when the logged operator can approve endorsements
         */
        public static boolean supervisorLogged() {
            try {
                InstanceHandle<com.intermarche.pos.ui.endorsement.EndorsementService> svc =
                        Arc.container().instance(com.intermarche.pos.ui.endorsement.EndorsementService.class);
                InstanceHandle<com.intermarche.pos.ui.PosState> st =
                        Arc.container().instance(com.intermarche.pos.ui.PosState.class);
                return svc.isAvailable() && st.isAvailable()
                        && svc.get().operatorIsSupervisor(st.get());
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * Whether the article EAN is displayed next to the label on the
         * register and customer screens (LC-02-04-02) — driven by the
         * {@code pos.display.show-ean} configuration key, available in every
         * template as {@code {showEan}}.
         *
         * @return true when the EAN accompanies the label on screen
         */
        public static boolean showEan() {
            try {
                InstanceHandle<com.intermarche.pos.service.PosSettingsService> handle =
                        Arc.container().instance(com.intermarche.pos.service.PosSettingsService.class);
                return handle.isAvailable() && handle.get().showEan();
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * Whether the enter key alone opens the settlement (BO-10-02-13),
         * available in every template as {@code {enterAsTotal}}.
         *
         * <p>Resolved through Arc like {@code posTheme()}, and false whenever
         * the parameters cannot be read: a register that cannot say what the
         * key does keeps the behaviour it has always had.
         *
         * @return true when enter on an empty buffer is the TOTAL key
         */
        public static boolean enterAsTotal() {
            try {
                InstanceHandle<com.intermarche.pos.service.PosSettingsService> handle =
                        Arc.container().instance(com.intermarche.pos.service.PosSettingsService.class);
                return handle.isAvailable() && handle.get().enterAsTotal();
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * The resolved theme name, available in every template as
         * {@code {posTheme}} without any per-resource plumbing.
         *
         * @return the data-theme value to render
         */
        public static String posTheme() {
            // Theming must never break a rendering: any resolution trouble
            // falls back to the default theme.
            try {
                InstanceHandle<ThemeService> handle = Arc.container().instance(ThemeService.class);
                return handle.isAvailable() ? handle.get().currentTheme() : DEFAULT_THEME;
            } catch (Exception e) {
                return DEFAULT_THEME;
            }
        }
    }
}
