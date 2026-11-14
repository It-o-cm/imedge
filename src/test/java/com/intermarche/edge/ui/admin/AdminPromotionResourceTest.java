package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.Promotion;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminPromotionResource} (BO-06-08-01): the page of the
 * administered promotions, the creation and update of one — its number and
 * label required, its type and upstream funding — and its deletion.
 */
class AdminPromotionResourceTest {

    /**
     * Builds the resource over a mocked template.
     *
     * @return the resource
     */
    private AdminPromotionResource resource() {
        AdminPromotionResource resource = new AdminPromotionResource();
        resource.adminPromotions = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the promotion finder.
     *
     * @param panache the static mock
     * @param code the code searched
     * @param found the promotion found, or null
     */
    @SuppressWarnings("unchecked")
    private void finds(MockedStatic<PanacheEntityBase> panache, String code, Promotion found) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(found);
        panache.when(() -> PanacheEntityBase.find("code", code)).thenReturn(query);
    }

    /**
     * Builds a form.
     *
     * @param pairs the field names and values
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    /**
     * The page lists the promotions with its notice, successful unless told
     * otherwise.
     */
    @Test
    void thePageListsThePromotions() {
        AdminPromotionResource resource = resource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminPromotions.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        Promotion promotion = new Promotion();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by code")).thenReturn(List.of(promotion));
            assertSame(instance, resource.page("Fait.", null));
            verify(resource.adminPromotions).data("promotions", List.of(promotion));
            verify(instance).data("notice", "Fait.");
            verify(instance).data("noticeOk", true);
            resource.page(null, "false");
            verify(instance).data("noticeOk", false);
        }
    }

    /**
     * A promotion without number or label is refused.
     */
    @Test
    void anIncompletePromotionIsRefused() {
        Response missingLabel = resource().save(form("code", "OFF1"));
        assertEquals(303, missingLabel.getStatus());
        assertTrue(missingLabel.getLocation().toString().contains("noticeOk=false"));
        Response missingCode = resource().save(form("label", "Lot de 3"));
        assertTrue(missingCode.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * A new promotion is created and persisted with its type and funding.
     */
    @Test
    void aNewPromotionIsCreated() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Promotion> built = mockConstruction(Promotion.class)) {
            finds(panache, "OFF1", null);
            Response response = resource().save(form("code", " OFF1 ", "label", "Moins 20 %", "kind",
                    Promotion.BY_PERCENT, "upstreamFunded", "on"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            Promotion created = built.constructed().get(0);
            assertEquals("OFF1", created.code);
            assertEquals("Moins 20 %", created.label);
            assertEquals(Promotion.BY_PERCENT, created.kind);
            assertTrue(created.upstreamFunded);
            verify(created).persist();
        }
    }

    /**
     * A known promotion is updated in place, its type read as value when not
     * a percentage, its funding unticked.
     */
    @Test
    void aKnownPromotionIsUpdated() {
        Promotion known = mock(Promotion.class);
        known.upstreamFunded = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            finds(panache, "OFF1", known);
            resource().save(form("code", "OFF1", "label", "Lot de 3", "kind", "OTHER"));
        }
        assertEquals("Lot de 3", known.label);
        assertEquals(Promotion.BY_VALUE, known.kind);
        assertFalse(known.upstreamFunded);
        verify(known, never()).persist();
    }

    /**
     * A known promotion is deleted; an unknown one is reported.
     */
    @Test
    void aPromotionIsDeleted() {
        Promotion known = mock(Promotion.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            finds(panache, "OFF1", known);
            finds(panache, "OFF9", null);
            Response deleted = resource().delete(form("code", "OFF1"));
            assertTrue(deleted.getLocation().toString().contains("noticeOk=true"));
            verify(known).delete();
            Response unknown = resource().delete(form("code", "OFF9"));
            assertTrue(unknown.getLocation().toString().contains("noticeOk=false"));
            assertTrue(resource().delete(form()).getLocation().toString().contains("noticeOk=false"));
        }
        verify(known, never()).persist();
    }
}
