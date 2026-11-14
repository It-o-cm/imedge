package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.TechnicalEventService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link MenuWatchFilter} (BO-04-03-04).
 */
class MenuWatchFilterTest {

    /**
     * A handler declaring its screen.
     */
    @FeatureAccess(value = com.intermarche.edge.domain.setting.Feature.JOURNAL)
    void declared() {
        // Only its annotation is read.
    }

    /**
     * A handler declaring nothing.
     */
    void undeclared() {
        // Only its absence of annotation is read.
    }

    /**
     * Builds the filter on a handler, the given menus watched.
     *
     * @param handler the handler's name, or null for none
     * @param watched the administered list
     * @return the filter
     * @throws Exception never
     */
    private MenuWatchFilter filter(String handler, String watched) throws Exception {
        MenuWatchFilter filter = new MenuWatchFilter();
        filter.resourceInfo = mock(ResourceInfo.class);
        Method method = handler == null ? null : MenuWatchFilterTest.class.getDeclaredMethod(handler);
        when(filter.resourceInfo.getResourceMethod()).thenReturn(method);
        filter.posSettingsService = mock(PosSettingsService.class);
        when(filter.posSettingsService.watchedMenus()).thenReturn(watched);
        filter.technicalEventService = mock(TechnicalEventService.class);
        filter.currentUser = mock(CurrentUser.class);
        when(filter.currentUser.getName()).thenReturn("mcurie");
        return filter;
    }

    /**
     * Builds a request.
     *
     * @param method the HTTP method
     * @param accept the Accept header
     * @param path the path
     * @return the request
     */
    private ContainerRequestContext request(String method, String accept, String path) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getHeaderString("Accept")).thenReturn(accept);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(request.getUriInfo()).thenReturn(uri);
        return request;
    }

    /**
     * Runs every transaction in place.
     *
     * @param tx the static mock of the transaction API
     */
    private void inPlace(MockedStatic<QuarkusTransaction> tx) {
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(runner).run(any(Runnable.class));
    }

    /**
     * A watched screen opened is journalled with the operator's badge and the
     * screen's code; a holder without account is journalled without badge.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aWatchedScreenIsJournalled() throws Exception {
        MenuWatchFilter filter = filter("declared", "journal");
        Employee employee = new Employee();
        employee.badgeId = "11111111";
        PanacheQuery<PanacheEntityBase> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(employee, (PanacheEntityBase) null);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            panache.when(() -> PanacheEntityBase.find("loginName", "mcurie")).thenReturn(found);
            filter.filter(request("GET", "text/html", "admin/journal"));
            filter.filter(request("GET", "text/html", "/admin/journal"));
        }
        verify(filter.technicalEventService).record(TechnicalEvent.EventType.BACK_OFFICE_MENU_OPENED,
                "mcurie : Journal (/admin/journal)", "11111111", null, null, "journal");
        verify(filter.technicalEventService).record(TechnicalEvent.EventType.BACK_OFFICE_MENU_OPENED,
                "mcurie : Journal (/admin/journal)", null, null, null, "journal");
    }

    /**
     * Nothing is journalled off a page opening or off the watched list, and a
     * journal that fails never refuses the screen.
     */
    @Test
    void otherRequestsAreNotJournalled() throws Exception {
        filter(null, "*").filter(request("GET", "text/html", "x"));
        MenuWatchFilter unresolved = filter("declared", "*");
        unresolved.resourceInfo = null;
        unresolved.filter(request("GET", "text/html", "x"));
        verifyNoInteractions(unresolved.technicalEventService);
        MenuWatchFilter undeclared = filter("undeclared", "*");
        undeclared.filter(request("GET", "text/html", "x"));
        verifyNoInteractions(undeclared.technicalEventService);
        MenuWatchFilter post = filter("declared", "*");
        post.filter(request("POST", "text/html", "x"));
        post.filter(request("GET", null, "x"));
        post.filter(request("GET", "application/json", "x"));
        MenuWatchFilter unwatched = filter("declared", "settings");
        unwatched.filter(request("GET", "text/html", "admin/journal"));
        verify(unwatched.technicalEventService, never()).record(any(), anyString(), any(), any(), any(), any());
        verifyNoInteractions(post.technicalEventService);
        MenuWatchFilter failing = filter("declared", "*");
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            doThrow(new IllegalStateException("down")).when(runner).run(any(Runnable.class));
            failing.filter(request("GET", "text/html", "admin/journal"));
        }
    }

    /**
     * A screen is watched by its code, by the beginning of its path, or by *.
     */
    @Test
    void watchedReadsCodesPathsAndStar() {
        assertFalse(MenuWatchFilter.watched(null, "journal", "/admin/journal"));
        assertFalse(MenuWatchFilter.watched(" ; settings", "journal", "/admin/journal"));
        assertTrue(MenuWatchFilter.watched("JOURNAL", "journal", "/admin/journal"));
        assertTrue(MenuWatchFilter.watched("/admin/jour", "journal", "/admin/journal"));
        assertFalse(MenuWatchFilter.watched("admin/jour", "journal", "/admin/journal"));
        assertFalse(MenuWatchFilter.watched("/admin/settings", "journal", "/admin/journal"));
        assertTrue(MenuWatchFilter.watched("*", "journal", "/admin/journal"));
    }
}
