package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.barcode.StoredValueRecord;
import com.intermarche.edge.domain.payment.DepositPayment;
import com.intermarche.edge.domain.payment.VoucherPayment;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.session.CashMovement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ÉTAT ACOMPTES / AVOIRS / COUPONS / BONS (BO-06-06-06): ONE list of the
 * instruments the shop issues and takes back — the credit notes and gift cards
 * it prints (avoirs, bons d'achat), the down-payments it cashes (acomptes) —
 * each with its barcode and label, the cashier and the ticket that ISSUED it,
 * its amount and date of issue, and the cashier and the date of each USE.
 *
 * <p>An instrument used several times is listed once per use, its issue
 * repeated; one never used is listed once, its use empty. The option says
 * which to edit: the instruments issued over the period, the ones used over it
 * — « seuls les avoirs utilisés sont suivis » — or both. The registers kept are
 * the ones the period, the registers chosen and the checkout island keep: the
 * issuing register for an issue, the using one for a use.
 *
 * <p>An instrument is matched to its uses by its NUMBER, which the paying
 * ticket records on its voucher settlement; a down-payment, by the order
 * reference the final ticket records on its deposit settlement. The issuing
 * cashier is the one of the sale or the refund that printed the instrument,
 * found by the document number the instrument carries.
 */
@ApplicationScoped
public class StoredValueReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(StoredValueReport.class);

    /** The option value editing the instruments issued. */
    static final String ISSUED = "ISSUED";

    /** The option value editing the instruments used. */
    static final String USED = "USED";

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "stored-values";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État acomptes / avoirs / coupons / bons";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-06";
    }

    /**
     * {@inheritDoc}
     *
     * @return the cashiers family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.CASHIERS;
    }

    /**
     * {@inheritDoc}
     *
     * @return the issue then the use of each instrument
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("type", "Type"),
                ReportColumn.text("barcode", "Code-barre"),
                ReportColumn.text("label", "Libellé"),
                ReportColumn.text("issuer", "Caissière (émission)"),
                ReportColumn.text("issuingTicket", "N° ticket (émission)"),
                ReportColumn.amount("amount", "Montant"),
                ReportColumn.dateTime("issuedAt", "Date d'émission"),
                ReportColumn.text("user", "Caissière (utilisation)"),
                ReportColumn.text("usingTicket", "N° ticket (utilisation)"),
                ReportColumn.dateTime("usedAt", "Date d'utilisation"),
                ReportColumn.amount("used", "Montant utilisé"));
    }

    /**
     * {@inheritDoc}
     *
     * @return what the option is called on the screen
     */
    @Override
    public String optionLabel() {
        return "Instruments édités";
    }

    /**
     * {@inheritDoc}
     *
     * @return issued, used, both
     */
    @Override
    public List<String> optionChoices() {
        return List.of("ALL:Émis ou utilisés sur la période", ISSUED + ":Émis sur la période",
                USED + ":Utilisés sur la période");
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers and the island
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND);
    }

    /** One issue of an instrument. */
    record Issue(String type, String barcode, String label, String issuer, String ticket, BigDecimal amount,
                 LocalDateTime issuedAt, String terminal) {
    }

    /** One use of an instrument. */
    record Use(String user, String ticket, LocalDateTime usedAt, BigDecimal amount, String terminal) {
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per use, or per unused instrument, by date of issue
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        String option = criteria.optionOr("ALL");
        Map<String, List<Use>> voucherUses = uses(criteria, VoucherPayment.class, "p.voucherNumber");
        Map<String, List<Use>> depositUses = uses(criteria, DepositPayment.class, "p.orderCode");
        Map<String, Issue> issues = new LinkedHashMap<>();
        for (StoredValueRecord record : storedValues(criteria, voucherUses.keySet())) {
            String[] issuer = issuer(record.issuingDocumentNumber);
            String type = "CREDIT_NOTE".equals(record.kind) ? "Avoir" : "Carte cadeau / bon d'achat";
            issues.put("V" + record.number, new Issue(type, record.number, type + " " + record.number,
                    issuer[0], issuer[1], record.initialAmount, record.issuedAt, record.issuingTerminalId));
        }
        for (CashMovement deposit : deposits(criteria, depositUses.keySet())) {
            String reference = deposit.orderReference == null ? "" : deposit.orderReference;
            issues.put("D" + reference, new Issue("Acompte", reference, "Acompte commande " + reference,
                    deposit.cashier == null ? "" : deposit.cashier.badgeId, "", deposit.amount,
                    deposit.movementDate, deposit.terminalId));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Issue> entry : issues.entrySet()) {
            Issue issue = entry.getValue();
            boolean issuedHere = inPeriod(criteria, issue.issuedAt()) && criteria.keepsTerminal(issue.terminal());
            List<Use> uses = new ArrayList<>((entry.getKey().startsWith("V") ? voucherUses : depositUses)
                    .getOrDefault(issue.barcode(), List.of()));
            uses.removeIf(use -> !criteria.keepsTerminal(use.terminal()));
            if (ISSUED.equals(option) && !issuedHere || USED.equals(option) && uses.isEmpty()
                    || !issuedHere && uses.isEmpty()) {
                continue;
            }
            if (uses.isEmpty()) {
                rows.add(row(issue, null));
            }
            for (Use use : uses) {
                rows.add(row(issue, use));
            }
        }
        rows.sort(Comparator.comparing(row -> row.get(6) == null ? LocalDateTime.MIN : (LocalDateTime) row.get(6)));
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Builds the row of an issue and one of its uses.
     *
     * @param issue the issue
     * @param use the use, or null for an instrument never used
     * @return the row
     */
    private List<Object> row(Issue issue, Use use) {
        return Arrays.asList(issue.type(), issue.barcode(), issue.label(), issue.issuer(), issue.ticket(),
                issue.amount(), issue.issuedAt(),
                use == null ? "" : use.user(), use == null ? "" : use.ticket(),
                use == null ? null : use.usedAt(), use == null ? null : use.amount());
    }

    /**
     * Whether a moment falls in the period.
     *
     * @param criteria the edition parameters
     * @param when the moment, possibly null
     * @return true inside the half-open period
     */
    private static boolean inPeriod(ReportCriteria criteria, LocalDateTime when) {
        return when != null && !when.isBefore(criteria.from()) && when.isBefore(criteria.to());
    }

    /**
     * Reads the uses over the period of one kind of settlement.
     *
     * @param criteria the edition parameters
     * @param type the settlement class
     * @param reference the settlement's reference column
     * @return the uses, by instrument reference
     */
    private Map<String, List<Use>> uses(ReportCriteria criteria, Class<?> type, String reference) {
        var query = entityManager.createQuery(
                        "select " + reference + ", t.ticketNumber, t.closingDate, t.cashier.badgeId, p.amount,"
                                + " t.terminalId from Ticket t join t.payments p where type(p) = :type"
                                + " and t.status = :closed and t.closingDate >= :from and t.closingDate < :to"
                                + " and " + reference + " is not null order by t.closingDate", Object[].class)
                .setParameter("type", type)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        Map<String, List<Use>> uses = new HashMap<>();
        for (Object[] row : query.getResultList()) {
            uses.computeIfAbsent(String.valueOf(row[0]), key -> new ArrayList<>()).add(new Use(
                    row[3] == null ? "" : String.valueOf(row[3]), String.valueOf(row[1]),
                    (LocalDateTime) row[2], (BigDecimal) row[4], (String) row[5]));
        }
        return uses;
    }

    /**
     * Reads the credit notes and gift cards issued over the period or used in it.
     *
     * @param criteria the edition parameters
     * @param used the numbers used over the period
     * @return the instruments, by date of issue
     */
    private List<StoredValueRecord> storedValues(ReportCriteria criteria, Set<String> used) {
        var query = entityManager.createQuery(
                        "select r from StoredValueRecord r where (r.issuedAt >= :from and r.issuedAt < :to)"
                                + (used.isEmpty() ? "" : " or r.number in :used") + " order by r.issuedAt",
                        StoredValueRecord.class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (!used.isEmpty()) {
            query.setParameter("used", used);
        }
        return query.getResultList();
    }

    /**
     * Reads the down-payments cashed over the period or used in it.
     *
     * @param criteria the edition parameters
     * @param used the order references used over the period
     * @return the down-payments
     */
    private List<CashMovement> deposits(ReportCriteria criteria, Set<String> used) {
        var query = entityManager.createQuery(
                        "select m from CashMovement m where m.type = :deposit and ((m.movementDate >= :from"
                                + " and m.movementDate < :to)" + (used.isEmpty() ? "" : " or m.orderReference in :used")
                                + ") order by m.movementDate", CashMovement.class)
                .setParameter("deposit", CashMovement.MovementType.CUSTOMER_DEPOSIT)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (!used.isEmpty()) {
            query.setParameter("used", used);
        }
        return query.getResultList();
    }

    /**
     * Finds who issued an instrument: the cashier of the sale or the refund
     * whose number it carries.
     *
     * @param document the issuing document's number, possibly null
     * @return the cashier's badge and the document number, empty when unknown
     */
    private String[] issuer(String document) {
        if (document == null) {
            return new String[]{"", ""};
        }
        List<String> sale = entityManager.createQuery(
                        "select t.cashier.badgeId from Ticket t where t.ticketNumber = :number", String.class)
                .setParameter("number", document).getResultList();
        if (!sale.isEmpty()) {
            return new String[]{sale.get(0) == null ? "" : sale.get(0), document};
        }
        List<String> refund = entityManager.createQuery(
                        "select r.cashierBadgeId from Refund r where r.refundNumber = :number", String.class)
                .setParameter("number", document).getResultList();
        return new String[]{refund.isEmpty() || refund.get(0) == null ? "" : refund.get(0), document};
    }
}
