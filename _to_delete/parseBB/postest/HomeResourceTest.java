package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModState;
import com.intermarche.pos.ui.fidelity.FidelityState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.payment.MoneticsDegradedService;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HomeResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, three services
 * ({@link HomeService}, {@link TicketService}, {@link HardwareService}) and five
 * Qute {@link Template}s. Every collaborator is a Mockito mock: templates echo a
 * {@link TemplateInstance} so the returned view can be identified, {@code PosState}
 * exposes its {@code isLocked()} decision and carries plain public fields
 * ({@code returnUrl}, {@code version}, sub-states) that the tests set directly.
 * Tests assert absolute expected values and verify delegation, covering both arms
 * of every lock guard, drawer check, version comparison, quantity/price parsing
 * branch and the {@code null}/empty short-circuits.
 */
class HomeResourceTest {

    /**
     * Builds a {@link HomeResource} whose collaborators are fresh mocks wired onto
     * its package-private fields, including the {@link PosState} sub-state holders
     * so no direct field access hits a null.
     *
     * @return a resource with fully mocked state, services and templates
     */
    private HomeResource newResource() {
        HomeResource resource = new HomeResource();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        resource.i18n = TestMessages.french();
        resource.state = mock(PosState.class);
        resource.state.ticket = mock(TicketState.class);
        resource.state.fidelity = mock(FidelityState.class);
        resource.state.ageCheck = mock(PosState.AgeCheckState.class);
        resource.state.priceModState = mock(PriceModState.class);
        // LC-02-03-01/03: the poll tells the screen whether a price or quantity prompt
        // is open, so it reads the prompt on every answer. A pristine prompt is the
        // ordinary case — nothing suspended — and keeps that read from hitting a null.
        resource.state.entryPrompt = new PosState.EntryPromptState();
        // LC-01-02-03: the poll also carries the close asked for by a badge
        // scan, so it reads the auth state on every answer. A pristine state
        // asks for nothing, which is the ordinary case.
        resource.state.auth = new com.intermarche.pos.ui.auth.AuthState();
        // LC-01-07-03/-04, LC-01-08-04: the poll carries the lane's request
        // state on every answer too, and a mocked PosState leaves the enum
        // field null — seed it at NONE, the lane that has asked nothing.
        resource.state.serviceRequest = PosState.ServiceRequestStatus.NONE;
        resource.homeService = mock(HomeService.class);
        resource.ticketService = mock(TicketService.class);
        resource.hardwareService = mock(HardwareService.class);
        resource.moneticsDegradedService = mock(MoneticsDegradedService.class);
        resource.main = mock(Template.class);
        resource.supervisor = mock(Template.class);
        resource.ticket = mock(Template.class);
        resource.boMessage = mock(Template.class);
        resource.drawerError = mock(Template.class);
        resource.exchangeHealthService = mock(com.intermarche.pos.ui.supervision.ExchangeHealthService.class);
        // The ticket-fragment poll settles a finished cheque reading on every
        // answer (the register writes its draft from one thread at a time), so it
        // reaches the payment service — a mock keeps that read from hitting null.
        resource.paymentService = mock(com.intermarche.pos.ui.payment.PaymentService.class);
        // The endorsement flow, for the FIN SUPERVISEUR route (EXP-33).
        resource.endorsementService = mock(com.intermarche.pos.ui.endorsement.EndorsementService.class);
        return resource;
    }

    /**
     * Stubs the {@code main} template to return a recognizable view for the given
     * resource's state.
     *
     * @param resource the resource whose {@code main} template is stubbed
     * @return the view {@code main.data("state", state)} will return
     */
    private TemplateInstance stubMain(HomeResource resource) {
        return stubChain(resource.main, resource.state);
    }

    /**
     * Stubs a template so that {@code data("state", state)} yields a recognizable
     * view AND every further {@code data(...)} of the chain yields that same view.
     *
     * <p>The home page and the ticket fragment both seed more than the state — the
     * eligible restricted-tender bases ride along ({@code LC-09-01-12/14/16/18}) — so
     * a stub that answered only the first link of the chain would hand the second one
     * a null and the render would never happen.
     *
     * @param template the template to stub
     * @param state    the state the first link is seeded with
     * @return the view every link of the chain returns
     */
    private TemplateInstance stubChain(Template template, PosState state) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(template.data("state", state)).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        return view;
    }

    // --- Drawer error handling ---

    /**
     * {@code drawerErrorPage()} renders the drawer-error page seeded with the state.
     */
    @Test
    void drawerErrorPageRendersDrawerError() {
        HomeResource resource = newResource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.drawerError.data("state", resource.state)).thenReturn(view);
        TemplateInstance result = resource.drawerErrorPage();
        assertSame(view, result);
    }

    /**
     * {@code resumeAfterDrawer()} redirects to the stored return URL, clears it and
     * touches the state when a non-empty return URL is present.
     */
    @Test
    void resumeAfterDrawerRedirectsToStoredUrl() {
        HomeResource resource = newResource();
        resource.state.returnUrl = "/pay";
        Response response = resource.resumeAfterDrawer();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/pay", response.getLocation().toString());
        assertNull(resource.state.returnUrl);
        verify(resource.state).touch();
    }

    /**
     * {@code resumeAfterDrawer()} falls back to the home page when the return URL is
     * null (first arm of the guard false).
     */
    @Test
    void resumeAfterDrawerRedirectsHomeWhenUrlNull() {
        HomeResource resource = newResource();
        resource.state.returnUrl = null;
        Response response = resource.resumeAfterDrawer();
        assertEquals("/", response.getLocation().toString());
        verify(resource.state).touch();
    }

    /**
     * {@code resumeAfterDrawer()} falls back to the home page when the return URL is
     * empty (second arm of the guard false).
     */
    @Test
    void resumeAfterDrawerRedirectsHomeWhenUrlEmpty() {
        HomeResource resource = newResource();
        resource.state.returnUrl = "";
        Response response = resource.resumeAfterDrawer();
        assertEquals("/", response.getLocation().toString());
        verify(resource.state).touch();
    }

    /**
     * {@code checkDrawerStatus()} reports the drawer still open and no redirect,
     * without clearing the return URL, when the hardware drawer is open.
     */
    @Test
    void checkDrawerStatusReportsOpen() {
        HomeResource resource = newResource();
        when(resource.hardwareService.isDrawerOpen()).thenReturn(true);
        resource.state.returnUrl = "/pay";
        Map<String, Object> result = resource.checkDrawerStatus();
        assertEquals(true, result.get("open"));
        assertEquals("", result.get("redirect"));
        assertEquals("/pay", resource.state.returnUrl);
        verify(resource.state, never()).touch();
    }

    /**
     * {@code checkDrawerStatus()} reports the drawer closed and redirects to the
     * stored return URL when it is non-empty.
     */
    @Test
    void checkDrawerStatusReportsClosedWithReturnUrl() {
        HomeResource resource = newResource();
        when(resource.hardwareService.isDrawerOpen()).thenReturn(false);
        resource.state.returnUrl = "/pay";
        Map<String, Object> result = resource.checkDrawerStatus();
        assertEquals(false, result.get("open"));
        assertEquals("/pay", result.get("redirect"));
        assertNull(resource.state.returnUrl);
        verify(resource.state).touch();
    }

    /**
     * {@code checkDrawerStatus()} reports the drawer closed and redirects home when
     * the return URL is null (first arm of the ternary false).
     */
    @Test
    void checkDrawerStatusReportsClosedHomeWhenUrlNull() {
        HomeResource resource = newResource();
        when(resource.hardwareService.isDrawerOpen()).thenReturn(false);
        resource.state.returnUrl = null;
        Map<String, Object> result = resource.checkDrawerStatus();
        assertEquals(false, result.get("open"));
        assertEquals("/", result.get("redirect"));
        verify(resource.state).touch();
    }

    /**
     * {@code checkDrawerStatus()} reports the drawer closed and redirects home when
     * the return URL is empty (second arm of the ternary false).
     */
    @Test
    void checkDrawerStatusReportsClosedHomeWhenUrlEmpty() {
        HomeResource resource = newResource();
        when(resource.hardwareService.isDrawerOpen()).thenReturn(false);
        resource.state.returnUrl = "";
        Map<String, Object> result = resource.checkDrawerStatus();
        assertEquals("/", result.get("redirect"));
        verify(resource.state).touch();
    }

    // --- Main pages ---

    /**
     * {@code home()} renders the main page when the terminal is unlocked.
     */
    @Test
    void homeRendersMainWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.home());
    }

    /**
     * LC-01-08-04: the consult page renders the back-office message seeded with
     * the state.
     */
    @Test
    void boMessagePageRendersTheConsultScreen() {
        HomeResource resource = newResource();
        TemplateInstance view = stubChain(resource.boMessage, resource.state);
        assertSame(view, resource.boMessagePage());
    }

    /**
     * LC-01-08-05: the acknowledgement route delegates to the service and
     * returns to the sale screen.
     */
    @Test
    void ackBoMessageDelegatesAndRedirects() {
        HomeResource resource = newResource();
        Response response = resource.ackBoMessage();
        verify(resource.homeService).ackBackOfficeMessage();
        assertEquals(303, response.getStatus());
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * LC-01-07-05: the cancellation route delegates to the service and returns
     * to the supervisor page, where the lane's indicators live.
     */
    @Test
    void cancelServiceRequestDelegatesAndRedirects() {
        HomeResource resource = newResource();
        Response response = resource.cancelServiceRequest();
        verify(resource.homeService).cancelServiceRequest();
        assertEquals("/supervisor", response.getLocation().toString());
    }

    /**
     * LC-01-07-06: the validation route delegates to the service and returns
     * to the supervisor page.
     */
    @Test
    void validateServiceRequestDelegatesAndRedirects() {
        HomeResource resource = newResource();
        Response response = resource.validateServiceRequest();
        verify(resource.homeService).validateServiceRequest();
        assertEquals("/supervisor", response.getLocation().toString());
    }

    /**
     * LC-01-07-03/-04, LC-01-08-04: the fragment poll carries the lane's
     * request state and the message-arrived flag on EVERY answer, version match
     * included — they are how the indicator zone learns it must reload.
     */
    @Test
    void getTicketFragmentCarriesTheSupervisionIndicators() {
        HomeResource resource = newResource();
        resource.state.version = 7L;
        resource.state.serviceRequest = PosState.ServiceRequestStatus.PENDING;
        resource.state.boMessageArrived = true;
        when(resource.state.ticket.getTotalAmount()).thenReturn(BigDecimal.ZERO);
        Map<String, Object> result = resource.getTicketFragment(7L);
        assertEquals("PENDING", result.get("serviceRequest"));
        assertEquals(true, result.get("boArrived"));
    }

    /**
     * {@code getTicketFragment()} reports no change when the client version matches
     * the current state version.
     */
    @Test
    void getTicketFragmentReportsUnchangedWhenVersionsMatch() {
        HomeResource resource = newResource();
        resource.state.version = 7L;
        when(resource.state.ticket.getTotalAmount()).thenReturn(BigDecimal.ZERO);
        Map<String, Object> result = resource.getTicketFragment(7L);
        assertEquals(false, result.get("changed"));
        assertFalse(result.containsKey("html"));
        // The lock and payability flags ride on EVERY answer, version match
        // included: they are how an open page learns it must reload.
        assertEquals(false, result.get("locked"));
        assertEquals(false, result.get("payable"));
        assertEquals(false, result.get("closeRequested"));
        verifyNoInteractions(resource.ticket);
    }

    /**
     * {@code getTicketFragment()} carries the close asked for by a badge scan
     * on a version match too: that is how the sale screen learns the operator
     * scanned their own badge and must go to the closing (LC-01-02-03).
     */
    @Test
    void getTicketFragmentCarriesTheCloseAskedForByABadge() {
        HomeResource resource = newResource();
        resource.state.version = 7L;
        resource.state.auth.closeRequestedByBadge = true;
        when(resource.state.ticket.getTotalAmount()).thenReturn(BigDecimal.ZERO);
        Map<String, Object> result = resource.getTicketFragment(7L);
        assertEquals(false, result.get("changed"));
        assertEquals(true, result.get("closeRequested"));
    }

    /**
     * {@code getTicketFragment()} reports a change and returns the rendered fragment
     * when the client version is null (first arm of the guard false).
     */
    @Test
    void getTicketFragmentReportsChangedWhenClientVersionNull() {
        HomeResource resource = newResource();
        resource.state.version = 3L;
        resource.state.fidelity.active = true;
        when(resource.state.fidelity.getDisplaySummary()).thenReturn("DUPONT · 2990000000019");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("12,00");
        when(resource.state.ticket.getTotalAmount()).thenReturn(new BigDecimal("12.00"));
        TemplateInstance ticketView = stubChain(resource.ticket, resource.state);
        when(ticketView.render()).thenReturn("<html>");
        Map<String, Object> result = resource.getTicketFragment(null);
        assertEquals(true, result.get("changed"));
        assertEquals(3L, result.get("version"));
        assertEquals("<html>", result.get("html"));
        assertEquals("12,00", result.get("total"));
        assertEquals(new BigDecimal("12.00"), result.get("amount"));
        assertEquals(false, result.get("locked"));
        assertEquals(true, result.get("payable"));
        assertEquals(true, result.get("fidelityActive"));
        // The attached-card summary rides on the changed answer next to the icon.
        assertEquals("DUPONT · 2990000000019", result.get("fidelitySummary"));
    }

    /**
     * {@code getTicketFragment()} reports a change when the client version differs
     * from the current one (second arm of the guard false).
     */
    @Test
    void getTicketFragmentReportsChangedWhenVersionsDiffer() {
        HomeResource resource = newResource();
        resource.state.version = 3L;
        resource.state.fidelity.active = false;
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalAmount()).thenReturn(BigDecimal.ZERO);
        TemplateInstance ticketView = stubChain(resource.ticket, resource.state);
        when(ticketView.render()).thenReturn("<html>");
        Map<String, Object> result = resource.getTicketFragment(99L);
        assertEquals(true, result.get("changed"));
        assertEquals(3L, result.get("version"));
        assertEquals(false, result.get("locked"));
        assertEquals(false, result.get("payable"));
        assertEquals(false, result.get("fidelityActive"));
        // No card attached: the summary is null on the changed answer too.
        assertNull(result.get("fidelitySummary"));
    }

    /**
     * {@code supervisorPage()} renders the supervisor page when the terminal is
     * unlocked.
     */
    @Test
    void supervisorPageRendersSupervisorWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.supervisor.data("state", resource.state)).thenReturn(view);
        // The page now seeds the exchange-health panel and the one-shot relaunch
        // notice too; every chained data(...) returns the same instance so the
        // page's identity is unchanged.
        when(view.data(any(), any())).thenReturn(view);
        assertSame(view, resource.supervisorPage(null, true));
        verify(resource.exchangeHealthService).build();
    }

    /**
     * {@code callSupervisor()} normalizes the reason, calls the service and redirects
     * home when the terminal is unlocked.
     */
    @Test
    void callSupervisorSendsReasonWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.callSupervisor("no-change");
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).callSupervisor("NO CHANGE");
    }

    /**
     * {@code toggleTraining()} requests the endorsed toggle and redirects home when
     * the terminal is unlocked.
     */
    @Test
    void toggleTrainingRequestsToggleWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.toggleTraining();
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).requestTrainingToggle();
    }

    /**
     * {@code toggleMoneticsDegraded()} RELEASES the forced degraded mode when it
     * is already forced (true arm of the guard): the single key deactivates and
     * redirects home, and the activate path is never taken.
     */
    @Test
    void toggleMoneticsDegradedDeactivatesWhenAlreadyForced() {
        HomeResource resource = newResource();
        when(resource.state.isMoneticsDegradedForced()).thenReturn(true);
        Response response = resource.toggleMoneticsDegraded("mcurie", "1111");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.moneticsDegradedService).deactivate(resource.state, "mcurie", "1111");
        verify(resource.moneticsDegradedService, never()).activate(any(), any(), any());
    }

    /**
     * {@code toggleMoneticsDegraded()} FORCES the degraded mode when it is not yet
     * forced (false arm of the guard): the same key activates and redirects home,
     * and the deactivate path is never taken.
     */
    @Test
    void toggleMoneticsDegradedActivatesWhenNotForced() {
        HomeResource resource = newResource();
        when(resource.state.isMoneticsDegradedForced()).thenReturn(false);
        Response response = resource.toggleMoneticsDegraded("mcurie", "1111");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.moneticsDegradedService).activate(resource.state, "mcurie", "1111");
        verify(resource.moneticsDegradedService, never()).deactivate(any(), any(), any());
    }

    // --- Menu navigation ---

    /**
     * {@code showMenu()} shows the menu the bottom bar named and returns the home
     * view.
     */
    @Test
    void showMenuSelectsTheNamedMenuAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.showMenu("client"));
        verify(resource.homeService).selectMenu(com.intermarche.pos.ui.PosMenu.GENEROSITE);
    }

    /**
     * {@code showMenu()} lands on the ARTICLES menu when the key is unreadable: a
     * stale link must never leave the cashier in front of a blank grid.
     */
    @Test
    void showMenuFallsBackToTheSaleMenu() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.showMenu("rayon"));
        verify(resource.homeService).selectMenu(com.intermarche.pos.ui.PosMenu.ARTICLES);
    }

    // --- Ticket navigation ---

    /**
     * {@code ticketPrev()} moves the ticket display to the previous page and returns
     * the home view.
     */
    @Test
    void ticketPrevMovesBackAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.ticketPrev());
        verify(resource.state).prevPage();
    }

    /**
     * {@code ticketNext()} moves the ticket display to the next page and returns the
     * home view.
     */
    @Test
    void ticketNextMovesForwardAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.ticketNext());
        verify(resource.state).nextPage();
    }

    /**
     * {@code toggleReturnMode()} delegates to the service and returns the home view
     * (EXP-40).
     */
    @Test
    void toggleReturnModeDelegatesAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.toggleReturnMode());
        verify(resource.homeService).toggleReturnMode();
    }

    // --- Selection & cancellation ---

    /**
     * {@code selectLine()} toggles the line selection and returns the home view when
     * unlocked.
     */
    @Test
    void selectLineSelectsWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.selectLine("U2"));
        verify(resource.homeService).selectLine("U2");
    }

    /**
     * {@code cancelLine()} cancels the targeted line and returns the home view when
     * unlocked.
     */
    @Test
    void cancelLineCancelsWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.cancelLine());
        verify(resource.homeService).cancelLine();
    }

    // --- Price-modification modal ---

    /**
     * {@code openPriceMod()} opens the modal for the given type and returns the home
     * view when unlocked.
     */
    @Test
    void openPriceModOpensWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.openPriceMod("remise", null));
        verify(resource.homeService).openPriceMod("remise");
        verify(resource.homeService, never()).applyPreEntry(anyString());
    }

    /**
     * PRE-ENTRY: a figure typed before the key is applied through the opening,
     * which is what makes it inherit the opening's own refusals.
     */
    @Test
    void openPriceModAppliesAFigureTypedBeforeTheKey() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        stubMain(resource);
        resource.openPriceMod("quantity", "3");
        InOrder order = inOrder(resource.homeService);
        order.verify(resource.homeService).openPriceMod("quantity");
        order.verify(resource.homeService).applyPreEntry("3");
    }

    /**
     * A BLANK value is not a pre-entry: the key opens its modal as it always
     * did — the two legs of the guard, null and blank.
     */
    @Test
    void openPriceModIgnoresAnEmptyPreEntry() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        stubMain(resource);
        resource.openPriceMod("remise", "");
        resource.openPriceMod("remise", "   ");
        verify(resource.homeService, times(2)).openPriceMod("remise");
        verify(resource.homeService, never()).applyPreEntry(anyString());
    }

    /**
     * {@code cancelPriceMod()} closes the modal and returns the home view.
     */
    @Test
    void cancelPriceModClosesAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.cancelPriceMod());
        verify(resource.homeService).cancelPriceMod();
    }

    /**
     * {@code submitPriceMod()} parses a comma value and submits it when unlocked.
     */
    @Test
    void submitPriceModSubmitsParsedValue() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.submitPriceMod("REMISE", "u1", "1,5");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).submitPriceMod(PriceModType.REMISE, "u1", new BigDecimal("1.5"));
    }

    /**
     * {@code submitPriceMod()} defaults a null raw value to zero (first arm of the
     * empty guard true) and submits it.
     */
    @Test
    void submitPriceModDefaultsNullValueToZero() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.submitPriceMod("REMISE", "u1", null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).submitPriceMod(PriceModType.REMISE, "u1", new BigDecimal("0"));
    }

    /**
     * {@code submitPriceMod()} defaults an empty raw value to zero (second arm of the
     * empty guard true) and submits it.
     */
    @Test
    void submitPriceModDefaultsEmptyValueToZero() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.submitPriceMod("REMISE", "u1", "");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).submitPriceMod(PriceModType.REMISE, "u1", new BigDecimal("0"));
    }

    /**
     * {@code submitPriceMod()} sets an error, clears the modal and touches the state
     * without submitting when the raw value is not a number.
     */
    @Test
    void submitPriceModRejectsInvalidValue() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.submitPriceMod("REMISE", "u1", "abc");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.state.ticket).setError("VALEUR INVALIDE");
        verify(resource.state.priceModState).clear();
        verify(resource.state).touch();
        verify(resource.homeService, never()).submitPriceMod(any(), any(), any());
    }

    /**
     * {@code submitPriceMod()} refuses a posted word that names no mode: the value
     * parses, but nothing is applied and the modal closes on an error (unknown-mode
     * arm). A forged post cannot reach the dispatch with a mode the register has no
     * rule for.
     */
    @Test
    void submitPriceModRejectsUnknownMode() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.submitPriceMod("MYSTERY", "u1", "1,5");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.state.ticket).setError("MODIFICATION INCONNUE");
        verify(resource.state.priceModState).clear();
        verify(resource.state).touch();
        verify(resource.homeService, never()).submitPriceMod(any(), any(), any());
    }

    /**
     * {@code submitPriceMod()} refuses a missing mode the same way — an absent form
     * field is not a mode either (null arm of the parser).
     */
    @Test
    void submitPriceModRejectsNullMode() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.submitPriceMod(null, "u1", "1,5");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        verify(resource.state.ticket).setError("MODIFICATION INCONNUE");
        verify(resource.homeService, never()).submitPriceMod(any(), any(), any());
    }

    /**
     * {@code submitPriceMod()} refuses a blank mode too (blank arm of the parser).
     */
    @Test
    void submitPriceModRejectsBlankMode() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.submitPriceMod("  ", "u1", "1,5");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        verify(resource.state.ticket).setError("MODIFICATION INCONNUE");
        verify(resource.homeService, never()).submitPriceMod(any(), any(), any());
    }

    // --- Other actions ---

    /**
     * {@code addPlu()} adds the weighed product by PLU and returns the home view when
     * unlocked.
     */
    @Test
    void addPluAddsWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.addPlu("123"));
        verify(resource.ticketService).addItemByPlu(resource.state, "123");
    }

    /**
     * {@code addManualKnown()} parses a positive quantity and adds the product when
     * unlocked (both null/empty guards true, {@code qty <= 0} false).
     */
    @Test
    void addManualKnownAddsParsedQuantity() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualKnown("EAN", "3");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(3));
    }

    /**
     * {@code addManualKnown()} defaults a null quantity to one (first arm of the guard
     * false) and adds the product.
     */
    @Test
    void addManualKnownDefaultsNullQuantityToOne() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualKnown("EAN", null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(1));
    }

    /**
     * {@code addManualKnown()} defaults an empty quantity to one (second arm of the
     * guard false) and adds the product.
     */
    @Test
    void addManualKnownDefaultsEmptyQuantityToOne() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualKnown("EAN", "");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(1));
    }

    /**
     * {@code addManualKnown()} swallows an unparsable quantity and falls back to one.
     */
    @Test
    void addManualKnownFallsBackOnUnparsableQuantity() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualKnown("EAN", "abc");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(1));
    }

    /**
     * {@code addManualKnown()} clamps a non-positive quantity to one ({@code qty <= 0}
     * true arm).
     */
    @Test
    void addManualKnownClampsNonPositiveQuantity() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualKnown("EAN", "0");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(1));
    }

    /**
     * {@code addManualUnknown()} adds the unlisted item and redirects to the home page
     * when unlocked.
     */
    @Test
    void addManualUnknownAddsWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualUnknown("Label", "2,00");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addUnknownItem(resource.state, "Label", "2,00");
    }

    /**
     * {@code addDepositReturn()} adds a deposit-return line and returns the home view
     * when unlocked.
     */
    @Test
    void addDepositReturnAddsWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.addDepositReturn());
        verify(resource.ticketService).addDeposit(resource.state);
    }

    /**
     * {@code cancelTicket()} sends the operator to the ABANDON SCREEN rather than
     * cancelling on the spot ({@code LC-04-04-06} to {@code -12}).
     *
     * <p>The screen is where the shop's rules are applied: the reason is chosen from
     * the administered list, a settlement already taken is named before it is undone,
     * and the abandon ticket is printed or not according to the parameter. Cancelling
     * from here would skip all three, so this key carries no cancellation of its own
     * and touches no service.
     */
    @Test
    void cancelTicketSendsTheOperatorToTheAbandonScreen() {
        HomeResource resource = newResource();
        Response response = resource.cancelTicket();
        assertEquals(303, response.getStatus());
        assertEquals("/abandon", response.getLocation().toString());
        verifyNoInteractions(resource.homeService);
    }

    /**
     * {@code printLast()} reprints the last closed ticket and redirects to the
     * sale screen — like every other function of the TICKET menu, so a refresh
     * cannot print the ticket twice.
     */
    @Test
    void printLastReprintsAndRedirectsHome() {
        HomeResource resource = newResource();
        Response response = resource.printLast();
        assertEquals(303, response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).printLastTicket();
    }

    /**
     * {@code printLastBarcode()} prints the identity slip and redirects to the
     * sale screen (LC-08-01-04).
     */
    @Test
    void printLastBarcodePrintsAndRedirectsHome() {
        HomeResource resource = newResource();
        Response response = resource.printLastBarcode();
        assertEquals(303, response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).printLastTicketBarcode();
    }

    /**
     * {@code printLastCard()} prints the card-receipt duplicate and redirects to
     * the sale screen (LC-08-05-09).
     */
    @Test
    void printLastCardPrintsAndRedirectsHome() {
        HomeResource resource = newResource();
        Response response = resource.printLastCard();
        assertEquals(303, response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).printLastCardReceiptDuplicate();
    }

    /**
     * Prepares a resource whose fragment can be rendered, with the given earn
     * projection on the card.
     *
     * @param earnTotal the projected earn, or null when none
     * @return the ready resource
     */
    private HomeResource fragmentResourceWithEarn(java.math.BigDecimal earnTotal) {
        HomeResource resource = newResource();
        resource.state.version = 3L;
        resource.state.fidelity.active = true;
        resource.state.fidelity.earnTotal = earnTotal;
        when(resource.state.ticket.getTotalFormatted()).thenReturn("12,00");
        when(resource.state.ticket.getTotalAmount()).thenReturn(new BigDecimal("12.00"));
        TemplateInstance ticketView = stubChain(resource.ticket, resource.state);
        when(ticketView.render()).thenReturn("<html>");
        return resource;
    }

    /**
     * A POSITIVE projection produces the badge, formatted to the cent and in
     * FRENCH notation — the comma matters: the badge is read by a cashier,
     * not by a parser.
     */
    @Test
    void getTicketFragmentFormatsTheEarnBadge() {
        HomeResource resource = fragmentResourceWithEarn(new BigDecimal("1.03"));
        Map<String, Object> result = resource.getTicketFragment(null);
        assertEquals("AVANTAGE CARTE 1,03 €", result.get("fidelityEarn"));
    }

    /**
     * The amount is ROUNDED to two decimals like any money on screen.
     */
    @Test
    void getTicketFragmentRoundsTheEarnBadgeToCents() {
        HomeResource resource = fragmentResourceWithEarn(new BigDecimal("2.345"));
        Map<String, Object> result = resource.getTicketFragment(null);
        assertEquals("AVANTAGE CARTE 2,35 €", result.get("fidelityEarn"));
    }

    /**
     * A NULL projection yields a null badge (first leg of the guard): this is
     * the DEGRADED display — imfid is unreachable, and the register says so
     * by saying nothing rather than by showing a zero it cannot vouch for.
     */
    @Test
    void getTicketFragmentHidesTheBadgeWhenNoProjection() {
        HomeResource resource = fragmentResourceWithEarn(null);
        Map<String, Object> result = resource.getTicketFragment(null);
        assertNull(result.get("fidelityEarn"));
        assertEquals(true, result.get("fidelityActive"));
    }

    /**
     * A ZERO projection hides the badge too (second leg, {@code signum() ==
     * 0}): a cart entirely absorbed by an offer earns nothing, and "AVANTAGE
     * CARTE 0,00 €" would look like a bug to the customer.
     */
    @Test
    void getTicketFragmentHidesTheBadgeOnAZeroProjection() {
        HomeResource resource = fragmentResourceWithEarn(BigDecimal.ZERO);
        Map<String, Object> result = resource.getTicketFragment(null);
        assertNull(result.get("fidelityEarn"));
    }

    /**
     * A NEGATIVE projection hides the badge as well (second leg, {@code
     * signum() < 0}): the guard is {@code > 0}, so no arithmetic accident
     * can ever advertise a negative advantage.
     */
    @Test
    void getTicketFragmentHidesTheBadgeOnANegativeProjection() {
        HomeResource resource = fragmentResourceWithEarn(new BigDecimal("-1.00"));
        Map<String, Object> result = resource.getTicketFragment(null);
        assertNull(result.get("fidelityEarn"));
    }

    /**
     * The badge is INDEPENDENT of the card flag: what drives it is the
     * projection, so an attached card with no projection shows nothing.
     */
    @Test
    void getTicketFragmentBadgeFollowsTheProjectionNotTheCardFlag() {
        HomeResource resource = fragmentResourceWithEarn(null);
        resource.state.fidelity.active = true;
        Map<String, Object> result = resource.getTicketFragment(null);
        assertEquals(true, result.get("fidelityActive"));
        assertNull(result.get("fidelityEarn"));
    }

    // --- age check ---

    /**
     * Confirming the ID check replays the parked gesture and returns the
     * cashier to the sale screen.
     */
    @Test
    void ageCheckConfirmReplaysAndRedirects() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.ageCheckConfirm();
        verify(resource.ticketService).confirmAgeCheck(resource.state);
        assertEquals(303, response.getStatus());
        assertEquals(URI.create("/"), response.getLocation());
    }

    /**
     * Refusing journals the refusal, clears the parked gesture and returns to
     * the sale screen.
     */
    @Test
    void ageCheckRefuseJournalsAndRedirects() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.ageCheckRefuse();
        verify(resource.ticketService).refuseAgeCheck(resource.state);
        assertEquals(303, response.getStatus());
        assertEquals(URI.create("/"), response.getLocation());
    }

    // --- Entry prompt & quantity arming (parseAmount branches) ---

    /**
     * {@code armQuantity()} parses a French-comma figure and arms it, then
     * redirects to the sale screen (parseAmount: non-null, non-blank, parseable).
     */
    @Test
    void armQuantityArmsParsedQuantity() {
        HomeResource resource = newResource();
        Response response = resource.armQuantity("2,5");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).armQuantity(new BigDecimal("2.5"));
    }

    /**
     * {@code armQuantity()} arms null when the raw value is null (first leg of
     * the parseAmount guard true, second leg short-circuited).
     */
    @Test
    void armQuantityArmsNullWhenRawValueNull() {
        HomeResource resource = newResource();
        Response response = resource.armQuantity(null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).armQuantity(null);
    }

    /**
     * {@code armQuantity()} arms null when the raw value is blank (first leg
     * false, second leg {@code isBlank()} true).
     */
    @Test
    void armQuantityArmsNullWhenRawValueBlank() {
        HomeResource resource = newResource();
        Response response = resource.armQuantity("   ");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).armQuantity(null);
    }

    /**
     * {@code confirmEntry()} parses a French-comma figure and confirms it on the
     * ticket service, then redirects to the sale screen (parseAmount happy path).
     */
    @Test
    void confirmEntryConfirmsParsedValue() {
        HomeResource resource = newResource();
        Response response = resource.confirmEntry("3,00");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).confirmEntry(resource.state, new BigDecimal("3.00"));
    }

    /**
     * {@code confirmEntry()} confirms null when the raw value is empty (first leg
     * false, second leg {@code isBlank()} true on the empty string).
     */
    @Test
    void confirmEntryConfirmsNullWhenRawValueEmpty() {
        HomeResource resource = newResource();
        Response response = resource.confirmEntry("");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).confirmEntry(resource.state, null);
    }

    /**
     * {@code confirmEntry()} confirms null when the raw value is unparsable: the
     * parse throws and parseAmount swallows it as a refusal, not an incident
     * (parseAmount: non-null, non-blank, NumberFormatException arm).
     */
    @Test
    void confirmEntryConfirmsNullWhenRawValueUnparsable() {
        HomeResource resource = newResource();
        Response response = resource.confirmEntry("abc");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).confirmEntry(resource.state, null);
    }

    /**
     * {@code cancelEntry()} gives up the suspended add and redirects to the sale
     * screen: nothing is registered.
     */
    @Test
    void cancelEntryCancelsAndRedirects() {
        HomeResource resource = newResource();
        Response response = resource.cancelEntry();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).cancelEntry(resource.state);
    }

    /**
     * {@code repeatLastItem()} repeats the last registration and returns the home
     * view.
     */
    @Test
    void repeatLastItemRepeatsAndReturnsHome() {
        HomeResource resource = newResource();
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.repeatLastItem());
        verify(resource.homeService).repeatLastItem();
    }

    /**
     * {@code toggleCollect()} marks the selected line "à enlever" (or arms the
     * next article for it) and returns the home view.
     */
    @Test
    void toggleCollectTogglesAndReturnsHome() {
        HomeResource resource = newResource();
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.toggleCollect());
        verify(resource.homeService).toggleCollect();
    }

    /**
     * {@code armCancelByScan()} (EXP-36) delegates to the service and re-renders
     * the sale screen.
     */
    @Test
    void armCancelByScanRouteDelegatesAndRendersHome() {
        HomeResource resource = newResource();
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.armCancelByScan());
        verify(resource.homeService).armCancelByScan();
    }

    /**
     * {@code endSupervisorSession()} (EXP-33) clears the session and re-renders the
     * sale screen.
     */
    @Test
    void endSupervisorSessionRouteClearsAndRendersHome() {
        HomeResource resource = newResource();
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.endSupervisorSession());
        verify(resource.endorsementService).endSupervisorSession(resource.state);
    }

    /**
     * {@code getTicketFragment} carries and clears the return-by-scan request
     * (EXP-37): the poll navigates to /return exactly once.
     */
    @Test
    void getTicketFragmentCarriesAndClearsTheReturnRequest() {
        HomeResource resource = newResource();
        resource.state.version = 7L;
        resource.state.returnByScanRequested = true;
        when(resource.state.ticket.getTotalAmount()).thenReturn(BigDecimal.ZERO);
        Map<String, Object> result = resource.getTicketFragment(7L);
        assertEquals(true, result.get("returnRequested"));
        assertEquals(false, resource.state.returnByScanRequested);
    }

    /**
     * {@code supervisorMessage} delegates the chosen reason and the free-text
     * message to the service and redirects home (EXP-19/84).
     */
    @Test
    void supervisorMessageDelegatesReasonAndMessageThenRedirects() {
        HomeResource resource = newResource();
        Response response = resource.supervisorMessage("CAISSE", "besoin de monnaie");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).callSupervisor("CAISSE", "besoin de monnaie");
    }

    /**
     * {@code supervisorMessage} passes an empty reason when none is chosen (the
     * null-reason arm of the ternary), so a free message alone still goes up.
     */
    @Test
    void supervisorMessageNullReasonBecomesEmpty() {
        HomeResource resource = newResource();
        resource.supervisorMessage(null, "message libre");
        verify(resource.homeService).callSupervisor("", "message libre");
    }

    /**
     * {@code surveyAnswer} delegates the chosen answer to the service and
     * redirects home (EXP-89).
     */
    @Test
    void surveyAnswerDelegatesAndRedirects() {
        HomeResource resource = newResource();
        Response response = resource.surveyAnswer("Oui");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).answerSurvey("Oui");
    }

}
