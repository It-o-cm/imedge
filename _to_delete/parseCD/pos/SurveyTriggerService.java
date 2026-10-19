package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Fires the back-office survey at the three TRANSACTION moments the
 * questionnaire administers (LC-03-07-01, -02 and -03): the beginning of a
 * transaction, its end, and the scan of a code-barre carrying the survey
 * function and its id.
 *
 * <p>THE DESCENDING CHANNEL IS REUSED, NOT REBUILT: the survey the node
 * answers with is the very PendingMessage the twenty-second poll already
 * deserializes, and the banner that shows it is the one EXP-89 built. What
 * this service adds is the ASK: the register names the moment it just reached
 * — or the id the scanned code carries — and the node answers the survey
 * administered for it, or nothing.
 *
 * <p>Placement: ui.supervision, beside the poller it completes — it reads the
 * register state and serves the sale screen's banner.
 */
@ApplicationScoped
public class SurveyTriggerService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SurveyTriggerService.class);

    /** The pull client toward the node — the channel's caisse end. */
    @Inject
    BackOfficeMessageClient client;

    /** Applies a fetched message to the shared sale state. */
    @Inject
    com.intermarche.pos.ui.home.HomeService homeService;

    /** The shared POS state, touched so the ask surfaces the banner. */
    @Inject
    PosState state;

    /**
     * Asks for the survey administered for the BEGINNING of the transaction
     * (LC-03-07-01): called from the cart's post-mutation funnel, it asks
     * ONCE per transaction — at the first article rung up — and never again
     * until the ticket is cleared. Tolerant by construction: a node that does
     * not answer leaves the sale exactly as it was.
     *
     * @param posState the current POS state
     */
    public void maybeFireOnTransactionStart(PosState posState) {
        LOGGER.info("Entering method maybeFireOnTransactionStart with posState: " + posState);
        if (posState.surveyTransactionStartAsked || posState.ticket.items.isEmpty()) {
            LOGGER.info("Exiting method maybeFireOnTransactionStart");
            return;
        }
        posState.surveyTransactionStartAsked = true;
        BackOfficeMessageClient.PendingMessage message = client.fetchPending("TRANSACTION_START");
        if (message != null) {
            homeService.receiveBackOfficeMessage(message.text(), surveyOf(message));
        }
        posState.touch();
        LOGGER.info("Exiting method maybeFireOnTransactionStart");
    }

    /**
     * Asks for the survey administered for the END of the transaction
     * (LC-03-07-02): called where the paid ticket closes, once per closed
     * sale. Tolerant like the start: a silent node is a lane that simply was
     * asked nothing.
     *
     * @param posState the current POS state
     */
    public void fireOnTransactionEnd(PosState posState) {
        LOGGER.info("Entering method fireOnTransactionEnd with posState: " + posState);
        BackOfficeMessageClient.PendingMessage message = client.fetchPending("TRANSACTION_END");
        if (message != null) {
            homeService.receiveBackOfficeMessage(message.text(), surveyOf(message));
        }
        posState.touch();
        LOGGER.info("Exiting method fireOnTransactionEnd");
    }

    /**
     * Asks for the survey a SCANNED code-barre names (LC-03-07-03): the code
     * carries the survey function and the administered id, and the register
     * asks for exactly that. Works during a transaction and out of one — the
     * handler only guards the lock.
     *
     * @param posState the current POS state
     * @param surveyId the administered id the scanned code carries
     */
    public void fireByScan(PosState posState, long surveyId) {
        LOGGER.info("Entering method fireByScan with posState: " + posState + ", surveyId: " + surveyId);
        BackOfficeMessageClient.PendingMessage message = client.fetchSurvey(surveyId);
        if (message != null) {
            homeService.receiveBackOfficeMessage(message.text(), surveyOf(message));
        }
        posState.touch();
        LOGGER.info("Exiting method fireByScan");
    }

    /**
     * Builds the survey object of a fetched message, or null for a plain
     * notice — the same mapping the poller performs, kept beside the ask so
     * the two never disagree about what the channel carries.
     *
     * @param message the message pulled from the node
     * @return the survey, or null when the message is a plain notice
     */
    private PosState.BackOfficeSurvey surveyOf(BackOfficeMessageClient.PendingMessage message) {
        if (message.question() == null || message.question().isBlank()) {
            return null;
        }
        PosState.BackOfficeSurvey survey = new PosState.BackOfficeSurvey();
        survey.question = message.question().trim();
        if (message.options() != null) {
            for (String raw : message.options().split(";")) {
                if (!raw.isBlank()) {
                    survey.options.add(raw.trim());
                }
            }
        }
        survey.responseType = message.responseType();
        survey.required = message.required();
        survey.postalFormat = message.postalFormat();
        if (message.postalCodes() != null) {
            for (String raw : message.postalCodes().split(";")) {
                if (!raw.isBlank()) {
                    survey.postalCodes.add(raw.trim());
                }
            }
        }
        return survey;
    }
}
