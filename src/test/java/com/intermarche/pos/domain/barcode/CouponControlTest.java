package com.intermarche.pos.domain.barcode;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CouponControl}, targeting 100% branch coverage.
 * <p>
 * A plain entity: every test runs on instances built by hand, with no database
 * and no Quarkus boot.
 * <p>
 * Branch enumeration (every leg exercised): {@code effectiveMessage} covers the
 * administered wording, the null wording, the blank wording and the control
 * carrying no kind at all.
 */
class CouponControlTest {

    /**
     * Builds a control of the given kind carrying the given wording.
     *
     * @param kind the control kind, possibly null
     * @param message the administered wording, possibly null or blank
     * @return the configured control
     */
    private CouponControl control(CouponControl.Kind kind, String message) {
        CouponControl control = new CouponControl();
        control.kind = kind;
        control.message = message;
        return control;
    }

    /**
     * A fresh control is silent, which is the only default that cannot stop a lane.
     */
    @Test
    void fieldDefaults() {
        Assertions.assertEquals(AlertLevel.NONE, new CouponControl().level);
        Assertions.assertNull(new CouponControl().message);
    }

    /**
     * effectiveMessage hands back the administered wording when there is one.
     */
    @Test
    void effectiveMessageHandsBackTheAdministeredWording() {
        Assertions.assertEquals("BON PERIME, APPELER LE RESPONSABLE",
                control(CouponControl.Kind.EXPIRED, "BON PERIME, APPELER LE RESPONSABLE")
                        .effectiveMessage());
    }

    /**
     * effectiveMessage falls back on the control's own wording when none is
     * administered (first leg of the guard).
     */
    @Test
    void effectiveMessageFallsBackOnTheDefaultWhenNull() {
        Assertions.assertEquals("BON EXPIRE",
                control(CouponControl.Kind.EXPIRED, null).effectiveMessage());
    }

    /**
     * effectiveMessage falls back on the control's own wording on a blank one
     * (second leg of the guard).
     */
    @Test
    void effectiveMessageFallsBackOnTheDefaultWhenBlank() {
        Assertions.assertEquals("BON DEJA UTILISE",
                control(CouponControl.Kind.DUPLICATE, "   ").effectiveMessage());
    }

    /**
     * effectiveMessage is empty on a control carrying no kind at all.
     */
    @Test
    void effectiveMessageOfAKindlessControlIsEmpty() {
        Assertions.assertEquals("", control(null, null).effectiveMessage());
    }

    /**
     * Every control of the catalog carries its own default wording.
     */
    @Test
    void everyKindCarriesADefaultWording() {
        Assertions.assertEquals(9, CouponControl.Kind.values().length);
        Assertions.assertEquals("BON PAS ENCORE VALABLE",
                CouponControl.Kind.NOT_YET_VALID.getDefaultMessage());
        Assertions.assertEquals("BON EXPIRE", CouponControl.Kind.EXPIRED.getDefaultMessage());
        Assertions.assertEquals("BON D'UN AUTRE MAGASIN",
                CouponControl.Kind.OTHER_STORE.getDefaultMessage());
        Assertions.assertEquals("BON REFUSE SUR CET ILOT",
                CouponControl.Kind.OTHER_ISLAND.getDefaultMessage());
        Assertions.assertEquals("CARTE FIDELITE REQUISE",
                CouponControl.Kind.FIDELITY_REQUIRED.getDefaultMessage());
        Assertions.assertEquals("CARTE FIDELITE NON CONCORDANTE",
                CouponControl.Kind.FIDELITY_MISMATCH.getDefaultMessage());
        Assertions.assertEquals("CLE MAGASIN INCORRECTE",
                CouponControl.Kind.STORE_CHECK.getDefaultMessage());
        Assertions.assertEquals("TOTAL TICKET INSUFFISANT",
                CouponControl.Kind.MIN_TICKET_TOTAL.getDefaultMessage());
        Assertions.assertEquals("BON DEJA UTILISE",
                CouponControl.Kind.DUPLICATE.getDefaultMessage());
    }
}
