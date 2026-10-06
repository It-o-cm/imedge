package com.intermarche.edge.ui.financial;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.FinancialState;
import com.intermarche.edge.domain.session.FinancialState.PeriodKind;
import com.intermarche.edge.domain.session.FinancialState.StateScope;
import com.intermarche.edge.domain.session.FinancialState.StateType;
import com.intermarche.edge.domain.session.FinancialStateLine;
import com.intermarche.edge.domain.session.FinancialStateLine.StateSection;
import com.intermarche.edge.domain.util.DateTimeProvider;
import com.intermarche.edge.service.FinancialStateEmissionService;
import com.intermarche.edge.ui.report.ReportColumn;
import com.intermarche.edge.ui.report.ReportData;
import com.intermarche.edge.ui.report.ReportDefinition;
import com.intermarche.edge.ui.report.ReportFamily;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The screen-facing service of the financial states (§6): it lists the emitted Z,
 * opens one, emits a Z or reads an X on demand, and turns a state's section into a
 * {@link ReportData} the existing writers export. It lives in {@code ui.*} because
 * it serves a screen; the pure totals are the calculator's, the chaining the
 * emission service's — this one only orchestrates what the back office asks.
 */
@ApplicationScoped
public class FinancialStateService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FinancialStateService.class);

    /** The emission and reading service (chaining, idempotence, control). */
    @Inject FinancialStateEmissionService emissionService;

    /**
     * Lists the emitted Z documents, newest first.
     *
     * @return the Z documents
     */
    public List<FinancialState> listEmitted() {
        LOGGER.info("Entering method listEmitted");
        List<FinancialState> list = FinancialState.list(
                "stateType = ?1 order by emittedAt desc", StateType.Z);
        LOGGER.info("Exiting method listEmitted with " + list.size() + " document(s)");
        return list;
    }

    /**
     * Opens one financial state by its database id.
     *
     * @param id the state id
     * @return the state, or null when it does not exist
     */
    public FinancialState byId(Long id) {
        LOGGER.info("Entering method byId with id: " + id);
        FinancialState state = FinancialState.findById(id);
        LOGGER.info("Exiting method byId");
        return state;
    }

    /**
     * Emits a Z manually from the screen (§4): the emission service does the
     * chaining and the idempotence, the bound being the natural end of the current
     * period so a second click reprints rather than doubling the document.
     *
     * @param scope the counter holder
     * @param periodKind the period bound
     * @param cashier the cashier of a cashier-scope Z, or null
     * @param emittedBy the operator who clicked
     * @return the emitted or reprinted Z
     */
    @Transactional
    public FinancialState emitManual(StateScope scope, PeriodKind periodKind, Employee cashier, Employee emittedBy) {
        LOGGER.info("Entering method emitManual");
        FinancialState state = emissionService.emitZ(scope, periodKind, cashier,
                periodBound(periodKind, DateTimeProvider.now().toLocalDate()), false, emittedBy);
        LOGGER.info("Exiting method emitManual");
        return state;
    }

    /**
     * Reads a throw-away X on the open interval (§1).
     *
     * @param scope the counter holder
     * @param periodKind the period bound
     * @param cashier the cashier of a cashier-scope X, or null
     * @return the computed X
     */
    public FinancialState readX(StateScope scope, PeriodKind periodKind, Employee cashier) {
        LOGGER.info("Entering method readX");
        FinancialState state = emissionService.readX(scope, periodKind, cashier);
        LOGGER.info("Exiting method readX");
        return state;
    }

    /**
     * The natural end bound of the period a day belongs to: the next midnight for a
     * day, the next Monday for a week, the first of next month for a month.
     *
     * @param periodKind the period
     * @param day the day the manual emission is triggered on
     * @return the exclusive upper bound of the current period
     */
    LocalDateTime periodBound(PeriodKind periodKind, LocalDate day) {
        if (periodKind == PeriodKind.WEEK) {
            return day.with(DayOfWeek.MONDAY).plusDays(7).atStartOfDay();
        }
        if (periodKind == PeriodKind.MONTH) {
            return day.withDayOfMonth(1).plusMonths(1).atStartOfDay();
        }
        return day.plusDays(1).atStartOfDay();
    }

    /**
     * Whether a state carries an anomaly worth flagging on the list (§4): a
     * non-zero control variance or a late ticket swept into it.
     *
     * @param state the state
     * @return true when the state should be flagged
     */
    public boolean hasAnomaly(FinancialState state) {
        boolean variance = state.controlVariance != null && state.controlVariance.signum() != 0;
        return variance || state.lateTicketCount > 0;
    }

    /**
     * Turns one section of a state into a {@link ReportData} the existing writers
     * export (§6): no new writer, a report per section.
     *
     * @param state the state
     * @param section the section to export
     * @return the executed report of that section
     */
    public ReportData export(FinancialState state, StateSection section) {
        LOGGER.info("Entering method export with section: " + section);
        List<List<Object>> rows = new ArrayList<>();
        for (FinancialStateLine line : state.lines) {
            if (line.section != section) {
                continue;
            }
            rows.add(row(line));
        }
        ReportDefinition definition = new SectionReportDefinition(
                "financial-" + state.id + "-" + section.name().toLowerCase(),
                state.stateType + " " + section.name(), COLUMNS);
        LOGGER.info("Exiting method export with " + rows.size() + " row(s)");
        return new ReportData(definition, rows);
    }

    /**
     * Maps a line to the generic export row.
     *
     * @param line the line
     * @return the cells, one per column
     */
    private List<Object> row(FinancialStateLine line) {
        List<Object> cells = new ArrayList<>();
        cells.add(line.code);
        cells.add(line.label);
        cells.add(line.quantity == null ? null : line.quantity.toPlainString());
        cells.add(line.amountExcludingTax);
        cells.add(line.vatAmount);
        cells.add(line.amount);
        cells.add(line.share);
        return cells;
    }

    /** The generic export columns shared by every section. */
    static final List<ReportColumn> COLUMNS = List.of(
            ReportColumn.text("code", "Code"),
            ReportColumn.text("label", "Libellé"),
            ReportColumn.text("quantity", "Quantité"),
            ReportColumn.amount("ht", "Montant HT"),
            ReportColumn.amount("vat", "TVA"),
            ReportColumn.amount("amount", "Montant TTC"),
            ReportColumn.percent("share", "Part"));

    /**
     * A throw-away {@link ReportDefinition} for one section of a state, so the
     * section travels to the existing writers without a bean of its own.
     */
    static final class SectionReportDefinition implements ReportDefinition {

        /** The report code, carried into the file name. */
        private final String code;

        /** The report title, printed at the top of the export. */
        private final String label;

        /** The columns. */
        private final List<ReportColumn> columns;

        /**
         * Builds a section report.
         *
         * @param code the report code
         * @param label the report title
         * @param columns the columns
         */
        SectionReportDefinition(String code, String label, List<ReportColumn> columns) {
            this.code = code;
            this.label = label;
            this.columns = columns;
        }

        /**
         * {@inheritDoc}
         *
         * @return the report code
         */
        @Override
        public String code() {
            return code;
        }

        /**
         * {@inheritDoc}
         *
         * @return the report title
         */
        @Override
        public String label() {
            return label;
        }

        /**
         * {@inheritDoc}
         *
         * @return the requirement, the X/Z campaign
         */
        @Override
        public String requirement() {
            return "X/Z";
        }

        /**
         * {@inheritDoc}
         *
         * @return the cashiers family, closest to a financial state
         */
        @Override
        public ReportFamily family() {
            return ReportFamily.CASHIERS;
        }

        /**
         * {@inheritDoc}
         *
         * @return the columns
         */
        @Override
        public List<ReportColumn> columns() {
            return columns;
        }

        /**
         * {@inheritDoc}
         *
         * <p>The rows are already computed from the frozen state; the criteria are
         * ignored, a financial state never recomputing.
         *
         * @param criteria the edition parameters, ignored
         * @return an empty list; the rows are supplied to {@link ReportData} directly
         */
        @Override
        public List<List<Object>> rows(com.intermarche.edge.ui.report.ReportCriteria criteria) {
            return List.of();
        }
    }
}
