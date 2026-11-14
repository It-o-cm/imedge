package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.payment.PrepaidCardType;
import com.intermarche.edge.domain.people.Menu;
import com.intermarche.edge.domain.setting.Feature;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminPrepaidCardTypeResource}, the prepaid-card-type
 * screen (BO-03-02-37).
 *
 * <p>Branch enumeration: every guard of the save path (no code, a code too
 * long, no label, no protocol, an unknown protocol, no range, an unreadable
 * range), creation and update, deletion and toggle of an unknown and a known
 * type, the toggle both ways, and the range reader on every shape.
 */
class AdminPrepaidCardTypeResourceTest {

    /**
     * Builds a resource with a mocked template.
     *
     * @return the resource
     */
    private AdminPrepaidCardTypeResource newResource() {
        AdminPrepaidCardTypeResource resource = new AdminPrepaidCardTypeResource();
        resource.adminPrepaidCards = mock(Template.class);
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
     * Reads the decoded location of a redirect.
     *
     * @param response the redirect
     * @return its decoded location
     */
    private String location(Response response) {
        return URLDecoder.decode(response.getLocation().toString(), StandardCharsets.UTF_8);
    }

    /**
     * The screen is declared in the parametrage menu, and the page lists the
     * types and the protocols.
     */
    @Test
    void thePageListsTheTypes() {
        assertEquals("prepaid_card_type", Feature.PREPAID_CARD_TYPE.getKey());
        assertEquals("/admin/prepaid-cards", Feature.PREPAID_CARD_TYPE.getPath());
        assertEquals(Menu.PARAMETRAGE, Feature.PREPAID_CARD_TYPE.getMenu());
        AdminPrepaidCardTypeResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminPrepaidCards.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        PrepaidCardType type = new PrepaidCardType();
        try (MockedStatic<PrepaidCardType> types = mockStatic(PrepaidCardType.class)) {
            types.when(PrepaidCardType::findAllOrdered).thenReturn(List.of(type));
            assertSame(instance, resource.list(null, true));
        }
        verify(resource.adminPrepaidCards).data("types", List.of(type));
        verify(instance).data("protocols", List.of("NEXO", "LOCAL"));
    }

    /**
     * Every guard of the save path refuses before anything is written.
     */
    @Test
    void saveRefusesWhatCannotBeAType() {
        AdminPrepaidCardTypeResource resource = newResource();
        assertTrue(location(resource.save(form())).contains("Le code du type de carte est obligatoire"));
        assertTrue(location(resource.save(form("code", "X".repeat(41), "label", "L")))
                .contains("Le code du type de carte est obligatoire"));
        assertTrue(location(resource.save(form("code", "GIFT"))).contains("Le libellé du type de carte est obligatoire."));
        assertTrue(location(resource.save(form("code", "GIFT", "label", "Carte")))
                .contains("Protocole de carte inconnu."));
        assertTrue(location(resource.save(form("code", "GIFT", "label", "Carte", "protocol", "ISO")))
                .contains("Protocole de carte inconnu."));
        assertTrue(location(resource.save(form("code", "GIFT", "label", "Carte", "protocol", "nexo")))
                .contains("Plage de cartes invalide : "));
        assertTrue(location(resource.save(form("code", "GIFT", "label", "Carte", "protocol", "nexo",
                "ranges", "6299;62-99"))).contains("Plage de cartes invalide : 62-99"));
    }

    /**
     * A new code creates a type, every column set before it is persisted; a
     * known code updates it, unposted gestures turning off.
     */
    @Test
    void saveCreatesThenUpdates() {
        AdminPrepaidCardTypeResource resource = newResource();
        PrepaidCardType existing = mock(PrepaidCardType.class);
        existing.paymentAllowed = true;
        try (MockedStatic<PrepaidCardType> types = mockStatic(PrepaidCardType.class);
             MockedConstruction<PrepaidCardType> created = mockConstruction(PrepaidCardType.class,
                     (mock, context) -> org.mockito.Mockito.doAnswer(invocation -> {
                         assertEquals("GIFT", mock.code);
                         assertEquals("6299:16", mock.ranges);
                         return null;
                     }).when(mock).persist())) {
            types.when(() -> PrepaidCardType.findByCode("BOX")).thenReturn(existing);
            Response createdResponse = resource.save(form("code", "gift", "label", "Carte cadeau",
                    "protocol", "local", "provider", "ILLICADO", "ranges", "6299:16",
                    "paymentAllowed", "on", "balanceAllowed", "on", "activationAllowed", "on",
                    "displayOrder", "3", "active", "on"));
            assertTrue(location(createdResponse).contains("Type de carte créé."));
            PrepaidCardType row = created.constructed().get(0);
            assertEquals("LOCAL", row.protocol);
            assertEquals("ILLICADO", row.provider);
            assertTrue(row.paymentAllowed && row.balanceAllowed && row.activationAllowed && row.active);
            assertEquals(3, row.displayOrder);
            verify(row).persist();
            Response updated = resource.save(form("code", "box", "label", "Coffret", "protocol", "NEXO",
                    "ranges", "296"));
            assertTrue(location(updated).contains("Type de carte enregistré."));
            assertFalse(existing.paymentAllowed);
            assertNull(existing.provider);
            assertEquals("NEXO", existing.protocol);
            verify(existing).persist();
        }
    }

    /**
     * Delete and toggle refuse an unknown type; a known one is deleted, or
     * switched both ways.
     */
    @Test
    void deleteAndToggle() {
        AdminPrepaidCardTypeResource resource = newResource();
        PrepaidCardType type = mock(PrepaidCardType.class);
        type.active = true;
        try (MockedStatic<PrepaidCardType> types = mockStatic(PrepaidCardType.class)) {
            types.when(() -> PrepaidCardType.findByCode("GIFT")).thenReturn(type);
            assertTrue(location(resource.delete(form())).contains("Type de carte inconnu."));
            assertTrue(location(resource.toggle(form())).contains("Type de carte inconnu."));
            assertTrue(location(resource.toggle(form("code", "GIFT"))).contains("Type de carte désactivé."));
            assertTrue(location(resource.toggle(form("code", "GIFT"))).contains("Type de carte activé."));
            assertTrue(location(resource.delete(form("code", "GIFT"))).contains("Type de carte supprimé."));
            verify(type).delete();
        }
    }

    /**
     * The range reader: nothing written, a readable list, a zero length, a
     * length that is not a number and an empty entry.
     */
    @Test
    void theRangeReaderReadsEveryShape() {
        assertEquals("", AdminPrepaidCardTypeResource.invalidRange(null));
        assertNull(AdminPrepaidCardTypeResource.invalidRange("6299; 296:13"));
        assertEquals("62:0", AdminPrepaidCardTypeResource.invalidRange("62:0"));
        assertEquals("62:x", AdminPrepaidCardTypeResource.invalidRange("62:x"));
        assertEquals("", AdminPrepaidCardTypeResource.invalidRange("62;;63"));
    }
}
