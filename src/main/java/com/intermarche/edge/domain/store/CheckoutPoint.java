package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * A CHECKOUT POINT: the point of contact d'encaissement as an administered
 * object (BO-03-09), the register seen from the back office rather than from the
 * shop floor. The store node administers it (BO-03-09, BO-03-10) and the
 * registers pull it down through the {@code CHECKOUT_POINTS} referential.
 *
 * <p>A register knows only its own {@code pos.terminal.id} and nothing else
 * about itself: not the island it belongs to, not whether it is a manned till or
 * a self-service point, not which displays it is supposed to have, not which
 * cash-menu model it should draw, not whether it is even in service. This entity
 * is that identifier promoted to a first-class object so the back office can
 * activate it (BO-03-09-01), attach it to an island (BO-03-09-05), give it a
 * type (BO-03-09-07), a technical hostess for the self-service families
 * (BO-03-09-08), the type and resolution of its cashier and customer displays
 * (BO-03-09-12/13), and a cash-menu model (BO-03-10-16). It is also the most
 * specific echelon: a setting posed on this terminal wins over the island, the
 * PDV, the enseigne and the country (see {@code EchelonLevel.CHECKOUT_POINT}).
 *
 * <p>The link to the PDV and to the island is held by CODE, the same shape the
 * rest of the store model uses (a PDV references its enseigne by
 * {@code enseigneCode}, an enseigne its country by {@code countryCode}); the
 * technical hostess is referenced by her {@link com.intermarche.edge.domain.people.Employee#badgeId}.
 * This entity is the truth of which register belongs to which island: it
 * REPLACES the semicolon {@code terminalIds} string {@link CheckoutIsland} used
 * to carry.
 *
 * <p>The store node and the registers hold the SAME class: same table, same
 * columns, same names, so a change on either side is carried across by
 * rewriting the package line and the imports and nothing else. The back-office
 * screens that create, rename and deactivate a point live on the node; on a
 * register nothing ever writes a point except the referential apply.
 *
 * <p>The register receives EVERY point of its store and not only its own: the
 * technical hostess of a self-service bank supervises the points beside her, a
 * remote close names another register, and the island's composition is read
 * there. One extra handful of rows buys all three.
 */
@Entity
@Table(name = "checkout_point")
public class CheckoutPoint extends PanacheEntity {

    /** The register identifier ({@code pos.terminal.id}), the stable key. */
    @Column(name = "terminal_id", unique = true, nullable = false, length = 50)
    public String terminalId;

    /** The point-of-vente number this register belongs to, or null when unattached. */
    @Column(name = "pdv_number", length = 5)
    public String pdvNumber;

    /** The code of the island this register belongs to, or null when in none. */
    @Column(name = "island_code", length = 50)
    public String islandCode;

    /** The human-readable label shown in the back office. */
    @Column(name = "label", length = 100)
    public String label;

    /** Whether the register is in service (BO-03-09-01); deactivated, never deleted. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** The kind of checkout point (BO-03-09-07) — the attribute, not a protocol. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tpv_type", length = 24)
    public TpvType tpvType = TpvType.CASH_REGISTER;

    /**
     * The badge of the technical hostess attached to a self-service point
     * (BO-03-09-08), or null when none is attached. Meaningful only when
     * {@link #tpvType} is self-service.
     */
    @Column(name = "technical_hostess_badge", length = 20)
    public String technicalHostessBadge;

    /** The type of the cashier display (BO-03-09-12). */
    @Enumerated(EnumType.STRING)
    @Column(name = "cashier_display_type", length = 16)
    public DisplayType cashierDisplayType = DisplayType.NONE;

    /** The resolution of the cashier display, free text (BO-03-09-12), or null. */
    @Column(name = "cashier_display_resolution", length = 32)
    public String cashierDisplayResolution;

    /** The type of the customer display (BO-03-09-13). */
    @Enumerated(EnumType.STRING)
    @Column(name = "customer_display_type", length = 16)
    public DisplayType customerDisplayType = DisplayType.NONE;

    /** The resolution of the customer display, free text (BO-03-09-13), or null. */
    @Column(name = "customer_display_resolution", length = 32)
    public String customerDisplayResolution;

    /**
     * The protocol of the scale attached to this register (BO-03-09-15), the
     * CD-06 the register already drove included.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scale_protocol", length = 16)
    public ScaleProtocol scaleProtocol = ScaleProtocol.NONE;

    /** Where the scale answers — a host name or an IP (BO-03-09-15), or null. */
    @Column(name = "scale_address", length = 100)
    public String scaleAddress;

    /** The port the scale answers on (BO-03-09-15), or null when it has none. */
    @Column(name = "scale_port")
    public Integer scalePort;

    /**
     * The protocol of the coin machine attached to this register
     * (BO-03-09-24 Gunnebo, -25 Glory, -26 Toshiba).
     *
     * <p>ONE field for the three lines: they ask for the same protocol, address
     * and port and differ only by the name, so a store that changes supplier
     * changes a value rather than waiting for a screen.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "coin_machine_protocol", length = 16)
    public CoinMachineProtocol coinMachineProtocol = CoinMachineProtocol.NONE;

    /** Where the coin machine answers — a host name or an IP, or null. */
    @Column(name = "coin_machine_address", length = 100)
    public String coinMachineAddress;

    /** The port the coin machine answers on, or null when it has none. */
    @Column(name = "coin_machine_port")
    public Integer coinMachinePort;

    /**
     * The code of the cash-menu model assigned to this register (BO-03-10-16),
     * or null when none is assigned. The model LIBRARY itself (the ninety-nine
     * named models) belongs to the template chantier 03-03/03-04; this lot poses
     * only the assignment.
     */
    @Column(name = "menu_template_code", length = 50)
    public String menuTemplateCode;

    /**
     * Finds a checkout point by its terminal identifier.
     *
     * @param terminalId the register identifier, or null
     * @return the checkout point, or null when none carries that identifier
     */
    public static CheckoutPoint findByTerminal(String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return null;
        }
        return find("terminalId", terminalId.trim()).firstResult();
    }

    /**
     * Tells whether a register is OUT OF SERVICE by administration
     * (BO-03-09-01): the reports, the closure controls and the connectivity
     * supervision leave such a register out.
     *
     * @param terminalId the register identifier, or null
     * @return true only when a point exists for it and is deactivated
     */
    public static boolean isDeactivated(String terminalId) {
        CheckoutPoint point = findByTerminal(terminalId);
        return point != null && !point.active;
    }

    /**
     * Lists the identifiers of the deactivated registers (BO-03-09-01).
     *
     * @return the identifiers, in terminal order, empty when every point is in service
     */
    public static List<String> deactivatedTerminals() {
        List<String> terminals = new ArrayList<>();
        for (CheckoutPoint point : CheckoutPoint.<CheckoutPoint>list("active = false order by terminalId")) {
            terminals.add(point.terminalId);
        }
        return terminals;
    }

    /**
     * Lists the ACTIVE checkout points of an island, ordered by terminal — what
     * a technical hostess screen needs to know which lanes it supervises.
     *
     * @param islandCode the island code, or null
     * @return the active points of that island, empty when none or when the code
     *         is null
     */
    public static List<CheckoutPoint> listByIsland(String islandCode) {
        if (islandCode == null || islandCode.isBlank()) {
            return new ArrayList<>();
        }
        return list("islandCode = ?1 and active = true order by terminalId", islandCode.trim());
    }

    /**
     * Counts the checkout points still attached to an island, active or not —
     * the guard the island screen uses before allowing a deletion (BO-03-10-02).
     *
     * @param islandCode the island code, or null
     * @return the number of points attached to that island, zero when the code is null
     */
    public static long countByIsland(String islandCode) {
        if (islandCode == null || islandCode.isBlank()) {
            return 0L;
        }
        return count("islandCode = ?1", islandCode.trim());
    }

    /**
     * Lists every checkout point ordered by terminal, for the back-office screen.
     *
     * @return the checkout points, never null
     */
    public static List<CheckoutPoint> listAllOrdered() {
        return list("order by terminalId");
    }

    /**
     * Tells whether this point is a self-service one, for which a technical
     * hostess is relevant (BO-03-09-08).
     *
     * @return true for every self-service family, false for a manned till and
     *         for a point whose type the node has not described
     */
    public boolean isSelfService() {
        return tpvType != null && tpvType.isSelfService();
    }

    /**
     * Tells whether this point declares a customer display (BO-03-09-13).
     *
     * <p>A point administered without one must not be reported in fault for not
     * polling the display it does not have; a point whose type the node has not
     * described is treated as having one, because supervising a display that
     * exists matters more than sparing a warning on a half-filled record.
     *
     * @return false when the node explicitly says NONE, true otherwise
     */
    public boolean hasCustomerDisplay() {
        return customerDisplayType != DisplayType.NONE;
    }
}
