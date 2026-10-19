package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.Menu;
import com.intermarche.edge.domain.people.UserFavorite;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.PermissionService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BackOfficeMenu}, the navigation built from the
 * {@link Feature} catalog.
 * <p>
 * What they are about is the three reasons an entry disappears — nobody is
 * signed in, the profile does not grant it, the operator hid it — and the one
 * rule about headings: a menu left with no entry is dropped rather than shown
 * empty.
 * <p>
 * The store node serves the WHOLE catalog: the shop's referential and its
 * parameters are administered where they are exported from, so every feature
 * the enum declares has a screen here.
 */
class BackOfficeMenuTest {

    /**
     * Builds the navigation with a signed-in operator and a permission service
     * that answers the given verdict for every feature.
     *
     * @param login the signed-in login, or null for nobody
     * @param allowed the verdict the permission service gives
     * @return the wired navigation
     */
    private BackOfficeMenu menuFor(String login, boolean allowed) {
        BackOfficeMenu menu = new BackOfficeMenu();
        CurrentUser user = mock(CurrentUser.class);
        when(user.isAuthenticated()).thenReturn(login != null);
        when(user.getName()).thenReturn(login);
        menu.currentUser = user;
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.isAllowed(any(), any(), eq(CrudOption.VIEW))).thenReturn(allowed);
        menu.permissionService = permissions;
        return menu;
    }

    /**
     * Stubs the operator lookup and the hidden keys on an active static mock.
     *
     * @param mocked the active PanacheEntityBase static mock
     * @param operator the operator the login resolves to, or null
     * @param hidden the keys the operator has hidden
     */
    private void stubOperator(MockedStatic<PanacheEntityBase> mocked, Employee operator,
                              Set<String> hidden) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(operator);
        mocked.when(() -> Employee.find("badgeId = ?1 or loginName = ?1", "hd"))
                .thenReturn(query);
        List<UserFavorite> rows = new java.util.ArrayList<>();
        for (String key : hidden) {
            UserFavorite row = new UserFavorite();
            row.featureKey = key;
            rows.add(row);
        }
        mocked.when(() -> UserFavorite.list("employeeId", 5L)).thenReturn(rows);
    }

    /**
     * Nobody signed in: the navigation is empty. The header must not offer a
     * single heading to an anonymous visitor, and the guard is checked before
     * anything is read from the database.
     */
    @Test
    void anAnonymousVisitorSeesNoMenuAtAll() {
        BackOfficeMenu menu = menuFor(null, true);
        assertTrue(menu.getGroups().isEmpty());
    }

    /**
     * An operator granted everything sees every menu, each holding exactly the
     * features that declare it — the whole catalog, grouped.
     */
    @Test
    void anOperatorGrantedEverythingSeesTheWholeCatalogGrouped() {
        BackOfficeMenu menu = menuFor("hd", true);
        Employee operator = new Employee();
        operator.id = 5L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOperator(mocked, operator, Set.of());
            List<BackOfficeMenu.Group> groups = menu.getGroups();
            assertEquals(Menu.values().length, groups.size());
            int entries = 0;
            for (BackOfficeMenu.Group group : groups) {
                entries += group.getEntries().size();
            }
            assertEquals(Feature.values().length, entries);
            assertEquals("Paramétrage", groups.get(0).getLabel());
            assertEquals("Paramètres caisse", groups.get(0).getEntries().get(0).getLabel());
            assertEquals("/admin/settings", groups.get(0).getEntries().get(0).getPath());
        }
    }

    /**
     * An operator granted nothing sees no heading: every menu emptied out is
     * dropped, not shown opening on nothing.
     */
    @Test
    void anOperatorGrantedNothingSeesNoHeading() {
        BackOfficeMenu menu = menuFor("hd", false);
        Employee operator = new Employee();
        operator.id = 5L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOperator(mocked, operator, Set.of());
            assertTrue(menu.getGroups().isEmpty());
        }
    }

    /**
     * A feature the operator hid drops out (BO-01-04-08), and the menu holding
     * it survives as long as one of its other features remains — the two arms
     * of the emptiness test on one pass.
     */
    @Test
    void aHiddenFeatureDropsOutAndEmptiesItsMenuOnlyWhenAlone() {
        BackOfficeMenu menu = menuFor("hd", true);
        Employee operator = new Employee();
        operator.id = 5L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOperator(mocked, operator, Set.of(Feature.PROFILE.getKey()));
            List<BackOfficeMenu.Group> groups = menu.getGroups();
            BackOfficeMenu.Group administration = groups.stream()
                    .filter(group -> "Administration".equals(group.getLabel()))
                    .findFirst().orElseThrow();
            // Four remain, by name: the menu grew BO-01-01-01 (accounts),
            // BO-04-02-07 (lists) and BO-03-03-15 (images), and each of its
            // features stays independently hideable.
            assertEquals(List.of("Utilisateurs", "Comptes", "Listes", "Images"),
                    administration.getEntries().stream()
                            .map(BackOfficeMenu.Entry::getLabel).toList());
        }
    }

    /**
     * Every feature of the Administration menu hidden: the heading goes too.
     */
    @Test
    void aMenuWhoseFeaturesAreAllHiddenDisappears() {
        BackOfficeMenu menu = menuFor("hd", true);
        Employee operator = new Employee();
        operator.id = 5L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOperator(mocked, operator, Set.of(Feature.PROFILE.getKey(),
                    Feature.USER.getKey(), Feature.ACCOUNT.getKey(),
                    Feature.POS_LIST.getKey(), Feature.STORE_IMAGE.getKey()));
            List<BackOfficeMenu.Group> groups = menu.getGroups();
            assertTrue(groups.stream().noneMatch(g -> "Administration".equals(g.getLabel())));
            assertEquals(Menu.values().length - 1, groups.size());
        }
    }

    /**
     * A login resolving to no operator hides nothing rather than failing: an
     * account removed under an open session still navigates, and the rights
     * still refuse what they refuse.
     */
    @Test
    void anUnknownOperatorHidesNothing() {
        BackOfficeMenu menu = menuFor("hd", true);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOperator(mocked, null, Set.of());
            assertEquals(Menu.values().length, menu.getGroups().size());
        }
    }

    /**
     * Without a permission service nothing opens — the null arm of the guard,
     * which a hand-built instance would otherwise dereference.
     */
    @Test
    void withoutAPermissionServiceNothingOpens() {
        BackOfficeMenu menu = menuFor("hd", true);
        menu.permissionService = null;
        Employee operator = new Employee();
        operator.id = 5L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOperator(mocked, operator, Set.of());
            assertTrue(menu.getGroups().isEmpty());
        }
    }

    /**
     * Every feature names a path, and no two features name the same one: a
     * duplicated path would put one screen under two headings and leave
     * another unreachable.
     */
    @Test
    void everyFeatureNamesItsOwnPath() {
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (Feature feature : Feature.values()) {
            assertTrue(feature.getPath() != null && feature.getPath().startsWith("/"),
                    feature.name() + " names no path");
            assertTrue(paths.add(feature.getPath()),
                    feature.name() + " repeats the path " + feature.getPath());
        }
        assertEquals(Feature.values().length, paths.size());
    }

    /**
     * A null current user short-circuits the guard on its very first leg: the
     * navigation is empty and nothing is read from the database.
     */
    @Test
    void aNullCurrentUserSeesNoMenu() {
        BackOfficeMenu menu = new BackOfficeMenu();
        menu.currentUser = null;
        menu.permissionService = mock(PermissionService.class);
        assertTrue(menu.getGroups().isEmpty());
    }

    /**
     * An operator resolved but carrying no id hides nothing: the second leg of
     * the identity guard (a non-null operator with a null id) falls back to no
     * hidden keys.
     */
    @Test
    void anOperatorWithoutAnIdHidesNothing() {
        BackOfficeMenu menu = menuFor("hd", true);
        Employee operator = new Employee();
        operator.id = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOperator(mocked, operator, Set.of());
            assertEquals(Menu.values().length, menu.getGroups().size());
        }
    }
}
