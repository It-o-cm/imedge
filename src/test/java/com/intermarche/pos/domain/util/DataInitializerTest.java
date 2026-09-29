package com.intermarche.pos.domain.util;

import com.intermarche.pos.domain.barcode.CouponField;
import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.campaign.Campaign;
import com.intermarche.pos.domain.catalog.Nomenclature;
import com.intermarche.pos.domain.catalog.NomenclatureLevel;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.domain.catalog.VatRate;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.setting.EchelonLevel;
import com.intermarche.pos.domain.setting.EchelonSetting;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Country;
import com.intermarche.pos.domain.store.Enseigne;
import com.intermarche.pos.domain.store.Pdv;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.service.campaign.CampaignFeedProjector;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the echelon-seeding branch of {@link DataInitializer}
 * ({@code loadEchelons}), the only part of the seeder this campaign adds.
 * <p>
 * The four echelon entities are Panache entities and are NOT bytecode-enhanced
 * under plain {@code mvn test}: their {@code deleteAll()} statics are neutralized
 * with {@link org.mockito.Mockito#mockStatic(Class)} on {@link PanacheEntityBase}
 * and their {@code persist()} with {@link org.mockito.Mockito#mockConstruction(Class)}
 * on each entity, so the method runs with no database. Both arms of the role
 * guard are covered: a central node seeds the registry, a store node returns at
 * once and constructs nothing.
 */
class DataInitializerTest {

    /**
     * Central arm: a central node wipes and reseeds the echelon registry — one
     * country, one enseigne rattached to it, one PDV rattached to the enseigne
     * and one echelon setting — each persisted with the expected rattachment
     * fields.
     */
    @Test
    void loadEchelonsSeedsRegistryOnCentralNode() {
        DataInitializer initializer = new DataInitializer();
        initializer.role = "central";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Country> countries = mockConstruction(Country.class);
             MockedConstruction<Enseigne> enseignes = mockConstruction(Enseigne.class);
             MockedConstruction<Pdv> pdvs = mockConstruction(Pdv.class);
             MockedConstruction<EchelonSetting> settings = mockConstruction(EchelonSetting.class)) {
            initializer.loadEchelons();
            assertEquals(1, countries.constructed().size());
            Country france = countries.constructed().get(0);
            assertEquals("FR", france.code);
            assertEquals("France", france.name);
            assertEquals("fr", france.defaultLanguage);
            verify(france).persist();
            assertEquals(1, enseignes.constructed().size());
            Enseigne itm = enseignes.constructed().get(0);
            assertEquals("ITM", itm.code);
            assertEquals("FR", itm.countryCode);
            verify(itm).persist();
            assertEquals(1, pdvs.constructed().size());
            Pdv pdv = pdvs.constructed().get(0);
            assertEquals("07039", pdv.pdvNumber);
            assertEquals("ITM", pdv.enseigneCode);
            verify(pdv).persist();
            assertEquals(1, settings.constructed().size());
            EchelonSetting setting = settings.constructed().get(0);
            assertEquals(EchelonLevel.COUNTRY, setting.level);
            assertEquals("FR", setting.echelonCode);
            verify(setting).persist();
        }
    }

    /**
     * Store arm: a store node returns before touching the registry — nothing is
     * constructed, proving a store never seeds echelons (it receives them by
     * pull), whatever the build profile.
     */
    @Test
    void loadEchelonsSeedsNothingOnStoreNode() {
        DataInitializer initializer = new DataInitializer();
        initializer.role = "store";
        try (MockedConstruction<Country> countries = mockConstruction(Country.class);
             MockedConstruction<Enseigne> enseignes = mockConstruction(Enseigne.class);
             MockedConstruction<Pdv> pdvs = mockConstruction(Pdv.class);
             MockedConstruction<EchelonSetting> settings = mockConstruction(EchelonSetting.class)) {
            initializer.loadEchelons();
            assertTrue(countries.constructed().isEmpty());
            assertTrue(enseignes.constructed().isEmpty());
            assertTrue(pdvs.constructed().isEmpty());
            assertTrue(settings.constructed().isEmpty());
        }
    }

    /**
     * Invokes a private method of {@link DataInitializer} by reflection, so a
     * single seed step can be exercised in isolation without booting the whole
     * {@code onStart} pipeline.
     *
     * @param target the initializer instance
     * @param name the private method name
     * @param types the declared parameter types, in order
     * @param args the arguments, in order
     * @return whatever the method returns
     */
    private Object invokePrivate(DataInitializer target, String name, Class<?>[] types, Object[] args) {
        try {
            Method m = DataInitializer.class.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * VAT loop: {@code loadVatRates} iterates the five-row seed table, so the
     * loop entering-and-exiting branch is covered by a single call — five
     * regimes are constructed and each is persisted before any price names it.
     */
    @Test
    void loadVatRatesSeedsFiveRegimes() {
        DataInitializer initializer = new DataInitializer();
        try (MockedConstruction<VatRate> vats = mockConstruction(VatRate.class)) {
            invokePrivate(initializer, "loadVatRates", new Class<?>[]{}, new Object[]{});
            assertEquals(5, vats.constructed().size());
            for (VatRate regime : vats.constructed()) {
                verify(regime).persist();
            }
        }
    }

    /**
     * Password-change TRUE arm: a supervising account seeded WITH a back-office
     * password carries {@code mustChangePassword}, because the password is
     * written in source and known outside the account.
     */
    @Test
    void createEmployeeForcesPasswordChangeWhenPasswordGiven() {
        DataInitializer initializer = new DataInitializer();
        try (MockedConstruction<Employee> employees = mockConstruction(Employee.class)) {
            invokePrivate(initializer, "createEmployee",
                    new Class<?>[]{String.class, String.class, String.class, String.class, String.class,
                            Employee.EmployeeRole.class, String.class},
                    new Object[]{"11111111", "mcurie", "1111", "Marie", "Curie",
                            Employee.EmployeeRole.MANAGER, "changeme00"});
            assertEquals(1, employees.constructed().size());
            Employee emp = employees.constructed().get(0);
            assertTrue(emp.mustChangePassword);
            assertEquals("mcurie", emp.loginName);
            assertEquals(Employee.EmployeeRole.MANAGER, emp.role);
            verify(emp).persist();
        }
    }

    /**
     * Password-change FALSE arm: an account seeded WITHOUT a back-office
     * password (a cashier or picker who has no back office) leaves
     * {@code mustChangePassword} off — the absence of a password is what says so.
     */
    @Test
    void createEmployeeLeavesPasswordChangeOffWhenNoPassword() {
        DataInitializer initializer = new DataInitializer();
        try (MockedConstruction<Employee> employees = mockConstruction(Employee.class)) {
            invokePrivate(initializer, "createEmployee",
                    new Class<?>[]{String.class, String.class, String.class, String.class, String.class,
                            Employee.EmployeeRole.class, String.class},
                    new Object[]{"22222222", "aeinstein", "2222", "Albert", "Einstein",
                            Employee.EmployeeRole.PICKER, null});
            assertEquals(1, employees.constructed().size());
            Employee emp = employees.constructed().get(0);
            assertFalse(emp.mustChangePassword);
            assertEquals("aeinstein", emp.loginName);
            verify(emp).persist();
        }
    }

    /**
     * Administered range WITH a price field: a non-empty prefix is kept (the
     * ternary's false arm) and a non-negative price offset attaches one PRICE
     * field (the guard's true arm), currency EUR with two decimals.
     */
    @Test
    void administerKeepsPrefixAndAttachesPriceField() {
        DataInitializer initializer = new DataInitializer();
        CouponType type = new CouponType();
        invokePrivate(initializer, "administer",
                new Class<?>[]{CouponType.class, String.class, int.class, int.class, int.class},
                new Object[]{type, "789", 15, 11, 4});
        assertEquals("789", type.prefix);
        assertEquals(Integer.valueOf(15), type.codeLength);
        assertEquals(CouponField.Kind.NUMERIC, type.codeKind);
        assertEquals(1, type.fields.size());
        CouponField price = type.fields.get(0);
        assertEquals(CouponField.Role.PRICE, price.role);
        assertEquals(11, price.offsetPosition);
        assertEquals(4, price.fieldLength);
        assertEquals(Integer.valueOf(2), price.decimals);
        assertEquals(CouponField.PriceCurrency.EUR, price.currency);
        assertSameType(type, price);
    }

    /**
     * Administered range WITHOUT a price field: an empty prefix collapses to
     * {@code null} (the ternary's true arm) and a negative price offset attaches
     * no field (the guard's false arm), so the range carries no price.
     */
    @Test
    void administerDropsEmptyPrefixAndOmitsPriceField() {
        DataInitializer initializer = new DataInitializer();
        CouponType type = new CouponType();
        invokePrivate(initializer, "administer",
                new Class<?>[]{CouponType.class, String.class, int.class, int.class, int.class},
                new Object[]{type, "", 40, -1, 0});
        assertNull(type.prefix);
        assertEquals(Integer.valueOf(40), type.codeLength);
        assertTrue(type.fields.isEmpty());
    }

    /**
     * Asserts the price field is rattached to its coupon type.
     *
     * @param type the owning coupon type
     * @param price the price field
     */
    private void assertSameType(CouponType type, CouponField price) {
        assertEquals(type, price.couponType);
    }

    /**
     * Filing TRUE arm: when a seeded article is found by name, it is filed under
     * the nomenclature node — the article reaches its {@code familyCode} through
     * membership, exercised via the {@code fileUnderNomenclature} static.
     */
    @Test
    void fileArticleFilesExistingArticle() {
        DataInitializer initializer = new DataInitializer();
        Product article = new Product();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(article);
            panache.when(() -> PanacheEntityBase.find("name", "Lait UHT 1L")).thenReturn(query);
            invokePrivate(initializer, "fileArticle",
                    new Class<?>[]{String.class, String.class},
                    new Object[]{"Lait UHT 1L", "202002000001"});
            families.verify(() -> ProductFamily.fileUnderNomenclature(article, "202002000001"));
        }
    }

    /**
     * Filing FALSE arm: when no article matches the name, nothing is filed — the
     * {@code fileUnderNomenclature} static is never reached, so a name with no
     * seeded article is a no-op.
     */
    @Test
    void fileArticleSkipsMissingArticle() {
        DataInitializer initializer = new DataInitializer();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> PanacheEntityBase.find("name", "Absent")).thenReturn(query);
            invokePrivate(initializer, "fileArticle",
                    new Class<?>[]{String.class, String.class},
                    new Object[]{"Absent", "202002000001"});
            families.verifyNoInteractions();
        }
    }

    /**
     * Full seeder drive: on a store node, {@code onStart} wipes then reloads
     * every referential table, running all seed steps in one pass with the
     * database neutralized — Panache statics mocked and each persisted entity
     * type mock-constructed. The exact number of each entity built is asserted
     * (store, VAT regimes, employees, families, products, prices, coupon types,
     * coupon fields, campaign), the campaign feed projection is verified, and
     * the store arm of {@code loadEchelons} is taken so no echelon entity is
     * constructed.
     */
    @Test
    void onStartRunsEveryStoreSeedStep() {
        DataInitializer initializer = new DataInitializer();
        initializer.role = "store";
        CampaignFeedProjector projector = mock(CampaignFeedProjector.class);
        initializer.campaignFeedProjector = projector;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Store> stores = mockConstruction(Store.class);
             MockedConstruction<Address> addresses = mockConstruction(Address.class);
             MockedConstruction<VatRate> vats = mockConstruction(VatRate.class);
             MockedConstruction<Employee> employees = mockConstruction(Employee.class);
             MockedConstruction<ProductFamily> families = mockConstruction(ProductFamily.class,
                     (mock, ctx) -> {
                         mock.products = new HashSet<>();
                         mock.productFamilies = new HashSet<>();
                     });
             MockedConstruction<Product> products = mockConstruction(Product.class);
             MockedConstruction<Price> prices = mockConstruction(Price.class);
             MockedConstruction<CouponType> couponTypes = mockConstruction(CouponType.class);
             MockedConstruction<CouponField> couponFields = mockConstruction(CouponField.class);
             MockedConstruction<Nomenclature> nomenclatures = mockConstruction(Nomenclature.class);
             MockedConstruction<NomenclatureLevel> levels = mockConstruction(NomenclatureLevel.class);
             MockedConstruction<Campaign> campaigns = mockConstruction(Campaign.class);
             MockedConstruction<Country> countries = mockConstruction(Country.class);
             MockedConstruction<Enseigne> enseignes = mockConstruction(Enseigne.class);
             MockedConstruction<Pdv> pdvs = mockConstruction(Pdv.class);
             MockedConstruction<EchelonSetting> settings = mockConstruction(EchelonSetting.class)) {
            Employee marie = mock(Employee.class);
            Employee admin = mock(Employee.class);
            PanacheQuery<Employee> marieQuery = employeeQueryReturning(marie);
            PanacheQuery<Employee> adminQuery = employeeQueryReturning(admin);
            PanacheQuery<Product> noArticle = productQueryReturningNull();
            panache.when(() -> PanacheEntityBase.find("loginName", "mcurie")).thenReturn(marieQuery);
            panache.when(() -> PanacheEntityBase.find("loginName", "admin")).thenReturn(adminQuery);
            panache.when(() -> PanacheEntityBase.find("name", "Lait UHT 1L")).thenReturn(noArticle);
            panache.when(() -> PanacheEntityBase.find("name", "Jambon Blanc 100g")).thenReturn(noArticle);
            panache.when(() -> PanacheEntityBase.find("name", "Biscuits Chocolat 200g")).thenReturn(noArticle);
            panache.when(() -> PanacheEntityBase.find("name", "Poulet Rôti 1.2kg")).thenReturn(noArticle);
            initializer.onStart(new StartupEvent());
            assertEquals(1, stores.constructed().size());
            assertEquals(1, addresses.constructed().size());
            assertEquals(5, vats.constructed().size());
            assertEquals(4, employees.constructed().size());
            assertEquals(31, families.constructed().size());
            assertEquals(38, products.constructed().size());
            assertEquals(41, prices.constructed().size());
            assertEquals(8, couponTypes.constructed().size());
            assertEquals(4, couponFields.constructed().size());
            assertEquals(1, nomenclatures.constructed().size());
            assertEquals(4, levels.constructed().size());
            assertEquals(1, campaigns.constructed().size());
            assertTrue(countries.constructed().isEmpty());
            assertTrue(enseignes.constructed().isEmpty());
            assertTrue(pdvs.constructed().isEmpty());
            assertTrue(settings.constructed().isEmpty());
            verify(projector).project();
        }
    }

    /**
     * Builds a Panache query mock whose {@code firstResult} yields the given
     * employee, so a {@code find(...).firstResult()} lookup in the seeder
     * resolves to an existing account.
     *
     * @param employee the employee the query returns
     * @return the query mock
     */
    private PanacheQuery<Employee> employeeQueryReturning(Employee employee) {
        @SuppressWarnings("unchecked")
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        return query;
    }

    /**
     * Builds a Panache query mock whose {@code firstResult} yields null, so a
     * seeded-article lookup finds nothing and files no article.
     *
     * @return the query mock
     */
    private PanacheQuery<Product> productQueryReturningNull() {
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(null);
        return query;
    }
}
