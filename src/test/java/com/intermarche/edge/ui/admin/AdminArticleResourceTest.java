package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.ArticleAttributeDefinition;
import com.intermarche.edge.domain.catalog.ArticleSelection;
import com.intermarche.edge.domain.catalog.EgalimRegime;
import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductType;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.edge.domain.util.DateTimeProvider;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import com.intermarche.edge.ui.journal.JournalPage;
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
import java.util.Map;

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
     * Builds a paged Panache query mock over the article finder: it counts the
     * rows it holds and hands them back whichever page is asked for, which is
     * what lets a test assert the window arithmetic without a database.
     *
     * @param products the products to return
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Product> productQuery(List<Product> products) {
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(products);
        when(query.count()).thenReturn((long) products.size());
        when(query.page(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(query);
        return query;
    }

    /**
     * The query the article list issues for a type-only criteria.
     *
     * @return the JPQL the resource builds
     */
    private String typeQuery() {
        return "from Product p where p.productType = :type order by p.ean";
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
     * Stubs the two referential reads every {@code list} call issues beside the
     * article query: the saved selections offered by the search form, and the
     * EGAlim regimes the list renders beside each article. Both empty, which is
     * what a test not concerned with them wants.
     *
     * @param panache the active Panache mock
     */
    private void stubSelectionList(MockedStatic<PanacheEntityBase> panache) {
        panache.when(() -> ArticleSelection.list("pdvCode is null order by name")).thenReturn(List.of());
        panache.when(() -> EgalimRegime.list("order by code")).thenReturn(List.of());
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            PanacheQuery<Product> products = productQuery(List.of(weighed));
            panache.when(() -> Product.find(typeQuery(),
                    Map.of("type", ProductType.WEIGHT))).thenReturn(products);
            stubSelectionList(panache);
            assertEquals(instance, resource.list(uriInfo(params), 1, null, true));
        }
        org.mockito.ArgumentCaptor<Object> captor =
                org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(resource.adminArticles).data(
                org.mockito.ArgumentMatchers.eq("page"), captor.capture());
        @SuppressWarnings("unchecked")
        JournalPage<Product> window = (JournalPage<Product>) captor.getValue();
        assertEquals(List.of(weighed), window.rows);
        assertEquals(1, window.number);
        assertEquals(1L, window.total);
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            PanacheQuery<ArticleSelection> found = priceSelection(selection);
            panache.when(() -> ArticleSelection.find("name = ?1 and pdvCode is null", "Pesés")).thenReturn(found);
            PanacheQuery<Product> products = productQuery(List.of(weighed));
            panache.when(() -> Product.find(typeQuery(),
                    Map.of("type", ProductType.WEIGHT))).thenReturn(products);
            stubSelectionList(panache);
            resource.list(uriInfo(params), 1, null, true);
        }
        verify(instance).data("selectedSelection", "Pesés");
        verify(instance).data("baseQuery", "selection=Pes%C3%A9s&");
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
            quiet(panache);
            PanacheQuery<ArticleSelection> missing = priceSelection(null);
            panache.when(() -> ArticleSelection.find("name = ?1 and pdvCode is null", "Ghost")).thenReturn(missing);
            PanacheQuery<Product> products = productQuery(List.of(unit));
            panache.when(() -> Product.find("from Product p order by p.ean")).thenReturn(products);
            stubSelectionList(panache);
            resource.list(uriInfo(params), 1, null, true);
        }
        verify(instance).data("selectedSelection", null);
        verify(instance).data("baseQuery", "");
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
     * BO-10-02-34/35: an article without its own flag shows the shop's
     * article-code display on its fiche — on when the shop shows the codes,
     * off when it does not.
     */
    @Test
    void editInitialisesTheFlagFromTheShopParameter() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticle);
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(resource.posSettingsService.showEan()).thenReturn(true);
        resource.edit(null);
        verify(instance).data("codeOnScreen", true);
        when(resource.posSettingsService.showEan()).thenReturn(false);
        assertFalse(resource.codeOnScreenDefault());
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
            quiet(panache);
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
            quiet(panache);
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
            quiet(panache);
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
            quiet(panache);
            panache.when(() -> Product.findById(9L)).thenReturn(null);
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /** LC-02-03-04: disabled, valid, missing and cyclic associations are distinguished. */
    @Test
    void linkedArticleValidationTraversesTheWholeChain() {
        AdminArticleResource resource = newResource();
        assertFalse(resource.invalidLinkedArticle("A", null));
        assertTrue(resource.invalidLinkedArticle("A", "A"));
        Product b = product("B", ProductType.UNIT);
        Product c = product("C", ProductType.UNIT);
        PanacheQuery<Product> qb = mock(PanacheQuery.class);
        PanacheQuery<Product> qc = mock(PanacheQuery.class);
        when(qb.firstResult()).thenReturn(b);
        when(qc.firstResult()).thenReturn(c);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            panache.when(() -> Product.find("ean = ?1 and active = true", "B")).thenReturn(qb);
            panache.when(() -> Product.find("ean = ?1 and active = true", "C")).thenReturn(qc);
            assertFalse(resource.invalidLinkedArticle("A", "B"));
            b.attributes.put(ProductAttributeCatalog.LINKED_ARTICLE_EAN, " C ");
            assertFalse(resource.invalidLinkedArticle("A", "B"));
            c.attributes.put(ProductAttributeCatalog.LINKED_ARTICLE_EAN, "B");
            assertTrue(resource.invalidLinkedArticle("A", "B"));
            when(qb.firstResult()).thenReturn(null);
            assertTrue(resource.invalidLinkedArticle("A", "B"));
        }
    }

    /** BO-10-07-20: the article's own forbidden-sale message is typed on the fiche, trimmed, and blank removes it. */
    @Test
    void saveForbiddenSaleMessageTypedOnTheFiche() {
        AdminArticleResource resource = newResource();
        Product source = product("A", ProductType.UNIT);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE, " RAPPEL LOT 12 ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            panache.when(() -> Product.findById(1L)).thenReturn(source);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of());
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=true"));
            assertEquals("RAPPEL LOT 12", source.attributes.get(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE));
            form.putSingle(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE, " ");
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=true"));
            assertFalse(source.attributes.containsKey(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE));
        }
    }

    /** LC-02-03-04: a valid association saves, blank removes it, and a loop changes nothing. */
    @Test
    void saveLinkedArticleValidatesBeforeAnyMutation() {
        AdminArticleResource resource = newResource();
        Product source = product("A", ProductType.UNIT);
        Product target = product("B", ProductType.UNIT);
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(target);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle("checkoutLabel", "Bottle");
        form.putSingle(ProductAttributeCatalog.LINKED_ARTICLE_EAN, " B ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            panache.when(() -> Product.findById(1L)).thenReturn(source);
            panache.when(() -> Product.find("ean = ?1 and active = true", "B")).thenReturn(query);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of());
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=true"));
            assertEquals("B", source.attributes.get(ProductAttributeCatalog.LINKED_ARTICLE_EAN));
            form.putSingle(ProductAttributeCatalog.LINKED_ARTICLE_EAN, "A");
            form.putSingle("checkoutLabel", "Must not be saved");
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=false"));
            assertEquals("Bottle", source.checkoutLabel);
            assertEquals("B", source.attributes.get(ProductAttributeCatalog.LINKED_ARTICLE_EAN));
            form.putSingle(ProductAttributeCatalog.LINKED_ARTICLE_EAN, " ");
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=true"));
            assertFalse(source.attributes.containsKey(ProductAttributeCatalog.LINKED_ARTICLE_EAN));
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
        form.putSingle("infoMessage", "  Check package  ");
        form.putSingle(ProductAttributeCatalog.INFO_MESSAGE_BLOCKING, "on");
        product.attributes.put(ProductAttributeCatalog.RECALL_LOTS, "LOT-42");
        product.attributes.put(ProductAttributeCatalog.ECO_TAX, "0.125");
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
            quiet(panache);
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of(custom));
            Response response = resource.save(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("LAIT", product.checkoutLabel);
        assertEquals("Check package", product.infoMessage);
        assertEquals("true", product.attributes.get(ProductAttributeCatalog.INFO_MESSAGE_BLOCKING));
        assertEquals("LOT-42", product.attributes.get(ProductAttributeCatalog.RECALL_LOTS));
        assertEquals("0.125", product.attributes.get(ProductAttributeCatalog.ECO_TAX));
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
            quiet(panache);
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
            quiet(panache);
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
            quiet(panache);
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
            quiet(panache);
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
            quiet(panache);
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
        // The perimeter is now the database's answer, not a sieve in Java: the
        // query resolves to the weighed article alone, so the unit one is left
        // untouched because it was never returned.
        PanacheQuery<Product> products = productQuery(List.of(weighed));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            panache.when(() -> Product.find(typeQuery(),
                    Map.of("type", ProductType.WEIGHT))).thenReturn(products);
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
        PanacheQuery<Product> products = productQuery(List.of(unit));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            panache.when(() -> Product.find("from Product p order by p.ean")).thenReturn(products);
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
        assertEquals(AdminArticleResource.boolDefs().size(), rows.size());
        assertFalse(rows.stream().anyMatch(row -> row.code.equals(ProductAttributeCatalog.LINKED_ARTICLE_EAN)));
        assertFalse(rows.stream().anyMatch(row -> row.code.equals(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE)));
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
            quiet(panache);
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
            quiet(panache);
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

    /**
     * Builds a single-result Panache query mock over the EGAlim code finder.
     *
     * @param regime the regime to return, possibly null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<EgalimRegime> regimeQuery(EgalimRegime regime) {
        PanacheQuery<EgalimRegime> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(regime);
        return query;
    }

    /**
     * {@code list} renders each EGAlim regime as the one line the article list
     * prints beside an article — the ceiling for a capped regime, the words
     * saying it has none for an uncapped one (both arms of the ternary) — and
     * reads the referential ONCE for the whole page rather than once per row.
     */
    @Test
    void listRendersTheEgalimCeilingsOncePerPage() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticles);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        Product unit = product("2", ProductType.UNIT);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            PanacheQuery<Product> products = productQuery(List.of(unit));
            panache.when(() -> Product.find("from Product p order by p.ean")).thenReturn(products);
            panache.when(() -> ArticleSelection.list("pdvCode is null order by name")).thenReturn(List.of());
            panache.when(() -> EgalimRegime.list("order by code")).thenReturn(List.of(
                    new EgalimRegime("FOOD_34", "Alimentaire", new java.math.BigDecimal("0.3400")),
                    new EgalimRegime("EXEMPT", "Hors champ", null)));
            resource.list(uriInfo(params), 1, null, true);
        }
        verify(instance).data("egalimCaps",
                Map.of("FOOD_34", "34 %", "EXEMPT", "non plafonné"));
    }

    /**
     * {@code list} over an EMPTY EGAlim referential hands the template an empty
     * map rather than null: the list then names every classified article as
     * unreferenced, which is exactly true — nothing is administered, so the
     * valuation engine caps nothing.
     */
    @Test
    void listWithoutAnyAdministeredRegime() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticles);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            PanacheQuery<Product> products = productQuery(List.of(product("2", ProductType.UNIT)));
            panache.when(() -> Product.find("from Product p order by p.ean")).thenReturn(products);
            stubSelectionList(panache);
            resource.list(uriInfo(params), 1, null, true);
        }
        verify(instance).data("egalimCaps", Map.of());
    }

    /**
     * {@code edit} of a classified article resolves its regime, so the fiche can
     * print the ceiling and the label beside the code.
     */
    @Test
    void editResolvesTheEgalimRegimeOfAClassifiedArticle() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticle);
        Product product = product("3", ProductType.UNIT);
        product.id = 3L;
        product.egalimRegime = "FOOD_34";
        EgalimRegime food = new EgalimRegime("FOOD_34", "Alimentaire", new java.math.BigDecimal("0.3400"));
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 10, 0);
        // The query mocks are built BEFORE the static mock: creating a mock inside a
        // when(...) lambda stubs a mock while another stubbing is unfinished, which
        // Mockito refuses with UnfinishedStubbingException.
        PanacheQuery<Price> noCurrentPrice = priceQuery(null);
        PanacheQuery<EgalimRegime> foundRegime = regimeQuery(food);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            quiet(panache);
            time.when(DateTimeProvider::now).thenReturn(now);
            panache.when(() -> Product.findById(3L)).thenReturn(product);
            panache.when(() -> Price.list(HISTORY_QUERY, 3L)).thenReturn(List.of());
            panache.when(() -> Price.find(CURRENT_QUERY, 3L, now)).thenReturn(noCurrentPrice);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of());
            panache.when(() -> EgalimRegime.find("code", "FOOD_34")).thenReturn(foundRegime);
            resource.edit(3L);
        }
        verify(instance).data("egalimRegime", food);
    }

    /**
     * {@code edit} of an article classified under a code NOBODY administers
     * resolves to null, which the fiche shows as an unknown regime rather than
     * as a ceiling — the case that costs money, since the engine will not cap
     * that article.
     */
    @Test
    void editOfAnArticleClassifiedUnderAnUnknownRegime() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticle);
        Product product = product("4", ProductType.UNIT);
        product.id = 4L;
        product.egalimRegime = "GHOST";
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 10, 0);
        PanacheQuery<Price> noCurrentPrice = priceQuery(null);
        PanacheQuery<EgalimRegime> noRegime = regimeQuery(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            quiet(panache);
            time.when(DateTimeProvider::now).thenReturn(now);
            panache.when(() -> Product.findById(4L)).thenReturn(product);
            panache.when(() -> Price.list(HISTORY_QUERY, 4L)).thenReturn(List.of());
            panache.when(() -> Price.find(CURRENT_QUERY, 4L, now)).thenReturn(noCurrentPrice);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of());
            panache.when(() -> EgalimRegime.find("code", "GHOST")).thenReturn(noRegime);
            resource.edit(4L);
        }
        verify(instance).data("egalimRegime", null);
    }

    /**
     * {@code edit} of an UNCLASSIFIED article never reaches the referential at
     * all — the null-code guard of the finder — and the fiche shows "non
     * classé" rather than an unknown regime: the two states are different and
     * the screen must not conflate them.
     */
    @Test
    void editOfAnUnclassifiedArticleNeverReadsTheReferential() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticle);
        Product product = product("5", ProductType.UNIT);
        product.id = 5L;
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 10, 0);
        PanacheQuery<Price> noCurrentPrice = priceQuery(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            quiet(panache);
            time.when(DateTimeProvider::now).thenReturn(now);
            panache.when(() -> Product.findById(5L)).thenReturn(product);
            panache.when(() -> Price.list(HISTORY_QUERY, 5L)).thenReturn(List.of());
            panache.when(() -> Price.find(CURRENT_QUERY, 5L, now)).thenReturn(noCurrentPrice);
            panache.when(() -> ArticleAttributeDefinition.list("order by code")).thenReturn(List.of());
            resource.edit(5L);
        }
        assertNull(product.egalimRegime);
        verify(instance).data("egalimRegime", null);
    }

    /**
     * {@code edit} with a null id resolves no regime at all: the product-null
     * arm of the ternary, which must not call the finder.
     */
    @Test
    void editWithNullIdResolvesNoEgalimRegime() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticle);
        resource.edit(null);
        verify(instance).data("egalimRegime", null);
    }

    /**
     * {@code list} clamps a page number below one back to the first page — the
     * requested-below-one arm of the clamp.
     */
    @Test
    void listClampsAPageBelowOneToTheFirst() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticles);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("type", "WEIGHT");
        Product weighed = product("1", ProductType.WEIGHT);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            PanacheQuery<Product> products = productQuery(List.of(weighed));
            panache.when(() -> Product.find(typeQuery(),
                    Map.of("type", ProductType.WEIGHT))).thenReturn(products);
            stubSelectionList(panache);
            assertEquals(instance, resource.list(uriInfo(params), 0, null, true));
        }
        org.mockito.ArgumentCaptor<Object> captor =
                org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(resource.adminArticles).data(
                org.mockito.ArgumentMatchers.eq("page"), captor.capture());
        @SuppressWarnings("unchecked")
        JournalPage<Product> window = (JournalPage<Product>) captor.getValue();
        assertEquals(1, window.number);
    }

    /**
     * Answers every finder of the fiche's referential reads with nothing: the
     * lot-20 data of the fiche and of the list (units, VAT regimes, nomenclature
     * nodes, selections) read the referential, and a test that does not care
     * about them must not fault. Registered first, so a test's own stubs win.
     *
     * @param panache the open static mock
     */
    @SuppressWarnings("unchecked")
    private void quiet(MockedStatic<PanacheEntityBase> panache) {
        PanacheQuery<Object> nothing = mock(PanacheQuery.class);
        when(nothing.list()).thenReturn(List.of());
        panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(nothing);
        panache.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class))).thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // Lot 20 (BO-02-03-03 to -44)
    // ------------------------------------------------------------------

    /**
     * Builds a single-result query answering the given first result.
     *
     * @param result the first result, possibly null
     * @param <T> the entity type
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> single(T result) {
        return mock(PanacheQuery.class, invocation -> switch (invocation.getMethod().getName()) {
            case "firstResult" -> result;
            case "list" -> List.of();
            default -> null;
        });
    }

    /**
     * Builds a posted form from name/value pairs.
     *
     * @param pairs the pairs
     * @return the form
     */
    private MultivaluedMap<String, String> posted(String... pairs) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int k = 0; k < pairs.length; k += 2) {
            form.putSingle(pairs[k], pairs[k + 1]);
        }
        return form;
    }

    /**
     * Builds a resource whose signed-in account may change only the given data.
     *
     * @param fields the changeable data
     * @return the resource
     */
    private AdminArticleResource restrictedTo(ArticleField... fields) {
        AdminArticleResource resource = newResource();
        resource.articleFieldRights = mock(ArticleFieldRights.class);
        resource.currentUser = mock(com.intermarche.edge.security.CurrentUser.class);
        when(resource.currentUser.getName()).thenReturn("chef");
        java.util.Set<ArticleField> set = java.util.EnumSet.noneOf(ArticleField.class);
        set.addAll(List.of(fields));
        when(resource.articleFieldRights.editable("chef")).thenReturn(set);
        return resource;
    }

    /**
     * Tells whether a redirect reports a success.
     *
     * @param response the redirect
     * @return true for a green notice
     */
    private boolean green(Response response) {
        return response.getLocation().toString().contains("noticeOk=true");
    }

    /**
     * Builds a regime.
     *
     * @param number the number
     * @param rate the rate
     * @param exempt whether it is an exemption
     * @return the regime
     */
    private com.intermarche.edge.domain.catalog.VatRate regime(int number, String rate, boolean exempt) {
        com.intermarche.edge.domain.catalog.VatRate regime =
                new com.intermarche.edge.domain.catalog.VatRate(number, new java.math.BigDecimal(rate), "R" + number);
        regime.exempt = exempt;
        return regime;
    }

    /**
     * The list carries the lot-20 data: the boolean criteria, the VAT regimes,
     * the nomenclature nodes, the canonical query, the EEG flow state and the
     * point of sale's selections.
     */
    @Test
    void listCarriesTheLot20Data() {
        AdminArticleResource resource = newResource();
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(resource.posSettingsService.eegEnabled()).thenReturn(true);
        resource.nodeEchelonService = mock(com.intermarche.edge.service.NodeEchelonService.class);
        when(resource.nodeEchelonService.currentPdv()).thenReturn("P1");
        TemplateInstance instance = wire(resource.adminArticles);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("plu", "12");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            PanacheQuery<Product> products = productQuery(List.of());
            panache.when(() -> Product.find(anyString(), org.mockito.ArgumentMatchers.<String, Object>anyMap()))
                    .thenReturn(products);
            resource.list(uriInfo(params), 1, null, true);
        }
        verify(instance).data("eegEnabled", true);
        verify(instance).data("criteriaQuery", "plu=12");
        verify(instance).data("attributeDefs", AdminArticleResource.boolDefs());
        assertEquals("P1", resource.currentPdv());
    }

    /**
     * The boolean definitions are the catalog's booleans only, and the family
     * nodes are the classified ones.
     */
    @Test
    void boolDefsAndFamilyNodes() {
        for (com.intermarche.edge.domain.catalog.attribute.ProductAttributeDef def : AdminArticleResource.boolDefs()) {
            assertEquals(com.intermarche.edge.domain.catalog.attribute.ProductAttributeType.BOOL, def.type());
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.catalog.ProductFamily
                    .list("nomenclature is not null order by code")).thenReturn(List.of());
            assertTrue(AdminArticleResource.familyNodes().isEmpty());
        }
    }

    /**
     * The fiche of a known article carries its sale codes, its checkout mode,
     * its unit, its VAT, its links, its supplier, its valued attributes and,
     * the flow being active, the prices its labels display.
     */
    @Test
    void detailsCarriesTheLot20DataOfAnArticle() {
        AdminArticleResource resource = newResource();
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(resource.posSettingsService.eegEnabled()).thenReturn(true);
        resource.eegGateway = mock(EegGateway.class);
        TemplateInstance page = mock(TemplateInstance.class);
        when(page.data(anyString(), any())).thenReturn(page);
        Product product = product("300", ProductType.UNIT);
        product.id = 9L;
        product.saleCodes.add("S2");
        product.saleCodes.add("S1");
        product.attributes.put(ProductAttributeCatalog.CHECKOUT_MODE, "WEIGHED");
        product.attributes.put(ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY, "6");
        product.attributes.put(ProductAttributeCatalog.SUPPLIER_NAME, "Lactalis");
        com.intermarche.edge.domain.catalog.EegProvider provider = new com.intermarche.edge.domain.catalog.EegProvider();
        provider.label = "SES";
        when(resource.eegGateway.readPrice(provider, "300")).thenReturn(new java.math.BigDecimal("2.00"));
        Price current = price(0);
        current.priceIncludingTax = new java.math.BigDecimal("1.99");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            panache.when(() -> com.intermarche.edge.domain.catalog.EegProvider.list("active = true order by code"))
                    .thenReturn(List.of(provider));
            panache.when(() -> Price.find(anyString(), any(Object[].class))).thenReturn(single(current));
            resource.details(page, product, current);
        }
        verify(page).data("saleCodes", "S1\nS2");
        verify(page).data("checkoutMode", "WEIGHED");
        verify(page).data("linkedArticleQuantity", "6");
        verify(page).data("supplierName", "Lactalis");
        verify(page).data("eegEnabled", true);
        org.mockito.ArgumentCaptor<Object> prices = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(page).data(org.mockito.ArgumentMatchers.eq("eegPrices"), prices.capture());
        @SuppressWarnings("unchecked")
        List<AdminArticleResource.EegPrice> read = (List<AdminArticleResource.EegPrice>) prices.getValue();
        assertEquals("SES", read.get(0).provider);
        assertTrue(read.get(0).differs);
    }

    /**
     * The fiche of an unknown article reads no referential; a known article
     * without parameters shows no EEG card.
     */
    @Test
    void detailsOfAnUnknownArticleReadsNothing() {
        AdminArticleResource resource = newResource();
        TemplateInstance page = mock(TemplateInstance.class);
        when(page.data(anyString(), any())).thenReturn(page);
        assertEquals(page, resource.details(page, null, null));
        verify(page).data("eegEnabled", false);
        Product product = product("300", ProductType.UNIT);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            resource.details(page, product, null);
        }
        verify(page).data("eegPrices", List.of());
    }

    /**
     * The EEG reading compares each label's price with the current one: equal,
     * absent, and without a current price.
     */
    @Test
    void eegPricesCompareWithTheCurrentPrice() {
        AdminArticleResource resource = newResource();
        resource.eegGateway = mock(EegGateway.class);
        com.intermarche.edge.domain.catalog.EegProvider same = new com.intermarche.edge.domain.catalog.EegProvider();
        com.intermarche.edge.domain.catalog.EegProvider silent = new com.intermarche.edge.domain.catalog.EegProvider();
        Product product = product("300", ProductType.UNIT);
        product.id = 9L;
        when(resource.eegGateway.readPrice(same, "300")).thenReturn(new java.math.BigDecimal("1.99"));
        Price current = price(0);
        current.priceIncludingTax = new java.math.BigDecimal("1.99");
        Price noAmount = price(0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.catalog.EegProvider.list("active = true order by code"))
                    .thenReturn(List.of(same, silent));
            panache.when(() -> Price.find(anyString(), any(Object[].class))).thenReturn(single(current));
            List<AdminArticleResource.EegPrice> read = resource.eegPrices(product);
            assertFalse(read.get(0).differs);
            assertFalse(read.get(1).differs);
            panache.when(() -> Price.find(anyString(), any(Object[].class))).thenReturn(single(null));
            assertFalse(resource.eegPrices(product).get(0).differs);
            panache.when(() -> Price.find(anyString(), any(Object[].class))).thenReturn(single(noAmount));
            assertFalse(resource.eegPrices(product).get(0).differs);
        }
    }

    /**
     * ownValue reads the article's own value only.
     */
    @Test
    void ownValueReadsTheArticleOnly() {
        assertEquals("", AdminArticleResource.ownValue(null, "X"));
        Product product = product("300", ProductType.UNIT);
        assertEquals("", AdminArticleResource.ownValue(product, ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY));
        product.attributes.put("X", "1");
        assertEquals("1", AdminArticleResource.ownValue(product, "X"));
        product.attributes = null;
        assertEquals("", AdminArticleResource.ownValue(product, "X"));
    }

    /**
     * Without the rights or the account every datum is changeable; with both,
     * the rights answer, and the names follow.
     */
    @Test
    void editableFollowsTheRights() {
        AdminArticleResource resource = newResource();
        assertEquals(ArticleField.values().length, resource.editable().size());
        resource.articleFieldRights = mock(ArticleFieldRights.class);
        assertEquals(ArticleField.values().length, resource.editable().size());
        AdminArticleResource restricted = restrictedTo(ArticleField.VAT);
        assertEquals(java.util.Set.of("VAT"), restricted.editableNames());
    }

    /**
     * Every refusal of a fiche is decided before anything is written: an
     * invalid link, an EAN another article carries (as EAN or as sale code), a
     * PLU another article carries, a clashing sale code, an unknown VAT regime,
     * an unknown or unclassified nomenclature node.
     */
    @Test
    void refusalNamesEveryClash() {
        AdminArticleResource resource = newResource();
        java.util.Set<ArticleField> all = java.util.EnumSet.allOf(ArticleField.class);
        Product product = product("300", ProductType.UNIT);
        Product other = product("400", ProductType.UNIT);
        com.intermarche.edge.domain.catalog.ProductFamily unclassified = new com.intermarche.edge.domain.catalog.ProductFamily();
        com.intermarche.edge.domain.catalog.ProductFamily classified = new com.intermarche.edge.domain.catalog.ProductFamily();
        classified.nomenclature = new com.intermarche.edge.domain.catalog.Nomenclature();
        String saleQuery = "select p from Product p join p.saleCodes c where c = ?1 and p.active = true";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            assertEquals("Article lié introuvable, inactif ou formant une boucle.",
                    resource.refusal(product, posted(ProductAttributeCatalog.LINKED_ARTICLE_EAN, "999"), all));
            panache.when(() -> Product.find("ean", "400")).thenReturn(single(other));
            assertTrue(resource.refusal(product, posted("ean", "400"), all).contains("EAN 400"));
            panache.when(() -> Product.find(saleQuery, "500")).thenReturn(single(other));
            assertTrue(resource.refusal(product, posted("ean", "500"), all).contains("EAN 500"));
            assertNull(resource.refusal(product, posted("ean", "600"), all));
            assertNull(resource.refusal(product, posted("ean", "300"), all));
            assertNull(resource.refusal(product, posted("ean", "400"), java.util.EnumSet.of(ArticleField.NAME)));
            panache.when(() -> Product.find("plu", "12")).thenReturn(single(other));
            panache.when(() -> Product.find("plu", "13")).thenReturn(single(product));
            assertTrue(resource.refusal(product, posted("plu", "12"), all).contains("PLU 12"));
            assertNull(resource.refusal(product, posted("plu", "13"), all));
            assertNull(resource.refusal(product, posted("plu", "12"), java.util.EnumSet.of(ArticleField.NAME)));
            assertTrue(resource.refusal(product, posted("saleCodes", "400"), all).contains("vente 400"));
            assertNull(resource.refusal(product, posted("saleCodes", "400"), java.util.EnumSet.of(ArticleField.NAME)));
            panache.when(() -> com.intermarche.edge.domain.catalog.VatRate.find("number", 3)).thenReturn(single(regime(3, "0.2", false)));
            assertTrue(resource.refusal(product, posted(ProductAttributeCatalog.VAT_NUMBER, "8"), all).contains("numéro 8"));
            assertNull(resource.refusal(product, posted(ProductAttributeCatalog.VAT_NUMBER, "3"), all));
            assertNull(resource.refusal(product, posted(ProductAttributeCatalog.VAT_NUMBER, "8"), java.util.EnumSet.of(ArticleField.NAME)));
            panache.when(() -> com.intermarche.edge.domain.catalog.ProductFamily.find("code", "N1")).thenReturn(single(unclassified));
            panache.when(() -> com.intermarche.edge.domain.catalog.ProductFamily.find("code", "N2")).thenReturn(single(classified));
            assertTrue(resource.refusal(product, posted("nomenclatureNode", "N0"), all).contains("code N0"));
            assertTrue(resource.refusal(product, posted("nomenclatureNode", "N1"), all).contains("code N1"));
            assertNull(resource.refusal(product, posted("nomenclatureNode", "N2"), all));
            assertNull(resource.refusal(product, posted("nomenclatureNode", "N0"), java.util.EnumSet.of(ArticleField.NAME)));
        }
    }

    /**
     * saveCodeClash names a code another article answers to by its EAN or by a
     * sale code, and none when the codes are the article's own or free.
     */
    @Test
    void saleCodeClashNamesTheFirstClash() {
        AdminArticleResource resource = newResource();
        Product product = product("300", ProductType.UNIT);
        Product other = product("400", ProductType.UNIT);
        String query = "select p from Product p join p.saleCodes c where c = ?1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            panache.when(() -> Product.find("ean", "A")).thenReturn(single(other));
            panache.when(() -> Product.find(query, "B")).thenReturn(single(other));
            panache.when(() -> Product.find("ean", "C")).thenReturn(single(product));
            panache.when(() -> Product.find(query, "C")).thenReturn(single(product));
            assertEquals("A", resource.saleCodeClash(product, java.util.Set.of("A")));
            assertEquals("B", resource.saleCodeClash(product, java.util.Set.of("B")));
            assertNull(resource.saleCodeClash(product, java.util.Set.of("C", "D")));
        }
    }

    /**
     * The typed sale codes split on blanks, commas and semicolons, the article's
     * own EAN dropped.
     */
    @Test
    void saleCodesSplitTheTypedText() {
        assertEquals(java.util.Set.of("A", "B"), AdminArticleResource.saleCodes(" A\nB;300, A ", "300"));
        assertTrue(AdminArticleResource.saleCodes(null, "300").isEmpty());
    }

    /**
     * A fiche saved with every lot-20 datum writes them all: identity, sale
     * codes, PLU, type, declared unit, checkout mode, VAT code, filing,
     * supplier, links and the valued attributes (a blank one withdrawn).
     */
    @Test
    void saveWritesTheLot20Data() {
        AdminArticleResource resource = newResource();
        Product product = product("300", ProductType.UNIT);
        product.attributes.put(ProductAttributeCatalog.RECALL_LOTS, "OLD");
        com.intermarche.edge.domain.catalog.MeasureUnit metre = new com.intermarche.edge.domain.catalog.MeasureUnit();
        metre.code = "4";
        metre.symbol = "m";
        com.intermarche.edge.domain.catalog.ProductFamily node = new com.intermarche.edge.domain.catalog.ProductFamily();
        node.nomenclature = new com.intermarche.edge.domain.catalog.Nomenclature();
        MultivaluedMap<String, String> form = posted("id", "1", "ean", "301", "name", "Tuyau", "saleCodes", "S1 S2",
                "plu", "", "productType", "WEIGHT", ProductAttributeCatalog.UNIT_CODE, "4",
                ProductAttributeCatalog.CHECKOUT_MODE, "DECIMAL_QUANTITY_TO_ENTER", ProductAttributeCatalog.VAT_NUMBER, "3",
                "nomenclatureNode", "N2", ProductAttributeCatalog.SUPPLIER_CODE, "F1",
                ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY, "6", ProductAttributeCatalog.COMPLEMENTARY_EANS, "500",
                ProductAttributeCatalog.RECALL_LOTS, " ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<com.intermarche.edge.domain.catalog.ProductFamily> families =
                        mockStatic(com.intermarche.edge.domain.catalog.ProductFamily.class)) {
            quiet(panache);
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            panache.when(() -> com.intermarche.edge.domain.catalog.MeasureUnit.find("code", "4")).thenReturn(single(metre));
            panache.when(() -> com.intermarche.edge.domain.catalog.VatRate.find("number", 3)).thenReturn(single(regime(3, "0.2", false)));
            families.when(() -> com.intermarche.edge.domain.catalog.ProductFamily.findByCode("N2")).thenReturn(node);
            assertTrue(green(resource.save(form)));
            families.verify(() -> com.intermarche.edge.domain.catalog.ProductFamily.fileUnderNomenclature(product, "N2"));
        }
        assertEquals("301", product.ean);
        assertEquals("Tuyau", product.name);
        assertEquals(java.util.Set.of("S1", "S2"), product.saleCodes);
        assertNull(product.plu);
        assertEquals(ProductType.WEIGHT, product.productType);
        assertEquals("m", product.unitName);
        assertEquals("4", product.attributes.get(ProductAttributeCatalog.UNIT_CODE));
        assertEquals("DECIMAL_QUANTITY_TO_ENTER", product.attributes.get(ProductAttributeCatalog.CHECKOUT_MODE));
        assertEquals("3", product.attributes.get(ProductAttributeCatalog.VAT_NUMBER));
        assertEquals("F1", product.attributes.get(ProductAttributeCatalog.SUPPLIER_CODE));
        assertEquals("6", product.attributes.get(ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY));
        assertEquals("500", product.attributes.get(ProductAttributeCatalog.COMPLEMENTARY_EANS));
        assertNull(product.attributes.get(ProductAttributeCatalog.RECALL_LOTS));
    }

    /**
     * A fiche saved by an account that may change nothing leaves every datum as
     * it was, and a refused fiche writes nothing.
     */
    @Test
    void saveHonoursTheRightsAndTheRefusals() {
        AdminArticleResource resource = restrictedTo();
        Product product = product("300", ProductType.UNIT);
        product.checkoutLabel = "KEEP";
        product.active = true;
        product.attributes.put(ProductAttributeCatalog.BULKY, "true");
        MultivaluedMap<String, String> form = posted("id", "1", "checkoutLabel", "NEW", "ean", "301",
                ProductAttributeCatalog.UNIT_CODE, "4", "nomenclatureNode", "N2");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            assertTrue(green(resource.save(form)));
            assertEquals("KEEP", product.checkoutLabel);
            assertEquals("300", product.ean);
            assertTrue(product.active);
            assertEquals("true", product.attributes.get(ProductAttributeCatalog.BULKY));
            AdminArticleResource open = newResource();
            panache.when(() -> Product.find("plu", "12")).thenReturn(single(product("400", ProductType.UNIT)));
            assertFalse(green(open.save(posted("id", "1", "plu", "12", "checkoutLabel", "NEW"))));
            assertEquals("KEEP", product.checkoutLabel);
        }
    }

    /**
     * A free unit label is kept when no declared unit is chosen, and the
     * declared unit code is withdrawn; an unreadable type leaves the type.
     */
    @Test
    void saveKeepsAFreeUnitLabel() {
        AdminArticleResource resource = newResource();
        Product product = product("300", ProductType.UNIT);
        product.attributes.put(ProductAttributeCatalog.UNIT_CODE, "4");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            assertTrue(green(resource.save(posted("id", "1", "unitName", "rouleau", "productType", "?"))));
        }
        assertEquals("rouleau", product.unitName);
        assertNull(product.attributes.get(ProductAttributeCatalog.UNIT_CODE));
        assertEquals(ProductType.UNIT, product.productType);
    }

    /**
     * The valued attribute rows are the non-boolean catalog entries without a
     * field of their own, with the article's own values.
     */
    @Test
    void textAttributeRowsListTheValuedEntries() {
        Product product = product("300", ProductType.UNIT);
        product.attributes.put(ProductAttributeCatalog.RECALL_LOTS, "L1");
        List<AdminArticleResource.CustomAttrRow> rows = newResource().textAttributeRows(product);
        assertEquals(ProductAttributeCatalog.RECALL_LOTS, rows.get(0).code);
        assertEquals("L1", rows.get(0).value);
        for (AdminArticleResource.CustomAttrRow row : rows) {
            assertFalse(AdminArticleResource.DEDICATED_CODES.contains(row.code));
        }
    }

    /**
     * The VAT view reads the article's own regime, else the price's, else none,
     * and states the exemption whoever declares it.
     */
    @Test
    void vatViewDescribesTheAppliedVat() {
        Product own = product("300", ProductType.UNIT);
        own.attributes.put(ProductAttributeCatalog.VAT_NUMBER, "3");
        Product plain = product("400", ProductType.UNIT);
        Product exempt = product("500", ProductType.UNIT);
        exempt.attributes.put(ProductAttributeCatalog.VAT_EXEMPT, "true");
        Price price = price(0);
        price.vat = regime(9, "0", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.catalog.VatRate.find("number", 3)).thenReturn(single(regime(3, "0.10", false)));
            AdminArticleResource.VatView view = AdminArticleResource.vatView(own, null);
            assertTrue(view.fromArticle);
            assertEquals("D", view.code);
            assertEquals("10", view.rate);
            assertFalse(view.exempt);
        }
        AdminArticleResource.VatView fromPrice = AdminArticleResource.vatView(plain, price);
        assertFalse(fromPrice.fromArticle);
        assertTrue(fromPrice.exempt);
        AdminArticleResource.VatView none = AdminArticleResource.vatView(exempt, null);
        assertNull(none.code);
        assertEquals("", none.rate);
        assertEquals("", none.label);
        assertTrue(none.exempt);
        assertFalse(AdminArticleResource.vatView(plain, null).exempt);
    }

    /**
     * The nomenclature path climbs from the deepest node to the top, naming each
     * level, and is empty for an article filed nowhere.
     */
    @Test
    void nomenclaturePathClimbsTheTree() {
        Product product = product("300", ProductType.UNIT);
        com.intermarche.edge.domain.catalog.Nomenclature scheme = mock(com.intermarche.edge.domain.catalog.Nomenclature.class);
        com.intermarche.edge.domain.catalog.NomenclatureLevel family = new com.intermarche.edge.domain.catalog.NomenclatureLevel();
        family.label = "Famille";
        com.intermarche.edge.domain.catalog.ProductFamily leaf = new com.intermarche.edge.domain.catalog.ProductFamily();
        leaf.code = "1002";
        leaf.nomenclature = scheme;
        leaf.level = 1;
        com.intermarche.edge.domain.catalog.ProductFamily top = new com.intermarche.edge.domain.catalog.ProductFamily();
        top.code = "10";
        top.nomenclature = scheme;
        com.intermarche.edge.domain.catalog.ProductFamily loose = new com.intermarche.edge.domain.catalog.ProductFamily();
        loose.code = "X";
        when(scheme.levelAt(1)).thenReturn(family);
        when(scheme.parentCodeOf("1002")).thenReturn("10");
        when(scheme.parentCodeOf("10")).thenReturn(null);
        try (MockedStatic<com.intermarche.edge.domain.catalog.ProductFamily> families =
                mockStatic(com.intermarche.edge.domain.catalog.ProductFamily.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            families.when(() -> com.intermarche.edge.domain.catalog.ProductFamily.findDirectFamily(product)).thenReturn(leaf);
            families.when(() -> com.intermarche.edge.domain.catalog.ProductFamily.findByCode("10")).thenReturn(top);
            List<AdminArticleResource.PathNode> path = AdminArticleResource.nomenclaturePath(product);
            assertEquals(2, path.size());
            assertEquals("10", path.get(0).code);
            assertNull(path.get(0).level);
            assertEquals("Famille", path.get(1).level);
            families.when(() -> com.intermarche.edge.domain.catalog.ProductFamily.findDirectFamily(product)).thenReturn(loose);
            List<AdminArticleResource.PathNode> single = AdminArticleResource.nomenclaturePath(product);
            assertEquals(1, single.size());
            assertNull(single.get(0).level);
            families.when(() -> com.intermarche.edge.domain.catalog.ProductFamily.findDirectFamily(product)).thenReturn(null);
            assertTrue(AdminArticleResource.nomenclaturePath(product).isEmpty());
        }
    }

    /**
     * The perimeter of a list gesture is the applied selection, else the
     * canonical query, else the posted criteria.
     */
    @Test
    void perimeterReadsSelectionQueryOrCriteria() {
        AdminArticleResource resource = newResource();
        ArticleSelection selection = new ArticleSelection();
        selection.criteria = "plu=1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ArticleSelection.find("name = ?1 and pdvCode is null", "S")).thenReturn(single(selection));
            panache.when(() -> ArticleSelection.find("name = ?1 and pdvCode is null", "U")).thenReturn(single(null));
            assertEquals("plu=1", resource.perimeter(posted("selection", "S")).toQueryString());
            assertEquals("brand=B", resource.perimeter(posted("selection", "U", "query", "brand=B")).toQueryString());
        }
        assertEquals("plu=7", resource.perimeter(posted("plu", "7")).toQueryString());
    }

    /**
     * Deleting refuses an unknown article and an account without the right,
     * deactivates a sold article, and removes an unsold one with its prices and
     * its memberships.
     */
    @Test
    void deleteRemovesOrDeactivates() {
        Product sold = mock(Product.class);
        sold.id = 1L;
        sold.ean = "300";
        sold.active = true;
        Product unsold = mock(Product.class);
        unsold.id = 2L;
        unsold.ean = "400";
        com.intermarche.edge.domain.catalog.ProductFamily family = new com.intermarche.edge.domain.catalog.ProductFamily();
        family.products.add(unsold);
        @SuppressWarnings("unchecked")
        PanacheQuery<com.intermarche.edge.domain.catalog.ProductFamily> memberships = mock(PanacheQuery.class);
        when(memberships.list()).thenReturn(List.of(family));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertFalse(green(newResource().delete(posted())));
            panache.when(() -> Product.findById(1L)).thenReturn(sold);
            panache.when(() -> Product.findById(2L)).thenReturn(unsold);
            assertFalse(green(restrictedTo(ArticleField.NAME).delete(posted("id", "1"))));
            panache.when(() -> com.intermarche.edge.domain.sale.TicketLine.count("product.id", 1L)).thenReturn(3L);
            panache.when(() -> com.intermarche.edge.domain.sale.TicketLine.count("product.id", 2L)).thenReturn(0L);
            panache.when(() -> com.intermarche.edge.domain.catalog.ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 2L)).thenReturn(memberships);
            assertTrue(green(newResource().delete(posted("id", "1"))));
            assertFalse(sold.active);
            verify(sold, org.mockito.Mockito.never()).delete();
            assertTrue(green(newResource().delete(posted("id", "2"))));
            panache.verify(() -> Price.delete("product.id", 2L));
        }
        verify(unsold).delete();
        assertTrue(family.products.isEmpty());
    }

    /**
     * The export writes one CSV line per article of the perimeter, with its
     * current price, quoting what needs it.
     */
    @Test
    void exportWritesThePerimeter() {
        AdminArticleResource resource = newResource();
        Product priced = product("300", ProductType.UNIT);
        priced.id = 1L;
        priced.name = "Lait; entier";
        Product unpriced = product("400", ProductType.UNIT);
        unpriced.id = 2L;
        Price price = price(0);
        price.priceIncludingTax = new java.math.BigDecimal("1.20");
        price.vat = regime(1, "0.055", false);
        Price bare = price(0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<Price> prices = mockStatic(Price.class)) {
            PanacheQuery<Product> perimeter = productQuery(List.of(priced, unpriced, priced));
            panache.when(() -> Product.find("from Product p order by p.ean")).thenReturn(perimeter);
            prices.when(() -> Price.findCurrentPrice(1L)).thenReturn(price, bare);
            prices.when(() -> Price.findCurrentPrice(2L)).thenReturn(null);
            Response response = resource.export(uriInfo(new MultivaluedHashMap<>()));
            String csv = (String) response.getEntity();
            assertTrue(csv.contains("300;\"Lait; entier\";;;;;1.20;0.055;true"), csv);
            assertTrue(csv.contains("400;400;;;;;;;true"), csv);
            assertTrue(csv.contains("300;\"Lait; entier\";;;;;;;true"), csv);
        }
        assertEquals("", AdminArticleResource.cell(null));
        assertEquals("\"a\"\"b\"", AdminArticleResource.cell("a\"b"));
        assertEquals("plain", AdminArticleResource.cell("plain"));
    }

    /**
     * Sending prices to the labels is refused without parameters, with the flow
     * off and without an active provider; otherwise the current prices of the
     * active, priced articles reach every provider, a failed one turning the
     * notice red.
     */
    @Test
    void eegPushSendsThePerimeterPrices() {
        AdminArticleResource resource = newResource();
        assertFalse(green(resource.eegPush(posted())));
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        assertFalse(green(resource.eegPush(posted())));
        when(resource.posSettingsService.eegEnabled()).thenReturn(true);
        resource.eegGateway = mock(EegGateway.class);
        com.intermarche.edge.domain.catalog.EegProvider up = new com.intermarche.edge.domain.catalog.EegProvider();
        com.intermarche.edge.domain.catalog.EegProvider down = new com.intermarche.edge.domain.catalog.EegProvider();
        Product active = product("300", ProductType.UNIT);
        active.id = 1L;
        active.active = true;
        Product inactive = product("400", ProductType.UNIT);
        inactive.id = 2L;
        inactive.active = false;
        Product unpriced = product("500", ProductType.UNIT);
        unpriced.id = 3L;
        unpriced.active = true;
        Product noAmount = product("600", ProductType.UNIT);
        noAmount.id = 4L;
        noAmount.active = true;
        Price price = price(0);
        price.priceIncludingTax = new java.math.BigDecimal("1.999");
        java.util.Map<String, java.math.BigDecimal> expected = java.util.Map.of("300", new java.math.BigDecimal("2.00"));
        when(resource.eegGateway.push(up, expected)).thenReturn(true);
        when(resource.eegGateway.push(down, expected)).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<Price> prices = mockStatic(Price.class)) {
            panache.when(() -> com.intermarche.edge.domain.catalog.EegProvider.list("active = true order by code"))
                    .thenReturn(List.of());
            assertFalse(green(resource.eegPush(posted())));
            panache.when(() -> com.intermarche.edge.domain.catalog.EegProvider.list("active = true order by code"))
                    .thenReturn(List.of(up, down));
            PanacheQuery<Product> perimeter = productQuery(List.of(active, inactive, unpriced, noAmount));
            panache.when(() -> Product.find("from Product p order by p.ean")).thenReturn(perimeter);
            prices.when(() -> Price.findCurrentPrice(1L)).thenReturn(price);
            prices.when(() -> Price.findCurrentPrice(2L)).thenReturn(price);
            prices.when(() -> Price.findCurrentPrice(3L)).thenReturn(null);
            Price unpricedAmount = price(0);
            prices.when(() -> Price.findCurrentPrice(4L)).thenReturn(unpricedAmount);
            Response partial = resource.eegPush(posted());
            assertFalse(green(partial));
            assertTrue(partial.getLocation().toString().contains("1+fournisseur"), partial.getLocation().toString());
            when(resource.eegGateway.push(down, expected)).thenReturn(true);
            assertTrue(green(resource.eegPush(posted())));
        }
    }

    /**
     * With parameters whose EEG flow is off, the list and the fiche show no EEG
     * card.
     */
    @Test
    void theEegCardFollowsTheParameter() {
        AdminArticleResource resource = newResource();
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        TemplateInstance instance = wire(resource.adminArticles);
        TemplateInstance page = mock(TemplateInstance.class);
        when(page.data(anyString(), any())).thenReturn(page);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            quiet(panache);
            PanacheQuery<Product> products = productQuery(List.of());
            panache.when(() -> Product.find("from Product p order by p.ean")).thenReturn(products);
            resource.list(uriInfo(new MultivaluedHashMap<>()), 1, null, true);
            resource.details(page, product("300", ProductType.UNIT), null);
        }
        verify(instance).data("eegEnabled", false);
        verify(page).data("eegEnabled", false);
    }

    /**
     * A nomenclature whose parents loop stops after sixteen steps rather than
     * climbing forever.
     */
    @Test
    void aLoopingNomenclatureStops() {
        Product product = product("300", ProductType.UNIT);
        com.intermarche.edge.domain.catalog.Nomenclature scheme = mock(com.intermarche.edge.domain.catalog.Nomenclature.class);
        com.intermarche.edge.domain.catalog.ProductFamily loop = new com.intermarche.edge.domain.catalog.ProductFamily();
        loop.code = "10";
        loop.nomenclature = scheme;
        when(scheme.parentCodeOf("10")).thenReturn("10");
        try (MockedStatic<com.intermarche.edge.domain.catalog.ProductFamily> families =
                mockStatic(com.intermarche.edge.domain.catalog.ProductFamily.class)) {
            families.when(() -> com.intermarche.edge.domain.catalog.ProductFamily.findDirectFamily(product)).thenReturn(loop);
            families.when(() -> com.intermarche.edge.domain.catalog.ProductFamily.findByCode("10")).thenReturn(loop);
            assertEquals(16, AdminArticleResource.nomenclaturePath(product).size());
        }
    }
}
