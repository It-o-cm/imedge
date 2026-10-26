package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.barcode.AlertLevel;
import com.intermarche.edge.domain.barcode.ArticleBarcodeRange;
import com.intermarche.edge.domain.barcode.CouponControl;
import com.intermarche.edge.domain.barcode.CouponField;
import com.intermarche.edge.domain.barcode.CouponType;
import com.intermarche.edge.service.CouponPatternService;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminCouponResource}.
 * <p>
 * The resource is a Qute-backed back-office page over the Panache static
 * finders of {@link CouponType} (resolved to {@link PanacheEntityBase} under
 * plain {@code mvn test}, intercepted with
 * {@link org.mockito.Mockito#mockStatic}) plus a mocked
 * {@link CouponPatternService}. The row inserted by the create arm is
 * neutralised with {@link org.mockito.Mockito#mockConstruction}. No database,
 * no Quarkus boot.
 * <p>
 * Branch enumeration (every arm exercised): {@code saveType} covers the blank
 * code arm, the unknown amount source arm, the unknown kind arm, both legs of
 * the kept-fields ternary, the refused-description arm, the insert arm and the
 * update arm, both arms of each checkbox and both arms of the priority
 * fallback; {@code saveField} covers the unknown range arm, the unknown role
 * arm, the unknown kind arm, the refused-description arm, the replace arm and
 * the add arm, and both arms of the optional layout, currency and decimals;
 * {@code deleteField} covers the unknown range arm, the unknown role arm, the
 * nothing-removed arm and the removed arm; {@code positiveOrNull} and
 * {@code positiveOrZeroOrNull} cover their accepted and refused arms.
 */
class AdminCouponResourceTest {

    /**
     * Builds a resource over a mocked template and generator.
     *
     * @return the wired resource
     */
    private AdminCouponResource newResource() {
        AdminCouponResource resource = new AdminCouponResource();
        resource.adminCoupons = mock(Template.class);
        resource.couponPatterns = mock(CouponPatternService.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminCouponResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminCoupons.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds an empty posted form.
     *
     * @return a mutable empty form
     */
    private MultivaluedMap<String, String> form() {
        return new MultivaluedHashMap<>();
    }

    /**
     * Builds a stored range carrying a field list.
     *
     * @param code the range code
     * @return the range
     */
    private CouponType stored(String code) {
        CouponType type = new CouponType();
        type.code = code;
        type.codeLength = 14;
        type.amountSource = CouponType.AmountSource.MANUAL;
        type.fields = new ArrayList<>();
        return type;
    }

    /**
     * Adds a field to a stored range.
     *
     * @param type the range
     * @param role the role the field carries
     * @return the added field
     */
    private CouponField withField(CouponType type, CouponField.Role role) {
        CouponField field = new CouponField();
        field.couponType = type;
        field.role = role;
        field.offsetPosition = 4;
        field.fieldLength = 4;
        field.kind = CouponField.Kind.NUMERIC;
        type.fields.add(field);
        return field;
    }

    /**
     * Stubs {@code CouponType.find("code", code)} to resolve to the given row.
     *
     * @param panache the active static mock
     * @param code the code to match
     * @param row the row to resolve, or null
     */
    @SuppressWarnings("unchecked")
    /**
     * Builds a sound posted description of an article range.
     *
     * @param code the range code
     * @return the posted form
     */
    private MultivaluedMap<String, String> articleForm(String code) {
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", code);
        form.putSingle("label", "Étiquette prix");
        form.putSingle("prefix", "21");
        form.putSingle("codeLength", "13");
        form.putSingle("codeKind", "NUMERIC");
        form.putSingle("articlePosition", "2");
        form.putSingle("articleLength", "5");
        form.putSingle("valueSource", "PRICE");
        form.putSingle("valuePosition", "7");
        form.putSingle("valueLength", "5");
        form.putSingle("valueDecimals", "2");
        form.putSingle("currency", "EUR");
        form.putSingle("priority", "50");
        return form;
    }

    /**
     * Builds a stored article range.
     *
     * @param code the range code
     * @return the range
     */
    private ArticleBarcodeRange articleRange(String code) {
        ArticleBarcodeRange range = new ArticleBarcodeRange();
        range.code = code;
        range.label = "Étiquette prix";
        range.prefix = "21";
        range.codeLength = 13;
        return range;
    }

    /**
     * Wires the article-range finder to the given stored row.
     *
     * @param panache the intercepted Panache statics
     * @param code the code looked up
     * @param row the stored range, or null when none carries the code
     */
    @SuppressWarnings("unchecked")
    private void stubRangeFind(MockedStatic<PanacheEntityBase> panache, String code,
                               ArticleBarcodeRange row) {
        PanacheQuery<ArticleBarcodeRange> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> ArticleBarcodeRange.find("code", code)).thenReturn(query);
    }

    private void stubFind(MockedStatic<PanacheEntityBase> panache, String code, CouponType row) {
        PanacheQuery<CouponType> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> CouponType.find("code", code)).thenReturn(query);
    }

    // ---------------------------------------------------------------- GET

    /**
     * The page hands the template the ranges and every catalog the forms offer.
     */
    @Test
    @SuppressWarnings("unchecked")
    void pageHandsTheTemplateTheRangesAndTheCatalogs() {
        AdminCouponResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        List<CouponType> types = List.of(stored("GIFT"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<CouponType> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(types);
            panache.when(() -> CouponType.find("order by priority, code")).thenReturn(query);
            List<ArticleBarcodeRange> ranges = List.of(articleRange("BALANCE_PRIX"));
            // Sort carries no equals(), so the stub matches on the type: what the
            // case asserts is the ORDER the resource asks for, verified below.
            panache.when(() -> ArticleBarcodeRange.listAll(any(io.quarkus.panache.common.Sort.class)))
                    .thenReturn(ranges);
            // The islands use their OWN finder clause: two stubs on the same
            // erased Panache static would otherwise shadow one another.
            PanacheQuery<com.intermarche.edge.domain.store.CheckoutIsland> islandQuery =
                    mock(PanacheQuery.class);
            List<com.intermarche.edge.domain.store.CheckoutIsland> islands =
                    List.of(new com.intermarche.edge.domain.store.CheckoutIsland());
            when(islandQuery.list()).thenReturn(islands);
            panache.when(() -> com.intermarche.edge.domain.store.CheckoutIsland
                    .find("order by code")).thenReturn(islandQuery);
            // BO-03-06-08: the tenders a scanned range may be bound to.
            PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> tenderQuery =
                    mock(PanacheQuery.class);
            List<com.intermarche.edge.domain.payment.TenderDefinition> tenders =
                    List.of(new com.intermarche.edge.domain.payment.TenderDefinition());
            when(tenderQuery.list()).thenReturn(tenders);
            panache.when(() -> com.intermarche.edge.domain.payment.TenderDefinition
                    .find("code in ?1 order by functionalId", AdminCouponResource.SCANNED_TENDERS))
                    .thenReturn(tenderQuery);
            assertSame(instance, resource.couponsPage("n", false));
            verify(resource.adminCoupons).data("types", types);
            verify(instance).data("articleRanges", ranges);
            verify(instance).data("purposes", ArticleBarcodeRange.Purpose.values());
            verify(instance).data("valueSources", ArticleBarcodeRange.ValueSource.values());
            verify(instance).data("islands", islands);
            verify(instance).data("roles", CouponField.Role.values());
            verify(instance).data("kinds", CouponField.Kind.values());
            verify(instance).data("formats", CouponField.DateFormat.values());
            verify(instance).data("currencies", CouponField.PriceCurrency.values());
            verify(instance).data("sources", CouponType.AmountSource.values());
            verify(instance).data("notice", "n");
            verify(instance).data("noticeOk", false);
            verify(instance).data("functions", com.intermarche.edge.domain.barcode.RegisterFunction.values());
            verify(instance).data("tenders", tenders);
            verify(instance).data("issuedKinds", AdminCouponResource.ISSUED_KINDS);
        }
    }

    // ------------------------------------------------------------ SAVE TYPE

    /**
     * {@code saveType} refuses a blank code (first guard).
     */
    @Test
    void saveTypeRejectsABlankCode() {
        AdminCouponResource resource = newResource();
        Response response = resource.saveType(form());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code saveType} refuses an unknown amount source (second guard).
     */
    @Test
    void saveTypeRejectsAnUnknownAmountSource() {
        AdminCouponResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("amountSource", "WHATEVER");
        Response response = resource.saveType(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code saveType} refuses an unknown character kind (third guard).
     */
    @Test
    void saveTypeRejectsAnUnknownKind() {
        AdminCouponResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("amountSource", "MANUAL");
        form.putSingle("codeKind", "RUNES");
        Response response = resource.saveType(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code saveType} refuses a description the generator rejects, and writes
     * nothing (refused arm, existing row untouched).
     */
    @Test
    void saveTypeRefusesAnInvalidDescriptionWithoutWriting() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        withField(existing, CouponField.Role.PRICE);
        existing.label = "avant";
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("label", "après");
        form.putSingle("amountSource", "MANUAL");
        form.putSingle("codeKind", "NUMERIC");
        form.putSingle("codeLength", "10");
        when(resource.couponPatterns.validate(any(), any(), any(), any()))
                .thenReturn(List.of("Le champ PRICE dépasse la longueur du code."));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.saveType(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            assertEquals("avant", existing.label);
            verify(resource.couponPatterns, never()).regenerate(any());
        }
    }

    /**
     * {@code saveType} inserts a range when none carries the code, persisting
     * it exactly once and mapping a blank prefix to null.
     */
    @Test
    void saveTypeInsertsWhenAbsent() {
        AdminCouponResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", " GIFT ");
        form.putSingle("label", "Chèque cadeau");
        form.putSingle("prefix", "  ");
        form.putSingle("codeLength", "10");
        form.putSingle("codeKind", "NUMERIC");
        form.putSingle("amountSource", "ENCODED");
        form.putSingle("priority", "pas un nombre");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<CouponType> construction = mockConstruction(CouponType.class)) {
            stubFind(panache, "GIFT", null);
            Response response = resource.saveType(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(response.getLocation().toString().contains("cr%C3%A9%C3%A9e"));
            CouponType created = construction.constructed().get(0);
            assertEquals("GIFT", created.code);
            assertEquals("Chèque cadeau", created.label);
            assertNull(created.prefix);
            assertEquals(10, created.codeLength);
            assertEquals(CouponField.Kind.NUMERIC, created.codeKind);
            assertEquals(CouponType.AmountSource.ENCODED, created.amountSource);
            assertEquals(100, created.priority);
            assertFalse(created.active);
            assertFalse(created.depositLine);
            assertFalse(created.manualAmountOnAllNines);
            assertNull(created.islandCodes);
            verify(created, times(1)).persist();
            verify(resource.couponPatterns).regenerate(created);
            verify(resource.couponPatterns).validate(null, 10,
                    CouponType.AmountSource.ENCODED, List.of());
        }
    }

    /**
     * {@code saveType} updates the stored range when one carries the code,
     * keeps its fields for the check, and honours both ticked checkboxes.
     */
    @Test
    void saveTypeUpdatesWhenPresent() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("STORE");
        CouponField price = withField(existing, CouponField.Role.PRICE);
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "STORE");
        form.putSingle("label", "Bon enseigne");
        form.putSingle("prefix", "50");
        form.putSingle("codeLength", "14");
        form.putSingle("codeKind", "ALPHANUMERIC");
        form.putSingle("amountSource", "ENCODED");
        form.putSingle("priority", "20");
        form.putSingle("active", "on");
        form.putSingle("depositLine", "on");
        form.putSingle("manualAmountOnAllNines", "on");
        form.putSingle("islandCodes", " AVANT;COMPTOIR ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<CouponType> construction = mockConstruction(CouponType.class)) {
            stubFind(panache, "STORE", existing);
            Response response = resource.saveType(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(construction.constructed().isEmpty());
            assertEquals("Bon enseigne", existing.label);
            assertEquals("50", existing.prefix);
            assertEquals(14, existing.codeLength);
            assertEquals(CouponField.Kind.ALPHANUMERIC, existing.codeKind);
            assertEquals(20, existing.priority);
            assertTrue(existing.active);
            assertTrue(existing.depositLine);
            // BO-03-06-12 : la case cochée administre la règle du montant 9999.
            assertTrue(existing.manualAmountOnAllNines);
            // BO-03-06-07 : les îlots acceptant la plage, nettoyés.
            assertEquals("AVANT;COMPTOIR", existing.islandCodes);
            verify(resource.couponPatterns).validate("50", 14,
                    CouponType.AmountSource.ENCODED, List.of(price));
            verify(resource.couponPatterns).regenerate(existing);
        }
    }

    /**
     * {@code saveType} treats a stored range holding no field list as holding
     * no field (second leg of the kept-fields ternary).
     */
    @Test
    void saveTypeToleratesAStoredRangeWithoutFieldList() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("STORE");
        existing.fields = null;
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "STORE");
        form.putSingle("codeLength", "0");
        form.putSingle("codeKind", "NUMERIC");
        form.putSingle("amountSource", "MANUAL");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "STORE", existing);
            resource.saveType(form);
            assertNull(existing.codeLength);
            verify(resource.couponPatterns).validate(null, null,
                    CouponType.AmountSource.MANUAL, List.of());
        }
    }

    // ----------------------------------------------------------- SAVE FIELD

    /**
     * {@code saveField} refuses an unknown range (first guard).
     */
    @Test
    void saveFieldRejectsAnUnknownRange() {
        AdminCouponResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "NOPE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "NOPE", null);
            Response response = resource.saveField(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code saveField} refuses an unknown role (second guard).
     */
    @Test
    void saveFieldRejectsAnUnknownRole() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("role", "COULEUR");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.saveField(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code saveField} refuses an unknown character kind (third guard).
     */
    @Test
    void saveFieldRejectsAnUnknownKind() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("role", "PRICE");
        form.putSingle("kind", "RUNES");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.saveField(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code saveField} refuses a position the generator rejects, and leaves
     * the stored fields untouched.
     */
    @Test
    void saveFieldRefusesAnInvalidPositionWithoutWriting() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("role", "PRICE");
        form.putSingle("kind", "NUMERIC");
        form.putSingle("offsetPosition", "12");
        form.putSingle("fieldLength", "6");
        when(resource.couponPatterns.validate(any(), any(), any(), any()))
                .thenReturn(List.of("Le champ PRICE dépasse la longueur du code."));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.saveField(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            assertTrue(existing.fields.isEmpty());
            verify(resource.couponPatterns, never()).regenerate(any());
        }
    }

    /**
     * {@code saveField} adds a position, reading its optional layout,
     * currency and decimals (present arms).
     */
    @Test
    void saveFieldAddsAPositionWithItsOptionalSettings() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("role", "DATE_END");
        form.putSingle("kind", "NUMERIC");
        form.putSingle("offsetPosition", "3");
        form.putSingle("fieldLength", "6");
        form.putSingle("decimals", "0");
        form.putSingle("dateFormat", "DDMMYY");
        form.putSingle("currency", "FRF");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.saveField(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertEquals(1, existing.fields.size());
            CouponField added = existing.fields.get(0);
            assertSame(existing, added.couponType);
            assertEquals(CouponField.Role.DATE_END, added.role);
            assertEquals(3, added.offsetPosition);
            assertEquals(6, added.fieldLength);
            assertEquals(0, added.decimals);
            assertEquals(CouponField.DateFormat.DDMMYY, added.dateFormat);
            assertEquals(CouponField.PriceCurrency.FRF, added.currency);
            verify(resource.couponPatterns).regenerate(existing);
        }
    }

    /**
     * {@code saveField} replaces the position already carrying the role, and
     * leaves the optional settings null when nothing is posted (absent arms),
     * falling back on a malformed position and length.
     */
    @Test
    void saveFieldReplacesThePositionCarryingTheRole() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        CouponField old = withField(existing, CouponField.Role.PRICE);
        withField(existing, CouponField.Role.TPV_NUMBER);
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("role", "PRICE");
        form.putSingle("kind", "NUMERIC");
        form.putSingle("offsetPosition", "x");
        form.putSingle("fieldLength", "y");
        form.putSingle("decimals", "-1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            resource.saveField(form);
            assertEquals(2, existing.fields.size());
            assertFalse(existing.fields.contains(old));
            CouponField added = existing.fields.get(1);
            assertEquals(CouponField.Role.PRICE, added.role);
            assertEquals(-1, added.offsetPosition);
            assertEquals(0, added.fieldLength);
            assertNull(added.decimals);
            assertNull(added.dateFormat);
            assertNull(added.currency);
        }
    }

    /**
     * {@code saveField} gives a stored range with no field list one before
     * writing into it.
     */
    @Test
    void saveFieldGivesAFieldListToARangeWithoutOne() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        existing.fields = null;
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("role", "PRICE");
        form.putSingle("kind", "NUMERIC");
        form.putSingle("offsetPosition", "6");
        form.putSingle("fieldLength", "4");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            resource.saveField(form);
            assertEquals(1, existing.fields.size());
        }
    }

    // --------------------------------------------------------- DELETE FIELD

    /**
     * {@code deleteField} refuses an unknown range (first guard).
     */
    @Test
    void deleteFieldRejectsAnUnknownRange() {
        AdminCouponResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "NOPE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "NOPE", null);
            Response response = resource.deleteField(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code deleteField} refuses an unknown role (second guard).
     */
    @Test
    void deleteFieldRejectsAnUnknownRole() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("role", "COULEUR");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.deleteField(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code deleteField} reports that nothing carried the role (not-removed arm).
     */
    @Test
    void deleteFieldReportsWhenNothingCarriesTheRole() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        withField(existing, CouponField.Role.TPV_NUMBER);
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("role", "PRICE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.deleteField(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            assertEquals(1, existing.fields.size());
            verify(resource.couponPatterns, never()).regenerate(any());
        }
    }

    /**
     * {@code deleteField} reports a range holding no field list as carrying
     * nothing (short-circuit arm).
     */
    @Test
    void deleteFieldReportsARangeWithoutFieldList() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        existing.fields = null;
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("role", "PRICE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.deleteField(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code deleteField} removes the position and regenerates the patterns.
     */
    @Test
    void deleteFieldRemovesThePositionAndRegenerates() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        withField(existing, CouponField.Role.PRICE);
        existing.fields.add(null);
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("role", "PRICE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.deleteField(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertEquals(1, existing.fields.size());
            assertNull(existing.fields.get(0));
            verify(resource.couponPatterns).regenerate(existing);
        }
    }

    // -------------------------------------------------------- SAVE CONTROL

    /**
     * {@code saveControl} refuses an unknown range (first guard).
     */
    @Test
    void saveControlRejectsAnUnknownRange() {
        AdminCouponResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "NOPE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "NOPE", null);
            Response response = resource.saveControl(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code saveControl} refuses an unknown control (second guard).
     */
    @Test
    void saveControlRejectsAnUnknownControl() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("kind", "METEO");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.saveControl(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code saveControl} adds a control with its level and its wording.
     */
    @Test
    void saveControlAddsAControl() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("kind", "EXPIRED");
        form.putSingle("level", "BLOCK");
        form.putSingle("message", "BON PERIME");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.saveControl(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertEquals(1, existing.controls.size());
            CouponControl added = existing.controls.get(0);
            assertSame(existing, added.couponType);
            assertEquals(CouponControl.Kind.EXPIRED, added.kind);
            assertEquals(AlertLevel.BLOCK, added.level);
            assertEquals("BON PERIME", added.message);
        }
    }

    /**
     * {@code saveControl} replaces the control already carrying the kind, maps
     * a blank wording to null and an unreadable level to silence.
     */
    @Test
    void saveControlReplacesTheControlOfThatKind() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        CouponControl old = new CouponControl();
        old.kind = CouponControl.Kind.EXPIRED;
        old.level = AlertLevel.BLOCK;
        existing.controls.add(old);
        existing.controls.add(null);
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("kind", "EXPIRED");
        form.putSingle("level", "");
        form.putSingle("message", "   ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            resource.saveControl(form);
            assertFalse(existing.controls.contains(old));
            CouponControl added = existing.controls.get(existing.controls.size() - 1);
            assertEquals(AlertLevel.NONE, added.level);
            assertNull(added.message);
        }
    }

    /**
     * {@code saveControl} gives a stored range with no control list one before
     * writing into it.
     */
    @Test
    void saveControlGivesAControlListToARangeWithoutOne() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        existing.controls = null;
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("kind", "DUPLICATE");
        form.putSingle("level", "INFO");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            resource.saveControl(form);
            assertEquals(1, existing.controls.size());
        }
    }

    // ------------------------------------------------------ DELETE CONTROL

    /**
     * {@code deleteControl} refuses an unknown range (first guard).
     */
    @Test
    void deleteControlRejectsAnUnknownRange() {
        AdminCouponResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "NOPE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "NOPE", null);
            Response response = resource.deleteControl(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code deleteControl} refuses an unknown control (second guard).
     */
    @Test
    void deleteControlRejectsAnUnknownControl() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("kind", "METEO");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.deleteControl(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code deleteControl} reports that nothing carried the kind
     * (not-removed arm).
     */
    @Test
    void deleteControlReportsWhenNothingCarriesTheKind() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("kind", "EXPIRED");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.deleteControl(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code deleteControl} reports a range holding no control list as carrying
     * nothing (short-circuit arm).
     */
    @Test
    void deleteControlReportsARangeWithoutControlList() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        existing.controls = null;
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("kind", "EXPIRED");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.deleteControl(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code deleteControl} removes the control of that kind.
     */
    @Test
    void deleteControlRemovesTheControl() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        CouponControl control = new CouponControl();
        control.kind = CouponControl.Kind.DUPLICATE;
        existing.controls.add(control);
        existing.controls.add(null);
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("kind", "DUPLICATE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.deleteControl(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertEquals(1, existing.controls.size());
            assertNull(existing.controls.get(0));
        }
    }

    /**
     * A posted code length of zero is read as no code length at all
     * ({@code positiveOrNull} refused arm, at the boundary).
     */
    @Test
    void aZeroCodeLengthIsReadAsAbsent() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("codeLength", "0");
        form.putSingle("codeKind", "NUMERIC");
        form.putSingle("amountSource", "MANUAL");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            resource.saveType(form);
            assertNull(existing.codeLength);
        }
    }

    /**
     * A posted code length of one is kept ({@code positiveOrNull} accepted
     * arm, at the boundary).
     */
    @Test
    void aCodeLengthOfOneIsKept() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("codeLength", "1");
        form.putSingle("codeKind", "NUMERIC");
        form.putSingle("amountSource", "MANUAL");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            resource.saveType(form);
            assertEquals(1, existing.codeLength);
            verify(resource.couponPatterns).validate(eq(null), eq(1),
                    eq(CouponType.AmountSource.MANUAL), any());
        }
    }

    // ------------------------------------------------- SAVE ARTICLE RANGE

    /**
     * {@code saveArticleRange} refuses a blank code (first guard).
     */
    @Test
    void saveArticleRangeRejectsABlankCode() {
        Response response = newResource().saveArticleRange(form());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * An unknown character kind is refused (second guard).
     */
    @Test
    void saveArticleRangeRejectsAnUnknownKind() {
        MultivaluedMap<String, String> form = articleForm("BALANCE");
        form.putSingle("codeKind", "RUNIQUE");
        Response response = newResource().saveArticleRange(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * An unknown nature of value is refused (third guard).
     */
    @Test
    void saveArticleRangeRejectsAnUnknownValueSource() {
        MultivaluedMap<String, String> form = articleForm("BALANCE");
        form.putSingle("valueSource", "VOLUME");
        Response response = newResource().saveArticleRange(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * An unknown destination is refused (BO-03-06-56/-60/-61), and a form
     * posting none keeps the ARTICLE default (both arms of the parser).
     */
    @Test
    void saveArticleRangeRejectsAnUnknownPurpose() {
        MultivaluedMap<String, String> form = articleForm("BALANCE");
        form.putSingle("purpose", "LOTERIE");
        Response response = newResource().saveArticleRange(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * A destination posted by the back office is written on the range, and the
     * page serves the four of them to the form.
     */
    @Test
    void saveArticleRangeKeepsThePostedPurpose() {
        AdminCouponResource resource = newResource();
        when(resource.couponPatterns.validateRange(any())).thenReturn(List.of());
        ArticleBarcodeRange stored = articleRange("BALANCE");
        MultivaluedMap<String, String> form = articleForm("BALANCE");
        form.putSingle("purpose", "BALANCE_TICKET");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "BALANCE", stored);
            assertTrue(resource.saveArticleRange(form)
                    .getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals(ArticleBarcodeRange.Purpose.BALANCE_TICKET, stored.purpose);
    }

    /**
     * A promotion posted beside a PROMOTION destination is written on the
     * stored range, and a blank one is stored as null rather than as an empty
     * name the register would try to arm (BO-03-06-43 — both arms of
     * {@code blankToNull}).
     */
    @Test
    void saveArticleRangeKeepsThePostedPromotion() {
        AdminCouponResource resource = newResource();
        when(resource.couponPatterns.validateRange(any())).thenReturn(List.of());
        ArticleBarcodeRange stored = articleRange("BALANCE");
        MultivaluedMap<String, String> form = articleForm("BALANCE");
        form.putSingle("purpose", "PROMOTION");
        form.putSingle("promotionCode", "  PROMO_RENTREE  ");
        form.putSingle("lookupUrl", "  https://partenaire.test/bons/{code}  ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "BALANCE", stored);
            assertTrue(resource.saveArticleRange(form)
                    .getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals(ArticleBarcodeRange.Purpose.PROMOTION, stored.purpose);
        assertEquals("PROMO_RENTREE", stored.promotionCode);
        // BO-03-06-41: the third-party service travels the same way.
        assertEquals("https://partenaire.test/bons/{code}", stored.lookupUrl);
        form.putSingle("promotionCode", "   ");
        form.putSingle("lookupUrl", "   ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "BALANCE", stored);
            assertTrue(resource.saveArticleRange(form)
                    .getLocation().toString().contains("noticeOk=true"));
        }
        assertNull(stored.promotionCode);
        assertNull(stored.lookupUrl);
    }

    /**
     * BO-03-06-43 / -41: a promotion type other than PERCENT or AMOUNT is
     * refused; PERCENT and AMOUNT are written on the range with the field of
     * the partner's answer, and a blank type means a promotion named only.
     */
    @Test
    void saveArticleRangeKeepsThePromotionDefinitionAndTheResponseField() {
        AdminCouponResource resource = newResource();
        when(resource.couponPatterns.validateRange(any())).thenReturn(List.of());
        ArticleBarcodeRange stored = articleRange("BALANCE");
        MultivaluedMap<String, String> form = articleForm("BALANCE");
        form.putSingle("promotionType", "GIFT");
        assertTrue(java.net.URLDecoder.decode(resource.saveArticleRange(form).getLocation().toString(),
                java.nio.charset.StandardCharsets.UTF_8).contains("Type de promotion inconnu."));
        form.putSingle("promotionType", "PERCENT");
        form.putSingle("lookupField", " statut.message ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "BALANCE", stored);
            assertTrue(resource.saveArticleRange(form).getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("PERCENT", stored.promotionType);
        assertEquals("statut.message", stored.lookupField);
        form.putSingle("promotionType", "AMOUNT");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "BALANCE", stored);
            resource.saveArticleRange(form);
        }
        assertEquals("AMOUNT", stored.promotionType);
        form.putSingle("promotionType", " ");
        form.putSingle("lookupField", "");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "BALANCE", stored);
            resource.saveArticleRange(form);
        }
        assertNull(stored.promotionType);
        assertNull(stored.lookupField);
    }

    /**
     * A description the generator refuses is not written at all: no row is
     * constructed and no pattern is regenerated (refused arm).
     */
    @Test
    void saveArticleRangeWritesNothingWhenTheDescriptionIsRefused() {
        AdminCouponResource resource = newResource();
        when(resource.couponPatterns.validateRange(any())).thenReturn(List.of("Zone article absente."));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ArticleBarcodeRange> construction =
                     mockConstruction(ArticleBarcodeRange.class)) {
            stubRangeFind(panache, "BALANCE", null);
            Response response = resource.saveArticleRange(articleForm("BALANCE"));
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            verify(resource.couponPatterns, never()).regenerateRange(any());
        }
    }

    /**
     * {@code saveArticleRange} inserts a range when none carries the code,
     * persisting it exactly once and carrying every administered position.
     */
    @Test
    void saveArticleRangeInsertsWhenAbsent() {
        AdminCouponResource resource = newResource();
        when(resource.couponPatterns.validateRange(any())).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ArticleBarcodeRange> construction =
                     mockConstruction(ArticleBarcodeRange.class)) {
            stubRangeFind(panache, "BALANCE", null);
            Response response = resource.saveArticleRange(articleForm("BALANCE"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(response.getLocation().toString().contains("cr%C3%A9%C3%A9e"));
            ArticleBarcodeRange created = construction.constructed().get(0);
            assertEquals("BALANCE", created.code);
            assertEquals("21", created.prefix);
            assertEquals(13, created.codeLength);
            assertEquals(2, created.articlePosition);
            assertEquals(5, created.articleLength);
            assertEquals(ArticleBarcodeRange.ValueSource.PRICE, created.valueSource);
            assertEquals(7, created.valuePosition);
            assertEquals(5, created.valueLength);
            assertEquals(2, created.valueDecimals);
            assertEquals(CouponField.PriceCurrency.EUR, created.currency);
            // BO-03-06-56/-60/-61: nothing posted means an ordinary article range.
            assertEquals(ArticleBarcodeRange.Purpose.ARTICLE, created.purpose);
            // BO-03-06-43: and no promotion posted means NULL, not an empty
            // string — an incomplete row must look incomplete.
            assertNull(created.promotionCode);
            assertNull(created.lookupUrl);
            verify(created, times(1)).persist();
            verify(resource.couponPatterns).regenerateRange(created);
        }
    }

    /**
     * {@code saveArticleRange} updates the stored row when one carries the
     * code, and never inserts a second (update arm).
     */
    @Test
    void saveArticleRangeUpdatesWhenPresent() {
        AdminCouponResource resource = newResource();
        when(resource.couponPatterns.validateRange(any())).thenReturn(List.of());
        ArticleBarcodeRange stored = articleRange("BALANCE");
        MultivaluedMap<String, String> form = articleForm("BALANCE");
        form.putSingle("prefix", "297");
        form.putSingle("codeLength", "16");
        form.putSingle("articlePosition", "3");
        form.putSingle("articleLength", "6");
        form.putSingle("valueSource", "WEIGHT");
        form.putSingle("valuePosition", "9");
        form.putSingle("valueLength", "7");
        form.putSingle("valueDecimals", "3");
        form.putSingle("currency", "FRF");
        form.putSingle("checkDigit", "on");
        form.putSingle("active", "on");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "BALANCE", stored);
            Response response = resource.saveArticleRange(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(response.getLocation().toString().contains("enregistr%C3%A9e"));
        }
        assertEquals("297", stored.prefix);
        assertEquals(16, stored.codeLength);
        assertEquals(3, stored.articlePosition);
        assertEquals(6, stored.articleLength);
        assertEquals(ArticleBarcodeRange.ValueSource.WEIGHT, stored.valueSource);
        assertEquals(9, stored.valuePosition);
        assertEquals(7, stored.valueLength);
        assertEquals(3, stored.valueDecimals);
        assertEquals(CouponField.PriceCurrency.FRF, stored.currency);
        assertTrue(stored.checkDigit);
        assertTrue(stored.active);
        assertEquals(50, stored.priority);
        verify(resource.couponPatterns).regenerateRange(stored);
    }

    /**
     * The two checkboxes and the currency fall back the other way when nothing
     * is posted: no key, no check digit, inactive, euros (the false arms).
     */
    @Test
    void saveArticleRangeFallsBackWhenNothingIsTicked() {
        AdminCouponResource resource = newResource();
        when(resource.couponPatterns.validateRange(any())).thenReturn(List.of());
        ArticleBarcodeRange stored = articleRange("BALANCE");
        MultivaluedMap<String, String> form = articleForm("BALANCE");
        form.remove("currency");
        form.putSingle("priority", "pas un nombre");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "BALANCE", stored);
            resource.saveArticleRange(form);
        }
        assertFalse(stored.checkDigit);
        assertFalse(stored.active);
        assertEquals(CouponField.PriceCurrency.EUR, stored.currency);
        assertEquals(100, stored.priority);
    }

    /**
     * {@code deleteArticleRange} refuses a code no stored range carries.
     */
    @Test
    void deleteArticleRangeRejectsAnUnknownCode() {
        AdminCouponResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "ABSENTE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "ABSENTE", null);
            Response response = resource.deleteArticleRange(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code deleteArticleRange} deletes the stored row and says so.
     */
    @Test
    void deleteArticleRangeDeletesTheStoredRow() {
        AdminCouponResource resource = newResource();
        ArticleBarcodeRange stored = mock(ArticleBarcodeRange.class);
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "BALANCE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "BALANCE", stored);
            Response response = resource.deleteArticleRange(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            verify(stored).delete();
        }
    }

    // The checkout-island CRUD moved to AdminCheckoutIslandResource when the
    // island was promoted (BO-03-10); the coupon screen only lists islands now,
    // covered by pageHandsTheTemplateTheRangesAndTheCatalogs.

    /**
     * {@code saveField} skips a null field when it purges the role: the null leg
     * of both the copy's and the stored list's removeIf predicate is exercised,
     * and the null survives the write.
     */
    @Test
    void saveFieldSkipsNullFieldsWhenReplacing() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        existing.fields.add(null);
        withField(existing, CouponField.Role.PRICE);
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("role", "PRICE");
        form.putSingle("kind", "NUMERIC");
        form.putSingle("offsetPosition", "6");
        form.putSingle("fieldLength", "4");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.saveField(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertEquals(2, existing.fields.size());
            assertTrue(existing.fields.contains(null));
        }
    }

    /**
     * {@code saveControl} keeps a control of another kind while it purges the
     * posted one: the false leg of the kind predicate is exercised.
     */
    @Test
    void saveControlKeepsControlsOfOtherKinds() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        CouponControl other = new CouponControl();
        other.kind = CouponControl.Kind.DUPLICATE;
        existing.controls.add(other);
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("kind", "EXPIRED");
        form.putSingle("level", "INFO");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.saveControl(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertEquals(2, existing.controls.size());
            assertTrue(existing.controls.contains(other));
        }
    }

    /**
     * {@code deleteControl} keeps a control of another kind and reports nothing
     * removed: the false leg of the kind predicate is exercised.
     */
    @Test
    void deleteControlKeepsControlsOfOtherKinds() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("GIFT");
        CouponControl other = new CouponControl();
        other.kind = CouponControl.Kind.EXPIRED;
        existing.controls.add(other);
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("kind", "DUPLICATE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", existing);
            Response response = resource.deleteControl(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            assertEquals(1, existing.controls.size());
            assertTrue(existing.controls.contains(other));
        }
    }
    /** BO-03: an absent optional form value retains the documented empty/default interpretation. */
    @Test
    void absentOptionalFormValueKeepsItsFallback() throws Exception {
        var method = AdminCouponResource.class.getDeclaredMethod("parsePurpose", String.class);
        method.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertEquals(com.intermarche.edge.domain.barcode.ArticleBarcodeRange.Purpose.ARTICLE, method.invoke(new AdminCouponResource(), (Object) null));
    }


    // ------------------------------------------------------------ BO-03-06-08/30/54

    /**
     * {@code saveType} refuses binding a range to a tender a scan cannot settle
     * (BO-03-06-08): only the purchase voucher and the meal ticket are offered.
     */
    @Test
    void saveTypeRejectsATenderAScanCannotSettle() {
        AdminCouponResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "GIFT");
        form.putSingle("amountSource", "MANUAL");
        form.putSingle("codeKind", "NUMERIC");
        form.putSingle("tenderCode", "CB");
        Response response = resource.saveType(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        verify(resource.couponPatterns, never()).regenerate(any());
    }

    /**
     * {@code saveType} refuses an issuing kind it cannot hold (BO-03-06-30): an
     * unknown kind, a range that is not a registry range, a registry range with
     * no administered length.
     */
    @Test
    void saveTypeRejectsAnIssuingKindItCannotHold() {
        AdminCouponResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "AVOIR");
        form.putSingle("amountSource", "REGISTRY");
        form.putSingle("codeKind", "NUMERIC");
        form.putSingle("codeLength", "40");
        form.putSingle("issuedKind", "LOTTERY");
        assertTrue(resource.saveType(form).getLocation().toString().contains("noticeOk=false"));
        form.putSingle("issuedKind", "CREDIT_NOTE");
        form.putSingle("amountSource", "MANUAL");
        assertTrue(resource.saveType(form).getLocation().toString().contains("noticeOk=false"));
        form.putSingle("amountSource", "REGISTRY");
        form.putSingle("codeLength", "");
        assertTrue(resource.saveType(form).getLocation().toString().contains("noticeOk=false"));
        verify(resource.couponPatterns, never()).regenerate(any());
    }

    /**
     * {@code saveType} writes the tender binding and the issuing kind on the
     * stored range (BO-03-06-08 and -30).
     */
    @Test
    void saveTypeKeepsTheTenderAndTheIssuingKind() {
        AdminCouponResource resource = newResource();
        CouponType existing = stored("AVOIR");
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "AVOIR");
        form.putSingle("amountSource", "REGISTRY");
        form.putSingle("codeKind", "NUMERIC");
        form.putSingle("codeLength", "40");
        form.putSingle("tenderCode", " VOUCHER ");
        form.putSingle("issuedKind", " CREDIT_NOTE ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "AVOIR", existing);
            assertTrue(resource.saveType(form).getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("VOUCHER", existing.tenderCode);
        assertEquals("CREDIT_NOTE", existing.issuedKind);
    }

    /**
     * {@code saveArticleRange} writes the register function a FUNCTION range
     * calls, and a blank one as null (BO-03-06-54).
     */
    @Test
    void saveArticleRangeKeepsThePostedFunction() {
        AdminCouponResource resource = newResource();
        when(resource.couponPatterns.validateRange(any())).thenReturn(List.of());
        ArticleBarcodeRange stored = articleRange("FONCTION");
        MultivaluedMap<String, String> form = articleForm("FONCTION");
        form.putSingle("purpose", "FUNCTION");
        form.putSingle("functionCode", "  SURVEY  ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "FONCTION", stored);
            assertTrue(resource.saveArticleRange(form).getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals(ArticleBarcodeRange.Purpose.FUNCTION, stored.purpose);
        assertEquals("SURVEY", stored.functionCode);
        form.putSingle("functionCode", "  ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRangeFind(panache, "FONCTION", stored);
            assertTrue(resource.saveArticleRange(form).getLocation().toString().contains("noticeOk=true"));
        }
        assertNull(stored.functionCode);
    }

    /**
     * BO-03-02-37: the protocol of a prepaid range is chosen from a list — an
     * unknown one is refused, a known one stored, a blank one stored as none.
     */
    @Test
    void saveTypeKeepsTheCardProtocolOfAPrepaidRange() {
        AdminCouponResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "CADEAU");
        form.putSingle("amountSource", "REGISTRY");
        form.putSingle("codeKind", "NUMERIC");
        form.putSingle("cardProtocol", "MAGIC");
        assertTrue(resource.saveType(form).getLocation().toString().contains("noticeOk=false"));
        CouponType existing = stored("CADEAU");
        form.putSingle("cardProtocol", " NEXO ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "CADEAU", existing);
            assertTrue(resource.saveType(form).getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("NEXO", existing.cardProtocol);
        form.putSingle("cardProtocol", " ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "CADEAU", existing);
            resource.saveType(form);
        }
        assertNull(existing.cardProtocol);
    }
}
