package com.intermarche.pos.domain.sale;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the document kinds and the administered list the register reads them
 * from ({@code LC-08-04-04}).
 *
 * <p>THE LIST IS THE STORE'S, AND THE STORE CAN GET IT WRONG. It is typed into a
 * back-office field, so every way it can arrive damaged is exercised here: null, blank,
 * padded, in the wrong case, naming a kind twice, naming one this version does not
 * know, and naming nothing this version knows at all. The one thing none of these may
 * produce is an empty list — a register that offers no kind of document cannot bill
 * anybody, which is a worse outcome than any typing mistake it was trying to honour.
 */
class DocumentTypeTest {

    // --------------------------------------------------
    // The kinds themselves
    // --------------------------------------------------

    /**
     * Each kind carries its own sequence letter and its own printed title — the two
     * things {@code BO-03-03-02} says separate one kind of document from another.
     */
    @Test
    void eachKindCarriesItsPrefixAndItsTitle() {
        assertEquals("F", DocumentType.FACTURE.getPrefix());
        assertEquals("FACTURE", DocumentType.FACTURE.getTitle());
        assertEquals("L", DocumentType.BON_LIVRAISON.getPrefix());
        assertEquals("BON DE LIVRAISON", DocumentType.BON_LIVRAISON.getTitle());
    }

    // --------------------------------------------------
    // Reading one name
    // --------------------------------------------------

    /**
     * A name that is exactly an enum name resolves.
     */
    @Test
    void anExactNameResolves() {
        assertSame(DocumentType.BON_LIVRAISON, DocumentType.byName("BON_LIVRAISON"));
    }

    /**
     * Case and padding are tolerated: the name travels through a URL and a
     * back-office text field, and neither guarantees either.
     */
    @Test
    void caseAndPaddingAreTolerated() {
        assertSame(DocumentType.FACTURE, DocumentType.byName("  facture "));
    }

    /**
     * A missing name resolves to nothing — the null leg of the guard.
     */
    @Test
    void aMissingNameResolvesToNothing() {
        assertNull(DocumentType.byName(null));
    }

    /**
     * A name no kind carries resolves to nothing — the loop-fell-through leg.
     */
    @Test
    void anUnknownNameResolvesToNothing() {
        assertNull(DocumentType.byName("AVOIR"));
    }

    // --------------------------------------------------
    // Reading the administered list
    // --------------------------------------------------

    /**
     * A parameter that was never set falls back to the invoice alone — the null leg.
     */
    @Test
    void aMissingListFallsBackToTheInvoice() {
        assertEquals(List.of(DocumentType.FACTURE), DocumentType.activated(null));
    }

    /**
     * A parameter emptied by a store falls back the same way — the blank leg.
     */
    @Test
    void aBlankListFallsBackToTheInvoice() {
        assertEquals(List.of(DocumentType.FACTURE), DocumentType.activated("   "));
    }

    /**
     * A list naming several kinds keeps them ALL and in the administered order: that
     * order is what the store sees on the touch screen.
     */
    @Test
    void aListKeepsEveryKindInAdministeredOrder() {
        assertEquals(List.of(DocumentType.BON_LIVRAISON, DocumentType.FACTURE),
                DocumentType.activated("BON_LIVRAISON;FACTURE"));
    }

    /**
     * Padding and case survive inside the list too.
     */
    @Test
    void aListToleratesPaddingAndCase() {
        assertEquals(List.of(DocumentType.FACTURE, DocumentType.BON_LIVRAISON),
                DocumentType.activated(" facture ; Bon_Livraison "));
    }

    /**
     * A kind named twice is offered once: two identical buttons would be a defect of
     * the parameter showing through to the cashier.
     */
    @Test
    void aKindNamedTwiceIsOfferedOnce() {
        assertEquals(List.of(DocumentType.FACTURE),
                DocumentType.activated("FACTURE;FACTURE"));
    }

    /**
     * A kind this version does not know is DROPPED, and the ones it knows survive: a
     * back office ahead of this register must not cost the store its whole list.
     */
    @Test
    void anUnknownKindIsDroppedAndTheOthersSurvive() {
        assertEquals(List.of(DocumentType.FACTURE),
                DocumentType.activated("FACTURE;NOTA_DE_CREDITO"));
    }

    /**
     * A list where NOTHING is known falls back to the invoice rather than to nothing —
     * the last leg, and the one that decides whether a mistyped parameter stops the
     * register billing at all.
     */
    @Test
    void aListOfUnknownKindsFallsBackToTheInvoice() {
        assertEquals(List.of(DocumentType.FACTURE),
                DocumentType.activated("FATURA;RECIBO"));
    }

    /**
     * Separators alone name nothing and fall back the same way — the empty-token leg
     * of the loop.
     */
    @Test
    void separatorsAloneFallBackToTheInvoice() {
        assertEquals(List.of(DocumentType.FACTURE), DocumentType.activated(";;;"));
    }

    // --------------------------------------------------
    // The automatic emission by payment method (LC-08-04-16)
    // --------------------------------------------------

    /**
     * A parameter that was never set emits nothing — the null leg. Silence is the
     * default: a store that asked for no automatic document must get none.
     */
    @Test
    void aMissingAutomaticMappingEmitsNothing() {
        assertTrue(DocumentType.automatic(null).isEmpty());
    }

    /**
     * A parameter emptied by a store emits nothing either — the blank leg.
     */
    @Test
    void aBlankAutomaticMappingEmitsNothing() {
        assertTrue(DocumentType.automatic(" ").isEmpty());
    }

    /**
     * A well-formed parameter names the kind of each settlement, the method key
     * upper-cased so it matches the discriminator the payment answers.
     */
    @Test
    void aWellFormedAutomaticMappingIsRead() {
        Map<String, DocumentType> automatic =
                DocumentType.automatic(" credit :FACTURE;CHEQUE:BON_LIVRAISON");
        assertEquals(2, automatic.size());
        assertSame(DocumentType.FACTURE, automatic.get("CREDIT"));
        assertSame(DocumentType.BON_LIVRAISON, automatic.get("CHEQUE"));
    }

    /**
     * An entry that is not a pair is dropped and the others survive.
     */
    @Test
    void anAutomaticEntryWithoutAColonIsDropped() {
        Map<String, DocumentType> automatic = DocumentType.automatic("CREDIT;CHEQUE:FACTURE");
        assertEquals(1, automatic.size());
        assertSame(DocumentType.FACTURE, automatic.get("CHEQUE"));
    }

    /**
     * An entry naming a kind this version does not know is dropped — the unknown-kind
     * leg.
     */
    @Test
    void anAutomaticEntryNamingAnUnknownKindIsDropped() {
        assertTrue(DocumentType.automatic("CREDIT:FATURA").isEmpty());
    }

    /**
     * An entry naming no method at all is dropped — the empty-key leg, which a stray
     * colon produces.
     */
    @Test
    void anAutomaticEntryWithoutAMethodIsDropped() {
        assertTrue(DocumentType.automatic(" :FACTURE").isEmpty());
    }

    /**
     * A method key this register does not know is KEPT: the parameter may name a
     * method another lane has, and no payment of it will ever match here anyway.
     */
    @Test
    void anUnknownMethodKeyIsKept() {
        assertSame(DocumentType.FACTURE,
                DocumentType.automatic("MULTICARTE:FACTURE").get("MULTICARTE"));
    }
}
