package com.intermarche.pos.domain.payment;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A foreign currency the shop accepts at the till ({@code LC-07-14}).
 *
 * <p>Border shops take Swiss francs, and the exchange rate is the shop's own, not
 * the market's: it is administered in the back office, it moves when the manager
 * decides it moves, and the register applies whatever it was told. That is why the
 * rate lives here, on the currency, and travels with the referential.
 *
 * <p>THE RATE IS "EUROS FOR ONE UNIT". One Swiss franc is worth 1,05 euro, so the
 * rate is 1,05 and a euro amount is a currency amount multiplied by it. The
 * opposite convention — units per euro — reads more naturally on a board at the
 * counter, but it turns every conversion into a division, and a division is where
 * the rounding arguments start.
 *
 * <p>CHANGE IS ALWAYS GIVEN IN EUROS. A till holds a euro float; it does not hold a
 * Swiss float, so it cannot give Swiss change. Nothing here carries a change
 * currency because there is no decision to make.
 */
@Entity
@Table(name = "currencies")
public class Currency extends PanacheEntity {

    /** The ISO code (upsert key): CHF, USD, GBP. */
    @Column(name = "code", length = 3, unique = true, nullable = false)
    public String code;

    /** The name shown to the cashier (FRANC SUISSE). */
    @Column(name = "label", length = 40, nullable = false)
    public String label;

    /** The symbol printed beside an amount, or null to use the code. */
    @Column(name = "symbol", length = 5)
    public String symbol;

    /** How many euros one unit of this currency is worth, as the shop set it. */
    @Column(name = "euro_per_unit", precision = 12, scale = 6, nullable = false)
    public BigDecimal euroPerUnit = BigDecimal.ONE;

    /** Whether the till offers it; a currency is deactivated, never deleted. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** The order the currencies appear in on the payment screen. */
    @Column(name = "display_order", nullable = false)
    public int displayOrder = 0;

    /**
     * Lists the currencies the till offers, in administered order.
     *
     * @return the active currencies, empty when the shop takes none
     */
    public static List<Currency> listActive() {
        return list("active = true order by displayOrder, code");
    }

    /**
     * Finds an active currency by its ISO code.
     *
     * @param code the ISO code, as the screen posted it
     * @return the currency, or null when unknown or deactivated
     */
    public static Currency findActiveByCode(String code) {
        return find("code = ?1 and active = true", code).firstResult();
    }

    /**
     * Converts an amount in this currency into euros, at the administered rate.
     *
     * @param foreignAmount the amount handed over, in this currency
     * @return the euro value, rounded to the cent
     */
    public BigDecimal toEuro(BigDecimal foreignAmount) {
        if (foreignAmount == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return foreignAmount.multiply(euroPerUnit).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Converts a euro amount into this currency, for the figure shown to a customer
     * asking what the sale costs in their own money ({@code LC-07-14-03}).
     *
     * @param euroAmount the amount in euros
     * @return the amount in this currency, two decimals
     */
    public BigDecimal fromEuro(BigDecimal euroAmount) {
        if (euroAmount == null || euroPerUnit == null || euroPerUnit.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return euroAmount.divide(euroPerUnit, 2, RoundingMode.HALF_UP);
    }

    /**
     * Returns what to print beside an amount: the symbol when the shop gave one,
     * the ISO code otherwise.
     *
     * @return the display unit, never null
     */
    public String displayUnit() {
        return symbol != null && !symbol.isBlank() ? symbol : code;
    }
}
