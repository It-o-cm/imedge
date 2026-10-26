package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.payment.AccountCustomer;
import com.intermarche.edge.domain.store.Address;
import com.intermarche.edge.domain.barcode.AlertLevel;
import com.intermarche.edge.domain.barcode.ArticleBarcodeRange;
import com.intermarche.edge.domain.barcode.CouponControl;
import com.intermarche.edge.domain.barcode.CouponField;
import com.intermarche.edge.domain.barcode.CouponType;
import com.intermarche.edge.domain.payment.Currency;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sync.EngineFeed;
import com.intermarche.edge.domain.store.CheckoutIsland;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.EchelonSetting;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.setting.PosSetting;
import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.catalog.ProductType;
import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.sync.RefState;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.domain.setting.TouchGroupSetting;
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
        // BO-03-01-21/-22/-23: the scope descends with the group.
        insert.islandCodes = "AVANT";
        insert.terminalIds = "C04;C05";
        insert.tpvTypes = "CASH_REGISTER";
        // BO-03-01-19: and so do the translated wordings.
        insert.labels = "nl=Zuivel";
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
            assertEquals("AVANT", inserted.islandCodes);
            assertEquals("C04;C05", inserted.terminalIds);
            assertEquals("CASH_REGISTER", inserted.tpvTypes);
            assertEquals("nl=Zuivel", inserted.labels);
            assertEquals("LARGE", existing.buttonSize);
            assertEquals(7, existing.displayOrder);
            assertEquals(5L, existing.salesVolume);
            // The snapshot scopes F2 nowhere: it goes back to being drawn
            // everywhere, which is what a group with no scope has always been.
            assertNull(existing.islandCodes);
            assertNull(existing.terminalIds);
            assertNull(existing.tpvTypes);
            // The snapshot names no wording for F2 either: the stale one goes.
            assertNull(existing.labels);
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
        insert.salesVolume = 125L;
        insert.saleCodes = List.of("S1", "S2");
        RefPayloads.ProductDto update = new RefPayloads.ProductDto();
        update.ean = "E2";
        update.productType = null;
        update.active = true;
        // Null attributes on the update DTO covers the null-map arm of applyProducts.
        update.attributes = null;
        update.saleCodes = null;
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
            assertEquals(125L, inserted.salesVolume);
            assertEquals(java.util.Set.of("S1", "S2"), inserted.saleCodes);
            verify(inserted, times(1)).persist();
            assertNull(existing.productType);
            assertTrue(existing.attributes.isEmpty());
            assertTrue(existing.saleCodes.isEmpty());
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
     * BO-03-01-13: on a STORE node the sales volume is the store's own
     * computation, so a head office snapshot never overwrites it.
     */
    @Test
    void applyProductsKeepsTheStoresSalesVolumeOnAStoreNode() {
        RefApplyService service = new RefApplyService();
        service.role = "store";
        RefPayloads.ProductDto update = new RefPayloads.ProductDto();
        update.ean = "E2";
        update.active = true;
        update.salesVolume = 0L;
        Product existing = mock(Product.class);
        existing.salesVolume = 42L;
        PanacheQuery<Product> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Product.find("ean", "E2")).thenReturn(existingQuery);
            mocked.when(Product::listAll).thenReturn(List.of());
            service.applyProducts(List.of(update));
            assertEquals(42L, existing.salesVolume);
            verify(existing, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // applyVatRates
    // --------------------------------------------------

    /**
     * Stubs the regime a number names, for the upsert of {@code applyVatRates}.
     *
     * @param mocked the active static mock
     * @param number the number
     * @param regime the regime held, or null
     */
    @SuppressWarnings("unchecked")
    private void stubRegime(MockedStatic<PanacheEntityBase> mocked, int number, VatRate regime) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<VatRate> query =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.firstResult()).thenReturn(regime);
        mocked.when(() -> VatRate.find("number", number)).thenReturn(query);
    }

    /**
     * {@code applyVatRates} upserts by number (BO-02-02-01): an unknown number
     * is created with every value of its row, exemption included; a known one
     * is updated in place, its prices still pointing at it; a regime the
     * snapshot no longer carries detaches its own prices and goes.
     */
    @Test
    void applyVatRatesUpsertsByNumber() {
        RefApplyService service = new RefApplyService();
        RefPayloads.VatRateDto normal = new RefPayloads.VatRateDto();
        normal.number = 1;
        normal.rate = new BigDecimal("0.2000");
        normal.label = "Taux normal";
        normal.country = "FR";
        RefPayloads.VatRateDto exempt = new RefPayloads.VatRateDto();
        exempt.number = 5;
        exempt.rate = new BigDecimal("0.0000");
        exempt.exempt = true;
        exempt.exemptionCode = "M07";
        exempt.legalMention = "Isento";
        VatRate held = new VatRate(1, new BigDecimal("0.1960"), "Ancien");
        VatRate stale = new VatRate(9, new BigDecimal("0.0700"), "Disparu");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<VatRate> created = mockConstruction(VatRate.class)) {
            stubRegime(mocked, 1, held);
            stubRegime(mocked, 5, null);
            VatRate staleMock = mock(VatRate.class);
            staleMock.number = 9;
            mocked.when(PanacheEntityBase::listAll).thenReturn(List.of(held, staleMock));
            service.applyVatRates(List.of(normal, exempt));
            assertEquals(new BigDecimal("0.2000"), held.rate);
            assertEquals("Taux normal", held.label);
            assertEquals("FR", held.country);
            assertFalse(held.exempt);
            assertEquals(1, created.constructed().size());
            VatRate fresh = created.constructed().get(0);
            assertEquals(5, fresh.number);
            assertTrue(fresh.exempt);
            assertEquals("M07", fresh.exemptionCode);
            assertEquals("Isento", fresh.legalMention);
            verify(fresh, times(1)).persist();
            mocked.verify(() -> Price.update("set vat = null where vat = ?1", staleMock), times(1));
            verify(staleMock, times(1)).delete();
            mocked.verify(() -> Price.update("set vat = null where vat is not null"), never());
            assertEquals("Disparu", stale.label);
        }
    }

    /**
     * An empty snapshot removes every regime, each detaching its prices: a
     * referential emptied at the store node empties on the register too.
     */
    @Test
    void applyVatRatesOnAnEmptySnapshotRemovesEverything() {
        RefApplyService service = new RefApplyService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<VatRate> created = mockConstruction(VatRate.class)) {
            VatRate gone = mock(VatRate.class);
            gone.number = 3;
            mocked.when(PanacheEntityBase::listAll).thenReturn(List.of(gone));
            service.applyVatRates(List.of());
            verify(gone, times(1)).delete();
            assertEquals(0, created.constructed().size());
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
        priced.vatNumber = 1;
        priced.priority = 5;
        priced.startDateTime = "2026-01-01T10:00:00";
        priced.endDateTime = null;
        RefPayloads.PriceDto defaulted = new RefPayloads.PriceDto();
        defaulted.productEan = "E2";
        defaulted.priceExcludingTax = new BigDecimal("2.00");
        defaulted.priceIncludingTax = new BigDecimal("2.20");
        defaulted.vatNumber = null;
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
        VatRate regime = new VatRate();
        regime.number = 1;
        regime.rate = new BigDecimal("0.2000");
        PanacheQuery<VatRate> qRegime = queryReturning(regime);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Price> created = mockConstruction(Price.class)) {
            mocked.when(() -> Product.find("ean", "E1")).thenReturn(q1);
            mocked.when(() -> Product.find("ean", "E2")).thenReturn(q2);
            mocked.when(() -> Product.find("ean", "E9")).thenReturn(qOrphan);
            mocked.when(() -> VatRate.find("number", 1)).thenReturn(qRegime);
            service.applyPrices(List.of(priced, defaulted, nullEan, orphan));
            mocked.verify(Price::deleteAll, times(1));
            assertEquals(2, created.constructed().size());
            Price first = created.constructed().get(0);
            assertSame(p1, first.product);
            assertSame(regime, first.vat);
            assertEquals(5, first.priority);
            assertEquals(LocalDateTime.of(2026, 1, 1, 10, 0, 0), first.startDateTime);
            assertNull(first.endDateTime);
            verify(first, times(1)).persist();
            Price second = created.constructed().get(1);
            assertSame(p2, second.product);
            assertNull(second.vat);
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
        // BO-03-06-51: the supervisor card descends with the account.
        insert.supervisorCard = true;
        // BO-01-02-07: so does the Dallas key.
        insert.dallasKey = "01A2B3C4D5E6F708";
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
            mocked.when(() -> Employee.find("dallasKey", "01A2B3C4D5E6F708")).thenReturn(absentQuery);
            service.applyEmployees(List.of(insert, update));
            Employee inserted = created.constructed().get(0);
            assertEquals("01A2B3C4D5E6F708", inserted.dallasKey);
            assertEquals("alice", inserted.loginName);
            assertEquals(Employee.EmployeeRole.CASHIER, inserted.role);
            assertEquals("dark", inserted.theme);
            assertTrue(inserted.supervisorCard);
            verify(inserted, times(1)).persist();
            assertFalse(existing.supervisorCard);
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

    /**
     * BO-01-02-07: a pulled or distributed Dallas key already held by another
     * employee or account of this node is left off; the holder itself, a fresh
     * holder and an employee with no key keep theirs.
     */
    @Test
    void aDallasKeyHeldElsewhereIsLeftOff() {
        RefApplyService service = new RefApplyService();
        Employee other = new Employee();
        other.id = 2L;
        other.loginName = "other";
        Employee self = new Employee();
        self.id = 2L;
        Employee newcomer = new Employee();
        Employee stranger = new Employee();
        stranger.id = 3L;
        PanacheQuery<Employee> held = queryReturning(other);
        PanacheQuery<Employee> free = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.find("dallasKey", "01A2B3C4D5E6F708")).thenReturn(held);
            mocked.when(() -> Employee.find("dallasKey", "FFFFFFFFFFFFFFFF")).thenReturn(free);
            assertNull(service.dallasKeyFor(stranger, "stranger", "01A2B3C4D5E6F708"));
            assertNull(service.dallasKeyFor(newcomer, "newcomer", "01A2B3C4D5E6F708"));
            assertEquals("01A2B3C4D5E6F708", service.dallasKeyFor(self, "other", "01A2B3C4D5E6F708"));
            assertEquals("FFFFFFFFFFFFFFFF", service.dallasKeyFor(newcomer, "newcomer", "FFFFFFFFFFFFFFFF"));
            assertNull(service.dallasKeyFor(newcomer, "newcomer", null));
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
        // BO-03-06-08 and -30: the tender binding and the issuing kind descend.
        insert.tenderCode = "TR";
        insert.issuedKind = "CREDIT_NOTE";
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
            assertEquals("TR", inserted.tenderCode);
            assertEquals("CREDIT_NOTE", inserted.issuedKind);
            assertNull(existing.tenderCode);
            assertNull(existing.issuedKind);
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
        service.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
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
        service.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
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
            insert.baseFingerprint = "b1";
            insert.resultFingerprint = "r1";
            same.baseFingerprint = "b3";
            same.resultFingerprint = "r3";
            service.applyEngineFeeds(List.of(insert, differs, same, nullVersion));
            // BO-02-03-33: the stamps descend with a new file and on an unchanged one.
            assertEquals("b1", created.constructed().get(0).baseFingerprint);
            assertEquals("r1", created.constructed().get(0).resultFingerprint);
            assertEquals("b3", existingSame.baseFingerprint);
            assertEquals("r3", existingSame.resultFingerprint);
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
        // BO-03-02-39: the change tender descends verbatim, empty included.
        insert.changeTenderCode = "ESP";
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
            assertEquals("ESP", usd.changeTenderCode);
            verify(usd, times(1)).persist();
            assertEquals(BigDecimal.ONE, existing.euroPerUnit);
            assertNull(existing.changeTenderCode);
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
        // BO-03-06-56/-60/-61: the destination descends verbatim, and a range
        // from an older node stays an ARTICLE range.
        insert.purpose = "REFUND";
        // BO-03-06-43: the promotion a range names descends with it, and a
        // snapshot naming none leaves the range triggering nothing.
        insert.promotionCode = "PROMO_RENTREE";
        insert.lookupUrl = "https://partenaire.test/bons/{code}";
        // BO-03-06-59: and so does the register function it calls.
        insert.functionCode = "SURVEY";
        // BO-03-06-43 / -41: the promotion definition and the partner mapping.
        insert.promotionType = "PERCENT";
        insert.lookupField = "statut.message";
        ArticleBarcodeRange existing = mock(ArticleBarcodeRange.class);
        existing.functionCode = "METROLOGY";
        existing.promotionType = "AMOUNT";
        existing.lookupField = "ancien";
        existing.purpose = ArticleBarcodeRange.Purpose.TARE;
        existing.promotionCode = "PROMO_PERIMEE";
        existing.lookupUrl = "https://ancien.test/?c=";
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
            assertEquals(ArticleBarcodeRange.Purpose.REFUND, inserted.purpose);
            assertEquals("PROMO_RENTREE", inserted.promotionCode);
            assertEquals("https://partenaire.test/bons/{code}", inserted.lookupUrl);
            assertEquals("SURVEY", inserted.functionCode);
            assertNull(existing.functionCode);
            assertEquals("PERCENT", inserted.promotionType);
            assertEquals("statut.message", inserted.lookupField);
            assertNull(existing.promotionType);
            assertNull(existing.lookupField);
            assertEquals("^21\\d{11}$", inserted.matchPattern);
            verify(inserted, times(1)).persist();
            // The snapshot names no destination for R2: it goes back to ARTICLE,
            // which is what every range was before the column existed.
            assertEquals(ArticleBarcodeRange.Purpose.ARTICLE, existing.purpose);
            // The snapshot names no promotion either: the stale one goes away
            // rather than lingering on a range the back office changed.
            assertNull(existing.promotionCode);
            assertNull(existing.lookupUrl);
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
     * {@code applyCheckoutIslands} covers the insert arm, the update arm and the
     * three deactivation arms, keeps the effective {@code terminalIds} string and
     * rebuilds the register relation as checkout points from it.
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
        com.intermarche.edge.domain.store.CheckoutIsland existing =
                mock(com.intermarche.edge.domain.store.CheckoutIsland.class);
        com.intermarche.edge.domain.store.CheckoutIsland seen =
                mock(com.intermarche.edge.domain.store.CheckoutIsland.class);
        seen.code = "AVANT";
        seen.active = true;
        com.intermarche.edge.domain.store.CheckoutIsland absentActive =
                mock(com.intermarche.edge.domain.store.CheckoutIsland.class);
        absentActive.code = "Z1";
        absentActive.active = true;
        com.intermarche.edge.domain.store.CheckoutIsland absentInactive =
                mock(com.intermarche.edge.domain.store.CheckoutIsland.class);
        absentInactive.code = "Z2";
        absentInactive.active = false;
        PanacheQuery<com.intermarche.edge.domain.store.CheckoutIsland> absentQuery =
                queryReturning(null);
        PanacheQuery<com.intermarche.edge.domain.store.CheckoutIsland> existingQuery =
                queryReturning(existing);
        PanacheQuery<com.intermarche.edge.domain.store.CheckoutPoint> noPoint =
                queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.store.CheckoutIsland> created =
                        mockConstruction(com.intermarche.edge.domain.store.CheckoutIsland.class);
                MockedConstruction<com.intermarche.edge.domain.store.CheckoutPoint> points =
                        mockConstruction(com.intermarche.edge.domain.store.CheckoutPoint.class)) {
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutIsland
                    .find("code", "AVANT")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutIsland
                    .find("code", "COMPTOIR")).thenReturn(existingQuery);
            mocked.when(com.intermarche.edge.domain.store.CheckoutIsland::listAll)
                    .thenReturn(List.of(seen, absentActive, absentInactive));
            // reconstruction: every listed terminal is new, no stale point remains
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("terminalId", "POS01")).thenReturn(noPoint);
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("terminalId", "POS02")).thenReturn(noPoint);
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("terminalId", "POS08")).thenReturn(noPoint);
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .list("islandCode = ?1", "AVANT")).thenReturn(List.of());
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .list("islandCode = ?1", "COMPTOIR")).thenReturn(List.of());
            service.applyCheckoutIslands(List.of(insert, update));
            com.intermarche.edge.domain.store.CheckoutIsland inserted = created.constructed().get(0);
            assertEquals("AVANT", inserted.code);
            assertEquals("Ligne avant", inserted.label);
            assertEquals("POS01;POS02", inserted.terminalIds);
            assertTrue(inserted.active);
            verify(inserted, times(1)).persist();
            assertEquals("Comptoir", existing.label);
            assertEquals("POS08", existing.terminalIds);
            verify(existing, times(1)).persist();
            // three registers rebuilt as checkout points (POS01, POS02, POS08)
            assertEquals(3, points.constructed().size());
            assertEquals("POS01", points.constructed().get(0).terminalId);
            assertEquals("AVANT", points.constructed().get(0).islandCode);
            assertTrue(points.constructed().get(0).active);
            verify(points.constructed().get(0), times(1)).persist();
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
        // BO-03-09-30: the VIC product code descends verbatim, and a snapshot
        // naming none leaves the tender carrying nothing.
        insert.vicProductCode = "{DAJ}01";
        // BO-03-02-27/-29: the two self-service rules descend as well.
        insert.selfServiceAllowed = false;
        insert.selfServiceSupervision = true;
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
        com.intermarche.edge.domain.payment.TenderDefinition existing =
                mock(com.intermarche.edge.domain.payment.TenderDefinition.class);
        com.intermarche.edge.domain.payment.TenderDefinition seen =
                mock(com.intermarche.edge.domain.payment.TenderDefinition.class);
        seen.code = "TR";
        seen.active = true;
        com.intermarche.edge.domain.payment.TenderDefinition absentActive =
                mock(com.intermarche.edge.domain.payment.TenderDefinition.class);
        absentActive.code = "DEVISE";
        absentActive.active = true;
        com.intermarche.edge.domain.payment.TenderDefinition absentInactive =
                mock(com.intermarche.edge.domain.payment.TenderDefinition.class);
        absentInactive.code = "SECOURS";
        absentInactive.active = false;
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> absentQuery =
                queryReturning(null);
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> existingQuery =
                queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.payment.TenderDefinition> created =
                        mockConstruction(com.intermarche.edge.domain.payment.TenderDefinition.class)) {
            mocked.when(() -> com.intermarche.edge.domain.payment.TenderDefinition
                    .find("code", "TR")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.edge.domain.payment.TenderDefinition
                    .find("code", "CHEQUE")).thenReturn(existingQuery);
            mocked.when(com.intermarche.edge.domain.payment.TenderDefinition::listAll)
                    .thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyTenders(List.of(insert, update));
            com.intermarche.edge.domain.payment.TenderDefinition inserted = created.constructed().get(0);
            assertEquals("TR", inserted.code);
            assertEquals("030", inserted.functionalId);
            assertEquals("Titre restaurant", inserted.label);
            assertTrue(inserted.active);
            assertEquals(30, inserted.displayOrder);
            assertEquals(new java.math.BigDecimal("25.00"), inserted.maxAmount);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                    inserted.maxAmountControl);
            assertEquals(Integer.valueOf(2), inserted.maxCount);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                    inserted.maxCountControl);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.DrawerOpening.IF_CHANGE_DUE,
                    inserted.drawerOpening);
            assertEquals("CASH", inserted.changeTenderCode);
            assertEquals("{DAJ}01", inserted.vicProductCode);
            assertFalse(inserted.selfServiceAllowed);
            assertTrue(inserted.selfServiceSupervision);
            assertTrue(inserted.refundAllowed);
            assertTrue(inserted.fidelityReported);
            verify(inserted, times(1)).persist();
            assertEquals("Chèque", existing.label);
            assertEquals("020", existing.functionalId);
            assertEquals(new java.math.BigDecimal("5.00"), existing.minAmount);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.WARNING,
                    existing.minAmountControl);
            assertEquals(new java.math.BigDecimal("300.00"), existing.secondMaxAmount);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.INFO,
                    existing.secondMaxAmountControl);
            assertEquals(new java.math.BigDecimal("8.00"), existing.maxChangeAmount);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.INFO,
                    existing.maxChangeControl);
            assertNull(existing.vicProductCode);
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
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> absentQuery =
                queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.payment.TenderDefinition> created =
                        mockConstruction(com.intermarche.edge.domain.payment.TenderDefinition.class)) {
            mocked.when(() -> com.intermarche.edge.domain.payment.TenderDefinition
                    .find("code", "CASH")).thenReturn(absentQuery);
            mocked.when(com.intermarche.edge.domain.payment.TenderDefinition::listAll)
                    .thenReturn(List.of());
            service.applyTenders(List.of(bare));
            com.intermarche.edge.domain.payment.TenderDefinition inserted = created.constructed().get(0);
            assertNull(inserted.maxAmount);
            assertNull(inserted.secondMaxAmount);
            assertNull(inserted.minAmount);
            assertNull(inserted.maxChangeAmount);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.NONE,
                    inserted.maxAmountControl);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.NONE,
                    inserted.minAmountControl);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.DrawerOpening.NEVER,
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
                mock(com.intermarche.edge.service.DocumentTemplateService.class);
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
        com.intermarche.edge.domain.setting.DocumentTemplate existing =
                mock(com.intermarche.edge.domain.setting.DocumentTemplate.class);
        com.intermarche.edge.domain.setting.DocumentTemplate seen =
                mock(com.intermarche.edge.domain.setting.DocumentTemplate.class);
        seen.code = "TICKET_VENTE";
        seen.active = true;
        com.intermarche.edge.domain.setting.DocumentTemplate absentActive =
                mock(com.intermarche.edge.domain.setting.DocumentTemplate.class);
        absentActive.code = "VIEUX";
        absentActive.active = true;
        com.intermarche.edge.domain.setting.DocumentTemplate absentInactive =
                mock(com.intermarche.edge.domain.setting.DocumentTemplate.class);
        absentInactive.code = "ANCIEN";
        absentInactive.active = false;
        PanacheQuery<com.intermarche.edge.domain.setting.DocumentTemplate> absentQuery =
                queryReturning(null);
        PanacheQuery<com.intermarche.edge.domain.setting.DocumentTemplate> existingQuery =
                queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.setting.DocumentTemplate> created =
                        mockConstruction(
                                com.intermarche.edge.domain.setting.DocumentTemplate.class)) {
            mocked.when(() -> com.intermarche.edge.domain.setting.DocumentTemplate
                    .find("code", "TICKET_VENTE")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.edge.domain.setting.DocumentTemplate
                    .find("code", "RAPPORT_Z")).thenReturn(existingQuery);
            mocked.when(com.intermarche.edge.domain.setting.DocumentTemplate::listAll)
                    .thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyDocumentTemplates(List.of(insert, update));
            com.intermarche.edge.domain.setting.DocumentTemplate inserted =
                    created.constructed().get(0);
            assertEquals("TICKET_VENTE", inserted.code);
            assertEquals("Ticket de vente", inserted.label);
            assertEquals(com.intermarche.edge.domain.setting.DocumentTemplate
                    .DocumentType.SALE_RECEIPT, inserted.documentType);
            assertTrue(inserted.active);
            assertEquals(100, inserted.priority);
            assertEquals(42, inserted.width);
            assertEquals(2, inserted.copies);
            assertEquals("TOTAL {totals.includingTax}", inserted.source);
            verify(inserted, times(1)).persist();
            assertEquals("Rapport Z", existing.label);
            assertEquals(com.intermarche.edge.domain.setting.DocumentTemplate
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
                mock(com.intermarche.edge.service.DocumentTemplateService.class);
        RefPayloads.DocumentTemplateDto unknown = new RefPayloads.DocumentTemplateDto();
        unknown.code = "EXOTIQUE";
        unknown.documentType = "BON_DE_LIVRAISON";
        RefPayloads.DocumentTemplateDto absent = new RefPayloads.DocumentTemplateDto();
        absent.code = "SANS_TYPE";
        absent.documentType = null;
        PanacheQuery<com.intermarche.edge.domain.setting.DocumentTemplate> absentQuery =
                queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.setting.DocumentTemplate> created =
                        mockConstruction(
                                com.intermarche.edge.domain.setting.DocumentTemplate.class)) {
            mocked.when(() -> com.intermarche.edge.domain.setting.DocumentTemplate
                    .find("code", "EXOTIQUE")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.edge.domain.setting.DocumentTemplate
                    .find("code", "SANS_TYPE")).thenReturn(absentQuery);
            mocked.when(com.intermarche.edge.domain.setting.DocumentTemplate::listAll)
                    .thenReturn(List.of());
            service.applyDocumentTemplates(List.of(unknown, absent));
            assertNull(created.constructed().get(0).documentType);
            assertNull(created.constructed().get(1).documentType);
        }
    }

    // --------------------------------------------------
    // applyTouchGroups — the seen-contains arm of the deletion sweep
    // --------------------------------------------------

    /**
     * Covers the false arm of the {@code applyTouchGroups} deletion guard
     * ({@code !seen.contains(...)}): a stored setting whose family code is still
     * carried by the snapshot survives the sweep untouched — it is upserted, not
     * deleted, so a group the back office still administers keeps its touch.
     */
    @Test
    void applyTouchGroupsKeepsRowsStillInTheSnapshot() {
        RefApplyService service = new RefApplyService();
        RefPayloads.TouchGroupDto dto = new RefPayloads.TouchGroupDto();
        dto.familyCode = "F1";
        dto.pinned = false;
        dto.buttonSize = "NORMAL";
        dto.displayOrder = 1;
        dto.salesVolume = 0L;
        TouchGroupSetting kept = mock(TouchGroupSetting.class);
        kept.familyCode = "F1";
        PanacheQuery<TouchGroupSetting> existingQuery = queryReturning(kept);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TouchGroupSetting.find("familyCode", "F1")).thenReturn(existingQuery);
            mocked.when(() -> TouchGroupSetting.listAll()).thenReturn(List.of(kept));
            service.applyTouchGroups(List.of(dto));
            assertEquals("NORMAL", kept.buttonSize);
            assertEquals(1, kept.displayOrder);
            verify(kept, never()).delete();
        }
    }

    // --------------------------------------------------
    // feedContent
    // --------------------------------------------------

    /**
     * Covers both arms of the {@code feedContent} ternary
     * ({@code feed == null ? null : feed.content}): a feed this register holds
     * returns its raw content, and a domain it holds no feed for returns null.
     */
    @Test
    void feedContentReturnsRawWhenHeldAndNullOtherwise() {
        RefApplyService service = new RefApplyService();
        EngineFeed feed = mock(EngineFeed.class);
        feed.content = "ean;plu\n3001;100";
        PanacheQuery<EngineFeed> presentQuery = queryReturning(feed);
        PanacheQuery<EngineFeed> absentQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> EngineFeed.find("code", "PRODUCTS")).thenReturn(presentQuery);
            mocked.when(() -> EngineFeed.find("code", "ABSENT")).thenReturn(absentQuery);
            assertEquals("ean;plu\n3001;100", service.feedContent("PRODUCTS"));
            assertNull(service.feedContent("ABSENT"));
        }
    }

    // --------------------------------------------------
    // applyFromFile
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyFromFile}: the three served domains route
     * to their importer and return its non-null answer (switch PRODUCTS /
     * FAMILIES / PRICES arms, {@code answer == null} false arm), while an
     * unserved domain falls to the switch default and returns null (default arm,
     * {@code answer == null} true arm).
     */
    @Test
    void applyFromFileRoutesEachDomainAndReturnsNullForUnknown() {
        RefApplyService service = new RefApplyService();
        service.productImporter = mock(com.intermarche.edge.imports.ProductCsvResource.class);
        service.familyImporter = mock(com.intermarche.edge.imports.ProductFamilyCsvResource.class);
        service.priceImporter = mock(com.intermarche.edge.imports.PriceCsvResource.class);
        jakarta.ws.rs.core.Response productAnswer = mock(jakarta.ws.rs.core.Response.class);
        jakarta.ws.rs.core.Response familyAnswer = mock(jakarta.ws.rs.core.Response.class);
        jakarta.ws.rs.core.Response priceAnswer = mock(jakarta.ws.rs.core.Response.class);
        when(productAnswer.getEntity()).thenReturn("42 produit(s)");
        when(familyAnswer.getEntity()).thenReturn("7 famille(s)");
        when(priceAnswer.getEntity()).thenReturn("3 prix");
        when(service.productImporter.importProducts(org.mockito.ArgumentMatchers.any()))
                .thenReturn(productAnswer);
        when(service.familyImporter.importProductFamilies(org.mockito.ArgumentMatchers.any()))
                .thenReturn(familyAnswer);
        when(service.priceImporter.importPrices(org.mockito.ArgumentMatchers.any()))
                .thenReturn(priceAnswer);
        assertSame(productAnswer, service.applyFromFile("PRODUCTS", "ean;plu\n3001;100"));
        assertSame(familyAnswer, service.applyFromFile("FAMILIES", "code\nF1"));
        assertSame(priceAnswer, service.applyFromFile("PRICES", "ean;price\n3001;1.20"));
        assertNull(service.applyFromFile("STOCKS", "whatever"));
    }

    // --------------------------------------------------
    // applyPostalCodes
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyPostalCodes} and, through it, both
     * ternaries of {@code pairOf}: an unknown pair is inserted (row-null true
     * arm) with a non-null code and place (both {@code pairOf} false arms), a
     * known pair is updated (row-null false arm) whose null code and null place
     * exercise both {@code pairOf} true arms, then the deletion sweep keeps a
     * row still named by the snapshot (seen-contains false arm) and deletes one
     * the snapshot dropped (seen-contains true arm).
     */
    @Test
    void applyPostalCodesUpsertsByPairAndDeletesAbsent() {
        RefApplyService service = new RefApplyService();
        RefPayloads.PostalCodeDto insert = new RefPayloads.PostalCodeDto();
        insert.code = "69001";
        insert.place = "Lyon";
        insert.country = "FR";
        RefPayloads.PostalCodeDto update = new RefPayloads.PostalCodeDto();
        update.code = null;
        update.place = null;
        update.country = "PT";
        com.intermarche.edge.domain.store.PostalCode existing =
                mock(com.intermarche.edge.domain.store.PostalCode.class);
        com.intermarche.edge.domain.store.PostalCode kept =
                mock(com.intermarche.edge.domain.store.PostalCode.class);
        kept.code = "69001";
        kept.place = "Lyon";
        com.intermarche.edge.domain.store.PostalCode stale =
                mock(com.intermarche.edge.domain.store.PostalCode.class);
        stale.code = "99999";
        stale.place = "Gone";
        PanacheQuery<com.intermarche.edge.domain.store.PostalCode> absentQuery = queryReturning(null);
        PanacheQuery<com.intermarche.edge.domain.store.PostalCode> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.store.PostalCode> created =
                        mockConstruction(com.intermarche.edge.domain.store.PostalCode.class)) {
            mocked.when(() -> com.intermarche.edge.domain.store.PostalCode
                    .find("code = ?1 and place = ?2", "69001", "Lyon")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.edge.domain.store.PostalCode
                    .find("code = ?1 and place = ?2", (Object) null, (Object) null)).thenReturn(existingQuery);
            mocked.when(com.intermarche.edge.domain.store.PostalCode::listAll)
                    .thenReturn(List.of(kept, stale));
            service.applyPostalCodes(List.of(insert, update));
            com.intermarche.edge.domain.store.PostalCode inserted = created.constructed().get(0);
            assertEquals("69001", inserted.code);
            assertEquals("Lyon", inserted.place);
            assertEquals("FR", inserted.country);
            verify(inserted, times(1)).persist();
            assertEquals("PT", existing.country);
            verify(existing, times(1)).persist();
            verify(kept, never()).delete();
            verify(stale, times(1)).delete();
        }
    }

    // --------------------------------------------------
    // applyCustomers — origin, date and free-field branches
    // --------------------------------------------------

    /**
     * Covers the arms of {@code applyCustomers} and its helpers untouched by the
     * insert/update test: an existing customer whose free-field list is already
     * non-null (address and free-field-null false arms) receives a rebuilt
     * reserve from a non-null snapshot list ({@code dto.freeFields != null} true
     * arm, one iterated field). Across three rows the {@code origin} helper sees
     * a readable name (valueOf success), a blank string (isBlank true arm) and
     * an unknown name (IllegalArgumentException catch), and the {@code date}
     * helper sees a readable ISO date, a blank string and a malformed one
     * (DateTimeParseException catch) — the null-false and isBlank-false arms of
     * both guards included.
     */
    @Test
    void applyCustomersCoversOriginDateAndFreeFieldBranches() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CustomerDto valid = customerDto("ACC1");
        valid.origin = "INTEGRATED";
        valid.dueDate = "2026-06-15";
        RefPayloads.FreeFieldDto field = new RefPayloads.FreeFieldDto();
        field.slot = 3;
        field.label = "Contrat";
        field.value = "V-99";
        valid.freeFields = new java.util.ArrayList<>(List.of(field));
        RefPayloads.CustomerDto blank = customerDto("ACC2");
        blank.origin = "   ";
        blank.dueDate = "   ";
        blank.freeFields = null;
        RefPayloads.CustomerDto bad = customerDto("ACC3");
        bad.origin = "MARS";
        bad.dueDate = "32/13/2026";
        bad.freeFields = null;
        AccountCustomer c1 = mock(AccountCustomer.class);
        c1.address = new Address();
        c1.freeFields = new java.util.ArrayList<>();
        AccountCustomer c2 = mock(AccountCustomer.class);
        c2.address = new Address();
        c2.freeFields = new java.util.ArrayList<>();
        AccountCustomer c3 = mock(AccountCustomer.class);
        c3.address = new Address();
        c3.freeFields = new java.util.ArrayList<>();
        PanacheQuery<AccountCustomer> q1 = queryReturning(c1);
        PanacheQuery<AccountCustomer> q2 = queryReturning(c2);
        PanacheQuery<AccountCustomer> q3 = queryReturning(c3);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> AccountCustomer.find("accountNumber", "ACC1")).thenReturn(q1);
            mocked.when(() -> AccountCustomer.find("accountNumber", "ACC2")).thenReturn(q2);
            mocked.when(() -> AccountCustomer.find("accountNumber", "ACC3")).thenReturn(q3);
            service.applyCustomers(List.of(valid, blank, bad));
            assertEquals(AccountCustomer.Origin.INTEGRATED, c1.origin);
            assertEquals(LocalDate.of(2026, 6, 15), c1.dueDate);
            assertEquals(1, c1.freeFields.size());
            assertEquals(3, c1.freeFields.get(0).slot);
            assertEquals("Contrat", c1.freeFields.get(0).label);
            assertEquals("V-99", c1.freeFields.get(0).value);
            verify(c1, times(1)).persist();
            assertEquals(AccountCustomer.Origin.REGISTER, c2.origin);
            assertNull(c2.dueDate);
            assertTrue(c2.freeFields.isEmpty());
            assertEquals(AccountCustomer.Origin.REGISTER, c3.origin);
            assertNull(c3.dueDate);
        }
    }

    /**
     * Builds a minimal account-customer payload leaving every optional field
     * unset, so a test can override only the fields it exercises.
     *
     * @param account the account number (upsert key)
     * @return the payload
     */
    private RefPayloads.CustomerDto customerDto(String account) {
        RefPayloads.CustomerDto dto = new RefPayloads.CustomerDto();
        dto.accountNumber = account;
        dto.companyName = account + " SARL";
        return dto;
    }

    /**
     * Builds a minimal active checkout-island payload carrying only its code and
     * label, so a test sets whichever attachment form it exercises.
     *
     * @param code the island code (upsert key)
     * @return the payload
     */
    private RefPayloads.CheckoutIslandDto islandDto(String code) {
        RefPayloads.CheckoutIslandDto dto = new RefPayloads.CheckoutIslandDto();
        dto.code = code;
        dto.label = code + " label";
        dto.active = true;
        return dto;
    }

    /**
     * Stubs the checkout-point side of an island apply for a test that does not
     * look at it: no register is known yet as a point, and no island holds one,
     * so the relation rebuilt from the {@code terminalIds} string only creates
     * points (neutralized by the caller's construction mock).
     *
     * @param mocked the static Panache mock of the running test
     */
    private void stubNoCheckoutPoint(MockedStatic<PanacheEntityBase> mocked) {
        PanacheQuery<CheckoutPoint> none = queryReturning(null);
        mocked.when(() -> CheckoutPoint.find(org.mockito.ArgumentMatchers.eq("terminalId"),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(none);
        mocked.when(() -> CheckoutPoint.list(org.mockito.ArgumentMatchers.eq("islandCode = ?1"),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(List.of());
    }

    /**
     * Builds one contact-point payload binding a register to a contact point.
     *
     * @param code the contact-point code
     * @param terminalId the bound register identifier, possibly null or blank
     * @return the payload
     */
    private RefPayloads.ContactPointDto contactPoint(String code, String terminalId) {
        RefPayloads.ContactPointDto dto = new RefPayloads.ContactPointDto();
        dto.code = code;
        dto.terminalId = terminalId;
        return dto;
    }

    /**
     * OLD FORM (compat): a payload carrying only the semicolon {@code
     * terminalIds} string applies exactly as before — the string is kept as the
     * island's attachment, so a caisse facing an un-migrated node keeps its
     * islands.
     */
    @Test
    void applyCheckoutIslandsKeepsTheOldTerminalIdsString() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CheckoutIslandDto dto = islandDto("CAISSES_AVANT");
        dto.terminalIds = "POS01;POS02";
        dto.contactPoints = null;
        PanacheQuery<CheckoutIsland> absent = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CheckoutIsland> created = mockConstruction(CheckoutIsland.class);
                MockedConstruction<CheckoutPoint> rebuilt = mockConstruction(CheckoutPoint.class)) {
            stubNoCheckoutPoint(mocked);
            mocked.when(() -> CheckoutIsland.find("code", "CAISSES_AVANT")).thenReturn(absent);
            mocked.when(CheckoutIsland::listAll).thenReturn(List.of());
            service.applyCheckoutIslands(List.of(dto));
            assertEquals("POS01;POS02", created.constructed().get(0).terminalIds);
        }
    }

    /**
     * NEW FORM (compat): a payload carrying only the contact-point relation and
     * NO {@code terminalIds} string still attaches the registers — the effective
     * list is derived by joining the contact-point terminals. This is the one
     * unacceptable failure guarded against: an island must not be lost because
     * the field was renamed.
     */
    @Test
    void applyCheckoutIslandsDerivesTerminalsFromContactPoints() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CheckoutIslandDto dto = islandDto("CAISSES_AVANT");
        dto.terminalIds = null;
        dto.contactPoints = List.of(contactPoint("PC1", "POS03"), contactPoint("PC2", "POS04"));
        PanacheQuery<CheckoutIsland> absent = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CheckoutIsland> created = mockConstruction(CheckoutIsland.class);
                MockedConstruction<CheckoutPoint> rebuilt = mockConstruction(CheckoutPoint.class)) {
            stubNoCheckoutPoint(mocked);
            mocked.when(() -> CheckoutIsland.find("code", "CAISSES_AVANT")).thenReturn(absent);
            mocked.when(CheckoutIsland::listAll).thenReturn(List.of());
            service.applyCheckoutIslands(List.of(dto));
            assertEquals("POS03;POS04", created.constructed().get(0).terminalIds);
        }
    }

    /**
     * BOTH FORMS present resolve deterministically to the contact-point relation
     * (the new form), without preference or warning.
     */
    @Test
    void applyCheckoutIslandsPrefersContactPointsWhenBothPresent() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CheckoutIslandDto dto = islandDto("CAISSES_AVANT");
        dto.terminalIds = "OLD01;OLD02";
        dto.contactPoints = List.of(contactPoint("PC1", "POSA"));
        PanacheQuery<CheckoutIsland> absent = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CheckoutIsland> created = mockConstruction(CheckoutIsland.class);
                MockedConstruction<CheckoutPoint> rebuilt = mockConstruction(CheckoutPoint.class)) {
            stubNoCheckoutPoint(mocked);
            mocked.when(() -> CheckoutIsland.find("code", "CAISSES_AVANT")).thenReturn(absent);
            mocked.when(CheckoutIsland::listAll).thenReturn(List.of());
            service.applyCheckoutIslands(List.of(dto));
            assertEquals("POSA", created.constructed().get(0).terminalIds);
        }
    }

    /**
     * NEITHER form present leaves the attachment null — a genuinely empty island,
     * not a rename loss.
     */
    @Test
    void applyCheckoutIslandsWithNeitherFormLeavesNull() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CheckoutIslandDto dto = islandDto("CAISSES_AVANT");
        dto.terminalIds = null;
        dto.contactPoints = null;
        PanacheQuery<CheckoutIsland> absent = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CheckoutIsland> created = mockConstruction(CheckoutIsland.class);
                MockedConstruction<CheckoutPoint> rebuilt = mockConstruction(CheckoutPoint.class)) {
            stubNoCheckoutPoint(mocked);
            mocked.when(() -> CheckoutIsland.find("code", "CAISSES_AVANT")).thenReturn(absent);
            mocked.when(CheckoutIsland::listAll).thenReturn(List.of());
            service.applyCheckoutIslands(List.of(dto));
            assertNull(created.constructed().get(0).terminalIds);
        }
    }

    /**
     * An empty contact-point list is NOT the new form: it falls back to the
     * {@code terminalIds} string, and a contact point with a null or blank
     * terminal is dropped from the derived list.
     */
    @Test
    void applyCheckoutIslandsEmptyContactPointsFallBackAndDropBlanks() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CheckoutIslandDto emptyList = islandDto("EMPTY");
        emptyList.terminalIds = "POS09";
        emptyList.contactPoints = List.of();
        RefPayloads.CheckoutIslandDto withBlanks = islandDto("BLANKS");
        withBlanks.terminalIds = null;
        java.util.List<RefPayloads.ContactPointDto> points = new java.util.ArrayList<>();
        points.add(contactPoint("PC0", null));
        points.add(contactPoint("PC1", "   "));
        points.add(contactPoint("PC2", "POS05"));
        withBlanks.contactPoints = points;
        PanacheQuery<CheckoutIsland> emptyQuery = queryReturning(null);
        PanacheQuery<CheckoutIsland> blanksQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CheckoutIsland> created = mockConstruction(CheckoutIsland.class);
                MockedConstruction<CheckoutPoint> rebuilt = mockConstruction(CheckoutPoint.class)) {
            stubNoCheckoutPoint(mocked);
            mocked.when(() -> CheckoutIsland.find("code", "EMPTY")).thenReturn(emptyQuery);
            mocked.when(() -> CheckoutIsland.find("code", "BLANKS")).thenReturn(blanksQuery);
            mocked.when(CheckoutIsland::listAll).thenReturn(List.of());
            service.applyCheckoutIslands(List.of(emptyList, withBlanks));
            assertEquals("POS09", created.constructed().get(0).terminalIds);
            assertEquals("POS05", created.constructed().get(1).terminalIds);
        }
    }

    /**
     * {@code applyCheckoutPoints} covers every leg of the apply: the insert arm,
     * the update arm, the blank-identifier skip, both enumerations resolved by
     * name and both falling back to null on a value this register does not know,
     * and the deactivation sweep on an absent point that was active — an absent
     * point that was already inactive being left alone.
     */
    @Test
    void applyCheckoutPointsInsertsUpdatesSkipsBlankAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CheckoutPointDto insert = new RefPayloads.CheckoutPointDto();
        insert.terminalId = "  POS01  ";
        insert.pdvNumber = "01234";
        insert.islandCode = "AVANT";
        insert.label = "Caisse 1";
        insert.active = true;
        insert.tpvType = "SELF_CHECKOUT_TGCS";
        insert.technicalHostessBadge = "11111111";
        insert.cashierDisplayType = "TOUCH";
        insert.cashierDisplayResolution = "1024x768";
        insert.customerDisplayType = "NONE";
        insert.menuTemplateCode = "MENU_LS";
        // BO-03-09-15/-24/-25/-26: the peripherals as the store administered them.
        insert.scaleProtocol = "DIGI";
        insert.scaleAddress = "10.0.0.8";
        insert.scalePort = 4001;
        insert.coinMachineProtocol = "GLORY";
        insert.coinMachineAddress = "10.0.0.9";
        insert.coinMachinePort = 5001;
        RefPayloads.CheckoutPointDto update = new RefPayloads.CheckoutPointDto();
        update.terminalId = "POS08";
        update.active = false;
        update.tpvType = "UN_TYPE_INCONNU";
        update.cashierDisplayType = "UN_AFFICHEUR_INCONNU";
        update.customerDisplayType = null;
        RefPayloads.CheckoutPointDto blank = new RefPayloads.CheckoutPointDto();
        blank.terminalId = "   ";
        RefPayloads.CheckoutPointDto nullIdentifier = new RefPayloads.CheckoutPointDto();
        nullIdentifier.terminalId = null;
        com.intermarche.edge.domain.store.CheckoutPoint existing =
                mock(com.intermarche.edge.domain.store.CheckoutPoint.class);
        com.intermarche.edge.domain.store.CheckoutPoint seen =
                mock(com.intermarche.edge.domain.store.CheckoutPoint.class);
        seen.terminalId = "POS01";
        seen.active = true;
        com.intermarche.edge.domain.store.CheckoutPoint absentActive =
                mock(com.intermarche.edge.domain.store.CheckoutPoint.class);
        absentActive.terminalId = "POS99";
        absentActive.active = true;
        com.intermarche.edge.domain.store.CheckoutPoint absentInactive =
                mock(com.intermarche.edge.domain.store.CheckoutPoint.class);
        absentInactive.terminalId = "POS98";
        absentInactive.active = false;
        PanacheQuery<com.intermarche.edge.domain.store.CheckoutPoint> absentQuery =
                queryReturning(null);
        PanacheQuery<com.intermarche.edge.domain.store.CheckoutPoint> existingQuery =
                queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.store.CheckoutPoint> created =
                        mockConstruction(com.intermarche.edge.domain.store.CheckoutPoint.class)) {
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("terminalId", "POS01")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("terminalId", "POS08")).thenReturn(existingQuery);
            mocked.when(com.intermarche.edge.domain.store.CheckoutPoint::listAll)
                    .thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyCheckoutPoints(List.of(insert, update, blank, nullIdentifier));
            // ONE row was created: the blank and the null identifiers were skipped
            // before the finder was ever consulted.
            assertEquals(1, created.constructed().size());
            com.intermarche.edge.domain.store.CheckoutPoint inserted = created.constructed().get(0);
            assertEquals("POS01", inserted.terminalId);
            assertEquals("01234", inserted.pdvNumber);
            assertEquals("AVANT", inserted.islandCode);
            assertEquals("Caisse 1", inserted.label);
            assertTrue(inserted.active);
            assertEquals(com.intermarche.edge.domain.store.TpvType.SELF_CHECKOUT_TGCS,
                    inserted.tpvType);
            assertEquals("11111111", inserted.technicalHostessBadge);
            assertEquals(com.intermarche.edge.domain.store.DisplayType.TOUCH,
                    inserted.cashierDisplayType);
            assertEquals("1024x768", inserted.cashierDisplayResolution);
            assertEquals(com.intermarche.edge.domain.store.DisplayType.NONE,
                    inserted.customerDisplayType);
            assertEquals("MENU_LS", inserted.menuTemplateCode);
            assertEquals(com.intermarche.edge.domain.store.ScaleProtocol.DIGI,
                    inserted.scaleProtocol);
            assertEquals("10.0.0.8", inserted.scaleAddress);
            assertEquals(Integer.valueOf(4001), inserted.scalePort);
            assertEquals(com.intermarche.edge.domain.store.CoinMachineProtocol.GLORY,
                    inserted.coinMachineProtocol);
            assertEquals("10.0.0.9", inserted.coinMachineAddress);
            assertEquals(Integer.valueOf(5001), inserted.coinMachinePort);
            verify(inserted, times(1)).persist();
            // The existing row was updated in place, and the two values this
            // register does not know fell back to null rather than to a guess.
            assertFalse(existing.active);
            assertNull(existing.tpvType);
            assertNull(existing.cashierDisplayType);
            assertNull(existing.customerDisplayType);
            // BO-03-09-15/-24/-25/-26: a snapshot naming no protocol leaves the
            // register with NONE — no machine, which is what it had before the
            // column existed, and never a protocol nobody administered.
            assertEquals(com.intermarche.edge.domain.store.ScaleProtocol.NONE,
                    existing.scaleProtocol);
            assertNull(existing.scalePort);
            assertEquals(com.intermarche.edge.domain.store.CoinMachineProtocol.NONE,
                    existing.coinMachineProtocol);
            assertNull(existing.coinMachinePort);
            verify(existing, times(1)).persist();
            // The sweep deactivated the absent ACTIVE point and left the absent
            // inactive one untouched.
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
    }
    /** BO-04-03: list snapshots replace entries, create missing lists, and delete only absent codes. */
    @Test
    void coverageApplyAdministeredLists() {
        var service = new RefApplyService();
        var insert = new RefPayloads.PosListDto();
        insert.code = "NEW";
        insert.kind = "TARE_CONTAINER";
        insert.items = new ArrayList<>();
        var item = new RefPayloads.PosListItemDto();
        item.code = "BOX";
        item.numericValue = "0,035";
        insert.items.add(item);
        var update = new RefPayloads.PosListDto();
        update.code = "EXISTING";
        update.items = null;
        var existing = mock(com.intermarche.edge.domain.setting.PosList.class);
        existing.code = "EXISTING";
        existing.items = new ArrayList<>();
        existing.items.add(new com.intermarche.edge.domain.setting.PosListItem());
        var obsolete = mock(com.intermarche.edge.domain.setting.PosList.class);
        obsolete.code = "OBSOLETE";
        try (var lists = mockStatic(com.intermarche.edge.domain.setting.PosList.class);
             var base = mockStatic(PanacheEntityBase.class);
             var created = mockConstruction(com.intermarche.edge.domain.setting.PosList.class,
                     (row, context) -> row.items = new ArrayList<>())) {
            lists.when(() -> com.intermarche.edge.domain.setting.PosList.findByCode("EXISTING")).thenReturn(existing);
            base.when(PanacheEntityBase::listAll).thenReturn(List.of(existing, obsolete));
            service.applyPosLists(List.of(insert, update));
            var row = created.constructed().getFirst();
            assertEquals("NEW", row.code);
            assertEquals(new BigDecimal("0.035"), row.items.getFirst().numericValue);
            assertTrue(existing.items.isEmpty());
            verify(existing).persist();
            verify(obsolete).delete();
            verify(existing, never()).delete();
        }
    }

    /** BO-03-03: image snapshots create/update images and remove stale rows without deactivating retained ones. */
    @Test
    void coverageApplyImages() {
        var service = new RefApplyService();
        var insert = new RefPayloads.StoreImageDto();
        insert.code = "NEW";
        insert.imageData = "IMAGE";
        insert.usage = "RECEIPT_LOGO";
        var update = new RefPayloads.StoreImageDto();
        update.code = "EXISTING";
        update.imageData = "REPLACED";
        var existing = mock(com.intermarche.edge.domain.setting.StoreImage.class);
        existing.code = "EXISTING";
        var obsolete = mock(com.intermarche.edge.domain.setting.StoreImage.class);
        obsolete.code = "OBSOLETE";
        try (var images = mockStatic(com.intermarche.edge.domain.setting.StoreImage.class);
             var base = mockStatic(PanacheEntityBase.class);
             var created = mockConstruction(com.intermarche.edge.domain.setting.StoreImage.class)) {
            images.when(() -> com.intermarche.edge.domain.setting.StoreImage.findByCode("EXISTING")).thenReturn(existing);
            base.when(PanacheEntityBase::listAll).thenReturn(List.of(existing, obsolete));
            service.applyStoreImages(List.of(insert, update));
            assertEquals("IMAGE", created.constructed().getFirst().imageData);
            assertEquals("REPLACED", existing.imageData);
            verify(existing).persist();
            verify(obsolete).delete();
            verify(existing, never()).delete();
        }
    }

    /** BO-03-13-01/-02/-03: return-type snapshots upsert by code, every option carried, and remove stale rows. */
    @Test
    void coverageApplyReturnTypes() {
        var service = new RefApplyService();
        var insert = new RefPayloads.ReturnTypeDto();
        insert.code = "NEW";
        insert.label = "Nouveau";
        insert.ticketMode = true;
        insert.endorsementExempt = true;
        insert.originOptional = true;
        insert.priceConfirmed = true;
        insert.depositReturn = true;
        insert.rangeCode = "TRI";
        insert.displayOrder = 3;
        insert.active = true;
        var update = new RefPayloads.ReturnTypeDto();
        update.code = "EXISTING";
        update.label = "Renommé";
        var existing = mock(com.intermarche.edge.domain.setting.ReturnTypeDefinition.class);
        existing.code = "EXISTING";
        existing.active = true;
        var obsolete = mock(com.intermarche.edge.domain.setting.ReturnTypeDefinition.class);
        obsolete.code = "OBSOLETE";
        try (var types = mockStatic(com.intermarche.edge.domain.setting.ReturnTypeDefinition.class);
             var base = mockStatic(PanacheEntityBase.class);
             var created = mockConstruction(com.intermarche.edge.domain.setting.ReturnTypeDefinition.class)) {
            types.when(() -> com.intermarche.edge.domain.setting.ReturnTypeDefinition.findByCode("EXISTING"))
                    .thenReturn(existing);
            base.when(PanacheEntityBase::listAll).thenReturn(List.of(existing, obsolete));
            service.applyReturnTypes(List.of(insert, update));
            var row = created.constructed().getFirst();
            assertEquals("NEW", row.code);
            assertEquals("Nouveau", row.label);
            assertTrue(row.ticketMode && row.endorsementExempt && row.originOptional
                    && row.priceConfirmed && row.depositReturn && row.active);
            assertEquals("TRI", row.rangeCode);
            assertEquals(3, row.displayOrder);
            assertEquals("Renommé", existing.label);
            assertFalse(existing.active);
            verify(existing).persist();
            verify(obsolete).delete();
            verify(existing, never()).delete();
        }
    }

    /** BO-03-02-37: prepaid-card-type snapshots upsert by code, an absent protocol reading NEXO, and remove stale rows. */
    @Test
    void coverageApplyPrepaidCardTypes() {
        var service = new RefApplyService();
        var insert = new RefPayloads.PrepaidCardTypeDto();
        insert.code = "NEW";
        insert.label = "Carte";
        insert.protocol = "LOCAL";
        insert.provider = "ILLICADO";
        insert.ranges = "6299:16";
        insert.paymentAllowed = true;
        insert.balanceAllowed = true;
        insert.activationAllowed = true;
        insert.displayOrder = 2;
        insert.active = true;
        var blank = new RefPayloads.PrepaidCardTypeDto();
        blank.code = "BLANK";
        blank.protocol = " ";
        var update = new RefPayloads.PrepaidCardTypeDto();
        update.code = "EXISTING";
        var existing = mock(com.intermarche.edge.domain.payment.PrepaidCardType.class);
        existing.code = "EXISTING";
        var obsolete = mock(com.intermarche.edge.domain.payment.PrepaidCardType.class);
        obsolete.code = "OBSOLETE";
        try (var types = mockStatic(com.intermarche.edge.domain.payment.PrepaidCardType.class);
             var base = mockStatic(PanacheEntityBase.class);
             var created = mockConstruction(com.intermarche.edge.domain.payment.PrepaidCardType.class)) {
            types.when(() -> com.intermarche.edge.domain.payment.PrepaidCardType.findByCode("EXISTING"))
                    .thenReturn(existing);
            base.when(PanacheEntityBase::listAll).thenReturn(List.of(existing, obsolete));
            service.applyPrepaidCardTypes(List.of(insert, blank, update));
            var row = created.constructed().get(0);
            assertEquals("LOCAL", row.protocol);
            assertEquals("ILLICADO", row.provider);
            assertEquals("6299:16", row.ranges);
            assertTrue(row.paymentAllowed && row.balanceAllowed && row.activationAllowed && row.active);
            assertEquals(2, row.displayOrder);
            assertEquals("NEXO", created.constructed().get(1).protocol);
            assertEquals("NEXO", existing.protocol);
            verify(obsolete).delete();
            verify(existing, never()).delete();
        }
    }

    /** BO-03-08: provider substitutions upsert on both key columns and delete rows absent from the snapshot. */
    @Test
    void coverageApplyProviderMessages() {
        var service = new RefApplyService();
        var insert = new RefPayloads.ProviderMessageDto();
        insert.providerCode = "P";
        insert.messageCode = "NEW";
        insert.screenText = "SCREEN";
        var update = new RefPayloads.ProviderMessageDto();
        update.providerCode = "P";
        update.messageCode = "EXISTING";
        update.ticketText = "PAPER";
        var existing = mock(com.intermarche.edge.domain.setting.ProviderMessage.class);
        existing.providerCode = "P";
        existing.messageCode = "EXISTING";
        var obsolete = mock(com.intermarche.edge.domain.setting.ProviderMessage.class);
        obsolete.providerCode = "P";
        obsolete.messageCode = "OBSOLETE";
        var absent = queryReturning(null);
        var found = queryReturning(existing);
        try (var base = mockStatic(PanacheEntityBase.class);
             var created = mockConstruction(com.intermarche.edge.domain.setting.ProviderMessage.class)) {
            base.when(() -> PanacheEntityBase.find("providerCode = ?1 and messageCode = ?2", "P", "NEW")).thenReturn(absent);
            base.when(() -> PanacheEntityBase.find("providerCode = ?1 and messageCode = ?2", "P", "EXISTING")).thenReturn(found);
            base.when(PanacheEntityBase::listAll).thenReturn(List.of(existing, obsolete));
            service.applyProviderMessages(List.of(insert, update));
            assertEquals("SCREEN", created.constructed().getFirst().screenText);
            assertEquals("PAPER", existing.ticketText);
            verify(obsolete).delete();
            verify(existing, never()).delete();
        }
    }

    /**
     * BO-03-04-06/09/10/14: the printable documents upsert by code, an unknown
     * nature reads as an invoice, the printer is read as one of the three, and
     * a document the snapshot no longer carries is deleted.
     */
    @Test
    void coverageApplyPrintableDocuments() {
        var service = new RefApplyService();
        var insert = new RefPayloads.PrintableDocumentDto();
        insert.code = "NEW";
        insert.label = "FACTURE PRO";
        insert.kind = "BON_LIVRAISON";
        insert.output = "a4";
        insert.templateCode = "INV_PRO";
        insert.active = true;
        insert.priority = 5;
        var update = new RefPayloads.PrintableDocumentDto();
        update.code = "EXISTING";
        update.label = "AUTRE";
        update.kind = "INCONNU";
        var existing = mock(com.intermarche.edge.domain.setting.PrintableDocument.class);
        existing.code = "EXISTING";
        var obsolete = mock(com.intermarche.edge.domain.setting.PrintableDocument.class);
        obsolete.code = "OBSOLETE";
        try (var documents = mockStatic(com.intermarche.edge.domain.setting.PrintableDocument.class);
             var base = mockStatic(PanacheEntityBase.class);
             var created = mockConstruction(com.intermarche.edge.domain.setting.PrintableDocument.class)) {
            documents.when(() -> com.intermarche.edge.domain.setting.PrintableDocument.findByCode("EXISTING"))
                    .thenReturn(existing);
            documents.when(() -> com.intermarche.edge.domain.setting.PrintableDocument.outputOf(org.mockito.ArgumentMatchers.any()))
                    .thenCallRealMethod();
            base.when(PanacheEntityBase::listAll).thenReturn(List.of(existing, obsolete));
            service.applyPrintableDocuments(List.of(insert, update));
            var made = created.constructed().getFirst();
            assertEquals("NEW", made.code);
            assertEquals("FACTURE PRO", made.label);
            assertEquals(com.intermarche.edge.domain.sale.DocumentType.BON_LIVRAISON, made.kind);
            assertEquals("A4", made.output);
            assertEquals("INV_PRO", made.templateCode);
            assertEquals(5, made.priority);
            assertEquals("AUTRE", existing.label);
            assertEquals(com.intermarche.edge.domain.sale.DocumentType.FACTURE, existing.kind);
            assertNull(existing.output);
            verify(existing).persist();
            verify(obsolete).delete();
            verify(existing, never()).delete();
        }
    }

    /** BO-04: null, blank, unknown and all recognized enum names have explicit wire-format fallbacks. */
    @Test
    void coverageWireEnumsAndWeights() throws Exception {
        var usage = RefApplyService.class.getDeclaredMethod("usageOf", String.class);
        var kind = RefApplyService.class.getDeclaredMethod("kindOf", String.class);
        var weight = RefApplyService.class.getDeclaredMethod("weightOf", String.class);
        usage.setAccessible(true);
        kind.setAccessible(true);
        weight.setAccessible(true);
        for (String value : new String[]{null, " ", "UNKNOWN"}) {
            assertEquals(com.intermarche.edge.domain.setting.StoreImage.Usage.LOYALTY_ADVANTAGE, usage.invoke(null, value));
            assertEquals(com.intermarche.edge.domain.setting.PosList.Kind.JUSTIFICATION, kind.invoke(null, value));
            assertNull(weight.invoke(null, value));
        }
        for (var value : com.intermarche.edge.domain.setting.StoreImage.Usage.values()) assertEquals(value, usage.invoke(null, value.name()));
        for (var value : com.intermarche.edge.domain.setting.PosList.Kind.values()) assertEquals(value, kind.invoke(null, value.name()));
        assertEquals(new BigDecimal("0.035"), weight.invoke(null, "0,035"));
    }

    /** BO-01-01: employee snapshots carry the secret-change timestamp instead of resetting its age. */
    @Test
    void coverageEmployeeSecretTimestamp() {
        var service = new RefApplyService();
        var dto = new RefPayloads.EmployeeDto();
        dto.loginName = "OP";
        dto.role = "CASHIER";
        dto.passwordChangedAt = "2026-10-20T08:00:00";
        var existing = mock(Employee.class);
        existing.loginName = "OP";
        var found = queryReturning(existing);
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.find("loginName", "OP")).thenReturn(found);
            base.when(PanacheEntityBase::listAll).thenReturn(List.of(existing));
            service.applyEmployees(List.of(dto));
            assertEquals(java.time.LocalDateTime.of(2026, 10, 20, 8, 0), existing.passwordChangedAt);
        }
    }

    /** BO-03-09: malformed contact points are skipped independently while valid terminal identifiers retain their order. */
    @Test
    void incompleteContactPointsDoNotPolluteTheLegacyTerminalList() throws Exception {
        var missingId = new RefPayloads.ContactPointDto();
        var blankId = new RefPayloads.ContactPointDto();
        blankId.terminalId = " ";
        var valid = new RefPayloads.ContactPointDto();
        valid.terminalId = " C04 ";
        var dto = new RefPayloads.CheckoutIslandDto();
        dto.contactPoints = java.util.Arrays.asList(null, missingId, blankId, valid);
        var method = RefApplyService.class.getDeclaredMethod("effectiveTerminalIds", RefPayloads.CheckoutIslandDto.class);
        method.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertEquals("C04", method.invoke(new RefApplyService(), dto));
    }

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
        dated.pendingValue = "true";
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
            // The wipe is scoped to the CENTRAL levels: the node-local ISLAND and
            // CHECKOUT_POINT rows must survive a central pull (BO-03-09/03-10).
            mocked.verify(() -> EchelonSetting.delete(
                    org.mockito.ArgumentMatchers.eq("level in ?1"),
                    org.mockito.ArgumentMatchers.any(java.util.List.class)), times(1));
            mocked.verify(EchelonSetting::deleteAll, never());
            assertEquals(2, created.constructed().size());
            EchelonSetting first = created.constructed().get(0);
            assertEquals(EchelonLevel.ENSEIGNE, first.level);
            assertEquals("ITM", first.echelonCode);
            assertEquals("discount.enabled", first.settingKey);
            assertEquals("false", first.settingValue);
            assertEquals("true", first.pendingValue);
            assertEquals(LocalDate.of(2026, 3, 1), first.effectiveDate);
            verify(first, times(1)).persist();
            EchelonSetting second = created.constructed().get(1);
            assertEquals(EchelonLevel.COUNTRY, second.level);
            assertNull(second.effectiveDate);
            assertNull(second.pendingValue);
            verify(second, times(1)).persist();
            verify(service.posSettingsService, times(1)).invalidate();
        }
    }

    /**
     * {@code reconstructPoints} rebuilds the island&rarr;register relation from
     * the semicolon list: it creates a missing point, reactivates and re-points
     * an existing one, and deactivates a stale point no longer listed.
     */
    @Test
    void reconstructPointsCreatesReactivatesAndDeactivates() {
        RefApplyService service = new RefApplyService();
        com.intermarche.edge.domain.store.CheckoutPoint kept =
                mock(com.intermarche.edge.domain.store.CheckoutPoint.class);
        kept.terminalId = "POS01";
        kept.active = false;
        com.intermarche.edge.domain.store.CheckoutPoint stale =
                mock(com.intermarche.edge.domain.store.CheckoutPoint.class);
        stale.terminalId = "POS09";
        stale.active = true;
        PanacheQuery<com.intermarche.edge.domain.store.CheckoutPoint> keptQuery = queryReturning(kept);
        PanacheQuery<com.intermarche.edge.domain.store.CheckoutPoint> noPoint = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.store.CheckoutPoint> points =
                        mockConstruction(com.intermarche.edge.domain.store.CheckoutPoint.class)) {
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("terminalId", "POS01")).thenReturn(keptQuery);
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("terminalId", "POS02")).thenReturn(noPoint);
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .list("islandCode = ?1", "AVANT")).thenReturn(List.of(kept, stale));
            service.reconstructPoints("AVANT", " POS01 ; POS02 ; ; ");
            // existing point re-pointed and reactivated
            assertEquals("AVANT", kept.islandCode);
            assertTrue(kept.active);
            verify(kept, times(1)).persist();
            // missing terminal created
            assertEquals(1, points.constructed().size());
            assertEquals("POS02", points.constructed().get(0).terminalId);
            verify(points.constructed().get(0), times(1)).persist();
            // stale point no longer listed is deactivated
            assertFalse(stale.active);
            verify(stale, times(1)).persist();
        }
    }

    /**
     * {@code reconstructPoints} with a null list deactivates every point of the
     * island and creates none — the empty-relation arm.
     */
    @Test
    void reconstructPointsWithNoTerminalsDeactivatesAll() {
        RefApplyService service = new RefApplyService();
        com.intermarche.edge.domain.store.CheckoutPoint stale =
                mock(com.intermarche.edge.domain.store.CheckoutPoint.class);
        stale.terminalId = "POS09";
        stale.active = true;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.store.CheckoutPoint> points =
                        mockConstruction(com.intermarche.edge.domain.store.CheckoutPoint.class)) {
            mocked.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .list("islandCode = ?1", "AVANT")).thenReturn(List.of(stale));
            service.reconstructPoints("AVANT", null);
            assertTrue(points.constructed().isEmpty());
            assertFalse(stale.active);
            verify(stale, times(1)).persist();
        }
    }

    /**
     * Covers the kept arm of the {@code applyTouchGroups} delete loop: a listed
     * setting whose family code is still named by the snapshot ({@code seen}
     * contains it) is left in place, so {@code !seen.contains(...)} is false and
     * {@code delete()} is never called on it. A stale row not in the snapshot is
     * deleted in the same pass, exercising both arms of the guard.
     */
    @Test
    void applyTouchGroupsKeepsTheStillNamedRow() {
        RefApplyService service = new RefApplyService();
        RefPayloads.TouchGroupDto dto = new RefPayloads.TouchGroupDto();
        dto.familyCode = "F1";
        dto.pinned = true;
        dto.buttonSize = "LARGE";
        dto.displayOrder = 1;
        dto.salesVolume = 3L;
        dto.labels = "fr=GROUPE";
        TouchGroupSetting existing = mock(TouchGroupSetting.class);
        TouchGroupSetting kept = mock(TouchGroupSetting.class);
        kept.familyCode = "F1";
        TouchGroupSetting stale = mock(TouchGroupSetting.class);
        stale.familyCode = "GONE";
        PanacheQuery<TouchGroupSetting> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TouchGroupSetting.find("familyCode", "F1")).thenReturn(existingQuery);
            mocked.when(() -> TouchGroupSetting.listAll()).thenReturn(List.of(kept, stale));
            service.applyTouchGroups(List.of(dto));
            assertEquals("LARGE", existing.buttonSize);
            assertEquals("fr=GROUPE", existing.labels);
            verify(kept, never()).delete();
            verify(stale, times(1)).delete();
        }
    }

    /**
     * Covers every arm of {@code applyPostalCodes}: a pair unknown on the
     * register is inserted (findPair-null arm), a known pair is updated
     * (findPair-present arm), a listed row still named by the snapshot is kept
     * (seen-contains false-delete arm) and a listed row the snapshot no longer
     * names is DELETED, not deactivated (seen-absent true-delete arm). The
     * deleted row carries a null code and a null place, which drives both null
     * arms of the {@code pairOf} key composer.
     */
    @Test
    void applyPostalCodesUpsertsAndDeletesTheAbsent() {
        RefApplyService service = new RefApplyService();
        RefPayloads.PostalCodeDto insert = new RefPayloads.PostalCodeDto();
        insert.code = "69001";
        insert.place = "Lyon";
        insert.country = "FR";
        RefPayloads.PostalCodeDto update = new RefPayloads.PostalCodeDto();
        update.code = "75002";
        update.place = "Paris";
        update.country = "FR";
        com.intermarche.edge.domain.store.PostalCode existing =
                mock(com.intermarche.edge.domain.store.PostalCode.class);
        com.intermarche.edge.domain.store.PostalCode seen =
                mock(com.intermarche.edge.domain.store.PostalCode.class);
        seen.code = "69001";
        seen.place = "Lyon";
        com.intermarche.edge.domain.store.PostalCode stale =
                mock(com.intermarche.edge.domain.store.PostalCode.class);
        stale.code = null;
        stale.place = null;
        PanacheQuery<com.intermarche.edge.domain.store.PostalCode> absentQuery =
                queryReturning(null);
        PanacheQuery<com.intermarche.edge.domain.store.PostalCode> existingQuery =
                queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.store.PostalCode> created =
                        mockConstruction(com.intermarche.edge.domain.store.PostalCode.class)) {
            mocked.when(() -> com.intermarche.edge.domain.store.PostalCode
                    .find("code = ?1 and place = ?2", "69001", "Lyon")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.edge.domain.store.PostalCode
                    .find("code = ?1 and place = ?2", "75002", "Paris")).thenReturn(existingQuery);
            mocked.when(() -> com.intermarche.edge.domain.store.PostalCode.listAll())
                    .thenReturn(List.of(seen, stale));
            service.applyPostalCodes(List.of(insert, update));
            com.intermarche.edge.domain.store.PostalCode inserted = created.constructed().get(0);
            assertEquals("69001", inserted.code);
            assertEquals("Lyon", inserted.place);
            assertEquals("FR", inserted.country);
            verify(inserted, times(1)).persist();
            assertEquals("FR", existing.country);
            verify(existing, times(1)).persist();
            verify(seen, never()).delete();
            verify(stale, times(1)).delete();
        }
    }

    /**
     * An empty postal-code snapshot deletes every listed pair: a referential
     * emptied upstream empties on the register too, since a postal code carries
     * no foreign key that would forbid the deletion.
     */
    @Test
    void applyPostalCodesEmptyPayloadDeletesEveryListedPair() {
        RefApplyService service = new RefApplyService();
        com.intermarche.edge.domain.store.PostalCode stale =
                mock(com.intermarche.edge.domain.store.PostalCode.class);
        stale.code = "13001";
        stale.place = "Marseille";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.store.PostalCode.listAll())
                    .thenReturn(List.of(stale));
            service.applyPostalCodes(List.of());
            verify(stale, times(1)).delete();
        }
    }

    /**
     * Covers the readable arms of the {@code origin} and {@code date} helpers and
     * the free-field replacement: an inserted customer carries a known origin
     * (both guard-false arms, valueOf success), a parseable due date (both
     * guard-false arms, parse success) and one free field, so the free-field
     * loop body runs on a freshly created list ({@code freeFields == null} true
     * arm, {@code dto.freeFields != null} true arm). An updated customer that
     * already carries a free-field list has it CLEARED and left empty because
     * the snapshot names none ({@code freeFields == null} false arm,
     * {@code dto.freeFields != null} false arm), and its blank origin and blank
     * due date fall back to REGISTER and null (the {@code isBlank} true arms).
     */
    @Test
    void applyCustomersReadsOriginDueDateAndReplacesFreeFields() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CustomerDto insert = new RefPayloads.CustomerDto();
        insert.accountNumber = "ACC1";
        insert.companyName = "Alpha SARL";
        insert.origin = "INTEGRATED";
        insert.dueDate = "2026-05-15";
        RefPayloads.FreeFieldDto field = new RefPayloads.FreeFieldDto();
        field.slot = 1;
        field.label = "Contact";
        field.value = "Jean";
        insert.freeFields = new ArrayList<>(List.of(field));
        RefPayloads.CustomerDto update = new RefPayloads.CustomerDto();
        update.accountNumber = "ACC2";
        update.companyName = "Beta SA";
        update.origin = "   ";
        update.dueDate = "   ";
        update.freeFields = null;
        AccountCustomer existing = mock(AccountCustomer.class);
        existing.address = new Address();
        existing.freeFields = new ArrayList<>();
        existing.freeFields.add(new AccountCustomer.FreeField(9, "Stale", "Old"));
        PanacheQuery<AccountCustomer> absentQuery = queryReturning(null);
        PanacheQuery<AccountCustomer> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<AccountCustomer> created = mockConstruction(AccountCustomer.class)) {
            mocked.when(() -> AccountCustomer.find("accountNumber", "ACC1")).thenReturn(absentQuery);
            mocked.when(() -> AccountCustomer.find("accountNumber", "ACC2")).thenReturn(existingQuery);
            service.applyCustomers(List.of(insert, update));
            AccountCustomer inserted = created.constructed().get(0);
            assertEquals(AccountCustomer.Origin.INTEGRATED, inserted.origin);
            assertEquals(LocalDate.of(2026, 5, 15), inserted.dueDate);
            assertEquals(1, inserted.freeFields.size());
            assertEquals(1, inserted.freeFields.get(0).slot);
            assertEquals("Contact", inserted.freeFields.get(0).label);
            assertEquals("Jean", inserted.freeFields.get(0).value);
            verify(inserted, times(1)).persist();
            assertEquals(AccountCustomer.Origin.REGISTER, existing.origin);
            assertNull(existing.dueDate);
            assertTrue(existing.freeFields.isEmpty());
            verify(existing, times(1)).persist();
        }
    }

    /**
     * Covers the catch arms of the {@code origin} and {@code date} helpers: an
     * origin word this version does not know is read as REGISTER rather than
     * failing the pull (valueOf's IllegalArgumentException), and a malformed due
     * date is read as no date rather than as an incident (parse's
     * DateTimeParseException) — one bad row must not stop the whole referential.
     */
    @Test
    void applyCustomersToleratesUnknownOriginAndMalformedDueDate() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CustomerDto dto = new RefPayloads.CustomerDto();
        dto.accountNumber = "ACC9";
        dto.companyName = "Gamma";
        dto.origin = "MARTIENNE";
        dto.dueDate = "35/13/2026";
        dto.freeFields = null;
        PanacheQuery<AccountCustomer> absentQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<AccountCustomer> created = mockConstruction(AccountCustomer.class)) {
            mocked.when(() -> AccountCustomer.find("accountNumber", "ACC9")).thenReturn(absentQuery);
            service.applyCustomers(List.of(dto));
            AccountCustomer inserted = created.constructed().get(0);
            assertEquals(AccountCustomer.Origin.REGISTER, inserted.origin);
            assertNull(inserted.dueDate);
            assertTrue(inserted.freeFields.isEmpty());
            verify(inserted, times(1)).persist();
        }
    }

    /** BO-04: disabled audit writes nothing; null/short/long diagnostics preserve or bound the recorded detail. */
    @Test
    void coverageAuditDetailBounds() {
        var service = new RefApplyService();
        service.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        try (var created = mockConstruction(com.intermarche.edge.domain.sync.RefApplyAudit.class)) {
            service.recordAudit("products", "FP", com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.APPLIED, null);
            assertTrue(created.constructed().isEmpty());
            when(service.posSettingsService.referentialUpdateAuditEnabled()).thenReturn(true);
            service.recordAudit("products", "FP", com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.APPLIED, null);
            service.recordAudit("products", "FP", com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.FAILED, "SHORT");
            service.recordAudit("products", "FP", com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.FAILED, "X".repeat(600));
            assertNull(created.constructed().get(0).detail);
            assertEquals("SHORT", created.constructed().get(1).detail);
            assertEquals("X".repeat(500), created.constructed().get(2).detail);
            for (var row : created.constructed()) verify(row).persist();
        }
    }

    /** BO-03-09: an already inactive checkout omitted by its island stays inactive without a redundant write. */
    @Test
    void omittedInactiveCheckoutDoesNotNeedAnotherWrite() {
        var point = mock(com.intermarche.edge.domain.store.CheckoutPoint.class);
        point.terminalId = "C04";
        point.active = false;
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> com.intermarche.edge.domain.store.CheckoutPoint.list("islandCode = ?1", "A")).thenReturn(List.of(point));
            new RefApplyService().reconstructPoints("A", null);
        }
        assertFalse(point.active);
        verify(point, never()).persist();
    }

    /**
     * BO-01-03-04: the central profiles are upserted by name with their grants,
     * the validating profile resolved by name in the snapshot or on the node,
     * a grant without feature or with an unknown option dropped; a central
     * profile the centre no longer sends is removed with its assignments.
     */
    @Test
    void applyProfilesUpsertsAndRemoves() {
        RefApplyService service = new RefApplyService();
        RefPayloads.ProfileDto fresh = new RefPayloads.ProfileDto();
        fresh.name = "Caisse";
        fresh.hierarchyLevel = 1;
        RefPayloads.ProfileDto kept = new RefPayloads.ProfileDto();
        kept.name = "Chef";
        kept.description = "Chefs de caisse";
        fresh.grants.add(grant("account", "DELETE", true, "Chef"));
        fresh.grants.add(grant("account", "UPDATE", true, "Local"));
        fresh.grants.add(grant("account", "VIEW", false, null));
        fresh.grants.add(grant("account", "VIEW", true, "Ghost"));
        fresh.grants.add(grant(null, "VIEW", false, null));
        fresh.grants.add(grant("store", "BOGUS", false, null));
        com.intermarche.edge.domain.people.Profile existing = mock(com.intermarche.edge.domain.people.Profile.class);
        existing.id = 2L;
        existing.name = "Chef";
        com.intermarche.edge.domain.people.Profile local = new com.intermarche.edge.domain.people.Profile();
        local.id = 3L;
        local.name = "Local";
        com.intermarche.edge.domain.people.Profile stale = mock(com.intermarche.edge.domain.people.Profile.class);
        stale.id = 9L;
        stale.name = "Ancien";
        PanacheQuery<Object> none = queryReturning(null);
        PanacheQuery<Object> chief = queryReturning(existing);
        PanacheQuery<Object> localQuery = queryReturning(local);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             MockedConstruction<com.intermarche.edge.domain.people.Profile> built =
                     mockConstruction(com.intermarche.edge.domain.people.Profile.class)) {
            ms.when(() -> PanacheEntityBase.find("name", "Caisse")).thenReturn(none);
            ms.when(() -> PanacheEntityBase.find("name", "Chef")).thenReturn(chief);
            ms.when(() -> PanacheEntityBase.find("name", "Local")).thenReturn(localQuery);
            ms.when(() -> PanacheEntityBase.find("name", "Ghost")).thenReturn(none);
            ms.when(() -> PanacheEntityBase.list("centralManaged", true)).thenReturn(List.of(existing, stale));
            service.applyProfiles(List.of(fresh, kept));
            com.intermarche.edge.domain.people.Profile created = built.constructed().get(0);
            assertEquals("Caisse", created.name);
            assertTrue(created.centralManaged);
            assertEquals(1, created.hierarchyLevel);
            assertEquals(4, created.grants.size());
            assertEquals(2L, created.grants.get(0).validatingProfileId);
            assertEquals(3L, created.grants.get(1).validatingProfileId);
            assertNull(created.grants.get(2).validatingProfileId);
            assertNull(created.grants.get(3).validatingProfileId);
            assertEquals("Chefs de caisse", existing.description);
            assertTrue(existing.centralManaged);
            verify(stale).delete();
            ms.verify(() -> PanacheEntityBase.delete("profileId", 9L));
        }
    }

    /**
     * Builds one distributed grant.
     *
     * @param feature the feature key
     * @param option the option name
     * @param validation whether it asks for a validation
     * @param validator the validating profile's name, or null
     * @return the grant
     */
    private RefPayloads.ProfileGrantDto grant(String feature, String option, boolean validation, String validator) {
        RefPayloads.ProfileGrantDto grant = new RefPayloads.ProfileGrantDto();
        grant.featureKey = feature;
        grant.option = option;
        grant.requiresValidation = validation;
        grant.validatingProfileName = validator;
        return grant;
    }

    /**
     * BO-01-01-03: the central accounts are upserted by login, the hash adopted
     * as it is, every one marked central; an address or a badge another local
     * account holds skips the account; a central account the centre no longer
     * sends is deactivated, an inactive one left alone.
     */
    @Test
    void applyAccountsUpsertsSkipsAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.AccountDto fresh = new RefPayloads.AccountDto();
        fresh.loginName = "new";
        fresh.password = "$2a$pin";
        fresh.backOfficePassword = "$2a$bo";
        fresh.role = "CASHIER";
        fresh.active = true;
        RefPayloads.AccountDto known = new RefPayloads.AccountDto();
        known.loginName = "known";
        known.firstName = "Paule";
        known.lastName = "Lefort";
        known.email = "p@x.test";
        known.badgeId = "B2";
        known.role = "MANAGER";
        known.active = true;
        known.startDate = "2026-01-02";
        known.passwordChangedAt = "2026-02-03T04:05";
        known.authMode = "SSO";
        known.echelonScopes = "ENSEIGNE:ITM";
        known.hierarchyLevel = 2;
        known.supervisorCard = true;
        known.mustChangePassword = true;
        known.dallasKey = "01A2B3C4D5E6F708";
        RefPayloads.AccountDto mailClash = new RefPayloads.AccountDto();
        mailClash.loginName = "mail";
        mailClash.email = "taken@x.test";
        RefPayloads.AccountDto badgeClash = new RefPayloads.AccountDto();
        badgeClash.loginName = "badge";
        badgeClash.badgeId = "B9";
        RefPayloads.AccountDto moved = new RefPayloads.AccountDto();
        moved.loginName = "known";
        moved.email = "taken@x.test";
        RefPayloads.AccountDto freshClash = new RefPayloads.AccountDto();
        freshClash.loginName = "new";
        freshClash.badgeId = "B9";
        Employee existing = mock(Employee.class);
        existing.id = 2L;
        existing.loginName = "known";
        Employee other = new Employee();
        other.id = 5L;
        other.loginName = "other";
        Employee gone = mock(Employee.class);
        gone.loginName = "gone";
        gone.active = true;
        Employee asleep = mock(Employee.class);
        asleep.loginName = "asleep";
        asleep.active = false;
        PanacheQuery<Object> none = queryReturning(null);
        PanacheQuery<Object> existingQuery = queryReturning(existing);
        PanacheQuery<Object> otherQuery = queryReturning(other);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Employee> built = mockConstruction(Employee.class)) {
            ms.when(() -> PanacheEntityBase.find("loginName", "new")).thenReturn(none);
            ms.when(() -> PanacheEntityBase.find("loginName", "known")).thenReturn(existingQuery);
            ms.when(() -> PanacheEntityBase.find("email", "p@x.test")).thenReturn(existingQuery);
            ms.when(() -> PanacheEntityBase.find("badgeId", "B2")).thenReturn(existingQuery);
            ms.when(() -> PanacheEntityBase.find("loginName", "mail")).thenReturn(none);
            ms.when(() -> PanacheEntityBase.find("email", "taken@x.test")).thenReturn(otherQuery);
            ms.when(() -> PanacheEntityBase.find("loginName", "badge")).thenReturn(existingQuery);
            ms.when(() -> PanacheEntityBase.find("badgeId", "B9")).thenReturn(otherQuery);
            ms.when(() -> PanacheEntityBase.list("centralManaged", true)).thenReturn(List.of(existing, gone, asleep));
            ms.when(() -> PanacheEntityBase.find("dallasKey", "01A2B3C4D5E6F708")).thenReturn(existingQuery);
            service.applyAccounts(List.of(fresh, known, mailClash, badgeClash, moved, freshClash));
            Employee created = built.constructed().get(0);
            assertEquals("new", created.loginName);
            assertEquals("", created.firstName);
            assertEquals("$2a$pin", created.password);
            assertTrue(created.centralManaged);
            assertEquals(Employee.AuthMode.ANY, created.authMode);
            assertNull(created.startDate);
            assertNull(created.passwordChangedAt);
            ms.verify(() -> PanacheEntityBase.update("backOfficePassword = ?1 where id = ?2", "$2a$bo", null));
            assertEquals("Paule", existing.firstName);
            assertEquals(LocalDate.of(2026, 1, 2), existing.startDate);
            assertEquals(LocalDateTime.of(2026, 2, 3, 4, 5), existing.passwordChangedAt);
            assertEquals(Employee.AuthMode.SSO, existing.authMode);
            assertEquals("ENSEIGNE:ITM", existing.echelonScopes);
            assertTrue(existing.supervisorCard);
            assertTrue(existing.mustChangePassword);
            assertEquals("01A2B3C4D5E6F708", existing.dallasKey);
            assertTrue(existing.centralManaged);
            assertEquals(2, built.constructed().size());
            assertFalse(gone.active);
            assertFalse(asleep.active);
            assertTrue(existing.active);
        }
    }

    /**
     * BO-01-03-04: the central assignments are replaced as a whole, account by
     * login and profile by name, a missing scope reading as the whole
     * organisation; an unresolved account or profile is skipped.
     */
    @Test
    void applyAccountProfilesReplacesTheCentralAssignments() {
        RefApplyService service = new RefApplyService();
        RefPayloads.AccountProfileDto scoped = new RefPayloads.AccountProfileDto();
        scoped.loginName = "known";
        scoped.profileName = "Chef";
        scoped.echelonScope = "ENSEIGNE:ITM";
        RefPayloads.AccountProfileDto global = new RefPayloads.AccountProfileDto();
        global.loginName = "known";
        global.profileName = "Chef";
        RefPayloads.AccountProfileDto noAccount = new RefPayloads.AccountProfileDto();
        noAccount.loginName = "ghost";
        noAccount.profileName = "Chef";
        RefPayloads.AccountProfileDto noProfile = new RefPayloads.AccountProfileDto();
        noProfile.loginName = "known";
        noProfile.profileName = "Gone";
        Employee account = new Employee();
        account.id = 2L;
        com.intermarche.edge.domain.people.Profile chief = new com.intermarche.edge.domain.people.Profile();
        chief.id = 4L;
        PanacheQuery<Object> none = queryReturning(null);
        PanacheQuery<Object> accountQuery = queryReturning(account);
        PanacheQuery<Object> chiefQuery = queryReturning(chief);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             MockedConstruction<com.intermarche.edge.domain.people.EmployeeProfile> built =
                     mockConstruction(com.intermarche.edge.domain.people.EmployeeProfile.class)) {
            ms.when(() -> PanacheEntityBase.find("loginName", "known")).thenReturn(accountQuery);
            ms.when(() -> PanacheEntityBase.find("loginName", "ghost")).thenReturn(none);
            ms.when(() -> PanacheEntityBase.find("name", "Chef")).thenReturn(chiefQuery);
            ms.when(() -> PanacheEntityBase.find("name", "Gone")).thenReturn(none);
            service.applyAccountProfiles(List.of(scoped, global, noAccount, noProfile));
            ms.verify(() -> PanacheEntityBase.delete("centralManaged", true));
            assertEquals(2, built.constructed().size());
            assertEquals("ENSEIGNE:ITM", built.constructed().get(0).echelonScope);
            assertEquals("*", built.constructed().get(1).echelonScope);
            assertEquals(2L, built.constructed().get(1).employeeId);
            assertEquals(4L, built.constructed().get(1).profileId);
            assertTrue(built.constructed().get(1).centralManaged);
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
     * <p>On a STORE node ({@code pos.role=store}): a column the head office
     * snapshot leaves null keeps the store's own value.
     */
    @Test
    void applyTouchGroupsUpsertsAndDeletesTheAbsentOnAStoreNode() {
        RefApplyService service = new RefApplyService();
        service.role = "store";
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
        existing.labels = "pt=Lacteos";
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
     * Covers every arm of {@code applyCouponTypes}: an inserted coupon type
     * (ENCODED source), an updated one (MANUAL source), then the three
     * deactivation arms — a seen type left untouched, an absent active type
     * deactivated, and an absent inactive type left untouched.
     * <p>On a STORE node ({@code pos.role=store}): a column the head office
     * snapshot leaves null keeps the store's own value.
     */
    @Test
    void applyCouponTypesInsertsUpdatesAndDeactivatesOnAStoreNode() {
        RefApplyService service = new RefApplyService();
        service.role = "store";
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
        // BO-03-06-08 and -30: a head office naming a tender or an issuing kind
        // imposes it; one staying silent leaves the store's own in place.
        insert.tenderCode = "TR";
        insert.issuedKind = "CREDIT_NOTE";
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
        existing.tenderCode = "VOUCHER";
        existing.issuedKind = "GIFT_CARD";
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
            assertEquals("TR", inserted.tenderCode);
            assertEquals("CREDIT_NOTE", inserted.issuedKind);
            assertEquals("VOUCHER", existing.tenderCode);
            assertEquals("GIFT_CARD", existing.issuedKind);
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
     * {@code applyArticleBarcodeRanges} covers the insert arm, the update arm
     * and the three deactivation arms — a seen range left untouched, an absent
     * active range deactivated, an absent inactive one left alone.
     * <p>On a STORE node ({@code pos.role=store}): a column the head office
     * snapshot leaves null keeps the store's own value.
     */
    @Test
    void applyArticleRangesInsertsUpdatesAndDeactivatesOnAStoreNode() {
        RefApplyService service = new RefApplyService();
        service.role = "store";
        RefPayloads.ArticleBarcodeRangeDto insert = rangeDto("R1");
        RefPayloads.ArticleBarcodeRangeDto update = rangeDto("R2");
        update.prefix = "297";
        update.codeLength = 16;
        update.valueSource = "WEIGHT";
        update.valueDecimals = 3;
        update.currency = "FRF";
        update.checkDigit = false;
        update.priority = 20;
        // BO-03-06-56/-60/-61: a head office that names the destination imposes
        // it, one that stays silent leaves the store's — both legs of the guard.
        insert.purpose = "REFUND";
        // BO-03-06-43: same two legs for the promotion a range names — a head
        // office that names one imposes it, one that stays silent leaves the
        // store's promotion in place rather than erasing it behind its back.
        insert.promotionCode = "PROMO_RENTREE";
        insert.lookupUrl = "https://partenaire.test/bons/{code}";
        // BO-03-06-59: and the same two legs for the function a range calls.
        insert.functionCode = "SURVEY";
        // BO-03-06-43 / -41: the promotion definition and the partner mapping.
        insert.promotionType = "PERCENT";
        insert.lookupField = "statut.message";
        ArticleBarcodeRange existing = mock(ArticleBarcodeRange.class);
        existing.functionCode = "METROLOGY";
        existing.promotionType = "AMOUNT";
        existing.lookupField = "ancien";
        existing.purpose = ArticleBarcodeRange.Purpose.TARE;
        existing.promotionCode = "PROMO_MAGASIN";
        existing.lookupUrl = "https://service.magasin/?c=";
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
            assertEquals(ArticleBarcodeRange.Purpose.REFUND, inserted.purpose);
            assertEquals("PROMO_RENTREE", inserted.promotionCode);
            assertEquals("https://partenaire.test/bons/{code}", inserted.lookupUrl);
            assertEquals("SURVEY", inserted.functionCode);
            assertEquals("^21\\d{11}$", inserted.matchPattern);
            verify(inserted, times(1)).persist();
            assertEquals("METROLOGY", existing.functionCode);
            assertEquals("PERCENT", inserted.promotionType);
            assertEquals("statut.message", inserted.lookupField);
            assertEquals("AMOUNT", existing.promotionType);
            assertEquals("ancien", existing.lookupField);
            assertEquals(ArticleBarcodeRange.Purpose.TARE, existing.purpose);
            assertEquals("PROMO_MAGASIN", existing.promotionCode);
            assertEquals("https://service.magasin/?c=", existing.lookupUrl);
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
     * {@code applyTenders} covers the insert arm, the update arm and the three
     * deactivation arms, and carries the whole administered description down to
     * the register.
     * <p>On a STORE node ({@code pos.role=store}): a column the head office
     * snapshot leaves null keeps the store's own value.
     */
    @Test
    void applyTendersInsertsUpdatesAndDeactivatesOnAStoreNode() {
        RefApplyService service = new RefApplyService();
        service.role = "store";
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
        // BO-03-09-30: a head office that names the VIC product code imposes
        // it, one that stays silent leaves the store's — both legs of the
        // guard.
        insert.vicProductCode = "{DAJ}01";
        insert.selfServiceAllowed = false;
        insert.selfServiceSupervision = true;
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
        com.intermarche.edge.domain.payment.TenderDefinition existing =
                mock(com.intermarche.edge.domain.payment.TenderDefinition.class);
        com.intermarche.edge.domain.payment.TenderDefinition seen =
                mock(com.intermarche.edge.domain.payment.TenderDefinition.class);
        existing.vicProductCode = "{DCJ}01";
        seen.code = "TR";
        seen.active = true;
        com.intermarche.edge.domain.payment.TenderDefinition absentActive =
                mock(com.intermarche.edge.domain.payment.TenderDefinition.class);
        absentActive.code = "DEVISE";
        absentActive.active = true;
        com.intermarche.edge.domain.payment.TenderDefinition absentInactive =
                mock(com.intermarche.edge.domain.payment.TenderDefinition.class);
        absentInactive.code = "SECOURS";
        absentInactive.active = false;
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> absentQuery =
                queryReturning(null);
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> existingQuery =
                queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.payment.TenderDefinition> created =
                        mockConstruction(com.intermarche.edge.domain.payment.TenderDefinition.class)) {
            mocked.when(() -> com.intermarche.edge.domain.payment.TenderDefinition
                    .find("code", "TR")).thenReturn(absentQuery);
            mocked.when(() -> com.intermarche.edge.domain.payment.TenderDefinition
                    .find("code", "CHEQUE")).thenReturn(existingQuery);
            mocked.when(com.intermarche.edge.domain.payment.TenderDefinition::listAll)
                    .thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyTenders(List.of(insert, update));
            com.intermarche.edge.domain.payment.TenderDefinition inserted = created.constructed().get(0);
            assertEquals("TR", inserted.code);
            assertEquals("030", inserted.functionalId);
            assertEquals("Titre restaurant", inserted.label);
            assertTrue(inserted.active);
            assertEquals(30, inserted.displayOrder);
            assertEquals(new java.math.BigDecimal("25.00"), inserted.maxAmount);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                    inserted.maxAmountControl);
            assertEquals(Integer.valueOf(2), inserted.maxCount);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                    inserted.maxCountControl);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.DrawerOpening.IF_CHANGE_DUE,
                    inserted.drawerOpening);
            assertEquals("CASH", inserted.changeTenderCode);
            assertEquals("{DAJ}01", inserted.vicProductCode);
            // BO-03-02-27/-29: both carried verbatim, unlike the VIC code the
            // store may keep — a head office describing its self-service lanes
            // describes them for every store.
            assertFalse(inserted.selfServiceAllowed);
            assertTrue(inserted.selfServiceSupervision);
            assertTrue(inserted.refundAllowed);
            assertTrue(inserted.fidelityReported);
            verify(inserted, times(1)).persist();
            assertEquals("Chèque", existing.label);
            assertEquals("020", existing.functionalId);
            assertEquals(new java.math.BigDecimal("5.00"), existing.minAmount);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.WARNING,
                    existing.minAmountControl);
            assertEquals(new java.math.BigDecimal("300.00"), existing.secondMaxAmount);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.INFO,
                    existing.secondMaxAmountControl);
            assertEquals(new java.math.BigDecimal("8.00"), existing.maxChangeAmount);
            assertEquals(com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.INFO,
                    existing.maxChangeControl);
            // The snapshot names no code for CHEQUE: the store's own survives.
            assertEquals("{DCJ}01", existing.vicProductCode);
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
     * <p>On a STORE node ({@code pos.role=store}): a column the head office
     * snapshot leaves null keeps the store's own value.
     */
    @Test
    void applyCurrenciesInsertsUpdatesDefaultsRateAndDeactivatesOnAStoreNode() {
        RefApplyService service = new RefApplyService();
        service.role = "store";
        RefPayloads.CurrencyDto insert = currencyDto("USD", "0.9");
        // BO-03-02-39: a head office carrying the change tender imposes it, one
        // that does not leaves the store's choice — both legs.
        insert.changeTenderCode = "ESP";
        RefPayloads.CurrencyDto update = currencyDto("GBP", null);
        RefPayloads.CurrencyDto blank = currencyDto("CHF", "   ");
        RefPayloads.CurrencyDto invalid = currencyDto("JPY", "abc");
        Currency existing = mock(Currency.class);
        existing.changeTenderCode = "CHQ";
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
            assertEquals("ESP", usd.changeTenderCode);
            verify(usd, times(1)).persist();
            assertEquals(BigDecimal.ONE, existing.euroPerUnit);
            assertEquals("CHQ", existing.changeTenderCode);
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
     * BO-03-10-16/17: the cash menus are upserted by code — a blank code
     * skipped, a new model created with its label or its code, an existing one
     * updated — and a model the snapshot no longer names is deactivated once.
     */
    @Test
    void applyCashMenusUpsertsAndDeactivatesTheAbsent() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CashMenuDto blank = new RefPayloads.CashMenuDto();
        blank.code = " ";
        RefPayloads.CashMenuDto nullCode = new RefPayloads.CashMenuDto();
        RefPayloads.CashMenuDto fresh = new RefPayloads.CashMenuDto();
        fresh.code = "SLS";
        fresh.label = " ";
        fresh.active = true;
        RefPayloads.CashMenuDto unnamed = new RefPayloads.CashMenuDto();
        unnamed.code = "NEUF";
        fresh.menuKeys = "articles";
        fresh.hiddenFunctions = "ABANDON";
        fresh.keyColumns = 3;
        fresh.cashierScreen = "LARGE";
        fresh.customerScreen = "TOTAL_ONLY";
        fresh.ticketModel = "COMPACT";
        RefPayloads.CashMenuDto known = new RefPayloads.CashMenuDto();
        known.code = "LIGNE";
        known.label = "Ligne de caisse";
        known.active = true;
        com.intermarche.edge.domain.store.CashMenu existing = mock(com.intermarche.edge.domain.store.CashMenu.class);
        existing.code = "LIGNE";
        com.intermarche.edge.domain.store.CashMenu stale = mock(com.intermarche.edge.domain.store.CashMenu.class);
        stale.code = "OLD";
        stale.active = true;
        com.intermarche.edge.domain.store.CashMenu staleOff = mock(com.intermarche.edge.domain.store.CashMenu.class);
        staleOff.code = "OFF";
        staleOff.active = false;
        PanacheQuery<com.intermarche.edge.domain.store.CashMenu> absent = queryReturning(null);
        PanacheQuery<com.intermarche.edge.domain.store.CashMenu> present = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.store.CashMenu> created =
                        mockConstruction(com.intermarche.edge.domain.store.CashMenu.class)) {
            mocked.when(() -> com.intermarche.edge.domain.store.CashMenu.find("code", "SLS")).thenReturn(absent);
            mocked.when(() -> com.intermarche.edge.domain.store.CashMenu.find("code", "LIGNE")).thenReturn(present);
            mocked.when(() -> com.intermarche.edge.domain.store.CashMenu.find("code", "NEUF")).thenReturn(absent);
            mocked.when(com.intermarche.edge.domain.store.CashMenu::listAll)
                    .thenReturn(List.of(existing, stale, staleOff));
            service.applyCashMenus(List.of(blank, nullCode, fresh, known, unnamed));
            assertEquals("NEUF", created.constructed().get(1).label);
            com.intermarche.edge.domain.store.CashMenu inserted = created.constructed().get(0);
            assertEquals("SLS", inserted.code);
            assertEquals("SLS", inserted.label);
            assertEquals("articles", inserted.menuKeys);
            assertEquals("ABANDON", inserted.hiddenFunctions);
            assertEquals(3, inserted.keyColumns);
            assertEquals("LARGE", inserted.cashierScreen);
            assertEquals("TOTAL_ONLY", inserted.customerScreen);
            assertEquals("COMPACT", inserted.ticketModel);
            verify(inserted).persist();
            assertEquals("Ligne de caisse", existing.label);
            verify(existing).persist();
            assertFalse(stale.active);
            verify(stale).persist();
            verify(staleOff, never()).persist();
        }
    }

    /**
     * BO-03-10-19/20: the store record is copied onto this node's store row,
     * created with the node's code when there is none; the address created when
     * absent; a share capital read back from text.
     */
    @Test
    void applyStoreCreatesOrUpdatesTheStoreRow() {
        RefApplyService service = new RefApplyService();
        RefPayloads.StoreDto dto = new RefPayloads.StoreDto();
        dto.code = "0034";
        dto.name = "Inter Vaucresson";
        dto.streetLine1 = "1 rue A";
        dto.city = "Vaucresson";
        dto.vatNumber = "FR1";
        dto.phone = "0102";
        dto.bankAccountNumber = "ACC";
        dto.shareCapital = "1000.00";
        PanacheQuery<com.intermarche.edge.domain.store.Store> none = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.store.Store> created =
                        mockConstruction(com.intermarche.edge.domain.store.Store.class)) {
            mocked.when(com.intermarche.edge.domain.store.Store::findAll).thenReturn(none);
            service.applyStore(List.of(dto));
            com.intermarche.edge.domain.store.Store store = created.constructed().get(0);
            assertEquals("0034", store.code);
            assertEquals("Inter Vaucresson", store.name);
            assertEquals("Vaucresson", store.address.city);
            assertEquals("FR1", store.vatNumber);
            assertEquals("0102", store.phone);
            assertEquals("ACC", store.bankAccountNumber);
            assertEquals(new java.math.BigDecimal("1000.00"), store.shareCapital);
            verify(store).persist();
        }
        com.intermarche.edge.domain.store.Store mine = mock(com.intermarche.edge.domain.store.Store.class);
        mine.code = "LOCAL";
        mine.address = new com.intermarche.edge.domain.store.Address();
        dto.shareCapital = null;
        PanacheQuery<com.intermarche.edge.domain.store.Store> present = queryReturning(mine);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(com.intermarche.edge.domain.store.Store::findAll).thenReturn(present);
            service.applyStore(List.of(dto));
        }
        assertEquals("LOCAL", mine.code);
        assertEquals("1 rue A", mine.address.streetLine1);
        assertNull(mine.shareCapital);
        verify(mine).persist();
    }

    /** BO-03-10-19: an empty snapshot, or one without a name, leaves the store row untouched. */
    @Test
    void applyStoreIgnoresAnEmptyOrNamelessRecord() {
        RefApplyService service = new RefApplyService();
        RefPayloads.StoreDto nameless = new RefPayloads.StoreDto();
        RefPayloads.StoreDto blank = new RefPayloads.StoreDto();
        blank.name = " ";
        service.applyStore(List.of());
        service.applyStore(List.of(nameless));
        service.applyStore(List.of(blank));
    }

    /**
     * BO-02-03-17: the units of measure are upserted by code — a new one
     * created, a known one updated — and an absent active unit is deactivated
     * while an absent inactive one and a seen one are left alone.
     */
    @Test
    void applyMeasureUnitsUpsertsAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.MeasureUnitDto created = new RefPayloads.MeasureUnitDto();
        created.code = "B";
        created.label = "Mètre bobine";
        created.symbol = "mb";
        created.decimalAllowed = true;
        created.active = true;
        RefPayloads.MeasureUnitDto updated = new RefPayloads.MeasureUnitDto();
        updated.code = "2";
        updated.label = "Kilogramme";
        updated.symbol = "kg";
        updated.active = true;
        com.intermarche.edge.domain.catalog.MeasureUnit kilo = mock(com.intermarche.edge.domain.catalog.MeasureUnit.class);
        kilo.code = "2";
        com.intermarche.edge.domain.catalog.MeasureUnit gone = mock(com.intermarche.edge.domain.catalog.MeasureUnit.class);
        gone.code = "7";
        gone.active = true;
        com.intermarche.edge.domain.catalog.MeasureUnit dead = mock(com.intermarche.edge.domain.catalog.MeasureUnit.class);
        dead.code = "8";
        dead.active = false;
        try (MockedStatic<com.intermarche.edge.domain.catalog.MeasureUnit> units =
                     mockStatic(com.intermarche.edge.domain.catalog.MeasureUnit.class);
             MockedConstruction<com.intermarche.edge.domain.catalog.MeasureUnit> built =
                     mockConstruction(com.intermarche.edge.domain.catalog.MeasureUnit.class)) {
            units.when(() -> com.intermarche.edge.domain.catalog.MeasureUnit.findByCode("2")).thenReturn(kilo);
            units.when(com.intermarche.edge.domain.catalog.MeasureUnit::listAllOrdered)
                    .thenReturn(List.of(kilo, gone, dead));
            service.applyMeasureUnits(List.of(created, updated));
            com.intermarche.edge.domain.catalog.MeasureUnit bobbin = built.constructed().get(0);
            assertEquals("B", bobbin.code);
            assertEquals("mb", bobbin.symbol);
            assertTrue(bobbin.decimalAllowed);
            verify(bobbin).persist();
        }
        assertEquals("kg", kilo.symbol);
        assertTrue(kilo.active);
        verify(kilo).persist();
        assertFalse(gone.active);
        verify(gone).persist();
        verify(dead, never()).persist();
    }

    /**
     * BO-02-03-33: {@code feedStamp} reads the stamps of a held feed and null
     * for an absent one; a stamp starts from the register's state only when
     * both stamps are known and the base is that state — the empty base being
     * the state of a register that never applied the domain.
     */
    @Test
    void feedStampsTellWhereAFileStartsFrom() {
        RefApplyService service = new RefApplyService();
        EngineFeed feed = mock(EngineFeed.class);
        feed.baseFingerprint = "b";
        feed.resultFingerprint = "r";
        PanacheQuery<EngineFeed> presentQuery = queryReturning(feed);
        PanacheQuery<EngineFeed> absentQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> EngineFeed.find("code", "PRODUCTS")).thenReturn(presentQuery);
            mocked.when(() -> EngineFeed.find("code", "ABSENT")).thenReturn(absentQuery);
            assertEquals(new RefApplyService.FeedStamp("b", "r"), service.feedStamp("PRODUCTS"));
            assertNull(service.feedStamp("ABSENT"));
        }
        assertFalse(new RefApplyService.FeedStamp(null, "r").startsFrom("b"));
        assertFalse(new RefApplyService.FeedStamp("b", null).startsFrom("b"));
        assertTrue(new RefApplyService.FeedStamp("b", "r").startsFrom("b"));
        assertFalse(new RefApplyService.FeedStamp("b", "r").startsFrom("x"));
        assertTrue(new RefApplyService.FeedStamp("", "r").startsFrom(null));
        assertFalse(new RefApplyService.FeedStamp("", "r").startsFrom("b"));
    }
}
