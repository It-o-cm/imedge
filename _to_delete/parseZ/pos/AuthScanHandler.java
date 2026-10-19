package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Recognizes employee badges ({@code scan.pattern.badge}), first in the
 * chain: a badge must never fall through to the catalog.
 * <p>
 * Three destinations, one precedence: an ACTIVE ENDORSEMENT modal wins over
 * the lock screen (the manager badging over the cashier's shoulder is the more
 * immediate gesture), the lock screen comes second, and on a register in use
 * the operator's OWN badge asks for the close (LC-01-02-03). Any OTHER badge
 * scanned there is deliberately IGNORED: no automatic operator switch,
 * changing hands goes through a close first.
 */
@ApplicationScoped
@Priority(0) // Priorité maximale
public class AuthScanHandler implements ScanContext.ScanHandler {

    /** Recognition regex of employee badges. */
    @ConfigProperty(name = "scan.pattern.badge")
    String badgePattern;

    /** The back-office parameters (badge-scan activation — BO-10-02-29/30). */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Deposits a recognized badge into the endorsement or lock mailbox.
     *
     * @param ctx the token walking the chain
     */
    /**
     * The administered supervisor prefix, trimmed and never null
     * (BO-10-02-32).
     *
     * @return the prefix, blank when the shop administers none
     */
    private String safePrefix() {
        String prefix = posSettingsService.supervisorBadgePrefix();
        return prefix == null ? "" : prefix.trim();
    }

    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        // BO-10-02-29/30: when badge scanning is disabled, a badge is not
        // consumed here — it walks on rather than taking the post or endorsing,
        // so the operator keys an identifier by hand instead.
        // BO-10-02-32: the supervisor barcode carries a PREFIX the keyboard
        // does not. While one is administered, an identification that endorses
        // or supervises is accepted only from a prefixed code, and the bare
        // code — the one anybody could key — no longer endorses anything. The
        // prefix is stripped before the badge is read, so the identifier stored
        // stays the operator's own. Blank prefix: nothing changes.
        String prefix = safePrefix();
        boolean prefixed = !prefix.isEmpty() && ctx.code.startsWith(prefix)
                && ctx.code.length() > prefix.length();
        String badge = prefixed ? ctx.code.substring(prefix.length()) : ctx.code;
        if (posSettingsService.badgeScanEnabled() && badge.matches(badgePattern)) {
            PosState state = ctx.state;
            boolean mayIdentifySupervisor = prefix.isEmpty() || prefixed;
            boolean mayTakeThePost = prefix.isEmpty() || !prefixed;

            // 1. Priorité : Endossement (Manager)
            if (state.endorsement.active && mayIdentifySupervisor) {
                state.endorsement.setScannedBadge(badge);
                state.touch();
                ctx.handled = true;
            }
            // 2. Sinon : un ECRAN DEMANDE UNE IDENTIFICATION SUPERVISEUR (le
            // volet partage de la fermeture, de l'ecart de comptage, de
            // l'annulation d'un ticket paye, du deverrouillage force). Passer
            // un badge et saisir son identifiant doivent TOUJOURS avoir le
            // meme effet : la ou un identifiant se tape, un badge se presente.
            // Ce cas passe AVANT le verrouillage parce que sur l'ecran
            // verrouille les deux sont vrais a la fois, et c'est le volet
            // ouvert qui a la main — l'identification de l'ouverture, elle,
            // n'est pas affichee a ce moment-la. L'armement est porte par le
            // volet lui-meme et expire des qu'il quitte l'ecran, donc un badge
            // etranger reste ignore partout ailleurs.
            else if (state.auth.supervisorBadgeArmed() && mayIdentifySupervisor) {
                state.auth.setSupervisorBadge(badge);
                state.touch();
                ctx.handled = true;
            }
            // 3. Sinon : Écran de verrouillage (Login Caissier)
            else if (state.isLocked() && mayTakeThePost) {
                state.auth.setScannedBadge(badge);
                state.touch();
                ctx.handled = true;
            }
            // 4. Sinon : le badge de l'opérateur EN POSTE ferme la caisse
            // (LC-01-02-03). Le badge est la preuve d'identité, donc aucun mot
            // de passe n'est demandé ; la fermeture elle-même reste soumise à
            // ses propres contrôles (tickets en attente), que la page de
            // fermeture applique.
            else if (mayTakeThePost && badge.equals(state.auth.operatorBadgeId)) {
                state.auth.closeRequestedByBadge = true;
                state.touch();
                ctx.handled = true;
            }
            // 5. Sinon on ignore le badge (pas de changement d'opérateur auto)
        }
    }
}