package com.intermarche.edge.service;

import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PdvHistory;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PdvRegistryService} (BO-02-05-01, -02, -03, -05).
 * <p>
 * The Panache finders resolve to {@link PanacheEntityBase} and are intercepted
 * with a static mock; the entities created are intercepted with
 * {@link MockedConstruction} so their {@code persist()} is observable and
 * neutral. Branches: the invalid number; the unknown, known and absent
 * enseigne; each leg of the renumbering condition (no original, blank,
 * equal, different); the renumbering refusals (unknown source, taken target,
 * former number of another) and its success; the creation refused on a former
 * number and the creation; the history written or not; the null name; both
 * upserts with each arm of their guards; the former holder absent, the same
 * point of sale, another one.
 */
class PdvRegistryServiceTest {

    /**
     * Builds the service over a mocked echelon engine.
     *
     * @return the service
     */
    private PdvRegistryService newService() {
        PdvRegistryService service = new PdvRegistryService();
        service.echelonSettings = mock(EchelonSettingService.class);
        return service;
    }

    /**
     * Stubs one finder query.
     *
     * @param panache the active static mock
     * @param row the row the query answers, or null
     * @param query the query text
     * @param params its parameters
     */
    @SuppressWarnings("unchecked")
    private void stub(MockedStatic<PanacheEntityBase> panache, Object row, String query, Object... params) {
        PanacheQuery<Object> answer = mock(PanacheQuery.class);
        when(answer.firstResult()).thenReturn(row);
        panache.when(() -> PanacheEntityBase.find(query, params)).thenReturn(answer);
    }

    /**
     * Stubs the point of sale carrying a number.
     *
     * @param panache the active static mock
     * @param number the number
     * @param pdv the point of sale, or null
     */
    private void stubPdv(MockedStatic<PanacheEntityBase> panache, String number, Pdv pdv) {
        stub(panache, pdv, "pdvNumber", number);
    }

    /**
     * Stubs the former holder of a number.
     *
     * @param panache the active static mock
     * @param number the number
     * @param holderId the former holder's id, or null for none
     */
    private void stubFormer(MockedStatic<PanacheEntityBase> panache, String number, Long holderId) {
        PdvHistory change = null;
        if (holderId != null) {
            change = new PdvHistory();
            change.pdvId = holderId;
        }
        stub(panache, change, "kind = ?1 and oldValue = ?2", PdvHistory.Kind.NUMBER, number);
    }

    /**
     * Builds a held point of sale.
     *
     * @param id its id
     * @param number its number
     * @return the point of sale
     */
    private Pdv held(Long id, String number) {
        Pdv pdv = new Pdv();
        pdv.id = id;
        pdv.pdvNumber = number;
        pdv.enseigneCode = "IF";
        pdv.adherentCode = "AD1";
        return pdv;
    }

    /**
     * A number that is not five digits is refused before anything is read.
     */
    @Test
    void anInvalidNumberIsRefused() {
        PdvRegistryService.Result result = newService().save("12A4", null, "x", null, null, true,
                PdvHistory.Source.SCREEN);
        assertFalse(result.ok());
        assertEquals("Le numéro de PDV doit comporter exactement 5 chiffres.", result.message());
        assertNull(result.pdv());
    }

    /**
     * An enseigne the organisation does not know is refused.
     */
    @Test
    void anUnknownEnseigneIsRefused() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, null, "code", "XX");
            PdvRegistryService.Result result = newService().save("12345", null, "x", "XX", null, true,
                    PdvHistory.Source.SCREEN);
            assertEquals("Enseigne inconnue : XX", result.message());
        }
    }

    /**
     * A free number creates the point of sale, filled before its insert, with
     * no history; a null name is stored empty.
     */
    @Test
    void aFreeNumberCreatesThePointOfSale() {
        List<String> nameAtPersist = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Pdv> pdvs = mockConstruction(Pdv.class,
                     (entity, context) -> doAnswer(call -> nameAtPersist.add(entity.name)).when(entity).persist());
             MockedConstruction<PdvHistory> history = mockConstruction(PdvHistory.class)) {
            stub(panache, new Enseigne(), "code", "IF");
            stubPdv(panache, "12345", null);
            stubFormer(panache, "12345", null);
            PdvRegistryService.Result result = newService().save("12345", null, null, "IF", "AD1", true,
                    PdvHistory.Source.IMPORT);
            assertTrue(result.ok());
            assertTrue(result.created());
            Pdv created = pdvs.constructed().get(0);
            assertSame(created, result.pdv());
            assertEquals("12345", created.pdvNumber);
            assertEquals("IF", created.enseigneCode);
            assertEquals("AD1", created.adherentCode);
            assertEquals(List.of(""), nameAtPersist);
            assertTrue(history.constructed().isEmpty());
        }
    }

    /**
     * A number another point of sale left is not handed to a new one.
     */
    @Test
    void aFormerNumberIsNotHandedToANewPointOfSale() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", null);
            stubFormer(panache, "12345", 3L);
            PdvRegistryService.Result result = newService().save("12345", "  ", "x", null, null, true,
                    PdvHistory.Source.SCREEN);
            assertFalse(result.ok());
            assertTrue(result.message().startsWith("Le numéro 12345 a été porté par un autre point de vente"));
        }
    }

    /**
     * An update moving a point of sale to another enseigne and another adhérent
     * writes both changes to the history.
     */
    @Test
    void anUpdateWritesTheChangesToTheHistory() {
        Pdv pdv = held(5L, "12345");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<PdvHistory> history = mockConstruction(PdvHistory.class)) {
            stub(panache, new Enseigne(), "code", "NF");
            stubPdv(panache, "12345", pdv);
            PdvRegistryService.Result result = newService().save("12345", "12345", "Lyon", "NF", null, false,
                    PdvHistory.Source.SCREEN);
            assertTrue(result.ok());
            assertFalse(result.created());
            assertEquals(2, history.constructed().size());
            PdvHistory enseigne = history.constructed().get(0);
            assertEquals(PdvHistory.Kind.ENSEIGNE, enseigne.kind);
            assertEquals("IF", enseigne.oldValue);
            assertEquals("NF", enseigne.newValue);
            assertEquals(5L, enseigne.pdvId);
            assertEquals(PdvHistory.Source.SCREEN, enseigne.source);
            verify(enseigne).persist();
            assertEquals(PdvHistory.Kind.ADHERENT, history.constructed().get(1).kind);
            assertEquals("NF", pdv.enseigneCode);
            assertNull(pdv.adherentCode);
            assertFalse(pdv.active);
            assertEquals("Lyon", pdv.name);
        }
    }

    /**
     * An update changing nothing of the identity writes no history.
     */
    @Test
    void anUnchangedIdentityWritesNoHistory() {
        Pdv pdv = held(5L, "12345");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<PdvHistory> history = mockConstruction(PdvHistory.class)) {
            stub(panache, new Enseigne(), "code", "IF");
            stubPdv(panache, "12345", pdv);
            newService().save("12345", null, "Lyon", "IF", "AD1", true, PdvHistory.Source.SCREEN);
            assertTrue(history.constructed().isEmpty());
        }
    }

    /**
     * Renumbering an unknown point of sale is refused.
     */
    @Test
    void renumberingAnUnknownPointOfSaleIsRefused() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", null);
            assertEquals("PDV à renommer introuvable.", newService().save("54321", "12345", "x", null, null,
                    true, PdvHistory.Source.SCREEN).message());
        }
    }

    /**
     * Renumbering onto a number in use is refused.
     */
    @Test
    void renumberingOntoATakenNumberIsRefused() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", held(5L, "12345"));
            stubPdv(panache, "54321", held(6L, "54321"));
            assertEquals("Ce numéro de PDV est déjà utilisé.", newService().save("54321", "12345", "x", null, null,
                    true, PdvHistory.Source.SCREEN).message());
        }
    }

    /**
     * Renumbering onto a number another point of sale left is refused.
     */
    @Test
    void renumberingOntoAnotherFormerNumberIsRefused() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", held(5L, "12345"));
            stubPdv(panache, "54321", null);
            stubFormer(panache, "54321", 9L);
            assertFalse(newService().save("54321", "12345", "x", null, null, true,
                    PdvHistory.Source.SCREEN).ok());
        }
    }

    /**
     * Renumbering keeps the former number in the history, carries the echelon
     * parameters and the checkout configuration over — a point of sale may
     * take back a number it left itself.
     */
    @Test
    void renumberingKeepsTheFormerNumber() {
        Pdv pdv = held(5L, "12345");
        PdvRegistryService service = newService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<PdvHistory> history = mockConstruction(PdvHistory.class)) {
            stubPdv(panache, "12345", pdv);
            stubPdv(panache, "54321", null);
            stubFormer(panache, "54321", 5L);
            PdvRegistryService.Result result = service.save("54321", "12345", "Lyon", null, "AD1", true,
                    PdvHistory.Source.IMPORT);
            assertTrue(result.ok());
            assertEquals("54321", pdv.pdvNumber);
            // The first construction is the former-holder row of the stub.
            PdvHistory number = history.constructed().get(1);
            assertEquals(PdvHistory.Kind.NUMBER, number.kind);
            assertEquals("12345", number.oldValue);
            assertEquals("54321", number.newValue);
            assertEquals(PdvHistory.Source.IMPORT, number.source);
            assertEquals(PdvHistory.Kind.ENSEIGNE, history.constructed().get(2).kind);
            verify(service.echelonSettings).migratePdv("12345", "54321");
            panache.verify(() -> PanacheEntityBase.update("pdvNumber = ?1 where pdvNumber = ?2", "54321", "12345"),
                    org.mockito.Mockito.times(2));
        }
    }

    /**
     * A country unknown is created named by its code unless a name is given;
     * a known one keeps its name on a blank one and its language on a null one,
     * and loses it on a blank one.
     */
    @Test
    void upsertCountryCoversItsGuards() {
        PdvRegistryService service = newService();
        Country held = new Country();
        held.code = "FR";
        held.name = "France";
        held.defaultLanguage = "fr";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Country> countries = mockConstruction(Country.class)) {
            stub(panache, null, "code", "PT");
            Country created = service.upsertCountry("PT", null, "pt");
            assertSame(countries.constructed().get(0), created);
            assertEquals("PT", created.name);
            assertEquals("pt", created.defaultLanguage);
            verify(created).persist();
            stub(panache, null, "code", "BE");
            assertEquals("Belgique", service.upsertCountry("BE", "Belgique", null).name);
            stub(panache, held, "code", "FR");
            assertSame(held, service.upsertCountry("FR", " ", null));
            assertEquals("France", held.name);
            assertEquals("fr", held.defaultLanguage);
            service.upsertCountry("FR", "France métropolitaine", " ");
            assertEquals("France métropolitaine", held.name);
            assertNull(held.defaultLanguage);
        }
    }

    /**
     * An enseigne unknown is created named by its code and attached to its
     * country; a known one keeps what a null or blank cell does not restate.
     */
    @Test
    void upsertEnseigneCoversItsGuards() {
        PdvRegistryService service = newService();
        Enseigne held = new Enseigne();
        held.code = "IF";
        held.name = "Intermarché";
        held.countryCode = "FR";
        held.defaultLanguage = "fr";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Enseigne> enseignes = mockConstruction(Enseigne.class)) {
            stub(panache, null, "code", "IP");
            Enseigne created = service.upsertEnseigne("IP", null, "PT", "pt");
            assertSame(enseignes.constructed().get(0), created);
            assertEquals("IP", created.name);
            assertEquals("PT", created.countryCode);
            assertEquals("pt", created.defaultLanguage);
            verify(created).persist();
            stub(panache, held, "code", "IF");
            service.upsertEnseigne("IF", "", null, null);
            assertEquals("Intermarché", held.name);
            assertEquals("FR", held.countryCode);
            assertEquals("fr", held.defaultLanguage);
            service.upsertEnseigne("IF", "Intermarché Hyper", "BE", "");
            assertEquals("Intermarché Hyper", held.name);
            assertEquals("BE", held.countryCode);
            assertNull(held.defaultLanguage);
            verify(service.echelonSettings, never()).migratePdv(anyString(), anyString());
        }
    }
}
