package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.supervision.SurveyTriggerService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Recognizes the SURVEY code-barre (LC-03-07-03): a code that carries the
 * survey function and the id of the survey to trigger. The id is asked from
 * the store node — the survey and its answers are the node's — and the banner
 * EXP-89 already built shows it.
 *
 * <p>Works during a transaction and out of one: the only guard is the lock,
 * like every sale-screen gesture. The pattern is administered by the
 * deployment ({@code scan.pattern.survey}), one CAPTURING GROUP naming the
 * survey id: {@code SONDAGE-12} carries the id 12.
 */
@ApplicationScoped
@Priority(1)
public class SurveyScanHandler implements ScanContext.ScanHandler {

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /**
     * Recognition pattern of the survey code, as the deployment states it — one
     * capturing group must carry the survey id.
     */
    @ConfigProperty(name = "scan.pattern.survey", defaultValue = "^SONDAGE-(\\d{1,6})$")
    String surveyPattern;

    /** The ask itself: the channel's caisse end (LC-03-07-03). */
    @Inject
    SurveyTriggerService surveyTriggerService;

    /**
     * Triggers the survey the scanned code names.
     *
     * @param ctx the token walking the chain
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) {
            return;
        }
        // A survey code is a shop gesture, not a sale one: the register only
        // needs to be unlocked, and the code carries its meaning whether a
        // transaction is running or not.
        if (ctx.state.isLocked()) {
            return;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(surveyPattern).matcher(ctx.code);
        if (!matcher.matches()) {
            return;
        }
        try {
            long surveyId = Long.parseLong(matcher.group(1));
            surveyTriggerService.fireByScan(ctx.state, surveyId);
            ctx.handled = true;
        } catch (NumberFormatException | IllegalStateException e) {
            // A pattern with no id group is a deployment defect, not a lane
            // error: the code falls through to the next handler rather than
            // breaking the scan.
        }
    }
}
