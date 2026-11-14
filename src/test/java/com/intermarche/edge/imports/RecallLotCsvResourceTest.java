package com.intermarche.edge.imports;

import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link RecallLotCsvResource} (BO-02-03-44): unreadable,
 * empty and malformed files, the delta integration posing and lifting lots,
 * the unknown articles, and the FULL integration lifting what it does not
 * state.
 */
class RecallLotCsvResourceTest {

    /** The JPQL of the articles carrying recalled lots. */
    private static final String RECALLED = "select p from Product p join p.attributes a where key(a) = ?1";

    /**
     * Wraps a text as a stream.
     *
     * @param content the file
     * @return the stream
     */
    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds a single-result query.
     *
     * @param result the first result, possibly null
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Product> single(Product result) {
        return mock(PanacheQuery.class, invocation ->
                "firstResult".equals(invocation.getMethod().getName()) ? result : null);
    }

    /**
     * Builds an article.
     *
     * @param ean the EAN
     * @return the article
     */
    private Product product(String ean) {
        Product product = new Product();
        product.ean = ean;
        return product;
    }

    /**
     * An unreadable stream is a server error, an empty file and a file without
     * the EAN or the LOTS column a bad request.
     */
    @Test
    void malformedFilesAreRefused() {
        RecallLotCsvResource resource = new RecallLotCsvResource();
        InputStream broken = new InputStream() {
            /**
             * Fails every read.
             *
             * @return never
             * @throws IOException always
             */
            @Override
            public int read() throws IOException {
                throw new IOException("broken");
            }
        };
        assertEquals(500, resource.importRecallLots(broken, null).getStatus());
        assertEquals(400, resource.importRecallLots(stream("\n \n"), null).getStatus());
        assertEquals(400, resource.importRecallLots(stream("CODE|LOTS\n"), null).getStatus());
        assertEquals(400, resource.importRecallLots(stream("EAN|LOT\n"), null).getStatus());
    }

    /**
     * A delta file poses the normalized lots of the articles it names, lifts
     * the recall of an empty cell, and counts the unknown articles and the
     * lines without an EAN as errors.
     */
    @Test
    void aDeltaFilePosesAndLiftsLots() {
        RecallLotCsvResource resource = new RecallLotCsvResource();
        Product yoghurt = product("300");
        Product cheese = product("400");
        cheese.attributes.put(ProductAttributeCatalog.RECALL_LOTS, "OLD");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.find("ean", "300")).thenReturn(single(yoghurt));
            panache.when(() -> Product.find("ean", "400")).thenReturn(single(cheese));
            panache.when(() -> Product.find("ean", "999")).thenReturn(single(null));
            Response response = resource.importRecallLots(stream("EAN|LOTS\n300| L1 ; L2,L1 \n400|\n999|X\n|Y\n400"), "delta");
            assertEquals("{\"updated\":3,\"lifted\":0,\"errors\":2}", response.getEntity());
        }
        assertEquals("L1;L2", yoghurt.attributes.get(ProductAttributeCatalog.RECALL_LOTS));
        assertNull(cheese.attributes.get(ProductAttributeCatalog.RECALL_LOTS));
    }

    /**
     * A FULL file lifts the recall of every article it does not name.
     */
    @Test
    void aFullFileLiftsTheRecallsItDoesNotState() {
        RecallLotCsvResource resource = new RecallLotCsvResource();
        Product named = product("300");
        Product forgotten = product("500");
        forgotten.attributes.put(ProductAttributeCatalog.RECALL_LOTS, "Z9");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.find("ean", "300")).thenReturn(single(named));
            panache.when(() -> Product.list(RECALLED, ProductAttributeCatalog.RECALL_LOTS))
                    .thenReturn(List.of(named, forgotten));
            Response response = resource.importRecallLots(stream("EAN|LOTS\n300|A1\n"), "FULL");
            assertEquals("{\"updated\":1,\"lifted\":1,\"errors\":0}", response.getEntity());
        }
        assertEquals("A1", named.attributes.get(ProductAttributeCatalog.RECALL_LOTS));
        assertNull(forgotten.attributes.get(ProductAttributeCatalog.RECALL_LOTS));
    }

    /**
     * The helpers read a short line as empty cells and normalize a list to
     * nothing when it names no lot.
     */
    @Test
    void helpers() {
        assertEquals("", RecallLotCsvResource.cell(new String[]{"a"}, 3));
        assertEquals("", RecallLotCsvResource.normalize(" ; , "));
    }
}
