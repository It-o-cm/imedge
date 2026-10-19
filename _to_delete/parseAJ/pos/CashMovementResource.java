package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.net.URI;
import org.jboss.logging.Logger;

/**
 * JAX-RS resource of the cash-movement screen: the cashier records a drawer
 * movement (expense, customer down-payment or cash-count declaration) with its
 * amount and reason, and the movement is written to the register's own database
 * and pushed to the store node through the outbox by {@link CashMovementService}
 * — no synchronous network call sits in this path, so a register cut off from the
 * network keeps recording movements.
 * <p>
 * THREE TYPES, NOT FIVE. The screen used to offer the withdrawal and the coin
 * supply as well, as a flat amount with no denomination detail — while the two
 * buttons next to it on the drawer menu do the same gestures counted coin by coin
 * and print their bordereau. Two roads for one movement, one of them degraded, is
 * how a prélèvement ends up recorded without the count that makes it opposable, so
 * those two types are refused here and the dedicated screens own them.
 * <p>
 * A JUSTIFICATIF IS PRINTED for every movement recorded here. These three printed
 * nothing, which left an expense taken from the drawer with no paper to put in the
 * till beside the receipts.
 * <p>
 * THE REASON IS NOT DECORATION: an expense must name the reason it was paid for
 * (administered list), and a customer down-payment must name the ORDER it belongs
 * to — it is then written through {@code recordAcompte}, which carries that
 * reference in its own column so a later recall of the order deducts what was
 * already collected (EXP-85). A declaration names nothing — it states a count.
 * <p>
 * ENDORSEMENT ceremony (BO-04-01-44 threshold): a movement strictly above the
 * administered threshold ({@code cash.movement-endorsement-threshold}) needs a
 * manager. Two roads, both landing a manager BADGE on the movement — never a
 * secret: the LOGGED operator already holding the MANAGER/ADMIN role
 * self-endorses (their own badge is carried), otherwise a manager presents
 * their credentials, checked and journaled through {@link EndorsementService}.
 * When the amount is at or below the threshold, no credential is asked. The
 * resource resolves the endorsing badge; the service is the guard that refuses
 * an above-threshold movement without one.
 * <p>
 * This screen is a DELIBERATE menu destination like the session page, reached
 * with an open session; it never interrupts a sale.
 */
@Path("/")
public class CashMovementResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashMovementResource.class);

    /** Action code journaled when a manager endorses a cash movement. */
    public static final String ENDORSEMENT_ACTION = "CASH_MOVEMENT";

    /**
     * The localised operator messages: every sentence this class shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;


    @Inject @Location("cash-movement") Template cashMovement;
    @Inject CashSessionService cashSessionService;
    @Inject CashMovementService cashMovementService;
    @Inject PosSettingsService posSettingsService;
    /** The administered lists: expense justifications (BO-04-03-12). */
    @Inject com.intermarche.pos.service.PosListService posListService;
    @Inject EndorsementService endorsementService;
    @Inject com.intermarche.pos.ui.hardware.TicketPrinterService ticketPrinterService;
    @Inject com.intermarche.pos.service.TicketNumberService ticketNumberService;
    @Inject PosState state;

    /**
     * Shows the cash-movement form: the movement types, the administered reasons
     * and the endorsement threshold, plus any outcome message from a redirect.
     *
     * @param error an optional error code from a redirect
     * @param ok a non-null flag when the previous movement was recorded
     * @return the cash-movement page
     */
    @GET
    @Path("/cash-movement")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cashMovementPage(@QueryParam("error") String error,
                                             @QueryParam("ok") String ok) {
        LOGGER.info("Entering method cashMovementPage with error: " + error + ", ok: " + ok);
        String message;
        if ("no-session".equals(error)) {
            message = i18n.get("op.aucuneSessionOuverte");
        } else if ("bad-type".equals(error)) {
            message = i18n.get("op.typeMouvementInvalide");
        } else if ("own-screen".equals(error)) {
            message = i18n.get("op.prelevementApportEcransDedies");
        } else if ("reason".equals(error)) {
            message = i18n.get("op.motifObligatoire");
        } else if ("reference".equals(error)) {
            message = i18n.get("op.referenceCommandeObligatoire");
        } else if ("endorsement".equals(error)) {
            message = i18n.get("op.avalManagerRefuseManquant");
        } else if ("training".equals(error)) {
            message = i18n.get("op.indisponibleFormation");
        } else {
            message = null;
        }
        LOGGER.info("Exiting method cashMovementPage");
        return cashMovement
                .data("state", state)
                .data("reasons", posListService.justifications(
                        com.intermarche.pos.service.PosListService.FUNCTION_EXPENSE))
                .data("tenders", posSettingsService.cashMovementTenders())
                .data("threshold", posSettingsService.cashMovementEndorsementThreshold().toPlainString())
                .data("saved", ok != null)
                .data("error", message);
    }

    /**
     * Records a cash movement of the open session. Above the administered
     * threshold a manager must endorse it; below it, nothing is asked.
     *
     * <p>The endorsement is asked through the SHARED modal, not through boxes
     * of this screen's own: the form is parked and the movement is written
     * only once a manager credential passes. The screen used to carry its own
     * badge and PIN inputs, which duplicated the modal and — being plain text
     * fields — could not be typed at all on a register with no keyboard.
     *
     * @param typeStr the movement type name
     * @param amountStr the amount typed by the cashier (French comma tolerated)
     * @param reason the movement reason, or null
     * @param reference the order reference of a customer down-payment, or null
     * @param paymentMethod the tender the movement concerns, or null for cash
     * @return a redirect back to the cash-movement page carrying the outcome
     */
    @POST
    @Path("/action/cash-movement/record")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response record(@FormParam("type") String typeStr,
                           @FormParam("amount") String amountStr,
                           @FormParam("reason") String reason,
                           @FormParam("reference") String reference,
                           @FormParam("paymentMethod") String paymentMethod) {
        LOGGER.info("Entering method record with typeStr: " + typeStr + ", amountStr: " + amountStr + ", reason: " + reason + ", reference: " + reference + ", paymentMethod: " + paymentMethod);
        if (state.trainingMode) {
            LOGGER.info("Exiting method record");
            return redirect("/cash-movement?error=training");
        }
        if (cashSessionService.getOpenSession() == null) {
            LOGGER.info("Exiting method record");
            return redirect("/cash-movement?error=no-session");
        }
        CashMovement.MovementType type = parseType(typeStr);
        if (type == null) {
            LOGGER.info("Exiting method record");
            return redirect("/cash-movement?error=bad-type");
        }
        String rejection = rejectionOf(type, reason, reference);
        if (rejection != null) {
            LOGGER.info("Exiting method record: " + rejection);
            return redirect("/cash-movement?error=" + rejection);
        }
        if (cashMovementService.requiresEndorsement(parseAmount(amountStr))
                && !endorsementService.operatorIsSupervisor(state)) {
            java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
            // No null guard on the type: the parse guard above refused the line
            // when parseType answered null, and it answers null on a null input.
            // Reaching here proves the type was given and names a movement.
            form.put("type", typeStr);
            form.put("amount", amountStr == null ? "" : amountStr);
            form.put("reason", reason == null ? "" : reason);
            form.put("reference", reference == null ? "" : reference);
            form.put("paymentMethod", paymentMethod == null ? "" : paymentMethod);
            endorsementService.requestAuthorization(state, ENDORSEMENT_ACTION, form, "/cash-movement");
            LOGGER.info("Exiting method record: aval demande");
            return redirect("/cash-movement");
        }
        LOGGER.info("Exiting method record");
        return perform(typeStr, amountStr, reason, reference, paymentMethod, supervisorBadge());
    }

    /**
     * Says why a movement cannot be recorded on THIS screen, as the error code the
     * page turns into a sentence.
     *
     * <p>Three refusals, each of them a gesture that exists elsewhere or a field
     * without which the movement cannot be justified: a withdrawal or a coin supply
     * belongs to its own screen, where it is counted denomination by denomination; an
     * expense must say what it paid for; a customer down-payment must say which order
     * it belongs to. A declaration asks for neither — it states a count.
     *
     * @param type the parsed movement type, never null
     * @param reason the reason chosen in the administered list, or null
     * @param reference the order reference of a down-payment, or null
     * @return the error code, or null when the movement may be recorded
     */
    private String rejectionOf(CashMovement.MovementType type, String reason, String reference) {
        if (type == CashMovement.MovementType.WITHDRAWAL
                || type == CashMovement.MovementType.DEPOSIT) {
            return "own-screen";
        }
        if (type == CashMovement.MovementType.EXPENSE && (reason == null || reason.isBlank())) {
            return "reason";
        }
        if (type == CashMovement.MovementType.CUSTOMER_DEPOSIT
                && (reference == null || reference.isBlank())) {
            return "reference";
        }
        return null;
    }

    /**
     * The operator-facing wording of a movement type, printed on the justificatif.
     *
     * @param type the movement type, never null
     * @return the wording the cashier read on the screen
     */
    private String typeLabelOf(CashMovement.MovementType type) {
        return switch (type) {
            case EXPENSE -> "Dépense";
            case CUSTOMER_DEPOSIT -> "Acompte client";
            case DECLARATION -> "Déclaration (comptage)";
            case WITHDRAWAL -> "Prélèvement";
            case DEPOSIT -> "Apport";
            case TRANSFER -> "Transfert";
        };
    }

    /**
     * Replays the parked movement once the shared modal validated a manager.
     *
     * @param form the parked form fields
     * @param endorsedBy the endorsing manager's badge or login
     * @return a redirect back to the cash-movement page carrying the outcome
     */
    public Response performEndorsed(java.util.Map<String, String> form, String endorsedBy) {
        LOGGER.info("Entering method performEndorsed with endorsedBy: " + endorsedBy);
        LOGGER.info("Exiting method performEndorsed");
        return perform(form.get("type"), form.get("amount"), form.get("reason"),
                form.get("reference"), form.get("paymentMethod"), endorsedBy);
    }

    /**
     * Writes the movement, re-running every guard: the parked form goes
     * through the same checks as a form posted directly, so a session closed
     * while the modal was open cannot slip a movement through.
     *
     * @param typeStr the movement type name
     * @param amountStr the raw amount
     * @param reason the movement reason, or null
     * @param reference the order reference of a customer down-payment, or null
     * @param paymentMethod the tender the movement concerns, or null for cash
     * @param endorsedBy the endorsing manager's badge, or null when none was needed
     * @return a redirect back to the cash-movement page carrying the outcome
     */
    private Response perform(String typeStr, String amountStr, String reason,
                             String reference, String paymentMethod, String endorsedBy) {
        LOGGER.info("Entering method perform with typeStr: " + typeStr + ", amountStr: " + amountStr);
        if (state.trainingMode) {
            LOGGER.info("Exiting method perform");
            return redirect("/cash-movement?error=training");
        }
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            LOGGER.info("Exiting method perform");
            return redirect("/cash-movement?error=no-session");
        }
        CashMovement.MovementType type = parseType(typeStr);
        if (type == null) {
            LOGGER.info("Exiting method perform");
            return redirect("/cash-movement?error=bad-type");
        }
        String rejection = rejectionOf(type, reason, reference);
        if (rejection != null) {
            LOGGER.info("Exiting method perform: " + rejection);
            return redirect("/cash-movement?error=" + rejection);
        }
        BigDecimal amount = parseAmount(amountStr);
        if (cashMovementService.requiresEndorsement(amount) && endorsedBy == null) {
            LOGGER.info("Exiting method perform");
            return redirect("/cash-movement?error=endorsement");
        }
        Employee cashier = (state.auth.operatorId != null)
                ? Employee.findById(state.auth.operatorId) : null;
        // BO-03-02-20: the movement names the tender it concerns when the form
        // carries one; a blank selection lands as null, i.e. a cash movement.
        String tender = (paymentMethod == null || paymentMethod.isBlank()) ? null : paymentMethod.trim();
        // EXP-85: a down-payment goes through its OWN writer, which carries the order
        // reference in its own column — that column is what a later recall of the
        // order reads to deduct what was already collected. Recording it as a plain
        // movement would leave the reference as prose nobody can query.
        CashMovement recorded = (type == CashMovement.MovementType.CUSTOMER_DEPOSIT)
                ? cashMovementService.recordAcompte(session, cashier, amount, reference)
                : cashMovementService.record(
                        session, cashier, type, amount, reason, endorsedBy, tender, null, null);
        state.touch();
        if (recorded == null) {
            LOGGER.info("Exiting method perform");
            return redirect("/cash-movement?error=endorsement");
        }
        // The paper that justifies the cash: these three movements printed nothing,
        // so an expense left the drawer with no slip to file beside the receipts.
        ticketPrinterService.printMovementTicket(typeLabelOf(type), amount, recorded.reason,
                tender, state.getOperatorName(), ticketNumberService.getTerminalId());
        LOGGER.info("Exiting method perform");
        return redirect("/cash-movement?ok=1");
    }

    /**
     * The badge of the logged operator when they are themselves a supervisor,
     * the connected-supervisor shortcut of LC-01-05-07; null otherwise.
     *
     * @return the endorsing badge, or null when the operator cannot self-endorse
     */
    private String supervisorBadge() {
        if (endorsementService.operatorIsSupervisor(state)) {
            return state.auth.operatorBadgeId;
        }
        return null;
    }

    /**
     * Parses a movement type name into its enum value, tolerating a null or an
     * unknown name (returns null).
     *
     * @param value the raw type name
     * @return the parsed type, or null when blank or unknown
     */
    private CashMovement.MovementType parseType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return CashMovement.MovementType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses a form amount (French comma tolerated) into a BigDecimal.
     *
     * @param value the raw form value
     * @return the parsed amount, or ZERO when blank or invalid
     */
    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", "."));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Builds a 303 redirect to the given location (PRG pattern, so a browser
     * reload never replays the POST).
     *
     * @param location the target location
     * @return a 303 See Other response
     */
    private Response redirect(String location) {
        return Response.seeOther(URI.create(location)).build();
    }
}
