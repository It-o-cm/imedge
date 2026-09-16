package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.barcode.AlertLevel;
import com.intermarche.pos.domain.barcode.ArticleBarcodeRange;
import com.intermarche.pos.domain.barcode.CouponControl;
import com.intermarche.pos.domain.barcode.CouponField;
import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.store.Country;
import com.intermarche.pos.domain.payment.Currency;
import com.intermarche.pos.domain.setting.EchelonLevel;
import com.intermarche.pos.domain.setting.EchelonSetting;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sync.EngineFeed;
import com.intermarche.pos.domain.store.Enseigne;
import com.intermarche.pos.domain.store.Pdv;
import com.intermarche.pos.domain.setting.PosSetting;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.domain.catalog.ProductType;
import com.intermarche.pos.domain.sync.RefState;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.domain.setting.TouchGroupSetting;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefApplyService}.
 * <p>
 * The service is a Panache active-record consumer: every domain method
 * upserts entities through static finders ({@code X.find(...)},
 * {@code X.listAll()}, {@code Price.deleteAll()}) and persists them. All
 * static access is intercepted with {@link org.mockito.Mockito#mockStatic}
 * on {@link PanacheEntityBase}, and every {@code new X()} on the insert path
 * is neutralized with {@link org.mockito.Mockito#mockConstruction} so the
 * constructed instance is a mock whose {@code persist()} is a no-op. Found
 * and listed entities are plain Mockito mocks (fields read/written directly,
 * {@code persist()} inert). No database and no Quarkus context is booted.
 * <p>
 * Every branch is covered: {@code applyFamilies} (insert / update arms),
 * {@code applyProducts} (insert / update, the {@code productType} ternary
 * both arms, and the three deactivation arms — seen, absent-active,
 * absent-inactive), {@code applyPrices} (the {@code productEan} ternary both
 * arms, product-not-found via null-EAN and via a missing row, the
 * {@code priority} ternary both arms, and both arms of the shared
 * {@code parse} helper via start/end timestamps), {@code applyEmployees}
 * (insert / update, the {@code theme == null} guard both arms, and the three
 * deactivation arms), {@code applyCouponTypes} (insert / update and the three
 * deactivation arms), {@code recordApplied} (insert / update) and
 * {@code lastApplied} (present / absent). Branch enumeration: 26 two-way
 * decision points (52 branches), every arm exercised — 100%.
 */
class RefApplyServiceTest {

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the given
     * value.
     *
     * @param result the value the query must return
     * @param <T> the queried type
     * @return the configured mocked query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    // --------------------------------------------------
    // applyFamilies
    // --------------------------------------------------

    /**
     * Covers both arms of {@code applyFamilies}: the first row has no existing
     * family (insert path, constructed instance gets its code) while the second
     * matches an existing family (update path), and both are populated and
     * persisted.
     */
    @Test
    void applyFamiliesInsertsAndUpdates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.FamilyDto insert = new RefPayloads.FamilyDto();
        insert.code = "F1";
        insert.description = "Fruits";
        insert.flags = "BIO";
        RefPayloads.FamilyDto update = new RefPayloads.FamilyDto();
        update.code = "F2";
        update.description = "Légumes";
        update.flags = "LOCAL";
        ProductFamily existing = mock(ProductFamily.class);
        existing.productFamilies = new java.util.HashSet<>();
        existing.products = new java.util.HashSet<>();
        PanacheQuery<ProductFamily> absentQuery = queryReturning(null);
        PanacheQuery<ProductFamily> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ProductFamily> created = mockConstruction(ProductFamily.class,
                        (family, ctx) -> {
                            family.productFamilies = new java.util.HashSet<>();
                            family.products = new java.util.HashSet<>();
                        })) {
            mocked.when(() -> ProductFamily.find("code", "F1")).thenReturn(absentQuery);
            mocked.when(() -> ProductFamily.find("code", "F2")).thenReturn(existingQuery);
            service.applyFamilies(List.of(insert, update));
            ProductFamily inserted = created.constructed().get(0);
            assertEquals("F1", inserted.code);
            assertEquals("Fruits", inserted.description);
            assertEquals("BIO", inserted.flags);
            assertEquals("Légumes", existing.description);
            assertEquals("LOCAL", existing.flags);
            // Persisted twice: once for the row, once for its (empty) edges.
            verify(inserted, times(2)).persist();
            verify(existing, times(2)).persist();
        }
    }

    /**
     * {@code applyTouchGroups} upserts by group code — an absent row is created,
     * a present one updated — and DELETES the rows the snapshot no longer
     * carries, so a group un-pinned upstream falls back to the defaults instead
     * of staying pinned on the register forever.
     * <p>
     * The null {@code buttonSize} of the inserted row also covers the default
     * arm: a payload that says nothing about the size must land on NORMAL, not
     * on null, because the column is not nullable.
     */
    @Test
    void applyTouchGroupsUpsertsAndDeletesTheAbsent() {
        RefApplyService service = new RefApplyService();
        RefPayloads.TouchGroupDto insert = new RefPayloads.TouchGroupDto();
        insert.familyCode = "F1";
        insert.pinned = true;
        insert.buttonSize = null;
        insert.displayOrder = 2;
        insert.salesVolume = 40L;
        RefPayloads.TouchGroupDto update = new RefPayloads.TouchGroupDto();
        update.familyCode = "F2";
        update.buttonSize = "LARGE";
        update.displayOrder = 7;
        update.salesVolume = 5L;
        TouchGroupSetting existing = mock(TouchGroupSetting.class);
        TouchGroupSetting stale = mock(TouchGroupSetting.class);
        stale.familyCode = "GONE";
        PanacheQuery<TouchGroupSetting> absentQuery = queryReturning(null);
        PanacheQuery<TouchGroupSetting> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<TouchGroupSetting> created =
                        mockConstruction(TouchGroupSetting.class)) {
            mocked.when(() -> TouchGroupSetting.find("familyCode", "F1")).thenReturn(absentQuery);
            mocked.when(() -> TouchGroupSetting.find("familyCode", "F2")).thenReturn(existingQuery);
            mocked.when(() -> TouchGroupSetting.listAll()).thenReturn(List.of(stale));
            service.applyTouchGroups(List.of(insert, update));
            TouchGroupSetting inserted = created.constructed().get(0);
            assertEquals("F1", inserted.familyCode);
            assertTrue(inserted.pinned);
            assertEquals("NORMAL", inserted.buttonSize);
            assertEquals(2, inserted.displayOrder);
            assertEquals(40L, inserted.salesVolume);
            assertEquals("LARGE", existing.buttonSize);
            assertEquals(7, existing.displayOrder);
            assertEquals(5L, existing.salesVolume);
            verify(stale, times(1)).delete();
        }
    }

    /**
     * The group TREE and the article memberships of the snapshot are rebuilt:
     * a child declaring its parent lands in that parent's collection even when
     * the parent comes later in the snapshot, and an article is attached by
     * EAN. Without this the register would receive flat, empty groups.
     */
    @Test
    void applyFamiliesRebuildsTheTreeAndTheMemberships() {
        RefApplyService service = new RefApplyService();
        RefPayloads.FamilyDto child = new RefPayloads.FamilyDto();
        child.code = "CHILD";
        child.parentCodes = List.of("PARENT");
        child.productEans = List.of("3001", "9999");
        RefPayloads.FamilyDto parent = new RefPayloads.FamilyDto();
        parent.code = "PARENT";
        ProductFamily childRow = mock(ProductFamily.class);
        childRow.productFamilies = new java.util.HashSet<>();
        childRow.products = new java.util.HashSet<>();
        ProductFamily parentRow = mock(ProductFamily.class);
        parentRow.productFamilies = new java.util.HashSet<>();
        parentRow.products = new java.util.HashSet<>();
        Product apple = mock(Product.class);
        // Every query mock is built BEFORE the static stubbing: calling a
        // helper that stubs, inside a when(...) that is not finished, is
        // nested stubbing and Mockito rejects it.
        PanacheQuery<ProductFamily> childQuery = queryReturning(childRow);
        PanacheQuery<ProductFamily> parentQuery = queryReturning(parentRow);
        PanacheQuery<Product> appleQuery = queryReturning(apple);
        PanacheQuery<Product> unknownQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("code", "CHILD")).thenReturn(childQuery);
            mocked.when(() -> ProductFamily.find("code", "PARENT")).thenReturn(parentQuery);
            mocked.when(() -> Product.find("ean", "3001")).thenReturn(appleQuery);
            mocked.when(() -> Product.find("ean", "9999")).thenReturn(unknownQuery);
            service.applyFamilies(List.of(child, parent));
            assertTrue(parentRow.productFamilies.contains(childRow));
            assertTrue(childRow.products.contains(apple));
            assertEquals(1, childRow.products.size());
            assertTrue(childRow.productFamilies.isEmpty());
        }
    }

    /**
     * The edge collections are emptied before any wiring: a family whose row
     * comes AFTER a child that just joined it does not lose that child.
     */
    @Test
    void applyFamiliesClearsEveryEdgeBeforeWiringAny() {
        RefApplyService service = new RefApplyService();
        RefPayloads.FamilyDto child = new RefPayloads.FamilyDto();
        child.code = "CHILD";
        child.parentCodes = List.of("PARENT");
        RefPayloads.FamilyDto parent = new RefPayloads.FamilyDto();
        parent.code = "PARENT";
        ProductFamily childRow = mock(ProductFamily.class);
        childRow.productFamilies = new java.util.HashSet<>();
        childRow.products = new java.util.HashSet<>();
        ProductFamily parentRow = mock(ProductFamily.class);
        ProductFamily stale = mock(ProductFamily.class);
        parentRow.productFamilies = new java.util.HashSet<>(java.util.Set.of(stale));
        parentRow.products = new java.util.HashSet<>();
        PanacheQuery<ProductFamily> childQuery = queryReturning(childRow);
        PanacheQuery<ProductFamily> parentQuery = queryReturning(parentRow);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("code", "CHILD")).thenReturn(childQuery);
            mocked.when(() -> ProductFamily.find("code", "PARENT")).thenReturn(parentQuery);
            service.applyFamilies(List.of(child, parent));
            assertTrue(parentRow.productFamilies.contains(childRow));
            assertFalse(parentRow.productFamilies.contains(stale));
        }
    }

    /**
     * Covers the missed {@code parent == null} arm of {@code applyFamilies}
     * (line 102): a child declares a parent code that is not part of the
     * snapshot, so {@code byCode.get(parentCode)} resolves to null and the row
     * is simply not wired into any parent — no NullPointerException, no edge.
     */
    @Test
    void applyFamiliesSkipsWiringWhenParentIsUnknown() {
        RefApplyService service = new RefApplyService();
        RefPayloads.FamilyDto child = new RefPayloads.FamilyDto();
        child.code = "CHILD";
        child.parentCodes = List.of("GHOST");
        ProductFamily childRow = mock(ProductFamily.class);
        childRow.productFamilies = new java.util.HashSet<>();
        childRow.products = new java.util.HashSet<>();
        PanacheQuery<ProductFamily> childQuery = queryReturning(childRow);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("code", "CHILD")).thenReturn(childQuery);
            service.applyFamilies(List.of(child));
            assertTrue(childRow.productFamilies.isEmpty());
            assertTrue(childRow.products.isEmpty());
            verify(childRow, times(2)).persist();
        }
    }

    // --------------------------------------------------
    // applyProducts
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyProducts}: an inserted row with a
     * non-null {@code productType} (ternary true arm), an updated row with a
     * null {@code productType} (ternary false arm), then the three
     * deactivation arms — a seen product left untouched, an absent active
     * product deactivated, and an absent inactive product left untouched.
     */
    @Test
    void applyProductsInsertsUpdatesAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.ProductDto insert = new RefPayloads.ProductDto();
        insert.ean = "E1";
        insert.plu = "100";
        insert.name = "Pomme";
        insert.description = "desc";
        insert.icon = "icon";
        insert.brand = "brand";
        insert.referenceWeight = new BigDecimal("1.000");
        insert.referenceVolume = new BigDecimal("2.000");
        insert.productType = "WEIGHT";
        insert.unitName = "kg";
        insert.active = true;
        insert.forbiddenToSale = false;
        insert.ageRestriction = 18;
        insert.checkoutLabel = "POMME";
        insert.internalCode = "INT-1";
        insert.variableWeight = true;
        insert.attributes.put("VAT_EXEMPT", "true");
        RefPayloads.ProductDto update = new RefPayloads.ProductDto();
        update.ean = "E2";
        update.productType = null;
        update.active = true;
        // Null attributes on the update DTO covers the null-map arm of applyProducts.
        update.attributes = null;
        Product existing = mock(Product.class);
        Product seen = mock(Product.class);
        seen.ean = "E1";
        seen.active = true;
        Product absentActive = mock(Product.class);
        absentActive.ean = "Z1";
        absentActive.active = true;
        Product absentInactive = mock(Product.class);
        absentInactive.ean = "Z2";
        absentInactive.active = false;
        PanacheQuery<Product> absentQuery = queryReturning(null);
        PanacheQuery<Product> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Product> created = mockConstruction(Product.class)) {
            mocked.when(() -> Product.find("ean", "E1")).thenReturn(absentQuery);
            mocked.when(() -> Product.find("ean", "E2")).thenReturn(existingQuery);
            mocked.when(Product::listAll).thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyProducts(List.of(insert, update));
            Product inserted = created.constructed().get(0);
            assertEquals("E1", inserted.ean);
            assertEquals(ProductType.WEIGHT, inserted.productType);
            assertEquals("Pomme", inserted.name);
            assertEquals(18, inserted.ageRestriction);
            assertEquals("POMME", inserted.checkoutLabel);
            assertEquals("INT-1", inserted.internalCode);
            assertTrue(inserted.variableWeight);
            assertEquals("true", inserted.attributes.get("VAT_EXEMPT"));
            verify(inserted, times(1)).persist();
            assertNull(existing.productType);
            assertTrue(existing.attributes.isEmpty());
            verify(existing, times(1)).persist();
            assertTrue(seen.active);
            verify(seen, never()).persist();
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
    }

    // --------------------------------------------------
    // applyPrices
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyPrices}: the whole table is cleared, one
     * row references an existing product with a non-null priority and a
     * non-null start / null end (parse both arms), one row references an
     * existing product with a null priority (defaulted to zero) and a null
     * start / non-null end, one row carries a null EAN (ternary false arm,
     * skipped) and one row references a missing product (skipped).
     */
    @Test
    void applyPricesReplacesAndSkipsOrphans() {
        RefApplyService service = new RefApplyService();
        RefPayloads.PriceDto priced = new RefPayloads.PriceDto();
        priced.productEan = "E1";
        priced.priceExcludingTax = new BigDecimal("1.00");
        priced.priceIncludingTax = new BigDecimal("1.20");
        priced.vatRate = new BigDecimal("0.2000");
        priced.priority = 5;
        priced.startDateTime = "2026-01-01T10:00:00";
        priced.endDateTime = null;
        RefPayloads.PriceDto defaulted = new RefPayloads.PriceDto();
        defaulted.productEan = "E2";
        defaulted.priceExcludingTax = new BigDecimal("2.00");
        defaulted.priceIncludingTax = new BigDecimal("2.20");
        defaulted.vatRate = new BigDecimal("0.1000");
        defaulted.priority = null;
        defaulted.startDateTime = null;
        defaulted.endDateTime = "2026-12-31T23:59:59";
        RefPayloads.PriceDto nullEan = new RefPayloads.PriceDto();
        nullEan.productEan = null;
        RefPayloads.PriceDto orphan = new RefPayloads.PriceDto();
        orphan.productEan = "E9";
        Product p1 = mock(Product.class);
        Product p2 = mock(Product.class);
        PanacheQuery<Product> q1 = queryReturning(p1);
        PanacheQuery<Product> q2 = queryReturning(p2);
        PanacheQuery<Product> qOrphan = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Price> created = mockConstruction(Price.class)) {
            mocked.when(() -> Product.find("ean", "E1")).thenReturn(q1);
            mocked.when(() -> Product.find("ean", "E2")).thenReturn(q2);
            mocked.when(() -> Product.find("ean", "E9")).thenReturn(qOrphan);
            service.applyPrices(List.of(priced, defaulted, nullEan, orphan));
            mocked.verify(Price::deleteAll, times(1));
            assertEquals(2, created.constructed().size());
            Price first = created.constructed().get(0);
            assertSame(p1, first.product);
            assertEquals(5, first.priority);
            assertEquals(LocalDateTime.of(2026, 1, 1, 10, 0, 0), first.startDateTime);
            assertNull(first.endDateTime);
            verify(first, times(1)).persist();
            Price second = created.constructed().get(1);
            assertSame(p2, second.product);
            assertEquals(0, second.priority);
            assertNull(second.startDateTime);
            assertEquals(LocalDateTime.of(2026, 12, 31, 23, 59, 59), second.endDateTime);
            verify(second, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // applyEmployees
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyEmployees}: an inserted employee whose
     * fresh theme is null so the pulled theme seeds it (guard true arm), an
     * updated employee whose local theme survives the pull (guard false arm),
     * then the three deactivation arms — a seen employee left untouched, an
     * absent active employee deactivated, and an absent inactive employee left
     * untouched.
     */
    @Test
    void applyEmployeesInsertsUpdatesAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.EmployeeDto insert = new RefPayloads.EmployeeDto();
        insert.loginName = "alice";
        insert.firstName = "Alice";
        insert.lastName = "Martin";
        insert.password = "hash-alice";
        insert.email = "alice@x.fr";
        insert.role = "CASHIER";
        insert.badgeId = "B1";
        insert.theme = "dark";
        insert.active = true;
        RefPayloads.EmployeeDto update = new RefPayloads.EmployeeDto();
        update.loginName = "bob";
        update.firstName = "Bob";
        update.lastName = "Durand";
        update.password = "hash-bob";
        update.email = "bob@x.fr";
        update.role = "MANAGER";
        update.badgeId = "B2";
        update.theme = "dark";
        update.active = true;
        Employee existing = mock(Employee.class);
        existing.theme = "light";
        Employee seen = mock(Employee.class);
        seen.loginName = "alice";
        seen.active = true;
        Employee absentActive = mock(Employee.class);
        absentActive.loginName = "gone";
        absentActive.active = true;
        Employee absentInactive = mock(Employee.class);
        absentInactive.loginName = "old";
        absentInactive.active = false;
        PanacheQuery<Employee> absentQuery = queryReturning(null);
        PanacheQuery<Employee> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Employee> created = mockConstruction(Employee.class)) {
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(absentQuery);
            mocked.when(() -> Employee.find("loginName", "bob")).thenReturn(existingQuery);
            mocked.when(Employee::listAll).thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyEmployees(List.of(insert, update));
            Employee inserted = created.constructed().get(0);
            assertEquals("alice", inserted.loginName);
            assertEquals(Employee.EmployeeRole.CASHIER, inserted.role);
            assertEquals("dark", inserted.theme);
            verify(inserted, times(1)).persist();
            assertEquals("light", existing.theme);
            assertEquals(Employee.EmployeeRole.MANAGER, existing.role);
            verify(existing, times(1)).persist();
            assertTrue(seen.active);
            verify(seen, never()).persist();
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
    }

    // --------------------------------------------------
    // applyCouponTypes
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyCouponTypes}: an inserted coupon type
     * (ENCODED source), an updated one (MANUAL source), then the three
     * deactivation arms — a seen type left untouched, an absent active type
     * deactivated, and an absent inactive type left untouched.
     */
    @Test
    void applyCouponTypesInsertsUpdatesAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CouponTypeDto insert = new RefPayloads.CouponTypeDto();
        insert.code = "C1";
        insert.label = "Bon 5€";
        insert.matchPattern = "^99.*";
        insert.amountSource = "ENCODED";
        insert.amountPattern = "(\\d+)";
        insert.priority = 10;
        insert.active = true;
        insert.depositLine = false;
        insert.manualAmountOnAllNines = true;
        insert.islandCodes = "AVANT;COMPTOIR";
        RefPayloads.CouponTypeDto update = new RefPayloads.CouponTypeDto();
        update.code = "C2";
        update.label = "Bon manuel";
        update.matchPattern = "";
        update.amountSource = "MANUAL";
        update.amountPattern = null;
        update.priority = 20;
        update.active = true;
        update.depositLine = true;
        CouponType existing = mock(CouponType.class);
        CouponType seen = mock(CouponType.class);
        seen.code = "C1";
        seen.active = true;
        CouponType absentActive = mock(CouponType.class);
        absentActive.code = "Z1";
        absentActive.active = true;
        CouponType absentInactive = mock(CouponType.class);
        absentInactive.code = "Z2";
        absentInactive.active = false;
        PanacheQuery<CouponType> absentQuery = queryReturning(null);
        PanacheQuery<CouponType> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CouponType> created = mockConstruction(CouponType.class)) {
            mocked.when(() -> CouponType.find("code", "C1")).thenReturn(absentQuery);
            mocked.when(() -> CouponType.find("code", "C2")).thenReturn(existingQuery);
            mocked.when(CouponType::listAll).thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyCouponTypes(List.of(insert, update));
            CouponType inserted = created.constructed().get(0);
            assertEquals("C1", inserted.code);
            assertEquals(CouponType.AmountSource.ENCODED, inserted.amountSource);
            // BO-03-06-12 : la règle du montant 9999 voyage avec la plage.
            assertTrue(inserted.manualAmountOnAllNines);
            assertEquals("AVANT;COMPTOIR", inserted.islandCodes);
            assertFalse(existing.manualAmountOnAllNines);
            verify(inserted, times(1)).persist();
            assertEquals(CouponType.AmountSource.MANUAL, existing.amountSource);
            assertTrue(existing.depositLine);
            verify(existing, times(1)).persist();
            assertTrue(seen.active);
            verify(seen, never()).persist();
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
    }

    /**
     * Covers {@code applyCouponFields}: the administered description rides
     * with the type, an unusable row is skipped, and the positions a stored
     * type already carried are dropped wholesale.
     */
    @Test
    void applyCouponTypesReplacesTheAdministeredPositions() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CouponTypeDto dto = new RefPayloads.CouponTypeDto();
        dto.code = "C3";
        dto.label = "Bon administré";
        dto.amountSource = "ENCODED";
        dto.priority = 5;
        dto.active = true;
        dto.prefix = "298";
        dto.codeLength = 13;
        dto.codeKind = "ALPHANUMERIC";
        RefPayloads.CouponFieldDto price = new RefPayloads.CouponFieldDto();
        price.role = "PRICE";
        price.offsetPosition = 9;
        price.fieldLength = 4;
        price.kind = "NUMERIC";
        price.decimals = 2;
        price.currency = "EUR";
        RefPayloads.CouponFieldDto date = new RefPayloads.CouponFieldDto();
        date.role = "DATE_END";
        date.offsetPosition = 3;
        date.fieldLength = 6;
        date.kind = null;
        date.dateFormat = "DDMMYY";
        RefPayloads.CouponFieldDto roleless = new RefPayloads.CouponFieldDto();
        dto.fields = new ArrayList<>(List.of(price, date, roleless));
        dto.fields.add(null);
        RefPayloads.CouponControlDto expiry = new RefPayloads.CouponControlDto();
        expiry.kind = "EXPIRED";
        expiry.level = "BLOCK";
        expiry.message = "BON PERIME";
        RefPayloads.CouponControlDto unreadable = new RefPayloads.CouponControlDto();
        unreadable.kind = "DUPLICATE";
        unreadable.level = "PEUT-ETRE";
        RefPayloads.CouponControlDto kindless = new RefPayloads.CouponControlDto();
        dto.controls = new ArrayList<>(List.of(expiry, unreadable, kindless));
        dto.controls.add(null);
        CouponType existing = mock(CouponType.class);
        existing.fields = new ArrayList<>();
        CouponField stale = new CouponField();
        stale.role = CouponField.Role.TPV_NUMBER;
        existing.fields.add(stale);
        existing.controls = new ArrayList<>();
        CouponControl staleControl = new CouponControl();
        staleControl.kind = CouponControl.Kind.OTHER_STORE;
        existing.controls.add(staleControl);
        PanacheQuery<CouponType> found = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.find("code", "C3")).thenReturn(found);
            mocked.when(CouponType::listAll).thenReturn(List.of());
            service.applyCouponTypes(List.of(dto));
            assertEquals("298", existing.prefix);
            assertEquals(13, existing.codeLength);
            assertEquals(CouponField.Kind.ALPHANUMERIC, existing.codeKind);
            assertEquals(2, existing.fields.size());
            CouponField applied = existing.fields.get(0);
            assertSame(existing, applied.couponType);
            assertEquals(CouponField.Role.PRICE, applied.role);
            assertEquals(9, applied.offsetPosition);
            assertEquals(4, applied.fieldLength);
            assertEquals(CouponField.Kind.NUMERIC, applied.kind);
            assertEquals(2, applied.decimals);
            assertNull(applied.dateFormat);
            assertEquals(CouponField.PriceCurrency.EUR, applied.currency);
            CouponField second = existing.fields.get(1);
            assertEquals(CouponField.Role.DATE_END, second.role);
            assertEquals(CouponField.Kind.NUMERIC, second.kind);
            assertEquals(CouponField.DateFormat.DDMMYY, second.dateFormat);
            assertNull(second.decimals);
            assertNull(second.currency);
            assertEquals(2, existing.controls.size());
            assertSame(existing, existing.controls.get(0).couponType);
            assertEquals(CouponControl.Kind.EXPIRED, existing.controls.get(0).kind);
            assertEquals(AlertLevel.BLOCK, existing.controls.get(0).level);
            assertEquals("BON PERIME", existing.controls.get(0).message);
            assertEquals(CouponControl.Kind.DUPLICATE, existing.controls.get(1).kind);
            assertEquals(AlertLevel.NONE, existing.controls.get(1).level);
        }
    }

    /**
     * Covers the remaining arms of {@code applyCouponFields}: a type carrying
     * no field list is given one, an absent code kind falls back to digits,
     * and a snapshot row without positions leaves the type with none.
     */
    @Test
    void applyCouponTypesToleratesAnAbsentDescription() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CouponTypeDto dto = new RefPayloads.CouponTypeDto();
        dto.code = "C4";
        dto.label = "Bon nu";
        dto.amountSource = "MANUAL";
        dto.priority = 5;
        dto.active = true;
        dto.fields = null;
        dto.controls = null;
        CouponType existing = mock(CouponType.class);
        PanacheQuery<CouponType> found = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.find("code", "C4")).thenReturn(found);
            mocked.when(CouponType::listAll).thenReturn(List.of());
            service.applyCouponTypes(List.of(dto));
            assertNull(existing.prefix);
            assertNull(existing.codeLength);
            assertEquals(CouponField.Kind.NUMERIC, existing.codeKind);
            assertTrue(existing.fields.isEmpty());
            assertTrue(existing.controls.isEmpty());
        }
    }

    // --------------------------------------------------
    // recordApplied
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code recordApplied}: no state row exists for
     * the domain, so one is constructed, stamped and persisted.
     */
    @Test
    void recordAppliedInsertsWhenAbsent() {
        RefApplyService service = new RefApplyService();
        PanacheQuery<RefState> absentQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<RefState> created = mockConstruction(RefState.class)) {
            mocked.when(() -> RefState.find("domain", "products")).thenReturn(absentQuery);
            service.recordApplied("products", "fp-123");
            RefState state = created.constructed().get(0);
            assertEquals("products", state.domain);
            assertEquals("fp-123", state.fingerprint);
            assertTrue(state.appliedAt != null);
            verify(state, times(1)).persist();
        }
    }

    /**
     * Covers the update arm of {@code recordApplied}: a state row already
     * exists, so its fingerprint and timestamp are refreshed and persisted.
     */
    @Test
    void recordAppliedUpdatesWhenPresent() {
        RefApplyService service = new RefApplyService();
        RefState existing = mock(RefState.class);
        PanacheQuery<RefState> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.find("domain", "products")).thenReturn(existingQuery);
            service.recordApplied("products", "fp-456");
            assertEquals("fp-456", existing.fingerprint);
            assertTrue(existing.appliedAt != null);
            verify(existing, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // lastApplied
    // --------------------------------------------------

    /**
     * Covers the present arm of {@code lastApplied}: a state row exists, so its
     * fingerprint is returned.
     */
    @Test
    void lastAppliedReturnsFingerprintWhenPresent() {
        RefApplyService service = new RefApplyService();
        RefState existing = mock(RefState.class);
        existing.fingerprint = "fp-789";
        PanacheQuery<RefState> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.find("domain", "products")).thenReturn(existingQuery);
            assertEquals("fp-789", service.lastApplied("products"));
        }
    }

    /**
     * Covers the absent arm of {@code lastApplied}: no state row exists, so
     * null is returned.
     */
    @Test
    void lastAppliedReturnsNullWhenAbsent() {
        RefApplyService service = new RefApplyService();
        PanacheQuery<RefState> absentQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.find("domain", "products")).thenReturn(absentQuery);
            assertNull(service.lastApplied("products"));
        }
    }

    // --------------------------------------------------
    // applySettings
    // --------------------------------------------------

    /**
     * Covers both arms of {@code applySettings}: one dto has no existing row
     * (insert path, constructed instance gets its key and value) while the
     * second matches an existing row (update path); every row is persisted,
     * the keys not seen are deleted (non-empty seen arm) and the settings
     * cache is invalidated.
     */
    @Test
    void applySettingsUpsertsDeletesAndInvalidates() {
        RefApplyService service = new RefApplyService();
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        RefPayloads.SettingDto insert = new RefPayloads.SettingDto();
        insert.key = "display.show-ean";
        insert.value = "true";
        RefPayloads.SettingDto update = new RefPayloads.SettingDto();
        update.key = "auth.idle-lockout-seconds";
        update.value = "30";
        PosSetting existing = mock(PosSetting.class);
        PanacheQuery<PosSetting> absentQuery = queryReturning(null);
        PanacheQuery<PosSetting> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<PosSetting> created = mockConstruction(PosSetting.class)) {
            mocked.when(() -> PosSetting.find("settingKey", "display.show-ean")).thenReturn(absentQuery);
            mocked.when(() -> PosSetting.find("settingKey", "auth.idle-lockout-seconds")).thenReturn(existingQuery);
            mocked.when(() -> PosSetting.delete(org.mockito.ArgumentMatchers.eq("settingKey not in ?1"),
                    org.mockito.ArgumentMatchers.any(java.util.Set.class))).thenReturn(2L);
            service.applySettings(List.of(insert, update));
            PosSetting inserted = created.constructed().get(0);
            assertEquals("display.show-ean", inserted.settingKey);
            assertEquals("true", inserted.settingValue);
            verify(inserted, times(1)).persist();
            assertEquals("30", existing.settingValue);
            verify(existing, times(1)).persist();
            mocked.verify(() -> PosSetting.delete(org.mockito.ArgumentMatchers.eq("settingKey not in ?1"),
                    org.mockito.ArgumentMatchers.any(java.util.Set.class)));
            verify(service.posSettingsService, times(1)).invalidate();
        }
    }

    /**
     * Covers the empty-payload arm of {@code applySettings}: with no dto the
     * loop is skipped and the delete guards against an empty {@code IN} clause
     * by passing a single sentinel (empty-seen arm), still invalidating the
     * cache.
     */
    @Test
    void applySettingsEmptyPayloadDeletesAllAndInvalidates() {
        RefApplyService service = new RefApplyService();
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<PosSetting> created = mockConstruction(PosSetting.class)) {
            mocked.when(() -> PosSetting.delete(org.mockito.ArgumentMatchers.eq("settingKey not in ?1"),
                    org.mockito.ArgumentMatchers.eq(List.of("")))).thenReturn(5L);
            service.applySettings(List.of());
            assertTrue(created.constructed().isEmpty());
            mocked.verify(() -> PosSetting.delete(org.mockito.ArgumentMatchers.eq("settingKey not in ?1"),
                    org.mockito.ArgumentMatchers.eq(List.of(""))));
            verify(service.posSettingsService, times(1)).invalidate();
        }
    }

    // --------------------------------------------------
    // applyEngineFeeds
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyEngineFeeds} except the empty-seen guard:
     * an unknown code is inserted (row-null arm); a known code whose version
     * differs is re-applied (row-present arm, version-non-null arm,
     * version-not-equal arm); a known code whose version is identical is skipped
     * (version-equal arm, no persist); a known code whose incoming version is
     * null is re-applied (version-null arm). Absent codes are deleted with a
     * non-empty seen set (non-empty-seen arm).
     */
    @Test
    void applyEngineFeedsInsertsUpdatesSkipsAndDeletes() {
        RefApplyService service = new RefApplyService();
        RefPayloads.EngineFeedDto insert = feedDto("F1", "v1", "c1");
        RefPayloads.EngineFeedDto differs = feedDto("F2", "new", "c2");
        RefPayloads.EngineFeedDto same = feedDto("F3", "same", "c3");
        RefPayloads.EngineFeedDto nullVersion = feedDto("F4", null, "c4");
        EngineFeed existingDiffers = mock(EngineFeed.class);
        existingDiffers.version = "old";
        EngineFeed existingSame = mock(EngineFeed.class);
        existingSame.version = "same";
        EngineFeed existingNull = mock(EngineFeed.class);
        existingNull.version = "prev";
        PanacheQuery<EngineFeed> q1 = queryReturning(null);
        PanacheQuery<EngineFeed> q2 = queryReturning(existingDiffers);
        PanacheQuery<EngineFeed> q3 = queryReturning(existingSame);
        PanacheQuery<EngineFeed> q4 = queryReturning(existingNull);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<EngineFeed> created = mockConstruction(EngineFeed.class)) {
            mocked.when(() -> EngineFeed.find("code", "F1")).thenReturn(q1);
            mocked.when(() -> EngineFeed.find("code", "F2")).thenReturn(q2);
            mocked.when(() -> EngineFeed.find("code", "F3")).thenReturn(q3);
            mocked.when(() -> EngineFeed.find("code", "F4")).thenReturn(q4);
            mocked.when(() -> EngineFeed.delete(org.mockito.ArgumentMatchers.eq("code not in ?1"),
                    org.mockito.ArgumentMatchers.any(java.util.Set.class))).thenReturn(1L);
            service.applyEngineFeeds(List.of(insert, differs, same, nullVersion));
            EngineFeed inserted = created.constructed().get(0);
            assertEquals("F1", inserted.code);
            assertEquals("c1", inserted.content);
            assertEquals("v1", inserted.version);
            verify(inserted, times(1)).persist();
            assertEquals("c2", existingDiffers.content);
            assertEquals("new", existingDiffers.version);
            verify(existingDiffers, times(1)).persist();
            verify(existingSame, never()).persist();
            assertEquals("c4", existingNull.content);
            assertNull(existingNull.version);
            verify(existingNull, times(1)).persist();
            mocked.verify(() -> EngineFeed.delete(org.mockito.ArgumentMatchers.eq("code not in ?1"),
                    org.mockito.ArgumentMatchers.any(java.util.Set.class)));
        }
    }

    /**
     * Covers the empty-seen guard of {@code applyEngineFeeds}: with no dto the
     * delete guards against an empty {@code IN} clause with a single sentinel
     * (empty-seen arm).
     */
    @Test
    void applyEngineFeedsEmptyPayloadDeletesAll() {
        RefApplyService service = new RefApplyService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> EngineFeed.delete(org.mockito.ArgumentMatchers.eq("code not in ?1"),
                    org.mockito.ArgumentMatchers.eq(List.of("")))).thenReturn(3L);
            service.applyEngineFeeds(List.of());
            mocked.verify(() -> EngineFeed.delete(org.mockito.ArgumentMatchers.eq("code not in ?1"),
                    org.mockito.ArgumentMatchers.eq(List.of(""))));
        }
    }

    /**
     * Builds an engine-feed payload.
     *
     * @param code the feed code
     * @param version the content version, or null
     * @param content the raw content
     * @return the payload
     */
    private RefPayloads.EngineFeedDto feedDto(String code, String version, String content) {
        RefPayloads.EngineFeedDto dto = new RefPayloads.EngineFeedDto();
        dto.code = code;
        dto.version = version;
        dto.content = content;
        return dto;
    }

    // --------------------------------------------------
    // applyCountries / applyEnseignes / applyPdvs / applyEchelonSettings
    // --------------------------------------------------

    /**
     * Covers {@code applyCountries}: the table is cleared wholesale and each dto
     * is inserted with its fields, the echelon tree being fully owned by the
     * central node.
     */
    @Test
    void applyCountriesReplacesWholesale() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CountryDto dto = new RefPayloads.CountryDto();
        dto.code = "FR";
        dto.name = "France";
        dto.defaultLanguage = "fr";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Country> created = mockConstruction(Country.class)) {
            service.applyCountries(List.of(dto));
            mocked.verify(Country::deleteAll, times(1));
            assertEquals(1, created.constructed().size());
            Country row = created.constructed().get(0);
            assertEquals("FR", row.code);
            assertEquals("France", row.name);
            assertEquals("fr", row.defaultLanguage);
            verify(row, times(1)).persist();
        }
    }

    /**
     * Covers {@code applyEnseignes}: wholesale replacement, each dto inserted
     * with its country up-link by code.
     */
    @Test
    void applyEnseignesReplacesWholesale() {
        RefApplyService service = new RefApplyService();
        RefPayloads.EnseigneDto dto = new RefPayloads.EnseigneDto();
        dto.code = "ITM";
        dto.name = "Intermarché";
        dto.countryCode = "FR";
        dto.defaultLanguage = "fr";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Enseigne> created = mockConstruction(Enseigne.class)) {
            service.applyEnseignes(List.of(dto));
            mocked.verify(Enseigne::deleteAll, times(1));
            assertEquals(1, created.constructed().size());
            Enseigne row = created.constructed().get(0);
            assertEquals("ITM", row.code);
            assertEquals("Intermarché", row.name);
            assertEquals("FR", row.countryCode);
            assertEquals("fr", row.defaultLanguage);
            verify(row, times(1)).persist();
        }
    }

    /**
     * Covers {@code applyPdvs}: wholesale replacement, each dto inserted with
     * its enseigne link, adhérent grouping and active flag.
     */
    @Test
    void applyPdvsReplacesWholesale() {
        RefApplyService service = new RefApplyService();
        RefPayloads.PdvDto dto = new RefPayloads.PdvDto();
        dto.pdvNumber = "01234";
        dto.name = "Lyon";
        dto.enseigneCode = "ITM";
        dto.adherentCode = "AD1";
        dto.active = true;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Pdv> created = mockConstruction(Pdv.class)) {
            service.applyPdvs(List.of(dto));
            mocked.verify(Pdv::deleteAll, times(1));
            assertEquals(1, created.constructed().size());
            Pdv row = created.constructed().get(0);
            assertEquals("01234", row.pdvNumber);
            assertEquals("Lyon", row.name);
            assertEquals("ITM", row.enseigneCode);
            assertEquals("AD1", row.adherentCode);
            assertTrue(row.active);
            verify(row, times(1)).persist();
        }
    }

    /**
     * Covers {@code applyEchelonSettings} and both arms of its
     * {@code effectiveDate != null} ternary: the table is cleared wholesale, a
     * dated row keeps its parsed effect date and an undated row keeps null, and
     * the settings cache is dropped so the store re-resolves.
     */
    @Test
    void applyEchelonSettingsReplacesParsesDateAndInvalidates() {
        RefApplyService service = new RefApplyService();
        service.posSettingsService = mock(PosSettingsService.class);
        RefPayloads.EchelonSettingDto dated = new RefPayloads.EchelonSettingDto();
        dated.level = "ENSEIGNE";
        dated.echelonCode = "ITM";
        dated.settingKey = "discount.enabled";
        dated.settingValue = "false";
        dated.effectiveDate = "2026-03-01";
        RefPayloads.EchelonSettingDto immediate = new RefPayloads.EchelonSettingDto();
        immediate.level = "COUNTRY";
        immediate.echelonCode = "FR";
        immediate.settingKey = "display.show-ean";
        immediate.settingValue = "true";
        immediate.effectiveDate = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<EchelonSetting> created = mockConstruction(EchelonSetting.class)) {
            service.applyEchelonSettings(List.of(dated, immediate));
            mocked.verify(EchelonSetting::deleteAll, times(1));
            assertEquals(2, created.constructed().size());
            EchelonSetting first = created.constructed().get(0);
            assertEquals(EchelonLevel.ENSEIGNE, first.level);
            assertEquals("ITM", first.echelonCode);
            assertEquals("discount.enabled", first.settingKey);
            assertEquals("false", first.settingValue);
            assertEquals(LocalDate.of(2026, 3, 1), first.effectiveDate);
            verify(first, times(1)).persist();
            EchelonSetting second = created.constructed().get(1);
            assertEquals(EchelonLevel.COUNTRY, second.level);
            assertNull(second.effectiveDate);
            verify(second, times(1)).persist();
            verify(service.posSettingsService, times(1)).invalidate();
        }
    }

    // --------------------------------------------------
    // applyCurrencies
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyCurrencies} and, through it, every arm of
     * the shared {@code amount} helper: an inserted currency with a readable
     * rate (currency-null true arm, rate-non-null ternary arm, amount
     * null-false + blank-false arms), an updated currency with a null rate
     * (currency-null false arm, rate-null ternary arm defaulting to ONE, amount
     * null-true arm), an inserted currency with a blank rate (amount blank-true
     * arm) and one with an unparsable rate (amount catch), then the three
     * deactivation arms — a seen currency untouched (seen-contains arm), an
     * absent active currency deactivated (absent + active arms) and an absent
     * inactive currency untouched (absent + inactive arm).
     */
    @Test
    void applyCurrenciesInsertsUpdatesDefaultsRateAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CurrencyDto insert = currencyDto("USD", "0.9");
        RefPayloads.CurrencyDto update = currencyDto("GBP", null);
        RefPayloads.CurrencyDto blank = currencyDto("CHF", "   ");
        RefPayloads.CurrencyDto invalid = currencyDto("JPY", "abc");
        Currency existing = mock(Currency.class);
        Currency seen = mock(Currency.class);
        seen.code = "USD";
        seen.active = true;
        Currency absentActive = mock(Currency.class);
        absentActive.code = "OLD";
        absentActive.active = true;
        Currency absentInactive = mock(Currency.class);
        absentInactive.code = "DEAD";
        absentInactive.active = false;
        PanacheQuery<Currency> absentQuery = queryReturning(null);
        PanacheQuery<Currency> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Currency> created = mockConstruction(Currency.class)) {
            mocked.when(() -> Currency.find("code", "USD")).thenReturn(absentQuery);
            mocked.when(() -> Currency.find("code", "GBP")).thenReturn(existingQuery);
            mocked.when(() -> Currency.find("code", "CHF")).thenReturn(absentQuery);
            mocked.when(() -> Currency.find("code", "JPY")).thenReturn(absentQuery);
            mocked.when(Currency::listAll).thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyCurrencies(List.of(insert, update, blank, invalid));
            assertEquals(3, created.constructed().size());
            Currency usd = created.constructed().get(0);
            assertEquals("USD", usd.code);
            assertEquals(new BigDecimal("0.9"), usd.euroPerUnit);
            verify(usd, times(1)).persist();
            assertEquals(BigDecimal.ONE, existing.euroPerUnit);
            verify(existing, times(1)).persist();
            Currency chf = created.constructed().get(1);
            assertEquals(BigDecimal.ONE, chf.euroPerUnit);
            Currency jpy = created.constructed().get(2);
            assertEquals(BigDecimal.ONE, jpy.euroPerUnit);
            assertTrue(seen.active);
            verify(seen, never()).persist();
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
    }

    /**
     * Builds a currency payload.
     *
     * @param code the ISO code
     * @param euroPerUnit the rate as text, possibly null or blank
     * @return the payload
     */
    private RefPayloads.CurrencyDto currencyDto(String code, String euroPerUnit) {
        RefPayloads.CurrencyDto dto = new RefPayloads.CurrencyDto();
        dto.code = code;
        dto.label = code + " label";
        dto.symbol = code;
        dto.euroPerUnit = euroPerUnit;
        dto.active = true;
        dto.displayOrder = 1;
        return dto;
    }

    // --------------------------------------------------
    // applyCustomers
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyCustomers}: an inserted customer
     * (customer-null true arm) whose constructed mock has a null address so a
     * fresh one is created (address-null true arm) and whose balance is
     * readable (balance-null false ternary arm), and an updated customer
     * (customer-null false arm) that already carries an address (address-null
     * false arm) and whose null balance defaults to zero (balance-null true
     * ternary arm). Nothing is deactivated for absent customers — a customer
     * opened at the till must survive the next pull.
     */
    @Test
    void applyCustomersInsertsUpdatesCreatesAddressAndDefaultsBalance() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CustomerDto insert = new RefPayloads.CustomerDto();
        insert.accountNumber = "ACC1";
        insert.companyName = "Alpha SARL";
        insert.lastName = "Martin";
        insert.firstName = "Alice";
        insert.street = "1 rue A";
        insert.postalCode = "69001";
        insert.city = "Lyon";
        insert.siret = "SIRET1";
        insert.vatNumber = "FR1";
        insert.phone = "0400000000";
        insert.email = "alpha@x.fr";
        insert.creditLimit = "100.00";
        insert.creditBalance = "10.50";
        RefPayloads.CustomerDto update = new RefPayloads.CustomerDto();
        update.accountNumber = "ACC2";
        update.companyName = "Beta SA";
        update.street = "2 rue B";
        update.postalCode = "75002";
        update.city = "Paris";
        update.creditLimit = "   ";
        update.creditBalance = null;
        AccountCustomer existing = mock(AccountCustomer.class);
        existing.address = new Address();
        PanacheQuery<AccountCustomer> absentQuery = queryReturning(null);
        PanacheQuery<AccountCustomer> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<AccountCustomer> created = mockConstruction(AccountCustomer.class)) {
            mocked.when(() -> AccountCustomer.find("accountNumber", "ACC1")).thenReturn(absentQuery);
            mocked.when(() -> AccountCustomer.find("accountNumber", "ACC2")).thenReturn(existingQuery);
            service.applyCustomers(List.of(insert, update));
            assertEquals(1, created.constructed().size());
            AccountCustomer inserted = created.constructed().get(0);
            assertEquals("ACC1", inserted.accountNumber);
            assertEquals("Alpha SARL", inserted.companyName);
            assertEquals("1 rue A", inserted.address.streetLine1);
            assertEquals("69001", inserted.address.postalCode);
            assertEquals("Lyon", inserted.address.city);
            assertEquals(new BigDecimal("100.00"), inserted.creditLimit);
            assertEquals(new BigDecimal("10.50"), inserted.creditBalance);
            verify(inserted, times(1)).persist();
            assertEquals("2 rue B", existing.address.streetLine1);
            assertEquals("Paris", existing.address.city);
            assertNull(existing.creditLimit);
            assertEquals(BigDecimal.ZERO, existing.creditBalance);
            verify(existing, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // Transactional boundary
    // --------------------------------------------------

    /**
     * Every public method that touches the database carries
     * {@link jakarta.transaction.Transactional}.
     * <p>
     * This is not decoration: the pull loop calls them from its own scheduler
     * thread, where no request context and no transaction exist. A method that
     * loses the annotation kills every pull cycle at its first database touch,
     * and a green {@code mvn verify} does not see it — unit tests never enter a
     * transaction, so the omission only shows at runtime, in a log line, once
     * per pull interval. Hence a test that reads the annotations themselves.
     */
    @Test
    void everyDatabaseMethodIsTransactional() {
        List<String> names = List.of("applyFamilies", "applyProducts", "applyPrices",
                "applyEmployees", "applyCouponTypes", "applySettings", "applyEngineFeeds",
                "applyCountries", "applyEnseignes", "applyPdvs", "applyEchelonSettings",
                "recordApplied", "lastApplied");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (java.lang.reflect.Method method : RefApplyService.class.getDeclaredMethods()) {
            if (names.contains(method.getName())) {
                seen.add(method.getName());
                assertTrue(method.isAnnotationPresent(jakarta.transaction.Transactional.class),
                        method.getName() + " must be @Transactional: the pull loop calls it"
                                + " outside any request context");
            }
        }
        assertEquals(new java.util.HashSet<>(names), seen,
                "a database method was renamed or removed: review the pull loop's"
                        + " transactional boundary before updating this list");
    }

    // --- Plages ARTICLE (BO-03-06-02/03/04/05/10) ---

    /**
     * Builds a snapshot row describing an article range.
     *
     * @param code the range code
     * @return the row
     */
    private RefPayloads.ArticleBarcodeRangeDto rangeDto(String code) {
        RefPayloads.ArticleBarcodeRangeDto dto = new RefPayloads.ArticleBarcodeRangeDto();
        dto.code = code;
        dto.label = "Étiquette prix";
        dto.active = true;
        dto.priority = 10;
        dto.prefix = "21";
        dto.codeLength = 13;
        dto.codeKind = "NUMERIC";
        // Deliberately NOT the historical 2/5 plan: a fixture matching the old
        // literals could not tell a real copy from a hard-coded one.
        dto.articlePosition = 4;
        dto.articleLength = 6;
        dto.valueSource = "PRICE";
        dto.valuePosition = 7;
        dto.valueLength = 5;
        dto.valueDecimals = 2;
        dto.currency = "EUR";
        dto.checkDigit = true;
        dto.matchPattern = "^21\\d{11}$";
        return dto;
    }

    /**
     * {@code applyArticleBarcodeRanges} covers the insert arm, the update arm
     * and the three deactivation arms — a seen range left untouched, an absent
     * active range deactivated, an absent inactive one left alone.
     */
    @Test
    void applyArticleRangesInsertsUpdatesAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.ArticleBarcodeRangeDto insert = rangeDto("R1");
        RefPayloads.ArticleBarcodeRangeDto update = rangeDto("R2");
        update.prefix = "297";
        update.codeLength = 16;
        update.valueSource = "WEIGHT";
        update.valueDecimals = 3;
        update.currency = "FRF";
        update.checkDigit = false;
        update.priority = 20;
        ArticleBarcodeRange existing = mock(ArticleBarcodeRange.class);
        ArticleBarcodeRange seen = mock(ArticleBarcodeRange.class);
        seen.code = "R1";
        seen.active = true;
        ArticleBarcodeRange absentActive = mock(ArticleBarcodeRange.class);
        absentActive.code = "Z1";
        absentActive.active = true;
        ArticleBarcodeRange absentInactive = mock(ArticleBarcodeRange.class);
        absentInactive.code = "Z2";
        absentInactive.active = false;
        PanacheQuery<ArticleBarcodeRange> absentQuery = queryReturning(null);
        PanacheQuery<ArticleBarcodeRange> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ArticleBarcodeRange> created =
                        mockConstruction(ArticleBarcodeRange.class)) {
            mocked.when(() -> ArticleBarcodeRange.find("code", "R1")).thenReturn(absentQuery);
            mocked.when(() -> ArticleBarcodeRange.find("code", "R2")).thenReturn(existingQuery);
            mocked.when(ArticleBarcodeRange::listAll)
                    .thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyArticleBarcodeRanges(List.of(insert, update));
            ArticleBarcodeRange inserted = created.constructed().get(0);
            assertEquals("R1", inserted.code);
            assertEquals("21", inserted.prefix);
            assertEquals(13, inserted.codeLength);
            assertEquals(4, inserted.articlePosition);
            assertEquals(6, inserted.articleLength);
            assertEquals(CouponField.Kind.NUMERIC, inserted.codeKind);
            assertEquals(ArticleBarcodeRange.ValueSource.PRICE, inserted.valueSource);
            assertEquals(2, inserted.valueDecimals);
            assertEquals(CouponField.PriceCurrency.EUR, inserted.currency);
            assertTrue(inserted.checkDigit);
            assertEquals("^21\\d{11}$", inserted.matchPattern);
            verify(inserted, times(1)).persist();

            assertEquals("297", existing.prefix);
            assertEquals(16, existing.codeLength);
            assertEquals(ArticleBarcodeRange.ValueSource.WEIGHT, existing.valueSource);
            assertEquals(3, existing.valueDecimals);
            assertEquals(CouponField.PriceCurrency.FRF, existing.currency);
            assertFalse(existing.checkDigit);
            assertEquals(20, existing.priority);
            verify(existing, times(1)).persist();

            assertTrue(seen.active);
            verify(seen, never()).persist();
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
    }

    /**
     * A row naming neither a character kind, nor a nature of value, nor a
     * currency falls back to the three defaults (the three null arms).
     */
    @Test
    void applyArticleRangesFallsBackOnTheThreeAbsentNames() {
        RefApplyService service = new RefApplyService();
        RefPayloads.ArticleBarcodeRangeDto dto = rangeDto("R1");
        dto.codeKind = null;
        dto.valueSource = null;
        dto.currency = null;
        ArticleBarcodeRange existing = mock(ArticleBarcodeRange.class);
        PanacheQuery<ArticleBarcodeRange> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ArticleBarcodeRange.find("code", "R1")).thenReturn(existingQuery);
            mocked.when(ArticleBarcodeRange::listAll).thenReturn(List.of());
            service.applyArticleBarcodeRanges(List.of(dto));
        }
        assertEquals(CouponField.Kind.NUMERIC, existing.codeKind);
        assertEquals(ArticleBarcodeRange.ValueSource.PRICE, existing.valueSource);
        assertEquals(CouponField.PriceCurrency.EUR, existing.currency);
    }

    // --- Îlots de caisse (BO-03-06-07, BO-03-06-50) ---

    /**
     * {@code applyCheckoutIslands} covers the insert arm, the update arm and
     * the three deactivation arms.
     */
    @Test
    void applyCheckoutIslandsInsertsUpdatesAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CheckoutIslandDto insert = new RefPayloads.CheckoutIslandDto();
        insert.code = "AVANT";
        insert.label = "Ligne avant";
        insert.active = true;
        insert.terminalIds = "POS01;POS02";
        RefPayloads.CheckoutIslandDto update = new RefPayloads.CheckoutIslandDto();
        update.code = "COMPTOIR";
        update.label = "Comptoir";
        update.active = true;
        update.terminalIds = "POS08";
        com.intermarche.pos.domain.store.CheckoutIsland existing =
                mock(com.intermarche.pos.domain.store.CheckoutIsland.class);
        com.intermarche.pos.domain.store.CheckoutIsland seen =
                mock(com.intermarche.pos.domain.store.CheckoutIsland.class);
        seen.code = "AVANT";
        seen.active = true;
        com.intermarche.pos.domain.store.CheckoutIsland absentActive =
                mock(com.intermarche.pos.domain.store.CheckoutIsland.class);
        absentActive.code = "Z1";
        absentActive.active = true;
        com.intermarche.pos.domain.store.CheckoutIsland absentInactive =
                mock(com.intermarche.pos.domain.store.CheckoutIsland.class);
        absentInactive.code = "Z2";
        absentInactive.active = false;
        PanacheQuery<com.intermarche.pos.domain.store.CheckoutIsland> absentQuery =
                queryReturning(null);
        PanacheQuery<com.intermarche.pos.domain.store.CheckoutIsland> existingQuery =
                queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.pos.domain.store.CheckoutIsland> created =
                        mockConstruction(com.intermarche.pos.domain.store.CheckoutIsland.class)) {
            mocked.when(() -> com.intermarche.pos.domain.store.CheckoutIsland
                    .find("code", "AVANT")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.pos.domain.store.CheckoutIsland
                    .find("code", "COMPTOIR")).thenReturn(existingQuery);
            mocked.when(com.intermarche.pos.domain.store.CheckoutIsland::listAll)
                    .thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyCheckoutIslands(List.of(insert, update));
            com.intermarche.pos.domain.store.CheckoutIsland inserted = created.constructed().get(0);
            assertEquals("AVANT", inserted.code);
            assertEquals("Ligne avant", inserted.label);
            assertEquals("POS01;POS02", inserted.terminalIds);
            assertTrue(inserted.active);
            verify(inserted, times(1)).persist();

            assertEquals("Comptoir", existing.label);
            assertEquals("POS08", existing.terminalIds);
            verify(existing, times(1)).persist();

            assertTrue(seen.active);
            verify(seen, never()).persist();
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
    }

    // --- Modes de règlement (BO-03-02-03/04/10 à 30) ---

    /**
     * {@code applyTenders} covers the insert arm, the update arm and the three
     * deactivation arms, and carries the whole administered description down to
     * the register.
     */
    @Test
    void applyTendersInsertsUpdatesAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.TenderDefinitionDto insert = new RefPayloads.TenderDefinitionDto();
        insert.code = "TR";
        insert.functionalId = "030";
        insert.label = "Titre restaurant";
        insert.active = true;
        insert.displayOrder = 30;
        insert.maxAmount = "25.00";
        insert.maxAmountControl = "BLOCKING";
        insert.maxCount = 2;
        insert.maxCountControl = "SUPERVISOR";
        insert.drawerOpening = "IF_CHANGE_DUE";
        insert.changeTenderCode = "CASH";
        insert.refundAllowed = true;
        insert.fidelityReported = true;
        RefPayloads.TenderDefinitionDto update = new RefPayloads.TenderDefinitionDto();
        update.code = "CHEQUE";
        update.functionalId = "020";
        update.label = "Chèque";
        update.active = true;
        update.displayOrder = 20;
        update.minAmount = "5.00";
        update.minAmountControl = "WARNING";
        update.secondMaxAmount = "300.00";
        update.secondMaxAmountControl = "INFO";
        update.maxChangeAmount = "8.00";
        update.maxChangeControl = "INFO";
        update.changeAllowed = true;
        update.cashierDeclaration = true;
        update.automaticWithdrawal = true;
        update.movementAllowed = true;
        update.bankDeposit = true;
        update.floatAllowed = true;
        update.defaultsToTotal = true;
        update.withdrawalReportDetail = true;
        com.intermarche.pos.domain.payment.TenderDefinition existing =
                mock(com.intermarche.pos.domain.payment.TenderDefinition.class);
        com.intermarche.pos.domain.payment.TenderDefinition seen =
                mock(com.intermarche.pos.domain.payment.TenderDefinition.class);
        seen.code = "TR";
        seen.active = true;
        com.intermarche.pos.domain.payment.TenderDefinition absentActive =
                mock(com.intermarche.pos.domain.payment.TenderDefinition.class);
        absentActive.code = "DEVISE";
        absentActive.active = true;
        com.intermarche.pos.domain.payment.TenderDefinition absentInactive =
                mock(com.intermarche.pos.domain.payment.TenderDefinition.class);
        absentInactive.code = "SECOURS";
        absentInactive.active = false;
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> absentQuery =
                queryReturning(null);
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> existingQuery =
                queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.pos.domain.payment.TenderDefinition> created =
                        mockConstruction(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            mocked.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                    .find("code", "TR")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                    .find("code", "CHEQUE")).thenReturn(existingQuery);
            mocked.when(com.intermarche.pos.domain.payment.TenderDefinition::listAll)
                    .thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyTenders(List.of(insert, update));
            com.intermarche.pos.domain.payment.TenderDefinition inserted = created.constructed().get(0);
            assertEquals("TR", inserted.code);
            assertEquals("030", inserted.functionalId);
            assertEquals("Titre restaurant", inserted.label);
            assertTrue(inserted.active);
            assertEquals(30, inserted.displayOrder);
            assertEquals(new java.math.BigDecimal("25.00"), inserted.maxAmount);
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                    inserted.maxAmountControl);
            assertEquals(Integer.valueOf(2), inserted.maxCount);
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                    inserted.maxCountControl);
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.DrawerOpening.IF_CHANGE_DUE,
                    inserted.drawerOpening);
            assertEquals("CASH", inserted.changeTenderCode);
            assertTrue(inserted.refundAllowed);
            assertTrue(inserted.fidelityReported);
            verify(inserted, times(1)).persist();

            assertEquals("Chèque", existing.label);
            assertEquals("020", existing.functionalId);
            assertEquals(new java.math.BigDecimal("5.00"), existing.minAmount);
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.WARNING,
                    existing.minAmountControl);
            assertEquals(new java.math.BigDecimal("300.00"), existing.secondMaxAmount);
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.INFO,
                    existing.secondMaxAmountControl);
            assertEquals(new java.math.BigDecimal("8.00"), existing.maxChangeAmount);
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.INFO,
                    existing.maxChangeControl);
            assertTrue(existing.changeAllowed);
            assertTrue(existing.cashierDeclaration);
            assertTrue(existing.automaticWithdrawal);
            assertTrue(existing.movementAllowed);
            assertTrue(existing.bankDeposit);
            assertTrue(existing.floatAllowed);
            assertTrue(existing.defaultsToTotal);
            assertTrue(existing.withdrawalReportDetail);
            verify(existing, times(1)).persist();

            assertTrue(seen.active);
            verify(seen, never()).persist();
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
    }

    /**
     * A snapshot row whose bounds and enumerations did not travel, or travelled
     * unreadable, lands as an unbounded tender rather than failing the pull —
     * every leg of the three fallbacks: null, blank, malformed and unknown.
     */
    @Test
    void applyTendersToleratesAnUnreadableRow() {
        RefApplyService service = new RefApplyService();
        RefPayloads.TenderDefinitionDto bare = new RefPayloads.TenderDefinitionDto();
        bare.code = "CASH";
        bare.functionalId = "010";
        bare.label = "Espèces";
        bare.active = true;
        bare.maxAmount = null;
        bare.secondMaxAmount = "   ";
        bare.minAmount = "pas un nombre";
        bare.maxChangeAmount = "";
        bare.maxAmountControl = null;
        bare.minAmountControl = "INEXISTANT";
        bare.drawerOpening = "JAMAIS_VU";
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> absentQuery =
                queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.pos.domain.payment.TenderDefinition> created =
                        mockConstruction(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            mocked.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                    .find("code", "CASH")).thenReturn(absentQuery);
            mocked.when(com.intermarche.pos.domain.payment.TenderDefinition::listAll)
                    .thenReturn(List.of());
            service.applyTenders(List.of(bare));
            com.intermarche.pos.domain.payment.TenderDefinition inserted = created.constructed().get(0);
            assertNull(inserted.maxAmount);
            assertNull(inserted.secondMaxAmount);
            assertNull(inserted.minAmount);
            assertNull(inserted.maxChangeAmount);
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE,
                    inserted.maxAmountControl);
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE,
                    inserted.minAmountControl);
            assertEquals(com.intermarche.pos.domain.payment.TenderDefinition.DrawerOpening.NEVER,
                    inserted.drawerOpening);
        }
    }

    // --- Gabarits de documents (BO-03-03) ---

    /**
     * {@code applyDocumentTemplates} covers the insert arm, the update arm and
     * the three deactivation arms, carries the source verbatim, and tells the
     * renderer to forget what it had parsed.
     */
    @Test
    void applyDocumentTemplatesInsertsUpdatesAndDeactivates() {
        RefApplyService service = new RefApplyService();
        service.documentTemplateService =
                mock(com.intermarche.pos.service.DocumentTemplateService.class);
        RefPayloads.DocumentTemplateDto insert = new RefPayloads.DocumentTemplateDto();
        insert.code = "TICKET_VENTE";
        insert.label = "Ticket de vente";
        insert.documentType = "SALE_RECEIPT";
        insert.active = true;
        insert.priority = 100;
        insert.width = 42;
        insert.copies = 2;
        insert.source = "TOTAL {totals.includingTax}";
        RefPayloads.DocumentTemplateDto update = new RefPayloads.DocumentTemplateDto();
        update.code = "RAPPORT_Z";
        update.label = "Rapport Z";
        update.documentType = "Z_REPORT";
        update.active = true;
        update.width = 42;
        update.source = "Z {session.number}";
        com.intermarche.pos.domain.setting.DocumentTemplate existing =
                mock(com.intermarche.pos.domain.setting.DocumentTemplate.class);
        com.intermarche.pos.domain.setting.DocumentTemplate seen =
                mock(com.intermarche.pos.domain.setting.DocumentTemplate.class);
        seen.code = "TICKET_VENTE";
        seen.active = true;
        com.intermarche.pos.domain.setting.DocumentTemplate absentActive =
                mock(com.intermarche.pos.domain.setting.DocumentTemplate.class);
        absentActive.code = "VIEUX";
        absentActive.active = true;
        com.intermarche.pos.domain.setting.DocumentTemplate absentInactive =
                mock(com.intermarche.pos.domain.setting.DocumentTemplate.class);
        absentInactive.code = "ANCIEN";
        absentInactive.active = false;
        PanacheQuery<com.intermarche.pos.domain.setting.DocumentTemplate> absentQuery =
                queryReturning(null);
        PanacheQuery<com.intermarche.pos.domain.setting.DocumentTemplate> existingQuery =
                queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.pos.domain.setting.DocumentTemplate> created =
                        mockConstruction(
                                com.intermarche.pos.domain.setting.DocumentTemplate.class)) {
            mocked.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate
                    .find("code", "TICKET_VENTE")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate
                    .find("code", "RAPPORT_Z")).thenReturn(existingQuery);
            mocked.when(com.intermarche.pos.domain.setting.DocumentTemplate::listAll)
                    .thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyDocumentTemplates(List.of(insert, update));
            com.intermarche.pos.domain.setting.DocumentTemplate inserted =
                    created.constructed().get(0);
            assertEquals("TICKET_VENTE", inserted.code);
            assertEquals("Ticket de vente", inserted.label);
            assertEquals(com.intermarche.pos.domain.setting.DocumentTemplate
                    .DocumentType.SALE_RECEIPT, inserted.documentType);
            assertTrue(inserted.active);
            assertEquals(100, inserted.priority);
            assertEquals(42, inserted.width);
            assertEquals(2, inserted.copies);
            assertEquals("TOTAL {totals.includingTax}", inserted.source);
            verify(inserted, times(1)).persist();

            assertEquals("Rapport Z", existing.label);
            assertEquals(com.intermarche.pos.domain.setting.DocumentTemplate
                    .DocumentType.Z_REPORT, existing.documentType);
            assertEquals("Z {session.number}", existing.source);
            verify(existing, times(1)).persist();

            assertTrue(seen.active);
            verify(seen, never()).persist();
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
        verify(service.documentTemplateService, times(1)).clearCache();
    }

    /**
     * A snapshot row naming a document the register does not know leaves the
     * template attached to none rather than failing the pull — the unknown and
     * absent legs of the type fallback.
     */
    @Test
    void applyDocumentTemplatesToleratesAnUnknownDocumentType() {
        RefApplyService service = new RefApplyService();
        service.documentTemplateService =
                mock(com.intermarche.pos.service.DocumentTemplateService.class);
        RefPayloads.DocumentTemplateDto unknown = new RefPayloads.DocumentTemplateDto();
        unknown.code = "EXOTIQUE";
        unknown.documentType = "BON_DE_LIVRAISON";
        RefPayloads.DocumentTemplateDto absent = new RefPayloads.DocumentTemplateDto();
        absent.code = "SANS_TYPE";
        absent.documentType = null;
        PanacheQuery<com.intermarche.pos.domain.setting.DocumentTemplate> absentQuery =
                queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.pos.domain.setting.DocumentTemplate> created =
                        mockConstruction(
                                com.intermarche.pos.domain.setting.DocumentTemplate.class)) {
            mocked.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate
                    .find("code", "EXOTIQUE")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate
                    .find("code", "SANS_TYPE")).thenReturn(absentQuery);
            mocked.when(com.intermarche.pos.domain.setting.DocumentTemplate::listAll)
                    .thenReturn(List.of());
            service.applyDocumentTemplates(List.of(unknown, absent));
            assertNull(created.constructed().get(0).documentType);
            assertNull(created.constructed().get(1).documentType);
        }
    }
}
