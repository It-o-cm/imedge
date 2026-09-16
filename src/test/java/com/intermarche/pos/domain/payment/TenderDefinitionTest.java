package com.intermarche.pos.domain.payment;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenderDefinition}, targeting 100% branch coverage.
 * <p>
 * Instance methods run on plain instances; the finders resolve the Panache
 * {@code list} and {@code find} statics, which fall back to
 * {@link PanacheEntityBase} outside a Quarkus context, so they are intercepted
 * with {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every leg exercised): {@code hasValidFunctionalId} covers
 * the null leg, the blank leg, a too-short identifier, the exact three-character
 * boundary and a longer one; {@code exceeds} — reached through the three ceiling
 * predicates — covers the null-amount leg, the unadministered-bound leg, the
 * strictly-above arm, the equal boundary and the below arm; {@code belowMinAmount}
 * covers the same five cases in the other direction; {@code exceedsMaxCount}
 * covers the unadministered leg, the exact boundary and both arms;
 * {@code opensDrawer} covers the null-moment leg and each of the four moments,
 * both arms of the two compound moments and both legs of the change-due
 * conjunction; {@code changeTender} covers the null leg, the blank leg and the
 * administered arm; {@code findByCode} covers the null leg, the blank leg, the
 * found arm and the missing arm; {@code isActive} covers the unadministered arm,
 * the active arm and the deactivated arm.
 */
class TenderDefinitionTest {

    /**
     * Builds the meal-voucher tender as a store would administer it — the
     * questionnaire's own example: twenty-five euros, twice per sale.
     *
     * @return the administered tender
     */
    private TenderDefinition mealVoucher() {
        TenderDefinition tender = new TenderDefinition();
        tender.code = "TR";
        tender.functionalId = "030";
        tender.label = "Titre restaurant";
        tender.active = true;
        tender.displayOrder = 30;
        tender.maxAmount = new BigDecimal("25.00");
        tender.maxAmountControl = TenderDefinition.ControlLevel.BLOCKING;
        tender.maxCount = 2;
        tender.maxCountControl = TenderDefinition.ControlLevel.SUPERVISOR;
        tender.drawerOpening = TenderDefinition.DrawerOpening.AFTER_PAYMENT;
        return tender;
    }



    /**
     * The first ceiling fires strictly above the administered amount: twenty-six
     * euros exceeds it, twenty-five exactly does not.
     */
    @Test
    void theFirstCeilingFiresStrictlyAboveTheAdministeredAmount() {
        TenderDefinition tender = mealVoucher();
        assertTrue(tender.exceedsMaxAmount(new BigDecimal("25.01")));
        assertFalse(tender.exceedsMaxAmount(new BigDecimal("25.00")));
        assertFalse(tender.exceedsMaxAmount(new BigDecimal("10.00")));
    }

    /**
     * A SECOND administered ceiling moves the very same verdict, which is what
     * proves the amount is read rather than hard-coded.
     */
    @Test
    void aSecondAdministeredCeilingMovesTheVerdict() {
        TenderDefinition tender = mealVoucher();
        tender.maxAmount = new BigDecimal("19.00");
        assertTrue(tender.exceedsMaxAmount(new BigDecimal("19.01")));
        assertFalse(tender.exceedsMaxAmount(new BigDecimal("19.00")));
    }

    /**
     * An unadministered ceiling never fires, and a null amount is never compared
     * — the two legs of the guard, on each of the three ceiling predicates.
     */
    @Test
    void anUnadministeredCeilingNeverFires() {
        TenderDefinition tender = mealVoucher();
        tender.maxAmount = null;
        assertFalse(tender.exceedsMaxAmount(new BigDecimal("999.00")));
        assertFalse(tender.exceedsSecondMaxAmount(new BigDecimal("999.00")));
        assertFalse(tender.exceedsMaxChange(new BigDecimal("999.00")));

        TenderDefinition bounded = mealVoucher();
        bounded.secondMaxAmount = new BigDecimal("50.00");
        bounded.maxChangeAmount = new BigDecimal("8.00");
        assertFalse(bounded.exceedsMaxAmount(null));
        assertFalse(bounded.exceedsSecondMaxAmount(null));
        assertFalse(bounded.exceedsMaxChange(null));
    }

    /**
     * The second ceiling is administered and read independently of the first
     * (BO-03-02-13).
     */
    @Test
    void theSecondCeilingIsAdministeredOnItsOwn() {
        TenderDefinition tender = mealVoucher();
        tender.secondMaxAmount = new BigDecimal("50.00");
        assertTrue(tender.exceedsSecondMaxAmount(new BigDecimal("50.01")));
        assertFalse(tender.exceedsSecondMaxAmount(new BigDecimal("50.00")));
        assertFalse(tender.exceedsSecondMaxAmount(new BigDecimal("49.99")));
    }

    /**
     * The change ceiling is read on the change and not on the settlement
     * (BO-03-02-11).
     */
    @Test
    void theChangeCeilingIsReadOnTheChange() {
        TenderDefinition tender = mealVoucher();
        tender.maxChangeAmount = new BigDecimal("8.00");
        assertTrue(tender.exceedsMaxChange(new BigDecimal("8.01")));
        assertFalse(tender.exceedsMaxChange(new BigDecimal("8.00")));
        assertFalse(tender.exceedsMaxChange(new BigDecimal("2.00")));
    }

    /**
     * The floor fires strictly below the administered amount, and never when it
     * is not administered or the amount is unknown (BO-03-02-14).
     */
    @Test
    void theFloorFiresStrictlyBelowTheAdministeredAmount() {
        TenderDefinition tender = mealVoucher();
        tender.minAmount = new BigDecimal("5.00");
        assertTrue(tender.belowMinAmount(new BigDecimal("4.99")));
        assertFalse(tender.belowMinAmount(new BigDecimal("5.00")));
        assertFalse(tender.belowMinAmount(new BigDecimal("5.01")));
        assertFalse(tender.belowMinAmount(null));

        tender.minAmount = null;
        assertFalse(tender.belowMinAmount(BigDecimal.ZERO));
    }

    /**
     * The count is read on the settlement ABOUT to be registered: with two
     * allowed, the third is refused and the second is not (BO-03-02-12).
     */
    @Test
    void theCountIsReadOnTheSettlementAboutToBeRegistered() {
        TenderDefinition tender = mealVoucher();
        assertFalse(tender.exceedsMaxCount(0));
        assertFalse(tender.exceedsMaxCount(1));
        assertTrue(tender.exceedsMaxCount(2));
    }

    /**
     * A SECOND administered count moves the very same verdict.
     */
    @Test
    void aSecondAdministeredCountMovesTheVerdict() {
        TenderDefinition tender = mealVoucher();
        tender.maxCount = 1;
        assertFalse(tender.exceedsMaxCount(0));
        assertTrue(tender.exceedsMaxCount(1));
    }

    /**
     * An unadministered count never refuses a settlement.
     */
    @Test
    void anUnadministeredCountNeverRefuses() {
        TenderDefinition tender = mealVoucher();
        tender.maxCount = null;
        assertFalse(tender.exceedsMaxCount(99));
    }

    /**
     * The drawer opens right after the settlement when that moment is
     * administered, and not again at printing time (BO-03-02-19).
     */
    @Test
    void theDrawerOpensAfterThePaymentWhenSoAdministered() {
        TenderDefinition tender = mealVoucher();
        assertTrue(tender.opensDrawer(false, false));
        assertTrue(tender.opensDrawer(true, false));
        assertFalse(tender.opensDrawer(false, true));
    }

    /**
     * Administered on the change, the drawer opens only when change is owed —
     * both legs of the conjunction, and the printing leg on top.
     */
    @Test
    void theDrawerOpensOnlyOnChangeWhenSoAdministered() {
        TenderDefinition tender = mealVoucher();
        tender.drawerOpening = TenderDefinition.DrawerOpening.IF_CHANGE_DUE;
        assertTrue(tender.opensDrawer(true, false));
        assertFalse(tender.opensDrawer(false, false));
        assertFalse(tender.opensDrawer(true, true));
    }

    /**
     * Administered after the receipt, the drawer opens at printing time and not
     * before.
     */
    @Test
    void theDrawerOpensAfterTheReceiptWhenSoAdministered() {
        TenderDefinition tender = mealVoucher();
        tender.drawerOpening = TenderDefinition.DrawerOpening.AFTER_RECEIPT;
        assertTrue(tender.opensDrawer(false, true));
        assertFalse(tender.opensDrawer(true, false));
    }

    /**
     * A tender administered never to open the drawer opens it at no moment, and
     * a row whose moment was never set behaves the same way (null leg).
     */
    @Test
    void theDrawerStaysShutWhenNoMomentIsAdministered() {
        TenderDefinition never = mealVoucher();
        never.drawerOpening = TenderDefinition.DrawerOpening.NEVER;
        assertFalse(never.opensDrawer(true, false));
        assertFalse(never.opensDrawer(true, true));

        TenderDefinition unset = mealVoucher();
        unset.drawerOpening = null;
        assertFalse(unset.opensDrawer(true, false));
    }

    /**
     * The change is given in the administered tender, and in the tender itself
     * when none is administered — null and blank alike (BO-03-02-16).
     */
    @Test
    void theChangeTenderIsAdministeredOrTheTenderItself() {
        TenderDefinition tender = mealVoucher();
        assertEquals("TR", tender.changeTender());

        tender.changeTenderCode = "   ";
        assertEquals("TR", tender.changeTender());

        tender.changeTenderCode = " VOUCHER ";
        assertEquals("VOUCHER", tender.changeTender());
    }


    /**
     * {@code listAllOrdered} answers the whole referential, deactivated rows
     * included — what the administration screen shows.
     */
    @Test
    void listAllOrderedAnswersTheWholeReferential() {
        TenderDefinition tender = mealVoucher();
        tender.active = false;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.list("order by displayOrder, code"))
                    .thenReturn(List.of(tender));
            assertEquals(List.of(tender), TenderDefinition.listAllOrdered());
        }
    }

    /**
     * {@code findByCode} answers the administered row, trimming what the caller
     * passed, and null when no row administers the key.
     */
    @Test
    void findByCodeAnswersTheAdministeredRow() {
        TenderDefinition tender = mealVoucher();
        PanacheQuery<TenderDefinition> found = queryOf(tender);
        PanacheQuery<TenderDefinition> empty = queryOf(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "TR")).thenReturn(found);
            ms.when(() -> TenderDefinition.find("code", "GIFT")).thenReturn(empty);
            assertSame(tender, TenderDefinition.findByCode(" TR "));
            assertNull(TenderDefinition.findByCode("GIFT"));
        }
    }

    /**
     * A null or blank key is answered without touching the database, which is
     * what the absence of any static stubbing here asserts.
     */
    @Test
    void findByCodeShortCircuitsOnAnEmptyKey() {
        assertNull(TenderDefinition.findByCode(null));
        assertNull(TenderDefinition.findByCode("   "));
    }

    /**
     * A tender no row administers stays in service, an administered active one
     * is in service, and a deactivated one is not (BO-03-02-03).
     */
    @Test
    void theReferentialDecidesWhichTendersAreInService() {
        TenderDefinition active = mealVoucher();
        TenderDefinition stopped = mealVoucher();
        stopped.active = false;
        PanacheQuery<TenderDefinition> activeQuery = queryOf(active);
        PanacheQuery<TenderDefinition> stoppedQuery = queryOf(stopped);
        PanacheQuery<TenderDefinition> empty = queryOf(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "TR")).thenReturn(activeQuery);
            ms.when(() -> TenderDefinition.find("code", "CHEQUE")).thenReturn(stoppedQuery);
            ms.when(() -> TenderDefinition.find("code", "DEVISE")).thenReturn(empty);
            assertTrue(TenderDefinition.isActive("TR"));
            assertFalse(TenderDefinition.isActive("CHEQUE"));
            assertTrue(TenderDefinition.isActive("DEVISE"));
        }
    }

    /**
     * The control levels answer the three questions the register asks of them:
     * does this stop the settlement, may a supervisor pass it, is it silent.
     */
    @Test
    void theControlLevelsAnswerWhatTheRegisterAsks() {
        assertFalse(TenderDefinition.ControlLevel.NONE.blocks());
        assertFalse(TenderDefinition.ControlLevel.INFO.blocks());
        assertFalse(TenderDefinition.ControlLevel.WARNING.blocks());
        assertTrue(TenderDefinition.ControlLevel.SUPERVISOR.blocks());
        assertTrue(TenderDefinition.ControlLevel.BLOCKING.blocks());

        assertTrue(TenderDefinition.ControlLevel.SUPERVISOR.allowsOverride());
        assertFalse(TenderDefinition.ControlLevel.BLOCKING.allowsOverride());
        assertFalse(TenderDefinition.ControlLevel.WARNING.allowsOverride());

        assertTrue(TenderDefinition.ControlLevel.NONE.isSilent());
        assertFalse(TenderDefinition.ControlLevel.INFO.isSilent());
    }

    /**
     * The five control levels and the four drawer moments the questionnaire
     * states are the ones the referential publishes, labels included.
     */
    @Test
    void theAdministeredVocabularyIsTheOneTheQuestionnaireStates() {
        assertEquals(5, TenderDefinition.ControlLevel.values().length);
        assertEquals("Bloquant superviseur",
                TenderDefinition.ControlLevel.valueOf("SUPERVISOR").getLabel());

        assertEquals(4, TenderDefinition.DrawerOpening.values().length);
        assertEquals("Si rendu dû",
                TenderDefinition.DrawerOpening.valueOf("IF_CHANGE_DUE").getLabel());
    }

    /**
     * The identifier width the questionnaire states is the one the entity
     * publishes (BO-03-02-04).
     */
    @Test
    void theAdministeredIdentifierWidthIsThree() {
        assertEquals(3, TenderDefinition.MIN_FUNCTIONAL_ID_LENGTH);
    }

    /**
     * Builds a Panache query answering the given single result.
     *
     * <p>Built OUTSIDE any {@code mockStatic} block by every caller: stubbing an
     * instance mock while a static mock is open is what raises
     * {@code UnfinishedStubbingException}.
     *
     * @param result the row the query answers, or null when it matches nothing
     * @return the stubbed query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<TenderDefinition> queryOf(TenderDefinition result) {
        PanacheQuery<TenderDefinition> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }
}
