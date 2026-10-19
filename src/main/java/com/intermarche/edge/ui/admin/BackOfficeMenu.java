package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.Menu;
import com.intermarche.edge.domain.people.UserFavorite;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.PermissionService;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The back-office NAVIGATION, built from the {@link Feature} catalog.
 * <p>
 * The header used to carry fourteen links written by hand, side by side, with
 * no grouping and no relation to the rights model: nine of those screens were
 * reachable by anyone holding the ADMIN role and grantable to no one, because
 * no {@code Feature} named them. Deriving the navigation from the catalog
 * makes the two the same thing — a screen exists in the menu because a feature
 * declares it, and it is hidden because the profile does not grant it
 * (BO-01-04-06) or because the operator chose to hide it (BO-01-04-08).
 * <p>
 * Two levels, not one: the {@link Menu} aggregates are the first level, their
 * features the second. That is the grouping the profile grid already uses, so
 * the operator sees the same tree when navigating and when being granted
 * rights.
 * <p>
 * Request-scoped, and reached from the layout through
 * {@code inject:backOfficeMenu}: the answer depends on who is signed in.
 */
@Named("backOfficeMenu")
@RequestScoped
@TemplateData
public class BackOfficeMenu {

    /** Who is asking. */
    @Inject
    CurrentUser currentUser;

    /** The rights of the signed-in operator. */
    @Inject
    PermissionService permissionService;

    /**
     * One entry of the navigation: a screen the operator may open.
     */
    @TemplateData
    public static class Entry {

        /** The operator-facing label. */
        public final String label;

        /** The path of the screen. */
        public final String path;

        /**
         * Builds an entry.
         *
         * @param label the operator-facing label
         * @param path the path of the screen
         */
        public Entry(String label, String path) {
            this.label = label;
            this.path = path;
        }

        /**
         * Returns the operator-facing label.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }

        /**
         * Returns the path of the screen.
         *
         * @return the path
         */
        public String getPath() {
            return path;
        }
    }

    /**
     * One aggregate of the navigation: a menu and the screens under it.
     */
    @TemplateData
    public static class Group {

        /** The menu label. */
        public final String label;

        /** The screens the operator may open under it, never empty. */
        public final List<Entry> entries;

        /**
         * Builds a group.
         *
         * @param label the menu label
         * @param entries the visible screens under it
         */
        public Group(String label, List<Entry> entries) {
            this.label = label;
            this.entries = entries;
        }

        /**
         * Returns the menu label.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }

        /**
         * Returns the visible screens under this menu.
         *
         * @return the entries
         */
        public List<Entry> getEntries() {
            return entries;
        }
    }

    /**
     * The navigation of the signed-in operator: the menus that still hold at
     * least one visible screen, in declaration order.
     * <p>
     * An empty menu is dropped rather than shown empty — a heading that opens
     * on nothing is worse than no heading.
     *
     * @return the visible groups, empty when nobody is signed in
     */
    public List<Group> getGroups() {
        List<Group> groups = new ArrayList<>();
        if (currentUser == null || !currentUser.isAuthenticated()) {
            return groups;
        }
        Set<String> hidden = hiddenKeys();
        for (Menu menu : Menu.values()) {
            List<Entry> entries = new ArrayList<>();
            for (Feature feature : menu.features()) {
                if (!hidden.contains(feature.getKey()) && mayOpen(feature)) {
                    entries.add(new Entry(feature.getLabel(), feature.getPath()));
                }
            }
            if (!entries.isEmpty()) {
                groups.add(new Group(menu.getLabel(), entries));
            }
        }
        return groups;
    }

    /**
     * Whether the signed-in operator may open the screen of a feature.
     * <p>
     * Viewing it is the weakest right a screen can need, so VIEW is what the
     * navigation asks about; a screen whose write gestures are refused is
     * still worth showing, and refuses them itself.
     *
     * @param feature the feature to test
     * @return true when the operator holds the read right
     */
    private boolean mayOpen(Feature feature) {
        if (permissionService == null) {
            return false;
        }
        return permissionService.isAllowed(currentUser.getName(), feature, CrudOption.VIEW);
    }

    /**
     * The feature keys the operator chose to hide (BO-01-04-08).
     *
     * @return the hidden keys, empty when the operator is unknown
     */
    private Set<String> hiddenKeys() {
        Employee operator = Employee.findLogin(currentUser.getName());
        return operator == null || operator.id == null
                ? Set.of() : UserFavorite.hiddenKeys(operator.id);
    }
}
