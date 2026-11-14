package com.intermarche.edge.ui.journal;

import com.intermarche.edge.ui.admin.BackOfficeMessages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Words the technical events of the functional journal and of the live watch
 * (BO-04-01, BO-04-03-07, BO-04-03-14) in the page's language.
 * <p>
 * An event's detail is a short French text written by whoever recorded it —
 * a register, the store node, the back office — and it often carries machine
 * codes: an action code ({@code DRAWER_FORCE_OPEN par mcurie}), an alert rule
 * ({@code article:3300000000012 YESNO}), a tender ({@code CASH 6.00}), an ISO
 * date ({@code 2026-10-25}). Shown as recorded, the journal reads codes and
 * French on a Dutch or Portuguese page. This wording reads the shapes the
 * suite records and rebuilds them from labels of the bundles; any other text
 * goes through the bundles' sentences, its dates, decimals and action codes
 * being written the way the page reads them.
 */
@ApplicationScoped
@Named("eventWording")
public class EventWording {

    /** Prefix of the action labels (a gesture a register asks an aval for). */
    static final String ACTION_PREFIX = "t.s21-act-";

    /** Prefix of the other codes an event carries (functions, decisions). */
    static final String CODE_PREFIX = "t.rs21-code-";

    /** Prefix of the alert-rule target kinds. */
    static final String TARGET_PREFIX = "t.rs21-target-";

    /** Prefix of the alert-rule decisions. */
    static final String DECISION_PREFIX = "t.rs21-decision-";

    /** Prefix of the tender labels. */
    static final String TENDER_PREFIX = "t.x21-tender-";

    /** Prefix of the period labels. */
    static final String PERIOD_PREFIX = "t.x21-fs-period-";

    /** Prefix of the store period-close statuses. */
    static final String CLOSE_PREFIX = "t.rs21-close-";

    /** Prefix of the lock reasons. */
    static final String LOCK_PREFIX = "t.rs21-lock-";

    /** The events the back office itself records, with no register behind them. */
    static final Set<String> BACK_OFFICE_TYPES = Set.of("BACK_OFFICE_SIGNED_IN", "BACK_OFFICE_MENU_OPENED");

    /** The lock reasons a register records as the whole detail. */
    static final Set<String> LOCK_REASONS = Set.of("MANUEL", "AUTO");

    /** A text ended by an upper-case code (a line and its price gesture, a ticket and its tender). */
    static final Pattern TRAILING_CODE = Pattern.compile("^(.+) ([A-Z][A-Z0-9_]*)$");

    /** A text led by an upper-case code (a payment method and its amount). */
    static final Pattern LEADING_CODE = Pattern.compile("^([A-Z][A-Z0-9_]*)( .*)?$");

    /** An aval: the gesture's code, its figures, the one who gave or refused it. */
    static final Pattern ENDORSEMENT = Pattern.compile("^([A-Z][A-Z0-9_]*_[A-Z0-9_]*[A-Z0-9])((?: \\S+)*?) par (\\S+)$");

    /** An alert rule's target, possibly followed by the decision taken. */
    static final Pattern ALERT = Pattern.compile(
            "^(function|payment|article|barcode|offline|expired)(?::(.*?))?(?: (YESNO|SUPERVISOR|BACKOFFICE|BLOCK|OUI|NON))?$");

    /** An alert rule's target followed by the message the rule shows. */
    static final Pattern ALERT_MESSAGE = Pattern.compile(
            "^(function|payment|article|barcode|offline|expired)(?::(.*?))? — (.*)$");

    /** A drawer opened outside a sale, with the one who gave the aval. */
    static final Pattern AVAL = Pattern.compile("^(.*) \\(aval (.+)\\)$");

    /** A refund: the original ticket, the tender, the amount and the reason. */
    static final Pattern REFUND = Pattern.compile(
            "^([A-Z0-9]+-\\d+) ([A-Z][A-Z0-9_]*) (-?\\d+(?:\\.\\d+)?)(?: MOTIF:(.*))?$");

    /** A session opened with its float. */
    static final Pattern FLOAT = Pattern.compile("^(\\S+) fond (-?\\d+(?:\\.\\d+)?)$");

    /** A back-office screen opened: who, which menu, which path. */
    static final Pattern MENU = Pattern.compile("^(\\S+) : (.+) \\((/\\S*)\\)$");

    /** A back-office sign-in. */
    static final Pattern SIGN_IN = Pattern.compile("^connexion (\\S+)$");

    /** A duplicate of a ticket and its rank. */
    static final Pattern DUPLICATE = Pattern.compile("^(\\S+) n°(\\d+)$");

    /** A code and the amount it bears (a compensated coupon). */
    static final Pattern CODE_AMOUNT = Pattern.compile("^(\\d{6,}) (-?\\d+\\.\\d+)$");

    /** The store's consolidated period close. */
    static final Pattern STORE_CLOSE = Pattern.compile(
            "^Clôture consolidée du point de vente du (\\d{4}-\\d{2}-\\d{2}) : ([A-Z_]+)$");

    /** The end of a period and the periods it closed. */
    static final Pattern PERIOD_END = Pattern.compile("^Fin de période du (\\d{4}-\\d{2}-\\d{2}) : \\[(.*)\\]$");

    /** A back-office decision on a register's request, and the request it answered. */
    static final Pattern HANDLED = Pattern.compile("^(.+? par .+?) — (.+?) ([A-Z][A-Z0-9_]*_[A-Z0-9_]*[A-Z0-9])$");

    /** An ISO date, possibly with its time. */
    static final Pattern ISO_DATE = Pattern.compile(
            "\\b(\\d{4})-(\\d{2})-(\\d{2})(?:T(\\d{2}):(\\d{2})(?::\\d{2}(?:\\.\\d+)?)?)?\\b");

    /** A decimal written the machine way. */
    static final Pattern DECIMAL = Pattern.compile("(?<![\\w.,/-])-?\\d+\\.\\d{1,4}(?![\\w.-])");

    /** An upper-case machine code with at least one underscore. */
    static final Pattern CODE = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b");

    /** The page's bundles. */
    @Inject
    BackOfficeMessages i18n;

    /**
     * The register column of an event: the back office for the events it
     * records itself, the register otherwise.
     *
     * @param type the event type's name
     * @param terminal the recorded register
     * @return the register as the page shows it
     */
    public String terminal(String type, String terminal) {
        return terminal(i18n, type, terminal);
    }

    /**
     * The register column of an event, on given bundles.
     *
     * @param i18n the page's bundles
     * @param type the event type's name, possibly null
     * @param terminal the recorded register, possibly null
     * @return the register as the page shows it
     */
    public static String terminal(BackOfficeMessages i18n, Object type, Object terminal) {
        return BACK_OFFICE_TYPES.contains(type) ? i18n.get("t.rs21-back-office") : terminal == null ? "" : terminal.toString();
    }

    /**
     * The code column of an event, worded.
     *
     * @param type the event type's name
     * @param code the recorded code, possibly null
     * @return the code as the page shows it
     */
    public String code(String type, String code) {
        return code(i18n, code);
    }

    /**
     * The detail of an event, worded.
     *
     * @param type the event type's name
     * @param detail the recorded detail, possibly null
     * @return the detail as the page shows it
     */
    public String detail(String type, String detail) {
        return detail(i18n, type, detail);
    }

    /**
     * Words a payment recorded as its method code followed by its amount
     * ({@code CASH 1,00}): the method by its label, the amount as recorded.
     *
     * @param payment the recorded payment, possibly null
     * @return the worded payment
     */
    public String payment(String payment) {
        Matcher m = payment == null ? null : LEADING_CODE.matcher(payment);
        if (m == null || !m.matches()) {
            return payment == null ? "" : payment;
        }
        return i18n.code(TENDER_PREFIX, m.group(1)) + (m.group(2) == null ? "" : m.group(2));
    }

    /**
     * Words a recorded code: an alert target by its kind, an upper-case code
     * by the label of an action, a function, a decision or a tender; any other
     * code (an EAN, a ticket, a screen) as recorded.
     *
     * @param i18n the page's bundles
     * @param code the recorded code, possibly null
     * @return the worded code
     */
    public static String code(BackOfficeMessages i18n, String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        Matcher alert = ALERT.matcher(code.trim());
        if (alert.matches() && alert.group(3) == null) {
            return target(i18n, alert.group(1), alert.group(2));
        }
        if (!code.trim().matches("[A-Z][A-Z0-9_]*")) {
            return code;
        }
        return label(i18n, code.trim());
    }

    /**
     * Words a recorded detail.
     *
     * @param i18n the page's bundles
     * @param type the event type's name, possibly null
     * @param detail the recorded detail, possibly null
     * @return the worded detail
     */
    public static String detail(BackOfficeMessages i18n, String type, String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        String text = detail.trim();
        if (LOCK_REASONS.contains(text)) {
            return i18n.code(LOCK_PREFIX, text);
        }
        Matcher m = TRAILING_CODE.matcher(text);
        if ("PRICE_MODIFIED".equals(type) && m.matches()) {
            return m.group(1) + " — " + label(i18n, m.group(2));
        }
        if ("PAYMENT_CANCELLED".equals(type) && m.matches()) {
            return m.group(1) + " — " + i18n.code(TENDER_PREFIX, m.group(2));
        }
        m = ENDORSEMENT.matcher(text);
        if (m.matches()) {
            return action(i18n, m.group(1)) + numbers(i18n, m.group(2)) + " — " + m.group(3);
        }
        m = ALERT.matcher(text);
        if (m.matches()) {
            String target = target(i18n, m.group(1), m.group(2));
            return m.group(3) == null ? target : target + " — " + i18n.code(DECISION_PREFIX, m.group(3));
        }
        m = AVAL.matcher(text);
        if (m.matches()) {
            return detail(i18n, type, m.group(1)) + " (" + i18n.get("t.rs21-aval") + " " + m.group(2) + ")";
        }
        m = REFUND.matcher(text);
        if (m.matches()) {
            return m.group(1) + " — " + i18n.code(TENDER_PREFIX, m.group(2)) + " " + money(i18n, m.group(3))
                    + (m.group(4) == null ? "" : " — " + i18n.get("t.rs21-motif") + " : " + i18n.text(m.group(4)));
        }
        m = FLOAT.matcher(text);
        if (m.matches()) {
            return m.group(1) + " — " + i18n.get("t.rs21-fond") + " " + money(i18n, m.group(2));
        }
        m = MENU.matcher(text);
        if (m.matches()) {
            return m.group(1) + " : " + i18n.text(m.group(2)) + " (" + m.group(3) + ")";
        }
        m = SIGN_IN.matcher(text);
        if (m.matches()) {
            return i18n.get("t.rs21-connexion") + " " + m.group(1);
        }
        m = DUPLICATE.matcher(text);
        if (m.matches()) {
            return m.group(1) + " — " + i18n.get("t.rs21-duplicata") + " " + m.group(2);
        }
        m = CODE_AMOUNT.matcher(text);
        if (m.matches()) {
            return m.group(1) + " — " + money(i18n, m.group(2));
        }
        m = STORE_CLOSE.matcher(text);
        if (m.matches()) {
            return fill(i18n.get("p.rs21-store-close"), date(m.group(1)), i18n.code(CLOSE_PREFIX, m.group(2)));
        }
        m = PERIOD_END.matcher(text);
        if (m.matches()) {
            List<String> periods = new ArrayList<>();
            for (String period : m.group(2).split(",")) {
                if (!period.isBlank()) {
                    periods.add(i18n.code(PERIOD_PREFIX, period.trim()));
                }
            }
            return fill(i18n.get("p.fin-de-periode-du-x-x"), date(m.group(1)), String.join(", ", periods));
        }
        m = HANDLED.matcher(text);
        if (m.matches()) {
            return i18n.text(m.group(1)) + " — " + i18n.text(m.group(2)) + " " + action(i18n, m.group(3));
        }
        return plain(i18n, sentences(i18n, normalized(i18n, text)));
    }

    /**
     * Translates a text through the bundles, whole or part by part — the
     * parts a dash or a colon separates, so that a decision followed by the
     * reason it gives reads in the page's language. The parts win when each
     * of them is known; the whole wins when one part is not and the bundles
     * know the whole sentence.
     *
     * @param i18n the page's bundles
     * @param text the normalized French text
     * @return the translated text
     */
    static String sentences(BackOfficeMessages i18n, String text) {
        String whole = i18n.text(text);
        List<String> parts = new ArrayList<>();
        boolean unknown = false;
        for (String dashed : text.split(" — ", -1)) {
            List<String> pieces = new ArrayList<>();
            for (String piece : dashed.split(" : ", -1)) {
                String translated = i18n.text(piece);
                unknown = unknown || (translated.equals(piece) && piece.matches(".*\\p{Ll}.*"));
                pieces.add(translated);
            }
            parts.add(String.join(" : ", pieces));
        }
        return unknown && !whole.equals(text) ? whole : String.join(" — ", parts);
    }

    /**
     * Words a gesture an alert names: its target by its kind, followed by the
     * message its rule shows; null when the message names no alert target.
     *
     * @param i18n the page's bundles
     * @param message the call's message, possibly null
     * @return the worded gesture, or null
     */
    public static String alertMessage(BackOfficeMessages i18n, String message) {
        Matcher m = message == null ? null : ALERT_MESSAGE.matcher(message.trim());
        return m == null || !m.matches() ? null : target(i18n, m.group(1), m.group(2)) + " — " + m.group(3);
    }

    /**
     * Words an alert rule's target: its kind, then what it names — a tender
     * by its label, a family by its code, anything else as administered.
     *
     * @param i18n the page's bundles
     * @param kind the kind of target
     * @param value what the target names, possibly null
     * @return the worded target
     */
    static String target(BackOfficeMessages i18n, String kind, String value) {
        String named = value == null ? "" : value.trim();
        if ("payment".equals(kind) && !named.isEmpty()) {
            return i18n.get(TARGET_PREFIX + kind) + " " + i18n.code(TENDER_PREFIX, named.toUpperCase());
        }
        if ("article".equals(kind) && named.length() > 1 && (named.charAt(0) == 'F' || named.charAt(0) == 'f')
                && Character.isDigit(named.charAt(1))) {
            return i18n.get(TARGET_PREFIX + "family") + " " + named.substring(1);
        }
        return named.isEmpty() ? i18n.get(TARGET_PREFIX + kind) : i18n.get(TARGET_PREFIX + kind) + " " + named;
    }

    /**
     * The label of an action code, whole or by its family — the code with its
     * trailing parts dropped one by one ({@code REFUND_CASH_7} reads as a
     * refund) —, the code itself when no label is known.
     *
     * @param i18n the page's bundles
     * @param code the action code
     * @return the label, or the code
     */
    public static String action(BackOfficeMessages i18n, String code) {
        String family = code;
        while (true) {
            String label = i18n.code(ACTION_PREFIX, family);
            if (!label.equals(family)) {
                return label;
            }
            int cut = family.lastIndexOf('_');
            if (cut <= 0) {
                return code;
            }
            family = family.substring(0, cut);
        }
    }

    /**
     * The label of any upper-case code an event carries: an action, a
     * function or a decision, a tender; the code itself when none is known.
     *
     * @param i18n the page's bundles
     * @param code the code
     * @return the label, or the code
     */
    static String label(BackOfficeMessages i18n, String code) {
        String label = i18n.code(ACTION_PREFIX, code);
        if (label.equals(code)) {
            label = i18n.code(CODE_PREFIX, code);
        }
        if (label.equals(code)) {
            label = i18n.code(TENDER_PREFIX, code);
        }
        return label;
    }

    /**
     * Writes the machine decimals of a text the way the page reads them.
     *
     * @param i18n the page's bundles
     * @param text the text, possibly empty
     * @return the text with its decimals formatted
     */
    public static String numbers(BackOfficeMessages i18n, String text) {
        Matcher m = DECIMAL.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(money(i18n, m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Writes an amount with two decimals in the page's separator.
     *
     * @param i18n the page's bundles
     * @param raw the machine amount
     * @return the formatted amount
     */
    static String money(BackOfficeMessages i18n, String raw) {
        return i18n.money(new BigDecimal(raw));
    }

    /**
     * Writes an ISO date as the back office reads dates.
     *
     * @param iso the ISO date
     * @return the date as dd/MM/yyyy
     */
    static String date(String iso) {
        return iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
    }

    /**
     * Writes the ISO dates and machine decimals of a French text the way the
     * back office reads them, before the text goes through the bundles.
     *
     * @param i18n the page's bundles
     * @param text the text
     * @return the normalized text
     */
    static String normalized(BackOfficeMessages i18n, String text) {
        Matcher m = ISO_DATE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String day = m.group(3) + "/" + m.group(2) + "/" + m.group(1);
            m.appendReplacement(out, m.group(4) == null ? day : day + " " + m.group(4) + ":" + m.group(5));
        }
        m.appendTail(out);
        return numbers(i18n, out.toString());
    }

    /**
     * Replaces the action codes left in a translated text by their labels.
     *
     * @param i18n the page's bundles
     * @param text the translated text
     * @return the text with its codes worded
     */
    static String plain(BackOfficeMessages i18n, String text) {
        Matcher m = CODE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(label(i18n, m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Inserts values into a bundle sentence.
     *
     * @param pattern the sentence with {0}, {1}
     * @param values the values, in order
     * @return the sentence filled
     */
    static String fill(String pattern, String... values) {
        String filled = pattern;
        for (int i = 0; i < values.length; i++) {
            filled = filled.replace("{" + i + "}", values[i]);
        }
        return filled;
    }
}
