package com.intermarche.pos.domain.barcode;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AlertLevel}, targeting 100% branch coverage.
 * <p>
 * A pure enum: every test runs on the constants themselves, with no
 * collaborator and no context.
 * <p>
 * Branch enumeration (every leg exercised): {@code of} covers the null leg,
 * the blank leg, the readable value and the unknown value that falls back to
 * silence, tolerating case and padding; {@code speaks} and {@code blocks} cover
 * each of the three constants; {@code max} covers the null arm, the sterner
 * other, the milder other and the equal case.
 */
class AlertLevelTest {

    /**
     * of falls back to silence on a null value (first leg of the guard).
     */
    @Test
    void ofNullIsNone() {
        Assertions.assertEquals(AlertLevel.NONE, AlertLevel.of(null));
    }

    /**
     * of falls back to silence on a blank value (second leg of the guard).
     */
    @Test
    void ofBlankIsNone() {
        Assertions.assertEquals(AlertLevel.NONE, AlertLevel.of("   "));
    }

    /**
     * of reads an administered value, tolerating case and padding.
     */
    @Test
    void ofReadsAnAdministeredValue() {
        Assertions.assertEquals(AlertLevel.INFO, AlertLevel.of("  info  "));
        Assertions.assertEquals(AlertLevel.BLOCK, AlertLevel.of("BLOCK"));
        Assertions.assertEquals(AlertLevel.NONE, AlertLevel.of("None"));
    }

    /**
     * of falls back to silence on a value nobody declared, because a mistyped
     * parameter must not stop a lane.
     */
    @Test
    void ofUnknownValueIsNone() {
        Assertions.assertEquals(AlertLevel.NONE, AlertLevel.of("REFUSER"));
    }

    /**
     * speaks separates silence from the two levels that say something.
     */
    @Test
    void speaksSeparatesSilenceFromTheRest() {
        Assertions.assertFalse(AlertLevel.NONE.speaks());
        Assertions.assertTrue(AlertLevel.INFO.speaks());
        Assertions.assertTrue(AlertLevel.BLOCK.speaks());
    }

    /**
     * blocks is true of the blocking level only.
     */
    @Test
    void blocksIsTrueOfTheBlockingLevelOnly() {
        Assertions.assertFalse(AlertLevel.NONE.blocks());
        Assertions.assertFalse(AlertLevel.INFO.blocks());
        Assertions.assertTrue(AlertLevel.BLOCK.blocks());
    }

    /**
     * max hands back this level when the other is absent (guard arm).
     */
    @Test
    void maxAgainstNullIsThisLevel() {
        Assertions.assertEquals(AlertLevel.INFO, AlertLevel.INFO.max(null));
    }

    /**
     * max keeps the sterner of the two, whichever side it is on.
     */
    @Test
    void maxKeepsTheSterner() {
        Assertions.assertEquals(AlertLevel.BLOCK, AlertLevel.INFO.max(AlertLevel.BLOCK));
        Assertions.assertEquals(AlertLevel.BLOCK, AlertLevel.BLOCK.max(AlertLevel.INFO));
        Assertions.assertEquals(AlertLevel.INFO, AlertLevel.NONE.max(AlertLevel.INFO));
        Assertions.assertEquals(AlertLevel.INFO, AlertLevel.INFO.max(AlertLevel.NONE));
    }

    /**
     * max of a level with itself is that level (the equal arm).
     */
    @Test
    void maxOfALevelWithItselfIsThatLevel() {
        Assertions.assertEquals(AlertLevel.INFO, AlertLevel.INFO.max(AlertLevel.INFO));
    }

    /**
     * The catalog holds exactly the three declared levels, in increasing
     * sternness — the order {@code max} relies on.
     */
    @Test
    void catalogIsOrderedFromSilenceToRefusal() {
        Assertions.assertArrayEquals(
                new AlertLevel[] {AlertLevel.NONE, AlertLevel.INFO, AlertLevel.BLOCK},
                AlertLevel.values());
    }
}
