package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.domain.session.TechnicalEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Journals what the back office DOES with the registers' requests
 * (BO-04-03-14): an acknowledgement, an authorisation, a request sent back to
 * the till, a refusal, a forced close — in the electronic journal, under the
 * register concerned and the operator who decided, so every use of a
 * supervision function is found there with the uses made at the tills.
 * <p>
 * Written in a transaction of its own: the decision has already been taken
 * and sent; a journal momentarily unwritable never undoes it.
 */
@ApplicationScoped
public class SupervisionJournal {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SupervisionJournal.class);

    /**
     * Records one decision of the back office.
     *
     * @param terminalId the register the decision is about
     * @param decision what was decided, e.g. AUTORISATION
     * @param operator who decided
     * @param detail what the request was
     */
    public void handled(String terminalId, String decision, String operator, String detail) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                TechnicalEvent event = new TechnicalEvent();
                event.eventType = TechnicalEvent.EventType.SUPERVISION_HANDLED;
                event.terminalId = terminalId == null ? "?" : terminalId;
                event.eventDate = LocalDateTime.now();
                String text = wording(decision) + " par " + operator
                        + (detail == null || detail.isBlank() ? "" : " — " + detail);
                event.detail = text.length() > 255 ? text.substring(0, 255) : text;
                event.code = decision;
                event.eventUid = UUID.randomUUID().toString();
                event.persist();
            });
        } catch (RuntimeException trouble) {
            LOGGER.warnf("Décision de supervision non journalisée (%s)", trouble.getMessage());
        }
    }

    /**
     * The French words of a decision, which the journal shows in the page's
     * language; a decision without words is written as its code.
     *
     * @param decision the decision's code
     * @return its words
     */
    static String wording(String decision) {
        return switch (decision == null ? "" : decision) {
            case "ACQUITTEMENT" -> "Acquittement";
            case "A_TRAITER_EN_CAISSE" -> "Renvoi en caisse";
            case "REFUS" -> "Refus";
            case "AUTORISATION" -> "Autorisation";
            case "AUTORISATION_REFUSEE" -> "Autorisation refusée (profil)";
            case "FERMETURE_FORCEE" -> "Fermeture forcée";
            default -> String.valueOf(decision);
        };
    }
}
