package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminAccountResource} (BO-01-01-01, -02, -06).
 *
 * <p>The screen is a Panache consumer over {@link Employee}: the lookups
 * resolve to {@link PanacheEntityBase} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}. No database and no Quarkus context
 * is booted; the redirect's query string carries the outcome, which is what
 * each case asserts.
 *
 * <p>Branch enumeration: the three mandatory fields (each absent in turn), a
 * login already taken by ANOTHER account and by the SAME one, a new account
 * without an initial secret (credentials generated, BO-01-01-02-06), a secret
 * shorter than the floor, an unknown id on save and on delete, a holder the
 * history names, and the role fallback; the hierarchy (BO-01-01-02,
 * BO-01-02-10): a central account, the actor's own account, an account not
 * below, a level or a role above the actor; the echelons (BO-01-01-03): valid,
 * invalid, duplicated and whole-organisation scopes; the reset of either or
 * both secrets; the printable badge; the actor's resolution.
 */
class AdminAccountResourceTest {

    /** The signed-in administrator every resource below acts as. */
    private final Employee admin = admin();

    /**
     * Builds the signed-in administrator.
     *
     * @return the ADMIN account
     */
    private static Employee admin() {
        Employee admin = new Employee();
        admin.id = 1L;
        admin.loginName = "admin";
        admin.role = Employee.EmployeeRole.ADMIN;
        admin.hierarchyLevel = 9;
        return admin;
    }

    /**
     * Builds a resource acting as a given operator, whose rank the hierarchy
     * answers as told.
     *
     * @param actor the signed-in operator, possibly null
     * @param outranks what the hierarchy answers
     * @return the resource
     */
    private AdminAccountResource resource(Employee actor, boolean outranks) {
        AdminAccountResource resource = new AdminAccountResource() {
            /**
             * Answers the account the test signs in as.
             *
             * @return the stubbed signed-in account
             */
            @Override
            Employee actor() {
                return actor;
            }
        };
        resource.permissionService = mock(com.intermarche.edge.service.PermissionService.class);
        when(resource.permissionService.outranks(any(), any())).thenReturn(outranks);
        resource.adminAccountCredentials = mock(io.quarkus.qute.Template.class);
        io.quarkus.qute.TemplateInstance sheet = mock(io.quarkus.qute.TemplateInstance.class,
                org.mockito.Mockito.RETURNS_SELF);
        when(sheet.render()).thenReturn("sheet");
        when(resource.adminAccountCredentials.data(anyString(), any())).thenReturn(sheet);
        return resource;
    }

    /**
     * Builds a resource acting as the administrator.
     *
     * @return the resource
     */
    private AdminAccountResource resource() {
        return resource(admin, true);
    }

    /**
     * Builds a form carrying everything a creation needs.
     *
     * @return the valid form
     */
    private MultivaluedMap<String, String> validForm() {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("loginName", "amartin");
        form.putSingle("lastName", "Martin");
        form.putSingle("firstName", "Alice");
        form.putSingle("email", "a.martin@magasin.fr");
        form.putSingle("secret", "4821");
        form.putSingle("badgeId", "11112222");
        form.putSingle("role", "MANAGER");
        form.putSingle("hierarchyLevel", "2");
        form.putSingle("active", "on");
        return form;
    }

    /**
     * Reads the notice the redirect carries, decoded as the browser would
     * read it — the notice is URL-encoded on the way out.
     *
     * @param response the redirect
     * @return its query string, decoded, which carries the notice
     */
    private String notice(Response response) {
        return java.net.URLDecoder.decode(response.getLocation().getQuery(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * BO-01-01-01: identifier, surname, first name and electronic address are
     * the four a creation cannot do without — each one absent refuses on its
     * own, rather than failing the commit on the entity's constraint.
     */
    @Test
    void theFourMandatoryFieldsAreMandatory() {
        for (String missing : new String[]{"loginName", "lastName", "firstName", "email"}) {
            MultivaluedMap<String, String> form = validForm();
            form.remove(missing);
            Response response = resource().save(form);
            assertTrue(notice(response).contains("obligatoires"), missing);
        }
    }

    /**
     * BO-01-01-02: a login already taken by ANOTHER account is refused, and the
     * same login carried by the account being edited is not.
     */
    @Test
    void aLoginIsTakenOnlyByAnotherAccount() {
        // A MOCK and not a real row: the valid branch persists, and a persist
        // outside a transaction would be the only thing this case proves.
        Employee other = mock(Employee.class);
        other.id = 7L;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> query = query(other);
            ms.when(() -> Employee.find("loginName", "amartin")).thenReturn(query);
            MultivaluedMap<String, String> creation = validForm();
            assertTrue(notice(resource().save(creation)).contains("déjà"));

            MultivaluedMap<String, String> itself = validForm();
            itself.putSingle("id", "7");
            ms.when(() -> Employee.findById(7L)).thenReturn(other);
            assertFalse(notice(resource().save(itself)).contains("déjà"));
        }
    }

    /**
     * BO-10-02-23: the PIN drawn for a new account has the shop's number of
     * digits — four when nothing is administered, six when the shop asks six.
     */
    @Test
    void theGeneratedPinFollowsTheAdministeredLength() {
        AdminAccountResource resource = resource();
        resource.random = mock(java.util.random.RandomGenerator.class);
        when(resource.random.nextInt(1_000_000)).thenReturn(4321);
        when(resource.random.nextInt(AdminAccountResource.PIN_BOUND)).thenReturn(42);
        assertEquals("0042", resource.newPin());
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(resource.posSettingsService.pinLength()).thenReturn(6);
        assertEquals("004321", resource.newPin());
        when(resource.posSettingsService.pinLength()).thenReturn(4);
        assertEquals("0042", resource.newPin());
    }

    /**
     * BO-01-02-06: a NEW account created without a code receives a generated
     * badge, PIN and temporary password, shown once on the sheet; a secret
     * typed must reach the floor — four characters.
     */
    @Test
    void anAccountWithoutCodeReceivesGeneratedCredentials() {
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> absent = query(null);
        AdminAccountResource resource = resource();
        resource.random = mock(java.util.random.RandomGenerator.class);
        when(resource.random.nextInt(anyInt())).thenReturn(42);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             var created = org.mockito.Mockito.mockConstruction(Employee.class)) {
            ms.when(() -> Employee.find("loginName", "amartin")).thenReturn(absent);
            ms.when(() -> Employee.find(org.mockito.ArgumentMatchers.eq("badgeId"), any(Object[].class)))
                    .thenReturn(absent);
            MultivaluedMap<String, String> without = validForm();
            without.remove("secret");
            without.remove("badgeId");
            Response sheet = resource.save(without);
            assertEquals(200, sheet.getStatus());
            assertEquals("sheet", sheet.getEntity());
            Employee built = created.constructed().get(0);
            assertEquals("00000042", built.badgeId);
            assertTrue(built.mustChangePassword);
            assertEquals(null, built.passwordChangedAt);
            org.mockito.Mockito.verify(built).setBackOfficePassword("tttttttttt");
            org.mockito.Mockito.verify(resource.adminAccountCredentials.data("holder", null)).data("pin", "0042");
            MultivaluedMap<String, String> tooShort = validForm();
            tooShort.putSingle("secret", "12");
            assertTrue(notice(resource.save(tooShort)).contains("moins"));
        }
    }

    /**
     * An unknown id is an unknown account, on the save as on the delete.
     */
    @Test
    void anUnknownAccountIsRefused() {
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> absent = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.find("loginName", "amartin")).thenReturn(absent);
            ms.when(() -> Employee.findById(42L)).thenReturn(null);
            MultivaluedMap<String, String> form = validForm();
            form.putSingle("id", "42");
            assertTrue(notice(resource().save(form)).contains("inconnu"));

            MultivaluedMap<String, String> deletion = new MultivaluedHashMap<>();
            deletion.putSingle("id", "42");
            assertTrue(notice(resource().delete(deletion)).contains("inconnu"));
            assertTrue(notice(resource().delete(new MultivaluedHashMap<>()))
                    .contains("inconnu"));
        }
    }

    /**
     * BO-01-01-02: an account the HISTORY names is not erased — it is
     * deactivated, because erasing it would orphan the journal that names it.
     */
    @Test
    void aReferencedAccountIsNotErased() {
        AdminAccountResource resource = new AdminAccountResource() {
            @Override
            boolean referenced(Employee employee) {
                return true;
            }

            /**
             * Answers the account the test signs in as.
             *
             * @return the stubbed signed-in account
             */
            @Override
            Employee actor() {
                return admin;
            }
        };
        resource.permissionService = mock(com.intermarche.edge.service.PermissionService.class);
        when(resource.permissionService.outranks(any(), any())).thenReturn(true);
        Employee account = new Employee();
        account.id = 7L;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(account);
            MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
            form.putSingle("id", "7");
            assertTrue(notice(resource.delete(form)).contains("historique"));
        }
    }

    /**
     * An absent or unknown role answers the CASHIER, which is the least any
     * account can do — never the ADMIN a typo would otherwise grant.
     */
    @Test
    void anUnknownRoleFallsBackToTheCashier() {
        AdminAccountResource resource = new AdminAccountResource();
        assertEquals(Employee.EmployeeRole.CASHIER, resource.role(null));
        assertEquals(Employee.EmployeeRole.CASHIER, resource.role("   "));
        assertEquals(Employee.EmployeeRole.CASHIER, resource.role("SORCIER"));
        assertEquals(Employee.EmployeeRole.ADMIN, resource.role("admin"));
        assertEquals(Employee.EmployeeRole.MANAGER, resource.role(" MANAGER "));
    }

    /**
     * Builds a Panache query whose {@code firstResult} answers the given row.
     *
     * @param result the row to answer, or null for none
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> query(Employee result) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> query =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }
    /** BO-01-01: new accounts receive both secrets; updates without a secret keep the existing credentials. */
    @Test
    void createAndUpdateWithoutReplacingSecret() {
        var resource = resource();
        var absent = query(null);
        try (var base = mockStatic(PanacheEntityBase.class);
             var created = org.mockito.Mockito.mockConstruction(Employee.class)) {
            base.when(() -> PanacheEntityBase.find("loginName", "amartin")).thenReturn(absent);
            base.when(() -> PanacheEntityBase.find(org.mockito.ArgumentMatchers.eq("badgeId"), any(Object[].class)))
                    .thenReturn(absent);
            assertTrue(notice(resource.save(validForm())).contains("créé"));
            Employee employee = created.constructed().getFirst();
            assertEquals("amartin", employee.loginName);
            assertTrue(employee.mustChangePassword);
            org.mockito.Mockito.verify(employee).setBackOfficePassword("4821");
            org.mockito.Mockito.verify(employee).persist();
            employee.id = 8L;
            base.when(() -> PanacheEntityBase.findById(8L)).thenReturn(employee);
            var update = validForm();
            update.putSingle("id", "8");
            update.remove("secret");
            update.putSingle("firstName", " Alicia ");
            assertTrue(notice(resource.save(update)).contains("enregistré"));
            assertEquals("Alicia", employee.firstName);
            org.mockito.Mockito.verify(employee).setBackOfficePassword("4821");
        }
    }

    /** BO-01-01-02: absent identities are unreferenced, both count legs are respected, and deletion removes profile links. */
    @Test
    void historyCountsAndDeletion() {
        var resource = resource();
        assertFalse(resource.referenced(null));
        Employee employee = mock(Employee.class);
        assertFalse(resource.referenced(employee));
        employee.id = 8L;
        try (var base = mockStatic(PanacheEntityBase.class)) {
            assertFalse(resource.referenced(employee));
            base.when(() -> PanacheEntityBase.count("employee.id = ?1", 8L)).thenReturn(1L);
            assertTrue(resource.referenced(employee));
            base.when(() -> PanacheEntityBase.count("employee.id = ?1", 8L)).thenReturn(0L);
            base.when(() -> PanacheEntityBase.findById(8L)).thenReturn(employee);
            var form = new MultivaluedHashMap<String, String>();
            form.putSingle("id", "8");
            assertTrue(notice(resource.delete(form)).contains("supprimé"));
            org.mockito.Mockito.verify(employee).delete();
            base.verify(() -> PanacheEntityBase.delete("employeeId", 8L));
        }
    }

    /** BO-01-01: searching covers each nullable searchable column and the absent/blank/unmatched needle legs. */
    @Test
    void searchEveryColumnAndNullNeedles() {
        var resource = new AdminAccountResource();
        java.util.List<Employee> employees = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            Employee employee = new Employee();
            employee.firstName = null;
            employee.lastName = null;
            employee.loginName = null;
            employee.department = null;
            if (index == 0) employee.lastName = "Needle";
            if (index == 1) employee.firstName = "Needle";
            if (index == 2) employee.loginName = "Needle";
            if (index == 3) employee.department = "Needle";
            employees.add(employee);
        }
        var query = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.list()).thenReturn(employees);
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.find("order by lastName, firstName")).thenReturn(query);
            assertEquals(5, resource.search(null).size());
            assertEquals(5, resource.search(" ").size());
            assertEquals(4, resource.search(" NEEDLE ").size());
            assertTrue(resource.search("other").isEmpty());
        }
    }

    /** BO-01-01: page projection renders nullable and populated account fields without leaking null labels. */
    @Test
    @SuppressWarnings("unchecked")
    void listProjectsNullableAndPopulatedFields() {
        var resource = resource();
        resource.adminAccounts = mock(io.quarkus.qute.Template.class);
        var view = mock(io.quarkus.qute.TemplateInstance.class);
        when(resource.adminAccounts.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        Employee first = new Employee();
        first.role = null;
        first.phone = null;
        first.department = null;
        first.language = null;
        Employee second = new Employee();
        second.phone = "0100000000";
        second.department = "Shop";
        second.language = "fr";
        second.role = Employee.EmployeeRole.MANAGER;
        second.startDate = java.time.LocalDate.of(2026, 1, 1);
        second.lastName = "Durand SA";
        second.legalEntity = true;
        var query = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.list()).thenReturn(java.util.List.of(first, second));
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.find("order by lastName, firstName")).thenReturn(query);
            assertEquals(view, resource.list(null, 1, null, true, null));
            assertEquals(view, resource.list(" ", 1, null, true, 99L));
            second.id = 5L;
            second.echelonScopes = "ENSEIGNE:ITM;TPV:POS01";
            second.authMode = null;
            second.centralManaged = true;
            base.when(() -> PanacheEntityBase.findById(5L)).thenReturn(second);
            resource.list(null, 1, null, true, 5L);
            org.mockito.ArgumentCaptor<Object> editing = org.mockito.ArgumentCaptor.forClass(Object.class);
            org.mockito.Mockito.verify(view, org.mockito.Mockito.atLeastOnce()).data(eq("editing"), editing.capture());
            AdminAccountResource.AccountForm form = (AdminAccountResource.AccountForm) editing.getValue();
            assertEquals(java.util.List.of("ENSEIGNE:ITM", "TPV:POS01"), form.scopes);
            assertEquals("ANY", form.authMode);
            assertEquals("MANAGER", form.role);
            assertEquals("2026-01-01", form.startDate);
            org.mockito.ArgumentCaptor<Object> page = org.mockito.ArgumentCaptor.forClass(Object.class);
            org.mockito.Mockito.verify(resource.adminAccounts, org.mockito.Mockito.atLeastOnce())
                    .data(eq("page"), page.capture());
            var rows = ((com.intermarche.edge.ui.journal.JournalPage<AdminAccountResource.AccountRow>) page.getValue()).rows;
            assertEquals("Tous les échelons", rows.get(0).scopes);
            assertTrue(rows.get(0).manageable);
            assertEquals(first.lastName, rows.get(0).lastName);
            assertFalse(rows.get(0).legalEntity);
            assertTrue(rows.get(1).legalEntity);
            assertEquals("Enseigne ITM, TPV POS01", rows.get(1).scopes);
            assertFalse(rows.get(1).manageable);
            assertTrue(rows.get(1).central);
            assertEquals("01/01/2026", rows.get(1).startDate);
            assertEquals("", rows.get(0).startDate);
            assertTrue(rows.get(1).legalEntity);
            assertEquals("Durand SA", rows.get(1).lastName);
            assertFalse(rows.get(0).legalEntity);
        }
        org.mockito.Mockito.verify(view, org.mockito.Mockito.times(2)).data("text", "");
        org.mockito.Mockito.verify(view).data("text", " ");
    }

    /** BO-01-01: an update cannot take a login from another existing account. */
    @Test
    void updateCannotTakeAnotherLogin() {
        Employee other = new Employee();
        other.id = 7L;
        var answer = query(other);
        var form = validForm();
        form.putSingle("id", "8");
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.find("loginName", "amartin")).thenReturn(answer);
            assertTrue(notice(resource().save(form)).contains("déjà pris"));
        }
    }

    /**
     * Builds a resource whose card template records what it binds.
     *
     * @param bound the map receiving the bound values
     * @return the resource
     */
    private AdminAccountResource cardResource(java.util.Map<String, Object> bound) {
        AdminAccountResource resource = resource();
        resource.adminSupervisorCard = mock(io.quarkus.qute.Template.class);
        io.quarkus.qute.TemplateInstance instance = mock(io.quarkus.qute.TemplateInstance.class);
        when(resource.adminSupervisorCard.data(anyString(), any())).thenAnswer(invocation -> {
            bound.put(invocation.getArgument(0), invocation.getArgument(1));
            return instance;
        });
        when(instance.data(anyString(), any())).thenAnswer(invocation -> {
            bound.put(invocation.getArgument(0), invocation.getArgument(1));
            return instance;
        });
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        return resource;
    }

    /**
     * BO-03-06-51: issuing a supervisor card to a holder without a badge draws
     * one nobody carries, marks the card and opens it to print; a holder with
     * a badge keeps it; withdrawing takes the card back and keeps the badge; an
     * unknown or absent account is refused.
     */
    @Test
    void aSupervisorCardIsIssuedAndWithdrawn() {
        AdminAccountResource resource = resource();
        resource.random = mock(java.util.random.RandomGenerator.class);
        when(resource.random.nextInt(AdminAccountResource.BADGE_BOUND)).thenReturn(42, 77);
        Employee holder = mock(Employee.class);
        holder.id = 8L;
        holder.firstName = "Alice";
        holder.lastName = "Martin";
        when(holder.getFullName()).thenReturn("Alice Martin");
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.findById(8L)).thenReturn(holder);
            var taken = query(new Employee());
            var free = query(null);
            base.when(() -> PanacheEntityBase.find("badgeId", "00000042")).thenReturn(taken);
            base.when(() -> PanacheEntityBase.find("badgeId", "00000077")).thenReturn(free);
            var form = new MultivaluedHashMap<String, String>();
            form.putSingle("id", "8");
            Response issued = resource.issueSupervisorCard(form);
            assertEquals(303, issued.getStatus());
            assertEquals("/admin/accounts/supervisor-card?id=8", issued.getLocation().toString());
            assertEquals("00000077", holder.badgeId);
            assertTrue(holder.supervisorCard);
            holder.badgeId = " ";
            resource.issueSupervisorCard(form);
            holder.badgeId = "12341234";
            resource.issueSupervisorCard(form);
            assertEquals("12341234", holder.badgeId);
            form.putSingle("action", "revoke");
            assertTrue(notice(resource.issueSupervisorCard(form)).contains("retirée à Alice Martin"));
            assertFalse(holder.supervisorCard);
            assertEquals("12341234", holder.badgeId);
            org.mockito.Mockito.verify(holder, org.mockito.Mockito.times(4)).persist();
            form.putSingle("id", "9");
            assertTrue(notice(resource.issueSupervisorCard(form)).contains("Compte inconnu"));
            form.remove("id");
            assertTrue(notice(resource.issueSupervisorCard(form)).contains("Compte inconnu"));
        }
    }

    /**
     * BO-03-06-51: the card shows the holder's name and the Code 128 of their
     * badge behind the administered supervisor prefix, trimmed or absent; no
     * card is shown for an unknown account, one without card, one without
     * badge, or no account at all.
     */
    @Test
    void theSupervisorCardIsDrawnBehindThePrefix() {
        java.util.Map<String, Object> bound = new java.util.HashMap<>();
        AdminAccountResource resource = cardResource(bound);
        when(resource.posSettingsService.supervisorBadgePrefix()).thenReturn(" SUP ", (String) null);
        Employee holder = mock(Employee.class);
        holder.supervisorCard = true;
        holder.badgeId = "12341234";
        when(holder.getFullName()).thenReturn("Alice Martin");
        Employee plain = mock(Employee.class);
        Employee badgeless = mock(Employee.class);
        badgeless.supervisorCard = true;
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.findById(8L)).thenReturn(holder);
            base.when(() -> PanacheEntityBase.findById(9L)).thenReturn(plain);
            base.when(() -> PanacheEntityBase.findById(10L)).thenReturn(badgeless);
            resource.supervisorCard(8L);
            assertEquals("Alice Martin", bound.get("holder"));
            assertEquals("SUP12341234", bound.get("code"));
            assertEquals(Code128Svg.svg("SUP12341234"), bound.get("barcode"));
            resource.supervisorCard(8L);
            assertEquals("12341234", bound.get("code"));
            for (Long id : new Long[]{9L, 10L, 11L, null}) {
                bound.clear();
                resource.supervisorCard(id);
                assertEquals(null, bound.get("holder"));
                assertEquals("", bound.get("code"));
                assertEquals("", bound.get("barcode"));
            }
        }
    }

    /**
     * BO-01-01-02, BO-01-02-10: an account the central node owns, the actor's
     * own account and an account not below the actor are not administered
     * here — on the save, the delete, the card and the reset alike.
     */
    @Test
    void theHierarchyIsEnforced() {
        Employee central = mock(Employee.class);
        central.id = 8L;
        central.centralManaged = true;
        Employee self = mock(Employee.class);
        self.id = 1L;
        Employee above = mock(Employee.class);
        above.id = 9L;
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> absent = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.find("loginName", "amartin")).thenReturn(absent);
            ms.when(() -> Employee.findById(8L)).thenReturn(central);
            ms.when(() -> Employee.findById(1L)).thenReturn(self);
            ms.when(() -> Employee.findById(9L)).thenReturn(above);
            MultivaluedMap<String, String> form = validForm();
            form.putSingle("id", "8");
            assertTrue(notice(resource().save(form)).contains("nœud central"));
            form.putSingle("id", "1");
            assertTrue(notice(resource().delete(form)).contains("propre compte"));
            form.putSingle("id", "9");
            assertTrue(notice(resource(admin, false).issueSupervisorCard(form)).contains("strictement inférieur"));
            assertTrue(notice(resource(admin, false).reset(form)).contains("strictement inférieur"));
            assertTrue(notice(resource(null, false).delete(form)).contains("strictement inférieur"));
        }
    }

    /**
     * BO-01-01-02: an account below the actor may not be raised to the actor's
     * level, nor made an administrator; within the actor's level it is saved.
     */
    @Test
    void anAccountStaysBelowItsAdministrator() {
        Employee chief = new Employee();
        chief.id = 2L;
        chief.role = Employee.EmployeeRole.MANAGER;
        chief.hierarchyLevel = 3;
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> absent = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             var created = org.mockito.Mockito.mockConstruction(Employee.class)) {
            ms.when(() -> Employee.find("loginName", "amartin")).thenReturn(absent);
            MultivaluedMap<String, String> form = validForm();
            form.putSingle("hierarchyLevel", "3");
            assertTrue(notice(resource(chief, true).save(form)).contains("strictement inférieur"));
            form.putSingle("hierarchyLevel", "2");
            form.putSingle("role", "ADMIN");
            assertTrue(notice(resource(chief, true).save(form)).contains("seul un"));
            form.putSingle("role", "CASHIER");
            form.putSingle("badgeId", "55555555");
            assertTrue(notice(resource(chief, true).save(form)).contains("créé"));
            assertEquals("55555555", created.constructed().get(2).badgeId);
        }
    }

    /**
     * BO-01-01-03: the echelons chosen are stored canonical, once, the whole
     * organisation meaning no restriction; an invalid echelon is refused.
     */
    @Test
    void theEchelonsAreStoredOrRefused() {
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> absent = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             var created = org.mockito.Mockito.mockConstruction(Employee.class)) {
            ms.when(() -> Employee.find("loginName", "amartin")).thenReturn(absent);
            ms.when(() -> Employee.find(eq("badgeId"), any(Object[].class))).thenReturn(absent);
            MultivaluedMap<String, String> form = validForm();
            form.add("scope", "REGION:IDF");
            assertTrue(notice(resource().save(form)).contains("REGION:IDF"));
            form.remove("scope");
            form.add("scope", "enseigne:ITM");
            form.add("scope", "ENSEIGNE:ITM");
            form.add("scope", "*");
            form.add("scope", "TPV_TYPE:self_checkout_ncr");
            form.putSingle("authMode", "sso");
            resource().save(form);
            Employee built = created.constructed().get(1);
            assertEquals("ENSEIGNE:ITM;TPV_TYPE:SELF_CHECKOUT_NCR", built.echelonScopes);
            assertEquals(Employee.AuthMode.SSO, built.authMode);
        }
        assertEquals(null, resource().scopes(null));
        assertEquals(null, resource().scopes(java.util.List.of("*")));
    }

    /**
     * BO-01-02-07: a Dallas key must be sixteen hexadecimal digits held by no
     * other account; no key, or the account's own, is accepted.
     */
    @Test
    void aDallasKeyIsCheckedBeforeBeingStored() {
        Employee holder = new Employee();
        holder.id = 7L;
        holder.loginName = "ines";
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> held = query(holder);
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> free = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.find("dallasKey", "01A2B3C4D5E6F708")).thenReturn(held);
            ms.when(() -> Employee.find("dallasKey", "FFFFFFFFFFFFFFFF")).thenReturn(free);
            org.junit.jupiter.api.Assertions.assertNull(resource().dallasRefusal(null, null));
            assertEquals("Clé Dallas invalide : seize chiffres hexadécimaux.", resource().dallasRefusal("XYZ", null));
            assertEquals("Cette clé Dallas est déjà attribuée à ines.", resource().dallasRefusal("01A2B3C4D5E6F708", null));
            assertEquals("Cette clé Dallas est déjà attribuée à ines.", resource().dallasRefusal("01A2B3C4D5E6F708", 8L));
            org.junit.jupiter.api.Assertions.assertNull(resource().dallasRefusal("01A2B3C4D5E6F708", 7L));
            org.junit.jupiter.api.Assertions.assertNull(resource().dallasRefusal("FFFFFFFFFFFFFFFF", null));
        }
    }

    /**
     * BO-01-02-07: the key typed on the form is stored normalized, a wrong one
     * refuses the account, and the edit form shows the key held.
     */
    @Test
    void theDallasKeyOfTheFormIsStored() {
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> absent = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             var created = org.mockito.Mockito.mockConstruction(Employee.class)) {
            ms.when(() -> Employee.find("loginName", "amartin")).thenReturn(absent);
            ms.when(() -> Employee.find("dallasKey", "01A2B3C4D5E6F708")).thenReturn(absent);
            ms.when(() -> Employee.find(eq("badgeId"), any(Object[].class))).thenReturn(absent);
            MultivaluedMap<String, String> form = validForm();
            form.putSingle("dallasKey", "123");
            assertTrue(notice(resource().save(form)).contains("Clé Dallas invalide"));
            form.putSingle("dallasKey", "01:a2:b3:c4:d5:e6:f7:08");
            resource().save(form);
            assertEquals("01A2B3C4D5E6F708", created.constructed().get(created.constructed().size() - 1).dallasKey);
        }
        Employee holder = new Employee();
        holder.dallasKey = "01A2B3C4D5E6F708";
        assertEquals("01A2B3C4D5E6F708", resource().form(holder).dallasKey);
        assertEquals("", resource().form(new Employee()).dallasKey);
    }

    /**
     * BO-01-02-03: registers typed in by identifier join the picked echelons,
     * whether declared or not; blank pieces of the typed list are skipped.
     */
    @Test
    void registersTypedInJoinThePickedEchelons() {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        assertEquals(java.util.List.of(), resource().postedScopes(form));
        form.add("scope", "PDV:07039");
        form.putSingle("terminals", ",POS07; pos08");
        assertEquals(java.util.List.of("PDV:07039", "TPV:POS07", "TPV:POS08"), resource().postedScopes(form));
        assertEquals("PDV:07039;TPV:POS07;TPV:POS08", resource().scopes(resource().postedScopes(form)));
    }

    /**
     * BO-01-02-10: an account below is reset — both secrets by default, the
     * PIN alone or the password alone — and unlocked; the new secrets are on
     * the sheet; an unknown or absent account is refused.
     */
    @Test
    void secretsAreReset() {
        AdminAccountResource resource = resource();
        resource.random = mock(java.util.random.RandomGenerator.class);
        when(resource.random.nextInt(anyInt())).thenReturn(7);
        Employee holder = mock(Employee.class);
        holder.id = 8L;
        holder.badgeId = "12341234";
        holder.failedAttempts = 3;
        holder.lockedUntil = java.time.LocalDateTime.now().plusMinutes(5);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(8L)).thenReturn(holder);
            MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
            assertTrue(notice(resource.reset(form)).contains("inconnu"));
            form.putSingle("id", "9");
            assertTrue(notice(resource.reset(form)).contains("inconnu"));
            form.putSingle("id", "8");
            Response both = resource.reset(form);
            assertEquals(200, both.getStatus());
            assertTrue(holder.password.startsWith("$2a$"));
            assertEquals(null, holder.passwordChangedAt);
            assertTrue(holder.mustChangePassword);
            assertEquals(0, holder.failedAttempts);
            assertEquals(null, holder.lockedUntil);
            org.mockito.Mockito.verify(holder).setBackOfficePassword("HHHHHHHHHH");
            holder.password = "kept";
            form.putSingle("what", "password");
            resource.reset(form);
            assertEquals("kept", holder.password);
            form.putSingle("what", "pin");
            holder.mustChangePassword = false;
            resource.reset(form);
            assertFalse(holder.mustChangePassword);
            org.mockito.Mockito.verify(holder, org.mockito.Mockito.times(2)).setBackOfficePassword("HHHHHHHHHH");
            org.mockito.Mockito.verify(holder, org.mockito.Mockito.times(3)).persist();
        }
    }

    /**
     * BO-01-02-06: the badge of an account prints without any secret; an
     * account without badge, an unknown or absent one is refused.
     */
    @Test
    void theBadgePrints() {
        AdminAccountResource resource = resource();
        Employee holder = new Employee();
        holder.badgeId = "12341234";
        Employee badgeless = new Employee();
        Employee blank = new Employee();
        blank.badgeId = " ";
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(8L)).thenReturn(holder);
            ms.when(() -> Employee.findById(9L)).thenReturn(badgeless);
            ms.when(() -> Employee.findById(10L)).thenReturn(blank);
            assertEquals(200, resource.badge(8L).getStatus());
            for (Long id : new Long[]{9L, 10L, 11L, null}) {
                assertTrue(notice(resource.badge(id)).contains("badge"));
            }
        }
        Employee none = new Employee();
        assertEquals(200, resource.credentials(none, null, null, null).getStatus());
    }

    /**
     * The refusal rules, one by one: no target and no actor asks the
     * hierarchy, a target without id is not the actor, an actor without
     * account is no administrator.
     */
    @Test
    void refusalsAreSpelledOut() {
        AdminAccountResource resource = resource(null, true);
        assertEquals(null, resource.refusal(null, null));
        Employee fresh = new Employee();
        assertEquals(null, resource.refusal(admin, fresh));
        assertEquals(null, resource.refusal(null, fresh));
    }

    /**
     * The actor is the active account of the signed-in identity: none without
     * identity, for an anonymous one or one without principal.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theActorIsTheSignedInAccount() {
        AdminAccountResource resource = new AdminAccountResource();
        assertEquals(null, resource.actor());
        resource.identity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        when(resource.identity.isAnonymous()).thenReturn(true);
        assertEquals(null, resource.actor());
        when(resource.identity.isAnonymous()).thenReturn(false);
        assertEquals(null, resource.actor());
        java.security.Principal principal = () -> "admin";
        when(resource.identity.getPrincipal()).thenReturn(principal);
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> found = query(admin);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.find("(badgeId = ?1 or loginName = ?1) and active = true", "admin")).thenReturn(found);
            assertEquals(admin, resource.actor());
        }
    }

    /**
     * The edit form of an account without start date, role or mode reads the
     * defaults; one with a mode reads it; an account the actor does not
     * outrank is listed without its actions; an operator without account
     * creates none.
     */
    @Test
    void defaultsAndUnmanageableRows() {
        AdminAccountResource resource = resource();
        Employee bare = new Employee();
        bare.role = null;
        bare.authMode = Employee.AuthMode.PASSWORD;
        AdminAccountResource.AccountForm form = resource.form(bare);
        assertEquals("", form.startDate);
        assertEquals("CASHIER", form.role);
        assertEquals("PASSWORD", form.authMode);
        assertEquals(java.util.List.of(), form.scopes);
        AdminAccountResource blind = resource(admin, false);
        blind.adminAccounts = mock(io.quarkus.qute.Template.class);
        var view = mock(io.quarkus.qute.TemplateInstance.class, org.mockito.Mockito.RETURNS_SELF);
        when(blind.adminAccounts.data(anyString(), any())).thenReturn(view);
        var query = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.list()).thenReturn(java.util.List.of(bare));
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> absent = query(null);
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.find("order by lastName, firstName")).thenReturn(query);
            blind.list(null, 1, null, true, null);
            base.when(() -> PanacheEntityBase.find("loginName", "amartin")).thenReturn(absent);
            assertTrue(notice(resource(null, true).save(validForm())).contains("strictement inférieur"));
        }
        org.mockito.ArgumentCaptor<Object> page = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(blind.adminAccounts).data(eq("page"), page.capture());
        @SuppressWarnings("unchecked")
        var rows = ((com.intermarche.edge.ui.journal.JournalPage<AdminAccountResource.AccountRow>) page.getValue()).rows;
        assertFalse(rows.get(0).manageable);
        assertEquals("PASSWORD", rows.get(0).authMode);
    }
}
