package com.intermarche.edge.security;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.TechnicalEventService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.oidc.OidcSession;
import io.quarkus.vertx.http.runtime.security.PersistentLoginManager;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SsoLoginResource} (BO-01-02-01, BO-01-02-09),
 * targeting 100% branch coverage.
 *
 * <p>Branch enumeration — login: SSO disabled, no session key, the echelon
 * closing the SSO, no account matched, an account matched (session written,
 * OIDC session closed, sign-in journalled). Match: by login, by e-mail after a
 * miss, no claim, a deactivated account, a password-only account. Claims: no
 * token, no claim, a quoted, a blank and a plain value. The OIDC session that
 * cannot be closed and the journal that cannot be written are swallowed.
 */
class SsoLoginResourceTest {

    /** The session manager the resource writes the cookie with. */
    private final PersistentLoginManager manager = mock(PersistentLoginManager.class);

    /**
     * A resource over mocked collaborators, the SSO enabled, the echelon open.
     *
     * @return the resource
     */
    private SsoLoginResource resource() {
        SsoLoginResource resource = new SsoLoginResource() {
            /**
             * Answers the login manager mock instead of the configured one.
             *
             * @return the mocked login manager
             */
            @Override
            PersistentLoginManager loginManager() {
                return manager;
            }
        };
        resource.enabled = true;
        resource.claim = "preferred_username";
        resource.encryptionKey = Optional.of("dev-only-not-for-production-0123456789");
        resource.cookieName = "quarkus-credential";
        resource.timeout = Duration.ofMinutes(30);
        resource.newCookieInterval = Duration.ofMinutes(5);
        resource.cookieSameSite = "strict";
        resource.cookiePath = "/";
        resource.oidcSession = mock(OidcSession.class);
        when(resource.oidcSession.logout()).thenReturn(Uni.createFrom().voidItem());
        resource.routingContext = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.isSSL()).thenReturn(true);
        when(resource.routingContext.request()).thenReturn(request);
        resource.technicalEventService = mock(TechnicalEventService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        when(resource.posSettingsService.backOfficeSignIn()).thenReturn(Employee.AuthMode.ANY);
        return resource;
    }

    /**
     * An ID token carrying a login and an address.
     *
     * @param login the login claim, or null
     * @param email the e-mail claim, or null
     * @return the token
     */
    private JsonWebToken token(Object login, Object email) {
        JsonWebToken token = mock(JsonWebToken.class);
        when(token.getClaim("preferred_username")).thenReturn(login);
        when(token.getClaim("email")).thenReturn(email);
        return token;
    }

    /**
     * Answers a finder with one account, or none.
     *
     * @param account the account, possibly null
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Object> query(Employee account) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(account);
        return query;
    }

    /**
     * Reads where a refusal sends the visitor.
     *
     * @param response the response
     * @return its location
     */
    private String location(Response response) {
        return response.getLocation().toString();
    }

    /**
     * The SSO off on the node, without session key, or closed by the echelon,
     * sends back to the sign-in page and closes the OIDC session.
     */
    @Test
    void aClosedSsoSendsBack() {
        SsoLoginResource off = resource();
        off.enabled = false;
        assertEquals("/admin/login?sso=disabled", location(off.login()));
        verify(off.oidcSession).logout();
        SsoLoginResource keyless = resource();
        keyless.encryptionKey = Optional.empty();
        assertEquals("/admin/login?sso=disabled", location(keyless.login()));
        SsoLoginResource closed = resource();
        when(closed.posSettingsService.backOfficeSignIn()).thenReturn(Employee.AuthMode.PASSWORD);
        assertEquals("/admin/login?sso=disabled", location(closed.login()));
        verify(manager, never()).save(any(String.class), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /**
     * An identity no admitted account matches is refused; a matched one gets
     * the back-office session, the OIDC session closed and the sign-in
     * journalled.
     */
    @Test
    void aMatchedAccountGetsItsSession() {
        Employee marie = new Employee();
        marie.loginName = "mcurie";
        marie.badgeId = "11111111";
        PanacheQuery<Object> found = query(marie);
        PanacheQuery<Object> none = query(null);
        SsoLoginResource refused = resource();
        JsonWebToken stranger = token("nobody", "nobody@x.test");
        when(refused.oidcSession.getIdToken()).thenReturn(stranger);
        SsoLoginResource accepted = resource();
        JsonWebToken known = token("mcurie", null);
        when(accepted.oidcSession.getIdToken()).thenReturn(known);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class,
                org.mockito.Answers.RETURNS_DEEP_STUBS);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            tx.when(() -> QuarkusTransaction.requiringNew().call(any()))
                    .thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());
            panache.when(() -> PanacheEntityBase.find("loginName", "nobody")).thenReturn(none);
            panache.when(() -> PanacheEntityBase.find("email", "nobody@x.test")).thenReturn(none);
            assertEquals("/admin/login?sso=refused", location(refused.login()));
            panache.when(() -> PanacheEntityBase.find("loginName", "mcurie")).thenReturn(found);
            Response page = accepted.login();
            assertEquals(200, page.getStatus());
            assertTrue(((String) page.getEntity()).contains("url=/admin"));
            verify(manager).save("mcurie", accepted.routingContext, "quarkus-credential", null, true);
            verify(accepted.oidcSession).logout();
            verify(accepted.technicalEventService).log(TechnicalEvent.EventType.BACK_OFFICE_SIGNED_IN,
                    "connexion SSO mcurie", "11111111");
        }
    }

    /**
     * An account is matched by login, then by address; a deactivated or a
     * password-only account is not admitted, nor an identity without claim.
     */
    @Test
    void theAccountIsMatched() {
        Employee byMail = new Employee();
        byMail.loginName = "pl";
        Employee inactive = new Employee();
        inactive.active = false;
        Employee passwordOnly = new Employee();
        passwordOnly.authMode = Employee.AuthMode.PASSWORD;
        PanacheQuery<Object> none = query(null);
        PanacheQuery<Object> mail = query(byMail);
        PanacheQuery<Object> off = query(inactive);
        PanacheQuery<Object> local = query(passwordOnly);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("loginName", "x")).thenReturn(none);
            panache.when(() -> PanacheEntityBase.find("email", "p@x.test")).thenReturn(mail);
            panache.when(() -> PanacheEntityBase.find("loginName", "off")).thenReturn(off);
            panache.when(() -> PanacheEntityBase.find("loginName", "local")).thenReturn(local);
            SsoLoginResource resource = resource();
            assertSame(byMail, resource.match("x", "p@x.test"));
            assertSame(byMail, resource.match(null, "p@x.test"));
            assertNull(resource.match("x", null));
            assertNull(resource.match(null, null));
            assertNull(resource.match("off", null));
            assertNull(resource.match("local", null));
        }
    }

    /**
     * A claim is read as text, its JSON quotes removed; an absent token or
     * claim, or a blank one, reads as nothing.
     */
    @Test
    void claimsAreRead() {
        assertNull(SsoLoginResource.claimValue(null, "email"));
        JsonWebToken token = token(null, " \"p@x.test\" ");
        assertNull(SsoLoginResource.claimValue(token, "preferred_username"));
        assertEquals("p@x.test", SsoLoginResource.claimValue(token, "email"));
        assertNull(SsoLoginResource.claimValue(token("  ", null), "preferred_username"));
        assertEquals("\"", SsoLoginResource.claimValue(token("\"", null), "preferred_username"));
        assertEquals("mcurie", SsoLoginResource.claimValue(token("mcurie", null), "preferred_username"));
        assertEquals("\"a", SsoLoginResource.claimValue(token("\"a", null), "preferred_username"));
    }

    /**
     * A session that cannot be closed and a journal that cannot be written
     * never stop the sign-in; the real session manager is built from the
     * form-authentication settings.
     */
    @Test
    void failuresAroundTheSignInAreSwallowed() {
        SsoLoginResource resource = resource();
        doThrow(new IllegalStateException("gone")).when(resource.oidcSession).logout();
        resource.closeOidcSession();
        Employee account = new Employee();
        account.loginName = "a";
        doThrow(new IllegalStateException("db")).when(resource.technicalEventService)
                .log(any(), any(), any());
        resource.journal(account);
        SsoLoginResource real = new SsoLoginResource();
        real.encryptionKey = Optional.of("dev-only-not-for-production-0123456789");
        real.cookieName = "quarkus-credential";
        real.timeout = Duration.ofMinutes(30);
        real.newCookieInterval = Duration.ofMinutes(5);
        real.cookieSameSite = "strict";
        real.cookiePath = "/";
        assertNotNull(real.loginManager());
    }
}
