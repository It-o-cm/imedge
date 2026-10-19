package com.intermarche.pos.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link ScaleProtocol} (BO-03-09-15), targeting 100% branch
 * coverage.
 * <p>
 * Branch enumeration: {@code getLabel} on a constant; {@code fromName} covers
 * the null arm, the unknown arm and the matched arm, trimming included.
 */
class ScaleProtocolTest {

    /**
     * A constant carries its operator-facing label.
     */
    @Test
    void aConstantCarriesItsLabel() {
        assertEquals("Aucune", ScaleProtocol.NONE.getLabel());
        assertEquals("DIGI", ScaleProtocol.DIGI.getLabel());
        assertEquals("CD-06", ScaleProtocol.CD06.getLabel());
    }

    /**
     * {@code fromName} resolves a known name (trimming it), and answers null for
     * a null or an unknown one.
     */
    @Test
    void fromNameResolvesTrimsOrAnswersNull() {
        assertSame(ScaleProtocol.DIGI, ScaleProtocol.fromName(" DIGI "));
        assertNull(ScaleProtocol.fromName(null));
        assertNull(ScaleProtocol.fromName("BIZERBA"));
    }

    /**
     * The protocols the questionnaire names are the ones the enum publishes —
     * no more, so a protocol nobody drives cannot be administered.
     */
    @Test
    void theProtocolsAreTheOnesTheQuestionnaireNames() {
        assertEquals(3, ScaleProtocol.values().length);
    }
}
