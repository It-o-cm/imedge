package com.intermarche.edge.domain.barcode;

import java.util.Locale;

/**
 * The register functions an administered barcode range may call at scan
 * (BO-03-06-59): reading the code IS pressing the key. Each function keeps the
 * number a code may carry in place of its name, so a printed card can say
 * "function 2" when the range leaves the function to the code.
 */
public enum RegisterFunction {

    /** Shows the legal metrology screen, the scale's CRC included. */
    METROLOGY(1, "Contrôle métrologique (CRC balance)"),

    /** Starts the survey whose number the code's value segment carries. */
    SURVEY(2, "Sondage"),

    /** Launches the external program installed on the register. */
    EXTERNAL_PROGRAM(3, "Programme externe"),

    /** Opens the list of the tickets on hold. */
    PARKED_TICKETS(4, "Tickets en attente"),

    /** Opens the recall of a quote, an order or a basket. */
    RECALL(5, "Rappel de document");

    /** The number a code may carry in place of the name. */
    private final int number;

    /** The label the back office shows. */
    private final String label;

    /**
     * Declares a function.
     *
     * @param number the number a code may carry in place of the name
     * @param label the label the back office shows
     */
    RegisterFunction(int number, String label) {
        this.number = number;
        this.label = label;
    }

    /**
     * The number a code may carry in place of the name.
     *
     * @return the number
     */
    public int getNumber() {
        return number;
    }

    /**
     * The label the back office shows.
     *
     * @return the label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Resolves an administered function name, tolerating case and padding.
     *
     * @param name the name, possibly null or blank
     * @return the function, or null when the name names none
     */
    public static RegisterFunction byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Resolves the number a code carries.
     *
     * @param number the number read on the code
     * @return the function, or null when the number names none
     */
    public static RegisterFunction byNumber(int number) {
        for (RegisterFunction function : values()) {
            if (function.number == number) {
                return function;
            }
        }
        return null;
    }
}
