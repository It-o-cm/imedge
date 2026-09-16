package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.ArticleAttributeDefinition;
import com.intermarche.pos.domain.catalog.ArticleSelection;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductType;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.domain.util.DateTimeProvider;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminArticleResource}, targeting 100% branch coverage.
 * <p>
 * The resource is a Qute-backed back-office page over the {@link Product},
 * {@link Price}, {@link ArticleAttributeDefinition} and {@link ArticleSelection}
 * static finders, which resolve to {@link PanacheEntityBase} under plain
 * {@code mvn test} and are intercepted with a single
 * {@link org.mockito.Mockito#mockStatic}; {@link DateTimeProvider} is pinned so
 * the current-price resolution is deterministic. Templates are Mockito mocks
 * whose chained {@code data(...)} returns a self-returning instance; no database
 * and no Quarkus context is booted.
 * <p>
 * Branch enumeration (every arm exercised): {@code list} covers the no-selection
 * (form criteria) arm, the applied-selection arm and the unknown-selection
 * arm, plus the matching and non-matching filter arms; {@code edit} covers the
 * null-id, unknown and found arms, the offers priority null/zero/positive arms
 * and the code-on-screen present/absent arms; {@code save} covers the id
 * null/blank/malformed/unknown-product rejections and the full write with the
 * active present/absent, custom-value put/remove and code-on-screen
 * present/absent arms; {@code bulkCodeOnScreen} covers the on and off values and
 * the matching filter; the helpers cover the map null/non-null and value
 * present/absent arms.
 */
class AdminArticleResourceTest {

    /** The exact JPQL of {@link Price#findByProduct}. */
    private static final String HISTORY_QUERY =
            "product.id = ?1 order by startDateTime desc, priority desc";

    /** The exact JPQL of {@link Price#findActivePriceAtDate}. */
    private static final String CURRENT_QUERY =
            "product.id = ?1 "
                    + "and (startDateTime is null or startDateTime <= ?2) "
                    + "and (endDateTime is null or endDateTime > ?2) "
                    + "order by priority DESC";

    /**
     * Builds a resource over mocked list and form templates.
     *
     * @return the wired resource
     */
    private AdminArticleResource newResource() {
        AdminArticleResource resource = new AdminArticleResource();
        resource.adminArticles = mock(Template.class);
        resource.adminArticle = mock(Template.class);
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
     * Builds a UriInfo whose query parameters are the given map.
     *
     * @param params the query parameters
     * @return the mocked UriInfo
     */
    private UriInfo uriInfo(MultivaluedMap<String, String> params) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(params);
        return uriInfo;
    }

    /**
     * Builds a product of the given EAN and type, active by default.
     *
     * @param ean the EAN
     * @param type the product type
     * @return the product
     */
    private Product product(String ean, ProductType type) {
        Product product = new Product();
        product.ean = ean;
        product.name = ean;
        product.productType = type;
        return product;
    }

    /**
     * Builds a price of the given priority, leaving amounts at defaults.
     *
     * @param priority the priority, possibly null
     * @return the price
     */
    private Price price(Integer priority) {
        Price price = new Price();
        price.priority = priority;
        return price;
    }

    /**
     * Builds a single-list Panache query mock over the ordered product finder.
     *
     * @param products the products to return
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Product> productQuery(List<Product> products) {
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(products);
        return query;
    }

    /**
     * Builds a single-result Panache query mock over the current-price finder.
     *
     * @param price the price to return, possibly null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Price> priceQuery(Price price) {
        PanacheQuery<Price> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(price);
        return query;
    }

    /**
     * Stubs the empty saved-selection list every {@code list} call reads.
     *
     * @param panache the active Panache mock
     */
    private void stubSelectionList(MockedStatic<PanacheEntityBase> panache) {
        panache.when(() -> ArticleSelection.list("order by name")).thenReturn(List.of());
    }

    /**
     * {@code list} without a selection filters by the form criteria, keeping the
     * matching product and dropping the mismatching one.
     */
    @Test
    void listFiltersByFormCriteria() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticles);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("type", "WEIGHT");
        Product weighed = product("1", ProductType.WEIGHT);
        Product unit = product("2", ProductType.UNIT);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Product> products = productQuery(List.of(weighed, unit));
            panache.when(() -> Product.find("order by ean")).thenReturn(products);
            stubSelectionList(panache);
            assertEquals(instance, resource.list(uriInfo(params), null, true));
        }
        verify(resource.adminArticles).data("products", List.of(weighed));
        verify(instance).data("selectedSelection", null);
    }

    /**
     * {@code list} with a known selection applies its stored criteria (selection
     * non-null arm) and echoes the selection name.
     */
    @Test
    void listAppliesKnownSelection() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticles);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("selection", "Pesés");
        ArticleSelection selection = new ArticleSelection();
        selection.name = "Pesés";
        selection.criteria = "type=WEIGHT";
        Product weighed = product("1", ProductType.WEIGHT);
        Product unit = product("2", ProductType.UNIT);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ArticleSelection> found = priceSelection(selection);
            panache.when(() -> ArticleSelection.find("name", "Pesés")).thenReturn(found);
            PanacheQuery<Product> products = productQuery(List.of(weighed, unit));
            panache.when(() -> Product.find("order by ean")).thenReturn(products);
            stubSelectionList(panache);
            resource.list(uriInfo(params), null, true);
        }
        verify(resource.adminArticles).data("products", List.of(weighed));
        verify(instance).data("selectedSelection", "Pesés");
    }

    /**
     * {@code list} with an unknown selection name falls back to the form
     * criteria (selection-null arm despite a non-null name).
     */
    @Test
    void listUnknownSelectionFallsBackToForm() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticles);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("selection", "Ghost");
        Product unit = product("2", ProductType.UNIT);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ArticleSelection> missing = priceSelection(null);
            panache.when(() -> ArticleSelection.find("name", "Ghost")).thenReturn(missing);
            PanacheQuery<Product> products = productQuery(List.of(unit));
            panache.when(() -> Product.find("order by ean")).thenReturn(products);
            stubSelectionList(panache);
            resource.list(uriInfo(params), null, true);
        }
        verify(resource.adminArticles).data("products", List.of(unit));
        verify(instance).data("selectedSelection", null);
    }

    /**
     * {@code edit} with a null id passes a null product and empty companions
     * (id-null arm, product-null arms of history/current/attributes/codeOnScreen).
     */
    @Test
    void editWithNullId() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticle);
        assertEquals(instance, resource.edit(null));
        verify(resource.adminArticle).data("product", null);
        verify(instance).data("attributes", List.of());
        verify(instance).data("customAttributes", List.of());
        verify(instance).data("codeOnScreen", false);
        verify(instance).data("currentPrice", null);
        verify(instance).data("offers", List.of());
        verify(instance).data("history", List.of());
    }

    /**
     * {@code edit} with an unknown id passes a null product (id-non-null arm,
     * findById-null arm).
     */
    @Test
    void editWithUnknownId() {
        AdminArticleResource resource = newResource();
        wire(resource.adminArticle);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(7L)).thenReturn(null);
            resource.edit(7L);
        }
        verify(resource.adminArticle).data("product", null);
    }

    /**
     * {@code edit} of a found article passes its history and only the positive
     * priority offers (offers priority null/zero skipped, positive kept), the
     * current price, and the code-on-screen flag read true from the map.
     */
    @Test
    void editFoundPassesOffersAndCurrentPrice() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticle);
        Product product = product("1", ProductType.UNIT);
        product.id = 1L;
        product.attributes.put(AdminArticleResource.CODE_ON_SCREEN, "true");
        Price base = price(0);
        Price nullPriority = price(null);
        Price offer = price(5);
        Price current = price(9);
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 10, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            time.when(DateTimeProvider::now).thenReturn(now);
            PanacheQuery<Price> currentQuery = priceQuery(current);
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            panache.when(() -> Price.list(HISTORY_QUERY, 1L)).thenReturn(List.of(base, nullPriority, offer));
            panache.when(() -> Price.find(CURRENT_QUERY, 1L, now)).thenReturn(currentQuery);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of());
            resource.edit(1L);
        }
        verify(instance).data("history", List.of(base, nullPriority, offer));
        verify(instance).data("offers", List.of(offer));
        verify(instance).data("currentPrice", current);
        verify(instance).data("codeOnScreen", true);
    }

    /**
     * {@code edit} of a found article with no offers, no current price and no
     * on-screen flag exercises the empty history/offers arms, the null
     * current-price arm and the code-on-screen flag-false arm.
     */
    @Test
    void editFoundWithoutOffersReadsCodeOff() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticle);
        Product product = product("1", ProductType.UNIT);
        product.id = 1L;
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 10, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            time.when(DateTimeProvider::now).thenReturn(now);
            PanacheQuery<Price> currentQuery = priceQuery(null);
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            panache.when(() -> Price.list(HISTORY_QUERY, 1L)).thenReturn(List.of());
            panache.when(() -> Price.find(CURRENT_QUERY, 1L, now)).thenReturn(currentQuery);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of());
            resource.edit(1L);
        }
        verify(instance).data("codeOnScreen", false);
        verify(instance).data("offers", List.of());
        verify(instance).data("currentPrice", null);
    }

    /**
     * {@code save} redirects red on a null id (parseId null arm, product-null arm).
     */
    @Test
    void saveRejectsNullId() {
        AdminArticleResource resource = newResource();
        Response response = resource.save(new MultivaluedHashMap<>());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} redirects red on a blank id (parseId blank arm).
     */
    @Test
    void saveRejectsBlankId() {
        AdminArticleResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "  ");
        Response response = resource.save(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} redirects red on a malformed id (parseId catch arm).
     */
    @Test
    void saveRejectsMalformedId() {
        AdminArticleResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "abc");
        Response response = resource.save(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} redirects red when the id is valid but the product is unknown
     * (product-null arm after a successful parse).
     */
    @Test
    void saveRejectsUnknownProduct() {
        AdminArticleResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "9");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(9L)).thenReturn(null);
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code save} writes every editable field: the base data, the active flag
     * present, the well-known attribute ticked, the custom value put and the
     * code-on-screen flag present.
     */
    @Test
    void saveWritesEverything() {
        AdminArticleResource resource = newResource();
        Product product = product("3000000000001", ProductType.UNIT);
        ArticleAttributeDefinition custom = new ArticleAttributeDefinition();
        custom.code = "ORIGIN";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle("checkoutLabel", "  LAIT  ");
        form.putSingle("internalCode", "INT-1");
        form.putSingle("description", "  desc  ");
        form.putSingle("brand", "  ITM  ");
        form.putSingle("active", "on");
        form.putSingle("forbiddenToSale", "on");
        form.putSingle("ageRestriction", "18");
        form.putSingle(ProductAttributeCatalog.VAT_EXEMPT, "on");
        form.putSingle("ORIGIN", "  France  ");
        form.putSingle("codeOnScreen", "on");
        // BO-02-03-17/24/28/33: the newly-editable fiche fields.
        form.putSingle("unitName", "  L  ");
        form.putSingle("referenceWeight", "1,5");
        form.putSingle("referenceVolume", "2.0");
        form.putSingle("giftCardAmount", "10");
        form.putSingle("variableWeight", "on");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of(custom));
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("LAIT", product.checkoutLabel);
        assertEquals("desc", product.description);
        assertEquals("ITM", product.brand);
        assertTrue(product.active);
        assertTrue(product.forbiddenToSale);
        assertEquals(18, product.ageRestriction);
        assertEquals("true", product.attributes.get(ProductAttributeCatalog.VAT_EXEMPT));
        assertEquals("France", product.attributes.get("ORIGIN"));
        assertEquals("true", product.attributes.get(AdminArticleResource.CODE_ON_SCREEN));
        assertEquals("L", product.unitName);
        assertEquals(new java.math.BigDecimal("1.500"), product.referenceWeight);
        assertEquals(new java.math.BigDecimal("2.000"), product.referenceVolume);
        assertEquals(new java.math.BigDecimal("10.00"), product.giftCardAmount);
        assertTrue(product.variableWeight);
        // BO-02-03-45: the internal code is posted by this form and was the one
        // field the fiche wrote without any test noticing — deleting the write
        // used to leave the suite green.
        assertEquals("INT-1", product.internalCode);
    }

    /**
     * {@code save} clears the newly-editable fiche fields on a blank or invalid
     * value (BO-02-03-17/24/33): the {@code parseDecimal} blank arm (empty
     * referenceWeight), its parse-error arm (a non-numeric referenceVolume), the
     * {@code blankToNull} arm (empty unitName) and the checkbox-absent arm
     * (variableWeight not posted) all land as the cleared/false state.
     */
    @Test
    void saveClearsBlankOrInvalidFicheFields() {
        AdminArticleResource resource = newResource();
        Product product = product("3000000000001", ProductType.UNIT);
        product.unitName = "kg";
        product.referenceWeight = new java.math.BigDecimal("9.000");
        product.referenceVolume = new java.math.BigDecimal("9.000");
        product.giftCardAmount = new java.math.BigDecimal("5.00");
        product.variableWeight = true;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle("unitName", "   ");
        form.putSingle("referenceWeight", "");
        form.putSingle("referenceVolume", "abc");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of());
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertNull(product.unitName);
        assertNull(product.referenceWeight);
        assertNull(product.referenceVolume);
        assertNull(product.giftCardAmount);
        assertFalse(product.variableWeight);
    }

    /**
     * {@code save} clears the omitted fields: active absent deactivates, a blank
     * custom value removes the key, and an absent code-on-screen flag writes
     * false; base data blanks become null.
     */
    @Test
    void saveClearsOmittedFields() {
        AdminArticleResource resource = newResource();
        Product product = product("3000000000001", ProductType.UNIT);
        product.description = "old";
        product.brand = "old";
        product.active = true;
        product.attributes.put("ORIGIN", "old");
        ArticleAttributeDefinition custom = new ArticleAttributeDefinition();
        custom.code = "ORIGIN";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle("ORIGIN", "   ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of(custom));
            resource.save(form);
        }
        assertNull(product.description);
        assertNull(product.brand);
        assertFalse(product.active);
        assertFalse(product.attributes.containsKey("ORIGIN"));
        assertEquals("false", product.attributes.get(AdminArticleResource.CODE_ON_SCREEN));
    }

    /**
     * {@code save} treats a blank age field as unrestricted (parseAge isBlank
     * arm).
     */
    @Test
    void saveTreatsBlankAgeAsNull() {
        AdminArticleResource resource = newResource();
        Product product = product("3000000000001", ProductType.UNIT);
        product.ageRestriction = 18;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle("ageRestriction", "   ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of());
            resource.save(form);
        }
        assertNull(product.ageRestriction);
    }

    /**
     * {@code save} treats a zero or negative age as unrestricted (parseAge
     * non-positive arm of the ternary).
     */
    @Test
    void saveTreatsNonPositiveAgeAsNull() {
        AdminArticleResource resource = newResource();
        Product product = product("3000000000001", ProductType.UNIT);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle("ageRestriction", "0");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of());
            resource.save(form);
        }
        assertNull(product.ageRestriction);
    }

    /**
     * {@code save} treats a non-numeric age as unrestricted (parseAge catch arm).
     */
    @Test
    void saveTreatsMalformedAgeAsNull() {
        AdminArticleResource resource = newResource();
        Product product = product("3000000000001", ProductType.UNIT);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle("ageRestriction", "grand");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of());
            resource.save(form);
        }
        assertNull(product.ageRestriction);
    }

    /**
     * {@code bulkCodeOnScreen} with a checked value activates the flag on the
     * articles matching the criteria only (value-on arm, matching filter).
     */
    @Test
    void bulkActivatesMatching() {
        AdminArticleResource resource = newResource();
        Product weighed = product("1", ProductType.WEIGHT);
        Product unit = product("2", ProductType.UNIT);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("value", "on");
        form.putSingle("type", "WEIGHT");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Product> products = productQuery(List.of(weighed, unit));
            panache.when(() -> Product.find("order by ean")).thenReturn(products);
            Response response = resource.bulkCodeOnScreen(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(response.getLocation().toString().contains("activ"));
        }
        assertEquals("true", weighed.attributes.get(AdminArticleResource.CODE_ON_SCREEN));
        assertFalse(unit.attributes.containsKey(AdminArticleResource.CODE_ON_SCREEN));
    }

    /**
     * {@code bulkCodeOnScreen} without a value clears the flag on every article
     * (value-off arm, empty criteria matches all).
     */
    @Test
    void bulkDeactivatesAll() {
        AdminArticleResource resource = newResource();
        Product unit = product("2", ProductType.UNIT);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Product> products = productQuery(List.of(unit));
            panache.when(() -> Product.find("order by ean")).thenReturn(products);
            resource.bulkCodeOnScreen(form);
        }
        assertEquals("false", unit.attributes.get(AdminArticleResource.CODE_ON_SCREEN));
    }

    /**
     * {@code attributeRows} tolerates a null attribute map (map-null arm),
     * falling back to catalog defaults.
     */
    @Test
    void attributeRowsToleratesNullMap() {
        AdminArticleResource resource = newResource();
        Product product = new Product();
        product.attributes = null;
        List<AdminArticleResource.AttributeRow> rows = resource.attributeRows(product);
        assertEquals(ProductAttributeCatalog.CATALOG.size(), rows.size());
        for (AdminArticleResource.AttributeRow row : rows) {
            assertFalse(row.checked);
        }
    }

    /**
     * {@code attributeRows} reads a well-known value from the product when
     * present (map non-null, raw-present arm) and the default otherwise
     * (raw-absent arm).
     */
    @Test
    void attributeRowsReadsProductAndDefault() {
        AdminArticleResource resource = newResource();
        Product product = new Product();
        product.attributes.put(ProductAttributeCatalog.VAT_EXEMPT, "true");
        List<AdminArticleResource.AttributeRow> rows = resource.attributeRows(product);
        AdminArticleResource.AttributeRow vat = rows.stream()
                .filter(r -> r.code.equals(ProductAttributeCatalog.VAT_EXEMPT)).findFirst().orElseThrow();
        assertTrue(vat.checked);
        AdminArticleResource.AttributeRow bulky = rows.stream()
                .filter(r -> r.code.equals(ProductAttributeCatalog.BULKY)).findFirst().orElseThrow();
        assertFalse(bulky.checked);
    }

    /**
     * {@code customAttributeRows} reads a stored value when present and an empty
     * string when the map omits the code (map non-null, get present/absent).
     */
    @Test
    void customAttributeRowsReadsValues() {
        AdminArticleResource resource = newResource();
        Product product = new Product();
        product.attributes.put("ORIGIN", "France");
        ArticleAttributeDefinition origin = new ArticleAttributeDefinition();
        origin.code = "ORIGIN";
        origin.label = "Origine";
        ArticleAttributeDefinition other = new ArticleAttributeDefinition();
        other.code = "SEASON";
        other.label = "Saison";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleAttributeDefinition.list("order by code"))
                    .thenReturn(List.of(origin, other));
            List<AdminArticleResource.CustomAttrRow> rows = resource.customAttributeRows(product);
            assertEquals("France", rows.get(0).value);
            assertEquals("", rows.get(1).value);
        }
    }

    /**
     * {@code customAttributeRows} tolerates a null attribute map (map-null arm).
     */
    @Test
    void customAttributeRowsToleratesNullMap() {
        AdminArticleResource resource = newResource();
        Product product = new Product();
        product.attributes = null;
        ArticleAttributeDefinition origin = new ArticleAttributeDefinition();
        origin.code = "ORIGIN";
        origin.label = "Origine";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of(origin));
            List<AdminArticleResource.CustomAttrRow> rows = resource.customAttributeRows(product);
            assertEquals("", rows.get(0).value);
        }
    }

    /**
     * {@code allAttributes} returns the sorted entries of a populated map
     * (product non-null, map non-null arm).
     */
    @Test
    void allAttributesReturnsSortedEntries() {
        AdminArticleResource resource = newResource();
        Product product = new Product();
        product.attributes.put("Z", "1");
        product.attributes.put("A", "2");
        List<AdminArticleResource.AttrEntry> entries = resource.allAttributes(product);
        assertEquals("A", entries.get(0).code);
        assertEquals("Z", entries.get(1).code);
    }

    /**
     * {@code allAttributes} returns an empty list for a null product and for a
     * null map (both guard arms).
     */
    @Test
    void allAttributesToleratesNull() {
        AdminArticleResource resource = newResource();
        assertTrue(resource.allAttributes(null).isEmpty());
        Product product = new Product();
        product.attributes = null;
        assertTrue(resource.allAttributes(product).isEmpty());
    }

    /**
     * Builds a single-result Panache query mock over the selection name finder.
     *
     * @param selection the selection to return, possibly null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<ArticleSelection> priceSelection(ArticleSelection selection) {
        PanacheQuery<ArticleSelection> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(selection);
        return query;
    }
}
