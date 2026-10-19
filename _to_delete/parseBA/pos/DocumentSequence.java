package com.intermarche.pos.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

/**
 * ONE FISCAL SEQUENCE: a document type, a register, a point of sale and a year
 * (BO-03-04-19, -21, -23 and -28).
 *
 * <p>The register already numbers its tickets, its sessions and its refunds on
 * a single perpetual counter per terminal. A fiscal document is numbered
 * differently and the difference is the whole point of this entity: Portugal
 * asks for a number UNIQUE PER TYPE, PER REGISTER, PER POINT OF SALE AND PER
 * YEAR, which is four keys where the ticket counter has one, and a counter
 * that goes back to one each January where the ticket counter never does.
 *
 * <p>So this is a second counter and not a fifth column on the first: adding
 * the year to {@link TicketCounter} would have reset the ticket sequence with
 * it, and the ticket sequence is the anchor of the fiscal chain, which is
 * perpetual by law.
 *
 * <p>The row is read under a pessimistic write lock and incremented inside the
 * caller's transaction, exactly as the ticket counter is: two registers hold
 * different rows, and one register serialises its own documents.
 */
@Entity
@Table(name = "document_sequences",
        indexes = @Index(name = "idx_document_sequence",
                columnList = "document_type, terminal_id, pdv_number, sequence_year",
                unique = true)
)
public class DocumentSequence extends PanacheEntity {

    /** The document type this sequence numbers, as its enum name. */
    @Column(name = "document_type", nullable = false, length = 30)
    public String documentType;

    /** The register the sequence belongs to. */
    @Column(name = "terminal_id", nullable = false, length = 20)
    public String terminalId;

    /** The point of sale the register belongs to. */
    @Column(name = "pdv_number", nullable = false, length = 10)
    public String pdvNumber;

    /** The year the sequence counts in; a new year is a new row at one. */
    @Column(name = "sequence_year", nullable = false)
    public int sequenceYear;

    /** The last number delivered in that year. */
    @Column(name = "last_number", nullable = false)
    public long lastNumber;

    /**
     * Reserves the next number of one sequence, creating the sequence when the
     * year, the register, the point of sale or the type is new.
     *
     * <p>THE YEAR IS PART OF THE KEY AND NOT A FIELD TO COMPARE. A new year
     * opens a new row starting at one and leaves last year's row untouched,
     * which is what an administration asking for the documents of a past year
     * comes back for.
     *
     * @param documentType the document type's enum name
     * @param terminalId the register
     * @param pdvNumber the point of sale
     * @param year the year the document is issued in
     * @return the reserved number, starting at one
     */
    public static long next(String documentType, String terminalId, String pdvNumber, int year) {
        DocumentSequence sequence = find(
                "documentType = ?1 and terminalId = ?2 and pdvNumber = ?3 and sequenceYear = ?4",
                documentType, terminalId, pdvNumber, year)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (sequence == null) {
            sequence = new DocumentSequence();
            sequence.documentType = documentType;
            sequence.terminalId = terminalId;
            sequence.pdvNumber = pdvNumber;
            sequence.sequenceYear = year;
            sequence.lastNumber = 0;
            sequence.persist();
        }
        sequence.lastNumber++;
        return sequence.lastNumber;
    }
}
