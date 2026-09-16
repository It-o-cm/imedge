package com.intermarche.pos.ui;

/**
 * The ways the operator may change what a line — or the whole ticket — costs.
 *
 * <p>THESE SIX WERE SIX SPELLINGS OF THE SAME WORD. The gesture travelled as a
 * {@code String} from the URL of the key, through the modal state, onto the ticket
 * line, into the database column, out to the store node and into the valuation
 * request — six sites in two layers, each comparing a literal the compiler never
 * checked. A typo compiled, and the only thing that noticed was a modal opening
 * under the fallback title.
 *
 * <p>WHAT A MODE IS, THE MODE ITSELF SAYS. Whether the modal must offer the
 * line-level trio or the ticket-level pair, and what title it carries, are
 * properties of the gesture and not a cascade of tests to keep in step: they are
 * declared once, on each constant. The database keeps the very same words — the
 * column is written by name — so nothing has to be migrated for the compiler to
 * start doing the checking.
 */
public enum PriceModType {

    /** A reduction in euros on one line ({@code BO-03-07-04}). */
    REMISE(Scope.LINE, "SAISIE REMISE (€)"),

    /** A reduction in percent on one line ({@code BO-03-07-02}). */
    DISCOUNT(Scope.LINE, "SAISIE DISCOUNT (%)"),

    /** A price typed in place of the catalog's, on one line ({@code BO-10-07-12}). */
    FORCE_PRICE(Scope.LINE, "NOUVEAU PRIX (€)"),

    /**
     * How many, not how much ({@code LC-02-13-11}).
     *
     * <p>It shares the modal and the numpad with the three above and belongs to
     * neither group: the modal must not offer it the choice between euros and
     * percent, because it is not a price question.
     */
    QUANTITY(Scope.NEITHER, "QUANTITÉ ARTICLE"),

    /** A reduction in euros on the whole ticket ({@code BO-03-07-07}). */
    GLOBAL_REMISE(Scope.TICKET, "REMISE TICKET (€)"),

    /** A reduction in percent on the whole ticket ({@code BO-03-07-08}). */
    GLOBAL_DISCOUNT(Scope.TICKET, "REMISE TICKET (%)");

    /** What a mode applies to, which decides the modes the modal offers beside it. */
    private enum Scope {
        /** One ticket line. */
        LINE,
        /** The whole ticket. */
        TICKET,
        /** Neither — the mode shares the modal without sharing the question. */
        NEITHER
    }

    /** What this mode applies to. */
    private final Scope scope;

    /** The title the modal carries for this mode. */
    private final String label;

    /**
     * Declares a mode.
     *
     * @param scope what the mode applies to
     * @param label the title the modal carries
     */
    PriceModType(Scope scope, String label) {
        this.scope = scope;
        this.label = label;
    }

    /**
     * The title the modal carries for this mode.
     *
     * @return the display title
     */
    public String getLabel() {
        return label;
    }

    /**
     * Whether this mode is one of the three ways of changing the price of ONE line.
     *
     * <p>These three were three separate keys of the VENTE menu, a quarter of the ten
     * slots the register has, for what is one gesture in three modes: the operator
     * picks the line, then says how the price moves. The modal offers the three
     * together, so the key carries one slot instead of three.
     *
     * @return true for {@link #REMISE}, {@link #DISCOUNT} and {@link #FORCE_PRICE}
     */
    public boolean isLineLevel() {
        return scope == Scope.LINE;
    }

    /**
     * Whether this mode is one of the two ways of discounting the WHOLE ticket.
     *
     * @return true for {@link #GLOBAL_REMISE} and {@link #GLOBAL_DISCOUNT}
     */
    public boolean isTicketLevel() {
        return scope == Scope.TICKET;
    }

    /**
     * Reads a mode from the word that names it — a key's URL, a persisted column, a
     * payload from the store node.
     *
     * <p>IT ANSWERS NULL RATHER THAN THROWING. A word this version does not know is a
     * stale page, an older node or a hand-typed URL; the caller refuses the gesture
     * and says so, which is a register that keeps selling, not one that answers 500.
     *
     * @param name the word naming the mode, or null
     * @return the mode, or null when the word names none
     */
    public static PriceModType of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (PriceModType type : values()) {
            if (type.name().equals(name.trim())) {
                return type;
            }
        }
        return null;
    }
}
