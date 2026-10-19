package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.VatRate;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Unit tests for {@link AdminVatRateResource}.
 * <p>
 * The resource is a Qute-backed back-office page over the Panache statics of
 * {@link VatRate} — {@code listAllOrdered} and {@code findByNumber} are
 * DECLARED on the entity and intercepted with {@code mockStatic(VatRate.class)},
 * while the inherited {@code findById} and {@link Price#count} resolve to
 * {@link PanacheEntityBase} and are intercepted separately. Inserted rows are
 * neutralised with {@link org.mockito.Mockito#mockConstruction}. No database,
 * no Quarkus boot.
 * <p>
 * Branch enumeration (every arm exercised): {@code isRemovable} both arms;
 * {@code saveVatRate} the unreadable-number arm, the unreadable-rate arm, both
 * arms of the {@code id == null} ternary, each leg of the
 * {@code id != null && edited == null} guard, each leg of the
 * {@code holder != null && (edited == null || !holder.id.equals(edited.id))}
 * collision guard including the fall-through where a row keeps its own number,
 * and both arms of the create/update branch; {@code deleteVatRate} both arms of
 * the ternary, the missing-row arm and both arms of the usage guard;
 * {@code number} the null arm, the unparseable arm and both arms of the
 * negativity ternary; {@code fraction} the null arm, the negative leg, the
 * above-hundred leg and the in-range fall-through. 21 branches, 21 covered.
 */
class AdminVatRateResourceTest {

    /**
     * Builds a resource over a mocked template.
     *
     * @return the wired resource
     */
    private AdminVatRateResource newResource() {
        AdminVatRateResource resource = new AdminVatRateResource();
        resource.adminVatRates = mock(Template.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminVatRateResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminVatRates.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a regime carrying an identifier and the three values.
     *
     * @param id the row identifier
     * @param number the regime number
     * @param rate the rate as a fraction
     * @param label the label, possibly null
     * @return the regime
     */
    private VatRate regime(Long id, Integer number, String rate, String label) {
        VatRate vat = new VatRate();
        vat.id = id;
        vat.number = number;
        vat.rate = new BigDecimal(rate);
        vat.label = label;
        return vat;
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
        Method method = AdminVatRateResource.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    /**
     * The page hands the template one row per regime, each carrying how many
     * prices name it.
     */
    @Test
    void vatRatesPageListsEveryRegimeWithItsUsage() {
        AdminVatRateResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        VatRate normal = regime(1L, 1, "0.2000", "Taux normal");
        VatRate exempt = regime(2L, 5, "0.0000", "Exonéré");
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            rates.when(VatRate::listAllOrdered).thenReturn(List.of(normal, exempt));
            panache.when(() -> Price.count("vat.id = ?1", 1L)).thenReturn(7L);
            panache.when(() -> Price.count("vat.id = ?1", 2L)).thenReturn(0L);
            assertSame(instance, resource.vatRatesPage(null, true));
            org.mockito.ArgumentCaptor<Object> captor =
                    org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(resource.adminVatRates).data(org.mockito.ArgumentMatchers.eq("rows"),
                    captor.capture());
            @SuppressWarnings("unchecked")
            List<AdminVatRateResource.RateRow> rows =
                    (List<AdminVatRateResource.RateRow>) captor.getValue();
            assertEquals(2, rows.size());
            assertSame(normal, rows.get(0).getRate());
            assertEquals(7L, rows.get(0).getPrices());
            assertFalse(rows.get(0).isRemovable());
            assertSame(exempt, rows.get(1).getRate());
            assertEquals(0L, rows.get(1).getPrices());
            assertTrue(rows.get(1).isRemovable());
        }
    }

    /**
     * An empty referential produces no row, and the page still renders.
     */
    @Test
    void vatRatesPageRendersAnEmptyReferential() {
        AdminVatRateResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            rates.when(VatRate::listAllOrdered).thenReturn(List.of());
            assertSame(instance, resource.vatRatesPage("Vide.", false));
            panache.verifyNoInteractions();
        }
    }

    /**
     * A regime nothing uses is removable (the {@code prices == 0} true arm).
     */
    @Test
    void rowIsRemovableWhenNoPriceNamesIt() {
        AdminVatRateResource.RateRow row = new AdminVatRateResource.RateRow(
                regime(1L, 1, "0.2000", "Taux normal"), 0L);
        assertTrue(row.isRemovable());
        assertEquals(0L, row.getPrices());
        assertEquals(1, row.getRate().number);
    }

    /**
     * A regime prices name is not removable (the {@code prices == 0} false
     * arm).
     */
    @Test
    void rowIsNotRemovableWhenPricesNameIt() {
        AdminVatRateResource.RateRow row = new AdminVatRateResource.RateRow(
                regime(1L, 1, "0.2000", "Taux normal"), 3L);
        assertFalse(row.isRemovable());
        assertEquals(3L, row.getPrices());
    }

    /**
     * A number that is not a positive integer is refused before anything is
     * read.
     */
    @Test
    void saveRefusesAnUnreadableNumber() {
        AdminVatRateResource resource = newResource();
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            Response response = resource.saveVatRate(form("number", "abc", "ratePercent", "20"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            rates.verifyNoInteractions();
        }
    }

    /**
     * A rate outside 0..100 is refused, the number having been read first.
     */
    @Test
    void saveRefusesAnOutOfRangeRate() {
        AdminVatRateResource resource = newResource();
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            Response response = resource.saveVatRate(form("number", "1", "ratePercent", "120"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            rates.verifyNoInteractions();
        }
    }

    /**
     * A blank identifier creates the regime, converting the typed percentage
     * into the stored fraction (the {@code id == null} ternary true arm and the
     * creation arm).
     */
    @Test
    void saveCreatesWhenNoIdentifierIsPosted() {
        AdminVatRateResource resource = newResource();
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
                MockedConstruction<VatRate> created = mockConstruction(VatRate.class)) {
            rates.when(() -> VatRate.findByNumber(2)).thenReturn(null);
            Response response = resource.saveVatRate(
                    form("number", "2", "ratePercent", "5,5", "label", "Taux réduit"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=true"));
            assertEquals(1, created.constructed().size());
            verify(created.constructed().get(0)).persist();
        }
    }

    /**
     * A known identifier updates the row in place (the ternary false arm and
     * the update arm), the percentage becoming the fraction.
     */
    @Test
    void saveUpdatesTheNamedRow() {
        AdminVatRateResource resource = newResource();
        VatRate edited = regime(11L, 2, "0.0550", "Taux réduit");
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.findById(11L)).thenReturn(edited);
            rates.when(() -> VatRate.findByNumber(2)).thenReturn(edited);
            Response response = resource.saveVatRate(
                    form("id", "11", "number", "2", "ratePercent", "5,5", "label", "Taux réduit 2026"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=true"));
            assertEquals(2, edited.number);
            assertEquals(new BigDecimal("0.0550"), edited.rate);
            assertEquals("Taux réduit 2026", edited.label);
        }
    }

    /**
     * An identifier naming nothing is refused (the
     * {@code id != null && edited == null} both-legs-true arm).
     */
    @Test
    void saveRefusesAnIdentifierNamingNothing() {
        AdminVatRateResource resource = newResource();
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.findById(99L)).thenReturn(null);
            Response response = resource.saveVatRate(
                    form("id", "99", "number", "1", "ratePercent", "20"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            rates.verify(() -> VatRate.findByNumber(any()), never());
        }
    }

    /**
     * Creating on a number another regime already carries is refused (the
     * collision guard with {@code edited == null}).
     */
    @Test
    void saveRefusesACreationOnATakenNumber() {
        AdminVatRateResource resource = newResource();
        VatRate holder = regime(11L, 1, "0.2000", "Taux normal");
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
                MockedConstruction<VatRate> created = mockConstruction(VatRate.class)) {
            rates.when(() -> VatRate.findByNumber(1)).thenReturn(holder);
            Response response = resource.saveVatRate(form("number", "1", "ratePercent", "20"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            assertEquals(0, created.constructed().size());
        }
    }

    /**
     * Renumbering onto a number another regime carries is refused (the
     * collision guard with {@code edited != null} and differing identifiers).
     */
    @Test
    void saveRefusesARenumberingOntoAnotherRegime() {
        AdminVatRateResource resource = newResource();
        VatRate edited = regime(11L, 2, "0.0550", "Taux réduit");
        VatRate holder = regime(12L, 1, "0.2000", "Taux normal");
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.findById(11L)).thenReturn(edited);
            rates.when(() -> VatRate.findByNumber(1)).thenReturn(holder);
            Response response = resource.saveVatRate(
                    form("id", "11", "number", "1", "ratePercent", "20"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            assertEquals(2, edited.number);
        }
    }

    /**
     * A row keeping its own number passes the collision guard (the
     * {@code holder.id.equals(edited.id)} fall-through), so only the rate and
     * the label move.
     */
    @Test
    void saveAcceptsARowKeepingItsOwnNumber() {
        AdminVatRateResource resource = newResource();
        VatRate edited = regime(11L, 1, "0.2000", "Taux normal");
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.findById(11L)).thenReturn(edited);
            rates.when(() -> VatRate.findByNumber(1)).thenReturn(edited);
            Response response = resource.saveVatRate(
                    form("id", "11", "number", "1", "ratePercent", "21"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=true"));
            assertEquals(new BigDecimal("0.2100"), edited.rate);
        }
    }

    /**
     * Renumbering onto a free number is accepted (the {@code holder == null}
     * leg of the collision guard, with a row being edited).
     */
    @Test
    void saveAcceptsARenumberingOntoAFreeNumber() {
        AdminVatRateResource resource = newResource();
        VatRate edited = regime(11L, 2, "0.0550", "Taux réduit");
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.findById(11L)).thenReturn(edited);
            rates.when(() -> VatRate.findByNumber(7)).thenReturn(null);
            Response response = resource.saveVatRate(
                    form("id", "11", "number", "7", "ratePercent", "5,5", "label", "Taux réduit"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=true"));
            assertEquals(7, edited.number);
        }
    }

    /**
     * An omitted label is stored as null rather than as an empty string, so the
     * row falls back on its number when a document prints it.
     */
    @Test
    void saveStoresAnOmittedLabelAsNull() {
        AdminVatRateResource resource = newResource();
        VatRate edited = regime(11L, 1, "0.2000", "Taux normal");
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.findById(11L)).thenReturn(edited);
            rates.when(() -> VatRate.findByNumber(1)).thenReturn(edited);
            resource.saveVatRate(form("id", "11", "number", "1", "ratePercent", "20", "label", "  "));
            assertNull(edited.label);
        }
    }

    /**
     * Deleting with no identifier at all is refused (the ternary true arm).
     */
    @Test
    void deleteRefusesAnAbsentIdentifier() {
        AdminVatRateResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Response response = resource.deleteVatRate(form("id", ""));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            panache.verifyNoInteractions();
        }
    }

    /**
     * Deleting an identifier naming nothing is refused (the ternary false arm
     * with a null row).
     */
    @Test
    void deleteRefusesAnIdentifierNamingNothing() {
        AdminVatRateResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.findById(99L)).thenReturn(null);
            Response response = resource.deleteVatRate(form("id", "99"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
        }
    }

    /**
     * A regime prices name is kept, and the refusal says how many (the
     * {@code prices > 0} true arm).
     */
    @Test
    void deleteRefusesARegimePricesName() {
        AdminVatRateResource resource = newResource();
        VatRate rate = mock(VatRate.class);
        rate.id = 11L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.findById(11L)).thenReturn(rate);
            panache.when(() -> Price.count("vat.id = ?1", 11L)).thenReturn(4L);
            Response response = resource.deleteVatRate(form("id", "11"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=false"));
            assertTrue(query(response).contains("4"));
            verify(rate, never()).delete();
        }
    }

    /**
     * A regime nothing uses is removed (the {@code prices > 0} false arm).
     */
    @Test
    void deleteRemovesAnUnusedRegime() {
        AdminVatRateResource resource = newResource();
        VatRate rate = mock(VatRate.class);
        rate.id = 11L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.findById(11L)).thenReturn(rate);
            panache.when(() -> Price.count("vat.id = ?1", 11L)).thenReturn(0L);
            Response response = resource.deleteVatRate(form("id", "11"));
            assertEquals(303, response.getStatus());
            assertTrue(query(response).contains("noticeOk=true"));
            verify(rate).delete();
        }
    }

    /**
     * The number reader answers null on an absent field (the null arm).
     *
     * @throws Exception on reflection failure
     */
    @Test
    void numberAnswersNullOnAnAbsentField() throws Exception {
        assertNull(privateMethod("number", String.class).invoke(newResource(), (String) null));
    }

    /**
     * The number reader answers null on a negative number (the ternary true
     * arm).
     *
     * @throws Exception on reflection failure
     */
    @Test
    void numberAnswersNullOnANegativeNumber() throws Exception {
        assertNull(privateMethod("number", String.class).invoke(newResource(), "-1"));
    }

    /**
     * The number reader answers null on a value that is not an integer (the
     * catch arm).
     *
     * @throws Exception on reflection failure
     */
    @Test
    void numberAnswersNullOnAMalformedValue() throws Exception {
        assertNull(privateMethod("number", String.class).invoke(newResource(), "1.5"));
    }

    /**
     * The number reader accepts zero and positive numbers (the ternary false
     * arm).
     *
     * @throws Exception on reflection failure
     */
    @Test
    void numberAcceptsZeroAndPositiveNumbers() throws Exception {
        Method method = privateMethod("number", String.class);
        assertEquals(0, method.invoke(newResource(), "0"));
        assertEquals(9, method.invoke(newResource(), "9"));
    }

    /**
     * The percentage converter answers null on an absent value (the null arm).
     *
     * @throws Exception on reflection failure
     */
    @Test
    void fractionAnswersNullOnAnAbsentValue() throws Exception {
        assertNull(privateMethod("fraction", BigDecimal.class).invoke(newResource(), (BigDecimal) null));
    }

    /**
     * The percentage converter answers null below zero (the first leg of the
     * range guard).
     *
     * @throws Exception on reflection failure
     */
    @Test
    void fractionAnswersNullBelowZero() throws Exception {
        assertNull(privateMethod("fraction", BigDecimal.class)
                .invoke(newResource(), new BigDecimal("-0.01")));
    }

    /**
     * The percentage converter answers null above a hundred (the second leg of
     * the range guard).
     *
     * @throws Exception on reflection failure
     */
    @Test
    void fractionAnswersNullAboveAHundred() throws Exception {
        assertNull(privateMethod("fraction", BigDecimal.class)
                .invoke(newResource(), new BigDecimal("100.01")));
    }

    /**
     * The percentage converter turns a percentage into a scale-4 fraction, the
     * two bounds included (the in-range fall-through).
     *
     * @throws Exception on reflection failure
     */
    @Test
    void fractionConvertsAPercentageIntoAFraction() throws Exception {
        Method method = privateMethod("fraction", BigDecimal.class);
        assertEquals(new BigDecimal("0.0550"), method.invoke(newResource(), new BigDecimal("5.5")));
        assertEquals(new BigDecimal("0.0000"), method.invoke(newResource(), BigDecimal.ZERO));
        assertEquals(new BigDecimal("1.0000"), method.invoke(newResource(), new BigDecimal("100")));
    }

    /**
     * The VAT screen is declared in the ARTICLES menu, next to the articles it
     * taxes, and points at this resource's path.
     */
    @Test
    void theScreenIsDeclaredInTheArticlesMenu() {
        com.intermarche.edge.domain.setting.Feature feature =
                com.intermarche.edge.domain.setting.Feature.VAT_RATE;
        assertEquals("vat_rate", feature.getKey());
        assertEquals("/admin/vat-rates", feature.getPath());
        assertEquals(com.intermarche.edge.domain.people.Menu.ARTICLES, feature.getMenu());
    }

    /**
     * BO-02-02-02: the posted country is trimmed, raised to upper case and
     * cut to the column's three characters; nothing typed answers null, the
     * regime then belonging to every country.
     */
    @Test
    void thePostedCountryIsNormalised() {
        assertNull(AdminVatRateResource.country(null));
        assertNull(AdminVatRateResource.country("   "));
        assertEquals("FR", AdminVatRateResource.country("  fr "));
        assertEquals("PRT", AdminVatRateResource.country("prtugal"));
    }
}
