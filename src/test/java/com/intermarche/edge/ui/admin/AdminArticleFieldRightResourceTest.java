package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.ArticleFieldRight;
import com.intermarche.edge.domain.people.Profile;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminArticleFieldRightResource} (BO-02-03-33): the
 * grid with reserved and open data, the save replacing the grid, and every arm
 * of the tick parser.
 */
class AdminArticleFieldRightResourceTest {

    /**
     * The grid marks the reserved datum with its profiles and leaves the
     * others open.
     */
    @Test
    @SuppressWarnings("unchecked")
    void pageBuildsTheGrid() {
        AdminArticleFieldRightResource resource = new AdminArticleFieldRightResource();
        resource.adminArticleFieldRights = mock(Template.class);
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminArticleFieldRights.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        ArticleFieldRight vat = new ArticleFieldRight();
        vat.fieldCode = "VAT";
        vat.profileId = 4L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleFieldRight.list("order by fieldCode, profileId")).thenReturn(List.of(vat));
            panache.when(() -> Profile.listAll(any(io.quarkus.panache.common.Sort.class))).thenReturn(List.of());
            assertEquals(instance, resource.page("n", true));
        }
        ArgumentCaptor<Object> rows = ArgumentCaptor.forClass(Object.class);
        verify(resource.adminArticleFieldRights).data(eq("rows"), rows.capture());
        List<AdminArticleFieldRightResource.Row> grid = (List<AdminArticleFieldRightResource.Row>) rows.getValue();
        assertEquals(ArticleField.values().length, grid.size());
        AdminArticleFieldRightResource.Row vatRow = grid.get(ArticleField.VAT.ordinal());
        assertTrue(vatRow.restricted);
        assertTrue(vatRow.profileIds.contains(4L));
        assertFalse(grid.get(0).restricted);
    }

    /**
     * Saving wipes the grid and stores the readable ticks only; a form without
     * ticks stores nothing.
     */
    @Test
    void saveReplacesTheGrid() {
        AdminArticleFieldRightResource resource = new AdminArticleFieldRightResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("right", "VAT:4");
        form.add("right", "NOPE:4");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ArticleFieldRight> created = mockConstruction(ArticleFieldRight.class)) {
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            panache.verify(ArticleFieldRight::deleteAll);
            assertEquals(1, created.constructed().size());
            verify(created.constructed().get(0)).persist();
            resource.save(new MultivaluedHashMap<>());
            assertEquals(1, created.constructed().size());
        }
    }

    /**
     * The parser refuses a null tick, a tick without colon or without datum, an
     * unreadable profile and an unknown datum, and reads a valid one.
     */
    @Test
    void parseReadsOnlyValidTicks() {
        assertNull(AdminArticleFieldRightResource.parse(null));
        assertNull(AdminArticleFieldRightResource.parse("VAT"));
        assertNull(AdminArticleFieldRightResource.parse(":4"));
        assertNull(AdminArticleFieldRightResource.parse("VAT:x"));
        assertNull(AdminArticleFieldRightResource.parse("NOPE:4"));
        ArticleFieldRight right = AdminArticleFieldRightResource.parse("EAN:12");
        assertEquals("EAN", right.fieldCode);
        assertEquals(12L, right.profileId);
    }
}
