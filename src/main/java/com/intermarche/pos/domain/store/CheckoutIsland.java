package com.intermarche.pos.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * A CHECKOUT ISLAND: the group of registers a store manages together
 * (BO-03-06-07, BO-03-06-50) — the front lane, the service counter, the
 * drive-through, the self-scanning bank.
 *
 * <p>A register knows its own identifier ({@code pos.terminal.id}) and nothing
 * more; the island is the missing level between the register and the point of
 * sale. It exists so that a rule can name a GROUP of registers rather than
 * repeating itself on each one: a voucher range accepted at the service counter
 * and refused on the front lane is one line of administration here, not a rule
 * per till.
 *
 * <p>The attached registers are held as a semicolon list rather than a child
 * table on purpose: a store has a handful of islands of a handful of registers,
 * the list is written and read whole on one screen, and a child table would buy
 * nothing but a join. The same shape the settings catalog already uses for its
 * own lists.
 */
@Entity
@Table(name = "checkout_island")
public class CheckoutIsland extends PanacheEntity {

    /** The stable technical code of the island (for example, CAISSES_AVANT). */
    @Column(name = "code", unique = true, nullable = false, length = 50)
    public String code;

    /** The human-readable label shown in the back office. */
    @Column(name = "label", nullable = false, length = 100)
    public String label;

    /** Whether the island is in service; deactivated, never deleted. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * The registers attached to the island, as a semicolon list of terminal
     * identifiers ({@code pos.terminal.id}) — for example
     * {@code POS01;POS02;POS03}.
     */
    @Column(name = "terminal_ids", length = 1000)
    public String terminalIds;

    /**
     * Returns the attached terminal identifiers, trimmed and without the empty
     * entries a hand-typed list leaves behind.
     *
     * @return the identifiers, empty when the island holds none
     */
    public List<String> terminals() {
        List<String> terminals = new ArrayList<>();
        if (terminalIds == null || terminalIds.isBlank()) {
            return terminals;
        }
        for (String raw : terminalIds.split(";")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                terminals.add(trimmed);
            }
        }
        return terminals;
    }

    /**
     * Tells whether the given register belongs to this island.
     *
     * @param terminalId the register's identifier, or null
     * @return true when the island lists that register
     */
    public boolean holds(String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return false;
        }
        return terminals().contains(terminalId.trim());
    }

    /**
     * Returns the active islands in administered order.
     *
     * @return the active islands, empty when the store administers none
     */
    public static List<CheckoutIsland> listActive() {
        return list("active = true order by code");
    }

    /**
     * Returns the active island the given register belongs to.
     *
     * <p>A register listed by no island belongs to none, which is NOT an error:
     * a store that never split its lanes administers no island at all, and every
     * island rule then stays silent.
     *
     * @param terminalId the register's identifier, or null
     * @return the island, or null when no active island lists that register
     */
    public static CheckoutIsland findByTerminal(String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return null;
        }
        for (CheckoutIsland island : listActive()) {
            if (island != null && island.holds(terminalId)) {
                return island;
            }
        }
        return null;
    }
}
