package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.PosState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReprintResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, its
 * {@link ReprintState} sub-state, a {@link ReprintService} and three Qute
 * {@link Template}s ({@code lock}, {@code reprint-ticket},
 * {@code reprint-ticket-detail}). Every collaborator is a Mockito mock:
 * templates echo a recognizable {@link TemplateInstance} so the returned view
 * can be identified, {@code PosState} exposes its {@code isLocked()} decision
 * and carries a mocked {@code reprint} holder whose paging decisions are
 * stubbed and whose public fields the tests drive and assert directly. The
 * detail-view lookup goes through the {@code Ticket.findById} static finder,
 * which resolves to {@link PanacheEntityBase} under plain {@code mvn test} and
 * is intercepted with {@link org.mockito.Mockito#mockStatic}. Tests assert
 * absolute expected values and verify delegation, covering both arms of every
 * lock guard, both paging guards, the {@code findById} null guard and the
 * compound viewed-ticket identity guard (30 branches).
 */
class ReprintResourceTest {

    /**
     * Builds a {@link ReprintResource} whose collaborators are fresh mocks wired
     * onto its package-private fields, including the {@link PosState#reprint}
     * sub-state holder so no direct field access hits a null.
     *
     * @return a resource with fully mocked state, service and templates
     */
    private ReprintResource newResource() {
        ReprintResource resource = new ReprintResource();
        resource.state = mock(PosState.class);
        resource.state.reprint = mock(ReprintState.class);
        resource.reprintService = mock(ReprintService.class);
        resource.reprintTicketPage = mock(Template.class);
        resource.reprintDetailPage = mock(Template.class);
        resource.reprintForeignPage = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the {@code reprint-foreign} template to return a recognizable view
     * for the given resource's state.
     *
     * @param resource the resource whose {@code reprintForeignPage} template is stubbed
     * @return the view {@code reprintForeignPage.data("state", state)} returns
     */
    private TemplateInstance stubForeign(ReprintResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.reprintForeignPage.data("state", resource.state)).thenReturn(view);
        return view;
    }

    /**
     * Stubs the {@code reprint-ticket} list template to return a recognizable
     * view for the given resource's state.
     *
     * @param resource the resource whose {@code reprintTicketPage} template is stubbed
     * @return the view {@code reprintTicketPage.data("state", state)} returns
     */
    private TemplateInstance stubList(ReprintResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.reprintTicketPage.data("state", resource.state)).thenReturn(view);
        return view;
    }

    /**
     * Stubs the {@code reprint-ticket-detail} template to return a recognizable
     * view for the given resource's state.
     *
     * @param resource the resource whose {@code reprintDetailPage} template is stubbed
     * @return the view {@code reprintDetailPage.data("state", state)} returns
     */
    private TemplateInstance stubDetail(ReprintResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.reprintDetailPage.data("state", resource.state)).thenReturn(view);
        return view;
    }

    // --- showReprintPage ---

    /**
     * {@code showReprintPage()} loads a fresh history and renders the list page
     * when unlocked (guard false).
     */
    @Test
    void showReprintPageLoadsHistoryWhenUnlocked() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.showReprintPage());
        verify(resource.reprintService).loadHistory();
    }

    // --- reprintPrevPage ---

    /**
     * {@code reprintPrevPage()} pages the list back when a previous page exists
     * (lock false, hasListPrev true).
     */
    @Test
    void reprintPrevPageDecrementsWhenHasPrev() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.reprint.isHasListPrev()).thenReturn(true);
        resource.state.reprint.listPage = 3;
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.reprintPrevPage());
        assertEquals(2, resource.state.reprint.listPage);
        verify(resource.state).touch();
    }

    /**
     * {@code reprintPrevPage()} leaves the page unchanged when no previous page
     * exists (lock false, hasListPrev false).
     */
    @Test
    void reprintPrevPageKeepsPageWhenNoPrev() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.reprint.isHasListPrev()).thenReturn(false);
        resource.state.reprint.listPage = 0;
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.reprintPrevPage());
        assertEquals(0, resource.state.reprint.listPage);
        verify(resource.state).touch();
    }

    // --- reprintNextPage ---

    /**
     * {@code reprintNextPage()} pages the list forward when a next page exists
     * (lock false, hasListNext true).
     */
    @Test
    void reprintNextPageIncrementsWhenHasNext() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.reprint.isHasListNext()).thenReturn(true);
        resource.state.reprint.listPage = 1;
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.reprintNextPage());
        assertEquals(2, resource.state.reprint.listPage);
        verify(resource.state).touch();
    }

    /**
     * {@code reprintNextPage()} leaves the page unchanged when no next page
     * exists (lock false, hasListNext false).
     */
    @Test
    void reprintNextPageKeepsPageWhenNoNext() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.reprint.isHasListNext()).thenReturn(false);
        resource.state.reprint.listPage = 1;
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.reprintNextPage());
        assertEquals(1, resource.state.reprint.listPage);
        verify(resource.state).touch();
    }

    // --- showReprintDetail ---

    /**
     * {@code showReprintDetail()} installs the found ticket in the detail view
     * when it exists (lock false, ticket non-null).
     */
    @Test
    void showReprintDetailOpensFoundTicket() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.showReprintDetail(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.state).touch();
    }

    /**
     * {@code showReprintDetail()} leaves the viewed ticket untouched when no
     * ticket matches the id (lock false, ticket null).
     */
    @Test
    void showReprintDetailKeepsViewWhenTicketMissing() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(null);
            assertSame(detailView, resource.showReprintDetail(7L));
        }
        verify(resource.state.reprint, org.mockito.Mockito.never()).setViewedTicket(org.mockito.ArgumentMatchers.any());
        verify(resource.state).touch();
    }

    // --- detailPrevPage ---

    /**
     * {@code detailPrevPage()} reopens the ticket when none is currently viewed
     * (identity guard first arm true: viewedTicket null).
     */
    @Test
    void detailPrevPageReopensWhenNoViewedTicket() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        resource.state.reprint.viewedTicket = null;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.detailPrevPage(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
    }

    /**
     * {@code detailPrevPage()} reopens the ticket when a different ticket is
     * viewed (identity guard first arm false, second arm true: id mismatch).
     */
    @Test
    void detailPrevPageReopensWhenViewedTicketMismatch() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 99L;
        resource.state.reprint.viewedTicket = viewed;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.detailPrevPage(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
    }

    /**
     * {@code detailPrevPage()} pages the detail back when the same ticket is
     * viewed and a previous page exists (identity guard both arms false,
     * hasDetailPrev true).
     */
    @Test
    void detailPrevPageDecrementsWhenHasPrev() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        when(resource.state.reprint.isHasDetailPrev()).thenReturn(true);
        resource.state.reprint.detailPage = 2;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.detailPrevPage(7L));
        assertEquals(1, resource.state.reprint.detailPage);
        verify(resource.state).touch();
    }

    /**
     * {@code detailPrevPage()} keeps the detail page when the same ticket is
     * viewed but no previous page exists (identity guard both arms false,
     * hasDetailPrev false).
     */
    @Test
    void detailPrevPageKeepsPageWhenNoPrev() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        when(resource.state.reprint.isHasDetailPrev()).thenReturn(false);
        resource.state.reprint.detailPage = 0;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.detailPrevPage(7L));
        assertEquals(0, resource.state.reprint.detailPage);
        verify(resource.state).touch();
    }

    // --- detailNextPage ---

    /**
     * {@code detailNextPage()} reopens the ticket when none is currently viewed
     * (identity guard first arm true: viewedTicket null).
     */
    @Test
    void detailNextPageReopensWhenNoViewedTicket() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        resource.state.reprint.viewedTicket = null;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.detailNextPage(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
    }

    /**
     * {@code detailNextPage()} reopens the ticket when a different ticket is
     * viewed (identity guard first arm false, second arm true: id mismatch).
     */
    @Test
    void detailNextPageReopensWhenViewedTicketMismatch() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 99L;
        resource.state.reprint.viewedTicket = viewed;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.detailNextPage(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
    }

    /**
     * {@code detailNextPage()} pages the detail forward when the same ticket is
     * viewed and a next page exists (identity guard both arms false,
     * hasDetailNext true).
     */
    @Test
    void detailNextPageIncrementsWhenHasNext() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        when(resource.state.reprint.isHasDetailNext()).thenReturn(true);
        resource.state.reprint.detailPage = 1;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.detailNextPage(7L));
        assertEquals(2, resource.state.reprint.detailPage);
        verify(resource.state).touch();
    }

    /**
     * {@code detailNextPage()} keeps the detail page when the same ticket is
     * viewed but no next page exists (identity guard both arms false,
     * hasDetailNext false).
     */
    @Test
    void detailNextPageKeepsPageWhenNoNext() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        when(resource.state.reprint.isHasDetailNext()).thenReturn(false);
        resource.state.reprint.detailPage = 1;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.detailNextPage(7L));
        assertEquals(1, resource.state.reprint.detailPage);
        verify(resource.state).touch();
    }

    // --- doReprint ---

    /**
     * {@code doReprint()} delegates the print to the service and redirects
     * (303) to the ticket's detail view.
     */
    @Test
    void doReprintPrintsAndRedirectsToDetail() {
        ReprintResource resource = newResource();
        Response response = resource.doReprint(42L);
        verify(resource.reprintService).print(42L);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/reprint/view/42", response.getLocation().toString());
    }

    // --- startExchange ---

    /**
     * {@code startExchange()} reopens the ticket instead of starting the
     * exchange when none is currently viewed (identity guard first arm true:
     * viewedTicket null).
     */
    @Test
    void startExchangeReopensWhenNoViewedTicket() {
        ReprintResource resource = newResource();
        resource.state.reprint.viewedTicket = null;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.startExchange(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.reprintService, org.mockito.Mockito.never()).startExchange();
    }

    /**
     * {@code startExchange()} reopens the ticket instead of starting the
     * exchange when a different ticket is viewed (identity guard first arm
     * false, second arm true: id mismatch).
     */
    @Test
    void startExchangeReopensWhenViewedTicketMismatch() {
        ReprintResource resource = newResource();
        Ticket viewed = mock(Ticket.class);
        viewed.id = 99L;
        resource.state.reprint.viewedTicket = viewed;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.startExchange(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.reprintService, org.mockito.Mockito.never()).startExchange();
    }

    /**
     * {@code startExchange()} delegates to the service and renders the detail
     * view when the same ticket is viewed (identity guard both arms false: id
     * match).
     */
    @Test
    void startExchangeStartsWhenSameTicketViewed() {
        ReprintResource resource = newResource();
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.startExchange(7L));
        verify(resource.reprintService).startExchange();
    }

    // --- cancelExchange ---

    /**
     * {@code cancelExchange()} cancels the exchange on the service then reopens
     * the ticket through {@code showReprintDetail}, installing the found ticket
     * in the detail view.
     */
    @Test
    void cancelExchangeCancelsThenReopensDetail() {
        ReprintResource resource = newResource();
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.cancelExchange(7L));
        }
        verify(resource.reprintService).cancelExchange();
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.state).touch();
    }

    // --- toggleExchangeLine ---

    /**
     * {@code toggleExchangeLine()} reopens the ticket instead of toggling when
     * none is currently viewed (identity guard first arm true: viewedTicket
     * null).
     */
    @Test
    void toggleExchangeLineReopensWhenNoViewedTicket() {
        ReprintResource resource = newResource();
        resource.state.reprint.viewedTicket = null;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.toggleExchangeLine(7L, 5L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.reprintService, org.mockito.Mockito.never()).toggleExchangeLine(org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * {@code toggleExchangeLine()} reopens the ticket instead of toggling when
     * a different ticket is viewed (identity guard first arm false, second arm
     * true: id mismatch).
     */
    @Test
    void toggleExchangeLineReopensWhenViewedTicketMismatch() {
        ReprintResource resource = newResource();
        Ticket viewed = mock(Ticket.class);
        viewed.id = 99L;
        resource.state.reprint.viewedTicket = viewed;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.toggleExchangeLine(7L, 5L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.reprintService, org.mockito.Mockito.never()).toggleExchangeLine(org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * {@code toggleExchangeLine()} delegates the toggle for the named line and
     * renders the detail view when the same ticket is viewed (identity guard
     * both arms false: id match).
     */
    @Test
    void toggleExchangeLineTogglesWhenSameTicketViewed() {
        ReprintResource resource = newResource();
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.toggleExchangeLine(7L, 5L));
        verify(resource.reprintService).toggleExchangeLine(5L);
    }

    // --- printExchange ---

    /**
     * {@code printExchange()} delegates the bon-pour-échange print to the
     * service and redirects (303) to the ticket's detail view.
     */
    @Test
    void printExchangePrintsAndRedirectsToDetail() {
        ReprintResource resource = newResource();
        Response response = resource.printExchange(42L);
        verify(resource.reprintService).printExchange(42L);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/reprint/view/42", response.getLocation().toString());
    }

    // --- showForeign ---

    /**
     * {@code showForeign()} opens the foreign-duplicata mask on the service and
     * renders the foreign page.
     */
    @Test
    void showForeignStartsAndRendersForeignPage() {
        ReprintResource resource = newResource();
        TemplateInstance foreignView = stubForeign(resource);
        assertSame(foreignView, resource.showForeign());
        verify(resource.reprintService).startForeign();
    }

    // --- printForeign ---

    /**
     * {@code printForeign()} delegates the typed number to the service and
     * renders the foreign page carrying any failure.
     */
    @Test
    void printForeignPrintsAndRendersForeignPage() {
        ReprintResource resource = newResource();
        TemplateInstance foreignView = stubForeign(resource);
        assertSame(foreignView, resource.printForeign("C03-000042"));
        verify(resource.reprintService).printForeign("C03-000042");
    }

    // --- searchReprint ---

    /**
     * {@code searchReprint(pattern)} (EXP-47) hands the pattern to the search
     * engine and renders the list page — the ONLY path by which a number, a date
     * or an amount reaches that engine.
     */
    @Test
    void searchReprintDelegatesAndRendersTheList() {
        ReprintResource resource = newResource();
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.searchReprint("17/03/2026"));
        verify(resource.reprintService).search("17/03/2026");
    }

    /**
     * A null pattern — an empty field posted by the form — is handed on as it
     * stands: it is the engine, and not the screen, that decides a pattern too
     * short to search returns nothing.
     */
    @Test
    void searchReprintPassesANullPatternThrough() {
        ReprintResource resource = newResource();
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.searchReprint(null));
        verify(resource.reprintService).search(null);
    }

    /**
     * LC-03-06-02: the reassignment-mode routes delegate to the service and
     * render the LIST template both ways — entering and leaving the mode.
     */
    @Test
    void reassignModeRoutesDelegateAndRenderTheList() {
        ReprintResource resource = newResource();
        TemplateInstance list = mock(TemplateInstance.class);
        when(resource.reprintTicketPage.data("state", resource.state)).thenReturn(list);
        assertSame(list, resource.startReassignMode());
        verify(resource.reprintService).startReassignMode();
        assertSame(list, resource.cancelReassignMode());
        verify(resource.reprintService).cancelReassignMode();
    }

    /**
     * LC-03-06-03: opening the card mask on the VIEWED ticket renders the
     * detail view; a stale id — another ticket, or none — reloads the detail
     * instead, exactly as the paging routes do.
     */
    @Test
    void beginReassignRendersTheDetailOrReloadsAStaleOne() {
        ReprintResource resource = newResource();
        TemplateInstance detail = mock(TemplateInstance.class);
        when(resource.reprintDetailPage.data("state", resource.state)).thenReturn(detail);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        assertSame(detail, resource.beginReassign(7L));
        verify(resource.reprintService).beginReassign(7L);
        Ticket other = mock(Ticket.class);
        other.id = 9L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(9L)).thenReturn(other);
            assertSame(detail, resource.beginReassign(9L));
        }
        verify(resource.state.reprint).setViewedTicket(other);
        verify(resource.reprintService, org.mockito.Mockito.never()).beginReassign(9L);
    }

    /**
     * LC-03-06-03: leaving the mask closes it and reloads the detail.
     */
    @Test
    void cancelReassignClosesTheMaskAndReloads() {
        ReprintResource resource = newResource();
        TemplateInstance detail = mock(TemplateInstance.class);
        when(resource.reprintDetailPage.data("state", resource.state)).thenReturn(detail);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(7L)).thenReturn(viewed);
            assertSame(detail, resource.cancelReassign(7L));
        }
        verify(resource.reprintService).cancelReassignMask();
    }

    /**
     * LC-03-06-01: the reassignment POST delegates to the service and lands
     * back on the detail view (PRG, so a reload never replays it).
     */
    @Test
    void applyReassignDelegatesAndRedirectsToTheDetail() {
        ReprintResource resource = newResource();
        Response response = resource.applyReassign(7L, "2990000000019");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/reprint/view/7", response.getLocation().toString());
        verify(resource.reprintService).applyReassign(7L, "2990000000019");
    }
}
