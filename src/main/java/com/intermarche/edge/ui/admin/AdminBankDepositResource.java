package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.BankDeposit;
import com.intermarche.edge.domain.safe.CashPouch;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.SafeService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The BANK DEPOSIT screen ({@code /admin/safe/bank-deposits},
 * {@code BO-05-02-07}): the pouches held in the safe leaving for the bank, the
 * debit it writes, and the two accounting pieces the requirement names.
 *
 * <p>The amount is NEVER typed. It is the sum of the pouches picked, because
 * those are what physically go into the bag — a declared amount that disagreed
 * with them would be a discrepancy nobody could explain and everybody would
 * argue about.
 *
 * <p>The two pieces — the {@code bordereau espèces} and the {@code versement
 * espèces} — are printed from the deposit and its pouches. Plain text, like the
 * coin-order slip: what a bank reads is a list of pouches and a total.
 *
 * <p>Access reserved to ADMIN; the profile grant {@code safe_bank_deposit} is
 * what puts it in an operator's menu.
 */
@Path("/admin/safe/bank-deposits")
public class AdminBankDepositResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminBankDepositResource.class);

    /** The deposit list template. */
    @Inject
    @Location("admin-bank-deposits")
    Template adminBankDeposits;

    /** The safe: the deposit is written through it. */
    @Inject
    SafeService safeService;

    /** Who is asking, so a deposit can name who declared it. */
    @Inject
    CurrentUser currentUser;

    /**
     * Shows the deposits and the pouches still available for one.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the deposits page
     */
    @GET
    @RolesAllowed("ADMIN")
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        List<CashPouch> available = CashPouch.inSafe();
        LOGGER.info("Exiting method list with " + available.size() + " pouch(es) available");
        return adminBankDeposits.data("deposits", BankDeposit.listNewestFirst())
                .data("available", available)
                .data("availableTotal", format(totalOf(available)))
                .data("today", LocalDate.now().toString())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Declares a deposit and debits the safe of it ({@code BO-05-02-07}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deposit(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deposit");
        String bag = AdminForms.text(form, "bagNumber");
        if (bag == null) {
            LOGGER.info("Exiting method deposit: no bag number");
            return back("Numéro de sac obligatoire.", false);
        }
        if (BankDeposit.find("bagNumber", bag).firstResult() != null) {
            LOGGER.info("Exiting method deposit: duplicate bag number");
            return back("Ce numéro de sac existe déjà : " + bag, false);
        }
        List<CashPouch> picked = pickedPouches(form);
        if (picked.isEmpty()) {
            LOGGER.info("Exiting method deposit: no pouch picked");
            return back("Aucune pochette sélectionnée.", false);
        }
        BankDeposit created = new BankDeposit();
        created.bagNumber = bag;
        created.label = AdminForms.text(form, "label");
        LocalDate day = AdminForms.day(form.getFirst("depositDay"));
        created.depositDay = day == null ? LocalDate.now() : day;
        String refusal = safeService.depositToBank(created, picked, signedIn());
        if (refusal != null) {
            LOGGER.info("Exiting method deposit: " + refusal);
            return back(refusal, false);
        }
        LOGGER.info("Exiting method deposit");
        return back("Remise en banque enregistrée.", true);
    }

    /**
     * Prints the {@code bordereau espèces} of a deposit ({@code BO-05-02-07}).
     *
     * @param id the deposit printed
     * @return the piece, or a 404 when the deposit is unknown
     */
    @GET
    @Path("/{id}/bordereau")
    @RolesAllowed("ADMIN")
    @Produces("text/plain; charset=UTF-8")
    public Response bordereau(@PathParam("id") Long id) {
        LOGGER.info("Entering method bordereau with id: " + id);
        BankDeposit deposit = id == null ? null : BankDeposit.<BankDeposit>findById(id);
        if (deposit == null) {
            LOGGER.info("Exiting method bordereau: unknown deposit");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        LOGGER.info("Exiting method bordereau");
        return piece(renderBordereau(deposit), "bordereau-especes-" + deposit.bagNumber);
    }

    /**
     * Prints the {@code versement espèces} of a deposit ({@code BO-05-02-07}).
     *
     * @param id the deposit printed
     * @return the piece, or a 404 when the deposit is unknown
     */
    @GET
    @Path("/{id}/versement")
    @RolesAllowed("ADMIN")
    @Produces("text/plain; charset=UTF-8")
    public Response versement(@PathParam("id") Long id) {
        LOGGER.info("Entering method versement with id: " + id);
        BankDeposit deposit = id == null ? null : BankDeposit.<BankDeposit>findById(id);
        if (deposit == null) {
            LOGGER.info("Exiting method versement: unknown deposit");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        LOGGER.info("Exiting method versement");
        return piece(renderVersement(deposit), "versement-especes-" + deposit.bagNumber);
    }

    /**
     * Lays the {@code bordereau espèces} out: what the bag holds, pouch by pouch.
     *
     * @param deposit the deposit printed
     * @return the piece
     */
    String renderBordereau(BankDeposit deposit) {
        StringBuilder piece = new StringBuilder();
        piece.append("BORDEREAU ESPECES\n");
        piece.append("Sac : ").append(deposit.bagNumber).append('\n');
        piece.append("Date : ").append(deposit.depositDay).append('\n');
        if (deposit.label != null && !deposit.label.isBlank()) {
            piece.append("Objet : ").append(deposit.label).append('\n');
        }
        piece.append('\n');
        for (CashPouch pouch : pouchesOf(deposit)) {
            piece.append(String.format("%-20s %-12s %12s%n", pouch.pouchNumber,
                    pouch.accountingDay == null ? "" : pouch.accountingDay.toString(),
                    pouch.getAmountFormatted()));
        }
        piece.append('\n');
        piece.append("TOTAL : ").append(deposit.getAmountFormatted()).append(" EUR\n");
        return piece.toString();
    }

    /**
     * Lays the {@code versement espèces} out: the one figure the bank credits.
     *
     * @param deposit the deposit printed
     * @return the piece
     */
    String renderVersement(BankDeposit deposit) {
        StringBuilder piece = new StringBuilder();
        piece.append("VERSEMENT ESPECES\n");
        piece.append("Sac : ").append(deposit.bagNumber).append('\n');
        piece.append("Date : ").append(deposit.depositDay).append('\n');
        piece.append("Nombre de pochettes : ").append(pouchesOf(deposit).size()).append('\n');
        piece.append("Montant verse : ").append(deposit.getAmountFormatted()).append(" EUR\n");
        return piece.toString();
    }

    /**
     * The pouches a deposit took away, re-read rather than taken off the row.
     *
     * @param deposit the deposit
     * @return its pouches, oldest day first
     */
    List<CashPouch> pouchesOf(BankDeposit deposit) {
        if (deposit == null || deposit.id == null) {
            return List.of();
        }
        return CashPouch.<CashPouch>find("bankDeposit.id = ?1 order by accountingDay, id",
                deposit.id).list();
    }

    /**
     * The pouches the form picked, dropping the ones no longer in the safe.
     *
     * @param form the posted form
     * @return the pouches to send to the bank, never null
     */
    List<CashPouch> pickedPouches(MultivaluedMap<String, String> form) {
        List<CashPouch> picked = new ArrayList<>();
        List<String> posted = form.get("pouchId");
        if (posted == null) {
            return picked;
        }
        for (String raw : posted) {
            Long id = AdminForms.id(raw);
            CashPouch pouch = id == null ? null : CashPouch.<CashPouch>findById(id);
            if (pouch != null && pouch.isInSafe()) {
                picked.add(pouch);
            }
        }
        return picked;
    }

    /**
     * The total of a list of pouches.
     *
     * @param pouches the pouches
     * @return the total, never null
     */
    BigDecimal totalOf(List<CashPouch> pouches) {
        BigDecimal total = BigDecimal.ZERO;
        for (CashPouch pouch : pouches) {
            total = total.add(pouch.amount == null ? BigDecimal.ZERO : pouch.amount);
        }
        return total;
    }

    /**
     * The employee signed in, as a deposit names them.
     *
     * @return the employee, or null when the session names nobody known
     */
    Employee signedIn() {
        String login = currentUser == null ? null : currentUser.getName();
        if (login == null || login.isBlank()) {
            return null;
        }
        return Employee.<Employee>find("loginName", login).firstResult();
    }

    /**
     * Wraps a printed piece as a downloadable text file.
     *
     * @param body the piece
     * @param name the file name, without its extension
     * @return the response
     */
    private Response piece(String body, String name) {
        return Response.ok(body)
                .header("Content-Disposition", "attachment; filename=\"" + name + ".txt\"")
                .build();
    }

    /**
     * Formats an amount the way the screens show it.
     *
     * @param amount the amount, possibly null
     * @return the amount, two decimals
     */
    private String format(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount)
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/safe/bank-deposits", notice, ok);
    }
}
