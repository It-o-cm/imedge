package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.Menu;
import com.intermarche.pos.domain.setting.Feature;
import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.domain.sync.RegisterCommand;
import com.intermarche.pos.security.CurrentUser;
import com.intermarche.pos.service.sync.RegisterCommandService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminRegisterMessageResource}.
 * <p>
 * Plain JUnit 5 + Mockito, no Quarkus and no database. Every guard of the send
 * path (no terminal, unknown terminal, blank message) and the success arm are
 * exercised (EXP-20).
 */
class AdminRegisterMessageResourceTest {

    /**
     * Builds a resource with a mocked template and command service.
     *
     * @return the resource
     */
    private AdminRegisterMessageResource newResource() {
        AdminRegisterMessageResource resource = new AdminRegisterMessageResource();
        resource.adminRegisterMessages = mock(Template.class);
        resource.registerCommandService = mock(RegisterCommandService.class);
        resource.currentUser = mock(CurrentUser.class);
        when(resource.currentUser.getName()).thenReturn("alice");
        return resource;
    }

    /**
     * Builds a posted form from key/value pairs.
     *
     * @param pairs the alternating keys and values
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> posted = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            posted.putSingle(pairs[i], pairs[i + 1]);
        }
        return posted;
    }

    /**
     * Wraps a first-result in a Panache query mock.
     *
     * @param row the checkout point the query resolves, or null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<CheckoutPoint> query(CheckoutPoint row) {
        PanacheQuery<CheckoutPoint> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * The screen is declared in the supervision menu (EXP-20).
     */
    @Test
    void theScreenIsDeclaredInTheMenu() {
        Feature feature = Feature.REGISTER_MESSAGE;
        assertEquals("register_message", feature.getKey());
        assertEquals("/admin/register-messages", feature.getPath());
        assertEquals(Menu.SUPERVISION, feature.getMenu());
    }

    /**
     * The page lists the checkout points a message can be aimed at.
     */
    @Test
    void messagesPageListsThePoints() {
        AdminRegisterMessageResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminRegisterMessages.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        CheckoutPoint point = new CheckoutPoint();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.list("order by terminalId")).thenReturn(List.of(point));
            assertSame(instance, resource.messagesPage(null, true));
            verify(resource.adminRegisterMessages).data("points", List.of(point));
        }
    }

    /**
     * A send with no terminal chosen is refused (first guard).
     */
    @Test
    void sendRefusesWithNoTerminal() {
        AdminRegisterMessageResource resource = newResource();
        Response response = resource.sendMessage(form("message", "Coucou"));
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        verify(resource.registerCommandService, never()).issue(anyString(), any(), anyString(), anyString());
    }

    /**
     * A send to a terminal the store does not know is refused.
     */
    @Test
    void sendRefusesAnUnknownTerminal() {
        AdminRegisterMessageResource resource = newResource();
        PanacheQuery<CheckoutPoint> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.find("terminalId", "POS09")).thenReturn(absent);
            Response response = resource.sendMessage(form("terminalId", "POS09", "message", "Coucou"));
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            verify(resource.registerCommandService, never()).issue(anyString(), any(), anyString(), anyString());
        }
    }

    /**
     * A send with a known terminal but a blank message is refused (message guard).
     */
    @Test
    void sendRefusesABlankMessage() {
        AdminRegisterMessageResource resource = newResource();
        PanacheQuery<CheckoutPoint> present = query(new CheckoutPoint());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.find("terminalId", "POS01")).thenReturn(present);
            Response response = resource.sendMessage(form("terminalId", "POS01", "message", "   "));
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            verify(resource.registerCommandService, never()).issue(anyString(), any(), anyString(), anyString());
        }
    }

    /**
     * A valid send issues a DISPLAY_MESSAGE order to the register, recording the
     * signed-in operator as its issuer (EXP-20).
     */
    @Test
    void sendIssuesTheDisplayMessageOrder() {
        AdminRegisterMessageResource resource = newResource();
        PanacheQuery<CheckoutPoint> present = query(new CheckoutPoint());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.find("terminalId", "POS01")).thenReturn(present);
            Response response = resource.sendMessage(form("terminalId", "POS01", "message", "Pause dans 5 min"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            verify(resource.registerCommandService, times(1)).issue(
                    eq("POS01"), eq(RegisterCommand.CommandType.DISPLAY_MESSAGE),
                    eq("alice"), eq("Pause dans 5 min"));
        }
    }
}
