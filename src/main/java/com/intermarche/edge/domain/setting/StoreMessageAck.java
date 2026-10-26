package com.intermarche.edge.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ONE CASHIER'S CONFIRMATION of one message (BO-03-08-04).
 *
 * <p>The questionnaire asks for the confirmation to be stored "sous la forme
 * n° caissière, nom-prénom caissière, date de confirmation", and that
 * sentence is this table: who, under which name, when, and — when the message
 * carried a question — what they answered.
 *
 * <p>Stored and not counted. A store that sends a safety notice to forty
 * registers needs to know WHICH cashier has read it, not how many have: the
 * one who has not is the point of asking.
 */
@Entity
@Table(name = "store_message_acks",
        indexes = @Index(name = "idx_store_message_ack", columnList = "message_id, terminal_id"))
public class StoreMessageAck extends PanacheEntity {

    /** The message confirmed. */
    @Column(name = "message_id", nullable = false)
    public Long messageId;

    /** The register the confirmation came from. */
    @Column(name = "terminal_id", nullable = false, length = 20)
    public String terminalId;

    /** The cashier's number, as the register knows it. */
    @Column(name = "operator_code", length = 20)
    public String operatorCode;

    /** The cashier's name, as the register showed it. */
    @Column(name = "operator_name", length = 100)
    public String operatorName;

    /** The answer given, or null when the message asked no question. */
    @Column(name = "answer", length = 200)
    public String answer;

    /**
     * The ticket the answer was given on (BO-06-03-02), as the register named
     * it, or null for a notice acknowledged or an older register.
     */
    @Column(name = "ticket_number", length = 30)
    public String ticketNumber;

    /** When the cashier confirmed. */
    @Column(name = "confirmed_at", nullable = false)
    public LocalDateTime confirmedAt = LocalDateTime.now();

    /**
     * The confirmations of one message, newest first.
     *
     * @param messageId the message
     * @return its confirmations, newest first
     */
    public static List<StoreMessageAck> listFor(Long messageId) {
        return list("messageId = ?1 order by confirmedAt desc", messageId);
    }

    /**
     * How many registers have confirmed one message.
     *
     * @param messageId the message
     * @return the count of confirmations
     */
    public static long countFor(Long messageId) {
        return count("messageId", messageId);
    }
}
