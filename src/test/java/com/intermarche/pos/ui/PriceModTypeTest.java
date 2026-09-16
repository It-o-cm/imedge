package com.intermarche.pos.ui;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PriceModType} — the six price gestures and the parser
 * that reads one back from the word naming it.
 *
 * <p>The parser is the only place a gesture is still a string: a key's URL, a
 * persisted column, a payload from the store node. Every one of its exits is
 * covered here, because every one of them decides whether a gesture is applied
 * or refused.
 */
class PriceModTypeTest {

    /**
     * The three per-line modes claim the line scope, and nothing else does.
     */
    @Test
    void onlyTheThreeLineModesAreLineLevel() {
        Assertions.assertTrue(PriceModType.REMISE.isLineLevel());
        Assertions.assertTrue(PriceModType.DISCOUNT.isLineLevel());
        Assertions.assertTrue(PriceModType.FORCE_PRICE.isLineLevel());
        Assertions.assertFalse(PriceModType.QUANTITY.isLineLevel());
        Assertions.assertFalse(PriceModType.GLOBAL_REMISE.isLineLevel());
        Assertions.assertFalse(PriceModType.GLOBAL_DISCOUNT.isLineLevel());
    }

    /**
     * The two whole-ticket modes claim the ticket scope, and nothing else does.
     */
    @Test
    void onlyTheTwoGlobalModesAreTicketLevel() {
        Assertions.assertTrue(PriceModType.GLOBAL_REMISE.isTicketLevel());
        Assertions.assertTrue(PriceModType.GLOBAL_DISCOUNT.isTicketLevel());
        Assertions.assertFalse(PriceModType.REMISE.isTicketLevel());
        Assertions.assertFalse(PriceModType.DISCOUNT.isTicketLevel());
        Assertions.assertFalse(PriceModType.FORCE_PRICE.isTicketLevel());
        Assertions.assertFalse(PriceModType.QUANTITY.isTicketLevel());
    }

    /**
     * No mode claims BOTH scopes: the two routers that ask these questions are
     * mutually exclusive, and a mode answering yes twice would be applied twice.
     */
    @Test
    void noModeIsBothLineAndTicketLevel() {
        for (PriceModType type : PriceModType.values()) {
            Assertions.assertFalse(type.isLineLevel() && type.isTicketLevel(),
                    "mode des deux portées à la fois: " + type);
        }
    }

    /**
     * Every mode carries a non-blank title — a modal opening untitled is a modal
     * the operator cannot read.
     */
    @Test
    void everyModeCarriesALabel() {
        for (PriceModType type : PriceModType.values()) {
            Assertions.assertNotNull(type.getLabel(), "titre absent pour " + type);
            Assertions.assertFalse(type.getLabel().isBlank(), "titre vide pour " + type);
        }
    }

    /**
     * Each of the six words reads back as its own mode — the round trip the
     * persisted column and the store payload both rely on.
     */
    @Test
    void everyNameReadsBackAsItsOwnMode() {
        for (PriceModType type : PriceModType.values()) {
            Assertions.assertSame(type, PriceModType.of(type.name()));
        }
    }

    /**
     * A null word names no mode (null arm of the guard).
     */
    @Test
    void ofNullNamesNoMode() {
        Assertions.assertNull(PriceModType.of(null));
    }

    /**
     * An empty word names no mode (empty arm of the blank guard).
     */
    @Test
    void ofEmptyNamesNoMode() {
        Assertions.assertNull(PriceModType.of(""));
    }

    /**
     * A whitespace-only word names no mode (whitespace arm of the blank guard).
     */
    @Test
    void ofBlankNamesNoMode() {
        Assertions.assertNull(PriceModType.of("   "));
    }

    /**
     * A word no constant carries names no mode, and the answer is null rather
     * than an exception: a stale page or an older node must not answer 500.
     */
    @Test
    void ofUnknownWordNamesNoMode() {
        Assertions.assertNull(PriceModType.of("MYSTERY"));
    }

    /**
     * A word that merely CONTAINS a mode's name is not that mode: the match is
     * on the whole word, so a future gesture named {@code REMISE_GLOBALE} can
     * never be read as {@code REMISE}.
     */
    @Test
    void ofPartialWordNamesNoMode() {
        Assertions.assertNull(PriceModType.of("REMISE_GLOBALE"));
        Assertions.assertNull(PriceModType.of("GLOBAL"));
    }

    /**
     * A word surrounded by spaces still names its mode — a payload field that
     * arrived padded is not a stale gesture.
     */
    @Test
    void ofTrimsSurroundingSpaces() {
        Assertions.assertSame(PriceModType.REMISE, PriceModType.of("  REMISE  "));
    }

    /**
     * The match is case-SENSITIVE: the words are written by the register itself,
     * upper-cased at every entry point, and a lower-case one is a caller that
     * did not do its half.
     */
    @Test
    void ofIsCaseSensitive() {
        Assertions.assertNull(PriceModType.of("remise"));
    }
}
