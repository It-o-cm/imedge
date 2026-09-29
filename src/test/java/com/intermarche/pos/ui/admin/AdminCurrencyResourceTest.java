package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.payment.Currency;
import com.intermarche.pos.domain.people.Menu;
import com.intermarche.pos.domain.setting.Feature;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminCurrencyResource}.
 * <p>
 * Plain JUnit 5 + Mockito, no Quarkus and no database. Every guard of the save
 * path (blank code, non-positive rate) and both the insert/update and delete arms
 * are exercised (EXP-22).
 */
class AdminCurrencyResourceTest {

    /**
     * Builds a resource with a mocked template.
     *
     * @return the resource
     */
    private AdminCurrencyResource newResource() {
        AdminCurrencyResource resource = new AdminCurrencyResource();
        resource.adminCurrencies = mock(Template.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the template to one self-returning
     * instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminCurrencyResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminCurrencies.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
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
     * @param row the row the query resolves, or null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Currency> query(Currency row) {
        PanacheQuery<Currency> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * The screen is declared in the parametrage menu (EXP-22).
     */
    @Test
    void theScreenIsDeclaredInTheMenu() {
        Feature feature = Feature.CURRENCY;
        assertEquals("currency", feature.getKey());
        assertEquals("/admin/currencies", feature.getPath());
        assertEquals(Menu.PARAMETRAGE, feature.getMenu());
    }

    /**
     * The page lists the currencies.
     */
    @Test
    void currenciesPageListsCurrencies() {
        AdminCurrencyResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        Currency currency = new Currency();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Currency.list("order by displayOrder, code")).thenReturn(List.of(currency));
            assertSame(instance, resource.currenciesPage(null, true));
            verify(resource.adminCurrencies).data("currencies", List.of(currency));
        }
    }

    /**
     * A blank code is refused (first guard).
     */
    @Test
    void saveRefusesABlankCode() {
        Response response = newResource().saveCurrency(form());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * A missing or non-positive rate is refused (both legs of the rate guard).
     */
    @Test
    void saveRefusesAMissingOrNonPositiveRate() {
        AdminCurrencyResource resource = newResource();
        assertTrue(resource.saveCurrency(form("code", "FRF"))
                .getLocation().toString().contains("noticeOk=false"));
        assertTrue(resource.saveCurrency(form("code", "FRF", "euroPerUnit", "0"))
                .getLocation().toString().contains("noticeOk=false"));
        assertTrue(resource.saveCurrency(form("code", "FRF", "euroPerUnit", "-1,5"))
                .getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * An absent code inserts the currency, upper-casing the code and keeping the
     * full-precision rate (the franc, 6,55957).
     */
    @Test
    void saveInsertsTheFranc() {
        AdminCurrencyResource resource = newResource();
        PanacheQuery<Currency> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Currency> created = mockConstruction(Currency.class)) {
            panache.when(() -> Currency.find("code", "FRF")).thenReturn(absent);
            Response response = resource.saveCurrency(form("code", " frf ", "label", "Franc",
                    "euroPerUnit", "6,55957", "displayOrder", "3", "active", "on"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            Currency currency = created.constructed().get(0);
            assertEquals("FRF", currency.code);
            assertEquals("Franc", currency.label);
            assertEquals(new BigDecimal("6.55957"), currency.euroPerUnit);
            assertEquals(3, currency.displayOrder);
            assertTrue(currency.active);
            verify(currency, times(1)).persist();
        }
    }

    /**
     * A present code updates the stored currency in place.
     */
    @Test
    void saveUpdatesAnExistingCurrency() {
        AdminCurrencyResource resource = newResource();
        Currency stored = new Currency();
        stored.code = "FRF";
        PanacheQuery<Currency> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Currency.find("code", "FRF")).thenReturn(present);
            Response response = resource.saveCurrency(form("code", "FRF", "label", "Ancien Franc",
                    "euroPerUnit", "6,55957"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("Ancien Franc", stored.label);
        assertEquals(new BigDecimal("6.55957"), stored.euroPerUnit);
    }

    /**
     * Deleting an unknown code is refused; a known one is removed.
     */
    @Test
    void deleteRefusesUnknownAndRemovesKnown() {
        AdminCurrencyResource resource = newResource();
        PanacheQuery<Currency> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Currency.find("code", "FRF")).thenReturn(absent);
            assertTrue(resource.deleteCurrency(form("code", "FRF"))
                    .getLocation().toString().contains("noticeOk=false"));
            Currency stored = mock(Currency.class);
            PanacheQuery<Currency> present = query(stored);
            panache.when(() -> Currency.find("code", "FRF")).thenReturn(present);
            assertTrue(resource.deleteCurrency(form("code", "FRF"))
                    .getLocation().toString().contains("noticeOk=true"));
            verify(stored, times(1)).delete();
            verify(stored, never()).persist();
        }
    }

    /**
     * A delete with no code posted is refused without touching the database.
     */
    @Test
    void deleteRefusesABlankCode() {
        Response response = newResource().deleteCurrency(form());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }
}
