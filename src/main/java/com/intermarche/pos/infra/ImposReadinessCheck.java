package com.intermarche.pos.infra;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.catalog.Product;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * SmallRye {@code @Readiness} probe answering the standard Quarkus health
 * endpoint ({@code /q/health}, {@code /q/health/ready}). It reports whether the
 * register is BOOTSTRAPPED: at least one active {@link Employee} and at least
 * one {@link Product} in the local referential — the minimum a cashier needs to
 * badge in and scan. Two light Panache counts, no collaborators; there is
 * deliberately no unit test (nothing meaningful to mock), the "public probe"
 * contract is pinned by an end-to-end test instead.
 * <p>
 * <b>Placement.</b> This lives in {@code infra} on purpose: it is neither a
 * business service nor UI, but deployment/supervision plumbing (the store node
 * and any future monitoring poll it), so it belongs with the infrastructure
 * beans rather than under {@code service} or {@code ui}.
 * <p>
 * <b>Scope.</b> The datasource is already covered by the extension's automatic
 * database check — not duplicated here. There is no custom {@code @Liveness}:
 * this is a readiness signal only. Training mode does NOT neutralise the probe —
 * a register in training is a live register — and no code is needed for that:
 * training touches no employee/product count, so it stays UP by default.
 */
@Readiness
@ApplicationScoped
public class ImposReadinessCheck implements HealthCheck {

    /** The health check name surfaced in the {@code /q/health} payload. */
    private static final String NAME = "impos-ready";

    /**
     * Runs the two referential counts and reports UP only when the register is
     * bootstrapped, naming the missing side (no active employee and/or no
     * product) on the DOWN response otherwise.
     *
     * @return the readiness response, UP when at least one active employee and
     *         at least one product exist, DOWN with the missing item named
     *         otherwise
     */
    @Override
    public HealthCheckResponse call() {
        long activeEmployees = Employee.count("active", true);
        long products = Product.count();
        HealthCheckResponseBuilder response =
                HealthCheckResponse.named(NAME)
                        .withData("activeEmployees", activeEmployees)
                        .withData("products", products);
        if (activeEmployees > 0 && products > 0) {
            return response.up().build();
        }
        StringBuilder missing = new StringBuilder();
        if (activeEmployees == 0) {
            missing.append("no active employee");
        }
        if (products == 0) {
            if (missing.length() > 0) {
                missing.append("; ");
            }
            missing.append("no product");
        }
        return response.withData("missing", missing.toString()).down().build();
    }
}
