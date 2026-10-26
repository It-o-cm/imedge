package com.intermarche.edge.security;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.service.TechnicalEventService;
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

    /** The journal the accepted sign-ins are expected on (BO-04-03-04). */
    private TechnicalEventService journal;

    /** Prepares a fresh provider before each test. */
    @BeforeEach
    void setUp() {
        provider = new EmployeeIdentityProvider();
        journal = mock(TechnicalEventService.class);
        provider.technicalEventService = journal;
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
     * An accepted sign-in is journalised, naming the operator by badge and
     * carrying the login name in the detail — never the secret
     * ({@code BO-04-03-04}).
     */
    @Test
    void identityOfJournalisesAnAcceptedSignIn() {
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        employee.badgeId = "007";
        identityOfWithLookup(employee, "correct-horse");
        verify(journal).log(eq(TechnicalEvent.EventType.BACK_OFFICE_SIGNED_IN),
                eq("connexion jdupont"), eq("007"));
    }

    /**
     * A journal that refuses the write does not refuse the sign-in: the
     * operator has proved who they are ({@code BO-04-03-04}).
     */
    @Test
    void identityOfSignsInEvenWhenTheJournalFails() {
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        org.mockito.Mockito.doThrow(new IllegalStateException("journal down"))
                .when(journal).log(any(TechnicalEvent.EventType.class), anyString(), nullable(String.class));
        SecurityIdentity identity = identityOfWithLookup(employee, "correct-horse");
        assertEquals("jdupont", identity.getPrincipal().getName());
    }

    /**
     * A restored session cookie is not an arrival: the null-password path
     * writes nothing to the journal ({@code BO-04-03-04}).
     */
    @Test
    void identityOfJournalisesNothingWhenPasswordIsNull() {
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        identityOfWithLookup(employee, null);
        verify(journal, never()).log(any(TechnicalEvent.EventType.class), anyString(), nullable(String.class));
    }

    /**
     * A refused sign-in writes nothing to the journal: BO-04-03-04 asks for
     * the successful connections, and a wrong password already has its own
     * trace in the failure counter.
     */
    @Test
    void identityOfJournalisesNothingWhenPasswordIsWrong() {
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        assertThrows(AuthenticationFailedException.class,
                () -> identityOfWithLookup(employee, "wrong-password"));
        verify(journal, never()).log(any(TechnicalEvent.EventType.class), anyString(), nullable(String.class));
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
    /** BO-01-01: transactional failure counters cover a vanished row, a first failure and the lockout threshold. */
    @Test
    void coverageFailureCountersAndLockout() throws Exception {
        provider.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(provider.posSettingsService.backOfficeMaxFailures()).thenReturn(2);
        when(provider.posSettingsService.backOfficeLockoutMinutes()).thenReturn(15);
        Employee account = new Employee();
        account.id = 9L;
        var count = EmployeeIdentityProvider.class.getDeclaredMethod("countFailure", Long.class);
        count.setAccessible(true);
        try (var base = mockStatic(PanacheEntityBase.class);
             var tx = mockStatic(QuarkusTransaction.class, Answers.RETURNS_DEEP_STUBS)) {
            var runner = QuarkusTransaction.requiringNew();
            org.mockito.Mockito.doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                    .when(runner).run(any(Runnable.class));
            count.invoke(provider, 9L);
            base.when(() -> PanacheEntityBase.findById(9L)).thenReturn(account);
            count.invoke(provider, 9L);
            assertEquals(1, account.failedAttempts);
            count.invoke(provider, 9L);
            assertEquals(0, account.failedAttempts);
            org.junit.jupiter.api.Assertions.assertNotNull(account.lockedUntil);
            when(provider.posSettingsService.backOfficeLockoutMinutes()).thenThrow(new IllegalStateException("settings unavailable"));
            account.failedAttempts = 1;
            count.invoke(provider, 9L);
            assertEquals(0, account.failedAttempts);
            org.junit.jupiter.api.Assertions.assertTrue(account.lockedUntil.isAfter(java.time.LocalDateTime.now().plusMinutes(14)));
        }
    }

    /** BO-01-01: successful authentication clears only positive counters of an account that still exists. */
    @Test
    void coverageClearFailureCounterNullAndZeroLegs() throws Exception {
        Employee account = new Employee();
        var clear = EmployeeIdentityProvider.class.getDeclaredMethod("clearFailures", Long.class);
        clear.setAccessible(true);
        try (var base = mockStatic(PanacheEntityBase.class);
             var tx = mockStatic(QuarkusTransaction.class, Answers.RETURNS_DEEP_STUBS)) {
            var runner = QuarkusTransaction.requiringNew();
            org.mockito.Mockito.doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                    .when(runner).run(any(Runnable.class));
            clear.invoke(provider, 9L);
            base.when(() -> PanacheEntityBase.findById(9L)).thenReturn(account);
            clear.invoke(provider, 9L);
            assertEquals(0, account.failedAttempts);
            account.failedAttempts = 2;
            clear.invoke(provider, 9L);
            assertEquals(0, account.failedAttempts);
        }
    }

    /** BO-01-02-12: an expired secret independently raises the password-change flag when no forced change was set. */
    @Test
    void coverageExpiredSecretRequiresChange() {
        provider.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(provider.posSettingsService.passwordExpiryDays()).thenReturn(1);
        Employee employee = backOfficeEmployee(Employee.EmployeeRole.MANAGER);
        employee.mustChangePassword = false;
        employee.passwordChangedAt = java.time.LocalDateTime.now().minusDays(2);
        assertEquals(true, identityOfWithLookup(employee, null).getAttribute("mustChangePassword"));
    }

    /** BO-01-01-01: unavailable counter transactions are contained, retaining the original password decision. */
    @Test
    void counterStorageFailuresDoNotEscapeAuthenticationHelpers() throws Exception {
        provider.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(provider.posSettingsService.backOfficeMaxFailures()).thenReturn(2);
        try (var transactions = mockStatic(QuarkusTransaction.class)) {
            transactions.when(QuarkusTransaction::requiringNew).thenThrow(new IllegalStateException("database unavailable"));
            var count = EmployeeIdentityProvider.class.getDeclaredMethod("countFailure", Long.class);
            count.setAccessible(true);
            var clear = EmployeeIdentityProvider.class.getDeclaredMethod("clearFailures", Long.class);
            clear.setAccessible(true);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> count.invoke(provider, 9L));
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> clear.invoke(provider, 9L));
            transactions.verify(QuarkusTransaction::requiringNew, org.mockito.Mockito.times(2));
        }
    }


    /**
     * BO-01-02-09: an account administered as SSO only, or a node whose
     * echelon is SSO only, opens no password door; an unreadable echelon
     * parameter keeps it open.
     */
    @Test
    void aPasswordDoorClosedBySsoIsRefused() {
        Employee ssoOnly = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        ssoOnly.authMode = Employee.AuthMode.SSO;
        assertThrows(AuthenticationFailedException.class, () -> identityOfWithLookup(ssoOnly, "correct-horse"));
        provider.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(provider.posSettingsService.backOfficeSignIn()).thenReturn(Employee.AuthMode.SSO);
        Employee any = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        assertThrows(AuthenticationFailedException.class, () -> identityOfWithLookup(any, "correct-horse"));
        when(provider.posSettingsService.backOfficeSignIn()).thenThrow(new IllegalStateException("unreadable"));
        assertEquals("jdupont", identityOfWithLookup(any, "correct-horse").getPrincipal().getName());
    }

    /**
     * BO-01-02-01: a session opened through the SSO is restored without a
     * local password for an account admitting the SSO, never for a
     * password-only one.
     */
    @Test
    void anSsoSessionIsRestoredWithoutLocalPassword() {
        Employee sso = new Employee();
        sso.loginName = "jdupont";
        sso.firstName = "Jean";
        sso.lastName = "Dupont";
        sso.active = true;
        sso.role = Employee.EmployeeRole.CASHIER;
        sso.authMode = Employee.AuthMode.SSO;
        sso.mustChangePassword = true;
        SecurityIdentity identity = identityOfWithLookup(sso, null);
        assertFalse(identity.<Boolean>getAttribute("mustChangePassword"));
        sso.authMode = Employee.AuthMode.PASSWORD;
        assertThrows(AuthenticationFailedException.class, () -> identityOfWithLookup(sso, null));
    }

    /**
     * The forced change is demanded of a local account, never on a store of
     * an account the central node owns (BO-01-01-03).
     */
    @Test
    void theForcedChangeSparesCentralAccounts() {
        Employee local = backOfficeEmployee(Employee.EmployeeRole.CASHIER);
        local.mustChangePassword = true;
        assertTrue(identityOfWithLookup(local, "correct-horse").<Boolean>getAttribute("mustChangePassword"));
        local.centralManaged = true;
        assertFalse(identityOfWithLookup(local, "correct-horse").<Boolean>getAttribute("mustChangePassword"));
    }
}
