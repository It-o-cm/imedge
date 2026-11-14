package com.intermarche.edge.ui.admin;

import com.intermarche.edge.service.sync.store.ReferentialReleaseService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * What the back-office layout tells about the programmed deployments
 * (BO-03-12-03, BO-03-12-04), on every page: the date the signed-in
 * administrator armed for his changes, and how many domains are held — so
 * nobody edits an article believing it reaches the registers at once while it
 * waits for its date.
 */
@Named("releaseBanner")
@RequestScoped
public class ReleaseBanner {

    /** The minute format of the banner. */
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** The programmed deployment engine. */
    @Inject
    ReferentialReleaseService releaseService;

    /** The signed-in administrator. */
    @Inject
    SecurityIdentity identity;

    /**
     * Returns the date the administrator armed for his next changes.
     *
     * @return the minute shown, empty when his changes are immediate or nobody is signed in
     */
    public String getArmedAt() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return "";
        }
        LocalDateTime at = releaseService.armedAt(identity.getPrincipal().getName());
        return at == null ? "" : at.format(SHOWN);
    }

    /**
     * Returns how many domains are held until their date.
     *
     * @return the number of pending releases, 0 when nobody is signed in
     */
    public int getPendingCount() {
        if (identity == null || identity.isAnonymous()) {
            return 0;
        }
        return releaseService.pendingCount();
    }
}
