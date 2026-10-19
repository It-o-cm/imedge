package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.setting.PosList;
import com.intermarche.edge.domain.setting.PosListItem;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link AdminListResource}, the administered-lists screen
 * ({@code BO-04-02-07}, {@code BO-04-03-12}).
 *
 * <p>No database and no Quarkus context: the custom finder of
 * {@link PosList} and the inherited {@code findById} are intercepted with
 * {@code mockStatic} — on {@link PosList} and on {@link PanacheEntityBase}
 * respectively, because the two statics are declared on different classes.
 * The redirect's query string carries the outcome, which is what each case
 * asserts.
 *
 * <p>Branch enumeration: the two mandatory fields of a list and of an entry,
 * each absent in turn; a code already taken by ANOTHER list; an unknown id on
 * save, on delete, on an entry save and on an entry delete; the migration
 * gesture finding everything already there; and the pure helpers — the code
 * derivation on a label of letters, of separators and of nothing usable, the
 * code normalisation on a blank and on an over-long value, the kind fallback,
 * and the weight reader on a comma, a negative and an unreadable value.
 *
 * <p>The CREATING arm of the migration persists, so it is left to the
 * end-to-end run: a persist outside a transaction would be the only thing
 * such a case proved.
 */
class AdminListResourceTest {

    /**
     * Builds a form carrying everything a list creation needs.
     *
     * @return the valid form
     */
    private MultivaluedMap<String, String> listForm() {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("code", "service");
        form.putSingle("label", "Demandes de service");
        form.putSingle("kind", "SERVICE_REQUEST");
        form.putSingle("active", "on");
        return form;
    }

    /**
     * Builds a form carrying everything an entry creation needs.
     *
     * @return the valid form
     */
    private MultivaluedMap<String, String> itemForm() {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("listId", "4");
        form.putSingle("code", "ANNUL");
        form.putSingle("label", "Annulation");
        form.putSingle("displayOrder", "10");
        form.putSingle("severity", "2");
        return form;
    }

    /**
     * Reads the notice the redirect carries.
     *
     * @param response the redirect
     * @return its query string, which carries the notice
     */
    private String notice(Response response) {
        return response.getLocation().getQuery();
    }

    /**
     * BO-04-03-12: code and heading are the two a list cannot do without.
     */
    @Test
    void aListNeedsItsCodeAndItsLabel() {
        for (String missing : new String[]{"code", "label"}) {
            MultivaluedMap<String, String> form = listForm();
            form.remove(missing);
            Response response = new AdminListResource().save(form);
            assertTrue(notice(response).contains("obligatoires"), missing);
        }
    }

    /**
     * BO-04-03-12: a code already carried by ANOTHER list is refused.
     */
    @Test
    void aCodeIsTakenOnlyByAnotherList() {
        PosList other = mock(PosList.class);
        other.id = 7L;
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findByCode("SERVICE")).thenReturn(other);
            Response response = new AdminListResource().save(listForm());
            assertTrue(notice(response).contains("pris"));
        }
    }

    /**
     * An id nothing answers refuses, on the list and on the entry, whichever
     * gesture carries it.
     */
    @Test
    void anUnknownIdRefusesEveryGesture() {
        MultivaluedMap<String, String> byId = new MultivaluedHashMap<>();
        byId.putSingle("id", "9");
        MultivaluedMap<String, String> savedList = listForm();
        savedList.putSingle("id", "9");
        try (MockedStatic<PosList> lists = mockStatic(PosList.class);
             MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            lists.when(() -> PosList.findByCode("SERVICE")).thenReturn(null);
            base.when(() -> PosList.findById(9L)).thenReturn(null);
            base.when(() -> PosListItem.findById(9L)).thenReturn(null);
            AdminListResource resource = new AdminListResource();
            assertTrue(notice(resource.save(savedList)).contains("inconnue"));
            assertTrue(notice(resource.delete(byId)).contains("inconnue"));
            assertTrue(notice(resource.deleteItem(byId)).contains("inconnue"));
        }
    }

    /**
     * An entry posted against a list nothing answers refuses before anything
     * is read off the form.
     */
    @Test
    void anEntryNeedsAnExistingList() {
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PosList.findById(4L)).thenReturn(null);
            Response response = new AdminListResource().saveItem(itemForm());
            assertTrue(notice(response).contains("Liste inconnue"));
        }
    }

    /**
     * BO-04-02-07: code and label are the two an entry cannot do without.
     */
    @Test
    void anEntryNeedsItsCodeAndItsLabel() {
        PosList parent = mock(PosList.class);
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PosList.findById(4L)).thenReturn(parent);
            for (String missing : new String[]{"code", "label"}) {
                MultivaluedMap<String, String> form = itemForm();
                form.remove(missing);
                Response response = new AdminListResource().saveItem(form);
                assertTrue(notice(response).contains("obligatoires"), missing);
            }
        }
    }

    /**
     * BO-04-02-07: the migration gesture creates nothing when the four
     * standard lists are already there, and says so.
     */
    @Test
    void theMigrationCreatesNothingTwice() {
        AdminListResource resource = new AdminListResource();
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            for (String code : new String[]{"SERVICE", "JUSTIF-DEPENSE", "RETOURS", "TARES"}) {
                lists.when(() -> PosList.findByCode(code)).thenReturn(mock(PosList.class));
            }
            Response response = resource.bootstrap();
            assertTrue(notice(response).contains("existent"));
        }
    }

    /**
     * The code derivation keeps the letters and the digits, folds every run of
     * anything else into one dash, drops a trailing one, and answers a
     * placeholder for a label carrying nothing usable.
     */
    @Test
    void theCodeIsDerivedFromTheLabel() {
        assertEquals("ANNULATION", AdminListResource.slug("Annulation"));
        assertEquals("PRIX-ETIQUETTE", AdminListResource.slug("Prix / etiquette"));
        assertEquals("RETOUR-2", AdminListResource.slug("  retour 2 !!  "));
        assertEquals("X", AdminListResource.slug("///"));
        assertEquals(AdminListResource.MAX_CODE,
                AdminListResource.slug("A".repeat(AdminListResource.MAX_CODE + 10)).length());
    }

    /**
     * A posted code is trimmed, raised to upper case and cut to the column's
     * length; nothing typed answers null.
     */
    @Test
    void thePostedCodeIsNormalised() {
        assertNull(AdminListResource.code(null));
        assertNull(AdminListResource.code("   "));
        assertEquals("TARES", AdminListResource.code("  tares "));
        assertEquals(AdminListResource.MAX_CODE,
                AdminListResource.code("b".repeat(AdminListResource.MAX_CODE + 5)).length());
    }

    /**
     * The kind reader answers what was posted, and JUSTIFICATION for an
     * absent, a blank or an unknown value.
     */
    @Test
    void theKindFallsBackOnJustification() {
        assertEquals(PosList.Kind.RETURN_TYPE, AdminListResource.kind(" return_type "));
        assertEquals(PosList.Kind.JUSTIFICATION, AdminListResource.kind(null));
        assertEquals(PosList.Kind.JUSTIFICATION, AdminListResource.kind("  "));
        assertEquals(PosList.Kind.JUSTIFICATION, AdminListResource.kind("CE-QUE-VOUS-VOULEZ"));
    }

    /**
     * The migrated weight accepts a comma for a point and drops a negative or
     * an unreadable one.
     */
    @Test
    void theMigratedWeightIsReadLeniently() {
        assertEquals(new BigDecimal("0.035"), AdminListResource.weight(" 0,035 "));
        assertNull(AdminListResource.weight("-0.010"));
        assertNull(AdminListResource.weight("lourd"));
    }
}
