package com.intermarche.edge.ui.admin;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Code128Svg} (BO-03-06-51), targeting 100% branch
 * coverage.
 *
 * <p>Branch enumeration: a null and an empty text refused; a character below
 * and above character set B refused; a one-character text encoded with its
 * hand-computed check symbol; the drawing's runs of bars, its quiet zone and
 * its escaped label.
 */
class Code128SvgTest {

    /**
     * Nothing to encode, or a character set B cannot carry, is refused.
     */
    @Test
    void whatSetBCannotCarryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Code128Svg.modules(null));
        assertThrows(IllegalArgumentException.class, () -> Code128Svg.modules(""));
        assertThrows(IllegalArgumentException.class, () -> Code128Svg.modules("A\tB"));
        assertThrows(IllegalArgumentException.class, () -> Code128Svg.modules("é"));
    }

    /**
     * "A" is start B, the symbol of value 33, the check symbol (104 + 33) mod
     * 103 = 34 and the stop; every symbol is eleven modules wide.
     */
    @Test
    void aTextIsEncodedWithItsCheckSymbol() {
        assertEquals(Code128Svg.PATTERNS[104] + Code128Svg.PATTERNS[33] + Code128Svg.PATTERNS[34]
                + Code128Svg.STOP, Code128Svg.modules("A"));
        assertEquals("10001011000", Code128Svg.PATTERNS[34]);
        assertEquals(106, Code128Svg.PATTERNS.length);
        for (String pattern : Code128Svg.PATTERNS) {
            assertEquals(11, pattern.length());
        }
        assertEquals(11 * 13 + 13, Code128Svg.modules("SUP12341234").length());
    }

    /**
     * The drawing merges the bars into runs after the quiet zone, sizes itself
     * on the modules and carries the escaped text as its label.
     */
    @Test
    void theDrawingMergesTheBarsAfterTheQuietZone() {
        String svg = Code128Svg.svg("A&<\">");
        int modules = Code128Svg.modules("A&<\">").length();
        int width = (modules + 2 * Code128Svg.QUIET) * Code128Svg.MODULE;
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"60\""));
        assertTrue(svg.contains("aria-label=\"A&amp;&lt;&quot;&gt;\""));
        assertTrue(svg.contains("<rect x=\"20\" y=\"0\" width=\"4\" height=\"60\" fill=\"#000\"/>"));
        assertTrue(svg.endsWith("</svg>"));
        String one = Code128Svg.svg("A");
        int bars = one.split("fill=\"#000\"", -1).length - 1;
        String encoded = Code128Svg.modules("A");
        int runs = encoded.split("0+").length - (encoded.startsWith("0") ? 1 : 0);
        assertEquals(runs, bars);
    }

    /**
     * The class is a drawing function and cannot be instantiated from outside.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void theClassIsNotInstantiable() throws Exception {
        Constructor<Code128Svg> constructor = Code128Svg.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
