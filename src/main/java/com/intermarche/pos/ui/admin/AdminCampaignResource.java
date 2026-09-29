package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.campaign.Campaign;
import com.intermarche.pos.domain.util.DateTimeProvider;
import com.intermarche.pos.service.campaign.BudgetStatusClient;
import com.intermarche.pos.service.campaign.CampaignFeedProjector;
import com.intermarche.pos.service.campaign.CampaignService;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The back-office administration of campaigns on the store node
 * ({@code /admin/campaigns}, spec §2): the list, the fiche (edition, offer
 * association, budget entry), and duplication (GB-01-06-15).
 *
 * <p>The store node is the BO maître: administering a campaign is a store gesture,
 * not a register one. Every write follows the imedge admin cycle
 * POST &rarr; 303 &rarr; a one-shot notice, {@code @RolesAllowed("ADMIN")}
 * throughout (nav visibility is gated by the CAMPAIGN {@code Feature}). Every
 * save reprojects the {@code BUDGET_GROUPS} feed so the engine's budget view
 * follows the back office.
 */
@Path("/admin/campaigns")
public class AdminCampaignResource {

    /** Logger for this resource. */
    private static final Logger LOGGER = Logger.getLogger(AdminCampaignResource.class);

    /** Where the screen sends the operator back after a list-level write. */
    private static final String PAGE = "/admin/campaigns";

    /** The campaign list template. */
    @Inject
    @Location("admin-campaigns")
    Template adminCampaigns;

    /** The campaign fiche template. */
    @Inject
    @Location("admin-campaign")
    Template adminCampaign;

    /** The validation, creation, edition and duplication rules. */
    @Inject
    CampaignService campaignService;

    /** The BUDGET_GROUPS feed projector, run after every save. */
    @Inject
    CampaignFeedProjector campaignFeedProjector;

    /** The live consumption reader (engine GraphQL), for the list and fiche. */
    @Inject
    BudgetStatusClient budgetStatusClient;

    /**
     * Shows the campaign list with each campaign's live consumption.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the list page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Transactional
    public TemplateInstance list(@QueryParam("notice") String notice,
            @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list with notice: " + notice);
        List<Campaign> campaigns = Campaign.listAllOrdered();
        Map<String, BudgetStatusClient.BudgetStatus> statuses = new LinkedHashMap<>();
        for (Campaign campaign : campaigns) {
            statuses.put(campaign.code, budgetStatusClient.groupBudgetStatus(campaign.code));
        }
        LOGGER.info("Exiting method list");
        return adminCampaigns
                .data("campaigns", campaigns)
                .data("statuses", statuses)
                .data("today", DateTimeProvider.now().toLocalDate())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Shows the fiche of one campaign for edition.
     *
     * @param code the campaign code
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the fiche page
     */
    @GET
    @Path("/fiche")
    @RolesAllowed("ADMIN")
    @Transactional
    public TemplateInstance fiche(@QueryParam("code") String code,
            @QueryParam("notice") String notice,
            @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method fiche with code: " + code);
        Campaign campaign = Campaign.findByCode(code);
        LOGGER.info("Exiting method fiche");
        return adminCampaign
                .data("campaign", campaign)
                .data("status", budgetStatusClient.groupBudgetStatus(code))
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Creates a campaign from the list-screen form.
     *
     * @param form the posted form (code, label, validFrom, validTo, notes,
     *        maxAmount, maxApplications, perStore)
     * @return a 303 redirect to the list on success, or back with an error
     */
    @POST
    @Path("/create")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response create(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method create");
        String code = AdminForms.text(form, "code");
        if (code != null && Campaign.findByCode(code) != null) {
            LOGGER.info("Exiting method create: duplicate");
            return AdminForms.redirect(PAGE, "Une campagne porte déjà le code " + code + ".", false);
        }
        Campaign campaign = new Campaign();
        applyForm(campaign, form);
        campaign.code = code;
        try {
            campaignService.create(campaign);
        } catch (IllegalArgumentException invalid) {
            LOGGER.info("Exiting method create: invalid");
            return AdminForms.redirect(PAGE, message(invalid), false);
        }
        campaignFeedProjector.project();
        LOGGER.info("Exiting method create");
        return AdminForms.redirect(PAGE, "Campagne " + code + " créée.", true);
    }

    /**
     * Saves edits to an existing campaign (its code is its identity and is not
     * changed here — a new code is obtained by duplication).
     *
     * @param form the posted form (code, label, validFrom, validTo, notes,
     *        maxAmount, maxApplications, perStore)
     * @return a 303 redirect to the fiche carrying the outcome
     */
    @POST
    @Path("/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        String code = AdminForms.text(form, "code");
        Campaign campaign = Campaign.findByCode(code);
        if (campaign == null) {
            LOGGER.info("Exiting method save: unknown");
            return AdminForms.redirect(PAGE, "Campagne introuvable.", false);
        }
        applyForm(campaign, form);
        try {
            campaignService.update(campaign);
        } catch (IllegalArgumentException invalid) {
            LOGGER.info("Exiting method save: invalid");
            return AdminForms.redirect(fichePath(code), message(invalid), false);
        }
        campaignFeedProjector.project();
        LOGGER.info("Exiting method save");
        return AdminForms.redirect(fichePath(code), "Campagne " + code + " enregistrée.", true);
    }

    /**
     * Associates or dissociates an offer code on a campaign.
     *
     * @param form the posted form (code, offerCode, action)
     * @return a 303 redirect back to the fiche
     */
    @POST
    @Path("/offers")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response offers(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method offers");
        String code = AdminForms.text(form, "code");
        String offerCode = AdminForms.text(form, "offerCode");
        String action = AdminForms.text(form, "action");
        Campaign campaign = Campaign.findByCode(code);
        if (campaign == null) {
            LOGGER.info("Exiting method offers: unknown");
            return AdminForms.redirect(PAGE, "Campagne introuvable.", false);
        }
        if (offerCode == null) {
            LOGGER.info("Exiting method offers: no offer");
            return AdminForms.redirect(fichePath(code), "Aucune offre indiquée.", false);
        }
        if ("add".equals(action)) {
            if (campaignService.isOfferClaimedElsewhere(offerCode, code)) {
                LOGGER.info("Exiting method offers: claimed");
                return AdminForms.redirect(fichePath(code),
                        "L'offre " + offerCode + " est déjà rattachée à une autre campagne.", false);
            }
            if (!campaign.offerCodes.contains(offerCode)) {
                campaign.offerCodes.add(offerCode);
            }
        } else {
            campaign.offerCodes.remove(offerCode);
        }
        campaignService.update(campaign);
        campaignFeedProjector.project();
        LOGGER.info("Exiting method offers");
        return AdminForms.redirect(fichePath(code), "Offres de " + code + " mises à jour.", true);
    }

    /**
     * Duplicates a campaign (GB-01-06-15).
     *
     * @param form the posted form (code, newCode, withOffers)
     * @return a 303 redirect to the copy's fiche on success, or back on error
     */
    @POST
    @Path("/duplicate")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response duplicate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method duplicate");
        String code = AdminForms.text(form, "code");
        String newCode = AdminForms.text(form, "newCode");
        boolean withOffers = AdminForms.checkbox(form, "withOffers");
        if (newCode == null) {
            LOGGER.info("Exiting method duplicate: no new code");
            return AdminForms.redirect(fichePath(code), "Le nouveau code est obligatoire.", false);
        }
        if (Campaign.findByCode(newCode) != null) {
            LOGGER.info("Exiting method duplicate: duplicate");
            return AdminForms.redirect(fichePath(code),
                    "Une campagne porte déjà le code " + newCode + ".", false);
        }
        try {
            campaignService.duplicate(code, newCode, withOffers, "-2");
        } catch (IllegalArgumentException invalid) {
            LOGGER.info("Exiting method duplicate: error");
            return AdminForms.redirect(fichePath(code), message(invalid), false);
        }
        campaignFeedProjector.project();
        LOGGER.info("Exiting method duplicate");
        return AdminForms.redirect(fichePath(newCode), "Campagne " + newCode + " créée par duplication.",
                true);
    }

    /**
     * Fills a campaign's editable fields from a posted form, mapping blank
     * cells to open bounds and unset ceilings. The code is not touched here.
     *
     * @param campaign the campaign to fill
     * @param form the posted form
     */
    private void applyForm(Campaign campaign, MultivaluedMap<String, String> form) {
        campaign.label = AdminForms.text(form, "label");
        campaign.validFrom = AdminForms.day(AdminForms.text(form, "validFrom"));
        campaign.validTo = AdminForms.day(AdminForms.text(form, "validTo"));
        campaign.notes = AdminForms.text(form, "notes");
        campaign.maxAmount = amount(AdminForms.text(form, "maxAmount"));
        campaign.maxApplications = integer(AdminForms.text(form, "maxApplications"));
        campaign.perStore = AdminForms.checkbox(form, "perStore");
    }

    /**
     * Parses an amount ceiling, a blank cell meaning "no ceiling".
     *
     * @param raw the posted value, possibly null
     * @return the amount at scale 2, or null when blank or unreadable
     */
    private BigDecimal amount(String raw) {
        BigDecimal value = AdminForms.amount(raw);
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Parses an applications ceiling, a blank cell meaning "no ceiling".
     *
     * @param raw the posted value, possibly null
     * @return the integer, or null when blank or unreadable
     */
    private Integer integer(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Builds the fiche path of a campaign, its code carried as a query parameter.
     *
     * @param code the campaign code
     * @return the fiche path
     */
    private String fichePath(String code) {
        return PAGE + "/fiche?code=" + (code == null ? "" : code);
    }

    /**
     * Turns a validation exception's comma-joined error keys into a readable
     * French message.
     *
     * @param invalid the validation exception
     * @return the operator-facing message
     */
    private String message(IllegalArgumentException invalid) {
        return "Campagne refusée : " + invalid.getMessage();
    }
}
