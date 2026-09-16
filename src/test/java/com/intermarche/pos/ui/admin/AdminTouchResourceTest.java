package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doNothing;
import com.intermarche.pos.domain.setting.TouchGroupSetting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link AdminTouchResource}, targeting 100% branch coverage.
 * <p>
 * A Qute-backed admin resource over the {@link ProductFamily} touch
 * configuration and the {@link Product} pictures. The inherited Panache statics
 * ({@code find}, {@code findById}, {@code count}) resolve to
 * {@link PanacheEntityBase} under plain {@code mvn test} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the declared finders
 * {@code Product.findByEan/findByInternalCode/findByPlu} are intercepted on the
 * {@code Product} class; {@link PosSettingsService} and {@link ImageResizeService}
 * are Mockito mocks. No database is booted, and no entity is constructed (the
 * resource only updates rows it looks up), so no {@code mockConstruction} is
 * needed. Every guard leg, ternary arm and null guard is covered on both arms.
 */
class AdminTouchResourceTest {

    /**
     * Builds a resource with a mocked template, settings service and resizer.
     *
     * @return the wired resource
     */
    private AdminTouchResource newResource() {
        AdminTouchResource resource = new AdminTouchResource();
        resource.adminTouches = mock(Template.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        resource.imageResizeService = mock(ImageResizeService.class);
        return resource;
    }

    /**
     * Wires a template's chained {@code data(...)} to one self-returning instance.
     *
     * @param template the template mock to wire
     * @return the instance the chain returns
     */
    private TemplateInstance wire(Template template) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a real family with the given id, code and pinned flag.
     *
     * @param id the entity id, possibly null
     * @param code the family code
     * @param pinned whether the group is pinned
     * @return the configured family
     */
    private ProductFamily family(Long id, String code, boolean pinned) {
        ProductFamily f = new ProductFamily();
        f.id = id;
        f.code = code;
        TouchGroupSetting setting = spiedSetting(code);
        setting.pinned = pinned;
        touches.put(code, setting);
        return f;
    }

    /** The touch rows this test administers, by group code. */
    private final Map<String, TouchGroupSetting> touches = new HashMap<>();

    /**
     * Returns the touch row of a group, as the screen will find and write it.
     *
     * @param code the group code
     * @return its touch row
     */
    private TouchGroupSetting touch(String code) {
        return touches.computeIfAbsent(code, this::spiedSetting);
    }

    /**
     * Builds a touch row whose {@code persist()} does nothing.
     * <p>
     * The screen creates the row the first time a group is configured, and a
     * real {@code persist()} reaches for the CDI container, which no unit test
     * has. The spy keeps the real fields — they are what the assertions read —
     * and silences the write.
     *
     * @param code the group code
     * @return the spied row
     */
    private TouchGroupSetting spiedSetting(String code) {
        TouchGroupSetting setting = spy(TouchGroupSetting.defaults(code));
        doNothing().when(setting).persist();
        return setting;
    }

    /**
     * Stubs the touch referential on an active static mock: the lookup by group
     * code, the whole-configuration read and the pinned count.
     *
     * @param mocked the active PanacheEntityBase static mock
     */
    private void stubTouches(MockedStatic<PanacheEntityBase> mocked) {
        for (Map.Entry<String, TouchGroupSetting> entry : touches.entrySet()) {
            PanacheQuery<TouchGroupSetting> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(entry.getValue());
            mocked.when(() -> TouchGroupSetting.find("familyCode", entry.getKey()))
                    .thenReturn(query);
        }
        mocked.when(() -> TouchGroupSetting.list("order by familyCode"))
                .thenReturn(new ArrayList<>(touches.values()));
    }

    /**
     * Builds a real product with the given EAN and name.
     *
     * @param ean the EAN
     * @param name the commercial name
     * @return the configured product
     */
    private Product product(String ean, String name) {
        Product p = new Product();
        p.ean = ean;
        p.name = name;
        return p;
    }

    /**
     * Builds a list-returning Panache query mock.
     *
     * @param results the list to return
     * @param <T> the entity type
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> listQuery(List<T> results) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(results);
        return query;
    }

    /**
     * Builds a form pre-filled from alternating key/value pairs (repeated keys
     * accumulate into a list).
     *
     * @param pairs the alternating keys and values
     * @return the populated form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    /**
     * Asserts a 303 redirect whose location carries the expected notice outcome.
     *
     * @param response the response under test
     * @param ok the expected noticeOk flag
     */
    private void assertRedirect(Response response, boolean ok) {
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=" + ok),
                response.getLocation().toString());
    }

    /**
     * {@code list} renders the group rows, counting the pinned ones (both arms of
     * the pinned test) and echoing the order mode and page size.
     */
    @Test
    void listRendersRowsAndCountsPinned() {
        AdminTouchResource resource = newResource();
        TemplateInstance instance = wire(resource.adminTouches);
        when(resource.posSettingsService.touchDisplayOrder()).thenReturn("CUSTOM");
        when(resource.posSettingsService.touchGroupsPerPage()).thenReturn(8);
        ProductFamily pinned = family(1L, "A", true);
        ProductFamily plain = family(2L, "B", false);
        PanacheQuery<ProductFamily> query = listQuery(List.of(pinned, plain));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTouches(mocked);
            mocked.when(() -> ProductFamily.find("order by code")).thenReturn(query);
            assertSame(instance, resource.list(null, true));
        }
    }

    /**
     * {@code saveConfig} reports the group as not found when no id is posted
     * (id-null ternary arm).
     */
    @Test
    void saveConfigNoId() {
        AdminTouchResource resource = newResource();
        assertRedirect(resource.saveConfig(form()), false);
    }

    /**
     * {@code saveConfig} reports the group as not found on a blank id (parseLong
     * isBlank leg) and on a malformed id (parseLong catch).
     */
    @Test
    void saveConfigBlankAndMalformedId() {
        AdminTouchResource resource = newResource();
        assertRedirect(resource.saveConfig(form("id", "   ")), false);
        assertRedirect(resource.saveConfig(form("id", "x")), false);
    }

    /**
     * {@code saveConfig} reports the group as not found when the id resolves to
     * no family (id non-null ternary arm, family-null arm).
     */
    @Test
    void saveConfigMissingFamily() {
        AdminTouchResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTouches(mocked);
            mocked.when(() -> ProductFamily.findById(7L)).thenReturn(null);
            assertRedirect(resource.saveConfig(form("id", "7")), false);
        }
    }

    /**
     * {@code saveConfig} rejects an unknown touch size (SIZES.contains false arm).
     */
    @Test
    void saveConfigInvalidSize() {
        AdminTouchResource resource = newResource();
        ProductFamily f = family(3L, "C", false);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTouches(mocked);
            mocked.when(() -> ProductFamily.findById(3L)).thenReturn(f);
            assertRedirect(resource.saveConfig(form("id", "3", "buttonSize", "HUGE")), false);
        }
    }

    /**
     * {@code saveConfig} rejects a display order that is missing (null leg),
     * blank (isBlank leg), malformed (catch) or negative (negative arm).
     */
    @Test
    void saveConfigInvalidOrder() {
        AdminTouchResource resource = newResource();
        ProductFamily f = family(4L, "D", false);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTouches(mocked);
            mocked.when(() -> ProductFamily.findById(4L)).thenReturn(f);
            assertRedirect(resource.saveConfig(form("id", "4", "buttonSize", "NORMAL")), false);
            assertRedirect(resource.saveConfig(form("id", "4", "buttonSize", "NORMAL", "displayOrder", "  ")), false);
            assertRedirect(resource.saveConfig(form("id", "4", "buttonSize", "NORMAL", "displayOrder", "x")), false);
            assertRedirect(resource.saveConfig(form("id", "4", "buttonSize", "NORMAL", "displayOrder", "-1")), false);
        }
    }

    /**
     * {@code saveConfig} rejects a sales volume that is missing (null leg), blank
     * (isBlank leg), malformed (catch) or negative (negative arm).
     */
    @Test
    void saveConfigInvalidVolume() {
        AdminTouchResource resource = newResource();
        ProductFamily f = family(5L, "E", false);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTouches(mocked);
            mocked.when(() -> ProductFamily.findById(5L)).thenReturn(f);
            assertRedirect(resource.saveConfig(form("id", "5", "buttonSize", "NORMAL", "displayOrder", "0")), false);
            assertRedirect(resource.saveConfig(form("id", "5", "buttonSize", "NORMAL", "displayOrder", "0", "salesVolume", " ")), false);
            assertRedirect(resource.saveConfig(form("id", "5", "buttonSize", "NORMAL", "displayOrder", "0", "salesVolume", "x")), false);
            assertRedirect(resource.saveConfig(form("id", "5", "buttonSize", "NORMAL", "displayOrder", "0", "salesVolume", "-2")), false);
        }
    }

    /**
     * {@code saveConfig} stores a valid size (upper-cased), order and volume (the
     * all-valid arm).
     */
    @Test
    void saveConfigSuccess() {
        AdminTouchResource resource = newResource();
        ProductFamily f = family(6L, "F", false);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTouches(mocked);
            mocked.when(() -> ProductFamily.findById(6L)).thenReturn(f);
            Response response = resource.saveConfig(
                    form("id", "6", "buttonSize", "large", "displayOrder", "3", "salesVolume", "99"));
            assertRedirect(response, true);
            assertEquals("LARGE", touch("F").buttonSize);
            assertEquals(3, touch("F").displayOrder);
            assertEquals(99L, touch("F").salesVolume);
        }
    }

    /**
     * BO-03-01-08: SMALL is one of the three administered sizes and is written
     * as typed, upper-cased.
     *
     * <p>Each of these three cases starts from a SENTINEL size rather than from
     * the helper's default, so none of them can pass because the family already
     * carried the expected value.
     */
    @Test
    void saveConfigStoresTheSmallSize() {
        assertSizeIsStored("small", "SMALL");
    }

    /**
     * BO-03-01-08: NORMAL is one of the three administered sizes and is written
     * as typed, upper-cased.
     */
    @Test
    void saveConfigStoresTheNormalSize() {
        assertSizeIsStored("normal", "NORMAL");
    }

    /**
     * BO-03-01-08: LARGE is one of the three administered sizes and is written
     * as typed, upper-cased.
     */
    @Test
    void saveConfigStoresTheLargeSize() {
        assertSizeIsStored("large", "LARGE");
    }

    /**
     * Posts a button size on a family carrying a sentinel size, and asserts the
     * screen accepted it and stored the upper-cased form.
     *
     * @param posted the size as the form spells it
     * @param stored the size as the family must carry it afterwards
     */
    private void assertSizeIsStored(String posted, String stored) {
        AdminTouchResource resource = newResource();
        ProductFamily f = family(6L, "F", false);
        touch("F").buttonSize = "SENTINEL";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTouches(mocked);
            mocked.when(() -> ProductFamily.findById(6L)).thenReturn(f);
            Response response = resource.saveConfig(
                    form("id", "6", "buttonSize", posted, "displayOrder", "0", "salesVolume", "0"));
            assertRedirect(response, true);
            assertEquals(stored, touch("F").buttonSize);
        }
    }

    /**
     * {@code togglePin} reports the group as not found when no id resolves.
     */
    @Test
    void togglePinMissingFamily() {
        AdminTouchResource resource = newResource();
        assertRedirect(resource.togglePin(form()), false);
    }

    /**
     * {@code togglePin} refuses to pin a group once the pinned quota is reached
     * (not-pinned leg true, count-at-limit leg true).
     */
    @Test
    void togglePinRefusedAtQuota() {
        AdminTouchResource resource = newResource();
        ProductFamily f = family(8L, "G", false);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTouches(mocked);
            mocked.when(() -> ProductFamily.findById(8L)).thenReturn(f);
            mocked.when(() -> TouchGroupSetting.count("pinned", true)).thenReturn(4L);
            assertRedirect(resource.togglePin(form("id", "8")), false);
            assertFalse(touch("G").pinned);
        }
    }

    /**
     * {@code togglePin} pins a group below the quota (not-pinned leg true,
     * count-under-limit leg false; the pinned-state ternary true arm).
     */
    @Test
    void togglePinPinsBelowQuota() {
        AdminTouchResource resource = newResource();
        ProductFamily f = family(9L, "H", false);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTouches(mocked);
            mocked.when(() -> ProductFamily.findById(9L)).thenReturn(f);
            mocked.when(() -> TouchGroupSetting.count("pinned", true)).thenReturn(2L);
            assertRedirect(resource.togglePin(form("id", "9")), true);
            assertTrue(touch("H").pinned);
        }
    }

    /**
     * {@code togglePin} unpins an already-pinned group without consulting the
     * quota (not-pinned leg false, short-circuit; the pinned-state ternary false
     * arm).
     */
    @Test
    void togglePinUnpins() {
        AdminTouchResource resource = newResource();
        ProductFamily f = family(10L, "I", true);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTouches(mocked);
            mocked.when(() -> ProductFamily.findById(10L)).thenReturn(f);
            assertRedirect(resource.togglePin(form("id", "10")), true);
            assertFalse(touch("I").pinned);
        }
    }

    /**
     * {@code saveImages} reports nothing to import on each empty-input leg: no
     * codes list, no images list, and an empty codes list.
     */
    @Test
    void saveImagesEmptyInput() {
        AdminTouchResource resource = newResource();
        assertRedirect(resource.saveImages(form()), false);
        assertRedirect(resource.saveImages(form("imgCode", "111")), false);
        MultivaluedMap<String, String> emptyCodes = new MultivaluedHashMap<>();
        emptyCodes.put("imgCode", List.of());
        emptyCodes.put("imgData", List.of("data"));
        assertRedirect(resource.saveImages(emptyCodes), false);
    }

    /**
     * {@code saveImages} skips every unusable pair: a blank code, a missing image
     * (list shorter than codes), a blank image, an unresolved code and a picture
     * the resizer rejects — attaching none, so the outcome reports failure.
     */
    @Test
    void saveImagesSkipsAllUnusablePairs() {
        AdminTouchResource resource = newResource();
        MultivaluedMap<String, String> f = new MultivaluedHashMap<>();
        // code[0] blank; code[1] ok but image blank; code[2] unknown; code[3] resize fails; code[4] no image (shorter list)
        f.put("imgCode", List.of("  ", "222", "333", "444", "555"));
        f.put("imgData", List.of("d0", "  ", "d2", "d3"));
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("333")).thenReturn(null);
            products.when(() -> Product.findByInternalCode("333")).thenReturn(null);
            products.when(() -> Product.findByPlu("333")).thenReturn(null);
            Product resizeFail = product("444", "Fails");
            products.when(() -> Product.findByEan("444")).thenReturn(resizeFail);
            when(resource.imageResizeService.resizeToDataUri("d3"))
                    .thenThrow(new IllegalArgumentException("bad"));
            Response response = resource.saveImages(f);
            assertRedirect(response, false);
            assertNull(resizeFail.imageData);
        }
    }

    /**
     * {@code saveImages} attaches a resized picture to a resolved article (the
     * success arm, outcome reports success), resolving by EAN.
     */
    @Test
    void saveImagesAttachesResizedPicture() {
        AdminTouchResource resource = newResource();
        Product target = product("111", "Pomme");
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("111")).thenReturn(target);
            when(resource.imageResizeService.resizeToDataUri("raw")).thenReturn("data:image/png;base64,ZZ");
            Response response = resource.saveImages(form("imgCode", "111", "imgData", "raw"));
            assertRedirect(response, true);
            assertEquals("data:image/png;base64,ZZ", target.imageData);
        }
    }

    /**
     * {@code clearImage} reports the article as not found on a blank code (the
     * ternary null arm) and on an unresolved code (resolve chain all null).
     */
    @Test
    void clearImageNotFound() {
        AdminTouchResource resource = newResource();
        assertRedirect(resource.clearImage(form()), false);
        assertRedirect(resource.clearImage(form("code", "  ")), false);
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("999")).thenReturn(null);
            products.when(() -> Product.findByInternalCode("999")).thenReturn(null);
            products.when(() -> Product.findByPlu("999")).thenReturn(null);
            assertRedirect(resource.clearImage(form("code", "999")), false);
        }
    }

    /**
     * {@code clearImage} clears the picture of a resolved article, resolving via
     * the internal code (EAN null, internal-code found arm).
     */
    @Test
    void clearImageClearsViaInternalCode() {
        AdminTouchResource resource = newResource();
        Product target = product("111", "Pomme");
        target.imageData = "data:something";
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("INT1")).thenReturn(null);
            products.when(() -> Product.findByInternalCode("INT1")).thenReturn(target);
            Response response = resource.clearImage(form("code", "INT1"));
            assertRedirect(response, true);
            assertNull(target.imageData);
        }
    }

    /**
     * {@code saveImages} resolves an article by PLU when the EAN and internal
     * code both miss (the last leg of the resolve chain).
     */
    @Test
    void saveImagesResolvesByPlu() {
        AdminTouchResource resource = newResource();
        Product target = product("777", "Banane");
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("PLU7")).thenReturn(null);
            products.when(() -> Product.findByInternalCode("PLU7")).thenReturn(null);
            products.when(() -> Product.findByPlu("PLU7")).thenReturn(target);
            when(resource.imageResizeService.resizeToDataUri("raw")).thenReturn("data:x");
            Response response = resource.saveImages(form("imgCode", "PLU7", "imgData", "raw"));
            assertRedirect(response, true);
            assertEquals("data:x", target.imageData);
        }
    }
}
