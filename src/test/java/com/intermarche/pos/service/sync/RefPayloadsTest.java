package com.intermarche.pos.service.sync;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RefPayloads}.
 * <p>
 * {@code RefPayloads} is a branch-free data-container class: a non-instantiable
 * outer holder plus public DTOs whose only members are public fields and
 * implicit no-arg constructors. There is no conditional logic, so the tests
 * exercise the private constructor (via reflection) and every DTO's construction
 * and field round-trip to reach full instruction coverage.
 */
class RefPayloadsTest {

    /**
     * The outer class must expose a single private no-arg constructor and be
     * final, and invoking that constructor reflectively must yield an instance.
     */
    @Test
    void outerClassIsNonInstantiableHolder() throws Exception {
        assertTrue(Modifier.isFinal(RefPayloads.class.getModifiers()));
        Constructor<RefPayloads> ctor = RefPayloads.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()));
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }

    /**
     * A {@link RefPayloads.FamilyDto} stores and returns its three fields.
     */
    @Test
    void familyDtoHoldsItsFields() {
        RefPayloads.FamilyDto dto = new RefPayloads.FamilyDto();
        dto.code = "FAM1";
        dto.description = "Family one";
        dto.flags = "AB";
        assertEquals("FAM1", dto.code);
        assertEquals("Family one", dto.description);
        assertEquals("AB", dto.flags);
    }

    /**
     * A {@link RefPayloads.ProductDto} stores and returns each of its fields,
     * covering both the boolean flags and the nullable numeric fields.
     */
    @Test
    void productDtoHoldsItsFields() {
        RefPayloads.ProductDto dto = new RefPayloads.ProductDto();
        dto.ean = "3760000000001";
        dto.plu = "42";
        dto.name = "Milk";
        dto.description = "Whole milk";
        dto.icon = "milk.png";
        dto.brand = "BrandX";
        dto.referenceWeight = new BigDecimal("1.5");
        dto.referenceVolume = new BigDecimal("1.0");
        dto.productType = "STANDARD";
        dto.unitName = "L";
        dto.active = true;
        dto.forbiddenToSale = false;
        assertEquals("3760000000001", dto.ean);
        assertEquals("42", dto.plu);
        assertEquals("Milk", dto.name);
        assertEquals("Whole milk", dto.description);
        assertEquals("milk.png", dto.icon);
        assertEquals("BrandX", dto.brand);
        assertEquals(new BigDecimal("1.5"), dto.referenceWeight);
        assertEquals(new BigDecimal("1.0"), dto.referenceVolume);
        assertEquals("STANDARD", dto.productType);
        assertEquals("L", dto.unitName);
        assertTrue(dto.active);
        assertFalse(dto.forbiddenToSale);
    }

    /**
     * A default {@link RefPayloads.ProductDto} leaves nullable fields null and
     * boolean flags false, confirming no hidden initialization logic.
     */
    @Test
    void productDtoDefaultsAreNullAndFalse() {
        RefPayloads.ProductDto dto = new RefPayloads.ProductDto();
        assertNull(dto.ean);
        assertNull(dto.plu);
        assertNull(dto.referenceWeight);
        assertNull(dto.referenceVolume);
        assertNull(dto.unitName);
        assertFalse(dto.active);
        assertFalse(dto.forbiddenToSale);
    }

    /**
     * A {@link RefPayloads.PriceDto} stores and returns each of its fields.
     */
    @Test
    void priceDtoHoldsItsFields() {
        RefPayloads.PriceDto dto = new RefPayloads.PriceDto();
        dto.productEan = "3760000000001";
        dto.priceExcludingTax = new BigDecimal("1.00");
        dto.priceIncludingTax = new BigDecimal("1.20");
        dto.vatRate = new BigDecimal("20.0");
        dto.priority = 5;
        dto.startDateTime = "2026-01-01T00:00:00";
        dto.endDateTime = "2026-12-31T23:59:59";
        assertEquals("3760000000001", dto.productEan);
        assertEquals(new BigDecimal("1.00"), dto.priceExcludingTax);
        assertEquals(new BigDecimal("1.20"), dto.priceIncludingTax);
        assertEquals(new BigDecimal("20.0"), dto.vatRate);
        assertEquals(5, dto.priority);
        assertEquals("2026-01-01T00:00:00", dto.startDateTime);
        assertEquals("2026-12-31T23:59:59", dto.endDateTime);
    }

    /**
     * A default {@link RefPayloads.PriceDto} leaves nullable fields null.
     */
    @Test
    void priceDtoDefaultsAreNull() {
        RefPayloads.PriceDto dto = new RefPayloads.PriceDto();
        assertNull(dto.productEan);
        assertNull(dto.priceExcludingTax);
        assertNull(dto.priceIncludingTax);
        assertNull(dto.vatRate);
        assertNull(dto.priority);
        assertNull(dto.startDateTime);
        assertNull(dto.endDateTime);
    }

    /**
     * An {@link RefPayloads.EmployeeDto} stores and returns each of its fields.
     */
    @Test
    void employeeDtoHoldsItsFields() {
        RefPayloads.EmployeeDto dto = new RefPayloads.EmployeeDto();
        dto.loginName = "jdoe";
        dto.firstName = "John";
        dto.lastName = "Doe";
        dto.password = "hash";
        dto.email = "jdoe@example.com";
        dto.role = "CASHIER";
        dto.badgeId = "B123";
        dto.theme = "dark";
        dto.active = true;
        assertEquals("jdoe", dto.loginName);
        assertEquals("John", dto.firstName);
        assertEquals("Doe", dto.lastName);
        assertEquals("hash", dto.password);
        assertEquals("jdoe@example.com", dto.email);
        assertEquals("CASHIER", dto.role);
        assertEquals("B123", dto.badgeId);
        assertEquals("dark", dto.theme);
        assertTrue(dto.active);
    }

    /**
     * A default {@link RefPayloads.EmployeeDto} leaves nullable fields null and
     * the active flag false.
     */
    @Test
    void employeeDtoDefaultsAreNullAndFalse() {
        RefPayloads.EmployeeDto dto = new RefPayloads.EmployeeDto();
        assertNull(dto.badgeId);
        assertNull(dto.theme);
        assertFalse(dto.active);
    }

    /**
     * A {@link RefPayloads.CouponTypeDto} stores and returns each of its fields,
     * covering both boolean flags.
     */
    @Test
    void couponTypeDtoHoldsItsFields() {
        RefPayloads.CouponTypeDto dto = new RefPayloads.CouponTypeDto();
        dto.code = "CT1";
        dto.label = "Coupon one";
        dto.matchPattern = "^\\d{8}$";
        dto.amountSource = "ENCODED";
        dto.amountPattern = "(\\d+)";
        dto.priority = 3;
        dto.active = true;
        dto.depositLine = true;
        assertEquals("CT1", dto.code);
        assertEquals("Coupon one", dto.label);
        assertEquals("^\\d{8}$", dto.matchPattern);
        assertEquals("ENCODED", dto.amountSource);
        assertEquals("(\\d+)", dto.amountPattern);
        assertEquals(3, dto.priority);
        assertTrue(dto.active);
        assertTrue(dto.depositLine);
    }

    /**
     * A default {@link RefPayloads.CouponTypeDto} leaves nullable fields null
     * and both boolean flags false.
     */
    @Test
    void couponTypeDtoDefaultsAreNullAndFalse() {
        RefPayloads.CouponTypeDto dto = new RefPayloads.CouponTypeDto();
        assertNull(dto.amountPattern);
        assertFalse(dto.active);
        assertFalse(dto.depositLine);
    }

    /**
     * A {@link RefPayloads.CountryDto} stores and returns its three fields.
     */
    @Test
    void countryDtoHoldsItsFields() {
        RefPayloads.CountryDto dto = new RefPayloads.CountryDto();
        dto.code = "FR";
        dto.name = "France";
        dto.defaultLanguage = "fr";
        assertEquals("FR", dto.code);
        assertEquals("France", dto.name);
        assertEquals("fr", dto.defaultLanguage);
    }

    /**
     * An {@link RefPayloads.EnseigneDto} stores and returns its four fields.
     */
    @Test
    void enseigneDtoHoldsItsFields() {
        RefPayloads.EnseigneDto dto = new RefPayloads.EnseigneDto();
        dto.code = "ITM";
        dto.name = "Intermarché";
        dto.countryCode = "FR";
        dto.defaultLanguage = "fr";
        assertEquals("ITM", dto.code);
        assertEquals("Intermarché", dto.name);
        assertEquals("FR", dto.countryCode);
        assertEquals("fr", dto.defaultLanguage);
    }

    /**
     * A {@link RefPayloads.PdvDto} stores and returns each of its fields, and a
     * default one leaves the active flag false.
     */
    @Test
    void pdvDtoHoldsItsFields() {
        RefPayloads.PdvDto dto = new RefPayloads.PdvDto();
        assertFalse(dto.active);
        dto.pdvNumber = "01234";
        dto.name = "Lyon";
        dto.enseigneCode = "ITM";
        dto.adherentCode = "AD1";
        dto.active = true;
        assertEquals("01234", dto.pdvNumber);
        assertEquals("Lyon", dto.name);
        assertEquals("ITM", dto.enseigneCode);
        assertEquals("AD1", dto.adherentCode);
        assertTrue(dto.active);
    }

    /**
     * An {@link RefPayloads.EchelonSettingDto} stores and returns its five
     * fields, the effect date defaulting to null.
     */
    @Test
    void echelonSettingDtoHoldsItsFields() {
        RefPayloads.EchelonSettingDto dto = new RefPayloads.EchelonSettingDto();
        assertNull(dto.effectiveDate);
        dto.level = "ENSEIGNE";
        dto.echelonCode = "ITM";
        dto.settingKey = "discount.enabled";
        dto.settingValue = "false";
        dto.effectiveDate = "2026-03-01";
        assertEquals("ENSEIGNE", dto.level);
        assertEquals("ITM", dto.echelonCode);
        assertEquals("discount.enabled", dto.settingKey);
        assertEquals("false", dto.settingValue);
        assertEquals("2026-03-01", dto.effectiveDate);
    }
}
