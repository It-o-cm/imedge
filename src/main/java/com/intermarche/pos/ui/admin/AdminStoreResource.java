package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.jboss.logging.Logger;

/**
 * The store back office's POINT-OF-SALE INFORMATION page
 * ({@code /admin/store}, BO-03-12-06): name, address, VAT number, SIRET,
 * phone and bank account of THIS node's own {@link Store} row.
 * <p>
 * The PDV number ({@code Store.code}) is shown read-only: its assignment is
 * an import/central concern (BO-02-05-01's global-uniqueness numbering,
 * still an open arbitrage), never a field this screen writes. Creation of
 * the row itself stays on the existing import/GraphQL surfaces
 * ({@code /stores/import}, {@code StoreResource}) — this page only edits an
 * EXISTING row, matching the "non modifiable par le Point De Vente" clause
 * of the requirement.
 * <p>
 * Same node-local reach as {@link Store} itself: this data sits outside the
 * centralized referential pull, so editing it here only ever changes the
 * row of the node this screen is opened on.
 * <p>
 * Access reserved to ADMIN, like the parameters page.
 */
@Path("/admin/store")
public class AdminStoreResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminStoreResource.class);

    /** The point-of-sale information page template. */
    @Inject
    @Location("admin-store")
    Template adminStore;

    /**
     * Shows the point-of-sale information page.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page; {@code store} is null when this node has no row yet
     */
    @GET
    @RolesAllowed("ADMIN")
    public TemplateInstance storePage(@QueryParam("notice") String notice,
                                       @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method storePage with notice: " + notice + ", noticeOk: " + noticeOk);
        Store store = currentStore();
        if (store != null && store.address == null) {
            store.address = new Address();
        }
        LOGGER.info("Exiting method storePage");
        return adminStore.data("store", store)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Saves the editable point-of-sale fields on the existing row — the PDV
     * number is never read from the form.
     *
     * @param form the posted form
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        Store store = currentStore();
        if (store == null) {
            LOGGER.info("Exiting method save");
            return redirect("Aucun point de vente sur ce nœud : importez-le avant de le configurer.", false);
        }
        store.name = trimmed(form, "name", store.name);
        if (store.address == null) {
            store.address = new Address();
        }
        store.address.streetLine1 = trimmed(form, "streetLine1", store.address.streetLine1);
        store.address.streetLine2 = trimmed(form, "streetLine2", store.address.streetLine2);
        store.address.postalCode = trimmed(form, "postalCode", store.address.postalCode);
        store.address.city = trimmed(form, "city", store.address.city);
        store.address.country = trimmed(form, "country", store.address.country);
        store.vatNumber = trimmed(form, "vatNumber", store.vatNumber);
        store.siret = trimmed(form, "siret", store.siret);
        store.phone = trimmed(form, "phone", store.phone);
        store.bankAccountNumber = trimmed(form, "bankAccountNumber", store.bankAccountNumber);
        LOGGER.info("Exiting method save");
        return redirect("Informations du point de vente enregistrées.", true);
    }

    /**
     * Returns the single store row of this node.
     *
     * @return the local store row, or null when this node has none yet
     */
    private Store currentStore() {
        return Store.<Store>findAll().firstResult();
    }

    /**
     * Reads a posted field, trimmed, falling back to the current value when
     * the field is absent from the form.
     *
     * @param form the posted form
     * @param key the field name
     * @param current the value to keep when the field is missing
     * @return the trimmed posted value, or the current value
     */
    private String trimmed(MultivaluedMap<String, String> form, String key, String current) {
        String raw = form.getFirst(key);
        return raw == null ? current : raw.trim();
    }

    /**
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/store?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
