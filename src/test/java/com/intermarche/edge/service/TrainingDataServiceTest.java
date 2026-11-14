package com.intermarche.edge.service;

import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.people.Employee;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TrainingDataService} (BO-03-10-18): a first install
 * creating everything, a second one creating nothing, and the price computed
 * with and without a VAT regime.
 */
class TrainingDataServiceTest {

    /**
     * Builds a query answering one row.
     *
     * @param row the row, or null
     * @param <T> the row type
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> query(T row) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * On an empty base: the family, the three articles priced under the first
     * VAT regime, and the generic user, all filled before their persist.
     */
    @Test
    void aFirstInstallCreatesEverything() {
        VatRate vat = new VatRate();
        vat.rate = new BigDecimal("0.2000");
        PanacheQuery<ProductFamily> noFamily = query(null);
        PanacheQuery<VatRate> vatQuery = query(vat);
        PanacheQuery<Product> noProduct = query(null);
        PanacheQuery<Employee> noUser = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ProductFamily> families = mockConstruction(ProductFamily.class,
                        (family, context) -> family.products = new java.util.HashSet<>());
                MockedConstruction<Product> products = mockConstruction(Product.class);
                MockedConstruction<Price> prices = mockConstruction(Price.class);
                MockedConstruction<Employee> users = mockConstruction(Employee.class)) {
            panache.when(() -> ProductFamily.find("code", TrainingDataService.FAMILY_CODE)).thenReturn(noFamily);
            panache.when(() -> VatRate.find("order by number")).thenReturn(vatQuery);
            for (String[] article : TrainingDataService.ARTICLES) {
                panache.when(() -> Product.find("ean", article[0])).thenReturn(noProduct);
            }
            panache.when(() -> Employee.find("badgeId = ?1 or loginName = ?1", TrainingDataService.USER_LOGIN))
                    .thenReturn(noUser);
            panache.when(() -> Employee.find("badgeId = ?1 or loginName = ?1", TrainingDataService.USER_BADGE))
                    .thenReturn(noUser);
            assertEquals(5, new TrainingDataService().install());
            ProductFamily family = families.constructed().get(0);
            assertEquals(TrainingDataService.FAMILY_CODE, family.code);
            verify(family).persist();
            assertEquals(3, products.constructed().size());
            assertTrue(!family.products.isEmpty());
            Price first = prices.constructed().get(0);
            assertEquals(new BigDecimal("1.00"), first.priceIncludingTax);
            assertEquals(new BigDecimal("0.8333"), first.priceExcludingTax);
            Employee user = users.constructed().get(0);
            assertEquals(TrainingDataService.USER_BADGE, user.badgeId);
            assertEquals(Employee.EmployeeRole.CASHIER, user.role);
            verify(user).persist();
        }
    }

    /** A second install finds everything and creates nothing; no VAT regime prices at zero tax. */
    @Test
    void aSecondInstallCreatesNothing() {
        ProductFamily family = new ProductFamily();
        family.products = new java.util.HashSet<>();
        PanacheQuery<ProductFamily> familyQuery = query(family);
        PanacheQuery<VatRate> noVat = query(null);
        PanacheQuery<Product> product = query(new Product());
        PanacheQuery<Employee> user = query(new Employee());
        PanacheQuery<Employee> noUser = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.find("code", TrainingDataService.FAMILY_CODE)).thenReturn(familyQuery);
            panache.when(() -> VatRate.find("order by number")).thenReturn(noVat);
            for (String[] article : TrainingDataService.ARTICLES) {
                panache.when(() -> Product.find("ean", article[0])).thenReturn(product);
            }
            panache.when(() -> Employee.find("badgeId = ?1 or loginName = ?1", TrainingDataService.USER_LOGIN))
                    .thenReturn(noUser);
            panache.when(() -> Employee.find("badgeId = ?1 or loginName = ?1", TrainingDataService.USER_BADGE))
                    .thenReturn(user);
            assertEquals(0, new TrainingDataService().install());
            assertTrue(family.products.isEmpty());
        }
    }

    /** Without a VAT regime, or one without a rate, the price excluding tax equals the price. */
    @Test
    void anArticleWithoutVatIsPricedAtZeroTax() {
        ProductFamily family = new ProductFamily();
        family.products = new java.util.HashSet<>();
        VatRate noRate = new VatRate();
        PanacheQuery<ProductFamily> familyQuery = query(family);
        PanacheQuery<VatRate> vatQuery = query(noRate);
        PanacheQuery<VatRate> noVat = query(null);
        PanacheQuery<Product> noProduct = query(null);
        PanacheQuery<Employee> user = query(new Employee());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Product> products = mockConstruction(Product.class);
                MockedConstruction<Price> prices = mockConstruction(Price.class)) {
            panache.when(() -> ProductFamily.find("code", TrainingDataService.FAMILY_CODE)).thenReturn(familyQuery);
            panache.when(() -> VatRate.find("order by number")).thenReturn(vatQuery);
            for (String[] article : TrainingDataService.ARTICLES) {
                panache.when(() -> Product.find("ean", article[0])).thenReturn(noProduct);
            }
            panache.when(() -> Employee.find("badgeId = ?1 or loginName = ?1", TrainingDataService.USER_LOGIN))
                    .thenReturn(user);
            assertEquals(3, new TrainingDataService().install());
            assertEquals(new BigDecimal("10.0000"), prices.constructed().get(2).priceExcludingTax);
            panache.when(() -> VatRate.find("order by number")).thenReturn(noVat);
            assertEquals(3, new TrainingDataService().install());
            assertEquals(new BigDecimal("2.5000"), prices.constructed().get(4).priceExcludingTax);
        }
    }
}
