package com.intermarche.edge.imports;

import com.intermarche.edge.domain.catalog.Product;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** LC-02-03-11: message-only feed changes are applied even when the identity checksum is unchanged. */
class ArticleMessageImportTest {
    /** Present, equal, changed and cleared messages respect the optional CSV contract. */
    @Test
    void messageChangesAreAppliedWithoutAnIdentityChange() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product product = new Product();
        product.id = 42L;
        product.ean = "111";
        Map<String, Integer> header = Map.of("EAN", 0, "NAME", 1, "INFO_MESSAGE", 2, "ATTRIBUTES", 3);
        String[] parts = {"111", "Milk", "Check package", "INFO_MESSAGE_BLOCKING=true"};
        var data = new ImporterCsvResource.LineData(1, header, parts, "EAN");
        var checksum = ProductCsvResource.class.getDeclaredMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        checksum.setAccessible(true);
        product.attributes.put("INFO_MESSAGE_BLOCKING", "true");
        product.checksum = (Integer) checksum.invoke(resource, data, product);
        Map<String, Object> products = new HashMap<>();
        products.put("111", product);
        int[] counts = {0, 0};
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(42L)).thenReturn(product);
            resource.processLineLogic(data, products, counts);
            assertEquals("Check package", product.infoMessage);
            assertEquals(1, counts[1]);
            product.checksum = (Integer) checksum.invoke(resource, data, product);
            resource.processLineLogic(data, products, counts);
            assertEquals(1, counts[1]);
            parts[2] = "Different message";
            resource.processLineLogic(data, products, counts);
            assertEquals("Different message", product.infoMessage);
            assertEquals(2, counts[1]);
            product.checksum = (Integer) checksum.invoke(resource, data, product);
            parts[2] = "";
            resource.processLineLogic(data, products, counts);
            assertEquals("", product.infoMessage);
            assertEquals(3, counts[1]);
        }
    }
}
