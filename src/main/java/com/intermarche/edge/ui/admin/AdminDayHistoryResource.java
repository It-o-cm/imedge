package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.service.CashSessionService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The DAY HISTORY screen ({@code /admin/safe/days}): the takings of an
 * accounting day broken down by tender ({@code BO-05-02-08}) and the validation
 * of each cashier's movements ({@code BO-05-02-09}).
 *
 * <p>The X and Z reports already state a breakdown by tender — for ONE session.
 * What the requirement asks for is the day: every register consolidated, in one
 * place, with the net takings a shop is expected to confirm before anything is
 * banked. Reading the sessions of the day and summing their reports is what
 * makes that figure the same one the registers produced, rather than a second
 * computation that can disagree with them.
 *
 * <p>The validation is detailed by cashier and includes the floats and the
 * withdrawals, because that is what {@code -09} names: what a cashier did to
 * their drawer, checked one by one, with the transfers that repair a mis-keying
 * left where they happened — at the register ({@code LC-12-10}), which is the
 * only place that knows what was actually in the drawer.
 *
 * <p>Access reserved to ADMIN; the profile grant {@code safe_day} is what puts
 * it in an operator's menu.
 */
@Path("/admin/safe/days")
public class AdminDayHistoryResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminDayHistoryResource.class);

    /** The operator's language, for the names of the tenders. */
    @Inject
    BackOfficeMessages i18n;

    /** The day-history template. */
    @Inject
    @Location("admin-safe-days")
    Template adminSafeDays;

    /** The consolidation of a session, tender by tender. */
    @Inject
    CashSessionService cashSessionService;

    /** Parses the paper-tender declarations frozen on the sessions. */
    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * One tender of the day, with what the registers took in it.
     */
    public static class TenderRow {

        /** The tender key, as the registers name it. */
        public final String method;

        /** What the day took in that tender. */
        public final String amount;

        /**
         * Builds a row.
         *
         * @param method the tender key
         * @param amount the amount, two decimals
         */
        public TenderRow(String method, String amount) {
            this.method = method;
            this.amount = amount;
        }

        /**
         * Returns the tender key, for a template.
         *
         * @return the key
         */
        public String getMethod() {
            return method;
        }

        /**
         * Returns the amount, for a template.
         *
         * @return the amount, two decimals
         */
        public String getAmount() {
            return amount;
        }
    }

    /**
     * Shows one accounting day: its sessions, its breakdown by tender and the
     * movements each cashier made.
     *
     * @param day the accounting day, blank meaning today
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the day page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_DAY)
    public TemplateInstance day(@QueryParam("day") String day,
                                @QueryParam("notice") String notice,
                                @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method day with day: " + day);
        LocalDate examined = AdminForms.day(day) == null ? LocalDate.now() : AdminForms.day(day);
        List<CashSession> sessions = sessionsOf(examined);
        Map<String, BigDecimal> byTender = totalsByTender(sessions);
        Map<String, String> declarations = new LinkedHashMap<>();
        for (CashSession session : sessions) {
            String summary = paperDeclarationSummary(session);
            if (summary != null) {
                declarations.put(session.sessionNumber, summary);
            }
        }
        List<CashMovement> movements = movementsOf(examined);
        LOGGER.info("Exiting method day with " + sessions.size() + " session(s)");
        return adminSafeDays.data("sessions", sessions)
                .data("validation", com.intermarche.edge.domain.safe.SafeDayValidation.forDay(examined))
                .data("netRevenue", format(netRevenueOf(sessions)))
                .data("pending", pendingOf(movements))
                .data("tenderCodes", tenderCodesOf(byTender, movements))
                .data("tenderLabels", tenderLabelsOf(tenderCodesOf(byTender, movements)))
                .data("day", examined.toString())
                .data("tenders", rowsOf(byTender))
                .data("total", format(sum(byTender.values())))
                .data("movements", movements)
                .data("withdrawn", format(withdrawnOf(sessions)))
                .data("declarations", declarations)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Validates one cashier's movement of the day ({@code BO-05-02-09}).
     *
     * <p>Validating a drawer movement is an ENDORSEMENT and not a correction:
     * the row is the register's witness of what happened, and the back office
     * says it has checked it by naming the manager who did — which is the same
     * field a large movement endorsed at the till already carries.
     *
     * @param form the posted form
     * @return a 303 back to the day with the notice
     */
    @POST
    @Path("/validate")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_DAY, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response validate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method validate");
        Long id = AdminForms.id(form.getFirst("id"));
        CashMovement movement = id == null ? null : CashMovement.<CashMovement>findById(id);
        if (movement == null) {
            LOGGER.info("Exiting method validate: unknown movement");
            return back(AdminForms.text(form, "day"), "Mouvement inconnu.", false);
        }
        String endorsement = AdminForms.text(form, "endorsedBy");
        String refused = endorsementRefusal(endorsement);
        if (refused != null) {
            LOGGER.info("Exiting method validate: no endorsement");
            return back(AdminForms.text(form, "day"), refused, false);
        }
        movement.endorsedBy = endorsement;
        movement.persist();
        LOGGER.infof("Mouvement de caisse %s validé par %s", movement.movementUid, endorsement);
        LOGGER.info("Exiting method validate");
        return back(AdminForms.text(form, "day"), "Mouvement validé.", true);
    }

    /**
     * Corrects a cashier's movement of the day ({@code BO-05-02-09}).
     *
     * <p>The register's row is its witness and is never rewritten: the
     * correction is a NEW movement of the same kind and tender, carrying the
     * difference between the amount the controller found and the amount the
     * register recorded — or, for a cash declaration, which counts as its last
     * value, the corrected declaration itself. It is validated by construction,
     * by the controller who enters it, and it is refused once the day is
     * validated.
     *
     * @param form the posted form
     * @return a 303 back to the day with the notice
     */
    @POST
    @Path("/correct")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_DAY, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response correct(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method correct");
        String day = AdminForms.text(form, "day");
        Long id = AdminForms.id(form.getFirst("id"));
        CashMovement movement = id == null ? null : CashMovement.<CashMovement>findById(id);
        if (movement == null) {
            LOGGER.info("Exiting method correct: unknown movement");
            return back(day, "Mouvement inconnu.", false);
        }
        java.time.LocalDateTime when = movement.movementDate == null
                ? java.time.LocalDateTime.now() : movement.movementDate;
        String refusal = entryRefusal(when.toLocalDate(), form, true);
        if (refusal != null) {
            LOGGER.info("Exiting method correct: " + refusal);
            return back(day, refusal, false);
        }
        BigDecimal corrected = AdminForms.amount(form.getFirst("amount"));
        if (corrected == null) {
            LOGGER.info("Exiting method correct: no amount");
            return back(day, "Montant corrigé obligatoire.", false);
        }
        boolean declaration = movement.type == CashMovement.MovementType.DECLARATION;
        BigDecimal recorded = movement.amount == null ? BigDecimal.ZERO : movement.amount;
        BigDecimal written = declaration ? corrected : corrected.subtract(recorded);
        if (corrected.compareTo(recorded) == 0) {
            LOGGER.info("Exiting method correct: nothing to correct");
            return back(day, "Le montant corrigé est celui du mouvement.", false);
        }
        CashMovement correction = new CashMovement();
        correction.movementUid = backOfficeUid();
        correction.terminalId = movement.terminalId;
        correction.session = movement.session;
        correction.cashier = movement.cashier;
        correction.type = movement.type;
        correction.paymentMethod = movement.paymentMethod;
        correction.transferTo = movement.transferTo;
        correction.orderReference = movement.orderReference;
        correction.amount = written;
        correction.reason = clip("Correction du mouvement du "
                + when.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + " : "
                + AdminForms.text(form, "reason"));
        correction.movementDate = when.plusSeconds(1);
        correction.endorsedBy = AdminForms.text(form, "endorsedBy");
        correction.persist();
        LOGGER.infof("Mouvement de caisse %s corrigé par %s", movement.movementUid, correction.endorsedBy);
        LOGGER.info("Exiting method correct");
        return back(day, "Correction enregistrée.", true);
    }

    /**
     * Transfers a settlement from one tender to another in a session of the
     * day ({@code BO-05-02-09}, {@code LC-12-10}).
     *
     * <p>The same {@code TRANSFER} movement the register writes when a cashier
     * repairs a mis-keying, entered here by the controller who found it: the
     * session's report, and so the day's breakdown by tender, moves the amount
     * from one tender to the other.
     *
     * @param form the posted form
     * @return a 303 back to the day with the notice
     */
    @POST
    @Path("/transfer")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_DAY, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response transfer(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method transfer");
        String day = AdminForms.text(form, "day");
        String number = AdminForms.text(form, "session");
        CashSession session = number == null ? null
                : CashSession.<CashSession>find("sessionNumber", number).firstResult();
        if (session == null) {
            LOGGER.info("Exiting method transfer: unknown session");
            return back(day, "Session inconnue.", false);
        }
        LocalDate examined = AdminForms.day(day) == null ? LocalDate.now() : AdminForms.day(day);
        String refusal = entryRefusal(examined, form, true);
        if (refusal != null) {
            LOGGER.info("Exiting method transfer: " + refusal);
            return back(day, refusal, false);
        }
        String from = AdminForms.text(form, "from") == null ? CashMovement.CASH : AdminForms.text(form, "from");
        String to = AdminForms.text(form, "to");
        if (to == null || to.equals(from)) {
            LOGGER.info("Exiting method transfer: same tender");
            return back(day, "Les deux moyens de paiement du transfert doivent différer.", false);
        }
        BigDecimal amount = AdminForms.amount(form.getFirst("amount"));
        if (amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method transfer: unusable amount");
            return back(day, "Montant obligatoire et strictement positif.", false);
        }
        CashMovement transfer = new CashMovement();
        transfer.movementUid = backOfficeUid();
        transfer.terminalId = session.terminalId;
        transfer.session = session;
        transfer.cashier = session.openingCashier;
        transfer.type = CashMovement.MovementType.TRANSFER;
        transfer.paymentMethod = from;
        transfer.transferTo = to;
        transfer.amount = amount;
        transfer.reason = clip("Transfert de règlement : " + AdminForms.text(form, "reason"));
        transfer.movementDate = examined.atTime(java.time.LocalTime.now().withNano(0));
        transfer.endorsedBy = AdminForms.text(form, "endorsedBy");
        transfer.persist();
        LOGGER.infof("Transfert de %s de %s vers %s sur %s", amount, from, to, session.sessionNumber);
        LOGGER.info("Exiting method transfer");
        return back(day, "Transfert de règlement enregistré.", true);
    }

    /**
     * Validates the net revenue of the day ({@code BO-05-02-09}).
     *
     * <p>Refused while a cashier's movement of the day is still waiting for its
     * validation: the net revenue is confirmed once what the drawers did is
     * checked, and the pouches of the day wait for this validation
     * ({@code BO-05-02-10}).
     *
     * @param form the posted form
     * @return a 303 back to the day with the notice
     */
    @POST
    @Path("/validate-day")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_DAY, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response validateDay(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method validateDay");
        String day = AdminForms.text(form, "day");
        LocalDate examined = AdminForms.day(day) == null ? LocalDate.now() : AdminForms.day(day);
        String refusal = entryRefusal(examined, form, false);
        if (refusal != null) {
            LOGGER.info("Exiting method validateDay: " + refusal);
            return back(day, refusal, false);
        }
        List<CashSession> sessions = sessionsOf(examined);
        if (sessions.isEmpty()) {
            LOGGER.info("Exiting method validateDay: no session");
            return back(day, "Aucune session close ce jour-là : rien à valider.", false);
        }
        if (pendingOf(movementsOf(examined)) > 0) {
            LOGGER.info("Exiting method validateDay: pending movements");
            return back(day, "Des mouvements de caissières ne sont pas validés : validez-les avant la journée.",
                    false);
        }
        Map<String, BigDecimal> byTender = totalsByTender(sessions);
        StringBuilder totals = new StringBuilder();
        for (Map.Entry<String, BigDecimal> entry : byTender.entrySet()) {
            totals.append(totals.length() == 0 ? "" : ";").append(entry.getKey()).append('=')
                    .append(format(entry.getValue()));
        }
        com.intermarche.edge.domain.safe.SafeDayValidation validation =
                new com.intermarche.edge.domain.safe.SafeDayValidation();
        validation.accountingDay = examined;
        validation.netRevenue = netRevenueOf(sessions);
        validation.tenderTotals = clip(totals.toString());
        validation.validatedBy = AdminForms.text(form, "endorsedBy");
        validation.validatedAt = java.time.LocalDateTime.now();
        validation.persist();
        LOGGER.infof("Journée %s validée par %s", examined, validation.validatedBy);
        LOGGER.info("Exiting method validateDay");
        return back(day, "Chiffre d'affaires net de la journée validé.", true);
    }

    /**
     * The refusal common to the gestures of the controller: a day already
     * validated is frozen, and every gesture names its controller and, for a
     * correction or a transfer, its reason.
     *
     * @param day the accounting day the gesture touches
     * @param form the posted form
     * @param reasonRequired whether the gesture must state its reason
     * @return the refusal, or null when the gesture may proceed
     */
    String entryRefusal(LocalDate day, MultivaluedMap<String, String> form, boolean reasonRequired) {
        if (com.intermarche.edge.domain.safe.SafeDayValidation.forDay(day) != null) {
            return "Cette journée est déjà validée : elle ne se modifie plus.";
        }
        String endorsement = endorsementRefusal(AdminForms.text(form, "endorsedBy"));
        if (endorsement != null) {
            return endorsement;
        }
        if (reasonRequired && AdminForms.text(form, "reason") == null) {
            return "Motif obligatoire.";
        }
        return null;
    }

    /**
     * The net revenue the registers reported for the day: the closed tickets
     * less the refunds, session by session ({@code BO-05-02-09}).
     *
     * @param sessions the sessions of the day
     * @return the net revenue, never null
     */
    BigDecimal netRevenueOf(List<CashSession> sessions) {
        BigDecimal total = BigDecimal.ZERO;
        for (CashSession session : sessions) {
            CashSessionService.SessionReport report = cashSessionService.buildReport(session);
            total = total.add(report.totalIncludingTax == null ? BigDecimal.ZERO : report.totalIncludingTax)
                    .subtract(report.totalRefunds == null ? BigDecimal.ZERO : report.totalRefunds);
        }
        return total;
    }

    /**
     * How many movements of a list still wait for their validation.
     *
     * @param movements the movements of a day
     * @return the number of movements nobody validated
     */
    static long pendingOf(List<CashMovement> movements) {
        long pending = 0;
        for (CashMovement movement : movements) {
            if (movement.endorsedBy == null || movement.endorsedBy.isBlank()) {
                pending++;
            }
        }
        return pending;
    }

    /**
     * The tenders a transfer may name: the cash, the tenders of the day's
     * takings and those of its movements, from and to.
     *
     * @param byTender the day's takings by tender
     * @param movements the day's movements
     * @return the tender keys, cash first, without repetition
     */
    static List<String> tenderCodesOf(Map<String, BigDecimal> byTender, List<CashMovement> movements) {
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        codes.add(CashMovement.CASH);
        codes.addAll(byTender.keySet());
        for (CashMovement movement : movements) {
            if (movement.paymentMethod != null && !movement.paymentMethod.isBlank()) {
                codes.add(movement.paymentMethod);
            }
            if (movement.transferTo != null && !movement.transferTo.isBlank()) {
                codes.add(movement.transferTo);
            }
        }
        return new ArrayList<>(codes);
    }

    /**
     * The operator's names of the tenders the screen shows: the name of the
     * tender's nature in the page's language, the one the other screens use
     * ({@code t.x21-tender-CASH}…); for a key the bundles do not name, the
     * label the tender is declared under, else the key itself.
     *
     * @param codes the tender keys
     * @return each key's label, in the order given
     */
    Map<String, String> tenderLabelsOf(List<String> codes) {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        for (String code : codes) {
            String label = i18n.code("t.x21-tender-", code);
            if (label.equals(code)) {
                com.intermarche.edge.domain.payment.TenderDefinition tender =
                        com.intermarche.edge.domain.payment.TenderDefinition.findByCode(code);
                label = tender == null || tender.label == null || tender.label.isBlank() ? code : tender.label;
            }
            labels.put(code, label);
        }
        return labels;
    }

    /**
     * The refusal of a controller's name or badge: one is required, and it
     * holds in the 20 characters a movement keeps of it.
     *
     * @param endorsement the name or badge typed, or null
     * @return the refusal, or null when it is accepted
     */
    static String endorsementRefusal(String endorsement) {
        if (endorsement == null) {
            return "Nom ou badge du valideur obligatoire.";
        }
        return endorsement.length() > 20 ? "Nom ou badge du valideur trop long : 20 caractères au plus." : null;
    }

    /**
     * The identifier of a movement the back office writes: {@code BO-} and a
     * random UUID without its dashes, within the 36 characters the column holds.
     *
     * @return the identifier, 35 characters
     */
    static String backOfficeUid() {
        return "BO-" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Clips a text to the 255 characters a movement's reason holds.
     *
     * @param text the text, never null
     * @return the text, at most 255 characters
     */
    static String clip(String text) {
        return text.length() <= 255 ? text : text.substring(0, 255);
    }

    /**
     * Extracts the day's breakdown by tender as CSV ({@code BO-05-02-08}).
     *
     * @param day the accounting day, blank meaning today
     * @return the CSV file, named after the day
     */
    @GET
    @Path("/export")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_DAY)
    @Produces("text/csv; charset=UTF-8")
    public Response export(@QueryParam("day") String day) {
        LOGGER.info("Entering method export");
        LocalDate examined = AdminForms.day(day) == null ? LocalDate.now() : AdminForms.day(day);
        Map<String, BigDecimal> byTender = totalsByTender(sessionsOf(examined));
        StringBuilder csv = new StringBuilder("JOURNEE;MOYEN;MONTANT\n");
        for (Map.Entry<String, BigDecimal> entry : byTender.entrySet()) {
            csv.append(examined).append(';')
                    .append(entry.getKey() == null ? "" : entry.getKey().replace(';', ',')).append(';')
                    .append(format(entry.getValue())).append('\n');
        }
        LOGGER.info("Exiting method export");
        return Response.ok(csv.toString())
                .header("Content-Disposition",
                        "attachment; filename=\"journee-" + examined + ".csv\"")
                .build();
    }

    /**
     * The sessions closed on one accounting day.
     *
     * @param day the accounting day
     * @return the closed sessions of that day, by register
     */
    List<CashSession> sessionsOf(LocalDate day) {
        return CashSession.<CashSession>find(
                "status = ?1 and closingDate >= ?2 and closingDate < ?3 order by terminalId",
                CashSession.SessionStatus.CLOSED, day.atStartOfDay(),
                day.plusDays(1).atStartOfDay()).list();
    }

    /**
     * The drawer movements of one accounting day, whichever register they were
     * made on ({@code BO-05-02-09}).
     *
     * @param day the accounting day
     * @return the movements of that day, oldest first
     */
    List<CashMovement> movementsOf(LocalDate day) {
        return CashMovement.<CashMovement>find(
                "movementDate >= ?1 and movementDate < ?2 order by movementDate",
                day.atStartOfDay(), day.plusDays(1).atStartOfDay()).list();
    }

    /**
     * The day's theoretical, tender by tender ({@code BO-05-02-08}): the expected
     * amount of each tender as the registers' own reports state it, so the cash
     * line is the sum of the Z theoreticals (float, sales, refunds, cash-ins,
     * expenses, withdrawals and transfers) and never a second, different drawer.
     *
     * @param sessions the sessions of the day
     * @return the totals by tender key, in the order the registers report them
     */
    Map<String, BigDecimal> totalsByTender(List<CashSession> sessions) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (CashSession session : sessions) {
            CashSessionService.SessionReport report = cashSessionService.buildReport(session);
            for (Map.Entry<String, BigDecimal> entry : report.theoreticalByMethod.entrySet()) {
                BigDecimal already = totals.get(entry.getKey());
                BigDecimal added = entry.getValue() == null ? BigDecimal.ZERO : entry.getValue();
                totals.put(entry.getKey(), already == null ? added : already.add(added));
            }
        }
        return totals;
    }

    /**
     * Turns the totals into printable rows.
     *
     * @param totals the totals by tender key
     * @return the rows, in the same order
     */
    List<TenderRow> rowsOf(Map<String, BigDecimal> totals) {
        List<TenderRow> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            rows.add(new TenderRow(entry.getKey(), format(entry.getValue())));
        }
        return rows;
    }

    /**
     * What the registers withdrew on the day.
     *
     * @param sessions the sessions of the day
     * @return the total withdrawn, never null
     */
    BigDecimal withdrawnOf(List<CashSession> sessions) {
        BigDecimal total = BigDecimal.ZERO;
        for (CashSession session : sessions) {
            total = total.add(session.withdrawnAmount == null
                    ? BigDecimal.ZERO : session.withdrawnAmount);
        }
        return total;
    }

    /**
     * Sums a collection of amounts.
     *
     * @param amounts the amounts, nulls counting as zero
     * @return the sum, never null
     */
    BigDecimal sum(Iterable<BigDecimal> amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            total = total.add(amount == null ? BigDecimal.ZERO : amount);
        }
        return total;
    }

    /**
     * Formats an amount the way the screens show it.
     *
     * @param amount the amount, possibly null
     * @return the amount, two decimals
     */
    private String format(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount)
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Builds the 303 back to the day examined.
     *
     * @param day the day examined, null falling back to the screen's default
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String day, String notice, boolean ok) {
        String path = day == null ? "/admin/safe/days" : "/admin/safe/days?day=" + day;
        return AdminForms.redirect(path, notice, ok);
    }

    /**
     * Re-reads an employee by their identifier, for a caller that names one.
     *
     * @param id the identifier, possibly null
     * @return the employee, or null when the identifier names none
     */
    Employee employeeOf(Long id) {
        return id == null ? null : Employee.<Employee>findById(id);
    }

    /**
     * Summarizes one session's paper-tender declaration (EXP-83): each
     * declared quantity against its theoretical, so the back office reads at a
     * glance which tenders were declared short. The declaration is FROZEN on
     * the session by the selling register — the node restates it, it never
     * recomputes it.
     *
     * @param session the closed session
     * @return the summary, "ÉCART" first when any tender is short, or null
     *         when the session declared no paper tender
     */
    String paperDeclarationSummary(CashSession session) {
        String raw = session.paperDeclaration;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(raw);
            StringBuilder summary = new StringBuilder();
            boolean mismatch = false;
            for (java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> tenders
                    = root.fields(); tenders.hasNext();) {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> tender = tenders.next();
                int declared = tender.getValue().path("declared").asInt(0);
                int theoretical = tender.getValue().path("theoretical").asInt(0);
                if (summary.length() > 0) {
                    summary.append(", ");
                }
                summary.append(tender.getKey()).append(' ').append(declared)
                        .append('/').append(theoretical);
                if (declared != theoretical) {
                    mismatch = true;
                }
            }
            if (summary.length() == 0) {
                return null;
            }
            return mismatch ? "ÉCART — " + summary : summary.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
