package com.intermarche.edge.ui.admin;

/**
 * Draws a Code 128 barcode as an SVG image, for the cards the back office
 * prints (BO-03-06-51): character set B, which carries the letters, the
 * digits and the punctuation a badge or a prefix is made of, a modulo-103
 * check symbol, and a quiet zone of ten modules on each side.
 */
public final class Code128Svg {

    /** The width of a module, in SVG units. */
    static final int MODULE = 2;

    /** The height of the bars, in SVG units. */
    static final int HEIGHT = 60;

    /** The quiet zone on each side, in modules. */
    static final int QUIET = 10;

    /** The start symbol of character set B. */
    static final int START_B = 104;

    /** The bars and spaces of the 106 symbols, one character per module. */
    static final String[] PATTERNS = {
            "11011001100", "11001101100", "11001100110", "10010011000", "10010001100", "10001001100",
            "10011001000", "10011000100", "10001100100", "11001001000", "11001000100", "11000100100",
            "10110011100", "10011011100", "10011001110", "10111001100", "10011101100", "10011100110",
            "11001110010", "11001011100", "11001001110", "11011100100", "11001110100", "11101101110",
            "11101001100", "11100101100", "11100100110", "11101100100", "11100110100", "11100110010",
            "11011011000", "11011000110", "11000110110", "10100011000", "10001011000", "10001000110",
            "10110001000", "10001101000", "10001100010", "11010001000", "11000101000", "11000100010",
            "10110111000", "10110001110", "10001101110", "10111011000", "10111000110", "10001110110",
            "11101110110", "11010001110", "11000101110", "11011101000", "11011100010", "11011101110",
            "11101011000", "11101000110", "11100010110", "11101101000", "11101100010", "11100011010",
            "11101111010", "11001000010", "11110001010", "10100110000", "10100001100", "10010110000",
            "10010000110", "10000101100", "10000100110", "10110010000", "10110000100", "10011010000",
            "10011000010", "10000110100", "10000110010", "11000010010", "11001010000", "11110111010",
            "11000010100", "10001111010", "10100111100", "10010111100", "10010011110", "10111100100",
            "10011110100", "10011110010", "11110100100", "11110010100", "11110010010", "11011011110",
            "11011110110", "11110110110", "10101111000", "10100011110", "10001011110", "10111101000",
            "10111100010", "11110101000", "11110100010", "10111011110", "10111101110", "11101011110",
            "11110101110", "11010000100", "11010010000", "11010011100"
    };

    /** The stop symbol and its termination bar. */
    static final String STOP = "1100011101011";

    /**
     * Not instantiable: a drawing function.
     */
    private Code128Svg() {
    }

    /**
     * The modules of a text encoded in character set B: start, data, check,
     * stop.
     *
     * @param text the text, printable ASCII only
     * @return one character per module, '1' for a bar
     * @throws IllegalArgumentException when the text is empty or carries a
     *         character set B cannot encode
     */
    static String modules(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Nothing to encode");
        }
        StringBuilder modules = new StringBuilder(PATTERNS[START_B]);
        int check = START_B;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 || c > 126) {
                throw new IllegalArgumentException("Character set B cannot encode " + (int) c);
            }
            int value = c - 32;
            check += value * (i + 1);
            modules.append(PATTERNS[value]);
        }
        modules.append(PATTERNS[check % 103]);
        modules.append(STOP);
        return modules.toString();
    }

    /**
     * Draws a text as a Code 128 barcode.
     *
     * @param text the text, printable ASCII only
     * @return the SVG element, bars merged into runs
     */
    public static String svg(String text) {
        String modules = modules(text);
        int width = (modules.length() + 2 * QUIET) * MODULE;
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(HEIGHT).append("\" viewBox=\"0 0 ").append(width).append(' ')
                .append(HEIGHT).append("\" role=\"img\" aria-label=\"").append(escape(text)).append("\">")
                .append("<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>");
        int i = 0;
        while (i < modules.length()) {
            if (modules.charAt(i) == '1') {
                int run = i;
                while (run < modules.length() && modules.charAt(run) == '1') {
                    run++;
                }
                svg.append("<rect x=\"").append((QUIET + i) * MODULE).append("\" y=\"0\" width=\"")
                        .append((run - i) * MODULE).append("\" height=\"").append(HEIGHT)
                        .append("\" fill=\"#000\"/>");
                i = run;
            } else {
                i++;
            }
        }
        return svg.append("</svg>").toString();
    }

    /**
     * Escapes a text for an XML attribute.
     *
     * @param text the text
     * @return the escaped text
     */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
