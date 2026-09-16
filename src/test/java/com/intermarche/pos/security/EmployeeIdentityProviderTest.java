package com.intermarche.pos.security;

import com.intermarche.pos.domain.people.Employee;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.ManagedContext;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.PasswordCredential;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmployeeIdentityProvider}.
 * <p>
 * {@code identityOf} is exercised directly (it is package-private): every
 * refusal branch, the password-check short-circuit and the ADMIN-implies-
 * MANAGER role expansion. {@code authenticate} is exercised through a spy
 * that stubs {@code identityOf} away, so it isolates the two branches that
 * belong to it alone: the password-credential null guard and the CDI
 * request-context activation guard. Panache and {@code QuarkusTransaction}
 * are intercepted with static mocks, per the plain-{@code mvn test} contract.
 */
class EmployeeIdentityProviderTest {

    /** The provider under test for the identityOf branches. */
    private EmployeeIdentityProvider provider;

    /** Prepares a fresh provider before each test. */
    @BeforeEach
    void setUp() {
        provider = new EmployeeIdentityProvider();
    }

    /**
     * Builds an employee with back-office access, ready to sign in.
     *
     * @param role the role to assign
     * @return the configured employee
     */
    private Employee backOfficeEmployee(Employee.EmployeeRole role) {
        Employee employee = new Employee();
        employee.loginName = "jdupont";
        employee.firstName = "Jean";
        employee.lastName = "Dupont";
        employee.active = true;
        employee.role = role;
        employee.setBackOfficePassword("correct-horse");
        return employee;
    }

    /**
     * Stubs {@code Employee.find("loginName", "jdupont")} to resolve the given
     * employee and lets {@code identityOf} run inside the stubbed static
     * context.
     *
     * @param employee the employee the lookup must return, possibly null
     * @param password the password presented to {@code identityOf}
     * @return the identity {@code identityOf} resolved
     */
    @SuppressWarnings("unchecked")
    private SecurityIdentity identityOfWithLookup(Employee employee, String password) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<QuarkusTransaction> tx =
                     mockStatic(QuarkusTransaction.class, Answers.RETURNS_DEEP_STUBS)) {
            PanacheQuery<Employee> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(employee);
            panache.when(() -> Employee.find("loginName", "jdupont")).thenReturn(query);
            tx.when(() -> QuarkusTransaction.requiringNew().call(any(Callable.class)))
                    .thenAnswer(invocation -> {
                        Callable<?> callable = invocation.getArgument(0);
                        try {
                            return callable.call();
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            return provider.identityOf("jdupont", password);
        }
    }

    /**
     * Declares the answered request shape: the form login and HTTP Basic
     * both produce this one.
     */
    @Test
    void getRequestTypeReturnsUsernamePassword() {
        assertEquals(UsernamePasswordAuthenticationRequest.class, provider.getRequestType());
    }

    /** A null login name is refused before any lookup runs. */
    @Test
    void identityOfThrowsWhenLoginNameIsNull() {
        assertThrows(AuthenticationFailedException.class, () -> provider.identityOf(null, "x"));
    }

    /** A blank login name is refused before any lookup runs. */
    @Test
    void identityOfThrowsWhenLoginNameIsBlank() {
        assertThrows(AuthenticationFailedException.class, () -> provider.identityOf("   ", "x"));
    }

    /** An unknown login name is refused. */
    @Test
    void identityOfThrowsWhenEmployeeIsUnknown() {
        assertThrows(AuthenticationFailedException.class,
                () -> identityOfWithLookup(null, "x"));
    }

    /** A deactivated account is refused, whatever the password. */
    @Test
    void identityOfThrowsWhenEmployeeIsInactive() {
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        employee.active = false;
        assertThrows(AuthenticationFailedException.class,
                () -> identityOfWithLookup(employee, "correct-horse"));
    }

    /** An account locked out at the till is refused at the back office too. */
    @Test
    void identityOfThrowsWhenEmployeeIsLocked() {
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        employee.lockedUntil = java.time.LocalDateTime.now().plusMinutes(5);
        assertThrows(AuthenticationFailedException.class,
                () -> identityOfWithLookup(employee, "correct-horse"));
    }

    /** An employee with no back-office password has no back office. */
    @Test
    void identityOfThrowsWhenEmployeeHasNoBackOfficeAccess() {
        Employee employee = new Employee();
        employee.loginName = "jdupont";
        employee.active = true;
        employee.role = Employee.EmployeeRole.CASHIER;
        assertThrows(AuthenticationFailedException.class,
                () -> identityOfWithLookup(employee, "anything"));
    }

    /** A wrong password is refused when one is presented. */
    @Test
    void identityOfThrowsWhenPasswordIsWrong() {
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        assertThrows(AuthenticationFailedException.class,
                () -> identityOfWithLookup(employee, "wrong-password"));
    }

    /** The correct password builds an identity carrying the employee's role. */
    @Test
    void identityOfBuildsIdentityWithCorrectPassword() {
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        SecurityIdentity identity = identityOfWithLookup(employee, "correct-horse");
        assertEquals("jdupont", identity.getPrincipal().getName());
        assertTrue(identity.hasRole("CASHIER"));
        assertFalse(identity.hasRole("MANAGER"));
        assertEquals("Jean Dupont", identity.<String>getAttribute("fullName"));
        assertFalse(identity.<Boolean>getAttribute("mustChangePassword"));
    }

    /**
     * A null password skips the check entirely — the path taken by
     * {@link EmployeeTrustedIdentityProvider} to re-validate a session
     * cookie.
     */
    @Test
    void identityOfBuildsIdentityWhenPasswordIsNull() {
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        SecurityIdentity identity = identityOfWithLookup(employee, null);
        assertEquals("jdupont", identity.getPrincipal().getName());
    }

    /** The ADMIN role is expanded to also grant MANAGER. */
    @Test
    void identityOfGrantsManagerToAdmin() {
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.ADMIN);
        SecurityIdentity identity = identityOfWithLookup(employee, "correct-horse");
        assertTrue(identity.hasRole("ADMIN"));
        assertTrue(identity.hasRole("MANAGER"));
    }

    /** The forced-change attribute is carried through when it is set. */
    @Test
    void identityOfCarriesMustChangePassword() {
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.MANAGER);
        employee.mustChangePassword = true;
        SecurityIdentity identity = identityOfWithLookup(employee, "correct-horse");
        assertTrue(identity.<Boolean>getAttribute("mustChangePassword"));
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
     * Prepares the CDI request-context mock reached through {@code Arc},
     * and returns it so the test can drive its {@code isActive} answer.
     *
     * @param container the container mock to wire {@code Arc.container()} to
     * @return the managed context mock
     */
    private ManagedContext wireRequestContext(ArcContainer container) {
        ManagedContext managedContext = mock(ManagedContext.class);
        when(container.requestContext()).thenReturn(managedContext);
        return managedContext;
    }

    /**
     * A presented password is extracted from the credential and handed to
     * {@code identityOf}; an already-active request context runs the lookup
     * without activating or terminating one.
     */
    @Test
    void authenticateExtractsPasswordAndReusesActiveContext() {
        EmployeeIdentityProvider spyProvider = spy(new EmployeeIdentityProvider());
        SecurityIdentity identity = mock(SecurityIdentity.class);
        doReturn(identity).when(spyProvider).identityOf(anyString(), nullable(String.class));
        UsernamePasswordAuthenticationRequest request = new UsernamePasswordAuthenticationRequest(
                "jdupont", new PasswordCredential("secret".toCharArray()));
        AuthenticationRequestContext context = syncContext();
        ArcContainer container = mock(ArcContainer.class);
        ManagedContext managedContext = wireRequestContext(container);
        when(managedContext.isActive()).thenReturn(true);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            SecurityIdentity result = spyProvider.authenticate(request, context)
                    .await().indefinitely();
            assertSame(identity, result);
        }
        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        verify(spyProvider).identityOf(eq("jdupont"), passwordCaptor.capture());
        assertEquals("secret", passwordCaptor.getValue());
        verify(managedContext, never()).activate();
        verify(managedContext, never()).terminate();
    }

    /**
     * A request with no credential hands a null password to
     * {@code identityOf}; an inactive request context is activated for the
     * call and terminated afterwards.
     */
    @Test
    void authenticateHandlesMissingPasswordAndActivatesInactiveContext() {
        EmployeeIdentityProvider spyProvider = spy(new EmployeeIdentityProvider());
        SecurityIdentity identity = mock(SecurityIdentity.class);
        doReturn(identity).when(spyProvider).identityOf(anyString(), nullable(String.class));
        UsernamePasswordAuthenticationRequest request =
                new UsernamePasswordAuthenticationRequest("jdupont", null);
        AuthenticationRequestContext context = syncContext();
        ArcContainer container = mock(ArcContainer.class);
        ManagedContext managedContext = wireRequestContext(container);
        when(managedContext.isActive()).thenReturn(false);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            SecurityIdentity result = spyProvider.authenticate(request, context)
                    .await().indefinitely();
            assertSame(identity, result);
        }
        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        verify(spyProvider).identityOf(eq("jdupont"), passwordCaptor.capture());
        assertNull(passwordCaptor.getValue());
        verify(managedContext).activate();
        verify(managedContext).terminate();
    }
}
