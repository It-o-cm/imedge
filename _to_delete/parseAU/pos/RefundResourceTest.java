package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RefundResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, its
 * {@link RefundState} sub-state, a {@link RefundService} and three Qute
 * {@link Template}s ({@code lock}, {@code return-search}, {@code return-detail}).
 * Every collaborator is a Mockito mock: templates echo a recognizable
 * {@link TemplateInstance} so the returned view can be identified, {@code PosState}
 * exposes its {@code isLocked()} decision and carries a mocked {@code refund}
 * holder whose {@code isTicketSelected()} decision and public fields the tests
 * drive directly. Tests assert absolute expected values and verify delegation,
 * covering both arms of the lock guard, the ticket-selected guard and the
 * {@code rawValue} null ternary.
 */
class RefundResourceTest {

    /**
     * Builds a {@link RefundResource} whose collaborators are fresh mocks wired
     * onto its package-private fields, including the {@link PosState#refund}
     * sub-state holder so no direct field access hits a null.
     *
     * @return a resource with fully mocked state, service and templates
     */
    private RefundResource newResource() {
        RefundResource resource = new RefundResource();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        resource.i18n = TestMessages.french();
        resource.state = mock(PosState.class);
        resource.state.refund = mock(RefundState.class);
        resource.refundService = mock(RefundService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        resource.posListService = mock(com.intermarche.pos.service.PosListService.class);
        // The administered lists (BO-04-02-07), consulted for the return types.
        resource.posListService = mock(com.intermarche.pos.service.PosListService.class);
        resource.endorsementService = mock(com.intermarche.pos.ui.endorsement.EndorsementService.class);
        resource.returnSearchPage = mock(Template.class);
        resource.returnDetailPage = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the {@code return-search} template to return a recognizable view for
     * the given resource's state, through the two-step render the search page now
     * uses: the state, then the administered return types (LC-05-04-01).
     *
     * @param resource the resource whose {@code returnSearchPage} template is stubbed
     * @return the view the search render returns
     */
    private TemplateInstance stubSearch(RefundResource resource) {
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.returnSearchPage.data("state", resource.state)).thenReturn(withState);
        when(withState.data(eq("returnTypes"), any())).thenReturn(view);
        return view;
    }

    /**
     * Stubs the {@code return-detail} template to return a recognizable view for the
     * given resource's state.
     *
     * @param resource the resource whose {@code returnDetailPage} template is stubbed
     * @return the view {@code returnDetailPage.data("state", state)} returns
     */
    private TemplateInstance stubDetail(RefundResource resource) {
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.returnDetailPage.data("state", resource.state)).thenReturn(withState);
        when(withState.data(eq("reasons"), any())).thenReturn(view);
        return view;
    }

    // --- reason (LC-05-05-01) ---

    /**
     * {@code reason()} delegates the tapped reason to the service and returns the
     * detail view, and the detail render hands the administered reasons to the
     * template (the reason chooser data of LC-05-05-01).
     */
    @Test
    void reasonSelectsReasonAndReturnsDetailWithReasons() {
        RefundResource resource = newResource();
        when(resource.posListService.returnReasons())
                .thenReturn(List.of(new com.intermarche.pos.service.PosListService.ListChoice("CAISSE", "Erreur de caisse"),
                        new com.intermarche.pos.service.PosListService.ListChoice("PERIME", "Article périmé")));
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.reason("Article périmé"));
        verify(resource.refundService).selectReason(resource.state, "Article périmé");
        verify(resource.returnDetailPage).data("state", resource.state);
    }

    /**
     * {@code creditAccount()} delegates the scanned account number to the
     * service and returns the detail view (LC-05-06-05 account selection).
     */
    @Test
    void creditAccountSelectsAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.creditAccount("CC1"));
        verify(resource.refundService).selectCreditAccount(resource.state, "CC1");
    }

    /**
     * {@code payCredit()} requests an endorsed credit refund and returns the
     * detail view (LC-05-06-05).
     */
    @Test
    void payCreditRequestsCreditRefundAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.payCredit());
        verify(resource.refundService).requestRefund(resource.state, Refund.RefundMethod.CREDIT);
    }

    /**
     * {@code reason()} still returns the detail view when the tapped reason is null
     * (the query param was absent): the service clears the reason, the page is
     * re-rendered all the same.
     */
    @Test
    void reasonWithNullStillReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.reason(null));
        verify(resource.refundService).selectReason(resource.state, null);
    }

    // --- cancelPaidTicket (EXP-49) ---

    /**
     * {@code cancelPaidTicket} with no ticket recalled redirects to the return
     * screen without endorsing or reversing anything (isTicketSelected false arm).
     */
    @Test
    void cancelPaidTicketWithoutSelectionRedirectsAndDoesNothing() {
        RefundResource resource = newResource();
        when(resource.state.refund.isTicketSelected()).thenReturn(false);
        Response response = resource.cancelPaidTicket("M1", "1234");
        assertEquals("/return", response.getLocation().toString());
        verify(resource.endorsementService, never()).authorize(any(), any(), any());
        verify(resource.refundService, never()).cancelPaidTicket(any(), any(), any());
    }

    /**
     * {@code cancelPaidTicket} refused by the supervisor sets the refusal message
     * and reverses nothing (authorize false arm).
     */
    @Test
    void cancelPaidTicketRefusedBySupervisor() {
        RefundResource resource = newResource();
        when(resource.state.refund.isTicketSelected()).thenReturn(true);
        when(resource.endorsementService.authorize("M1", "9999", "CANCEL_PAID_TICKET"))
                .thenReturn(false);
        Response response = resource.cancelPaidTicket("M1", "9999");
        assertEquals("/return", response.getLocation().toString());
        assertEquals("AUTORISATION SUPERVISEUR REFUSÉE", resource.state.refund.errorMessage);
        verify(resource.refundService, never()).cancelPaidTicket(any(), any(), any());
    }

    /**
     * {@code cancelPaidTicket} endorsed by the supervisor reverses the recalled
     * paid ticket in cash and redirects (authorize true arm, EXP-49).
     */
    @Test
    void cancelPaidTicketEndorsedReversesTheTicket() {
        RefundResource resource = newResource();
        when(resource.state.refund.isTicketSelected()).thenReturn(true);
        when(resource.endorsementService.authorize("M1", "1111", "CANCEL_PAID_TICKET"))
                .thenReturn(true);
        Response response = resource.cancelPaidTicket("M1", "1111");
        assertEquals("/return", response.getLocation().toString());
        verify(resource.refundService).cancelPaidTicket(resource.state, Refund.RefundMethod.CASH, "M1");
    }

    // --- showSearchPage ---

    /**
     * {@code showSearchPage()} renders the detail page when unlocked and a ticket is
     * already selected (first guard false, second guard true).
     */
    @Test
    void showSearchPageRendersDetailWhenTicketSelected() {
        RefundResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.refund.isTicketSelected()).thenReturn(true);
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.showSearchPage());
        verifyNoInteractions(resource.returnSearchPage);
    }

    /**
     * {@code showSearchPage()} renders the search page when unlocked and no ticket is
     * selected (both guards false).
     */
    @Test
    void showSearchPageRendersSearchWhenNoTicketSelected() {
        RefundResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.refund.isTicketSelected()).thenReturn(false);
        TemplateInstance searchView = stubSearch(resource);
        assertSame(searchView, resource.showSearchPage());
        verifyNoInteractions(resource.returnDetailPage);
    }

    // --- doSearch ---

    /**
     * {@code doSearch()} trims a non-null pattern, runs the search and redirects to
     * the refund screen (ternary true arm; PRG pattern).
     */
    @Test
    void doSearchTrimsNonNullPattern() {
        RefundResource resource = newResource();
        Response response = resource.doSearch("  42  ");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/return", response.getLocation().toString());
        assertEquals("42", resource.state.refund.searchPattern);
        verify(resource.refundService).searchTickets(resource.state);
    }

    /**
     * {@code doSearch()} stores an empty pattern when the raw value is null (ternary
     * false arm) and still runs the search before redirecting.
     */
    @Test
    void doSearchDefaultsNullPatternToEmpty() {
        RefundResource resource = newResource();
        Response response = resource.doSearch(null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("", resource.state.refund.searchPattern);
        verify(resource.refundService).searchTickets(resource.state);
    }

    // --- selection & line editing ---

    /**
     * {@code selectTicket()} selects the ticket by id and returns the detail view.
     * The selection having succeeded, the store check of LC-05-02-07 let it
     * through.
     */
    @Test
    void selectTicketSelectsAndReturnsDetail() {
        RefundResource resource = newResource();
        when(resource.state.refund.isTicketSelected()).thenReturn(true);
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.selectTicket(5L));
        verify(resource.refundService).selectTicket(resource.state, 5L);
    }

    /**
     * {@code selectLine()} toggles the line selection and returns the detail view.
     */
    @Test
    void selectLineSelectsAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.selectLine(9L));
        verify(resource.refundService).selectLine(resource.state, 9L);
    }

    /**
     * {@code editAmount()} switches to global-amount edition and returns the detail
     * view.
     */
    @Test
    void editAmountStartsAmountEditAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.editAmount());
        verify(resource.refundService).startAmountEdit(resource.state);
    }

    /**
     * {@code submitLine()} applies the typed line quantity and redirects to the
     * refund screen (PRG pattern).
     */
    @Test
    void submitLineAppliesQuantityAndRedirects() {
        RefundResource resource = newResource();
        Response response = resource.submitLine(3L, "2");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/return", response.getLocation().toString());
        verify(resource.refundService).submitLineQuantity(resource.state, 3L, "2");
    }

    /**
     * {@code submitAmount()} applies the typed global amount and redirects to the
     * refund screen (PRG pattern).
     */
    @Test
    void submitAmountAppliesAmountAndRedirects() {
        RefundResource resource = newResource();
        Response response = resource.submitAmount("12,50");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/return", response.getLocation().toString());
        verify(resource.refundService).submitManualAmount(resource.state, "12,50");
    }

    /**
     * {@code addQty()} increments the line refund quantity and returns the detail
     * view.
     */
    @Test
    void addQtyIncrementsAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.addQty(7L));
        verify(resource.refundService).incrementQty(resource.state, 7L);
    }

    /**
     * {@code subQty()} decrements the line refund quantity and returns the detail
     * view.
     */
    @Test
    void subQtyDecrementsAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.subQty(7L));
        verify(resource.refundService).decrementQty(resource.state, 7L);
    }

    /**
     * {@code changePage()} sets the detail page index and returns the detail view.
     */
    @Test
    void changePageSetsPageAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.changePage(4));
        assertEquals(4, resource.state.refund.detailPage);
    }

    // --- endorsed refund requests ---

    /**
     * {@code payCash()} requests an endorsed cash refund and returns the detail view.
     */
    @Test
    void payCashRequestsCashRefund() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.payCash());
        verify(resource.refundService).requestRefund(resource.state, Refund.RefundMethod.CASH);
    }

    /**
     * {@code payCard()} requests an endorsed card refund and returns the detail view.
     */
    @Test
    void payCardRequestsCardRefund() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.payCard());
        verify(resource.refundService).requestRefund(resource.state, Refund.RefundMethod.CARD);
    }

    /**
     * {@code payVoucher()} requests an endorsed store-voucher refund and returns the
     * detail view.
     */
    @Test
    void payVoucherRequestsVoucherRefund() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.payVoucher());
        verify(resource.refundService).requestRefund(resource.state, Refund.RefundMethod.VOUCHER);
    }

    /**
     * {@code payLoyalty()} requests an endorsed loyalty refund and returns the detail
     * view.
     */
    @Test
    void payLoyaltyRequestsLoyaltyRefund() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.payLoyalty());
        verify(resource.refundService).requestRefund(resource.state, Refund.RefundMethod.LOYALTY);
    }

    /**
     * A ticket the register REFUSED (another point of sale's, LC-05-02-07) leaves
     * nothing selected, so the render falls back to the search page: the detail
     * page would have no ticket to show.
     */
    @Test
    void selectTicketRendersTheSearchWhenNothingGotSelected() {
        RefundResource resource = newResource();
        when(resource.state.refund.isTicketSelected()).thenReturn(false);
        TemplateInstance searchView = stubSearch(resource);
        assertSame(searchView, resource.selectTicket(10L));
        verify(resource.refundService).selectTicket(resource.state, 10L);
        verifyNoInteractions(resource.returnDetailPage);
    }

    /**
     * {@code enterArticle} hands the keyed code to the same service entry the
     * scan bus uses (LC-05-02-11) and redirects, so a browser reload never
     * replays the entry.
     */
    @Test
    void enterArticleDelegatesAndRedirects() {
        RefundResource resource = newResource();
        try (Response response = resource.enterArticle("123")) {
            assertEquals(303, response.getStatus());
            assertEquals("/return", response.getLocation().toString());
        }
        verify(resource.refundService).enterArticleByCode(resource.state, "123");
    }

    /**
     * {@code leave} DROPS the ticket selection on the way out (LC-05-02-11): the
     * selection is what routes a scan to the return, so the sale screen must not
     * inherit it.
     */
    @Test
    void leaveDropsTheSelectionAndRedirectsToTheSaleScreen() {
        RefundResource resource = newResource();
        try (Response response = resource.leave()) {
            assertEquals(303, response.getStatus());
            assertEquals("/", response.getLocation().toString());
        }
        verify(resource.state.refund).clear();
        verify(resource.state).touch();
    }
}
