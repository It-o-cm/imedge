package com.intermarche.pos.ui;

import io.quarkus.arc.Unremovable;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the localised labels of the register's screens, the customer display
 * and the printed documents (LC-13-06).
 *
 * <p>SAME MECHANISM AS THE BACK OFFICE. The valuation back office localises its
 * screens through a {@code @Named("i18n")} request-scoped bean reached from the
 * templates as {@code {inject:i18n.get('key')}}, with one {@code
 * messages/msg_<lang>.properties} bundle per language and the French bundle as
 * the exhaustive reference. The till does the same, deliberately: two bricks of
 * one suite that localise two different ways cost twice to maintain and drift
 * apart on the first label added in a hurry.
 *
 * <p>WHAT DIFFERS, AND WHY. The back office negotiates {@code Accept-Language},
 * which is the right answer for a browser someone chose. A till's browser says
 * nothing about who is standing at it: the same machine serves a French-speaking
 * cashier at eight and a Dutch-speaking one at two. So the chain here is the
 * OPERATOR's, most specific first:
 * <ol>
 *   <li>the language chosen on this register since the last sign-in
 *       ({@link PosState#language}) — the operator's own choice, before signing
 *       in (LC-13-06-03) or at any moment (LC-13-06-04);</li>
 *   <li>the language administered for the signed-in operator
 *       ({@code Employee.language}, referential data) — LC-13-06-02;</li>
 *   <li>the store's system language (back-office parameter
 *       {@code language.system}) — LC-13-06-01;</li>
 *   <li>French.</li>
 * </ol>
 *
 * <p>A SECOND LANGUAGE, for the bilingual customer display and ticket of a
 * bilingual region (LC-13-06-06 / -11): {@code language.customer-second} names
 * it, and {@link #both(String)} renders the two sides of one label. Empty, the
 * customer display and the ticket stay monolingual, which is every store but the
 * Belgian bilingual ones.
 *
 * <p>A MISSING KEY IS NEVER FATAL: the value falls back to the French bundle,
 * then to the key itself. A till must show a wrong label rather than a broken
 * screen, and the exhaustiveness test keeps the bundles aligned so the fallback
 * stays theoretical.
 *
 * <p>APPLICATION-SCOPED, AND NOT REQUEST-SCOPED. The screens resolve their
 * labels inside a request, but two consumers do not: the printer lays out a card
 * receipt from the payment terminal's own follower thread, and a terminal or
 * cheque-reader failure names its refusal there too. A request-scoped resolver
 * threw {@code ContextNotActiveException} on exactly those paths — the ones a
 * cashier meets when something goes wrong. The language is read from the
 * register state, which a till has one of, so there is nothing per-request to
 * remember: a till serves one operator at a time, and that operator's choice is
 * the one the follower thread must print in.
 *
 * <p>Package placement: consumed exclusively by the screens and the documents —
 * ui root, like {@link ThemeService}, the other cross-screen concern.
 */
@Named("i18n")
@ApplicationScoped
@TemplateData
@Unremovable
public class Messages {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(Messages.class);

    /** The default language: the exhaustive reference bundle. */
    public static final String DEFAULT_LANGUAGE = "fr";

    /** Where a bundle lives on the classpath, and how its language is named. */
    private static final String BUNDLE_PREFIX = "messages/msg_";

    /** The extension of a bundle file. */
    private static final String BUNDLE_SUFFIX = ".properties";

    /**
     * Configuration key of a directory of bundles OUTSIDE the jar, empty by
     * default.
     *
     * <p>What makes a language droppable instead of deliverable: a country that
     * wants Spanish writes {@code msg_es.properties} into this directory — by
     * hand or through the referential pull — and the register offers it at the
     * next render of the language screen. No recompilation, no new release.
     */
    private static final String EXTERNAL_DIR_KEY = "pos.messages.dir";

    /** Loaded bundles, keyed by language; a bundle is read once. */
    private static final Map<String, Map<String, String>> BUNDLES = new ConcurrentHashMap<>();

    /** The key by which a bundle names ITS OWN language, in that language. */
    private static final String SELF_NAME_KEY = "language.self";

    /**
     * The languages a bundle is shipped or dropped for, discovered and not
     * enumerated.
     *
     * <p>THE LIST IS NOT WRITTEN ANYWHERE. It used to be {@code List.of("fr",
     * "nl", "pt")}, which made adding a language a code change: the constant, a
     * new bundle, AND the name of that language added to every other bundle —
     * three gestures, two of them in Java, for a label. Now a bundle present is a
     * language offered.
     *
     * <p>Discovered at EVERY call and not cached, on purpose: this is read when
     * the language screen renders, never on the label path, and a cached list
     * would mean a file dropped on a running till is ignored until it reboots.
     * The bundle CONTENTS stay cached — that is the hot path.
     *
     * @return the language tags a bundle exists for, French always first, the
     *         rest alphabetical
     */
    public static List<String> bundledLanguages() {
        java.util.Set<String> found = new java.util.TreeSet<>();
        found.add(DEFAULT_LANGUAGE);
        found.addAll(classpathBundles());
        found.addAll(externalBundles());
        List<String> ordered = new ArrayList<>();
        ordered.add(DEFAULT_LANGUAGE);
        for (String tag : found) {
            if (!DEFAULT_LANGUAGE.equals(tag)) {
                ordered.add(tag);
            }
        }
        return ordered;
    }

    /**
     * The language tags the jar itself ships a bundle for.
     *
     * <p>Reads the {@code messages/} directory of the classpath, which is a real
     * directory in development and an entry of the jar once packaged — the two
     * cases are walked differently and both are handled, because a list that only
     * worked in development would be the same hard-coding one layer down.
     *
     * @return the tags found, possibly empty
     */
    private static java.util.Set<String> classpathBundles() {
        java.util.Set<String> tags = new java.util.TreeSet<>();
        try {
            java.util.Enumeration<java.net.URL> roots = Thread.currentThread()
                    .getContextClassLoader().getResources("messages/");
            while (roots.hasMoreElements()) {
                java.net.URL root = roots.nextElement();
                if ("jar".equals(root.getProtocol())) {
                    java.net.JarURLConnection connection =
                            (java.net.JarURLConnection) root.openConnection();
                    try (java.util.jar.JarFile jar = connection.getJarFile()) {
                        java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            tags.addAll(tagOf(entries.nextElement().getName()));
                        }
                    }
                } else if ("file".equals(root.getProtocol())) {
                    java.io.File[] files = new java.io.File(root.toURI()).listFiles();
                    if (files != null) {
                        for (java.io.File file : files) {
                            tags.addAll(tagOf(file.getName()));
                        }
                    }
                }
            }
        } catch (Exception unreadable) {
            // A classpath that cannot be listed is not a reason to serve no
            // language at all: French is added by the caller whatever happens.
            LOGGER.warnf("Bundles du classpath illisibles (%s)", unreadable.getMessage());
        }
        return tags;
    }

    /**
     * The language tags dropped in the external directory, when one is configured.
     *
     * @return the tags found, empty when no directory is configured or it is gone
     */
    private static java.util.Set<String> externalBundles() {
        java.util.Set<String> tags = new java.util.TreeSet<>();
        java.io.File directory = externalDirectory();
        if (directory == null) {
            return tags;
        }
        java.io.File[] files = directory.listFiles();
        if (files == null) {
            LOGGER.warnf("Répertoire de messages illisible : %s", directory);
            return tags;
        }
        for (java.io.File file : files) {
            tags.addAll(tagOf(file.getName()));
        }
        return tags;
    }

    /**
     * The configured external directory of bundles, or null when there is none.
     *
     * @return the directory, or null when unconfigured, blank or not a directory
     */
    private static java.io.File externalDirectory() {
        String configured = ConfigProvider.getConfig()
                .getOptionalValue(EXTERNAL_DIR_KEY, String.class).orElse("");
        if (configured.isBlank()) {
            return null;
        }
        java.io.File directory = new java.io.File(configured.trim());
        return directory.isDirectory() ? directory : null;
    }

    /**
     * The language tag a file name carries, when that name is a bundle's.
     *
     * <p>Answers a set and not a tag so a caller can add the result blindly: a
     * file that is not a bundle contributes nothing instead of a null to test.
     *
     * @param fileName the file or jar-entry name, possibly with directories
     * @return the tag in a one-element set, or an empty set
     */
    private static java.util.Set<String> tagOf(String fileName) {
        String name = fileName.substring(fileName.lastIndexOf('/') + 1);
        String prefix = BUNDLE_PREFIX.substring(BUNDLE_PREFIX.lastIndexOf('/') + 1);
        if (!name.startsWith(prefix) || !name.endsWith(BUNDLE_SUFFIX)) {
            return java.util.Set.of();
        }
        String tag = name.substring(prefix.length(), name.length() - BUNDLE_SUFFIX.length());
        return tag.isEmpty() || !tag.equals(normalize(tag)) ? java.util.Set.of() : java.util.Set.of(tag);
    }

    /**
     * The inputs the memorised language was resolved from, or null before the
     * first resolution.
     *
     * <p>WHY A MEMO AT ALL. The operator leg reads the {@code Employee} row, and
     * a screen resolves a hundred labels: without this, rendering the sale
     * screen asked the database a hundred times for the same answer. The key is
     * the three inputs of the chain, so a sign-in, a sign-out or a language
     * chosen on the language screen all move the labels at once — what a
     * per-request cache could not do, since the resolver now outlives the
     * request.
     */
    private volatile String memoInputs;

    /** The language the memorised inputs resolved to. */
    private volatile String memoLanguage = DEFAULT_LANGUAGE;

    /**
     * Forgets the memorised resolution, so the next label resolves the chain
     * again from the database.
     *
     * <p>THE MEMO WATCHES THREE INPUTS and the operator's administered language
     * is not one of them: it is referential data the back office owns, written
     * on the {@code Employee} row, and the register has no way of noticing that
     * it moved. Whoever rewrites those rows says so here, and the shop's choice
     * reaches the very next label instead of waiting for a sign-out — without
     * costing the database read per label that the memo exists to avoid.
     */
    public void forgetResolvedLanguage() {
        LOGGER.info("Entering method forgetResolvedLanguage");
        memoInputs = null;
        memoLanguage = DEFAULT_LANGUAGE;
        LOGGER.info("Exiting method forgetResolvedLanguage");
    }

    /** The register state: carries the language the operator chose. */
    @Inject
    PosState state;

    /** Administered settings: the store's system language and the second one. */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Returns the localised label of a key, in the language resolved for the
     * operator at this register.
     *
     * @param key the message key, or null
     * @return the localised label, the French value as a fallback, or the key
     *         itself when it is defined nowhere
     */
    public String get(String key) {
        if (key == null) {
            return "";
        }
        String language = language();
        String value = bundle(language).get(key);
        if (value == null && !DEFAULT_LANGUAGE.equals(language)) {
            value = bundle(DEFAULT_LANGUAGE).get(key);
        }
        return value != null ? value : key;
    }

    /**
     * Returns the localised label of a key with its {@code {0}}, {@code {1}}…
     * placeholders substituted.
     *
     * @param key the message key
     * @param args the arguments substituted into the pattern, possibly empty
     * @return the formatted localised label
     */
    public String format(String key, Object... args) {
        String pattern = get(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        return new MessageFormat(pattern, Locale.forLanguageTag(language())).format(args);
    }

    /**
     * Returns a label in BOTH languages of a bilingual store, second language
     * first and separated by a slash — the shape the requirement spells out,
     * {@code "Terug te geven / A rendre"} (LC-13-06-06 / -11).
     *
     * <p>Falls back to the single resolved label when no second language is
     * administered, when it is the resolved one, or when the two renderings are
     * identical: a label printed twice reads as a defect, not as a courtesy.
     *
     * @param key the message key
     * @return the bilingual label, or the single one when there is no second
     *         language to show
     */
    public String both(String key) {
        String second = secondLanguage();
        String primary = get(key);
        if (second == null) {
            return primary;
        }
        String other = bundle(second).get(key);
        if (other == null || other.equals(primary)) {
            return primary;
        }
        return other + " / " + primary;
    }

    /**
     * Returns the NAME of a language in the language being rendered — "Dutch" in
     * French, "Nederlands" in Dutch — for the selection screen.
     *
     * <p>It exists because Qute has no concatenation operator: a template cannot
     * build the key {@code language.nl} from the tag it is looping over, so the
     * key is built here.
     *
     * @param tag the language tag, possibly null
     * @return the language's name, or the tag itself when it has no label
     */
    public String languageName(String tag) {
        String normalized = normalize(tag);
        if (normalized.isEmpty()) {
            return "";
        }
        // A LANGUAGE NAMES ITSELF, in its own bundle and in its own language:
        // "Nederlands", not "Néerlandais". Two reasons, and the second is the
        // structural one. Whoever looks for Dutch on that screen does not read
        // French. And a name held by the OTHER bundles meant that adding a
        // language forced an edit of every language already shipped — the
        // coupling that made this list hard-coded in the first place.
        String own = bundle(normalized).get(SELF_NAME_KEY);
        return own != null ? own : normalized.toUpperCase(Locale.ROOT);
    }

    /**
     * Returns the language actually rendered, for the {@code <html lang>}
     * attribute and for the screens that highlight the current choice.
     *
     * @return the rendered language tag
     */
    public String getLang() {
        return language();
    }

    /**
     * Returns the administered second language of the customer display and the
     * ticket, or null when the store is monolingual (LC-13-06-06 / -11).
     *
     * @return the second language tag, or null
     */
    public String getSecondLang() {
        return secondLanguage();
    }

    /**
     * Returns the languages the operator may choose from: the administered list
     * narrowed to the ones a bundle ships, never empty (the default language is
     * always offered).
     *
     * @return the selectable language tags, in administered order
     */
    public List<String> getAvailableLanguages() {
        List<String> available = new ArrayList<>();
        for (String entry : posSettingsService.availableLanguages()) {
            String tag = normalize(entry);
            if (bundledLanguages().contains(tag) && !available.contains(tag)) {
                available.add(tag);
            }
        }
        if (available.isEmpty()) {
            available.add(DEFAULT_LANGUAGE);
        }
        return available;
    }

    /**
     * Resolves the language to render: the operator's own choice, then the
     * signed-in operator's administered language, then the store's system
     * language, then French. A language no bundle ships is ignored at every step
     * rather than shown as raw keys.
     *
     * <p>Resolved at every call and not memorised: the resolver outlives the
     * request now, and a language chosen on the language screen must apply to
     * the very next label rather than to the next instance.
     *
     * @return the language tag to render
     */
    private String language() {
        String chosen = normalize(state.language);
        if (hasBundle(chosen)) {
            return chosen;
        }
        String system = normalize(posSettingsService.systemLanguage());
        Long operator = state.auth == null ? null : state.auth.operatorId;
        String inputs = chosen + '|' + operator + '|' + system;
        if (!inputs.equals(memoInputs)) {
            memoLanguage = resolve(operator, system);
            memoInputs = inputs;
        }
        return memoLanguage;
    }

    /**
     * Resolves the operator leg and what follows it, the only leg that costs a
     * database read.
     *
     * @param operator the signed-in operator's id, or null
     * @param system the store's normalized system language
     * @return the language tag to render
     */
    private String resolve(Long operator, String system) {
        String administered = normalize(operatorLanguage(operator));
        if (hasBundle(administered)) {
            return administered;
        }
        return hasBundle(system) ? system : DEFAULT_LANGUAGE;
    }

    /**
     * Tells whether a bundle exists for a language, cheaply enough for the
     * label path.
     *
     * <p>NOT {@link #bundledLanguages()}, deliberately: that one walks the
     * classpath at every call, which is the right answer for the language screen
     * and the wrong one for a method called once per label. This reads the
     * cached bundle instead — a language with keys has a bundle, and a bundle
     * loaded once stays loaded.
     *
     * @param tag the normalized language tag
     * @return true when that language resolves labels
     */
    private static boolean hasBundle(String tag) {
        return !tag.isEmpty() && !bundle(tag).isEmpty();
    }

    /**
     * Returns the language administered for the signed-in operator
     * (LC-13-06-02), or null when nobody is signed in, the employee row is gone
     * or no language was administered for them.
     *
     * @return the operator's administered language, or null
     */
    private String operatorLanguage(Long operatorId) {
        if (operatorId == null) {
            return null;
        }
        try {
            com.intermarche.pos.domain.people.Employee employee =
                    com.intermarche.pos.domain.people.Employee.findById(operatorId);
            return employee == null ? null : employee.language;
        } catch (RuntimeException unreachable) {
            // A LABEL IS NEVER WORTH AN EXCEPTION. This read runs wherever a
            // label is resolved, and the printer resolves labels from the
            // payment terminal's follower thread, where no persistence context
            // is active. Falling through to the store's language prints a
            // correct ticket; propagating would lose the customer's receipt.
            LOGGER.debugf("Langue de l'operateur illisible ici (%s)", unreachable.getMessage());
            return null;
        }
    }

    /**
     * Returns the administered second language, normalized and checked against
     * the shipped bundles, or null when there is none to show.
     *
     * @return the second language tag, or null
     */
    private String secondLanguage() {
        String second = normalize(posSettingsService.customerSecondLanguage());
        if (!hasBundle(second) || second.equals(language())) {
            return null;
        }
        return second;
    }

    /**
     * Reduces a language tag to the lower-case primary subtag this register
     * reasons about ({@code fr-BE} → {@code fr}).
     *
     * @param tag the language tag, possibly null or blank
     * @return the primary subtag, or an empty string
     */
    private static String normalize(String tag) {
        if (tag == null || tag.isBlank()) {
            return "";
        }
        String trimmed = tag.trim().toLowerCase(Locale.ROOT);
        int dash = trimmed.indexOf('-');
        int underscore = trimmed.indexOf('_');
        int separator;
        if (dash < 0) {
            separator = underscore;
        } else if (underscore < 0) {
            separator = dash;
        } else {
            separator = Math.min(dash, underscore);
        }
        return separator < 0 ? trimmed : trimmed.substring(0, separator);
    }

    /**
     * Returns a language's bundle, reading it from the classpath on first use.
     *
     * @param language the language tag
     * @return the key/value pairs of that bundle, empty when the file is absent
     */
    private static Map<String, String> bundle(String language) {
        return BUNDLES.computeIfAbsent(language, Messages::load);
    }

    /**
     * Loads a bundle from the classpath, UTF-8 encoded.
     *
     * @param language the bundle language
     * @return the loaded key/value pairs; empty when the bundle file is absent
     */
    private static Map<String, String> load(String language) {
        Map<String, String> map = new HashMap<>();
        String path = "messages/msg_" + language + ".properties";
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.warnf("Bundle de messages absent : %s", path);
                return map;
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String name : properties.stringPropertyNames()) {
                map.put(name, properties.getProperty(name));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Bundle de messages illisible : " + path, e);
        }
        return map;
    }
}
