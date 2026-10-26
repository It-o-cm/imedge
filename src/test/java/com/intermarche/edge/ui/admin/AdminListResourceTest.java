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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * Reads the notice the redirect carries, decoded as the browser would
     * read it — the notice is URL-encoded on the way out.
     *
     * @param response the redirect
     * @return its query string, decoded, which carries the notice
     */
    private String notice(Response response) {
        return java.net.URLDecoder.decode(response.getLocation().getQuery(),
                java.nio.charset.StandardCharsets.UTF_8);
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
    /** BO-04-03-12: creation persists the normalized header and an update retains its own duplicate code. */
    @Test
    void createAndUpdateListHeaders() {
        var resource = new AdminListResource();
        try (var lists = mockStatic(PosList.class);
             var base = mockStatic(PanacheEntityBase.class);
             var created = org.mockito.Mockito.mockConstruction(PosList.class)) {
            assertTrue(notice(resource.save(listForm())).contains("créée"));
            PosList row = created.constructed().getFirst();
            assertEquals("SERVICE", row.code);
            assertEquals(PosList.Kind.SERVICE_REQUEST, row.kind);
            org.mockito.Mockito.verify(row).persist();
            row.id = 4L;
            lists.when(() -> PosList.findByCode("SERVICE")).thenReturn(row);
            base.when(() -> PanacheEntityBase.findById(4L)).thenReturn(row);
            var update = listForm();
            update.putSingle("id", "4");
            assertTrue(notice(resource.save(update)).contains("enregistrée"));
            update.putSingle("id", "5");
            assertTrue(notice(resource.save(update)).contains("pris"));
        }
    }

    /** BO-04-03-12: delete rejects absent ids, deletes known lists and unlinks items with or without a parent. */
    @Test
    void deleteListAndItemBranches() {
        var resource = new AdminListResource();
        var form = new MultivaluedHashMap<String, String>();
        assertTrue(notice(resource.delete(form)).contains("inconnue"));
        assertTrue(notice(resource.deleteItem(form)).contains("inconnue"));
        var parent = mock(PosList.class);
        parent.items = new java.util.ArrayList<>();
        var item = mock(PosListItem.class);
        item.posList = parent;
        parent.items.add(item);
        form.putSingle("id", "4");
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.findById(4L)).thenReturn(parent);
            assertTrue(notice(resource.delete(form)).contains("supprimée"));
            org.mockito.Mockito.verify(parent).delete();
            base.when(() -> PanacheEntityBase.findById(4L)).thenReturn(item);
            assertTrue(notice(resource.deleteItem(form)).contains("supprimée"));
            assertEquals(0, parent.items.size());
            item.posList = null;
            assertTrue(notice(resource.deleteItem(form)).contains("supprimée"));
            org.mockito.Mockito.verify(item, org.mockito.Mockito.times(2)).delete();
        }
    }

    /** BO-04-02-07: item creation, duplicate detection, missing ids and updates cover independent identity guards. */
    @Test
    void itemCreationDuplicateAndUpdate() {
        var resource = new AdminListResource();
        var parent = mock(PosList.class);
        parent.id = 4L;
        parent.items = new java.util.ArrayList<>();
        try (var base = mockStatic(PanacheEntityBase.class);
             var created = org.mockito.Mockito.mockConstruction(PosListItem.class)) {
            base.when(() -> PanacheEntityBase.findById(4L)).thenReturn(parent);
            var query = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            base.when(() -> PanacheEntityBase.find("posList.id = ?1 and code = ?2", 4L, "ANNUL")).thenReturn(query);
            var form = itemForm();
            assertTrue(notice(resource.saveItem(form)).contains("ajoutée"));
            PosListItem item = created.constructed().getFirst();
            assertEquals(parent, item.posList);
            assertEquals(2, item.severity);
            org.mockito.Mockito.verify(item).persist();
            org.mockito.Mockito.when(query.firstResult()).thenReturn(item);
            assertTrue(notice(resource.saveItem(form)).contains("déjà pris"));
            form.putSingle("id", "8");
            assertTrue(notice(resource.saveItem(form)).contains("inconnue"));
            base.when(() -> PanacheEntityBase.findById(8L)).thenReturn(item);
            assertTrue(notice(resource.saveItem(form)).contains("enregistrée"));
            form.remove("listId");
            assertTrue(notice(resource.saveItem(form)).contains("Liste inconnue"));
        }
    }

    /** BO-04-03-12: page projections cover every kind and both null/non-null numeric/options fields. */
    @Test
    void listsProjectAllKindsAndNullableFields() {
        var resource = new AdminListResource();
        resource.adminLists = mock(io.quarkus.qute.Template.class);
        var view = mock(io.quarkus.qute.TemplateInstance.class);
        org.mockito.Mockito.when(resource.adminLists.data(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(view);
        org.mockito.Mockito.when(view.data(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(view);
        java.util.List<PosList> rows = new java.util.ArrayList<>();
        java.util.List<PosList.Kind> kinds = new java.util.ArrayList<>(java.util.List.of(PosList.Kind.values()));
        kinds.add(null);
        for (PosList.Kind kind : kinds) {
            PosList list = new PosList();
            list.kind = kind;
            list.functions = kind == null ? null : "EXPENSE";
            PosListItem first = new PosListItem();
            PosListItem second = new PosListItem();
            second.numericValue = new BigDecimal("0.035");
            second.options = "LABEL";
            list.items.add(first);
            list.items.add(second);
            rows.add(list);
        }
        try (var base = mockStatic(PanacheEntityBase.class)) {
            var query = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            base.when(() -> PanacheEntityBase.find("order by kind, code")).thenReturn(query);
            org.mockito.Mockito.when(query.list()).thenReturn(rows);
            assertEquals(view, resource.list(null, true));
        }
        var projected = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(resource.adminLists).data(org.mockito.ArgumentMatchers.eq("lists"), projected.capture());
        var lists = (java.util.List<AdminListResource.ListRow>) projected.getValue();
        assertEquals(kinds.size(), lists.size());
        assertEquals("", lists.getLast().kind);
        assertEquals("", lists.getFirst().items.getFirst().numericValue);
        assertEquals("0.035", lists.getFirst().items.getLast().numericValue);
    }

    /** BO-04-02-07: null and blank source parameters create no lists and perform no persistence. */
    @Test
    void migrationSkipsMissingAndBlankSettings() {
        var resource = new AdminListResource();
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        try (var lists = mockStatic(PosList.class);
             var created = org.mockito.Mockito.mockConstruction(PosList.class)) {
            assertTrue(notice(resource.bootstrap()).contains("rien"));
            org.mockito.Mockito.when(resource.posSettingsService.value(org.mockito.ArgumentMatchers.anyString())).thenReturn(" ");
            assertTrue(notice(resource.bootstrap()).contains("rien"));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /** BO-04-02-07: migration retains only valid labels, return markers and tare weights with deterministic ranks. */
    @Test
    void migrationCreatesValidatedListsAndEntries() {
        var resource = new AdminListResource();
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        org.mockito.Mockito.when(resource.posSettingsService.value("supervisor.service-reasons")).thenReturn("Call; ;Assist");
        org.mockito.Mockito.when(resource.posSettingsService.value("cash.movement-reasons")).thenReturn("Expense");
        org.mockito.Mockito.when(resource.posSettingsService.value("return.types")).thenReturn("Normal; ; >X;Gift>LABEL");
        org.mockito.Mockito.when(resource.posSettingsService.value("balance.tare-containers"))
                .thenReturn(" ;invalid; :Box:0.1;A: :0.1;B:Box:bad;C:Box:-1;D:Box:0,035");
        try (var lists = mockStatic(PosList.class);
             var created = org.mockito.Mockito.mockConstruction(PosList.class, (value, context) -> value.items = new java.util.ArrayList<>());
             var items = org.mockito.Mockito.mockConstruction(PosListItem.class)) {
            assertTrue(notice(resource.bootstrap()).contains("4 liste(s)"));
            assertEquals(4, created.constructed().size());
            assertEquals(6, items.constructed().size());
            PosList service = created.constructed().getFirst();
            assertEquals("SERVICE", service.code);
            assertEquals(2, service.items.size());
            assertEquals(10, service.items.getLast().displayOrder);
            assertEquals(1, service.items.getFirst().severity);
            PosList returns = created.constructed().get(2);
            assertNull(returns.items.getFirst().options);
            assertEquals("LABEL", returns.items.getLast().options);
            assertEquals(new BigDecimal("0.035"), created.constructed().getLast().items.getFirst().numericValue);
            for (PosList list : created.constructed()) org.mockito.Mockito.verify(list).persist();
        }
    }


    /**
     * BO-03-02-45/-47, BO-03-07-03/-04, BO-03-13-11/-12: each kind has its
     * closed vocabulary of option markers; an unknown marker is named, the
     * kinds that read none accept anything, and an absent string is fine.
     */
    @Test
    void theOptionMarkersAreAClosedVocabularyPerKind() {
        assertNull(AdminListResource.optionProblem(PosList.Kind.RETURN_TYPE, null));
        assertNull(AdminListResource.optionProblem(PosList.Kind.RETURN_TYPE,
                "ticket, NOENDORSE,, NOORIGIN,PRICE,DECONSIGNE,RANGE:R29"));
        assertEquals("FOO", AdminListResource.optionProblem(PosList.Kind.RETURN_TYPE, "TICKET,FOO"));
        assertEquals("RANGE:", AdminListResource.optionProblem(PosList.Kind.RETURN_TYPE, "RANGE:"));
        assertEquals("MAX:3", AdminListResource.optionProblem(PosList.Kind.RETURN_TYPE, "MAX:3"));
        assertNull(AdminListResource.optionProblem(PosList.Kind.JUSTIFICATION, "TENDER:CHEQUE,CONTROL:warning"));
        assertEquals("CHEQUE", AdminListResource.optionProblem(PosList.Kind.JUSTIFICATION, "CHEQUE"));
        assertEquals(":X", AdminListResource.optionProblem(PosList.Kind.JUSTIFICATION, ":X"));
        assertEquals("TENDER:", AdminListResource.optionProblem(PosList.Kind.JUSTIFICATION, "TENDER:"));
        assertEquals("CONTROL:BLOK", AdminListResource.optionProblem(PosList.Kind.JUSTIFICATION, "CONTROL:BLOK"));
        assertEquals("MAX:3", AdminListResource.optionProblem(PosList.Kind.JUSTIFICATION, "MAX:3"));
        assertNull(AdminListResource.optionProblem(PosList.Kind.DISCOUNT, "PERCENT,AMOUNT,TICKET,CUMUL,MAX:30"));
        assertEquals("MAX:x", AdminListResource.optionProblem(PosList.Kind.DISCOUNT, "MAX:x"));
        assertEquals("RANGE:1", AdminListResource.optionProblem(PosList.Kind.DISCOUNT, "RANGE:1"));
        assertEquals("FOO", AdminListResource.optionProblem(PosList.Kind.DISCOUNT, "FOO"));
        assertNull(AdminListResource.optionProblem(PosList.Kind.TARE_CONTAINER, "ANYTHING"));
        assertFalse(AdminListResource.hasMarker(null, "PERCENT"));
        assertTrue(AdminListResource.hasMarker("x, percent", "PERCENT"));
        assertEquals("t.montant-maximum-de-la-depense", AdminListResource.valueLabelKey(PosList.Kind.JUSTIFICATION));
        assertEquals("t.valeur-de-la-touche", AdminListResource.valueLabelKey(PosList.Kind.DISCOUNT));
        assertEquals("t.poids-kg", AdminListResource.valueLabelKey(PosList.Kind.TARE_CONTAINER));
        assertEquals("t.options-depense", AdminListResource.optionsHintKey(PosList.Kind.JUSTIFICATION));
        assertEquals("t.options-remise", AdminListResource.optionsHintKey(PosList.Kind.DISCOUNT));
        assertEquals("t.options-type-retour", AdminListResource.optionsHintKey(PosList.Kind.RETURN_TYPE));
    }

    /**
     * BO-03-02-45, BO-03-07-03/-06: an entry posting an unknown marker, or a
     * discount key naming both natures or none, is refused before anything is
     * written.
     */
    @Test
    void anEntryWithAMarkerItsKindDoesNotKnowIsRefused() {
        var resource = new AdminListResource();
        var parent = mock(PosList.class);
        parent.id = 4L;
        parent.kind = PosList.Kind.DISCOUNT;
        parent.items = new java.util.ArrayList<>();
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.findById(4L)).thenReturn(parent);
            var form = itemForm();
            form.putSingle("options", "PERCENT,FOO");
            assertTrue(notice(resource.saveItem(form)).contains("Option inconnue pour ce genre de liste : FOO."));
            form.putSingle("options", "PERCENT,AMOUNT");
            assertTrue(notice(resource.saveItem(form)).contains("PERCENT ou AMOUNT"));
            form.putSingle("options", "CUMUL");
            assertTrue(notice(resource.saveItem(form)).contains("PERCENT ou AMOUNT"));
        }
        assertTrue(parent.items.isEmpty());
        try (var base = mockStatic(PanacheEntityBase.class);
             var created = org.mockito.Mockito.mockConstruction(PosListItem.class)) {
            base.when(() -> PanacheEntityBase.findById(4L)).thenReturn(parent);
            var query = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            base.when(() -> PanacheEntityBase.find("posList.id = ?1 and code = ?2", 4L, "ANNUL")).thenReturn(query);
            var form = itemForm();
            form.putSingle("options", "PERCENT,MAX:30");
            assertTrue(notice(resource.saveItem(form)).contains("ajoutée"));
            assertEquals("PERCENT,MAX:30", created.constructed().getFirst().options);
        }
    }

    /**
     * BO-03-13-02: a return type the refunds already name is deactivated and
     * not deleted, and its list is not deleted either; an unused one goes.
     */
    @Test
    void aReturnTypeInUseIsDeactivatedRatherThanDeleted() {
        var resource = new AdminListResource();
        var parent = mock(PosList.class);
        parent.kind = PosList.Kind.RETURN_TYPE;
        parent.items = new java.util.ArrayList<>();
        var item = mock(PosListItem.class);
        item.code = "DEFECT";
        item.active = true;
        item.posList = parent;
        parent.items.add(item);
        var form = new MultivaluedHashMap<String, String>();
        form.putSingle("id", "4");
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.count("returnTypeCode", "DEFECT")).thenReturn(3L);
            base.when(() -> PanacheEntityBase.findById(4L)).thenReturn(parent);
            assertTrue(notice(resource.delete(form)).contains("déjà utilisés"));
            org.mockito.Mockito.verify(parent, org.mockito.Mockito.never()).delete();
            base.when(() -> PanacheEntityBase.findById(4L)).thenReturn(item);
            assertTrue(notice(resource.deleteItem(form)).contains("rattaché à 3 retour(s)"));
            assertFalse(item.active);
            org.mockito.Mockito.verify(item).persist();
            org.mockito.Mockito.verify(item, org.mockito.Mockito.never()).delete();
            base.when(() -> PanacheEntityBase.count("returnTypeCode", "DEFECT")).thenReturn(0L);
            assertTrue(notice(resource.deleteItem(form)).contains("supprimée"));
            parent.items.add(item);
            base.when(() -> PanacheEntityBase.findById(4L)).thenReturn(parent);
            assertTrue(notice(resource.delete(form)).contains("supprimée"));
        }
    }

    /**
     * BO-03-02-46, BO-03-13-03: an entry is switched in and out of service in
     * one gesture; an unknown one is named.
     */
    @Test
    void anEntryIsToggledInOneGesture() {
        var resource = new AdminListResource();
        var form = new MultivaluedHashMap<String, String>();
        assertTrue(notice(resource.toggleItem(form)).contains("inconnue"));
        var item = mock(PosListItem.class);
        item.active = true;
        form.putSingle("id", "9");
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.findById(9L)).thenReturn(item);
            assertTrue(notice(resource.toggleItem(form)).contains("désactivée"));
            assertFalse(item.active);
            assertTrue(notice(resource.toggleItem(form)).contains("activée"));
            assertTrue(item.active);
        }
    }
}
