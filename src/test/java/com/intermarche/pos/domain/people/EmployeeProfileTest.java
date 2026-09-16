package com.intermarche.pos.domain.people;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Sort;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link EmployeeProfile}.
 * <p>
 * The pure scope test ({@code appliesTo}) is exercised directly; the static
 * finders resolve to {@link PanacheEntityBase} under plain {@code mvn test}
 * and are intercepted with {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every arm exercised): {@code appliesTo} covers the
 * null-scope arm, the GLOBAL arm, the code-match arm and the code-mismatch
 * arm; {@code exists} covers the positive-count and zero-count arms.
 */
class EmployeeProfileTest {

    /**
     * Builds a binding at a given scope.
     *
     * @param scope the echelon scope
     * @return the binding
     */
    private EmployeeProfile binding(String scope) {
        EmployeeProfile binding = new EmployeeProfile();
        binding.employeeId = 7L;
        binding.profileId = 3L;
        binding.echelonScope = scope;
        return binding;
    }

    /**
     * A null scope applies to every node (null-scope arm).
     */
    @Test
    void appliesToTrueOnNullScope() {
        assertTrue(binding(null).appliesTo("0101"));
    }

    /**
     * The GLOBAL scope applies to every node (GLOBAL arm).
     */
    @Test
    void appliesToTrueOnGlobalScope() {
        assertTrue(binding(EmployeeProfile.GLOBAL).appliesTo("0101"));
    }

    /**
     * A confined scope applies on the matching node (code-match arm).
     */
    @Test
    void appliesToTrueOnMatchingCode() {
        assertTrue(binding("0101").appliesTo("0101"));
    }

    /**
     * A confined scope is inert on another node (code-mismatch arm).
     */
    @Test
    void appliesToFalseOnMismatchingCode() {
        assertFalse(binding("0101").appliesTo("0202"));
    }

    /**
     * {@code forEmployee} delegates to the id-ordered listing.
     */
    @Test
    void forEmployeeDelegatesToOrderedListing() {
        List<EmployeeProfile> expected = List.of(binding("*"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EmployeeProfile.list(eq("employeeId"), any(Sort.class), eq(7L)))
                    .thenReturn(expected);
            assertSame(expected, EmployeeProfile.forEmployee(7L));
        }
    }

    /**
     * {@code exists} is true when a matching binding is counted (positive arm).
     */
    @Test
    void existsTrueWhenCounted() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EmployeeProfile.count(
                    "employeeId = ?1 and profileId = ?2 and echelonScope = ?3", 7L, 3L, "*"))
                    .thenReturn(1L);
            assertTrue(EmployeeProfile.exists(7L, 3L, "*"));
        }
    }

    /**
     * {@code exists} is false when nothing is counted (zero arm).
     */
    @Test
    void existsFalseWhenNoneCounted() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EmployeeProfile.count(
                    "employeeId = ?1 and profileId = ?2 and echelonScope = ?3", 7L, 3L, "0101"))
                    .thenReturn(0L);
            assertFalse(EmployeeProfile.exists(7L, 3L, "0101"));
        }
    }

    /**
     * {@code getChecksum} is deterministic for equal business content.
     */
    @Test
    void checksumIsDeterministic() {
        assertEquals(binding("*").getChecksum(), binding("*").getChecksum());
    }
}
