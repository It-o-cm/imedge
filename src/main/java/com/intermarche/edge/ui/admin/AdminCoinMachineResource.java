package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.safe.CoinMachine;
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
import java.time.LocalDateTime;
import java.util.List;

/**
 * The COIN MACHINE screen ({@code /admin/coin-machines}, {@code BO-05-02-16}):
 * what each automatic cash unit of the line holds, as the checkout software
 * believes it.
 *
 * <p>The requirement's whole point is that the figure comes from the SOFTWARE
 * and not from the machine: a shop must be able to answer "how much is in
 * register four's coin unit" without walking to it or waking a protocol. So the
 * screen shows a running total — moved by the settlements collected, the change
 * given back and the loadings declared — and a counted amount beside it for the
 * days somebody does open the machine.
 *
 * <p>The two figures are never merged. Their difference is the only thing worth
 * looking at, and a single column would be the one place it could hide.
 *
 * <p>Access reserved to ADMIN; the profile grant {@code coin_machine} is what
 * puts it in an operator's menu.
 */
@Path("/admin/coin-machines")
public class AdminCoinMachineResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminCoinMachineResource.class);

    /** How many of the latest flows the screen shows (BO-05-02-16). */
    static final int FLOW_LINES = 30;

    /** The coin-machine template. */
    @Inject
    @Location("admin-coin-machines")
    Template adminCoinMachines;

    /** What the machines hold, moved by the line and by the loadings. */
    @Inject
    com.intermarche.edge.service.CoinMachineService coinMachineService;

    /**
     * Shows the machines and what each is believed to hold.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the machines page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.COIN_MACHINE)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        List<CoinMachine> machines = CoinMachine.listOrdered();
        LOGGER.info("Exiting method list with " + machines.size() + " machine(s)");
        return adminCoinMachines.data("machines", machines)
                .data("total", format(totalOf(machines)))
                .data("flows", com.intermarche.edge.domain.safe.CoinMachineFlow.latest(FLOW_LINES))
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Declares a machine or corrects its description ({@code BO-05-02-16}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Authenticated
    @FeatureAccess(value = Feature.COIN_MACHINE, option = CrudOption.CREATE, updateKey = "id")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        String code = AdminForms.text(form, "code");
        if (code == null) {
            LOGGER.info("Exiting method save: no code");
            return back("Code du monnayeur obligatoire.", false);
        }
        Long id = AdminForms.id(form.getFirst("id"));
        CoinMachine machine = id == null ? null : CoinMachine.<CoinMachine>findById(id);
        CoinMachine clash = CoinMachine.findByCode(code);
        if (clash != null && (machine == null || !clash.id.equals(machine.id))) {
            LOGGER.info("Exiting method save: duplicate code");
            return back("Ce code de monnayeur existe déjà : " + code, false);
        }
        if (machine == null) {
            machine = new CoinMachine();
        }
        machine.code = code;
        machine.label = AdminForms.text(form, "label");
        machine.terminalId = AdminForms.text(form, "terminalId");
        machine.active = AdminForms.checkbox(form, "active");
        machine.persist();
        LOGGER.infof("Monnayeur %s enregistré", code);
        LOGGER.info("Exiting method save");
        return back("Monnayeur enregistré.", true);
    }

    /**
     * Declares what a machine was found to hold ({@code BO-05-02-16}).
     *
     * <p>Writes the COUNTED amount and leaves the running total alone: a count
     * that silently realigned the software would destroy the discrepancy it was
     * taken to reveal.
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/count")
    @Authenticated
    @FeatureAccess(value = Feature.COIN_MACHINE, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response count(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method count");
        Long id = AdminForms.id(form.getFirst("id"));
        CoinMachine machine = id == null ? null : CoinMachine.<CoinMachine>findById(id);
        if (machine == null) {
            LOGGER.info("Exiting method count: unknown machine");
            return back("Monnayeur inconnu.", false);
        }
        BigDecimal counted = AdminForms.amount(form.getFirst("countedAmount"));
        if (counted == null || counted.signum() < 0) {
            LOGGER.info("Exiting method count: unusable amount");
            return back("Montant compté obligatoire et positif.", false);
        }
        machine.countedAmount = counted;
        machine.countedOn = LocalDateTime.now();
        machine.persist();
        coinMachineService.journal(machine, com.intermarche.edge.domain.safe.CoinMachineFlow.Kind.COUNT,
                BigDecimal.ZERO);
        LOGGER.infof("Monnayeur %s compté à %s", machine.code, counted);
        LOGGER.info("Exiting method count");
        return back(machine.isReconciled()
                ? "Comptage conforme au montant tenu par le logiciel."
                : "Comptage enregistré, écart de " + machine.getVarianceFormatted().replace('.', ',') + " €.",
                machine.isReconciled());
    }

    /**
     * Realigns the running total on the last count ({@code BO-05-02-16}).
     *
     * <p>A separate gesture from the count on purpose: somebody decides that the
     * machine is right and the software wrong, and that decision is taken by a
     * person, never by the act of counting.
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/align")
    @Authenticated
    @FeatureAccess(value = Feature.COIN_MACHINE, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response align(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method align");
        Long id = AdminForms.id(form.getFirst("id"));
        CoinMachine machine = id == null ? null : CoinMachine.<CoinMachine>findById(id);
        if (machine == null || machine.countedAmount == null) {
            LOGGER.info("Exiting method align: nothing to align on");
            return back("Aucun comptage à reprendre.", false);
        }
        BigDecimal before = machine.declaredAmount == null ? BigDecimal.ZERO : machine.declaredAmount;
        machine.declaredAmount = machine.countedAmount;
        machine.updatedOn = LocalDateTime.now();
        machine.persist();
        coinMachineService.journal(machine, com.intermarche.edge.domain.safe.CoinMachineFlow.Kind.REALIGN,
                machine.countedAmount.subtract(before));
        LOGGER.infof("Monnayeur %s réaligné sur %s", machine.code, machine.countedAmount);
        LOGGER.info("Exiting method align");
        return back("Montant tenu par le logiciel réaligné.", true);
    }

    /**
     * Declares cash loaded into a machine, or taken out of it
     * ({@code BO-05-02-16}).
     *
     * <p>A loading is the third thing that moves the running total, beside the
     * settlements collected and the change given back. Typed with a minus sign
     * it empties the machine, which is the same gesture read the other way.
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/load")
    @Authenticated
    @FeatureAccess(value = Feature.COIN_MACHINE, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response load(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method load");
        BigDecimal amount = AdminForms.amount(form.getFirst("amount"));
        if (amount == null || amount.signum() == 0) {
            LOGGER.info("Exiting method load: unusable amount");
            return back("Montant de chargement obligatoire et non nul.", false);
        }
        CoinMachine moved = coinMachineService.load(AdminForms.text(form, "code"), amount);
        if (moved == null) {
            LOGGER.info("Exiting method load: unknown machine");
            return back("Monnayeur inconnu.", false);
        }
        LOGGER.info("Exiting method load");
        return back("Chargement enregistré.", true);
    }

    /**
     * Extracts the machines as CSV ({@code BO-05-02-16}).
     *
     * @return the CSV file
     */
    @GET
    @Path("/export")
    @Authenticated
    @FeatureAccess(value = Feature.COIN_MACHINE)
    @Produces("text/csv; charset=UTF-8")
    public Response export() {
        LOGGER.info("Entering method export");
        StringBuilder csv = new StringBuilder("CODE;LIBELLE;CAISSE;ACTIF;TENU;COMPTE;ECART\n");
        for (CoinMachine machine : CoinMachine.listOrdered()) {
            csv.append(cell(machine.code))
                    .append(cell(machine.label))
                    .append(cell(machine.terminalId))
                    .append(cell(machine.active ? "Oui" : "Non"))
                    .append(cell(machine.getDeclaredAmountFormatted()))
                    .append(cell(machine.countedAmount == null
                            ? "" : format(machine.countedAmount)))
                    .append(machine.getVarianceFormatted())
                    .append('\n');
        }
        LOGGER.info("Exiting method export");
        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=\"monnayeurs.csv\"")
                .build();
    }

    /**
     * What the line's machines are believed to hold in total.
     *
     * @param machines the machines
     * @return the total, never null
     */
    BigDecimal totalOf(List<CoinMachine> machines) {
        BigDecimal total = BigDecimal.ZERO;
        for (CoinMachine machine : machines) {
            total = total.add(machine.declaredAmount == null
                    ? BigDecimal.ZERO : machine.declaredAmount);
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
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/coin-machines", notice, ok);
    }
}
