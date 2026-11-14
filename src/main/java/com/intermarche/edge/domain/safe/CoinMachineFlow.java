package com.intermarche.edge.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One FLOW that moved a coin machine's running total ({@code BO-05-02-16}):
 * the cash a register collected or gave back, a drawer movement made in cash
 * at that register, a loading declared at the back office, a count or a
 * realignment.
 *
 * <p>The running total of {@link CoinMachine} says what the software believes
 * the machine holds; this journal says WHY, flow by flow, so the figure can be
 * explained without asking the machine — which is the whole point of the
 * requirement. A count moves nothing and is journalled all the same, since it is
 * the moment the software's belief was compared with the machine.
 */
@Entity
@Table(name = "coin_machine_flows",
        indexes = {
                @Index(name = "idx_coin_flow_machine", columnList = "machine_code, recorded_at")
        })
public class CoinMachineFlow extends PanacheEntity {

    /** What moved the machine. */
    public enum Kind {

        /** The net cash a consolidated sale left in the machine. */
        TICKET,

        /** The cash a consolidated refund paid out of the machine. */
        REFUND,

        /** A drawer movement made in cash at the machine's register. */
        MOVEMENT,

        /** A loading or an emptying declared at the back office. */
        LOAD,

        /** A count of the machine: the running total is not moved. */
        COUNT,

        /** The running total realigned on the last count. */
        REALIGN
    }

    /** The code of the machine moved. */
    @Column(name = "machine_code", nullable = false, length = 30)
    public String machineCode;

    /** What moved it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 12)
    public Kind kind;

    /** The business reference of the flow: a ticket, refund or movement number, or null. */
    @Column(name = "reference", length = 60)
    public String reference;

    /** The signed amount added to the running total, zero for a count. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    public BigDecimal amount = BigDecimal.ZERO;

    /** The running total once the flow applied. */
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    public BigDecimal balanceAfter = BigDecimal.ZERO;

    /** When the flow was recorded. */
    @Column(name = "recorded_at", nullable = false)
    public LocalDateTime recordedAt;

    /**
     * The latest flows of a machine, newest first.
     *
     * @param machineCode the machine's code
     * @param limit how many flows at most
     * @return the flows, empty when none was recorded
     */
    public static List<CoinMachineFlow> latestOf(String machineCode, int limit) {
        return CoinMachineFlow.<CoinMachineFlow>find("machineCode = ?1 order by recordedAt desc, id desc",
                machineCode).page(0, limit).list();
    }

    /**
     * The latest flows of every machine, newest first.
     *
     * @param limit how many flows at most
     * @return the flows, empty when none was recorded
     */
    public static List<CoinMachineFlow> latest(int limit) {
        return CoinMachineFlow.<CoinMachineFlow>find("order by recordedAt desc, id desc").page(0, limit).list();
    }

    /**
     * The amount formatted the way the screens show it.
     *
     * @return the signed amount, two decimals
     */
    public String getAmountFormatted() {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The running total after the flow, formatted the way the screens show it.
     *
     * @return the amount, two decimals
     */
    public String getBalanceAfterFormatted() {
        return balanceAfter.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
