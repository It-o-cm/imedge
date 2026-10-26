package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CheckoutPoint}, targeting 100% branch coverage of its
 * finders and of its two derived predicates.
 *
 * <p>The static finders resolve the Panache finders, which under plain
 * {@code mvn test} fall back to {@link PanacheEntityBase}, intercepted with
 * {@link org.mockito.Mockito#mockStatic}.
 *
 * <p>Branch enumeration, leg by leg. {@code findByTerminal}: the null leg, the
 * blank leg, and the queried leg — which also proves the identifier is trimmed.
 * {@code listByIsland}: the same three legs, the empty list being returned
 * WITHOUT touching the database. {@code countByIsland}: the null, the blank and
 * the counting leg. {@code listAllOrdered} forwards. {@code isSelfService}:
 * {@code tpvType} null, then non-null on both sides of {@code isSelfService}.
 * {@code hasCustomerDisplay}: NONE, a declared type, and null. The defaults
 * assert the sane starting state of a fresh point.
 */
class CheckoutPointTest {

    /**
     * Wraps a first-result in a Panache query mock.
     *
     * @param result the row the query resolves, or null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<CheckoutPoint> query(CheckoutPoint result) {
        PanacheQuery<CheckoutPoint> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * {@code findByTerminal} answers the row for a terminal, trimming it, and
     * short-circuits a null or blank identifier without a query.
     */
    @Test
    void findByTerminalResolvesOrShortCircuits() {
        CheckoutPoint point = new CheckoutPoint();
        point.terminalId = "POS01";
        PanacheQuery<CheckoutPoint> found = query(point);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.find("terminalId", "POS01")).thenReturn(found);
            Assertions.assertSame(point, CheckoutPoint.findByTerminal("  POS01  "));
        }
        Assertions.assertNull(CheckoutPoint.findByTerminal(null));
        Assertions.assertNull(CheckoutPoint.findByTerminal("   "));
    }

    /**
     * {@code listByIsland} resolves the active points of an island, and answers
     * an empty list for a null or blank code without a query.
     */
    @Test
    void listByIslandResolvesOrShortCircuits() {
        CheckoutPoint point = new CheckoutPoint();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list("islandCode = ?1 and active = true order by terminalId", "AVANT"))
                    .thenReturn(List.of(point));
            Assertions.assertEquals(List.of(point), CheckoutPoint.listByIsland(" AVANT "));
        }
        Assertions.assertTrue(CheckoutPoint.listByIsland(null).isEmpty());
        Assertions.assertTrue(CheckoutPoint.listByIsland("   ").isEmpty());
    }

    /**
     * {@code countByIsland} counts the points of an island, and answers zero for
     * a null or blank code without a query.
     */
    @Test
    void countByIslandCountsOrShortCircuits() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.count("islandCode = ?1", "AVANT")).thenReturn(3L);
            Assertions.assertEquals(3L, CheckoutPoint.countByIsland(" AVANT "));
        }
        Assertions.assertEquals(0L, CheckoutPoint.countByIsland(null));
        Assertions.assertEquals(0L, CheckoutPoint.countByIsland("   "));
    }

    /**
     * {@code listAllOrdered} forwards the administered order verbatim.
     */
    @Test
    void listAllOrderedForwardsTheOrder() {
        CheckoutPoint point = new CheckoutPoint();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list("order by terminalId")).thenReturn(List.of(point));
            Assertions.assertEquals(List.of(point), CheckoutPoint.listAllOrdered());
        }
    }

    /**
     * A fresh checkout point is active, a manned till with no display: the sane
     * starting state before administration.
     */
    @Test
    void aFreshPointHasSaneDefaults() {
        CheckoutPoint point = new CheckoutPoint();
        Assertions.assertTrue(point.active);
        Assertions.assertEquals(TpvType.CASH_REGISTER, point.tpvType);
        Assertions.assertEquals(DisplayType.NONE, point.cashierDisplayType);
        Assertions.assertEquals(DisplayType.NONE, point.customerDisplayType);
    }

    /** The null identifier never reaches the database. */
    @Test
    void findByTerminalIsNullForANullIdentifier() {
        Assertions.assertNull(CheckoutPoint.findByTerminal(null));
    }

    /** A blank identifier never reaches the database either. */
    @Test
    void findByTerminalIsNullForABlankIdentifier() {
        Assertions.assertNull(CheckoutPoint.findByTerminal("   "));
    }

    /** A real identifier is trimmed and queried. */
    @Test
    void findByTerminalTrimsAndQueries() {
        CheckoutPoint expected = new CheckoutPoint();
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<CheckoutPoint> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            statics.when(() -> PanacheEntityBase.find("terminalId", "POS03")).thenReturn(query);
            Assertions.assertSame(expected, CheckoutPoint.findByTerminal("  POS03  "));
        }
    }

    /** A null island code answers an empty list without querying. */
    @Test
    void listByIslandIsEmptyForANullCode() {
        Assertions.assertTrue(CheckoutPoint.listByIsland(null).isEmpty());
    }

    /** A blank island code answers an empty list without querying. */
    @Test
    void listByIslandIsEmptyForABlankCode() {
        Assertions.assertTrue(CheckoutPoint.listByIsland("  ").isEmpty());
    }

    /** A real island code is trimmed and queried for its active points. */
    @Test
    void listByIslandTrimsAndQueries() {
        CheckoutPoint point = new CheckoutPoint();
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            statics.when(() -> PanacheEntityBase.list(
                    "islandCode = ?1 and active = true order by terminalId", "SERVICE"))
                    .thenReturn(List.of(point));
            List<CheckoutPoint> found = CheckoutPoint.listByIsland(" SERVICE ");
            Assertions.assertEquals(1, found.size());
            Assertions.assertSame(point, found.get(0));
        }
    }

    /** A point whose type the node never described is not self-service. */
    @Test
    void isSelfServiceIsFalseWhenNoTypeIsDescribed() {
        CheckoutPoint point = new CheckoutPoint();
        point.tpvType = null;
        Assertions.assertFalse(point.isSelfService());
    }

    /** A manned till is not self-service. */
    @Test
    void isSelfServiceIsFalseForAMannedTill() {
        CheckoutPoint point = new CheckoutPoint();
        point.tpvType = TpvType.CASH_REGISTER;
        Assertions.assertFalse(point.isSelfService());
    }

    /** Every self-service family is self-service. */
    @Test
    void isSelfServiceIsTrueForASelfServiceFamily() {
        CheckoutPoint point = new CheckoutPoint();
        point.tpvType = TpvType.SELF_CHECKOUT_DN;
        Assertions.assertTrue(point.isSelfService());
    }

    /** NONE means no customer display. */
    @Test
    void hasNoCustomerDisplayWhenTheTypeIsNone() {
        CheckoutPoint point = new CheckoutPoint();
        point.customerDisplayType = DisplayType.NONE;
        Assertions.assertFalse(point.hasCustomerDisplay());
    }

    /** A declared type means a customer display. */
    @Test
    void hasCustomerDisplayWhenATypeIsDeclared() {
        CheckoutPoint point = new CheckoutPoint();
        point.customerDisplayType = DisplayType.TOUCH;
        Assertions.assertTrue(point.hasCustomerDisplay());
    }

    /** A null type is treated as carrying a display, not as none. */
    @Test
    void hasCustomerDisplayWhenNoTypeIsDescribed() {
        CheckoutPoint point = new CheckoutPoint();
        point.customerDisplayType = null;
        Assertions.assertTrue(point.hasCustomerDisplay());
    }

    /**
     * {@code isDeactivated} (BO-03-09-01): true only for an existing point out
     * of service — no point, and a point in service, answer false.
     */
    @Test
    void isDeactivatedNeedsAnExistingInactivePoint() {
        CheckoutPoint off = new CheckoutPoint();
        off.active = false;
        CheckoutPoint on = new CheckoutPoint();
        PanacheQuery<CheckoutPoint> offQuery = query(off);
        PanacheQuery<CheckoutPoint> onQuery = query(on);
        PanacheQuery<CheckoutPoint> noneQuery = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.find("terminalId", "C01")).thenReturn(offQuery);
            ms.when(() -> CheckoutPoint.find("terminalId", "C02")).thenReturn(onQuery);
            ms.when(() -> CheckoutPoint.find("terminalId", "C03")).thenReturn(noneQuery);
            Assertions.assertTrue(CheckoutPoint.isDeactivated("C01"));
            Assertions.assertFalse(CheckoutPoint.isDeactivated("C02"));
            Assertions.assertFalse(CheckoutPoint.isDeactivated("C03"));
        }
    }

    /** {@code deactivatedTerminals} lists the identifiers of the points out of service (BO-03-09-01). */
    @Test
    void deactivatedTerminalsListsTheIdentifiers() {
        CheckoutPoint off = new CheckoutPoint();
        off.terminalId = "C09";
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list("active = false order by terminalId")).thenReturn(List.of(off));
            Assertions.assertEquals(List.of("C09"), CheckoutPoint.deactivatedTerminals());
        }
    }
}
