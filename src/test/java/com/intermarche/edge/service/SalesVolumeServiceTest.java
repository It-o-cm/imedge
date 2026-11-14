package com.intermarche.edge.service;

import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.setting.TouchGroupSetting;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SalesVolumeService} (BO-03-01-13).
 *
 * <p>The sale lines come from a mocked entity manager, the article and group
 * writes go through the Panache statics intercepted on
 * {@link PanacheEntityBase}, and a created touch configuration's
 * {@code persist()} is neutralised by construction mocking.
 */
class SalesVolumeServiceTest {

    /**
     * Builds a group.
     *
     * @param id its id
     * @param code its code
     * @return the group
     */
    private ProductFamily group(Long id, String code) {
        ProductFamily family = new ProductFamily();
        family.id = id;
        family.code = code;
        return family;
    }

    /**
     * Builds an article.
     *
     * @param id its id
     * @param ean its EAN
     * @return the article
     */
    private Product article(Long id, String ean) {
        Product product = new Product();
        product.id = id;
        product.ean = ean;
        return product;
    }

    /**
     * Builds a touch-configuration query answering one row.
     *
     * @param row the row, or null
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<TouchGroupSetting> touchQuery(TouchGroupSetting row) {
        PanacheQuery<TouchGroupSetting> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * The quantity sold of each article over the window is written on the
     * article, every other article back to zero; a group's volume is the sum
     * over its articles and its sub-groups', each counted once, written on its
     * configuration, created only when there is a volume to carry.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theVolumesAreCountedOnTheRegistersSales() {
        SalesVolumeService service = new SalesVolumeService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.touchSalesVolumeDays()).thenReturn(30);
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = mock(TypedQuery.class, org.mockito.Mockito.RETURNS_SELF);
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {"E1", new BigDecimal("12.4")});
        rows.add(new Object[] {"E2", null});
        rows.add(new Object[] {"E3", new BigDecimal("2.5")});
        when(query.getResultList()).thenReturn(rows);
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        ProductFamily fruits = group(1L, "FRUITS");
        ProductFamily apples = group(2L, "POMMES");
        ProductFamily vegetables = group(3L, "LEGUMES");
        ProductFamily empty = group(4L, "VIDE");
        Product apple = article(10L, "E1");
        Product leek = article(11L, "E3");
        fruits.products.add(apple);
        fruits.productFamilies.add(apples);
        apples.products.add(apple);
        apples.productFamilies.add(fruits);
        vegetables.products.add(leek);
        TouchGroupSetting fruitsTouch = TouchGroupSetting.defaults("FRUITS");
        PanacheQuery<TouchGroupSetting> withRow = touchQuery(fruitsTouch);
        PanacheQuery<TouchGroupSetting> withoutRow = touchQuery(null);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class);
             MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class);
             MockedConstruction<TouchGroupSetting> created = mockConstruction(TouchGroupSetting.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            entities.when(() -> ProductFamily.listAll()).thenReturn(List.of(fruits, apples, vegetables, empty));
            entities.when(() -> TouchGroupSetting.find(eq("familyCode"), any(Object[].class))).thenReturn(withoutRow);
            entities.when(() -> TouchGroupSetting.find("familyCode", "FRUITS")).thenReturn(withRow);
            assertEquals(3, service.refresh(LocalDate.of(2026, 10, 25)));
            verify(query).setParameter(1, Ticket.TicketStatus.CLOSED);
            verify(query).setParameter(2, LocalDate.of(2026, 9, 26).atStartOfDay());
            entities.verify(() -> Product.update("salesVolume = 0 where salesVolume <> 0"));
            entities.verify(() -> Product.update("salesVolume = ?1 where ean = ?2", 12L, "E1"));
            entities.verify(() -> Product.update("salesVolume = ?1 where ean = ?2", 0L, "E2"));
            entities.verify(() -> Product.update("salesVolume = ?1 where ean = ?2", 3L, "E3"));
            assertEquals(2, created.constructed().size());
            TouchGroupSetting applesTouch = created.constructed().get(0);
            assertEquals(12L, applesTouch.salesVolume);
            verify(applesTouch).persist();
            assertEquals(3L, created.constructed().get(1).salesVolume);
        }
        assertEquals(12L, fruitsTouch.salesVolume);
    }
}
