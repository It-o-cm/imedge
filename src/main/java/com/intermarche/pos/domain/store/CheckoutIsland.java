package com.intermarche.pos.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * A CHECKOUT ISLAND: the group of registers a store manages together
 * (BO-03-06-07, BO-03-10) — the front lane, the service counter, the
 * drive-through, the self-scanning bank.
 *
 * <p>The island is the level between the register and the point of sale. It
 * exists so that a rule can name a GROUP of registers rather than repeating
 * itself on each one, and this lot PROMOTES it to a settings-bearing object: it
 * belongs to a PDV ({@link #pdvNumber}), it is an echelon of its own (a setting
 * posed at {@code EchelonLevel.ISLAND} reaches every register of the island),
 * and it carries its own parameters — whether change may be given
 * (BO-03-10-04), the promotion channel it tags transactions with
 * (BO-03-10-08), and whether it declares the end of period on its own
 * (BO-03-10-10).
 *
 * <p>The attached registers are NO LONGER a semicolon string on the island: the
 * relation is now carried by {@link CheckoutPoint} (its {@code islandCode}), so
 * a register is administered as a first-class checkout point and belongs to an
 * island by pointing at it. The {@link #terminals()} / {@link #holds(String)} /
 * {@link #findByTerminal(String)} API is preserved for callers, but it reads the
 * relation from {@link CheckoutPoint} now.
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

    /** The point-of-vente number this island belongs to (BO-03-10), or null. */
    @Column(name = "pdv_number", length = 5)
    public String pdvNumber;

    /** Whether change may be given at this island (BO-03-10-04). */
    @Column(name = "change_giving_allowed", nullable = false)
    public boolean changeGivingAllowed = true;

    /** The promotion channel this island tags its transactions with (BO-03-10-08). */
    @Enumerated(EnumType.STRING)
    @Column(name = "promotion_channel", length = 16)
    public PromotionChannel promotionChannel = PromotionChannel.POINT_OF_SALE;

    /** Whether the island declares the end of period on its own (BO-03-10-10). */
    @Column(name = "auto_period_close_declaration", nullable = false)
    public boolean autoPeriodCloseDeclaration = false;

    /**
     * Returns the identifiers of the registers attached to this island, read
     * from the {@link CheckoutPoint} relation (active points only), ordered by
     * terminal.
     *
     * @return the identifiers, empty when the island holds none
     */
    public List<String> terminals() {
        List<String> terminals = new ArrayList<>();
        for (CheckoutPoint point : CheckoutPoint.listByIsland(code)) {
            terminals.add(point.terminalId);
        }
        return terminals;
    }

    /**
     * Tells whether the given register belongs to this island.
     *
     * @param terminalId the register's identifier, or null
     * @return true when an active checkout point ties that register to this island
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
     * Finds an island by its unique code.
     *
     * @param code the island code, or null
     * @return the island, or null when no row carries that code
     */
    public static CheckoutIsland findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim()).firstResult();
    }

    /**
     * Returns the active island the given register belongs to.
     *
     * <p>A register attached to no island belongs to none, which is NOT an
     * error: a store that never split its lanes administers no island at all, and
     * every island rule then stays silent. The register's island is resolved
     * through its {@link CheckoutPoint}.
     *
     * @param terminalId the register's identifier, or null
     * @return the island, or null when no active island lists that register
     */
    public static CheckoutIsland findByTerminal(String terminalId) {
        CheckoutPoint point = CheckoutPoint.findByTerminal(terminalId);
        if (point == null || point.islandCode == null) {
            return null;
        }
        CheckoutIsland island = findByCode(point.islandCode);
        return island != null && island.active ? island : null;
    }
}
