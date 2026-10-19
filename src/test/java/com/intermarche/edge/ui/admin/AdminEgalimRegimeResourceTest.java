package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.EgalimRegime;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Unit tests for {@link AdminEgalimRegimeResource}.
 * <p>
 * The resource is a Qute-backed back-office page over the Panache statics of
 * {@link EgalimRegime} — {@code listAllOrdered} and {@code findByCode} are
 * DECLARED on the entity and intercepted with {@code mockStatic(EgalimRegime
 * .class)}, while the inherited {@code findById} resolves to
 * {@link PanacheEntityBase} and is intercepted separately. Inserted rows are
 * neutralised with {@link org.mockito.Mockito#mockConstruction}. No database,
 * no Quarkus boot.
 * <p>
 * Branch enumeration, every arm exercised: {@code saveRegime} the unreadable
 * code arm, each leg of the {@code cap == null && rawCap != null} guard
 * (nothing typed, garbage typed, valid value), both arms of the
 * {@code id == null} ternary, each leg of the {@code id != null && edited ==
 * null} guard, each leg of the
 * {@code holder != null && (edited == null || !holder.id.equals(edited.id))}
 * collision guard including the fall-through where a row keeps its own code,
 * and both arms of the create/update branch; {@code deleteRegime} both arms of
 * the ternary and the missing-row arm; {@code code} the null arm, the blank
 * leg, the too-long leg and the upper-casing fall-through; {@code fraction}
 * the null arm, the negative leg, the above-hundred leg and the in-range
 * fall-through. 20 branches, 20 covered.
 */
class AdminEgalimRegimeResourceTest {

    /**
     * Builds a resource over a mocked template.
     *
     * @return the wired resource
     */
    private AdminEgalimRegimeResource newResource() {
        AdminEgalimRegimeResource resource = new AdminEgalimRegimeResource();
        resource.adminEgalimRegimes = mock(Template.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminEgalimRegimeResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminEgalimRegimes.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a regime carrying an identifier and its three values.
     *
     * @param id the row identifier
     * @param code the code
     * @param label the label, possibly null
     * @param capRate the ceiling as a fraction, or null
     * @return the regime
     */
    private EgalimRegime regime(Long id, String code, String label, String capRate) {
        EgalimRegime regime = new EgalimRegime(code, label,
                capRate == null ? null : new BigDecimal(capRate));
        regime.id = id;
        return regime;
    }

    /**
     * Builds a posted form from alternating names and values.
     *
     * @param pairs the field names and values, in pairs
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
     * Reads the notice carried by a redirect, still percent-encoded.
     *
     * @param response the redirect
     * @return the query string of the Location header
     */
    private String query(Response response) {
        return response.getLocation().getQuery();
    }

    /**
     * Invokes a private method of the resource by reflection.
     *
     * @param name the method name
     * @param types the parameter types
     * @return the accessible method
     * @throws Exception when the method does not exist
     */
    private Method privateMethod(String name, Class<?>... types) throws Exception {
        Method method = AdminEgalimRegimeResource.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    /**
     * The page hands the template every regime, in the order the entity lists
     * them.
     */
    @Test
    void pageListsEveryRegime() {
        AdminEgalimRegimeResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        EgalimRegime dph = regime(1L, "DPH", "Droguerie", "0.4000");
        EgalimRegime food = regime(2L, "FOOD", "Alimentaire", "0.3400");
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class)) {
            regimes.when(EgalimRegime::listAllOrdered).thenReturn(List.of(dph, food));
            assertSame(instance, resource.egalimRegimesPage(null, true));
            org.mockito.ArgumentCaptor<Object> captor =
                    org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(resource.adminEgalimRegimes).data(org.mockito.ArgumentMatchers.eq("rows"),
                    captor.capture());
            @SuppressWarnings("unchecked")
            List<EgalimRegime> rows = (List<EgalimRegime>) captor.getValue();
            assertEquals(2, rows.size());
            assertSame(dph, rows.get(0));
            assertSame(food, rows.get(1));
        }
    }

    /**
     * An empty referential produces no row, and the page still renders with
     * its notice.
     */
    @Test
    void pageRendersAnEmptyReferential() {
        AdminEgalimRegimeResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class)) {
            regimes.when(EgalimRegime::listAllOrdered).thenReturn(List.of());
            assertSame(instance, resource.egalimRegimesPage("Vide.", false));
        }
    }

    /**
     * A blank code is refused before anything is read — the blank leg of the
     * code guard.
     */
    @Test
    void saveRefusesABlankCode() {
        AdminEgalimRegimeResource resource = newResource();
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class)) {
            Response response = resource.saveRegime(form("code", "   ", "capPercent", "34"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            regimes.verifyNoInteractions();
        }
    }

    /**
     * A code longer than the column is refused — the length leg of the same
     * guard, which a blank check alone would let through.
     */
    @Test
    void saveRefusesATooLongCode() {
        AdminEgalimRegimeResource resource = newResource();
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class)) {
            Response response = resource.saveRegime(
                    form("code", "A".repeat(31), "capPercent", "34"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            regimes.verifyNoInteractions();
        }
    }

    /**
     * A ceiling that is not a percentage is refused — the leg where something
     * WAS typed but could not be read.
     */
    @Test
    void saveRefusesAnUnreadableCeiling() {
        AdminEgalimRegimeResource resource = newResource();
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class)) {
            Response response = resource.saveRegime(form("code", "FOOD", "capPercent", "beaucoup"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            regimes.verifyNoInteractions();
        }
    }

    /**
     * A ceiling above a hundred percent is refused: a line cannot be given
     * more than it costs.
     */
    @Test
    void saveRefusesACeilingAboveAHundred() {
        AdminEgalimRegimeResource resource = newResource();
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class)) {
            Response response = resource.saveRegime(form("code", "FOOD", "capPercent", "101"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
        }
    }

    /**
     * A NO ceiling at all creates an uncapped regime — the leg where nothing
     * was typed, which must not be confused with a garbage value.
     */
    @Test
    void saveCreatesAnUncappedRegimeWhenNoCeilingIsTyped() {
        AdminEgalimRegimeResource resource = newResource();
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class);
                MockedConstruction<EgalimRegime> created = mockConstruction(EgalimRegime.class)) {
            regimes.when(() -> EgalimRegime.findByCode("EXEMPT")).thenReturn(null);
            Response response = resource.saveRegime(form("code", "exempt", "label", "Exempté"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=true"));
            assertEquals(1, created.constructed().size());
            verify(created.constructed().get(0)).persist();
        }
    }

    /**
     * A creation upper-cases its code and converts the typed percentage into
     * the fraction the model stores.
     */
    @Test
    void saveCreatesARegimeUpperCasedAndInFraction() {
        AdminEgalimRegimeResource resource = newResource();
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class);
                MockedConstruction<EgalimRegime> created = mockConstruction(EgalimRegime.class,
                        (mockRegime, context) -> {
                            assertEquals("FOOD", context.arguments().get(0));
                            assertEquals("Alimentaire", context.arguments().get(1));
                            assertEquals(new BigDecimal("0.3400"), context.arguments().get(2));
                        })) {
            regimes.when(() -> EgalimRegime.findByCode("FOOD")).thenReturn(null);
            Response response = resource.saveRegime(
                    form("code", "food", "label", "Alimentaire", "capPercent", "34"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=true"));
            assertEquals(1, created.constructed().size());
        }
    }

    /**
     * An identifier naming no row is refused — the second leg of the
     * {@code id != null && edited == null} guard.
     */
    @Test
    void saveRefusesAnUnknownIdentifier() {
        AdminEgalimRegimeResource resource = newResource();
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EgalimRegime.findById(9L)).thenReturn(null);
            Response response = resource.saveRegime(
                    form("id", "9", "code", "FOOD", "capPercent", "34"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
        }
    }

    /**
     * A code already carried by ANOTHER row is refused — the collision guard
     * on an update.
     */
    @Test
    void saveRefusesACodeAnotherRegimeCarries() {
        AdminEgalimRegimeResource resource = newResource();
        EgalimRegime edited = regime(1L, "DPH", "Droguerie", "0.4000");
        EgalimRegime holder = regime(2L, "FOOD", "Alimentaire", "0.3400");
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EgalimRegime.findById(1L)).thenReturn(edited);
            regimes.when(() -> EgalimRegime.findByCode("FOOD")).thenReturn(holder);
            Response response = resource.saveRegime(
                    form("id", "1", "code", "FOOD", "capPercent", "40"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            assertEquals("DPH", edited.code);
        }
    }

    /**
     * A code already carried on a CREATION is refused — the same guard through
     * its {@code edited == null} leg.
     */
    @Test
    void saveRefusesACodeAlreadyTakenOnCreation() {
        AdminEgalimRegimeResource resource = newResource();
        EgalimRegime holder = regime(2L, "FOOD", "Alimentaire", "0.3400");
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class)) {
            regimes.when(() -> EgalimRegime.findByCode("FOOD")).thenReturn(holder);
            Response response = resource.saveRegime(form("code", "FOOD", "capPercent", "34"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
        }
    }

    /**
     * A row keeping its OWN code is saved — the fall-through of the collision
     * guard, which a naive uniqueness check would refuse.
     */
    @Test
    void saveKeepsARegimeOnItsOwnCode() {
        AdminEgalimRegimeResource resource = newResource();
        EgalimRegime edited = regime(1L, "FOOD", "Alimentaire", "0.3400");
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EgalimRegime.findById(1L)).thenReturn(edited);
            regimes.when(() -> EgalimRegime.findByCode("FOOD")).thenReturn(edited);
            Response response = resource.saveRegime(
                    form("id", "1", "code", "FOOD", "label", "Alimentaire et petfood",
                            "capPercent", "36"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=true"));
            assertEquals("Alimentaire et petfood", edited.label);
            assertEquals(new BigDecimal("0.3600"), edited.capRate);
        }
    }

    /**
     * An update can REMOVE a ceiling: the row becomes uncapped rather than
     * capped at zero.
     */
    @Test
    void saveCanRemoveACeiling() {
        AdminEgalimRegimeResource resource = newResource();
        EgalimRegime edited = regime(1L, "FOOD", "Alimentaire", "0.3400");
        try (MockedStatic<EgalimRegime> regimes = mockStatic(EgalimRegime.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EgalimRegime.findById(1L)).thenReturn(edited);
            regimes.when(() -> EgalimRegime.findByCode("FOOD")).thenReturn(edited);
            Response response = resource.saveRegime(form("id", "1", "code", "FOOD"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=true"));
            assertNull(edited.capRate);
        }
    }

    /**
     * A removal names the row by its identifier and says what it costs.
     */
    @Test
    void deleteRemovesTheRegime() {
        AdminEgalimRegimeResource resource = newResource();
        EgalimRegime regime = mock(EgalimRegime.class);
        regime.code = "FOOD";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EgalimRegime.findById(1L)).thenReturn(regime);
            Response response = resource.deleteRegime(form("id", "1"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=true"));
            verify(regime).delete();
        }
    }

    /**
     * A removal naming no row is refused — the missing-row arm.
     */
    @Test
    void deleteRefusesAnUnknownRegime() {
        AdminEgalimRegimeResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EgalimRegime.findById(9L)).thenReturn(null);
            Response response = resource.deleteRegime(form("id", "9"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
        }
    }

    /**
     * A removal with NO identifier is refused without a lookup — the null arm
     * of the ternary.
     */
    @Test
    void deleteRefusesAMissingIdentifier() {
        AdminEgalimRegimeResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Response response = resource.deleteRegime(form());
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            panache.verifyNoInteractions();
        }
    }

    /**
     * The code reader answers null on a null value — the arm no form reaches
     * but every refactoring can.
     *
     * @throws Exception when the reflective call fails
     */
    @Test
    void codeReaderAnswersNullOnNull() throws Exception {
        AdminEgalimRegimeResource resource = newResource();
        assertNull(privateMethod("code", String.class).invoke(resource, (Object) null));
    }

    /**
     * The fraction reader answers null on a negative percentage — the
     * negative leg of its range guard.
     *
     * @throws Exception when the reflective call fails
     */
    @Test
    void fractionReaderRefusesANegativePercentage() throws Exception {
        AdminEgalimRegimeResource resource = newResource();
        assertNull(privateMethod("fraction", String.class).invoke(resource, "-1"));
    }

    /**
     * The fraction reader answers null on an absent value — the arm that means
     * "no ceiling" and is told apart by the caller.
     *
     * @throws Exception when the reflective call fails
     */
    @Test
    void fractionReaderAnswersNullOnNothingTyped() throws Exception {
        AdminEgalimRegimeResource resource = newResource();
        assertNull(privateMethod("fraction", String.class).invoke(resource, (Object) null));
    }

    /**
     * The fraction reader converts a comma-separated percentage, which is how
     * a French keyboard writes one.
     *
     * @throws Exception when the reflective call fails
     */
    @Test
    void fractionReaderConvertsACommaPercentage() throws Exception {
        AdminEgalimRegimeResource resource = newResource();
        assertEquals(new BigDecimal("0.3350"),
                privateMethod("fraction", String.class).invoke(resource, "33,5"));
    }
}
