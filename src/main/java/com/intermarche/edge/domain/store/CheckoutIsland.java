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
 * A CHECKOUT ISLAND: the group of registers a store manages together
 * (BO-03-06-07, BO-03-06-50, BO-03-10) — the front lane, the service counter,
 * the drive-through, the self-scanning bank.
 *
 * <p>A register knows its own identifier ({@code pos.terminal.id}) and nothing
 * more; the island is the missing level between the register and the point of
 * sale. It exists so that a rule can name a GROUP of registers rather than
 * repeating itself on each one: a voucher range accepted at the service counter
 * and refused on the front lane is one line of administration here, not a rule
 * per till. This lot PROMOTES it to a settings-bearing object: it belongs to a
 * PDV ({@link #pdvNumber}), it is an echelon of its own (a setting posed at
 * {@code EchelonLevel.ISLAND} reaches every register of the island), and it
 * carries its own parameters — whether change may be given (BO-03-10-04), the
 * promotion channel it tags transactions with (BO-03-10-08), and whether it
 * declares the end of period on its own (BO-03-10-10).
 *
 * <p>The attached registers are carried by {@link CheckoutPoint} (its
 * {@code islandCode}), so a register is administered as a first-class checkout
 * point and belongs to an island by pointing at it. The historical semicolon
 * list {@link #terminalIds} is kept as the OLD FORM of the attachment: a
 * register still fed by a node that does not serve the checkout points yet
 * receives its islands that way, and the {@link #terminals()} /
 * {@link #holds(String)} / {@link #findByTerminal(String)} API reads both — the
 * checkout points first, then the old list.
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
     * Whether the transactions of this island's registers are left out of the
     * Planexa flow (BO-03-10-06): an island needing no hostess — a
     * self-service bank, a drive — has no place in the hostesses' planning.
     */
    @Column(name = "planexa_excluded", nullable = false)
    public boolean planexaExcluded = false;

    /**
     * OLD FORM of the attachment (BO-03-06-07): the registers attached to the
     * island, as a semicolon list of terminal identifiers
     * ({@code pos.terminal.id}) — for example {@code POS01;POS02;POS03} — or
     * null. Written only by the referential apply of a register fed with the
     * old payload form; the store node attaches its registers through
     * {@link CheckoutPoint} and leaves this column null.
     */
    @Column(name = "terminal_ids", length = 1000)
    public String terminalIds;

    /**
     * The register HOSTS attached to the island (BO-09-03-01): the badges of
     * the hostesses who supervise its self-service lanes, as a semicolon list,
     * or null when none is attached. Where the store declares them
     * automatically, the sessions these hosts hold on the island's registers
     * are closed at the end of period on their theoretical amount.
     */
    @Column(name = "host_badges", length = 1000)
    public String hostBadges;

    /**
     * Returns the identifiers of the registers attached to this island: first
     * those read from the {@link CheckoutPoint} relation (active points only),
     * ordered by terminal, then those of the old semicolon list
     * {@link #terminalIds} not already named, trimmed and without the empty
     * entries a hand-typed list leaves behind.
     *
     * @return the identifiers, empty when the island holds none
     */
    public List<String> terminals() {
        List<String> terminals = new ArrayList<>();
        for (CheckoutPoint point : CheckoutPoint.listByIsland(code)) {
            terminals.add(point.terminalId);
        }
        if (terminalIds == null || terminalIds.isBlank()) {
            return terminals;
        }
        for (String raw : terminalIds.split(";")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty() && !terminals.contains(trimmed)) {
                terminals.add(trimmed);
            }
        }
        return terminals;
    }

    /**
     * Tells whether the given register belongs to this island.
     *
     * @param terminalId the register's identifier, or null
     * @return true when an active checkout point ties that register to this
     *         island, or when the island's old list names it
     */
    public boolean holds(String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return false;
        }
        return terminals().contains(terminalId.trim());
    }

    /**
     * Returns the badges of the hosts attached to the island (BO-09-03-01),
     * trimmed and without the empty entries a hand-typed list leaves behind.
     *
     * @return the badges, empty when no host is attached
     */
    public List<String> hosts() {
        List<String> hosts = new ArrayList<>();
        if (hostBadges == null) {
            return hosts;
        }
        for (String raw : hostBadges.split(";")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty() && !hosts.contains(trimmed)) {
                hosts.add(trimmed);
            }
        }
        return hosts;
    }

    /**
     * Tells whether a host is attached to the island (BO-09-03-01): an island
     * naming no host takes every host of its registers.
     *
     * @param badge the host's badge, or null
     * @return true when the island names that badge, or names none
     */
    public boolean attaches(String badge) {
        List<String> hosts = hosts();
        return hosts.isEmpty() || (badge != null && hosts.contains(badge.trim()));
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
     * through {@link #holds(String)}, so through its active {@link CheckoutPoint}
     * first and through the old semicolon list of each island otherwise; the
     * active islands are read in code order and the first that holds the
     * register wins.
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
