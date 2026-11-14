package com.intermarche.edge.ui.supervision;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link StatusChart}. */
class StatusChartTest {

    /** Nothing to count draws nothing. */
    @Test
    void anEmptyChartIsEmpty() {
        assertEquals("", StatusChart.svg(List.of()));
        assertEquals("", StatusChart.svg(List.of(new StatusChart.Bar("FR", 0, 0))));
    }

    /** BO-08-04-15: one stacked bar per group, green then red, its label escaped and shortened. */
    @Test
    void eachGroupIsAStackedBar() {
        String svg = StatusChart.svg(List.of(new StatusChart.Bar("ITM & co <Nord> \"x\"", 3, 1),
                new StatusChart.Bar("FR", 1, 0)));
        assertTrue(svg.startsWith("<svg class=\"report-chart\""));
        assertTrue(svg.contains("fill=\"#2e8540\""));
        assertTrue(svg.contains("fill=\"#c0392b\""));
        assertTrue(svg.contains("ITM &amp; co &lt;Nord&gt; &quot;x&quot; : 3 / 1"));
        assertTrue(svg.contains(">ITM &amp; co &lt;N…</text>"));
        assertTrue(svg.contains(">FR</text>"));
        assertTrue(svg.endsWith("</svg>"));
    }

    /** Beyond forty groups only the first forty are drawn. */
    @Test
    void theBarsAreCapped() {
        List<StatusChart.Bar> bars = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            bars.add(new StatusChart.Bar("G" + i, 1, 0));
        }
        String svg = StatusChart.svg(bars);
        assertTrue(svg.contains(">G39</text>"));
        assertFalse(svg.contains(">G40</text>"));
    }
}
