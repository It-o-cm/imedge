package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * When each register last spoke to this node ({@code BO-04-03-05}).
 *
 * <p>NOTHING NEW TRAVELS FOR THIS. Every register already asks this node for
 * its orders every twenty seconds, and that call names the register: the
 * node had the information and was throwing it away. So presence is not a
 * heartbeat anybody had to build — it is a timestamp written on a call that
 * was already being made, which is why a lane that has stopped speaking is
 * noticed within the poll interval and not within a protocol.
 *
 * <p>TECHNICAL AND NOT ADMINISTERED, hence {@link PanacheEntity} and not the
 * audited base entity: the row is rewritten three times a minute per lane
 * and carries no business decision. A node restarted loses nothing that
 * matters — the first poll of each register writes it again.
 */
@Entity
@Table(name = "register_presence",
        indexes = {
                @Index(name = "idx_register_presence_terminal", columnList = "terminal_id")
        }
)
public class RegisterPresence extends PanacheEntity {

    /** The register's identifier, unique: one row per lane. */
    @Column(name = "terminal_id", nullable = false, unique = true, length = 40)
    public String terminalId;

    /** When that register last spoke to this node. */
    @Column(name = "last_seen_at", nullable = false)
    public LocalDateTime lastSeenAt;

    /**
     * Finds the row of a register.
     *
     * @param terminalId the register's identifier, possibly null
     * @return the row, or null when that register has never spoken
     */
    public static RegisterPresence findByTerminal(String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return null;
        }
        return find("terminalId", terminalId.trim()).firstResult();
    }

    /**
     * Every register that has ever spoken, the longest silent first.
     *
     * @return the rows, possibly empty
     */
    public static List<RegisterPresence> allByAge() {
        return list("order by lastSeenAt");
    }
}
