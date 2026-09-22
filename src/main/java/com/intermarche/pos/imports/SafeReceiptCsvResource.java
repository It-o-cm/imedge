package com.intermarche.pos.imports;

import com.intermarche.pos.domain.safe.AccountingPeriod;
import com.intermarche.pos.domain.safe.SafeMovement;
import com.intermarche.pos.domain.safe.SafeMovementType;
import com.intermarche.pos.domain.safe.SafeVatLine;
import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Integrates the RECEIPTS collected outside the registers ({@code BO-05-02-03}).
 *
 * <p>The requirement names the cases: the fuel station, the gas supplier, the
 * drinks machine — takings a shop receives without a till ever seeing them, and
 * which three feeds already produce today. A file rather than a screen, because
 * that is what those feeds emit and because typing a month of station takings by
 * hand is how a shop stops doing it.
 *
 * <p>UPSERT ON THE REFERENCE. A feed that resends yesterday's file — and they do
 * — must not credit the safe twice: the reference is the row's identity, so the
 * second pass corrects the first instead of adding to it. That is the ONE reason
 * this import keys on a reference rather than generating a movement per line.
 *
 * <p>The movement lands PENDING like any other, so an integrated receipt is
 * pointed and validated with the rest of the period rather than slipping into a
 * closure nobody checked.
 *
 * <p>Consumed columns, resolved by header name: REFERENCE (key), LABEL, AMOUNT,
 * DATE, and the optional VAT_RATE / VAT_BASE / VAT_AMOUNT triplet.
 */
// No @Path: the ONE door is POST /feeds/import/{CODE}, which delegates here.
// A second address for the same import is a second thing to secure, to
// document and to keep in step, and the permission policy had to name them
// one by one — an importer forgotten there was a door left open.
@ApplicationScoped
@RunOnVirtualThread
public class SafeReceiptCsvResource extends ImporterCsvResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SafeReceiptCsvResource.class);

    /** Header name of the feed's own reference, the upsert key. */
    static final String COL_REFERENCE = "REFERENCE";
    /** Header name of the wording. */
    static final String COL_LABEL = "LABEL";
    /** Header name of the amount received. */
    static final String COL_AMOUNT = "AMOUNT";
    /** Header name of the day the takings count for. */
    static final String COL_DATE = "DATE";
    /** Header name of the optional VAT rate. */
    static final String COL_VAT_RATE = "VAT_RATE";
    /** Header name of the optional taxable base. */
    static final String COL_VAT_BASE = "VAT_BASE";
    /** Header name of the optional VAT amount. */
    static final String COL_VAT_AMOUNT = "VAT_AMOUNT";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(COL_AMOUNT);

    /**
     * Integrates the receipts carried by a CSV stream.
     *
     * @param inputStream the stream carrying the CSV
     * @return a JSON summary of the created and updated counts and the errors
     */
    public Response importReceipts(InputStream inputStream) {
        LOGGER.info("Entering method importReceipts");
        LOGGER.info("Exiting method importReceipts");
        return this.importCsvStream(inputStream, COL_REFERENCE, REQUIRED_COLUMNS);
    }

    /**
     * Bulk-fetches the movements the chunk's references already name.
     *
     * @param parsedLines the rows of the current chunk
     * @param targetCodes the references of the current chunk
     * @param counters the created and updated counters
     * @param errors the collected error messages
     * @return the reference-to-movement map the generic algorithm works on
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines,
                                                           Set<String> targetCodes,
                                                           int[] counters, List<String> errors) {
        Map<String, Object> rows = new HashMap<>();
        if (parsedLines.isEmpty()) {
            return rows;
        }
        for (SafeMovement movement : SafeMovement.<SafeMovement>list(
                "type = ?1 and reference IN ?2", SafeMovementType.AUTOMATIC_RECEIPT, targetCodes)) {
            rows.put(movement.reference, movement);
        }
        return rows;
    }

    /**
     * Creates or corrects one integrated receipt.
     *
     * <p>A movement whose period has been CLOSED is left alone: the period is
     * frozen, and a feed resending an old file must not reopen it behind the
     * closure's back. The line is counted as neither created nor updated, which
     * is what it is — nothing happened.
     *
     * @param data the parsed row
     * @param entityMap the movements already integrated, by reference
     * @param counters the created and updated counters
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        BigDecimal amount = decimal(safeGet(data, COL_AMOUNT));
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        SafeMovement movement = (SafeMovement) entityMap.get(data.code);
        if (movement == null) {
            AccountingPeriod period = AccountingPeriod.open();
            if (period == null) {
                return;
            }
            movement = new SafeMovement();
            movement.type = SafeMovementType.AUTOMATIC_RECEIPT;
            movement.reference = data.code;
            movement.period = period;
            movement.enteredAt = LocalDateTime.now();
            movement.status = SafeMovement.MovementStatus.PENDING;
            apply(movement, data, amount);
            counters[0]++;
            Panache.getEntityManager().persist(movement);
            return;
        }
        movement = SafeMovement.findById(movement.id);
        if (!movement.isCorrectable()) {
            return;
        }
        apply(movement, data, amount);
        counters[1]++;
    }

    /**
     * Finds one movement afresh, for the one-by-one fallback of the base class.
     *
     * @param data the parsed row
     * @return the movement, or null when that reference is unknown
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return SafeMovement.find("type = ?1 and reference = ?2",
                SafeMovementType.AUTOMATIC_RECEIPT, data.code).firstResult();
    }

    /**
     * Writes a parsed row onto a movement.
     *
     * @param movement the movement written
     * @param data the parsed row
     * @param amount the amount already read and checked
     */
    private void apply(SafeMovement movement, LineData data, BigDecimal amount) {
        movement.amount = amount;
        String label = safeGet(data, COL_LABEL);
        movement.label = label == null || label.isBlank()
                ? "Recette intégrée " + data.code : label;
        LocalDate day = date(safeGet(data, COL_DATE));
        movement.movementDate = day == null ? LocalDate.now() : day;
        movement.vatLines = vatLinesOf(data);
    }

    /**
     * Reads the VAT triplet of a row, when it carries one.
     *
     * @param data the parsed row
     * @return the lines stated, never null, possibly empty
     */
    List<SafeVatLine> vatLinesOf(LineData data) {
        List<SafeVatLine> lines = new ArrayList<>();
        SafeVatLine line = new SafeVatLine(decimal(safeGet(data, COL_VAT_RATE)),
                decimal(safeGet(data, COL_VAT_BASE)), decimal(safeGet(data, COL_VAT_AMOUNT)));
        if (line.isStated()) {
            lines.add(line);
        }
        return lines;
    }

    /**
     * Reads an amount written by a feed, the decimal comma tolerated.
     *
     * @param raw the value, possibly null or unreadable
     * @return the amount, or null
     */
    static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim().replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Reads an ISO day written by a feed.
     *
     * @param raw the value, possibly null or unreadable
     * @return the day, or null
     */
    static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
