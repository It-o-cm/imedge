package com.intermarche.pos.security;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmployeeTrustedIdentityProvider}.
 * <p>
 * The provider re-validates a session-cookie principal through
 * {@link EmployeeIdentityProvider#identityOf(String, String)} with a null
 * password, which is stubbed away here (that lookup's own branches belong to
 * {@code EmployeeIdentityProviderTest}). What is proper to this class is the
 * CDI request-context activation guard, covered on both legs.
 */
class EmployeeTrustedIdentityProviderTest {

    /** The provider under test. */
    private EmployeeTrustedIdentityProvider provider;

    /** The mocked shared lookup the provider delegates to. */
    private EmployeeIdentityProvider employeeIdentityProvider;

    /** Prepares a provider wired to a mocked lookup. */
    @BeforeEach
    void setUp() {
        provider = new EmployeeTrustedIdentityProvider();
        employeeIdentityProvider = mock(EmployeeIdentityProvider.class);
        provider.employeeIdentityProvider = employeeIdentityProvider;
    }

    /**
     * Builds a mocked request context whose {@code runBlocking} runs the
     * supplied lookup immediately, synchronously.
     *
     * @return the mocked request context
     */
    private AuthenticationRequestContext syncContext() {
        AuthenticationRequestContext context = mock(AuthenticationRequestContext.class);
        when(context.runBlocking(org.mockito.ArgumentMatchers.<Supplier<SecurityIdentity>>any()))
                .thenAnswer(invocation -> {
                    Supplier<SecurityIdentity> supplier = invocation.getArgument(0);
                    return Uni.createFrom().item(supplier.get());
                });
        return context;
    }

    /**
     * Prepares the CDI request-context mock reached through {@code Arc}.
     *
     * @param container the container mock to wire {@code Arc.container()} to
     * @return the managed context mock
     */
    private ManagedContext wireRequestContext(ArcContainer container) {
        ManagedContext managedContext = mock(ManagedContext.class);
        when(container.requestContext()).thenReturn(managedContext);
        return managedContext;
    }

    /** Declares the answered request shape: the session-cookie restore. */
    @Test
    void getRequestTypeReturnsTrusted() {
        assertEquals(TrustedAuthenticationRequest.class, provider.getRequestType());
    }

    /**
     * An already-active request context runs the re-validation without
     * activating or terminating one of its own.
     */
    @Test
    void authenticateReusesAnActiveRequestContext() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(employeeIdentityProvider.identityOf(eq("jdupont"), isNull())).thenReturn(identity);
        TrustedAuthenticationRequest request = new TrustedAuthenticationRequest("jdupont");
        AuthenticationRequestContext context = syncContext();
        ArcContainer container = mock(ArcContainer.class);
        ManagedContext managedContext = wireRequestContext(container);
        when(managedContext.isActive()).thenReturn(true);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            SecurityIdentity result = provider.authenticate(request, context)
                    .await().indefinitely();
            assertSame(identity, result);
        }
        verify(managedContext, never()).activate();
        verify(managedContext, never()).terminate();
    }

    /**
     * An inactive request context is activated for the re-validation and
     * terminated afterwards, so the very next page after sign-in does not
     * bounce back to the login screen.
     */
    @Test
    void authenticateActivatesAnInactiveRequestContext() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(employeeIdentityProvider.identityOf(eq("jdupont"), isNull())).thenReturn(identity);
        TrustedAuthenticationRequest request = new TrustedAuthenticationRequest("jdupont");
        AuthenticationRequestContext context = syncContext();
        ArcContainer container = mock(ArcContainer.class);
        ManagedContext managedContext = wireRequestContext(container);
        when(managedContext.isActive()).thenReturn(false);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            SecurityIdentity result = provider.authenticate(request, context)
                    .await().indefinitely();
            assertSame(identity, result);
        }
        verify(managedContext).activate();
        verify(managedContext).terminate();
    }
}
