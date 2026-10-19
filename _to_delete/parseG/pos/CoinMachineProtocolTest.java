package com.intermarche.pos.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link CoinMachineProtocol} (BO-03-09-24, -25 and -26),
 * targeting 100% branch coverage.
 * <p>
 * Branch enumeration: {@code getLabel} on a constant; {@code fromName} covers
 * the null arm, the unknown arm and the matched arm, trimming included.
 */
class CoinMachineProtocolTest {

    /**
     * A constant carries its operator-facing label.
     */
    @Test
    void aConstantCarriesItsLabel() {
        assertEquals("Aucun", CoinMachineProtocol.NONE.getLabel());
        assertEquals("Gunnebo", CoinMachineProtocol.GUNNEBO.getLabel());
        assertEquals("Glory", CoinMachineProtocol.GLORY.getLabel());
        assertEquals("Toshiba", CoinMachineProtocol.TOSHIBA.getLabel());
    }

    /**
     * {@code fromName} resolves a known name (trimming it), and answers null for
     * a null or an unknown one.
     */
    @Test
    void fromNameResolvesTrimsOrAnswersNull() {
        assertSame(CoinMachineProtocol.GLORY, CoinMachineProtocol.fromName(" GLORY "));
        assertNull(CoinMachineProtocol.fromName(null));
        assertNull(CoinMachineProtocol.fromName("SCAN_COIN"));
    }

    /**
     * The three protocols the questionnaire names, plus the absence of a
     * machine — one administered choice for three lines.
     */
    @Test
    void theProtocolsAreTheOnesTheQuestionnaireNames() {
        assertEquals(4, CoinMachineProtocol.values().length);
    }
}
