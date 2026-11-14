package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.MeasureUnit;
import com.intermarche.edge.domain.catalog.Product;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Unit tests for {@link AdminMeasureUnitResource} (BO-02-03-17), every branch
 * exercised: the page with and without an edited unit; the save refusals (each
 * missing field, unknown id, code clash, full referential), the creation, the
 * update re-labelling the carriers; the standard seeding; the deletion
 * refusals and success; the code normalization.
 */
class AdminMeasureUnitResourceTest {

    /** The JPQL of the articles carrying a unit code. */
    private static final String CARRIERS = "select p from Product p join p.attributes a where key(a) = ?1 and a = ?2";

    /**
     * Builds the resource over a mocked template.
     *
     * @return the resource
     */
    private AdminMeasureUnitResource newResource() {
        AdminMeasureUnitResource resource = new AdminMeasureUnitResource();
        resource.adminMeasureUnits = mock(Template.class);
        return resource;
    }

    /**
     * Builds a posted form from name/value pairs.
     *
     * @param pairs the pairs
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.putSingle(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    /**
     * Builds a single-result query.
     *
     * @param result the first result, possibly null
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<MeasureUnit> single(MeasureUnit result) {
        return mock(PanacheQuery.class, invocation ->
                "firstResult".equals(invocation.getMethod().getName()) ? result : null);
    }

    /**
     * Builds a unit.
     *
     * @param id the id
     * @param code the code
     * @return the unit
     */
    private MeasureUnit unit(Long id, String code) {
        MeasureUnit unit = new MeasureUnit();
        unit.id = id;
        unit.code = code;
        unit.label = "L" + code;
        unit.symbol = "s" + code;
        return unit;
    }

    /**
     * Tells whether a redirect reports a success.
     *
     * @param response the redirect
     * @return true for a green notice
     */
    private boolean ok(Response response) {
        return response.getLocation().toString().contains("noticeOk=true");
    }

    /**
     * The page lists the units and the edited one, or none.
     */
    @Test
    void pageRendersUnitsAndTheEditedOne() {
        AdminMeasureUnitResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminMeasureUnits.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        MeasureUnit kg = unit(2L, "2");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> MeasureUnit.list("order by code")).thenReturn(List.of(kg));
            panache.when(() -> MeasureUnit.findById(2L)).thenReturn(kg);
            assertEquals(instance, resource.page(2L, "n", true));
            verify(instance).data("edited", kg);
            assertEquals(instance, resource.page(null, null, true));
            verify(instance).data("edited", null);
        }
    }

    /**
     * A missing code, label or symbol is refused, one test per leg; a malformed
     * code counts as missing.
     */
    @Test
    void saveRefusesAMissingField() {
        AdminMeasureUnitResource resource = newResource();
        assertFalse(ok(resource.save(form("label", "Kilo", "symbol", "kg"))));
        assertFalse(ok(resource.save(form("code", "2", "symbol", "kg"))));
        assertFalse(ok(resource.save(form("code", "2", "label", "Kilo"))));
        assertFalse(ok(resource.save(form("code", "TOO", "label", "Kilo", "symbol", "kg"))));
    }

    /**
     * An id naming no unit, a code another unit carries and a full referential
     * are refused.
     */
    @Test
    void saveRefusesUnknownIdClashAndFullReferential() {
        AdminMeasureUnitResource resource = newResource();
        MeasureUnit other = unit(5L, "2");
        PanacheQuery<MeasureUnit> clash = single(other);
        PanacheQuery<MeasureUnit> free = single(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> MeasureUnit.findById(9L)).thenReturn(null);
            assertFalse(ok(resource.save(form("id", "9", "code", "2", "label", "Kilo", "symbol", "kg"))));
            panache.when(() -> MeasureUnit.find("code", "2")).thenReturn(clash);
            assertFalse(ok(resource.save(form("code", "2", "label", "Kilo", "symbol", "kg"))));
            panache.when(() -> MeasureUnit.find("code", "3")).thenReturn(free);
            panache.when(MeasureUnit::count).thenReturn(99L);
            assertFalse(ok(resource.save(form("code", "3", "label", "Litre", "symbol", "l"))));
        }
    }

    /**
     * A new unit is created active, upper-cased, and re-labels nothing when no
     * article carries its code.
     */
    @Test
    void saveCreatesAUnit() {
        AdminMeasureUnitResource resource = newResource();
        PanacheQuery<MeasureUnit> free = single(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<MeasureUnit> created = mockConstruction(MeasureUnit.class)) {
            panache.when(() -> MeasureUnit.find("code", "B")).thenReturn(free);
            panache.when(MeasureUnit::count).thenReturn(3L);
            panache.when(() -> Product.list(CARRIERS, "UNIT_CODE", "B")).thenReturn(List.of());
            Response response = resource.save(form("code", "b", "label", "Mètre bobine", "symbol", "mb",
                    "decimalAllowed", "on"));
            assertTrue(ok(response));
            MeasureUnit unit = created.constructed().get(0);
            assertEquals("B", unit.code);
            assertTrue(unit.decimalAllowed);
            assertTrue(unit.active);
            verify(unit).persist();
        }
    }

    /**
     * Updating a unit re-labels the articles carrying its former code with its
     * symbol and its new code, and reads the posted activity.
     */
    @Test
    void saveUpdatesAndRelabelsTheCarriers() {
        AdminMeasureUnitResource resource = newResource();
        MeasureUnit edited = unit(4L, "4");
        PanacheQuery<MeasureUnit> self = single(edited);
        Product hose = new Product();
        hose.attributes.put("UNIT_CODE", "4");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> MeasureUnit.findById(4L)).thenReturn(edited);
            panache.when(() -> MeasureUnit.find("code", "M")).thenReturn(self);
            panache.when(() -> Product.list(CARRIERS, "UNIT_CODE", "4")).thenReturn(List.of(hose));
            assertTrue(ok(resource.save(form("id", "4", "code", "M", "label", "Mètre", "symbol", "m"))));
        }
        assertEquals("M", edited.code);
        assertFalse(edited.active);
        assertEquals("m", hose.unitName);
        assertEquals("M", hose.attributes.get("UNIT_CODE"));
    }

    /**
     * The standard seeding creates only the units the referential lacks.
     */
    @Test
    void seedStandardCreatesTheMissingUnits() {
        AdminMeasureUnitResource resource = newResource();
        PanacheQuery<MeasureUnit> present = single(unit(1L, "1"));
        PanacheQuery<MeasureUnit> absent = single(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> MeasureUnit.find(anyString(), any(Object[].class))).thenReturn(absent);
            panache.when(() -> MeasureUnit.find("code", "1")).thenReturn(present);
            try (MockedConstruction<MeasureUnit> created = mockConstruction(MeasureUnit.class)) {
                assertTrue(ok(resource.seedStandard()));
                assertEquals(9, created.constructed().size());
                assertEquals("kg", created.constructed().get(0).symbol);
                assertTrue(created.constructed().get(0).decimalAllowed);
            }
        }
    }

    /**
     * A deletion naming no unit is refused, a unit some article carries is
     * refused, a free unit is deleted.
     */
    @Test
    void deleteRefusesUnknownAndCarriedUnits() {
        AdminMeasureUnitResource resource = newResource();
        MeasureUnit carried = mock(MeasureUnit.class);
        carried.code = "2";
        MeasureUnit free = mock(MeasureUnit.class);
        free.code = "7";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertFalse(ok(resource.delete(form())));
            panache.when(() -> MeasureUnit.findById(2L)).thenReturn(carried);
            panache.when(() -> MeasureUnit.findById(7L)).thenReturn(free);
            panache.when(() -> Product.list(CARRIERS, "UNIT_CODE", "2")).thenReturn(List.of(new Product()));
            panache.when(() -> Product.list(CARRIERS, "UNIT_CODE", "7")).thenReturn(List.of());
            assertFalse(ok(resource.delete(form("id", "2"))));
            verify(carried, never()).delete();
            assertTrue(ok(resource.delete(form("id", "7"))));
            verify(free).delete();
        }
    }

    /**
     * The code normalization upper-cases one or two letters or digits and
     * refuses anything else.
     */
    @Test
    void codeNormalization() {
        assertNull(AdminMeasureUnitResource.code(null));
        assertEquals("B", AdminMeasureUnitResource.code("b"));
        assertEquals("12", AdminMeasureUnitResource.code("12"));
        assertNull(AdminMeasureUnitResource.code("1-"));
    }

    /**
     * An update posting the activity keeps the unit active.
     */
    @Test
    void saveKeepsAnUpdatedUnitActive() {
        AdminMeasureUnitResource resource = newResource();
        MeasureUnit edited = unit(4L, "4");
        edited.active = false;
        PanacheQuery<MeasureUnit> self = single(edited);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> MeasureUnit.findById(4L)).thenReturn(edited);
            panache.when(() -> MeasureUnit.find("code", "4")).thenReturn(self);
            panache.when(() -> Product.list(CARRIERS, "UNIT_CODE", "4")).thenReturn(List.of());
            assertTrue(ok(resource.save(form("id", "4", "code", "4", "label", "Mètre", "symbol", "m", "active", "on"))));
        }
        assertTrue(edited.active);
    }
}
