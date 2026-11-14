package com.intermarche.edge.ui.report;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link TimeSlices}: the three widths and the fallback, the
 * slice of a moment, the name of a slice crossing midnight.
 */
class TimeSlicesTest {

    /**
     * The width reads 15 and 30, anything else the hour.
     */
    @Test
    void theWidthFallsBackToTheHour() {
        assertEquals(15, TimeSlices.width("15"));
        assertEquals(30, TimeSlices.width("30"));
        assertEquals(60, TimeSlices.width("60"));
        assertEquals(60, TimeSlices.width(null));
    }

    /**
     * A moment falls in the slice of its width.
     */
    @Test
    void aMomentFallsInItsSlice() {
        LocalDateTime when = LocalDateTime.of(2026, 10, 20, 14, 37);
        assertEquals(14 * 60 + 30, TimeSlices.start(when, 15));
        assertEquals(14 * 60 + 30, TimeSlices.start(when, 30));
        assertEquals(14 * 60, TimeSlices.start(when, 60));
    }

    /**
     * A slice is named by its bounds, the last one of the day ending at midnight.
     */
    @Test
    void aSliceIsNamedByItsBounds() {
        assertEquals("14:30 – 14:45", TimeSlices.name(14 * 60 + 30, 15));
        assertEquals("23:00 – 00:00", TimeSlices.name(23 * 60, 60));
    }
}
