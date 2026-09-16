package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.security.Principal;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminAuthResource}, covering the sign-in page
 * ({@code loginPage}), sign-out ({@code logout}), the password-change page
 * ({@code passwordPage}) and the back-office password change
 * ({@code changePassword}) with its journaling of a {@code PASSWORD_CHANGED}
 * event (BO-04-01-29).
 * <p>
 * The resource verifies and rewrites the back-office credential inside a
 * {@code QuarkusTransaction.requiringNew().call(...)} block; under plain
 * {@code mvn test} there is no transaction manager, so the static
 * {@link io.quarkus.narayana.jta.QuarkusTransaction} is intercepted with
 * {@link org.mockito.Mockito#mockStatic} and its callable simply run, and the
 * {@link Employee} finder resolves to {@link PanacheEntityBase} and is
 * intercepted the same way. The injected {@link TechnicalEventService} and
 * {@link SecurityIdentity} are mocks, and the Qute templates are mocked with
 * their {@code data(...)} chain stubbed to a recognizable view so the returned
 * instance and the passed data can be asserted. Every validation gate and both
 * arms of every guard have their own case.
 */
class AdminAuthResourceTest {

    /**
     * Builds a resource with a mocked emitter and a mocked identity resolving
     * to the given back-office login name.
     *
     * @param loginName the signed-in principal name
     * @return the wired resource
     */
    private AdminAuthResource newResource(String loginName) {
        AdminAuthResource resource = new AdminAuthResource();
        resource.technicalEventService = mock(TechnicalEventService.class);
        resource.identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(loginName);
        when(resource.identity.getPrincipal()).thenReturn(principal);
        return resource;
    }

    /**
     * Runs {@code changePassword} with the {@link io.quarkus.narayana.jta.QuarkusTransaction}
     * static intercepted so the transactional callable is executed in place and
     * its result returned, and the {@link Employee} finder stubbed to the given
     * employee (or none).
     *
     * @param resource the resource under test
     * @param loginName the signed-in login name the finder keys on
     * @param employee the employee the finder returns, or null
     * @param current the current password submitted
     * @param renewed the new password submitted
     * @param confirmation the confirmation submitted
     * @return the resource response
     */
    private Response run(AdminAuthResource resource, String loginName, Employee employee,
                         String current, String renewed, String confirmation) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<io.quarkus.narayana.jta.QuarkusTransaction> tx =
                     mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class,
                             org.mockito.Answers.RETURNS_DEEP_STUBS)) {
            PanacheQuery<Employee> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(employee);
            panache.when(() -> Employee.find("loginName", loginName)).thenReturn(query);
            tx.when(() -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .call(any()))
                    .thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());
            return resource.changePassword(current, renewed, confirmation);
        }
    }

    /**
     * With no {@code error} query param the sign-in page carries a null message
     * (null arm of the error ternary) and the raw notice, returning the rendered
     * template instance.
     */
    @Test
    void loginPagePassesNullMessageWhenNoError() {
        AdminAuthResource resource = new AdminAuthResource();
        Template login = mock(Template.class);
        resource.adminLogin = login;
        TemplateInstance withError = mock(TemplateInstance.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(login.data("error", null)).thenReturn(withError);
        when(withError.data("notice", "Vous êtes déconnecté.")).thenReturn(view);
        TemplateInstance result = resource.loginPage(null, "Vous êtes déconnecté.");
        assertSame(view, result);
        verify(login).data("error", null);
        verify(withError).data("notice", "Vous êtes déconnecté.");
    }

    /**
     * With an {@code error} query param the sign-in page carries the fixed
     * rejection message (non-null arm of the error ternary) and a null notice,
     * returning the rendered template instance.
     */
    @Test
    void loginPagePassesRejectionMessageWhenError() {
        AdminAuthResource resource = new AdminAuthResource();
        Template login = mock(Template.class);
        resource.adminLogin = login;
        TemplateInstance withError = mock(TemplateInstance.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(login.data("error",
                "Identifiant ou code incorrect, ou compte désactivé ou verrouillé."))
                .thenReturn(withError);
        when(withError.data("notice", null)).thenReturn(view);
        TemplateInstance result = resource.loginPage("1", null);
        assertSame(view, result);
        verify(login).data("error",
                "Identifiant ou code incorrect, ou compte désactivé ou verrouillé.");
        verify(withError).data("notice", null);
    }

    /**
     * Signing out redirects to the sign-in page and carries a cleared, expired
     * session cookie.
     */
    @Test
    void logoutClearsSessionCookieAndRedirects() {
        AdminAuthResource resource = new AdminAuthResource();
        Response response = resource.logout();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/login"));
        NewCookie cleared = response.getCookies().get("quarkus-credential");
        assertEquals("", cleared.getValue());
        assertEquals(0, cleared.getMaxAge());
    }

    /**
     * A missing forced-change attribute (getAttribute returns null) short-circuits
     * the compound and marks the page not imposed (null arm of {@code imposed != null}).
     */
    @Test
    void passwordPageWithoutAttributeMarksNotImposed() {
        AdminAuthResource resource = newResource("mcurie");
        when(resource.identity.getAttribute("mustChangePassword")).thenReturn(null);
        Template pwd = mock(Template.class);
        resource.adminPassword = pwd;
        TemplateInstance withError = mock(TemplateInstance.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(pwd.data("error", "boom")).thenReturn(withError);
        when(withError.data("imposed", false)).thenReturn(view);
        TemplateInstance result = resource.passwordPage("boom");
        assertSame(view, result);
        verify(withError).data("imposed", false);
    }

    /**
     * A present but false forced-change flag marks the page not imposed (non-null
     * arm of {@code imposed != null}, false arm of {@code imposed}).
     */
    @Test
    void passwordPageWithFalseAttributeMarksNotImposed() {
        AdminAuthResource resource = newResource("mcurie");
        when(resource.identity.getAttribute("mustChangePassword")).thenReturn(Boolean.FALSE);
        Template pwd = mock(Template.class);
        resource.adminPassword = pwd;
        TemplateInstance withError = mock(TemplateInstance.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(pwd.data("error", null)).thenReturn(withError);
        when(withError.data("imposed", false)).thenReturn(view);
        TemplateInstance result = resource.passwordPage(null);
        assertSame(view, result);
        verify(withError).data("imposed", false);
    }

    /**
     * A present, true forced-change flag marks the page imposed (non-null arm of
     * {@code imposed != null}, true arm of {@code imposed}).
     */
    @Test
    void passwordPageWithTrueAttributeMarksImposed() {
        AdminAuthResource resource = newResource("mcurie");
        when(resource.identity.getAttribute("mustChangePassword")).thenReturn(Boolean.TRUE);
        Template pwd = mock(Template.class);
        resource.adminPassword = pwd;
        TemplateInstance withError = mock(TemplateInstance.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(pwd.data("error", null)).thenReturn(withError);
        when(withError.data("imposed", true)).thenReturn(view);
        TemplateInstance result = resource.passwordPage(null);
        assertSame(view, result);
        verify(withError).data("imposed", true);
    }

    /**
     * A valid change on a verifiable account rewrites the credential, clears the
     * forced-change flag, journals a {@code PASSWORD_CHANGED} event by badge and
     * redirects to the sign-in page (applied arm).
     */
    @Test
    void changePasswordAppliesRewritesAndJournalsByBadge() {
        AdminAuthResource resource = newResource("mcurie");
        Employee employee = mock(Employee.class);
        employee.badgeId = "11111111";
        when(employee.verifyBackOfficePassword("oldsecret")).thenReturn(true);
        Response response = run(resource, "mcurie", employee, "oldsecret", "newsecret", "newsecret");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/login"));
        verify(employee).setBackOfficePassword("newsecret");
        assertEquals(false, employee.mustChangePassword);
        verify(resource.technicalEventService).log(TechnicalEvent.EventType.PASSWORD_CHANGED, null, "11111111");
    }

    /**
     * A null new password is refused up front (null arm of the length guard),
     * without touching the transaction or the journal.
     */
    @Test
    void changePasswordRefusesNullPassword() {
        AdminAuthResource resource = newResource("mcurie");
        Response response = resource.changePassword("oldsecret", null, "oldsecret");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/password"));
        verify(resource.technicalEventService, never()).log(any(), any());
    }

    /**
     * A new password shorter than the minimum is refused up front (short arm),
     * without touching the transaction or the journal.
     */
    @Test
    void changePasswordRefusesShortPassword() {
        AdminAuthResource resource = newResource("mcurie");
        Response response = resource.changePassword("oldsecret", "short", "short");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/password"));
        verify(resource.technicalEventService, never()).log(any(), any());
    }

    /**
     * A new password not matching its confirmation is refused (mismatch arm).
     */
    @Test
    void changePasswordRefusesConfirmationMismatch() {
        AdminAuthResource resource = newResource("mcurie");
        Response response = resource.changePassword("oldsecret", "newsecret", "different");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/password"));
        verify(resource.technicalEventService, never()).log(any(), any());
    }

    /**
     * A new password equal to the current one is refused (same-as-current arm).
     */
    @Test
    void changePasswordRefusesSameAsCurrent() {
        AdminAuthResource resource = newResource("mcurie");
        Response response = resource.changePassword("newsecret", "newsecret", "newsecret");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/password"));
        verify(resource.technicalEventService, never()).log(any(), any());
    }

    /**
     * A wrong current password fails the verification inside the transaction, so
     * the change is not applied, nothing is journaled and the form refuses
     * (not-applied arm).
     */
    @Test
    void changePasswordRefusesWrongCurrent() {
        AdminAuthResource resource = newResource("mcurie");
        Employee employee = mock(Employee.class);
        when(employee.verifyBackOfficePassword("wrong")).thenReturn(false);
        Response response = run(resource, "mcurie", employee, "wrong", "newsecret", "newsecret");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/password"));
        verify(employee, never()).setBackOfficePassword(any());
        verify(resource.technicalEventService, never()).log(any(), any());
    }

    /**
     * An unknown account (finder returns null) is treated as a failed
     * verification: not applied, nothing journaled (employee-null arm).
     */
    @Test
    void changePasswordRefusesUnknownAccount() {
        AdminAuthResource resource = newResource("ghost");
        Response response = run(resource, "ghost", null, "oldsecret", "newsecret", "newsecret");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/password"));
        verify(resource.technicalEventService, never()).log(any(), any());
    }
}
