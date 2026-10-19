package com.intermarche.pos.domain.people;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the SECRET EXPIRY of {@link Employee} (BO-01-02-12).
 *
 * <p>Four legs: no policy at all, a secret never changed, a secret changed
 * within the delay, and a secret changed before it. No database is booted —
 * the rule is arithmetic on one column.
 */
class EmployeeSecretExpiryTest {

    /**
     * Builds an account whose secret was changed at the given instant.
     *
     * @param changedAt when the secret was last changed, or null
     * @return the account
     */
    private Employee accountChangedAt(LocalDateTime changedAt) {
        Employee employee = new Employee();
        employee.passwordChangedAt = changedAt;
        return employee;
    }

    /**
     * A delay of zero or less is the ABSENCE of a policy and never an instant
     * expiry: a shop administering nothing must not find every account
     * demanding a new secret.
     */
    @Test
    void noPolicyNeverExpires() {
        assertFalse(accountChangedAt(null).secretExpired(0));
        assertFalse(accountChangedAt(null).secretExpired(-30));
        assertFalse(accountChangedAt(LocalDateTime.now().minusYears(5)).secretExpired(0));
    }

    /**
     * A secret NEVER changed is expired as soon as a policy exists: an account
     * still carrying the code it was created with is the one the policy is
     * written for.
     */
    @Test
    void aSecretNeverChangedIsExpiredUnderAnyPolicy() {
        assertTrue(accountChangedAt(null).secretExpired(1));
        assertTrue(accountChangedAt(null).secretExpired(3650));
    }

    /**
     * A secret changed within the delay holds; one changed before it does not.
     */
    @Test
    void theDelayCountsFromTheLastChange() {
        assertFalse(accountChangedAt(LocalDateTime.now().minusDays(10)).secretExpired(90));
        assertTrue(accountChangedAt(LocalDateTime.now().minusDays(91)).secretExpired(90));
    }
}
