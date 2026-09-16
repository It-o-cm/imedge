package com.intermarche.pos.infra;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.catalog.Product;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ImposReadinessCheck}.
 * <p>
 * The probe has no collaborator, only two Panache counts; under plain
 * {@code mvn test} entities are un-enhanced, so both finders are intercepted
 * with {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}.
 * The four combinations of the {@code activeEmployees > 0 && products > 0}
 * guard are covered, and the DOWN cases assert the NAMED cause: an operator
 * reading {@code /q/health} must see which side is missing, not a bare DOWN.
 */
class ImposReadinessCheckTest {

    /**
     * Reads the {@code missing} datum of a response.
     *
     * @param response the probe response
     * @return the missing description, or null when absent
     */
    private String missing(HealthCheckResponse response) {
        return response.getData().map(data -> (String) data.get("missing")).orElse(null);
    }

    /**
     * A bootstrapped register answers UP and publishes both counts — the data
     * is what makes the probe useful for supervision, not just its status.
     */
    @Test
    void upWhenEmployeesAndProductsExist() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.count("active", true)).thenReturn(3L);
            panache.when(Product::count).thenReturn(120L);
            HealthCheckResponse response = new ImposReadinessCheck().call();
            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
            assertEquals("impos-ready", response.getName());
            assertEquals(3L, response.getData().orElseThrow().get("activeEmployees"));
            assertEquals(120L, response.getData().orElseThrow().get("products"));
        }
    }

    /**
     * NO active employee: DOWN, and the missing side is named — nobody could
     * badge in on this register.
     */
    @Test
    void downAndNamedWhenNoActiveEmployee() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.count("active", true)).thenReturn(0L);
            panache.when(Product::count).thenReturn(120L);
            HealthCheckResponse response = new ImposReadinessCheck().call();
            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            assertEquals("no active employee", missing(response));
        }
    }

    /**
     * NO product: DOWN, named — nothing could be scanned.
     */
    @Test
    void downAndNamedWhenNoProduct() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.count("active", true)).thenReturn(3L);
            panache.when(Product::count).thenReturn(0L);
            HealthCheckResponse response = new ImposReadinessCheck().call();
            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            assertEquals("no product", missing(response));
        }
    }

    /**
     * BOTH missing: the two causes are joined rather than the first one
     * winning — a fresh, unseeded register says everything that is wrong in
     * one read.
     */
    @Test
    void downAndBothCausesJoinedOnAnEmptyRegister() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.count("active", true)).thenReturn(0L);
            panache.when(Product::count).thenReturn(0L);
            HealthCheckResponse response = new ImposReadinessCheck().call();
            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            assertEquals("no active employee; no product", missing(response));
        }
    }

    /**
     * A DOWN response still publishes the counts: the supervision payload is
     * the same shape in both statuses, so a poller never has to branch.
     */
    @Test
    void downResponseStillCarriesTheCounts() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.count("active", true)).thenReturn(0L);
            panache.when(Product::count).thenReturn(7L);
            HealthCheckResponse response = new ImposReadinessCheck().call();
            assertEquals(0L, response.getData().orElseThrow().get("activeEmployees"));
            assertEquals(7L, response.getData().orElseThrow().get("products"));
        }
    }

    /**
     * A single active employee and a single product are ENOUGH: the guard is
     * {@code > 0}, so the smallest viable referential boots the register.
     */
    @Test
    void upOnTheSmallestViableReferential() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.count("active", true)).thenReturn(1L);
            panache.when(Product::count).thenReturn(1L);
            assertEquals(HealthCheckResponse.Status.UP, new ImposReadinessCheck().call().getStatus());
        }
    }

    /**
     * Only ACTIVE employees count: the probe asks the referential with the
     * {@code active = true} filter, so a register whose staff was all
     * deactivated reports DOWN.
     */
    @Test
    void inactiveEmployeesDoNotCount() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.count("active", true)).thenReturn(0L);
            panache.when(Product::count).thenReturn(50L);
            HealthCheckResponse response = new ImposReadinessCheck().call();
            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            assertTrue(missing(response).contains("employee"));
        }
    }
}
