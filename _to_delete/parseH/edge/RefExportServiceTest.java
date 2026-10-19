package com.intermarche.edge.service.sync.store;

import com.intermarche.edge.domain.barcode.CouponControl;
import com.intermarche.edge.domain.barcode.CouponField;
import com.intermarche.edge.domain.barcode.CouponType;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.EchelonSetting;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.catalog.ProductType;
import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import com.intermarche.edge.service.sync.RefPayloads;

/**
 * Unit tests for {@link RefExportService}.
 * <p>
 * The service reads every domain through Panache static finders
 * ({@code X.find("order by ...").page(p, s).list()}) and folds a SHA-256
 * fingerprint over the canonical row stream. All static access is intercepted
 * with {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase};
 * finder chains are plain Mockito mocks returning entity mocks whose public
 * fields are set directly. No database and no Quarkus context is booted.
 * <p>
 * The fingerprint oracle is an independent SHA-256 over hand-written canonical
 * strings ({@link #sha256hex(String)}); the empty-input fingerprint is also
 * pinned to the well-known constant so the hex encoding is checked against a
 * value not derived from the class under test. The two private-map caches are
 * seeded through reflection to exercise the fresh and expired cache arms
 * deterministically without mocking the clock.
 * <p>
 * Branch enumeration: {@code getFingerprints} — the four arms of
 * {@code cachedAt == null || now - cachedAt > TTL} (absent / expired / fresh);
 * {@code getPage} — the five domain switch arms plus the default throw;
 * {@code computeFingerprint} — the do-while both arms (empty exit, non-empty
 * continue) and the catch block; {@code canonical} — the five {@code instanceof}
 * true arms plus the final all-false / default arm; {@code toDto(Product)} the
 * {@code productType} ternary both arms; {@code toDto(Price)} the {@code product}
 * ternary both arms; {@code iso} both arms; {@code n} both arms.
 */
class RefExportServiceTest {

    /** The well-known SHA-256 of the empty byte stream. */
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /**
     * Computes the lowercase hex SHA-256 of a UTF-8 string, an oracle
     * independent of the class under test.
     *
     * @param input the string to digest
     * @return the fingerprint, hex encoded
     * @throws Exception if the SHA-256 algorithm is unavailable
     */
    private String sha256hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest(input.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Builds a finder-chain mock whose {@code page(0, 1000)} yields the given
     * first page and whose {@code page(1, 1000)} yields an empty page, matching
     * the paging loop of {@code computeFingerprint}.
     *
     * @param firstPage the rows returned for page 0
     * @param <T> the entity type
     * @return the mocked query returned by {@code find(...)}
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> pagedQuery(List<T> firstPage) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        PanacheQuery<T> page0 = mock(PanacheQuery.class);
        PanacheQuery<T> page1 = mock(PanacheQuery.class);
        when(query.page(0, 1000)).thenReturn(page0);
        when(query.page(1, 1000)).thenReturn(page1);
        when(page0.list()).thenReturn(firstPage);
        when(page1.list()).thenReturn(List.of());
        return query;
    }

    /**
     * Builds a finder-chain mock whose {@code page(page, size)} yields the given
     * rows, for a single {@code getPage} call.
     *
     * @param page the expected page index
     * @param size the expected page size
     * @param rows the rows returned
     * @param <T> the entity type
     * @return the mocked query returned by {@code find(...)}
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> singlePage(int page, int size, List<T> rows) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        PanacheQuery<T> paged = mock(PanacheQuery.class);
        when(query.page(page, size)).thenReturn(paged);
        when(paged.list()).thenReturn(rows);
        return query;
    }

    /**
     * Seeds the private fingerprint caches of a service via reflection.
     *
     * @param service the service to seed
     * @param domain the domain key
     * @param fingerprint the cached fingerprint value
     * @param cachedAt the cache timestamp (epoch millis)
     * @throws Exception if reflection fails
     */
    @SuppressWarnings("unchecked")
    private void seed(RefExportService service, String domain, String fingerprint, long cachedAt)
            throws Exception {
        Field cacheField = RefExportService.class.getDeclaredField("fingerprintCache");
        cacheField.setAccessible(true);
        ((Map<String, String>) cacheField.get(service)).put(domain, fingerprint);
        Field atField = RefExportService.class.getDeclaredField("fingerprintCachedAt");
        atField.setAccessible(true);
        ((Map<String, Long>) atField.get(service)).put(domain, cachedAt);
    }

    /**
     * Wires a mock {@link PosSettingsService} into a service so the SETTINGS
     * export resolves against the given effective values (the store publishes
     * the resolved result, not the raw rows).
     *
     * @param service the service to wire
     * @param resolved the resolved administered values to publish
     */
    private void wireSettings(RefExportService service, Map<String, String> resolved) {
        PosSettingsService posSettings = mock(PosSettingsService.class);
        when(posSettings.administeredValues()).thenReturn(resolved);
        // Both forms answer the same rows here: this helper serves the tests that
        // do not care which register is asking. A null terminal IS the normal
        // case for them, hence nullable() and not any().
        when(posSettings.administeredValues(nullable(String.class))).thenReturn(resolved);
        service.posSettingsService = posSettings;
    }

    /**
     * Wires a mock {@link PosSettingsService} that answers a DIFFERENT resolution
     * per terminal, which is what a per-register echelon resolution does.
     *
     * @param service the service to wire
     * @param perTerminal the resolved values keyed by asking terminal
     * @param forNoTerminal the resolved values when no register names itself
     */
    private void wireSettingsPerTerminal(RefExportService service,
            Map<String, Map<String, String>> perTerminal, Map<String, String> forNoTerminal) {
        PosSettingsService posSettings = mock(PosSettingsService.class);
        when(posSettings.administeredValues()).thenReturn(forNoTerminal);
        when(posSettings.administeredValues(nullable(String.class))).thenAnswer(call -> {
            String terminal = call.getArgument(0);
            if (terminal == null) {
                return forNoTerminal;
            }
            return perTerminal.getOrDefault(terminal, forNoTerminal);
        });
        service.posSettingsService = posSettings;
    }

    // --------------------------------------------------
    // getPage — switch arms, mappings and ternaries
    // --------------------------------------------------

    /**
     * Covers the default switch arm of {@code getPage}: an unknown domain
     * raises an {@link IllegalArgumentException} with the offending name.
     */
    @Test
    void getPageThrowsOnUnknownDomain() {
        RefExportService service = new RefExportService();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getPage("UNKNOWN", 0, 10));
        assertEquals("Domaine inconnu: UNKNOWN", ex.getMessage());
    }

    /**
     * Covers the FAMILIES switch arm and {@code toDto(ProductFamily)}: the
     * family fields flow verbatim into the payload.
     */
    @Test
    void getPageMapsFamilies() {
        RefExportService service = new RefExportService();
        ProductFamily family = mock(ProductFamily.class);
        family.code = "F1";
        family.description = "Fruits";
        family.flags = "BIO";
        com.intermarche.edge.domain.catalog.Product apple = mock(com.intermarche.edge.domain.catalog.Product.class);
        apple.ean = "3001";
        family.products = new java.util.HashSet<>(java.util.Set.of(apple));
        ProductFamily parent = mock(ProductFamily.class);
        parent.code = "RAYON";
        PanacheQuery<ProductFamily> query = singlePage(0, 10, List.of(family));
        PanacheQuery<ProductFamily> parents = mock(PanacheQuery.class);
        when(parents.list()).thenReturn(List.of(parent));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("order by code")).thenReturn(query);
            mocked.when(() -> ProductFamily.find(
                    "select p from ProductFamily p join p.productFamilies c where c.code = ?1"
                            + " order by p.code", "F1")).thenReturn(parents);
            List<?> result = service.getPage("FAMILIES", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.FamilyDto dto = (RefPayloads.FamilyDto) result.get(0);
            assertEquals("F1", dto.code);
            assertEquals("Fruits", dto.description);
            assertEquals("BIO", dto.flags);
            assertEquals(List.of("RAYON"), dto.parentCodes);
            assertEquals(List.of("3001"), dto.productEans);
        }
    }

    /**
     * Covers the PRODUCTS switch arm and both arms of the {@code productType}
     * ternary in {@code toDto(Product)}: the first product carries a type
     * (rendered by name), the second has a null type (rendered null).
     */
    @Test
    void getPageMapsProductsWithAndWithoutType() {
        RefExportService service = new RefExportService();
        Product typed = mock(Product.class);
        typed.ean = "E1";
        typed.plu = "100";
        typed.name = "Pomme";
        typed.description = "Desc";
        typed.icon = "IC";
        typed.brand = "BR";
        typed.referenceWeight = new BigDecimal("1.000");
        typed.referenceVolume = new BigDecimal("2.000");
        typed.productType = ProductType.WEIGHT;
        typed.unitName = "kg";
        typed.active = true;
        typed.forbiddenToSale = false;
        typed.ageRestriction = 18;
        typed.checkoutLabel = "CL";
        typed.internalCode = "IC2";
        typed.variableWeight = true;
        typed.attributes = new java.util.HashMap<>(java.util.Map.of("BULKY", "true"));
        Product untyped = mock(Product.class);
        untyped.ean = "E2";
        untyped.name = "Poire";
        untyped.productType = null;
        untyped.active = false;
        untyped.forbiddenToSale = true;
        // Null attribute map covers the null arm of toDto's attributes ternary.
        untyped.attributes = null;
        PanacheQuery<Product> query = singlePage(0, 10, List.of(typed, untyped));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Product.find("order by ean")).thenReturn(query);
            List<?> result = service.getPage("PRODUCTS", 0, 10);
            assertEquals(2, result.size());
            RefPayloads.ProductDto first = (RefPayloads.ProductDto) result.get(0);
            assertEquals("E1", first.ean);
            assertEquals("100", first.plu);
            assertEquals("Pomme", first.name);
            assertEquals(new BigDecimal("1.000"), first.referenceWeight);
            assertEquals("WEIGHT", first.productType);
            assertTrue(first.active);
            assertEquals(18, first.ageRestriction);
            assertEquals("CL", first.checkoutLabel);
            assertEquals("IC2", first.internalCode);
            assertTrue(first.variableWeight);
            assertEquals("true", first.attributes.get("BULKY"));
            RefPayloads.ProductDto second = (RefPayloads.ProductDto) result.get(1);
            assertEquals("E2", second.ean);
            assertNull(second.productType);
            assertTrue(second.forbiddenToSale);
            assertFalse(second.variableWeight);
            assertTrue(second.attributes.isEmpty());
        }
    }

    /**
     * Covers the VAT_RATES switch arm and {@code toDto(VatRate)}: the three
     * values flow verbatim, a null label included.
     */
    @Test
    void getPageMapsVatRates() {
        RefExportService service = new RefExportService();
        VatRate normal = mock(VatRate.class);
        normal.number = 1;
        normal.rate = new BigDecimal("0.2000");
        normal.label = "Taux normal";
        VatRate bare = mock(VatRate.class);
        bare.number = 5;
        bare.rate = new BigDecimal("0.0000");
        bare.label = null;
        PanacheQuery<VatRate> query = singlePage(0, 10, List.of(normal, bare));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> VatRate.find("order by number")).thenReturn(query);
            List<?> result = service.getPage("VAT_RATES", 0, 10);
            assertEquals(2, result.size());
            RefPayloads.VatRateDto first = (RefPayloads.VatRateDto) result.get(0);
            assertEquals(1, first.number);
            assertEquals(new BigDecimal("0.2000"), first.rate);
            assertEquals("Taux normal", first.label);
            RefPayloads.VatRateDto second = (RefPayloads.VatRateDto) result.get(1);
            assertEquals(5, second.number);
            assertEquals(new BigDecimal("0.0000"), second.rate);
            assertNull(second.label);
        }
    }

    /**
     * VAT_RATES is applied BEFORE PRICES: a price names its regime by number,
     * so the table has to have landed when the price rows do.
     */
    @Test
    void vatRatesPrecedePricesInTheDomainOrder() {
        assertTrue(RefPayloads.DOMAINS.contains("VAT_RATES"));
        assertTrue(RefPayloads.DOMAINS.indexOf("VAT_RATES") < RefPayloads.DOMAINS.indexOf("PRICES"));
    }

    /**
     * Covers the PRICES switch arm and every conditional in {@code toDto(Price)}
     * and {@code iso}: the first row has a product (EAN taken) and a start but no
     * end (iso both arms), the second row has no product (EAN null) and an end
     * but no start (iso both arms again).
     */
    @Test
    void getPageMapsPricesWithAndWithoutProduct() {
        RefExportService service = new RefExportService();
        Product product = mock(Product.class);
        product.ean = "E1";
        Price linked = mock(Price.class);
        linked.product = product;
        linked.priceExcludingTax = new BigDecimal("1.00");
        linked.priceIncludingTax = new BigDecimal("1.20");
        when(linked.vatNumber()).thenReturn(1);
        linked.priority = 5;
        linked.startDateTime = LocalDateTime.of(2026, 1, 1, 10, 0, 30);
        linked.endDateTime = null;
        Price orphan = mock(Price.class);
        orphan.product = null;
        orphan.priceExcludingTax = new BigDecimal("2.00");
        orphan.priceIncludingTax = new BigDecimal("2.20");
        when(orphan.vatNumber()).thenReturn(null);
        orphan.priority = null;
        orphan.startDateTime = null;
        orphan.endDateTime = LocalDateTime.of(2026, 12, 31, 23, 59, 59);
        PanacheQuery<Price> query = singlePage(0, 10, List.of(linked, orphan));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Price.find("order by id")).thenReturn(query);
            List<?> result = service.getPage("PRICES", 0, 10);
            assertEquals(2, result.size());
            RefPayloads.PriceDto first = (RefPayloads.PriceDto) result.get(0);
            assertEquals("E1", first.productEan);
            assertEquals(1, first.vatNumber);
            assertEquals(5, first.priority);
            assertEquals("2026-01-01T10:00:30", first.startDateTime);
            assertNull(first.endDateTime);
            RefPayloads.PriceDto second = (RefPayloads.PriceDto) result.get(1);
            assertNull(second.productEan);
            assertNull(second.vatNumber);
            assertNull(second.priority);
            assertNull(second.startDateTime);
            assertEquals("2026-12-31T23:59:59", second.endDateTime);
        }
    }

    /**
     * Covers the EMPLOYEES switch arm and {@code toDto(Employee)}: the role is
     * rendered by name and the remaining fields flow verbatim.
     */
    @Test
    void getPageMapsEmployees() {
        RefExportService service = new RefExportService();
        Employee employee = mock(Employee.class);
        employee.loginName = "alice";
        employee.firstName = "Alice";
        employee.lastName = "Martin";
        employee.password = "hash";
        employee.email = "a@x.fr";
        employee.role = Employee.EmployeeRole.CASHIER;
        employee.badgeId = "B1";
        employee.theme = "dark";
        employee.active = true;
        PanacheQuery<Employee> query = singlePage(0, 10, List.of(employee));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.find("order by loginName")).thenReturn(query);
            List<?> result = service.getPage("EMPLOYEES", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.EmployeeDto dto = (RefPayloads.EmployeeDto) result.get(0);
            assertEquals("alice", dto.loginName);
            assertEquals("CASHIER", dto.role);
            assertEquals("dark", dto.theme);
            assertTrue(dto.active);
        }
    }

    /**
     * Covers the COUPON_TYPES switch arm and {@code toDto(CouponType)}: the
     * amount source is rendered by name and the remaining fields flow verbatim.
     */
    @Test
    void getPageMapsCouponTypes() {
        RefExportService service = new RefExportService();
        CouponType type = mock(CouponType.class);
        type.code = "C1";
        type.label = "Bon";
        type.matchPattern = "^9";
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "D";
        type.priority = 10;
        type.active = true;
        type.depositLine = false;
        PanacheQuery<CouponType> query = singlePage(0, 10, List.of(type));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.find("order by code")).thenReturn(query);
            List<?> result = service.getPage("COUPON_TYPES", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.CouponTypeDto dto = (RefPayloads.CouponTypeDto) result.get(0);
            assertEquals("C1", dto.code);
            assertEquals("ENCODED", dto.amountSource);
            assertEquals(10, dto.priority);
            assertTrue(dto.active);
            assertNull(dto.codeKind);
            assertFalse(dto.manualAmountOnAllNines);
            assertNull(dto.islandCodes);
            assertTrue(dto.fields.isEmpty());
            assertTrue(dto.controls.isEmpty());
        }
    }

    /**
     * Covers the administered half of {@code toDto(CouponType)}: the range
     * description travels with the type, and a null row or a row without a
     * role is left out of the snapshot.
     */
    @Test
    void getPageMapsTheAdministeredRangeDescription() {
        RefExportService service = new RefExportService();
        CouponType type = mock(CouponType.class);
        type.code = "C2";
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.prefix = "298";
        type.codeLength = 13;
        type.codeKind = CouponField.Kind.ALPHANUMERIC;
        CouponField price = new CouponField();
        price.role = CouponField.Role.PRICE;
        price.offsetPosition = 9;
        price.fieldLength = 4;
        price.kind = CouponField.Kind.NUMERIC;
        price.decimals = 2;
        price.dateFormat = null;
        price.currency = CouponField.PriceCurrency.EUR;
        CouponField bare = new CouponField();
        bare.role = CouponField.Role.TPV_NUMBER;
        bare.offsetPosition = 3;
        bare.fieldLength = 2;
        bare.kind = null;
        CouponField roleless = new CouponField();
        type.fields = new java.util.ArrayList<>(List.of(price, bare, roleless));
        type.fields.add(null);
        CouponControl expiry = new CouponControl();
        expiry.kind = CouponControl.Kind.EXPIRED;
        expiry.level = com.intermarche.edge.domain.barcode.AlertLevel.BLOCK;
        expiry.message = "BON PERIME";
        CouponControl kindless = new CouponControl();
        type.controls = new java.util.ArrayList<>(List.of(expiry, kindless));
        type.controls.add(null);
        PanacheQuery<CouponType> query = singlePage(0, 10, List.of(type));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.find("order by code")).thenReturn(query);
            RefPayloads.CouponTypeDto dto =
                    (RefPayloads.CouponTypeDto) service.getPage("COUPON_TYPES", 0, 10).get(0);
            assertEquals("298", dto.prefix);
            assertEquals(13, dto.codeLength);
            assertEquals("ALPHANUMERIC", dto.codeKind);
            assertEquals(2, dto.fields.size());
            assertEquals("PRICE", dto.fields.get(0).role);
            assertEquals(9, dto.fields.get(0).offsetPosition);
            assertEquals(4, dto.fields.get(0).fieldLength);
            assertEquals("NUMERIC", dto.fields.get(0).kind);
            assertEquals(2, dto.fields.get(0).decimals);
            assertNull(dto.fields.get(0).dateFormat);
            assertEquals("EUR", dto.fields.get(0).currency);
            assertEquals("TPV_NUMBER", dto.fields.get(1).role);
            assertNull(dto.fields.get(1).kind);
            assertNull(dto.fields.get(1).decimals);
            assertNull(dto.fields.get(1).currency);
            assertEquals(1, dto.controls.size());
            assertEquals("EXPIRED", dto.controls.get(0).kind);
            assertEquals("BLOCK", dto.controls.get(0).level);
            assertEquals("BON PERIME", dto.controls.get(0).message);
        }
    }

    /**
     * A range holding no field list is exported with no position at all
     * (second leg of the field-list guard).
     */
    @Test
    void getPageToleratesARangeWithoutFieldList() {
        RefExportService service = new RefExportService();
        CouponType type = mock(CouponType.class);
        type.code = "C3";
        type.amountSource = CouponType.AmountSource.MANUAL;
        type.fields = null;
        type.controls = null;
        PanacheQuery<CouponType> query = singlePage(0, 10, List.of(type));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.find("order by code")).thenReturn(query);
            RefPayloads.CouponTypeDto dto =
                    (RefPayloads.CouponTypeDto) service.getPage("COUPON_TYPES", 0, 10).get(0);
            assertTrue(dto.fields.isEmpty());
            assertTrue(dto.controls.isEmpty());
        }
    }

    /**
     * Covers the SETTINGS switch arm and {@code settingsPage}: the store node
     * publishes the RESOLVED effective values (from {@code administeredValues},
     * not the raw rows), ordered, with a full in-bounds page returning the
     * sublist ({@code from >= size} false arm) and a past-the-end page returning
     * empty ({@code from >= size} true arm).
     */
    @Test
    void getPageMapsSettingsFromResolvedValues() {
        RefExportService service = new RefExportService();
        LinkedHashMap<String, String> resolved = new LinkedHashMap<>();
        resolved.put("a.key", "1");
        resolved.put("b.key", "2");
        wireSettings(service, resolved);
        List<?> firstPage = service.getPage("SETTINGS", 0, 1);
        assertEquals(1, firstPage.size());
        RefPayloads.SettingDto dto = (RefPayloads.SettingDto) firstPage.get(0);
        assertEquals("a.key", dto.key);
        assertEquals("1", dto.value);
        List<?> pastEnd = service.getPage("SETTINGS", 5, 10);
        assertTrue(pastEnd.isEmpty());
    }

    /**
     * Covers the COUNTRIES switch arm and {@code toDto(Country)}: the country
     * fields flow verbatim into the payload.
     */
    @Test
    void getPageMapsCountries() {
        RefExportService service = new RefExportService();
        Country country = mock(Country.class);
        country.code = "FR";
        country.name = "France";
        country.defaultLanguage = "fr";
        PanacheQuery<Country> query = singlePage(0, 10, List.of(country));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Country.find("order by code")).thenReturn(query);
            List<?> result = service.getPage("COUNTRIES", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.CountryDto dto = (RefPayloads.CountryDto) result.get(0);
            assertEquals("FR", dto.code);
            assertEquals("France", dto.name);
            assertEquals("fr", dto.defaultLanguage);
        }
    }

    /**
     * Covers the ENSEIGNES switch arm and {@code toDto(Enseigne)}: the enseigne
     * fields flow verbatim into the payload.
     */
    @Test
    void getPageMapsEnseignes() {
        RefExportService service = new RefExportService();
        Enseigne enseigne = mock(Enseigne.class);
        enseigne.code = "ITM";
        enseigne.name = "Intermarché";
        enseigne.countryCode = "FR";
        enseigne.defaultLanguage = "fr";
        PanacheQuery<Enseigne> query = singlePage(0, 10, List.of(enseigne));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Enseigne.find("order by code")).thenReturn(query);
            List<?> result = service.getPage("ENSEIGNES", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.EnseigneDto dto = (RefPayloads.EnseigneDto) result.get(0);
            assertEquals("ITM", dto.code);
            assertEquals("Intermarché", dto.name);
            assertEquals("FR", dto.countryCode);
            assertEquals("fr", dto.defaultLanguage);
        }
    }

    /**
     * Covers the PDVS switch arm and {@code toDto(Pdv)}: the PDV fields flow
     * verbatim into the payload.
     */
    @Test
    void getPageMapsPdvs() {
        RefExportService service = new RefExportService();
        Pdv pdv = mock(Pdv.class);
        pdv.pdvNumber = "01234";
        pdv.name = "Lyon";
        pdv.enseigneCode = "ITM";
        pdv.adherentCode = "AD1";
        pdv.active = true;
        PanacheQuery<Pdv> query = singlePage(0, 10, List.of(pdv));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Pdv.find("order by pdvNumber")).thenReturn(query);
            List<?> result = service.getPage("PDVS", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.PdvDto dto = (RefPayloads.PdvDto) result.get(0);
            assertEquals("01234", dto.pdvNumber);
            assertEquals("Lyon", dto.name);
            assertEquals("ITM", dto.enseigneCode);
            assertEquals("AD1", dto.adherentCode);
            assertTrue(dto.active);
        }
    }

    /**
     * Covers the ECHELON_SETTINGS switch arm and both arms of the two ternaries
     * in {@code toDto(EchelonSetting)}: the first row has a level and an effect
     * date (rendered by name and ISO string), the second has neither (both
     * rendered null).
     */
    @Test
    void getPageMapsEchelonSettingsWithAndWithoutLevelAndDate() {
        RefExportService service = new RefExportService();
        EchelonSetting dated = mock(EchelonSetting.class);
        dated.level = EchelonLevel.ENSEIGNE;
        dated.echelonCode = "ITM";
        dated.settingKey = "discount.enabled";
        dated.settingValue = "false";
        dated.effectiveDate = LocalDate.of(2026, 3, 1);
        EchelonSetting bare = mock(EchelonSetting.class);
        bare.level = null;
        bare.echelonCode = "FR";
        bare.settingKey = "display.show-ean";
        bare.settingValue = "true";
        bare.effectiveDate = null;
        PanacheQuery<EchelonSetting> query =
                singlePage(0, 10, List.of(dated, bare));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> EchelonSetting.find("order by level, echelonCode, settingKey"))
                    .thenReturn(query);
            List<?> result = service.getPage("ECHELON_SETTINGS", 0, 10);
            assertEquals(2, result.size());
            RefPayloads.EchelonSettingDto first = (RefPayloads.EchelonSettingDto) result.get(0);
            assertEquals("ENSEIGNE", first.level);
            assertEquals("ITM", first.echelonCode);
            assertEquals("discount.enabled", first.settingKey);
            assertEquals("false", first.settingValue);
            assertEquals("2026-03-01", first.effectiveDate);
            RefPayloads.EchelonSettingDto second = (RefPayloads.EchelonSettingDto) result.get(1);
            assertNull(second.level);
            assertNull(second.effectiveDate);
        }
    }

    // --------------------------------------------------
    // getFingerprints — computation, caching, failure
    // --------------------------------------------------

    /**
     * Covers the {@code cachedAt == null} arm with empty domains: every finder
     * yields an empty first page so the do-while exits immediately and each
     * fingerprint is the pinned empty-stream SHA-256, in domain order.
     */
    @Test
    void getFingerprintsComputesEmptyDomains() {
        RefExportService service = new RefExportService();
        wireSettings(service, Map.of());
        PanacheQuery<ProductFamily> families = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.setting.TouchGroupSetting> touchGroups =
                pagedQuery(List.of());
        PanacheQuery<Product> products = pagedQuery(List.of());
        PanacheQuery<Price> prices = pagedQuery(List.of());
        PanacheQuery<Employee> employees = pagedQuery(List.of());
        PanacheQuery<CouponType> couponTypes = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.setting.PosSetting> settings = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.sync.EngineFeed> engineFeeds = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.payment.AccountCustomer> customers = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.payment.Currency> currencies = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.store.PostalCode> postalCodes = pagedQuery(List.of());
        PanacheQuery<VatRate> vatRateQuery = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.store.CheckoutPoint> checkoutPoints =
                pagedQuery(List.of());
        // BO-03-08-06/-07: the provider messages are a domain of their own, and
        // its finder carries its own ORDER BY — so it needs its own stub, where
        // the domains ordered by code all share one.
        PanacheQuery<com.intermarche.edge.domain.setting.ProviderMessage> providerMessages =
                pagedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("order by code")).thenReturn(families);
            mocked.when(() -> com.intermarche.edge.domain.setting.TouchGroupSetting
                    .find("order by familyCode")).thenReturn(touchGroups);
            mocked.when(() -> Product.find("order by ean")).thenReturn(products);
            mocked.when(() -> VatRate.find("order by number")).thenReturn(vatRateQuery);
            mocked.when(() -> Price.find("order by id")).thenReturn(prices);
            mocked.when(() -> Employee.find("order by loginName")).thenReturn(employees);
            mocked.when(() -> CouponType.find("order by code")).thenReturn(couponTypes);
            mocked.when(() -> com.intermarche.edge.domain.setting.PosSetting.find("order by settingKey")).thenReturn(settings);
            mocked.when(() -> com.intermarche.edge.domain.sync.EngineFeed.find("order by code")).thenReturn(engineFeeds);
            mocked.when(() -> com.intermarche.edge.domain.payment.AccountCustomer.find("order by accountNumber")).thenReturn(customers);
            mocked.when(() -> com.intermarche.edge.domain.payment.Currency.find("order by code")).thenReturn(currencies);
            mocked.when(() -> com.intermarche.edge.domain.store.PostalCode.find("order by code, place")).thenReturn(postalCodes);
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("order by terminalId")).thenReturn(checkoutPoints);
            mocked.when(() -> com.intermarche.edge.domain.setting.ProviderMessage
                    .find("order by providerCode, messageCode")).thenReturn(providerMessages);
            Map<String, String> fingerprints = service.getFingerprints();
            assertEquals(RefPayloads.DOMAINS, List.copyOf(fingerprints.keySet()));
            assertEquals(EMPTY_SHA256, fingerprints.get("FAMILIES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("PRODUCTS"));
            assertEquals(EMPTY_SHA256, fingerprints.get("PRICES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("EMPLOYEES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("COUPON_TYPES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("SETTINGS"));
            assertEquals(EMPTY_SHA256, fingerprints.get("ENGINE_FEEDS"));
            assertEquals(EMPTY_SHA256, fingerprints.get("CUSTOMERS"));
            assertEquals(EMPTY_SHA256, fingerprints.get("CURRENCIES"));
        }
    }

    /**
     * Covers the do-while continue arm of {@code computeFingerprint} and the
     * end-to-end fold: PRODUCTS returns two rows on page 0 then an empty page 1,
     * so the loop iterates before exiting and the domain fingerprint equals the
     * independent SHA-256 of the two canonical rows concatenated, while the
     * other (empty) domains hash to the empty stream. PRODUCTS is chosen because
     * its {@code "order by ean"} finder key is unique, whereas FAMILIES and
     * COUPON_TYPES share {@code "order by code"} and cannot be populated apart
     * under a single {@link PanacheEntityBase} static mock.
     */
    @Test
    void getFingerprintsFoldsRowsIntoFingerprint() throws Exception {
        RefExportService service = new RefExportService();
        wireSettings(service, Map.of());
        Product first = mock(Product.class);
        first.ean = "E1";
        first.plu = "100";
        first.name = "Pomme";
        first.description = "Desc";
        first.icon = "IC";
        first.brand = "BR";
        first.referenceWeight = new BigDecimal("1.000");
        first.referenceVolume = new BigDecimal("2.000");
        first.productType = ProductType.WEIGHT;
        first.unitName = "kg";
        first.active = true;
        first.forbiddenToSale = false;
        Product second = mock(Product.class);
        second.ean = "E2";
        second.name = "Poire";
        second.productType = null;
        second.active = false;
        second.forbiddenToSale = true;
        PanacheQuery<ProductFamily> families = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.setting.TouchGroupSetting> touchGroups =
                pagedQuery(List.of());
        PanacheQuery<Product> products = pagedQuery(List.of(first, second));
        PanacheQuery<Price> prices = pagedQuery(List.of());
        PanacheQuery<Employee> employees = pagedQuery(List.of());
        PanacheQuery<CouponType> couponTypes = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.setting.PosSetting> settings = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.sync.EngineFeed> engineFeeds = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.payment.AccountCustomer> customers = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.payment.Currency> currencies = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.store.PostalCode> postalCodes = pagedQuery(List.of());
        PanacheQuery<VatRate> vatRateQuery = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.store.CheckoutPoint> checkoutPoints =
                pagedQuery(List.of());
        // BO-03-08-06/-07: the provider messages are a domain of their own, and
        // its finder carries its own ORDER BY — so it needs its own stub, where
        // the domains ordered by code all share one.
        PanacheQuery<com.intermarche.edge.domain.setting.ProviderMessage> providerMessages =
                pagedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("order by code")).thenReturn(families);
            mocked.when(() -> com.intermarche.edge.domain.setting.TouchGroupSetting
                    .find("order by familyCode")).thenReturn(touchGroups);
            mocked.when(() -> Product.find("order by ean")).thenReturn(products);
            mocked.when(() -> VatRate.find("order by number")).thenReturn(vatRateQuery);
            mocked.when(() -> Price.find("order by id")).thenReturn(prices);
            mocked.when(() -> Employee.find("order by loginName")).thenReturn(employees);
            mocked.when(() -> CouponType.find("order by code")).thenReturn(couponTypes);
            mocked.when(() -> com.intermarche.edge.domain.setting.PosSetting.find("order by settingKey")).thenReturn(settings);
            mocked.when(() -> com.intermarche.edge.domain.sync.EngineFeed.find("order by code")).thenReturn(engineFeeds);
            mocked.when(() -> com.intermarche.edge.domain.payment.AccountCustomer.find("order by accountNumber")).thenReturn(customers);
            mocked.when(() -> com.intermarche.edge.domain.payment.Currency.find("order by code")).thenReturn(currencies);
            mocked.when(() -> com.intermarche.edge.domain.store.PostalCode.find("order by code, place")).thenReturn(postalCodes);
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("order by terminalId")).thenReturn(checkoutPoints);
            mocked.when(() -> com.intermarche.edge.domain.setting.ProviderMessage
                    .find("order by providerCode, messageCode")).thenReturn(providerMessages);
            Map<String, String> fingerprints = service.getFingerprints();
            // The canonical product row carries EIGHTEEN fields: the two last —
            // variableWeight and the declared attributes — joined the export
            // after this reference was written, and a fingerprint that ignores
            // a field is a field the store node never learns has changed.
            assertEquals(sha256hex("E1|100|Pomme|Desc|IC||BR|1.000|2.000|WEIGHT|kg|true|false||||false|"
                    + "E2||Poire|||||||||false|true||||false|"), fingerprints.get("PRODUCTS"));
            assertEquals(EMPTY_SHA256, fingerprints.get("FAMILIES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("PRICES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("EMPLOYEES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("COUPON_TYPES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("SETTINGS"));
            assertEquals(EMPTY_SHA256, fingerprints.get("ENGINE_FEEDS"));
            assertEquals(EMPTY_SHA256, fingerprints.get("CUSTOMERS"));
            assertEquals(EMPTY_SHA256, fingerprints.get("CURRENCIES"));
        }
    }

    /**
     * Covers the fresh-cache arm ({@code cachedAt != null} and
     * {@code now - cachedAt <= TTL}): every domain is seeded with a recent
     * sentinel, so {@code getFingerprints} returns the sentinels verbatim
     * without touching any finder.
     */
    @Test
    void getFingerprintsServesCacheWithinTtl() throws Exception {
        RefExportService service = new RefExportService();
        long now = System.currentTimeMillis();
        for (String domain : RefPayloads.DOMAINS) {
            seed(service, domain, "FRESH-" + domain, now);
        }
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            Map<String, String> fingerprints = service.getFingerprints();
            for (String domain : RefPayloads.DOMAINS) {
                assertEquals("FRESH-" + domain, fingerprints.get(domain));
            }
        }
    }

    /**
     * Covers the expired-cache arm ({@code cachedAt != null} and
     * {@code now - cachedAt > TTL}): every domain is seeded with an old sentinel
     * and empty finders, so {@code getFingerprints} discards the sentinel and
     * recomputes the empty-stream fingerprint.
     */
    @Test
    void getFingerprintsRecomputesAfterTtl() throws Exception {
        RefExportService service = new RefExportService();
        long expired = System.currentTimeMillis() - 120_000;
        for (String domain : RefPayloads.DOMAINS) {
            seed(service, domain, "STALE-" + domain, expired);
        }
        wireSettings(service, Map.of());
        PanacheQuery<ProductFamily> families = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.setting.TouchGroupSetting> touchGroups =
                pagedQuery(List.of());
        PanacheQuery<Product> products = pagedQuery(List.of());
        PanacheQuery<Price> prices = pagedQuery(List.of());
        PanacheQuery<Employee> employees = pagedQuery(List.of());
        PanacheQuery<CouponType> couponTypes = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.setting.PosSetting> settings = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.sync.EngineFeed> engineFeeds = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.payment.AccountCustomer> customers = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.payment.Currency> currencies = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.store.PostalCode> postalCodes = pagedQuery(List.of());
        PanacheQuery<VatRate> vatRateQuery = pagedQuery(List.of());
        PanacheQuery<com.intermarche.edge.domain.store.CheckoutPoint> checkoutPoints =
                pagedQuery(List.of());
        // BO-03-08-06/-07: the provider messages are a domain of their own, and
        // its finder carries its own ORDER BY — so it needs its own stub, where
        // the domains ordered by code all share one.
        PanacheQuery<com.intermarche.edge.domain.setting.ProviderMessage> providerMessages =
                pagedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("order by code")).thenReturn(families);
            mocked.when(() -> com.intermarche.edge.domain.setting.TouchGroupSetting
                    .find("order by familyCode")).thenReturn(touchGroups);
            mocked.when(() -> Product.find("order by ean")).thenReturn(products);
            mocked.when(() -> VatRate.find("order by number")).thenReturn(vatRateQuery);
            mocked.when(() -> Price.find("order by id")).thenReturn(prices);
            mocked.when(() -> Employee.find("order by loginName")).thenReturn(employees);
            mocked.when(() -> CouponType.find("order by code")).thenReturn(couponTypes);
            mocked.when(() -> com.intermarche.edge.domain.setting.PosSetting.find("order by settingKey")).thenReturn(settings);
            mocked.when(() -> com.intermarche.edge.domain.sync.EngineFeed.find("order by code")).thenReturn(engineFeeds);
            mocked.when(() -> com.intermarche.edge.domain.payment.AccountCustomer.find("order by accountNumber")).thenReturn(customers);
            mocked.when(() -> com.intermarche.edge.domain.payment.Currency.find("order by code")).thenReturn(currencies);
            mocked.when(() -> com.intermarche.edge.domain.store.PostalCode.find("order by code, place")).thenReturn(postalCodes);
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("order by terminalId")).thenReturn(checkoutPoints);
            mocked.when(() -> com.intermarche.edge.domain.setting.ProviderMessage
                    .find("order by providerCode, messageCode")).thenReturn(providerMessages);
            Map<String, String> fingerprints = service.getFingerprints();
            for (String domain : RefPayloads.DOMAINS) {
                assertEquals(EMPTY_SHA256, fingerprints.get(domain));
            }
        }
    }

    /**
     * Covers the catch block of {@code computeFingerprint}: a domain's finder
     * throws, so the failure is wrapped in an {@link IllegalStateException}
     * naming THAT domain and carrying the original cause.
     * <p>
     * The domains before the failing one are stubbed to an empty page so the
     * computation reaches it: the assertion is about the wrapping, not about
     * which domain happens to come first in {@code DOMAINS} — that order
     * changed twice already (PRODUCTS ahead of FAMILIES so a family payload
     * can resolve its articles, then ENGINE_FEEDS ahead of both so the
     * register has the raw file when its domain comes up).
     */
    @Test
    void getFingerprintsWrapsComputationFailure() {
        RefExportService service = new RefExportService();
        RuntimeException boom = new RuntimeException("boom");
        PanacheQuery<com.intermarche.edge.domain.sync.EngineFeed> feeds = pagedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            // C'est PRODUCTS qui lève ici, et non FAMILIES : le mock statique
            // porte sur PanacheEntityBase, où le type de l'entité est effacé,
            // donc « order by code » désigne la MÊME invocation pour les
            // familles et pour les flux moteur — on ne peut pas faire lever
            // l'une sans l'autre. « order by ean » n'appartient qu'aux
            // produits. L'assertion porte sur le nommage du domaine fautif,
            // pas sur lequel des domaines échoue.
            mocked.when(() -> com.intermarche.edge.domain.sync.EngineFeed.find("order by code"))
                    .thenReturn(feeds);
            mocked.when(() -> Product.find("order by ean")).thenThrow(boom);
            IllegalStateException ex = assertThrows(IllegalStateException.class, service::getFingerprints);
            assertEquals("Empreinte incalculable pour PRODUCTS", ex.getMessage());
            assertSame(boom, ex.getCause());
        }
    }

    // --------------------------------------------------
    // canonical — default arm
    // --------------------------------------------------

    /**
     * Covers every arm of {@code canonical} and both arms of {@code n}: each of
     * the five payload types renders through its own {@code instanceof} true arm
     * (with a null field to exercise the empty {@code n} arm alongside non-null
     * fields), and a plain object falls through every {@code instanceof} false
     * arm to the default {@code String.valueOf}. Invoked reflectively because
     * the production flow only ever feeds already-mapped DTOs to
     * {@code canonical}, so the default arm is otherwise unreachable.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void canonicalRendersEveryPayloadTypeAndUnknownRow() throws Exception {
        RefExportService service = new RefExportService();
        Method canonical = RefExportService.class.getDeclaredMethod("canonical", Object.class);
        canonical.setAccessible(true);
        RefPayloads.FamilyDto family = new RefPayloads.FamilyDto();
        family.code = "F1";
        family.description = "Fruits";
        family.flags = null;
        assertEquals("F1|Fruits|||", canonical.invoke(service, family));
        RefPayloads.FamilyDto wiredFamily = new RefPayloads.FamilyDto();
        wiredFamily.code = "F2";
        wiredFamily.description = "Frais";
        wiredFamily.flags = "BIO";
        wiredFamily.parentCodes = List.of("RAYON", "PROMO");
        wiredFamily.productEans = List.of("3001", "3002");
        // The edges are part of the canonical string: a re-parenting or an
        // article joining a group must change the domain fingerprint, or the
        // registers would never pull the new graph.
        assertEquals("F2|Frais|BIO|RAYON,PROMO|3001,3002",
                canonical.invoke(service, wiredFamily));
        // The touch configuration is its own domain now, with its own canonical
        // string: a group re-pinned in the back office must change TOUCH_GROUPS
        // and leave FAMILIES alone.
        RefPayloads.TouchGroupDto plainTouch = new RefPayloads.TouchGroupDto();
        plainTouch.familyCode = "F1";
        assertEquals("F1|false||0|0", canonical.invoke(service, plainTouch));
        RefPayloads.TouchGroupDto pinnedTouch = new RefPayloads.TouchGroupDto();
        pinnedTouch.familyCode = "F2";
        pinnedTouch.pinned = true;
        pinnedTouch.buttonSize = "LARGE";
        pinnedTouch.displayOrder = 3;
        pinnedTouch.salesVolume = 99L;
        assertEquals("F2|true|LARGE|3|99", canonical.invoke(service, pinnedTouch));
        RefPayloads.ProductDto product = new RefPayloads.ProductDto();
        product.ean = "E1";
        product.plu = "100";
        product.name = "Pomme";
        product.description = "Desc";
        product.icon = "IC";
        product.brand = "BR";
        product.referenceWeight = new BigDecimal("1.000");
        product.referenceVolume = new BigDecimal("2.000");
        product.productType = "WEIGHT";
        product.unitName = null;
        product.active = true;
        product.forbiddenToSale = false;
        product.ageRestriction = 18;
        product.checkoutLabel = "CL";
        product.internalCode = "INT9";
        product.attributes.put("VAT_EXEMPT", "true");
        product.attributes.put("BULKY", "false");
        // BO-03-08-09: the article's own message rides the canonical string, so
        // a message posed at the back office reaches the register at the pull.
        product.infoMessage = "LIVRAISON 48H";
        assertEquals("E1|100|Pomme|Desc|IC||BR|1.000|2.000|WEIGHT||true|false|18|CL|INT9|false|LIVRAISON 48H|BULKY=false,VAT_EXEMPT=true",
                canonical.invoke(service, product));
        // A picture renders in its own field, between the icon and the brand.
        product.imageData = "IMG";
        assertEquals("E1|100|Pomme|Desc|IC|IMG|BR|1.000|2.000|WEIGHT||true|false|18|CL|INT9|false|LIVRAISON 48H|BULKY=false,VAT_EXEMPT=true",
                canonical.invoke(service, product));
        product.imageData = null;
        // A null attribute map renders as the empty trailing field (null arm of
        // the canonical attributes helper).
        product.attributes = null;
        product.infoMessage = null;
        assertEquals("E1|100|Pomme|Desc|IC||BR|1.000|2.000|WEIGHT||true|false|18|CL|INT9|false||",
                canonical.invoke(service, product));
        RefPayloads.PriceDto price = new RefPayloads.PriceDto();
        price.productEan = "E1";
        price.priceExcludingTax = new BigDecimal("1.00");
        price.priceIncludingTax = new BigDecimal("1.20");
        price.vatNumber = 1;
        price.priority = 5;
        price.startDateTime = "2026-01-01T10:00:30";
        price.endDateTime = null;
        assertEquals("E1|1.00|1.20|1|5|2026-01-01T10:00:30|",
                canonical.invoke(service, price));
        RefPayloads.VatRateDto vatRate = new RefPayloads.VatRateDto();
        vatRate.number = 1;
        vatRate.rate = new BigDecimal("0.2000");
        vatRate.label = "Taux normal";
        assertEquals("1|0.2000|Taux normal", canonical.invoke(service, vatRate));
        vatRate.label = null;
        assertEquals("1|0.2000|", canonical.invoke(service, vatRate));
        RefPayloads.EmployeeDto employee = new RefPayloads.EmployeeDto();
        employee.loginName = "alice";
        employee.firstName = "Alice";
        employee.lastName = "Martin";
        employee.password = "hash";
        employee.email = "a@x.fr";
        employee.role = "CASHIER";
        employee.badgeId = null;
        employee.theme = "dark";
        employee.active = true;
        assertEquals("alice|Alice|Martin|hash|a@x.fr|CASHIER||dark|true",
                canonical.invoke(service, employee));
        RefPayloads.CouponTypeDto type = new RefPayloads.CouponTypeDto();
        type.code = "C1";
        type.label = "Bon";
        type.matchPattern = "^9";
        type.amountSource = "ENCODED";
        type.amountPattern = null;
        type.priority = 10;
        type.active = true;
        type.depositLine = false;
        assertEquals("C1|Bon|^9|ENCODED||10|true|false||||false|||", canonical.invoke(service, type));
        RefPayloads.CouponTypeDto administered = new RefPayloads.CouponTypeDto();
        administered.code = "C2";
        administered.label = "Bon administré";
        administered.matchPattern = "^298\\d{10}$";
        administered.amountSource = "ENCODED";
        administered.amountPattern = "^298\\d{6}(\\d{4})$";
        administered.priority = 5;
        administered.active = true;
        administered.depositLine = true;
        administered.prefix = "298";
        administered.codeLength = 13;
        administered.codeKind = "NUMERIC";
        RefPayloads.CouponFieldDto tpv = new RefPayloads.CouponFieldDto();
        tpv.role = "TPV_NUMBER";
        tpv.offsetPosition = 3;
        tpv.fieldLength = 2;
        tpv.kind = "NUMERIC";
        RefPayloads.CouponFieldDto amount = new RefPayloads.CouponFieldDto();
        amount.role = "PRICE";
        amount.offsetPosition = 9;
        amount.fieldLength = 4;
        amount.kind = "NUMERIC";
        amount.decimals = 2;
        amount.currency = "EUR";
        administered.fields = new java.util.ArrayList<>(java.util.List.of(tpv, amount));
        RefPayloads.CouponControlDto expiry = new RefPayloads.CouponControlDto();
        expiry.kind = "EXPIRED";
        expiry.level = "BLOCK";
        expiry.message = "BON PERIME";
        RefPayloads.CouponControlDto duplicate = new RefPayloads.CouponControlDto();
        duplicate.kind = "DUPLICATE";
        duplicate.level = "INFO";
        administered.controls = new java.util.ArrayList<>(java.util.List.of(expiry, duplicate));
        assertEquals("C2|Bon administré|^298\\d{10}$|ENCODED|^298\\d{6}(\\d{4})$|5|true|true"
                        + "|298|13|NUMERIC|false||PRICE:9:4:NUMERIC:2::EUR,TPV_NUMBER:3:2:NUMERIC:::"
                        + "|DUPLICATE:INFO:,EXPIRED:BLOCK:BON PERIME",
                canonical.invoke(service, administered));
        RefPayloads.SettingDto setting = new RefPayloads.SettingDto();
        setting.key = "display.show-ean";
        setting.value = null;
        assertEquals("display.show-ean|", canonical.invoke(service, setting));
        RefPayloads.EngineFeedDto feed = new RefPayloads.EngineFeedDto();
        feed.code = "VAL";
        feed.version = null;
        feed.content = "ignored-in-canonical";
        assertEquals("VAL|", canonical.invoke(service, feed));
        RefPayloads.CountryDto country = new RefPayloads.CountryDto();
        country.code = "FR";
        country.name = "France";
        country.defaultLanguage = null;
        assertEquals("FR|France|", canonical.invoke(service, country));
        RefPayloads.EnseigneDto enseigne = new RefPayloads.EnseigneDto();
        enseigne.code = "ITM";
        enseigne.name = "Intermarché";
        enseigne.countryCode = "FR";
        enseigne.defaultLanguage = null;
        assertEquals("ITM|Intermarché|FR|", canonical.invoke(service, enseigne));
        RefPayloads.PdvDto pdv = new RefPayloads.PdvDto();
        pdv.pdvNumber = "01234";
        pdv.name = "Lyon";
        pdv.enseigneCode = "ITM";
        pdv.adherentCode = null;
        pdv.active = true;
        assertEquals("01234|Lyon|ITM||true", canonical.invoke(service, pdv));
        RefPayloads.EchelonSettingDto echelon = new RefPayloads.EchelonSettingDto();
        echelon.level = "ENSEIGNE";
        echelon.echelonCode = "ITM";
        echelon.settingKey = "discount.enabled";
        echelon.settingValue = "false";
        echelon.effectiveDate = null;
        assertEquals("ENSEIGNE|ITM|discount.enabled|false|", canonical.invoke(service, echelon));
        assertEquals("RAW", canonical.invoke(service, "RAW"));
    }

    /**
     * Covers the CURRENCIES switch arm and both arms of the {@code euroPerUnit}
     * ternary in {@code toDto(Currency)}: the first currency carries a rate
     * (rendered as its plain string), the second has a null rate (rendered null).
     */
    @Test
    void getPageMapsCurrenciesWithAndWithoutRate() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.payment.Currency rated = mock(com.intermarche.edge.domain.payment.Currency.class);
        rated.code = "CHF";
        rated.label = "Franc Suisse";
        rated.symbol = "CHF";
        rated.euroPerUnit = new BigDecimal("1.050000");
        rated.active = true;
        rated.displayOrder = 1;
        rated.changeTenderCode = "ESP";
        com.intermarche.edge.domain.payment.Currency rateless = mock(com.intermarche.edge.domain.payment.Currency.class);
        rateless.code = "USD";
        rateless.label = "Dollar";
        rateless.symbol = null;
        rateless.euroPerUnit = null;
        rateless.active = false;
        rateless.displayOrder = 2;
        PanacheQuery<com.intermarche.edge.domain.payment.Currency> query =
                singlePage(0, 10, List.of(rated, rateless));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.payment.Currency.find("order by code"))
                    .thenReturn(query);
            List<?> result = service.getPage("CURRENCIES", 0, 10);
            assertEquals(2, result.size());
            RefPayloads.CurrencyDto first = (RefPayloads.CurrencyDto) result.get(0);
            assertEquals("CHF", first.code);
            assertEquals("Franc Suisse", first.label);
            assertEquals("CHF", first.symbol);
            assertEquals("1.050000", first.euroPerUnit);
            assertTrue(first.active);
            assertEquals(1, first.displayOrder);
            // BO-03-02-39: the change tender descends with the currency.
            assertEquals("ESP", first.changeTenderCode);
            RefPayloads.CurrencyDto second = (RefPayloads.CurrencyDto) result.get(1);
            assertEquals("USD", second.code);
            assertNull(second.euroPerUnit);
            assertFalse(second.active);
            assertEquals(2, second.displayOrder);
            assertNull(second.changeTenderCode);
        }
    }

    /**
     * Covers the CUSTOMERS switch arm and both arms of every conditional in
     * {@code toDto(AccountCustomer)}: the first customer has an address and both
     * credit figures (the {@code address == null} guard false for street, postal
     * code and city, and the {@code creditLimit}/{@code creditBalance} ternaries
     * take their {@code toPlainString} arm), the second has a null address and
     * null credit figures (the same five conditionals take their null arm).
     */
    @Test
    void getPageMapsCustomersWithAndWithoutAddressAndCredit() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.payment.AccountCustomer full =
                mock(com.intermarche.edge.domain.payment.AccountCustomer.class);
        full.accountNumber = "A1";
        full.companyName = "Acme";
        full.lastName = "Martin";
        full.firstName = "Alice";
        com.intermarche.edge.domain.store.Address address = new com.intermarche.edge.domain.store.Address();
        address.streetLine1 = "10 rue X";
        address.postalCode = "75008";
        address.city = "Paris";
        full.address = address;
        full.siret = "SIR";
        full.vatNumber = "VAT";
        full.phone = "0102";
        full.email = "a@x.fr";
        full.creditLimit = new BigDecimal("1000.00");
        full.creditBalance = new BigDecimal("250.00");
        com.intermarche.edge.domain.payment.AccountCustomer bare =
                mock(com.intermarche.edge.domain.payment.AccountCustomer.class);
        bare.accountNumber = "A2";
        bare.companyName = "Beta";
        bare.lastName = null;
        bare.firstName = null;
        bare.address = null;
        bare.siret = null;
        bare.vatNumber = null;
        bare.phone = null;
        bare.email = null;
        bare.creditLimit = null;
        bare.creditBalance = null;
        PanacheQuery<com.intermarche.edge.domain.payment.AccountCustomer> query =
                singlePage(0, 10, List.of(full, bare));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.payment.AccountCustomer
                    .find("order by accountNumber")).thenReturn(query);
            List<?> result = service.getPage("CUSTOMERS", 0, 10);
            assertEquals(2, result.size());
            RefPayloads.CustomerDto first = (RefPayloads.CustomerDto) result.get(0);
            assertEquals("A1", first.accountNumber);
            assertEquals("Acme", first.companyName);
            assertEquals("10 rue X", first.street);
            assertEquals("75008", first.postalCode);
            assertEquals("Paris", first.city);
            assertEquals("SIR", first.siret);
            assertEquals("1000.00", first.creditLimit);
            assertEquals("250.00", first.creditBalance);
            RefPayloads.CustomerDto second = (RefPayloads.CustomerDto) result.get(1);
            assertEquals("A2", second.accountNumber);
            assertNull(second.street);
            assertNull(second.postalCode);
            assertNull(second.city);
            assertNull(second.creditLimit);
            assertNull(second.creditBalance);
        }
    }

    /**
     * Covers the ENGINE_FEEDS switch arm and {@code toDto(EngineFeed)}: the feed
     * code, version and verbatim content flow into the payload unchanged (the
     * method is branchless, shipping the parcel unopened).
     */
    @Test
    void getPageMapsEngineFeeds() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.sync.EngineFeed feed =
                mock(com.intermarche.edge.domain.sync.EngineFeed.class);
        feed.code = "VAL";
        feed.version = "abc123";
        feed.content = "verbatim-body";
        PanacheQuery<com.intermarche.edge.domain.sync.EngineFeed> query =
                singlePage(0, 10, List.of(feed));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.sync.EngineFeed.find("order by code"))
                    .thenReturn(query);
            List<?> result = service.getPage("ENGINE_FEEDS", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.EngineFeedDto dto = (RefPayloads.EngineFeedDto) result.get(0);
            assertEquals("VAL", dto.code);
            assertEquals("abc123", dto.version);
            assertEquals("verbatim-body", dto.content);
        }
    }

    /**
     * Covers the two remaining {@code instanceof} true arms of {@code canonical}:
     * a {@link RefPayloads.CurrencyDto} and a {@link RefPayloads.CustomerDto} each
     * render through their own arm, with a null field apiece to keep exercising
     * the empty {@code n} arm alongside the non-null fields. Invoked reflectively
     * because the production flow only ever feeds already-mapped DTOs.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void canonicalRendersCurrencyAndCustomer() throws Exception {
        RefExportService service = new RefExportService();
        Method canonical = RefExportService.class.getDeclaredMethod("canonical", Object.class);
        canonical.setAccessible(true);
        RefPayloads.CurrencyDto currency = new RefPayloads.CurrencyDto();
        currency.code = "CHF";
        currency.label = "Franc Suisse";
        currency.symbol = null;
        currency.euroPerUnit = "1.050000";
        currency.active = true;
        currency.displayOrder = 1;
        currency.changeTenderCode = "ESP";
        assertEquals("CHF|Franc Suisse||1.050000|true|1|ESP",
                canonical.invoke(service, currency));
        RefPayloads.CustomerDto customer = new RefPayloads.CustomerDto();
        customer.accountNumber = "A1";
        customer.companyName = "Acme";
        customer.lastName = "Martin";
        customer.firstName = "Alice";
        customer.street = "10 rue X";
        customer.postalCode = "75008";
        customer.city = "Paris";
        customer.siret = "SIR";
        customer.vatNumber = null;
        customer.phone = "0102";
        customer.email = "a@x.fr";
        customer.creditLimit = "1000.00";
        customer.creditBalance = null;
        // Every administered field of BO-02-04 rides the canonical string, or a
        // register whose customer was blocked upstream would never be told.
        customer.origin = "INTEGRATED";
        customer.civility = "Mme";
        customer.taxId = "NIF-9";
        customer.blocked = true;
        customer.dueDate = "2026-10-31";
        customer.discountPercent = "2.50";
        RefPayloads.FreeFieldDto second = new RefPayloads.FreeFieldDto();
        second.slot = 2;
        second.label = "Tournée";
        second.value = "B";
        RefPayloads.FreeFieldDto first = new RefPayloads.FreeFieldDto();
        first.slot = 1;
        first.label = "Zone";
        first.value = null;
        customer.freeFields.add(second);
        customer.freeFields.add(first);
        assertEquals("A1|Acme|INTEGRATED|Mme|Martin|Alice|10 rue X|75008|Paris|SIR||NIF-9||0102|"
                        + "a@x.fr|true|2026-10-31|2.50|1000.00||1=Zone=;2=Tournée=B",
                canonical.invoke(service, customer));
    }

    /**
     * A customer carrying no free field renders an empty last segment rather
     * than a null: the two arms of the guard, and the fingerprint must not move
     * from one export to the next.
     */
    @Test
    void canonicalRendersACustomerWithoutFreeFields() throws Exception {
        RefExportService service = new RefExportService();
        Method canonical = RefExportService.class.getDeclaredMethod("canonical", Object.class);
        canonical.setAccessible(true);
        RefPayloads.CustomerDto customer = new RefPayloads.CustomerDto();
        customer.accountNumber = "A2";
        customer.companyName = "Sans champs";
        assertEquals("A2|Sans champs||||||||||||||false|||||",
                canonical.invoke(service, customer));
        customer.freeFields = null;
        assertEquals("A2|Sans champs||||||||||||||false|||||",
                canonical.invoke(service, customer));
    }

    // --- Plages ARTICLE (BO-03-06-02/03/04/05/10) ---

    /**
     * The all-nines rule travels with its range (BO-03-06-12): a register that
     * never received the flag would settle a 9999 voucher for 99,99 €.
     */
    @Test
    void getPageCarriesTheAllNinesRule() {
        RefExportService service = new RefExportService();
        CouponType type = mock(CouponType.class);
        type.code = "C1";
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.manualAmountOnAllNines = true;
        // BO-03-06-07: the islands accepting the range travel with it — a
        // register receiving the range without them would accept it everywhere.
        type.islandCodes = "AVANT;COMPTOIR";
        PanacheQuery<CouponType> query = singlePage(0, 10, List.of(type));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.find("order by code")).thenReturn(query);
            RefPayloads.CouponTypeDto dto =
                    (RefPayloads.CouponTypeDto) service.getPage("COUPON_TYPES", 0, 10).get(0);
            assertTrue(dto.manualAmountOnAllNines);
            assertEquals("AVANT;COMPTOIR", dto.islandCodes);
        }
    }

    /**
     * Covers the ARTICLE_RANGES switch arm and {@code toDto(ArticleBarcodeRange)}:
     * every administered position, the three enum names and the generated
     * pattern flow into the snapshot row, so a register reads its scale plan
     * from the referential.
     */
    @Test
    void getPageMapsArticleBarcodeRanges() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.barcode.ArticleBarcodeRange range =
                mock(com.intermarche.edge.domain.barcode.ArticleBarcodeRange.class);
        range.code = "BALANCE_PRIX";
        range.label = "Étiquette prix";
        range.active = true;
        range.priority = 10;
        range.prefix = "21";
        range.codeLength = 13;
        range.codeKind = com.intermarche.edge.domain.barcode.CouponField.Kind.ALPHANUMERIC;
        // Deliberately NOT the historical 2/5/7/5 plan: a fixture matching the
        // old literals could not tell a real copy from a hard-coded one.
        range.articlePosition = 4;
        range.articleLength = 6;
        range.valueSource = com.intermarche.edge.domain.barcode.ArticleBarcodeRange
                .ValueSource.WEIGHT;
        range.valuePosition = 7;
        range.valueLength = 5;
        range.valueDecimals = 3;
        range.currency = com.intermarche.edge.domain.barcode.CouponField.PriceCurrency.FRF;
        range.checkDigit = true;
        range.purpose = com.intermarche.edge.domain.barcode.ArticleBarcodeRange
                .Purpose.REFUND;
        range.matchPattern = "^21[A-Za-z0-9]{11}$";
        PanacheQuery<com.intermarche.edge.domain.barcode.ArticleBarcodeRange> query =
                singlePage(0, 10, List.of(range));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.barcode.ArticleBarcodeRange
                    .find("order by code")).thenReturn(query);
            List<?> result = service.getPage("ARTICLE_RANGES", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.ArticleBarcodeRangeDto dto =
                    (RefPayloads.ArticleBarcodeRangeDto) result.get(0);
            assertEquals("BALANCE_PRIX", dto.code);
            assertEquals("Étiquette prix", dto.label);
            assertTrue(dto.active);
            assertEquals(10, dto.priority);
            assertEquals("21", dto.prefix);
            assertEquals(13, dto.codeLength);
            assertEquals("ALPHANUMERIC", dto.codeKind);
            assertEquals(4, dto.articlePosition);
            assertEquals(6, dto.articleLength);
            assertEquals("WEIGHT", dto.valueSource);
            assertEquals(7, dto.valuePosition);
            assertEquals(5, dto.valueLength);
            assertEquals(3, dto.valueDecimals);
            assertEquals("FRF", dto.currency);
            assertTrue(dto.checkDigit);
            // BO-03-06-56/-60/-61: the destination descends with the range.
            assertEquals("REFUND", dto.purpose);
            assertEquals("^21[A-Za-z0-9]{11}$", dto.matchPattern);
        }
    }

    /**
     * A range naming neither a character kind, nor a nature of value, nor a
     * currency renders those three as null (the three null arms of the mapper).
     */
    @Test
    void getPageMapsAnArticleRangeWithoutItsThreeEnums() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.barcode.ArticleBarcodeRange range =
                mock(com.intermarche.edge.domain.barcode.ArticleBarcodeRange.class);
        range.code = "R";
        range.codeKind = null;
        range.valueSource = null;
        range.currency = null;
        range.purpose = null;
        PanacheQuery<com.intermarche.edge.domain.barcode.ArticleBarcodeRange> query =
                singlePage(0, 10, List.of(range));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.barcode.ArticleBarcodeRange
                    .find("order by code")).thenReturn(query);
            RefPayloads.ArticleBarcodeRangeDto dto = (RefPayloads.ArticleBarcodeRangeDto)
                    service.getPage("ARTICLE_RANGES", 0, 10).get(0);
            assertNull(dto.codeKind);
            assertNull(dto.valueSource);
            assertNull(dto.currency);
            assertNull(dto.purpose);
        }
    }

    /**
     * An article range renders into the canonical string field by field, so a
     * position moved upstream changes the domain fingerprint and the registers
     * pull the new plan. A range missing its three enum names renders them as
     * the empty field (the null arms).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void canonicalRendersAnArticleRange() throws Exception {
        RefExportService service = new RefExportService();
        Method canonical = RefExportService.class.getDeclaredMethod("canonical", Object.class);
        canonical.setAccessible(true);
        RefPayloads.ArticleBarcodeRangeDto dto = new RefPayloads.ArticleBarcodeRangeDto();
        dto.code = "BALANCE_PRIX";
        dto.label = "Étiquette prix";
        dto.active = true;
        dto.priority = 10;
        dto.prefix = "21";
        dto.codeLength = 13;
        dto.codeKind = "NUMERIC";
        dto.articlePosition = 2;
        dto.articleLength = 5;
        dto.valueSource = "PRICE";
        dto.valuePosition = 7;
        dto.valueLength = 5;
        dto.valueDecimals = 2;
        dto.currency = "EUR";
        dto.checkDigit = true;
        dto.purpose = "ARTICLE";
        dto.matchPattern = "^21\\d{11}$";
        assertEquals("BALANCE_PRIX|Étiquette prix|true|10|21|13|NUMERIC|2|5|PRICE|7|5|2|EUR|true|^21\\d{11}$|ARTICLE",
                canonical.invoke(service, dto));
        // Moving the value segment by one position changes the canonical string:
        // that is what makes the tills pull a corrected plan.
        dto.valuePosition = 8;
        assertEquals("BALANCE_PRIX|Étiquette prix|true|10|21|13|NUMERIC|2|5|PRICE|8|5|2|EUR|true|^21\\d{11}$|ARTICLE",
                canonical.invoke(service, dto));
        // Changing only the DESTINATION changes the fingerprint too: a plan read
        // the same way but acted on differently is not the same plan.
        dto.purpose = "REFUND";
        assertEquals("BALANCE_PRIX|Étiquette prix|true|10|21|13|NUMERIC|2|5|PRICE|8|5|2|EUR|true|^21\\d{11}$|REFUND",
                canonical.invoke(service, dto));
        RefPayloads.ArticleBarcodeRangeDto bare = new RefPayloads.ArticleBarcodeRangeDto();
        bare.code = "R";
        assertEquals("R||false|0||0||0|0||0|0|0||false||", canonical.invoke(service, bare));
    }

    /**
     * The ARTICLE_RANGES domain is part of the pulled snapshot, right after the
     * voucher ranges it shares its vocabulary with.
     */
    @Test
    void theArticleRangesDomainIsPartOfTheSnapshot() {
        assertTrue(RefPayloads.DOMAINS.contains("ARTICLE_RANGES"));
        assertEquals(RefPayloads.DOMAINS.indexOf("COUPON_TYPES") + 1,
                RefPayloads.DOMAINS.indexOf("ARTICLE_RANGES"));
    }

    // --- Îlots de caisse (BO-03-06-07, BO-03-06-50) ---

    /**
     * Covers the ISLANDS switch arm and {@code toDto(CheckoutIsland)}: a
     * register cannot know which island it stands on unless the island travels.
     */
    @Test
    void getPageMapsCheckoutIslands() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.store.CheckoutIsland island =
                mock(com.intermarche.edge.domain.store.CheckoutIsland.class);
        island.code = "AVANT";
        island.label = "Ligne avant";
        island.active = true;
        // terminalIds is no longer a column: the export DERIVES it from the
        // island's checkout points, so the wire stays byte-compatible.
        when(island.terminals()).thenReturn(List.of("POS01", "POS02"));
        PanacheQuery<com.intermarche.edge.domain.store.CheckoutIsland> query =
                singlePage(0, 10, List.of(island));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutIsland
                    .find("order by code")).thenReturn(query);
            RefPayloads.CheckoutIslandDto dto =
                    (RefPayloads.CheckoutIslandDto) service.getPage("ISLANDS", 0, 10).get(0);
            assertEquals("AVANT", dto.code);
            assertEquals("Ligne avant", dto.label);
            assertTrue(dto.active);
            assertEquals("POS01;POS02", dto.terminalIds);
        }
    }

    /**
     * An island renders into the canonical string field by field, so attaching
     * a register upstream changes the domain fingerprint.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void canonicalRendersACheckoutIsland() throws Exception {
        RefExportService service = new RefExportService();
        Method canonical = RefExportService.class.getDeclaredMethod("canonical", Object.class);
        canonical.setAccessible(true);
        RefPayloads.CheckoutIslandDto dto = new RefPayloads.CheckoutIslandDto();
        dto.code = "AVANT";
        dto.label = "Ligne avant";
        dto.active = true;
        dto.terminalIds = "POS01;POS02";
        assertEquals("AVANT|Ligne avant|true|POS01;POS02", canonical.invoke(service, dto));
        dto.terminalIds = "POS01;POS02;POS03";
        assertEquals("AVANT|Ligne avant|true|POS01;POS02;POS03", canonical.invoke(service, dto));
        RefPayloads.CheckoutIslandDto bare = new RefPayloads.CheckoutIslandDto();
        bare.code = "VIDE";
        assertEquals("VIDE||false|", canonical.invoke(service, bare));
    }

    /**
     * The ISLANDS domain is part of the pulled snapshot, right after the
     * article ranges.
     */
    @Test
    void theIslandsDomainIsPartOfTheSnapshot() {
        assertTrue(RefPayloads.DOMAINS.contains("ISLANDS"));
        assertEquals(RefPayloads.DOMAINS.indexOf("ARTICLE_RANGES") + 1,
                RefPayloads.DOMAINS.indexOf("ISLANDS"));
    }

    /**
     * Covers the TENDERS switch arm and {@code toDto(TenderDefinition)}: a
     * register cannot oppose a ceiling it was never told about, so the whole
     * administered description travels (BO-03-02-03/04/10 to 30).
     */
    @Test
    void getPageMapsTenders() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.payment.TenderDefinition tender =
                mock(com.intermarche.edge.domain.payment.TenderDefinition.class);
        tender.code = "TR";
        tender.functionalId = "030";
        tender.label = "Titre restaurant";
        tender.active = true;
        tender.displayOrder = 30;
        tender.maxAmount = new java.math.BigDecimal("25.00");
        tender.maxAmountControl =
                com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.BLOCKING;
        tender.maxCount = 2;
        tender.maxCountControl =
                com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR;
        tender.drawerOpening =
                com.intermarche.edge.domain.payment.TenderDefinition.DrawerOpening.IF_CHANGE_DUE;
        tender.changeTenderCode = "CASH";
        tender.refundAllowed = true;
        tender.fidelityReported = true;
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> query =
                singlePage(0, 10, List.of(tender));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.payment.TenderDefinition
                    .find("order by code")).thenReturn(query);
            RefPayloads.TenderDefinitionDto dto =
                    (RefPayloads.TenderDefinitionDto) service.getPage("TENDERS", 0, 10).get(0);
            assertEquals("TR", dto.code);
            assertEquals("030", dto.functionalId);
            assertEquals("Titre restaurant", dto.label);
            assertTrue(dto.active);
            assertEquals(30, dto.displayOrder);
            assertEquals("25.00", dto.maxAmount);
            assertEquals("BLOCKING", dto.maxAmountControl);
            assertEquals(Integer.valueOf(2), dto.maxCount);
            assertEquals("SUPERVISOR", dto.maxCountControl);
            assertEquals("IF_CHANGE_DUE", dto.drawerOpening);
            assertEquals("CASH", dto.changeTenderCode);
            assertTrue(dto.refundAllowed);
            assertTrue(dto.fidelityReported);
            assertNull(dto.minAmount);
            assertNull(dto.secondMaxAmount);
            assertNull(dto.maxChangeAmount);
        }
    }

    /**
     * A tender renders into the canonical string field by field, so raising a
     * ceiling upstream changes the domain fingerprint — and an unadministered
     * row renders its absent bounds as empty rather than as "null".
     *
     * @throws Exception if reflection fails
     */
    @Test
    void canonicalRendersATender() throws Exception {
        RefExportService service = new RefExportService();
        Method canonical = RefExportService.class.getDeclaredMethod("canonical", Object.class);
        canonical.setAccessible(true);
        RefPayloads.TenderDefinitionDto dto = new RefPayloads.TenderDefinitionDto();
        dto.code = "TR";
        dto.functionalId = "030";
        dto.label = "Titre restaurant";
        dto.active = true;
        dto.displayOrder = 30;
        dto.maxAmount = "25.00";
        dto.maxAmountControl = "BLOCKING";
        dto.maxCount = 2;
        dto.maxCountControl = "SUPERVISOR";
        dto.drawerOpening = "IF_CHANGE_DUE";
        dto.changeTenderCode = "CASH";
        dto.refundAllowed = true;
        dto.fidelityReported = true;
        // BO-03-02-26/-31/-32/-33: the slip of this tender rides the canonical
        // string too, or a register whose slip was administered upstream would
        // never pull it.
        dto.documentTemplateCode = "JUSTIF_TR";
        dto.printCount = 2;
        dto.printDuplicate = true;
        dto.printForced = true;
        assertEquals("TR|030|Titre restaurant|true|30|25.00|BLOCKING|||||2|SUPERVISOR|||"
                        + "true|false|CASH|false|false|IF_CHANGE_DUE|false|false|false|false|"
                        + "false|true|JUSTIF_TR|2|true|true",
                canonical.invoke(service, dto));

        dto.maxAmount = "19.00";
        assertEquals("TR|030|Titre restaurant|true|30|19.00|BLOCKING|||||2|SUPERVISOR|||"
                        + "true|false|CASH|false|false|IF_CHANGE_DUE|false|false|false|false|"
                        + "false|true|JUSTIF_TR|2|true|true",
                canonical.invoke(service, dto));

        RefPayloads.TenderDefinitionDto bare = new RefPayloads.TenderDefinitionDto();
        bare.code = "VIDE";
        assertEquals("VIDE|||false|0|||||||||||false|false||false|false||false|false|false|"
                        + "false|false|false||0|false|false",
                canonical.invoke(service, bare));
    }

    /**
     * The TENDERS domain is part of the pulled snapshot, right after the
     * checkout points, which themselves follow the islands: a register is told
     * which island it belongs to, then which point it is, then how it may be
     * paid.
     */
    @Test
    void theTendersDomainIsPartOfTheSnapshot() {
        assertTrue(RefPayloads.DOMAINS.contains("TENDERS"));
        assertEquals(RefPayloads.DOMAINS.indexOf("ISLANDS") + 1,
                RefPayloads.DOMAINS.indexOf("CHECKOUT_POINTS"));
        assertEquals(RefPayloads.DOMAINS.indexOf("CHECKOUT_POINTS") + 1,
                RefPayloads.DOMAINS.indexOf("TENDERS"));
    }

    /**
     * Covers the DOCUMENT_TEMPLATES switch arm and
     * {@code toDto(DocumentTemplate)}: a register cannot lay out a document it
     * was never handed the layout of, so the source travels verbatim (BO-03-03).
     */
    @Test
    void getPageMapsDocumentTemplates() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.setting.DocumentTemplate template =
                mock(com.intermarche.edge.domain.setting.DocumentTemplate.class);
        template.code = "TICKET_VENTE";
        template.label = "Ticket de vente";
        template.documentType = com.intermarche.edge.domain.setting.DocumentTemplate
                .DocumentType.SALE_RECEIPT;
        template.active = true;
        template.priority = 100;
        template.width = 42;
        template.copies = 2;
        template.source = "TOTAL {totals.includingTax}";
        PanacheQuery<com.intermarche.edge.domain.setting.DocumentTemplate> query =
                singlePage(0, 10, List.of(template));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.setting.DocumentTemplate
                    .find("order by code")).thenReturn(query);
            RefPayloads.DocumentTemplateDto dto = (RefPayloads.DocumentTemplateDto)
                    service.getPage("DOCUMENT_TEMPLATES", 0, 10).get(0);
            assertEquals("TICKET_VENTE", dto.code);
            assertEquals("Ticket de vente", dto.label);
            assertEquals("SALE_RECEIPT", dto.documentType);
            assertTrue(dto.active);
            assertEquals(100, dto.priority);
            assertEquals(42, dto.width);
            assertEquals(2, dto.copies);
            assertEquals("TOTAL {totals.includingTax}", dto.source);
        }
    }

    /**
     * A template renders into the canonical string field by field, so rewriting
     * a layout upstream changes the domain fingerprint — and a template attached
     * to no document renders its absent type as empty rather than as "null".
     *
     * @throws Exception if reflection fails
     */
    @Test
    void canonicalRendersADocumentTemplate() throws Exception {
        RefExportService service = new RefExportService();
        Method canonical = RefExportService.class.getDeclaredMethod("canonical", Object.class);
        canonical.setAccessible(true);
        RefPayloads.DocumentTemplateDto dto = new RefPayloads.DocumentTemplateDto();
        dto.code = "TICKET_VENTE";
        dto.label = "Ticket de vente";
        dto.documentType = "SALE_RECEIPT";
        dto.active = true;
        dto.priority = 100;
        dto.width = 42;
        dto.copies = 2;
        dto.source = "TOTAL {totals.includingTax}";
        // BO-03-04-12/-13 and -19/-21 to -26: the NAMES of the document and its
        // numbering ride the canonical string as well — a shop that renamed its
        // reprint must see the new name at the next pull.
        dto.title = "TICKET DE CAISSE";
        dto.duplicateTitle = "DUPLICATA";
        dto.numberPrefix = "FS";
        dto.copyTitles = "ORIGINAL;DUPLICATA";
        assertEquals("TICKET_VENTE|Ticket de vente|SALE_RECEIPT|true|100|42|2|"
                + "TOTAL {totals.includingTax}|TICKET DE CAISSE|DUPLICATA|FS|"
                + "ORIGINAL;DUPLICATA", canonical.invoke(service, dto));

        dto.source = "TOTAL {totals.excludingTax}";
        assertEquals("TICKET_VENTE|Ticket de vente|SALE_RECEIPT|true|100|42|2|"
                + "TOTAL {totals.excludingTax}|TICKET DE CAISSE|DUPLICATA|FS|"
                + "ORIGINAL;DUPLICATA", canonical.invoke(service, dto));

        RefPayloads.DocumentTemplateDto bare = new RefPayloads.DocumentTemplateDto();
        bare.code = "VIDE";
        assertEquals("VIDE|||false|0|0|0|||||", canonical.invoke(service, bare));
    }

    /**
     * The DOCUMENT_TEMPLATES domain is part of the pulled snapshot, right after
     * the tenders.
     */
    @Test
    void theDocumentTemplatesDomainIsPartOfTheSnapshot() {
        assertTrue(RefPayloads.DOMAINS.contains("DOCUMENT_TEMPLATES"));
        assertEquals(RefPayloads.DOMAINS.indexOf("TENDERS") + 1,
                RefPayloads.DOMAINS.indexOf("DOCUMENT_TEMPLATES"));
    }

    // --------------------------------------------------
    // Gap-fill: TOUCH_GROUPS / POSTAL_CODES arms, the
    // non-null customer ternaries with their free fields,
    // the coupon field/control null arms, and the null
    // list arms of the two coupon canonical helpers.
    // --------------------------------------------------

    /**
     * Covers the TOUCH_GROUPS switch arm and {@code toDto(TouchGroupSetting)}:
     * the administered touch configuration flows verbatim into the payload, a
     * domain the existing suite only reached through {@code canonical}.
     */
    @Test
    void getPageMapsTouchGroups() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.setting.TouchGroupSetting setting =
                mock(com.intermarche.edge.domain.setting.TouchGroupSetting.class);
        setting.familyCode = "F1";
        setting.pinned = true;
        setting.buttonSize = "LARGE";
        setting.displayOrder = 3;
        setting.salesVolume = 99L;
        PanacheQuery<com.intermarche.edge.domain.setting.TouchGroupSetting> query =
                singlePage(0, 10, List.of(setting));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.setting.TouchGroupSetting
                    .find("order by familyCode")).thenReturn(query);
            List<?> result = service.getPage("TOUCH_GROUPS", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.TouchGroupDto dto = (RefPayloads.TouchGroupDto) result.get(0);
            assertEquals("F1", dto.familyCode);
            assertTrue(dto.pinned);
            assertEquals("LARGE", dto.buttonSize);
            assertEquals(3, dto.displayOrder);
            assertEquals(99L, dto.salesVolume);
        }
    }

    /**
     * Covers the POSTAL_CODES switch arm and {@code toDto(PostalCode)}: the
     * three fields flow verbatim into the payload.
     */
    @Test
    void getPageMapsPostalCodes() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.store.PostalCode postalCode =
                mock(com.intermarche.edge.domain.store.PostalCode.class);
        postalCode.code = "75008";
        postalCode.place = "Paris";
        postalCode.country = "FR";
        PanacheQuery<com.intermarche.edge.domain.store.PostalCode> query =
                singlePage(0, 10, List.of(postalCode));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.store.PostalCode
                    .find("order by code, place")).thenReturn(query);
            List<?> result = service.getPage("POSTAL_CODES", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.PostalCodeDto dto = (RefPayloads.PostalCodeDto) result.get(0);
            assertEquals("75008", dto.code);
            assertEquals("Paris", dto.place);
            assertEquals("FR", dto.country);
        }
    }

    /**
     * Covers the {@link RefPayloads.PostalCodeDto} {@code instanceof} true arm
     * of {@code canonical}: a postal code renders field by field, a null
     * country exercising the empty {@code n} arm alongside the non-null ones.
     * Invoked reflectively because the production flow only ever feeds
     * already-mapped DTOs to {@code canonical}.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void canonicalRendersAPostalCode() throws Exception {
        RefExportService service = new RefExportService();
        Method canonical = RefExportService.class.getDeclaredMethod("canonical", Object.class);
        canonical.setAccessible(true);
        RefPayloads.PostalCodeDto dto = new RefPayloads.PostalCodeDto();
        dto.code = "75008";
        dto.place = "Paris";
        dto.country = "FR";
        assertEquals("75008|Paris|FR", canonical.invoke(service, dto));
        dto.country = null;
        assertEquals("75008|Paris|", canonical.invoke(service, dto));
    }

    /**
     * Covers the non-null arms of the {@code origin}, {@code dueDate} and
     * {@code discountPercent} ternaries of {@code toDto(AccountCustomer)} and
     * the true arm of the {@code freeFields != null} guard with its iteration:
     * a customer carrying an origin, a due date, a discount and two free fields
     * renders every one of them, whereas the existing customer fixtures left all
     * four unset (the null / empty arms).
     */
    @Test
    void getPageMapsACustomerWithOriginDueDateDiscountAndFreeFields() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.payment.AccountCustomer customer =
                mock(com.intermarche.edge.domain.payment.AccountCustomer.class);
        customer.accountNumber = "A3";
        customer.companyName = "Gamma";
        customer.origin = com.intermarche.edge.domain.payment.AccountCustomer.Origin.INTEGRATED;
        customer.dueDate = LocalDate.of(2026, 10, 31);
        customer.discountPercent = new BigDecimal("2.50");
        com.intermarche.edge.domain.payment.AccountCustomer.FreeField zone =
                new com.intermarche.edge.domain.payment.AccountCustomer.FreeField(1, "Zone", "A");
        com.intermarche.edge.domain.payment.AccountCustomer.FreeField tour =
                new com.intermarche.edge.domain.payment.AccountCustomer.FreeField(2, "Tournée", "B");
        customer.freeFields = new java.util.ArrayList<>(List.of(zone, tour));
        PanacheQuery<com.intermarche.edge.domain.payment.AccountCustomer> query =
                singlePage(0, 10, List.of(customer));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.payment.AccountCustomer
                    .find("order by accountNumber")).thenReturn(query);
            RefPayloads.CustomerDto dto =
                    (RefPayloads.CustomerDto) service.getPage("CUSTOMERS", 0, 10).get(0);
            assertEquals("A3", dto.accountNumber);
            assertEquals("INTEGRATED", dto.origin);
            assertEquals("2026-10-31", dto.dueDate);
            assertEquals("2.50", dto.discountPercent);
            assertEquals(2, dto.freeFields.size());
            assertEquals(1, dto.freeFields.get(0).slot);
            assertEquals("Zone", dto.freeFields.get(0).label);
            assertEquals("A", dto.freeFields.get(0).value);
            assertEquals(2, dto.freeFields.get(1).slot);
            assertEquals("Tournée", dto.freeFields.get(1).label);
            assertEquals("B", dto.freeFields.get(1).value);
        }
    }

    /**
     * Covers the non-null {@code dateFormat} arm of a coupon field and the null
     * {@code level} arm of a coupon control in {@code toDto(CouponType)}: the
     * field names a date layout (rendered by name) while the control names a
     * kind but leaves its level unset (rendered null), the two arms the
     * existing fixtures never reached.
     */
    @Test
    void getPageMapsACouponFieldDateFormatAndALevellessControl() {
        RefExportService service = new RefExportService();
        CouponType type = mock(CouponType.class);
        type.code = "C4";
        type.amountSource = CouponType.AmountSource.ENCODED;
        CouponField dated = new CouponField();
        dated.role = CouponField.Role.DATE_START;
        dated.offsetPosition = 5;
        dated.fieldLength = 6;
        dated.kind = CouponField.Kind.NUMERIC;
        dated.dateFormat = CouponField.DateFormat.DDMMYY;
        type.fields = new java.util.ArrayList<>(List.of(dated));
        CouponControl levelless = new CouponControl();
        levelless.kind = CouponControl.Kind.EXPIRED;
        levelless.level = null;
        levelless.message = "PERIME";
        type.controls = new java.util.ArrayList<>(List.of(levelless));
        PanacheQuery<CouponType> query = singlePage(0, 10, List.of(type));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.find("order by code")).thenReturn(query);
            RefPayloads.CouponTypeDto dto =
                    (RefPayloads.CouponTypeDto) service.getPage("COUPON_TYPES", 0, 10).get(0);
            assertEquals(1, dto.fields.size());
            assertEquals("DATE_START", dto.fields.get(0).role);
            assertEquals("DDMMYY", dto.fields.get(0).dateFormat);
            assertEquals(1, dto.controls.size());
            assertEquals("EXPIRED", dto.controls.get(0).kind);
            assertNull(dto.controls.get(0).level);
            assertEquals("PERIME", dto.controls.get(0).message);
        }
    }

    /**
     * Covers the {@code == null} arm of the two coupon canonical helpers
     * ({@code couponFields} and {@code couponControls}): a coupon type whose
     * field and control lists are null renders both trailing segments empty,
     * whereas the existing fixtures only ever exercised the empty-but-non-null
     * and the populated arms. Invoked reflectively because {@code toDto} always
     * hands these helpers a non-null list in the production flow.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void canonicalRendersACouponTypeWithNullFieldAndControlLists() throws Exception {
        RefExportService service = new RefExportService();
        Method canonical = RefExportService.class.getDeclaredMethod("canonical", Object.class);
        canonical.setAccessible(true);
        RefPayloads.CouponTypeDto dto = new RefPayloads.CouponTypeDto();
        dto.code = "C5";
        dto.amountSource = "MANUAL";
        dto.fields = null;
        dto.controls = null;
        assertEquals("C5|||MANUAL||0|false|false||||false|||", canonical.invoke(service, dto));
    }

    // --------------------------------------------------
    // The descent of the checkout point (BO-03-09, BO-03-10)
    // --------------------------------------------------

    /**
     * Covers the CHECKOUT_POINTS switch arm and {@code toDto(CheckoutPoint)}:
     * the twelve fields travel, the two enumerations by NAME, and a point whose
     * type the back office has not described travels as null rather than as a
     * guessed default.
     */
    @Test
    void getPageMapsCheckoutPointsIncludingUndescribedTypes() {
        RefExportService service = new RefExportService();
        com.intermarche.edge.domain.store.CheckoutPoint described =
                new com.intermarche.edge.domain.store.CheckoutPoint();
        described.terminalId = "POS01";
        described.pdvNumber = "01234";
        described.islandCode = "CAISSES_AVANT";
        described.label = "Caisse 1";
        described.active = true;
        described.tpvType = com.intermarche.edge.domain.store.TpvType.SELF_CHECKOUT_DN;
        described.technicalHostessBadge = "11111111";
        described.cashierDisplayType = com.intermarche.edge.domain.store.DisplayType.TOUCH;
        described.cashierDisplayResolution = "1024x768";
        described.customerDisplayType = com.intermarche.edge.domain.store.DisplayType.NONE;
        described.customerDisplayResolution = null;
        described.menuTemplateCode = "MENU_LS";
        // BO-03-09-15/-24/-25/-26: the peripherals descend with the point.
        described.scaleProtocol = com.intermarche.edge.domain.store.ScaleProtocol.DIGI;
        described.scaleAddress = "10.0.0.8";
        described.scalePort = 4001;
        described.coinMachineProtocol =
                com.intermarche.edge.domain.store.CoinMachineProtocol.GLORY;
        described.coinMachineAddress = "10.0.0.9";
        described.coinMachinePort = 5001;

        com.intermarche.edge.domain.store.CheckoutPoint undescribed =
                new com.intermarche.edge.domain.store.CheckoutPoint();
        undescribed.terminalId = "POS02";
        undescribed.active = false;
        undescribed.tpvType = null;
        undescribed.cashierDisplayType = null;
        undescribed.customerDisplayType = null;
        undescribed.scaleProtocol = null;
        undescribed.coinMachineProtocol = null;

        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<com.intermarche.edge.domain.store.CheckoutPoint> query =
                    mock(PanacheQuery.class);
            when(query.page(0, 10)).thenReturn(query);
            when(query.list()).thenReturn(List.of(described, undescribed));
            ms.when(() -> PanacheEntityBase.find("order by terminalId")).thenReturn(query);

            List<?> rows = service.getPage("CHECKOUT_POINTS", 0, 10);
            assertEquals(2, rows.size());

            RefPayloads.CheckoutPointDto first = (RefPayloads.CheckoutPointDto) rows.get(0);
            assertEquals("POS01", first.terminalId);
            assertEquals("01234", first.pdvNumber);
            assertEquals("CAISSES_AVANT", first.islandCode);
            assertEquals("Caisse 1", first.label);
            assertTrue(first.active);
            assertEquals("SELF_CHECKOUT_DN", first.tpvType);
            assertEquals("11111111", first.technicalHostessBadge);
            assertEquals("TOUCH", first.cashierDisplayType);
            assertEquals("1024x768", first.cashierDisplayResolution);
            assertEquals("NONE", first.customerDisplayType);
            assertNull(first.customerDisplayResolution);
            assertEquals("MENU_LS", first.menuTemplateCode);
            assertEquals("DIGI", first.scaleProtocol);
            assertEquals("10.0.0.8", first.scaleAddress);
            assertEquals(Integer.valueOf(4001), first.scalePort);
            assertEquals("GLORY", first.coinMachineProtocol);
            assertEquals("10.0.0.9", first.coinMachineAddress);
            assertEquals(Integer.valueOf(5001), first.coinMachinePort);

            RefPayloads.CheckoutPointDto second = (RefPayloads.CheckoutPointDto) rows.get(1);
            assertEquals("POS02", second.terminalId);
            assertFalse(second.active);
            assertNull(second.tpvType);
            assertNull(second.cashierDisplayType);
            assertNull(second.customerDisplayType);
            assertNull(second.scaleProtocol);
            assertNull(second.scalePort);
            assertNull(second.coinMachineProtocol);
            assertNull(second.coinMachinePort);
        }
    }

    /**
     * CHECKOUT_POINTS is a REGISTER-facing domain, served right after the islands
     * so a lane reads a complete graph in one cycle: a point names its island,
     * and the island must already be there.
     */
    @Test
    void checkoutPointsIsPulledJustAfterTheIslands() {
        List<String> domains = RefPayloads.DOMAINS;
        assertTrue(domains.contains("CHECKOUT_POINTS"));
        assertEquals(domains.indexOf("ISLANDS") + 1, domains.indexOf("CHECKOUT_POINTS"));
    }

    /**
     * THE trap of the whole lot: the SETTINGS fingerprint is cached PER TERMINAL.
     * Two registers asking inside the same TTL window each get the fingerprint of
     * THEIR resolution — keyed by domain alone, the second would be told that
     * nothing changed and would keep the first one's parameters.
     */
    @Test
    void settingsFingerprintIsCachedPerTerminal() {
        RefExportService service = new RefExportService();
        wireSettingsPerTerminal(service,
                Map.of("POS01", Map.of("display.show-ean", "true"),
                        "POS02", Map.of("display.show-ean", "false")),
                Map.of());

        String first = service.getFingerprints(List.of("SETTINGS"), "POS01").get("SETTINGS");
        String second = service.getFingerprints(List.of("SETTINGS"), "POS02").get("SETTINGS");
        assertNotEquals(first, second);

        // And within the TTL window each register keeps getting its own.
        assertEquals(first, service.getFingerprints(List.of("SETTINGS"), "POS01").get("SETTINGS"));
        assertEquals(second, service.getFingerprints(List.of("SETTINGS"), "POS02").get("SETTINGS"));
    }

    /**
     * A terminal that names itself with a BLANK header falls on the bare key, like
     * a register that sends no header at all — the two legs of the blank guard in
     * {@code cacheKey}.
     */
    @Test
    void settingsFingerprintOfABlankTerminalIsTheOneOfNoTerminal() {
        RefExportService service = new RefExportService();
        wireSettingsPerTerminal(service,
                Map.of("POS01", Map.of("display.show-ean", "true")),
                Map.of("display.show-ean", "false"));

        String none = service.getFingerprints(List.of("SETTINGS"), null).get("SETTINGS");
        String blank = service.getFingerprints(List.of("SETTINGS"), "   ").get("SETTINGS");
        assertEquals(none, blank);
    }

    /**
     * A GLOBAL domain does not become terminal-dependent: one register's poll
     * still warms the cache for the whole lane, which is what keeps the cheap
     * poll cheap.
     */
    @Test
    void aGlobalDomainKeepsOneFingerprintForEveryTerminal() {
        RefExportService service = new RefExportService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<VatRate> query = mock(PanacheQuery.class);
            when(query.page(anyInt(), anyInt())).thenReturn(query);
            when(query.list()).thenReturn(List.of());
            ms.when(() -> PanacheEntityBase.find("order by number")).thenReturn(query);

            String first = service.getFingerprints(List.of("VAT_RATES"), "POS01").get("VAT_RATES");
            String second = service.getFingerprints(List.of("VAT_RATES"), "POS02").get("VAT_RATES");
            assertEquals(first, second);
        }
    }
}
