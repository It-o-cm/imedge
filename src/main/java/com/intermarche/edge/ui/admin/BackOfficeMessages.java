package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.arc.Unremovable;
import io.quarkus.qute.TemplateData;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localises the back office (BO-01-01-04, BO-01-01-05): every label of the
 * screens, and the French texts the resources compute — notices, menu and
 * enum labels, parameter hints — rendered in the language of the operator.
 *
 * <p>SAME MECHANISM AS THE REST OF THE SUITE: a {@code @Named("i18n")} bean
 * reached from the templates as {@code {inject:i18n.get('key')}}, one
 * {@code messages/msg_<lang>.properties} bundle per language, the French
 * bundle being the exhaustive reference and every missing value falling back
 * to it, then to the key itself — a screen shows a wrong label rather than a
 * broken page.
 *
 * <p>THE LANGUAGE, most specific first:
 * <ol>
 *   <li>the language administered for the signed-in operator
 *       ({@code Employee.language}, set on the Accounts screen);</li>
 *   <li>the reference language of this node's echelon (parameter
 *       {@code language.system}, posed on the country, the enseigne or the
 *       point of sale the operator works in);</li>
 *   <li>French.</li>
 * </ol>
 * A language no bundle ships is skipped at every step.
 *
 * <p>TEXTS COMPUTED IN JAVA stay French in the code — the resources and the
 * services keep one language, and their tests their assertions — and are
 * translated where they are SHOWN, by {@link #text(String)}: the French
 * bundle is read backwards, an exact sentence first, then the sentences built
 * by concatenation, stored with {@code {0}}, {@code {1}}… where the code
 * inserts a value. A text no bundle knows is shown as it came.
 *
 * <p>Request-scoped: the language is the operator's, resolved once per
 * request and kept for the hundreds of labels of one page.
 */
@Named("i18n")
@RequestScoped
@TemplateData
@Unremovable
public class BackOfficeMessages {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(BackOfficeMessages.class);

    /** The default language: the exhaustive reference bundle. */
    public static final String DEFAULT_LANGUAGE = "fr";

    /** Where a bundle lives on the classpath, and how its language is named. */
    private static final String BUNDLE_PREFIX = "messages/msg_";

    /** The extension of a bundle file. */
    private static final String BUNDLE_SUFFIX = ".properties";

    /** The parameter holding the reference language of the node's echelon. */
    static final String SYSTEM_LANGUAGE_KEY = "language.system";

    /** A placeholder of a stored sentence: {@code {0}}, {@code {1}}… */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    /** Loaded bundles, keyed by language; a bundle is read once. */
    private static final Map<String, Map<String, String>> BUNDLES = new ConcurrentHashMap<>();

    /** The French bundle read backwards, built once. */
    private static volatile ReverseIndex reverse;

    /** The signed-in operator, if any. */
    @Inject
    SecurityIdentity identity;

    /** The administered parameters, for the echelon's reference language. */
    @Inject
    PosSettingsService posSettingsService;

    /** The language resolved for this request, or null before the first label. */
    private String language;

    /**
     * Builds the messages of a GIVEN language, outside the operator's request:
     * an export written in the language of the page that asked for it, or of
     * the operator who planned it (BO-06-01-02). The language is taken as
     * given instead of being resolved from the signed-in operator, French when
     * no bundle ships it.
     *
     * @param tag the language tag, possibly null or blank
     * @return the messages of that language
     */
    public static BackOfficeMessages in(String tag) {
        BackOfficeMessages messages = new BackOfficeMessages();
        String normalized = normalize(tag);
        messages.language = hasBundle(normalized) ? normalized : DEFAULT_LANGUAGE;
        return messages;
    }

    /**
     * Returns the language the page is rendered in, also used as the
     * {@code lang} attribute of the page.
     *
     * @return the language tag
     */
    public String getLanguage() {
        if (language == null) {
            language = resolve();
        }
        return language;
    }

    /**
     * Returns the localised label of a key.
     *
     * @param key the message key, or null
     * @return the label, the French one as a fallback, the key itself when it
     *         is defined nowhere, an empty string for a null key
     */
    public String get(String key) {
        if (key == null) {
            return "";
        }
        String value = bundle(getLanguage()).get(key);
        if (value == null) {
            value = bundle(DEFAULT_LANGUAGE).get(key);
        }
        return value != null ? value : key;
    }

    /**
     * Returns the localised label of a key for a JavaScript string written
     * between single quotes in an attribute: the straight apostrophe becomes
     * the typographic one, so the label never closes the string.
     *
     * @param key the message key
     * @return the label, safe between single quotes
     */
    public String js(String key) {
        return get(key).replace('\'', '’');
    }

    /**
     * Translates a French text computed by the code — a notice, a label —
     * into the page's language: an exact sentence of the French bundle first,
     * then a sentence built around inserted values.
     *
     * <p>Takes any object, a label being sometimes an enum or a number in the
     * templates: its {@code toString()} is what the page would have shown.
     *
     * @param shown the French text, possibly null
     * @return the translation, or the text itself when the page is French or
     *         no bundle knows it; an empty string for null
     */
    public String text(Object shown) {
        if (shown == null) {
            return "";
        }
        String french = shown.toString();
        String target = getLanguage();
        if (DEFAULT_LANGUAGE.equals(target)) {
            return french;
        }
        ReverseIndex index = reverseIndex();
        String key = index.exact.get(french.trim());
        if (key != null) {
            return get(key);
        }
        for (Sentence sentence : index.sentences) {
            Matcher matcher = sentence.regex.matcher(french.trim());
            if (matcher.matches()) {
                return fill(get(sentence.key), sentence, matcher, value -> label(index, value));
            }
        }
        return french;
    }

    /**
     * Reads the label of a CODE (an enum constant, a node kind, a state) in
     * the page's language: the key is the prefix followed by the code, e.g.
     * {@code i18n.code('t.code-', row.state)} reads {@code t.code-APPLIED}.
     * A code the bundles do not label shows itself, as data would.
     *
     * @param prefix the key prefix of the code family
     * @param code the code, possibly null
     * @return the label, the code itself when unlabelled, empty for null
     */
    public String code(String prefix, Object code) {
        if (code == null) {
            return "";
        }
        String key = prefix + code;
        String label = get(key);
        return label.equals(key) ? code.toString() : label;
    }

    /**
     * Writes an amount in the page's language: two decimals and the decimal
     * separator of that language (a comma in French, Dutch and Portuguese),
     * without grouping, so a price reads "99,00" and never "99.0".
     *
     * <p>Takes any object, an amount reaching the templates as a
     * {@code BigDecimal}, another number or a machine-readable text
     * ({@code "12.50"}): a value that is not a number shows itself.
     *
     * @param amount the amount, possibly null
     * @return the amount written, empty for null
     */
    public String money(Object amount) {
        BigDecimal value = decimal(amount);
        if (value == null) {
            return shown(amount);
        }
        return twoDecimals().format(value);
    }

    /**
     * Writes a rate held as a fraction — a VAT rate {@code 0.2000} — as a
     * percentage in the page's language: {@code 20,00 %}.
     *
     * @param fraction the rate, possibly null
     * @return the percentage written, empty for null, the value itself when it
     *         is not a number
     */
    public String rate(Object fraction) {
        BigDecimal value = decimal(fraction);
        if (value == null) {
            return shown(fraction);
        }
        return twoDecimals().format(value.movePointRight(2)) + " %";
    }

    /**
     * Writes a value already held in percent — a share of the turnover
     * {@code 12.5000} — in the page's language: {@code 12,50 %}.
     *
     * @param percent the percentage, possibly null
     * @return the percentage written, empty for null, the value itself when it
     *         is not a number
     */
    public String percent(Object percent) {
        BigDecimal value = decimal(percent);
        if (value == null) {
            return shown(percent);
        }
        return twoDecimals().format(value) + " %";
    }

    /**
     * Writes a quantity with the administered writing of quantities
     * (BO-10-08-28): a whole quantity without decimals, any other with the
     * administered decimals, the page language's decimal separator.
     *
     * @param quantity the quantity, possibly null
     * @return the quantity written, empty for null, the value itself when it
     *         is not a number
     */
    public String quantity(Object quantity) {
        BigDecimal value = decimal(quantity);
        if (value == null) {
            return shown(quantity);
        }
        return localised(unitFormat().measure(value));
    }

    /**
     * Writes a weight with the administered writing of weights
     * (BO-10-08-29): its decimals and its symbol, {@code 0,035 kg}.
     *
     * @param kilograms the weight, possibly null
     * @return the weight written, empty for null, the value itself when it is
     *         not a number
     */
    public String weight(Object kilograms) {
        BigDecimal value = decimal(kilograms);
        if (value == null) {
            return shown(kilograms);
        }
        return localised(unitFormat().weight(value));
    }

    /**
     * Writes a number that is neither an amount nor a quantity — an exchange
     * rate {@code 1.0850} — with its own decimals, needless trailing zeros
     * dropped, in the page's language: {@code 1,085}.
     *
     * @param number the number, possibly null
     * @return the number written, empty for null, the value itself when it is
     *         not a number
     */
    public String number(Object number) {
        BigDecimal value = decimal(number);
        if (value == null) {
            return shown(number);
        }
        return localised(value.stripTrailingZeros().toPlainString().replace('.', ','));
    }

    /**
     * Reads a value as a number: a {@code BigDecimal} as it is, another
     * number or a text through its decimal writing.
     *
     * @param value the value, possibly null
     * @return the number, or null when the value is null or not a number
     */
    static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal number) {
            return number;
        }
        if (!(value instanceof Number) && !(value instanceof CharSequence)) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Shows a value that is not a number as it came.
     *
     * @param value the value, possibly null
     * @return its text, empty for null
     */
    private static String shown(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * The writing of an amount in the page's language: two decimals, its
     * decimal separator, no grouping, half-up rounding.
     *
     * @return the format
     */
    private DecimalFormat twoDecimals() {
        DecimalFormat format = new DecimalFormat("0.00", symbols());
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }

    /**
     * The number symbols of the page's language.
     *
     * @return the symbols
     */
    private DecimalFormatSymbols symbols() {
        return DecimalFormatSymbols.getInstance(Locale.forLanguageTag(getLanguage()));
    }

    /**
     * Puts the page language's decimal separator in a number the unit format
     * wrote with a French comma.
     *
     * @param written the written number
     * @return the number with the page's separator
     */
    private String localised(String written) {
        return written.replace(',', symbols().getDecimalSeparator());
    }

    /**
     * The administered writing of quantities, the register's historical one
     * when the parameters cannot be read.
     *
     * @return the unit format, never null
     */
    private com.intermarche.edge.domain.catalog.UnitFormat unitFormat() {
        try {
            return com.intermarche.edge.domain.catalog.UnitFormat.orDefault(posSettingsService.unitFormat());
        } catch (RuntimeException unreadable) {
            LOGGER.debugf("Format des quantités illisible (%s)", unreadable.getMessage());
            return com.intermarche.edge.domain.catalog.UnitFormat.DEFAULT;
        }
    }

    /**
     * Translates a computed French text for a JavaScript string written
     * between single quotes in an attribute.
     *
     * @param shown the French text, possibly null
     * @return the translation, safe between single quotes
     */
    public String textJs(Object shown) {
        return text(shown).replace('\'', '’');
    }

    /**
     * Reads a value captured inside a sentence in the page's language: a label
     * the bundles hold whole is translated, any other value — a number, a
     * name, a date — kept as it is.
     *
     * @param index the reverse index of the French texts
     * @param value the captured value
     * @return the translated label, or the value itself
     */
    private String label(ReverseIndex index, String value) {
        String key = value.chars().anyMatch(Character::isLetter) ? index.exact.get(value.trim()) : null;
        return key == null ? value : get(key);
    }

    /**
     * Substitutes the values captured from the French sentence into its
     * translation.
     *
     * @param translated the translated sentence, with its placeholders
     * @param sentence the matched sentence
     * @param matcher the successful match
     * @return the translated sentence with the values inserted
     */
    static String fill(String translated, Sentence sentence, Matcher matcher) {
        return fill(translated, sentence, matcher, java.util.function.UnaryOperator.identity());
    }

    /**
     * Substitutes the values captured from the French sentence into its
     * translation, each value read through a translation of its own — a label
     * inside a sentence (« réponses : Oui / Non ») is translated with it.
     *
     * @param translated the translated sentence, with its placeholders
     * @param sentence the matched sentence
     * @param matcher the successful match
     * @param values the translation of a captured value
     * @return the translated sentence with the values inserted
     */
    static String fill(String translated, Sentence sentence, Matcher matcher,
                       java.util.function.UnaryOperator<String> values) {
        StringBuilder out = new StringBuilder();
        Matcher holes = PLACEHOLDER.matcher(translated);
        int last = 0;
        while (holes.find()) {
            out.append(translated, last, holes.start());
            int index = Integer.parseInt(holes.group(1));
            Integer group = sentence.groups.get(index);
            out.append(group == null ? holes.group() : values.apply(matcher.group(group)));
            last = holes.end();
        }
        out.append(translated.substring(last));
        return out.toString();
    }

    /**
     * Resolves the page's language: the operator's, then the echelon's, then
     * French, skipping any language no bundle ships.
     *
     * @return the language tag
     */
    private String resolve() {
        String operator = normalize(operatorLanguage());
        if (hasBundle(operator)) {
            return operator;
        }
        String system = normalize(systemLanguage());
        return hasBundle(system) ? system : DEFAULT_LANGUAGE;
    }

    /**
     * Reads the language administered for the signed-in operator.
     *
     * @return the language, or null when nobody is signed in, the account is
     *         gone or unreadable, or no language is administered
     */
    private String operatorLanguage() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return null;
        }
        try {
            Employee employee = Employee.findActiveLogin(identity.getPrincipal().getName());
            return employee == null ? null : employee.language;
        } catch (RuntimeException unreadable) {
            LOGGER.debugf("Langue de l'opérateur illisible (%s)", unreadable.getMessage());
            return null;
        }
    }

    /**
     * Reads the reference language of this node's echelon.
     *
     * @return the language, or null when it cannot be read
     */
    private String systemLanguage() {
        try {
            return posSettingsService.value(SYSTEM_LANGUAGE_KEY);
        } catch (RuntimeException unreadable) {
            LOGGER.debugf("Langue de l'échelon illisible (%s)", unreadable.getMessage());
            return null;
        }
    }

    /**
     * Tells whether a bundle exists for a language.
     *
     * @param tag the normalized language tag
     * @return true when that language resolves labels
     */
    static boolean hasBundle(String tag) {
        return !tag.isEmpty() && !bundle(tag).isEmpty();
    }

    /**
     * Reduces a language tag to its lower-case primary subtag
     * ({@code nl-BE} → {@code nl}).
     *
     * @param tag the language tag, possibly null or blank
     * @return the primary subtag, or an empty string
     */
    static String normalize(String tag) {
        if (tag == null || tag.isBlank()) {
            return "";
        }
        String trimmed = tag.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = trimmed.indexOf('-');
        return dash < 0 ? trimmed : trimmed.substring(0, dash);
    }

    /**
     * Returns the bundle of a language, read once from the classpath.
     *
     * @param tag the language tag
     * @return the bundle, empty when the language ships none
     */
    static Map<String, String> bundle(String tag) {
        return BUNDLES.computeIfAbsent(tag, BackOfficeMessages::load);
    }

    /**
     * Reads a bundle from the classpath.
     *
     * @param tag the language tag
     * @return the bundle's entries, empty when there is no such file or it
     *         cannot be read
     */
    static Map<String, String> load(String tag) {
        Map<String, String> entries = new HashMap<>();
        InputStream found = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(BUNDLE_PREFIX + tag + BUNDLE_SUFFIX);
        if (found == null) {
            return entries;
        }
        try (InputStream stream = found) {
            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String key : properties.stringPropertyNames()) {
                entries.put(key, properties.getProperty(key));
            }
        } catch (IOException unreadable) {
            LOGGER.warnf("Bundle %s illisible (%s)", tag, unreadable.getMessage());
        }
        return entries;
    }

    /**
     * Returns the French bundle read backwards, built at the first need.
     *
     * @return the reverse index
     */
    static ReverseIndex reverseIndex() {
        ReverseIndex index = reverse;
        if (index == null) {
            index = new ReverseIndex(bundle(DEFAULT_LANGUAGE));
            reverse = index;
        }
        return index;
    }

    /** The French bundle read backwards: sentence to key. */
    static final class ReverseIndex {

        /** The exact sentences, French text to key. */
        final Map<String, String> exact = new HashMap<>();

        /** The sentences built around values, the most specific first. */
        final List<Sentence> sentences = new ArrayList<>();

        /**
         * Indexes a French bundle.
         *
         * @param french the French bundle
         */
        ReverseIndex(Map<String, String> french) {
            for (Map.Entry<String, String> entry : french.entrySet()) {
                String value = entry.getValue().trim();
                if (PLACEHOLDER.matcher(value).find()) {
                    sentences.add(new Sentence(entry.getKey(), value));
                } else {
                    exact.putIfAbsent(value, entry.getKey());
                }
            }
            sentences.sort(Comparator.comparingInt((Sentence s) -> s.fixed).reversed()
                    .thenComparing(s -> s.key));
        }
    }

    /** A French sentence built around inserted values. */
    static final class Sentence {

        /** The bundle key. */
        final String key;

        /** The sentence as a regular expression capturing each value. */
        final Pattern regex;

        /** For each placeholder number, the group capturing its value. */
        final Map<Integer, Integer> groups = new HashMap<>();

        /** How many fixed characters the sentence has: the more, the more specific. */
        final int fixed;

        /**
         * Compiles a sentence.
         *
         * @param key the bundle key
         * @param french the French sentence with its placeholders
         */
        Sentence(String key, String french) {
            this.key = key;
            StringBuilder regex = new StringBuilder();
            Matcher holes = PLACEHOLDER.matcher(french);
            int last = 0;
            int group = 0;
            int fixedChars = 0;
            while (holes.find()) {
                String literal = french.substring(last, holes.start());
                regex.append(Pattern.quote(literal)).append("(.+?)");
                fixedChars += literal.length();
                group++;
                groups.putIfAbsent(Integer.parseInt(holes.group(1)), group);
                last = holes.end();
            }
            String tail = french.substring(last);
            regex.append(Pattern.quote(tail));
            this.fixed = fixedChars + tail.length();
            this.regex = Pattern.compile(regex.toString(), Pattern.DOTALL);
        }
    }
}
