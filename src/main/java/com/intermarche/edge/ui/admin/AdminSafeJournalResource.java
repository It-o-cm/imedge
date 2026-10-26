package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.safe.AccountingPeriod;
import com.intermarche.edge.domain.safe.SafeMovement;
import com.intermarche.edge.service.SafeService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * The SAFE JOURNAL ({@code /admin/safe/journal}, {@code BO-05-02-13}): the
 * debits and the credits of the safe over a stretch of days, with the balance
 * they lead to.
 *
 * <p>The report the requirement names — the {@code journal du coffre} the shops
 * print today — and not a list of drawer movements: what it states is the
 * SAFE's debits and credits, and the running balance is carried line by line so
 * a reader can see where a figure came from rather than being handed a total.
 *
 * <p>Read-only by construction: it holds no POST at all. A journal that could be
 * corrected from the journal screen would not be one.
 */
@Path("/admin/safe/journal")
public class AdminSafeJournalResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminSafeJournalResource.class);

    /** The journal template. */
    @Inject
    @Location("admin-safe-journal")
    Template adminSafeJournal;

    /** The safe: its balance and its ledger. */
    @Inject
    SafeService safeService;

    /**
     * One printed line of the journal: a movement, its debit or credit column,
     * and the balance the safe stands at once it is counted.
     */
    public static class JournalRow {

        /** The movement stated. */
        public final SafeMovement movement;

        /** The amount in the debit column, empty when the movement is a credit. */
        public final String debit;

        /** The amount in the credit column, empty when the movement is a debit. */
        public final String credit;

        /** The balance the safe stands at once this movement is counted. */
        public final String balance;

        /**
         * Builds a printed line.
         *
         * @param movement the movement stated
         * @param debit the debit column, empty on a credit
         * @param credit the credit column, empty on a debit
         * @param balance the running balance
         */
        public JournalRow(SafeMovement movement, String debit, String credit, String balance) {
            this.movement = movement;
            this.debit = debit;
            this.credit = credit;
            this.balance = balance;
        }

        /**
         * Returns the movement stated, for a template.
         *
         * @return the movement
         */
        public SafeMovement getMovement() {
            return movement;
        }

        /**
         * Returns the debit column, for a template.
         *
         * @return the debit, empty on a credit
         */
        public String getDebit() {
            return debit;
        }

        /**
         * Returns the credit column, for a template.
         *
         * @return the credit, empty on a debit
         */
        public String getCredit() {
            return credit;
        }

        /**
         * Returns the running balance, for a template.
         *
         * @return the balance after this movement
         */
        public String getBalance() {
            return balance;
        }
    }

    /**
     * Shows the journal of a stretch of days ({@code BO-05-02-13}).
     *
     * @param from the first day, blank meaning the start of the open period
     * @param to the last day, blank meaning today
     * @return the journal page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_JOURNAL)
    public TemplateInstance journal(@QueryParam("from") String from,
                                    @QueryParam("to") String to) {
        LOGGER.info("Entering method journal with from: " + from + ", to: " + to);
        LocalDate first = firstDay(from);
        LocalDate last = AdminForms.day(to) == null ? LocalDate.now() : AdminForms.day(to);
        List<SafeMovement> movements = safeService.journal(first, last);
        BigDecimal opening = openingOf(first);
        LOGGER.info("Exiting method journal with " + movements.size() + " movement(s)");
        return adminSafeJournal.data("rows", rows(movements, opening))
                .data("from", first.toString())
                .data("to", last.toString())
                .data("opening", format(opening))
                .data("closing", format(opening.add(total(movements))))
                .data("totalDebit", format(columnTotal(movements, false)))
                .data("totalCredit", format(columnTotal(movements, true)));
    }

    /**
     * Extracts the same journal as CSV ({@code BO-05-02-13}).
     *
     * @param from the first day, blank meaning the start of the open period
     * @param to the last day, blank meaning today
     * @return the CSV file, named after the days it covers
     */
    @GET
    @Path("/export")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_JOURNAL)
    @Produces("text/csv; charset=UTF-8")
    public Response export(@QueryParam("from") String from, @QueryParam("to") String to) {
        LOGGER.info("Entering method export");
        LocalDate first = firstDay(from);
        LocalDate last = AdminForms.day(to) == null ? LocalDate.now() : AdminForms.day(to);
        List<SafeMovement> movements = safeService.journal(first, last);
        StringBuilder csv = new StringBuilder("DATE;NATURE;LIBELLE;REFERENCE;DEBIT;CREDIT;SOLDE;ETAT\n");
        BigDecimal running = openingOf(first);
        for (SafeMovement movement : movements) {
            running = running.add(movement.signedAmount());
            csv.append(cell(movement.movementDate == null ? null : movement.movementDate.toString()))
                    .append(cell(movement.getTypeLabel()))
                    .append(cell(movement.label))
                    .append(cell(movement.reference))
                    .append(cell(column(movement, false)))
                    .append(cell(column(movement, true)))
                    .append(cell(format(running)))
                    .append(movement.isValidated() ? "Valide" : "A pointer")
                    .append('\n');
        }
        LOGGER.info("Exiting method export");
        return Response.ok(csv.toString())
                .header("Content-Disposition",
                        "attachment; filename=\"journal-coffre-" + first + "-" + last + ".csv\"")
                .build();
    }

    /**
     * Builds the printed lines, carrying the running balance.
     *
     * @param movements the movements of the stretch, oldest first
     * @param opening the balance the safe stood at before the first of them
     * @return the printed lines
     */
    List<JournalRow> rows(List<SafeMovement> movements, BigDecimal opening) {
        List<JournalRow> rows = new java.util.ArrayList<>();
        BigDecimal running = opening;
        for (SafeMovement movement : movements) {
            running = running.add(movement.signedAmount());
            rows.add(new JournalRow(movement, column(movement, false), column(movement, true),
                    format(running)));
        }
        return rows;
    }

    /**
     * The first day of the journal: the one asked for, or the first day of the
     * open period, or the epoch when the safe has never been opened.
     *
     * @param from the day asked for, possibly blank or unreadable
     * @return the first day, never null
     */
    private LocalDate firstDay(String from) {
        LocalDate asked = AdminForms.day(from);
        if (asked != null) {
            return asked;
        }
        AccountingPeriod period = safeService.openPeriod();
        return period == null || period.startDate == null
                ? LocalDate.of(1970, 1, 1) : period.startDate;
    }

    /**
     * The balance the safe stood at before a day: everything that happened
     * strictly before it.
     *
     * @param day the first day of the journal
     * @return the opening balance, never null
     */
    private BigDecimal openingOf(LocalDate day) {
        BigDecimal opening = BigDecimal.ZERO;
        for (SafeMovement movement : safeService.journal(LocalDate.of(1970, 1, 1),
                day.minusDays(1))) {
            opening = opening.add(movement.signedAmount());
        }
        return opening;
    }

    /**
     * The sum of a list of movements, signed.
     *
     * @param movements the movements
     * @return the sum, never null
     */
    private BigDecimal total(List<SafeMovement> movements) {
        BigDecimal total = BigDecimal.ZERO;
        for (SafeMovement movement : movements) {
            total = total.add(movement.signedAmount());
        }
        return total;
    }

    /**
     * The total of one column.
     *
     * @param movements the movements
     * @param credits true for the credit column, false for the debit one
     * @return the total, never null
     */
    private BigDecimal columnTotal(List<SafeMovement> movements, boolean credits) {
        BigDecimal total = BigDecimal.ZERO;
        for (SafeMovement movement : movements) {
            if (movement.type != null && movement.type.isCredit() == credits) {
                total = total.add(movement.amount == null ? BigDecimal.ZERO : movement.amount);
            }
        }
        return total;
    }

    /**
     * The amount a movement writes in one column, empty when it writes in the
     * other.
     *
     * @param movement the movement
     * @param credits true for the credit column, false for the debit one
     * @return the amount, or an empty string
     */
    private String column(SafeMovement movement, boolean credits) {
        if (movement.type == null || movement.type.isCredit() != credits) {
            return "";
        }
        return format(movement.amount);
    }

    /**
     * Formats an amount the way the journal prints it.
     *
     * @param amount the amount, possibly null
     * @return the amount, two decimals
     */
    private String format(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount)
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Renders one CSV cell, the separator neutralized.
     *
     * @param value the value, possibly null
     * @return the cell, followed by its separator
     */
    private String cell(String value) {
        return (value == null ? "" : value.replace(';', ',')) + ";";
    }
}
