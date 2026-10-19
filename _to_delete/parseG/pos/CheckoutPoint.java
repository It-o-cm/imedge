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
 * The CHECKOUT POINT of a register, as the store node administers it (BO-03-09,
 * BO-03-10) and pulls it down through the {@code CHECKOUT_POINTS} referential.
 *
 * <p>A register knows its own {@code pos.terminal.id} and nothing else about
 * itself: not the island it belongs to, not whether it is a manned till or a
 * self-service point, not which displays it is supposed to have, not which
 * cash-menu model it should draw, not whether it is even in service. Those are
 * administered in the back office, and this entity is how they arrive here.
 *
 * <p>MIRROR of the node's own {@code CheckoutPoint}: same table, same columns,
 * same names, so a change on either side is carried across by rewriting the
 * package line and the imports and nothing else. The register holds only the
 * finders it actually uses — the back-office screens that create, rename and
 * deactivate a point live on the node, and nothing here ever writes a point
 * except the referential apply.
 *
 * <p>The register receives EVERY point of its store and not only its own: the
 * technical hostess of a self-service bank supervises the points beside her, a
 * remote close names another register, and the island's composition is read
 * here. One extra handful of rows buys all three.
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

    /** The human-readable label given in the back office. */
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
     * or null when none is assigned. The model LIBRARY is the template chantier
     * 03-03/03-04; what descends here is only the assignment.
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
