package com.intermarche.edge.ui.supervision;

import java.util.List;

/**
 * Draws the OK / KO CHART of the supervision and deployment consoles as an
 * inline SVG, server side, with no library (BO-08-01-17, BO-08-03-06,
 * BO-08-04-15, BO-08-04-20, BO-08-02-02).
 *
 * <p>One stacked bar per group — the whole organisation, a country, an
 * enseigne, a point of sale, a version, a function — its green part the
 * healthy count, its red part the faulty one. The legend and the empty case
 * are the template's: they are words, and words are translated there.
 */
public final class StatusChart {

    /** The drawing's width, in user units. */
    static final int WIDTH = 960;

    /** The drawing's height, in user units. */
    static final int HEIGHT = 260;

    /** The space kept under the bars for their labels. */
    static final int BOTTOM = 40;

    /** The space kept above the highest bar. */
    static final int TOP = 16;

    /** The most bars drawn; the others are left to the list. */
    static final int MAX_BARS = 40;

    /** The colour of the healthy part. */
    static final String OK_FILL = "#2e8540";

    /** The colour of the faulty part. */
    static final String KO_FILL = "#c0392b";

    /**
     * One bar of the chart.
     *
     * @param label what the bar counts
     * @param ok the healthy count
     * @param ko the faulty count
     */
    public record Bar(String label, long ok, long ko) {
    }

    /** Utility class: no instance. */
    private StatusChart() {
    }

    /**
     * Renders the bars.
     *
     * @param bars the bars, the first {@link #MAX_BARS} drawn
     * @return the SVG markup, or an empty string when there is nothing to draw
     */
    public static String svg(List<Bar> bars) {
        List<Bar> drawn = bars.size() > MAX_BARS ? bars.subList(0, MAX_BARS) : bars;
        long top = 0;
        for (Bar bar : drawn) {
            top = Math.max(top, bar.ok() + bar.ko());
        }
        if (top == 0) {
            return "";
        }
        int slot = WIDTH / drawn.size();
        int width = Math.max(4, slot * 6 / 10);
        int area = HEIGHT - BOTTOM - TOP;
        StringBuilder svg = new StringBuilder("<svg class=\"report-chart\" viewBox=\"0 0 ").append(WIDTH)
                .append(' ').append(HEIGHT).append("\" preserveAspectRatio=\"none\" role=\"img\">");
        svg.append("<line x1=\"0\" y1=\"").append(HEIGHT - BOTTOM).append("\" x2=\"").append(WIDTH)
                .append("\" y2=\"").append(HEIGHT - BOTTOM).append("\" class=\"report-chart-axis\"/>");
        for (int i = 0; i < drawn.size(); i++) {
            Bar bar = drawn.get(i);
            int okHeight = (int) (bar.ok() * area / top);
            int koHeight = (int) (bar.ko() * area / top);
            int x = i * slot + (slot - width) / 2;
            int base = HEIGHT - BOTTOM;
            String title = "<title>" + escape(bar.label()) + " : " + bar.ok() + " / " + bar.ko() + "</title>";
            svg.append("<rect x=\"").append(x).append("\" y=\"").append(base - okHeight).append("\" width=\"")
                    .append(width).append("\" height=\"").append(okHeight).append("\" fill=\"").append(OK_FILL)
                    .append("\">").append(title).append("</rect>");
            svg.append("<rect x=\"").append(x).append("\" y=\"").append(base - okHeight - koHeight)
                    .append("\" width=\"").append(width).append("\" height=\"").append(koHeight)
                    .append("\" fill=\"").append(KO_FILL).append("\">").append(title).append("</rect>");
            svg.append("<text x=\"").append(x + width / 2).append("\" y=\"").append(base + 16)
                    .append("\" class=\"report-chart-label\">").append(escape(shorten(bar.label())))
                    .append("</text>");
        }
        return svg.append("</svg>").toString();
    }

    /**
     * Shortens a label to what fits under a bar.
     *
     * @param value the label
     * @return the label, cut to twelve characters
     */
    static String shorten(String value) {
        return value.length() <= 12 ? value : value.substring(0, 11) + "…";
    }

    /**
     * Escapes the characters XML reserves.
     *
     * @param value the raw text
     * @return the escaped text
     */
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
