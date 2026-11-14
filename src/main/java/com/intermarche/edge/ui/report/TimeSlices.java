package com.intermarche.edge.ui.report;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The SLICES OF TIME the hourly states cut a day into — the hour, the half-hour
 * or the quarter (BO-06-04-02, BO-06-04-06, BO-06-04-07: « possibilité de
 * zoomer sur une plage horaire et avoir le détail au 1/4 d'heure »).
 *
 * <p>Written once so the three states slice the same way: a quarter of the
 * productivity state and a quarter of the hourly sales state are the same
 * fifteen minutes. Zooming is the hour range of the criteria combined with the
 * quarter: the range narrows the day, the quarter details it.
 */
public final class TimeSlices {

    /** The slice widths offered, as {@code minutes:label} pairs. */
    public static final List<String> CHOICES =
            List.of("60:Une heure", "30:Une demi-heure", "15:Un quart d'heure");

    /** The label of the slice-width option. */
    public static final String LABEL = "Largeur de tranche";

    /**
     * Not instantiable.
     */
    private TimeSlices() {
    }

    /**
     * Reads the chosen slice width, falling back to the hour.
     *
     * @param option the submitted width in minutes, possibly null
     * @return 15, 30 or 60
     */
    public static int width(String option) {
        if ("15".equals(option)) {
            return 15;
        }
        if ("30".equals(option)) {
            return 30;
        }
        return 60;
    }

    /**
     * Finds the slice a moment falls in.
     *
     * @param when the moment
     * @param minutes the slice width
     * @return the slice's first minute of the day
     */
    public static int start(LocalDateTime when, int minutes) {
        return when.getHour() * 60 + (when.getMinute() / minutes) * minutes;
    }

    /**
     * Names a slice by its bounds.
     *
     * @param slot the slice's first minute of the day
     * @param minutes the slice width
     * @return the label, for example {@code 14:30 – 14:45}
     */
    public static String name(int slot, int minutes) {
        int end = slot + minutes;
        return String.format("%02d:%02d – %02d:%02d", slot / 60, slot % 60, (end / 60) % 24, end % 60);
    }
}
