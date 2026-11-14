package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.ui.admin.BackOfficeMessages;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Words a register's call for the supervision screens in the language of the
 * back office (BO-04-02-12, LC-01-05-08): the register sends its reason as a
 * French sentence and the gesture it holds as an action code, which the board
 * shows translated rather than raw.
 */
final class CallWording {

    /** An action code at the head of a gesture, its free-text tail kept. */
    private static final Pattern ACTION = Pattern.compile("^([A-Z][A-Z0-9_]*[A-Z0-9])(\\s.*)?$");

    /**
     * Not instantiable.
     */
    private CallWording() {
    }

    /**
     * The reason of a call in the board's language: a reason the register
     * fixes is translated, an administered one is shown as typed.
     *
     * @param i18n the back-office messages
     * @param reason the reason sent, possibly null
     * @return the reason to show, empty for none
     */
    static String reason(BackOfficeMessages i18n, String reason) {
        return i18n.text(reason);
    }

    /**
     * The gesture of a call in the board's language: its action code read as
     * its label — whole, or without the identifier the register appends
     * ({@code CANCEL_LINE_…}, {@code PRINT_BADGE_…}) — its figures written the
     * way the page reads decimals; an alert's target ({@code payment:CHEQUE — …})
     * read by its kind and label; any other text is shown as sent.
     *
     * @param i18n the back-office messages
     * @param message the gesture sent, possibly null
     * @return the gesture to show, empty for none
     */
    static String gesture(BackOfficeMessages i18n, String message) {
        if (message == null) {
            return "";
        }
        String alert = com.intermarche.edge.ui.journal.EventWording.alertMessage(i18n, message);
        if (alert != null) {
            return alert;
        }
        Matcher matcher = ACTION.matcher(message);
        if (!matcher.matches()) {
            return message;
        }
        String tail = matcher.group(2) == null ? "" : matcher.group(2);
        return com.intermarche.edge.ui.journal.EventWording.action(i18n, matcher.group(1))
                + com.intermarche.edge.ui.journal.EventWording.numbers(i18n, tail);
    }
}
