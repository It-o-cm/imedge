package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BackOfficeMessages} (BO-01-01-04, BO-01-01-05),
 * targeting 100% branch coverage: the language chain (operator, echelon,
 * French), the labels and their fallbacks, the backward reading of the French
 * texts computed by the code, and the completeness of the three bundles
 * against each other and against the templates.
 */
class BackOfficeMessagesTest {

    /** The query the account lookup of the language chain runs. */
    private static final String ACTIVE_LOGIN = "(badgeId = ?1 or loginName = ?1) and active = true";

    /**
     * Builds the bean for an operator.
     *
     * @param login the signed-in login, or null for an anonymous page
     * @param system the echelon's reference language
     * @return the bean
     */
    private BackOfficeMessages messages(String login, String system) {
        BackOfficeMessages messages = new BackOfficeMessages();
        messages.identity = mock(SecurityIdentity.class);
        if (login == null) {
            when(messages.identity.isAnonymous()).thenReturn(true);
        } else {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn(login);
            when(messages.identity.getPrincipal()).thenReturn(principal);
        }
        messages.posSettingsService = mock(PosSettingsService.class);
        when(messages.posSettingsService.value("language.system")).thenReturn(system);
        return messages;
    }

    /**
     * Resolves the language of a bean whose operator holds the given language.
     *
     * @param messages the bean
     * @param holder the account found for the login, possibly null
     * @return the resolved language
     */
    @SuppressWarnings("unchecked")
    private String languageWith(BackOfficeMessages messages, Employee holder) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(holder);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> PanacheEntityBase.find(ACTIVE_LOGIN, "paule")).thenReturn(query);
            return messages.getLanguage();
        }
    }

    /**
     * Builds an account administered in a language.
     *
     * @param language the language, possibly null
     * @return the account
     */
    private Employee speaking(String language) {
        Employee employee = new Employee();
        employee.language = language;
        return employee;
    }

    /**
     * The operator's administered language comes first, then the echelon's,
     * then French; a language no bundle ships is skipped at each step, and
     * the language is resolved once per request.
     */
    @Test
    void theLanguageFollowsTheOperatorThenTheEchelon() {
        BackOfficeMessages dutch = messages("paule", "pt");
        assertEquals("nl", languageWith(dutch, speaking("nl-BE")));
        assertEquals("nl", dutch.getLanguage());
        assertEquals("pt", languageWith(messages("paule", "pt_PT"), speaking("es")));
        assertEquals("pt", languageWith(messages("paule", "pt"), speaking(null)));
        assertEquals("pt", languageWith(messages("paule", "pt"), null));
        assertEquals("fr", languageWith(messages("paule", " "), null));
        assertEquals("fr", languageWith(messages("paule", null), null));
        assertEquals("nl", messages(null, "nl").getLanguage());
    }

    /**
     * Without an identity, or with one carrying no principal, the echelon
     * decides.
     */
    @Test
    void anUnidentifiedPageSpeaksTheEchelonsLanguage() {
        BackOfficeMessages nobody = messages(null, "pt");
        nobody.identity = null;
        assertEquals("pt", nobody.getLanguage());
        BackOfficeMessages faceless = messages("paule", "nl");
        when(faceless.identity.getPrincipal()).thenReturn(null);
        assertEquals("nl", faceless.getLanguage());
    }

    /**
     * An unreadable account or parameter never breaks a page: the next step
     * of the chain answers.
     */
    @Test
    void anUnreadableStepFallsThrough() {
        BackOfficeMessages broken = messages("paule", "nl");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> PanacheEntityBase.find(ACTIVE_LOGIN, "paule")).thenThrow(new IllegalStateException("db"));
            assertEquals("nl", broken.getLanguage());
        }
        BackOfficeMessages unset = messages(null, "nl");
        when(unset.posSettingsService.value("language.system")).thenThrow(new IllegalStateException("cfg"));
        assertEquals("fr", unset.getLanguage());
    }

    /**
     * A label is read in the page's language; a null key is empty, an unknown
     * key shows itself, and a key the language lacks falls back to French.
     */
    @Test
    void labelsAreReadWithTheirFallbacks() {
        BackOfficeMessages dutch = messages(null, "nl");
        assertEquals(BackOfficeMessages.bundle("nl").get("t.utilisateurs"), dutch.get("t.utilisateurs"));
        assertEquals("", dutch.get(null));
        assertEquals("no.such.key", dutch.get("no.such.key"));
        BackOfficeMessages.bundle("zzpartial").put("t.utilisateurs", "x");
        BackOfficeMessages partial = messages(null, "zzpartial");
        assertEquals("x", partial.get("t.utilisateurs"));
        assertEquals("Utilisateurs", partial.get("t.utilisateurs-et-profils").substring(0, 12));
        BackOfficeMessages french = messages(null, "fr");
        assertEquals("Utilisateurs", french.get("t.utilisateurs"));
    }

    /**
     * An amount is written with two decimals and the decimal separator of the
     * page's language (an electronic shelf label price reads "99,00", never
     * "99.0"); a null amount is empty.
     */
    @Test
    void amountsAreWrittenInThePageLanguage() {
        assertEquals("99,00", messages(null, "fr").money(new java.math.BigDecimal("99.0")));
        assertEquals("1,99", messages(null, "nl").money(new java.math.BigDecimal("1.99")));
        assertEquals("1234,57", messages(null, "pt").money(new java.math.BigDecimal("1234.565")));
        assertEquals("", messages(null, "fr").money(null));
    }

    /**
     * An amount reaching the page as another number or as a machine-readable
     * text is written the same way; a text that is not a number, or a value
     * that is neither a number nor a text, shows itself.
     */
    @Test
    void amountsOfEveryShapeAreWrittenInThePageLanguage() {
        BackOfficeMessages french = messages(null, "fr");
        assertEquals("2,76", french.money("2.7600"));
        assertEquals("12,50", french.money(" 12.5 "));
        assertEquals("3,00", french.money(3));
        assertEquals("0,10", french.money(0.1d));
        assertEquals("—", french.money("—"));
        assertEquals("CASH", french.money("CASH"));
        assertEquals("true", french.money(Boolean.TRUE));
    }

    /**
     * A VAT rate held as a fraction reads as a percentage with two decimals,
     * a share already in percent likewise; null is empty and a non-number
     * shows itself.
     */
    @Test
    void ratesAndPercentagesAreWrittenInThePageLanguage() {
        BackOfficeMessages dutch = messages(null, "nl");
        assertEquals("20,00 %", dutch.rate(new java.math.BigDecimal("0.2000")));
        assertEquals("5,50 %", dutch.rate("0.055"));
        assertEquals("", dutch.rate(null));
        assertEquals("—", dutch.rate("—"));
        assertEquals("12,50 %", dutch.percent(new java.math.BigDecimal("12.5000")));
        assertEquals("", dutch.percent(null));
        assertEquals("n/a", dutch.percent("n/a"));
    }

    /**
     * Quantities and weights follow the administered writing of quantities
     * (BO-10-08-28/29), the register's historical one when none is
     * administered or the parameters cannot be read.
     */
    @Test
    void quantitiesAndWeightsFollowTheAdministeredWriting() {
        BackOfficeMessages administered = messages(null, "fr");
        when(administered.posSettingsService.unitFormat())
                .thenReturn(new com.intermarche.edge.domain.catalog.UnitFormat(1, 2, "KG"));
        assertEquals("3", administered.quantity(new java.math.BigDecimal("3.000")));
        assertEquals("2,5", administered.quantity("2.50"));
        assertEquals("0,04 KG", administered.weight(new java.math.BigDecimal("0.035")));
        assertEquals("", administered.quantity(null));
        assertEquals("—", administered.quantity("—"));
        assertEquals("", administered.weight(null));
        assertEquals("—", administered.weight("—"));
        BackOfficeMessages historical = messages(null, "pt");
        assertEquals("0,035 kg", historical.weight(new java.math.BigDecimal("0.035")));
        BackOfficeMessages unreadable = messages(null, "fr");
        when(unreadable.posSettingsService.unitFormat()).thenThrow(new IllegalStateException("cfg"));
        assertEquals("1,25", unreadable.quantity(new java.math.BigDecimal("1.25")));
    }

    /**
     * A number that is neither an amount nor a quantity keeps its own
     * decimals, needless zeros dropped; null is empty and a non-number shows
     * itself.
     */
    @Test
    void numbersKeepTheirOwnDecimals() {
        BackOfficeMessages french = messages(null, "fr");
        assertEquals("1,085", french.number(new java.math.BigDecimal("1.0850")));
        assertEquals("100", french.number(new java.math.BigDecimal("100.0000")));
        assertEquals("", french.number(null));
        assertEquals("abc", french.number("abc"));
    }

    /**
     * A code is read through its family prefix; a code the bundles do not
     * label shows itself, and a null code is empty.
     */
    @Test
    void codesAreLabelledThroughTheirFamily() {
        BackOfficeMessages.bundle("zzcode").put("t.zz-APPLIED", "Toegepast");
        BackOfficeMessages messages = messages(null, "zzcode");
        assertEquals("Toegepast", messages.code("t.zz-", "APPLIED"));
        assertEquals("PENDING", messages.code("t.zz-", "PENDING"));
        assertEquals("", messages.code("t.zz-", null));
    }

    /**
     * A label meant for a JavaScript string between single quotes never
     * carries a straight apostrophe.
     */
    @Test
    void javascriptLabelsCarryNoStraightApostrophe() {
        BackOfficeMessages.bundle("zzapos").put("t.utilisateurs", "l'accès");
        BackOfficeMessages messages = messages(null, "zzapos");
        assertEquals("l’accès", messages.js("t.utilisateurs"));
        assertEquals("l’accès", messages.textJs("Utilisateurs"));
    }

    /**
     * A French text computed by the code is translated: an exact sentence
     * first, then a sentence built around values, which keep their place — a
     * value the bundles hold as a label translated with it (BO-06-01-04); a
     * text no bundle knows, a null and a French page leave it as it is.
     */
    @Test
    void computedTextsAreReadBackwards() {
        BackOfficeMessages dutch = messages(null, "nl");
        Map<String, String> nl = BackOfficeMessages.bundle("nl");
        assertEquals(nl.get("j.compte-cree"), dutch.text("Compte créé."));
        assertEquals(nl.get("p.profil-x-affecte-a-x").replace("{0}", "Marketing").replace("{1}", "Paule Lefort"),
                dutch.text("Profil « Marketing » affecté à Paule Lefort."));
        assertEquals("Personne ne connaît ce texte", dutch.text("Personne ne connaît ce texte"));
        String subtotal = nl.get("p.r21v-sous-total");
        assertEquals(subtotal.replace("{0}", nl.get("j.r21v-tender-cash")), dutch.text("Sous-total Espèces"));
        assertEquals(subtotal.replace("{0}", "2020"), dutch.text("Sous-total 2020"));
        assertEquals(subtotal.replace("{0}", "Rayon inconnu"), dutch.text("Sous-total Rayon inconnu"));
        assertEquals("", dutch.text(null));
        assertEquals("7", dutch.text(7));
        BackOfficeMessages french = messages(null, "fr");
        assertEquals("Compte créé.", french.text("Compte créé."));
    }

    /**
     * A placeholder the translation names but the French sentence lacks is
     * left as it stands; a placeholder repeated in the French sentence is
     * captured once.
     */
    @Test
    void placeholdersAreFilledByNumber() {
        BackOfficeMessages.Sentence sentence = new BackOfficeMessages.Sentence("k", "{0} et {0} puis {1}");
        Matcher matcher = sentence.regex.matcher("a et a puis b");
        assertTrue(matcher.matches());
        assertEquals("b, a, {9}", BackOfficeMessages.fill("{1}, {0}, {9}", sentence, matcher));
        assertEquals(1, (int) sentence.groups.get(0));
        assertEquals(3, (int) sentence.groups.get(1));
    }

    /**
     * The most specific sentence wins: more fixed characters first, the key
     * deciding a tie.
     */
    @Test
    void theMostSpecificSentenceComesFirst() {
        Map<String, String> french = new HashMap<>();
        french.put("b", "Code {0}");
        french.put("a", "Code {0}.");
        french.put("c", "Nœud {0}");
        french.put("d", "Bonjour");
        BackOfficeMessages.ReverseIndex index = new BackOfficeMessages.ReverseIndex(french);
        assertEquals(List.of("a", "b", "c"), index.sentences.stream().map(s -> s.key).toList());
        assertEquals("d", index.exact.get("Bonjour"));
        assertSame(BackOfficeMessages.reverseIndex(), BackOfficeMessages.reverseIndex());
    }

    /**
     * Language tags are reduced to their primary subtag; a language no bundle
     * ships, or no language at all, has no bundle.
     */
    @Test
    void languageTagsAreNormalized() {
        assertEquals("", BackOfficeMessages.normalize(null));
        assertEquals("", BackOfficeMessages.normalize("  "));
        assertEquals("nl", BackOfficeMessages.normalize("NL-be"));
        assertEquals("pt", BackOfficeMessages.normalize("pt_PT"));
        assertEquals("fr", BackOfficeMessages.normalize(" fr "));
        assertFalse(BackOfficeMessages.hasBundle(""));
        assertFalse(BackOfficeMessages.hasBundle("xx"));
        assertTrue(BackOfficeMessages.hasBundle("pt"));
    }

    /**
     * An unreadable bundle is an empty one, never an error.
     *
     * @throws Exception never in practice
     */
    @Test
    void anUnreadableBundleIsEmpty() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader failing = new ClassLoader(original) {
            /**
             * Answers a stream that fails at the first read.
             *
             * @param name the resource name
             * @return the failing stream
             */
            @Override
            public InputStream getResourceAsStream(String name) {
                return new InputStream() {
                    /**
                     * Fails.
                     *
                     * @return never
                     * @throws IOException always
                     */
                    @Override
                    public int read() throws IOException {
                        throw new IOException("unreadable");
                    }
                };
            }
        };
        Thread.currentThread().setContextClassLoader(failing);
        try {
            assertTrue(BackOfficeMessages.load("nl").isEmpty());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * The Dutch and Portuguese bundles hold every French key, and nothing
     * else, each with the same placeholders.
     */
    @Test
    void theBundlesAreComplete() {
        Map<String, String> french = BackOfficeMessages.load("fr");
        assertFalse(french.isEmpty());
        for (String language : List.of("nl", "pt")) {
            Map<String, String> other = BackOfficeMessages.load(language);
            assertEquals(french.keySet(), other.keySet(), language);
            for (Map.Entry<String, String> entry : french.entrySet()) {
                assertEquals(placeholders(entry.getValue()), placeholders(other.get(entry.getKey())),
                        language + " " + entry.getKey());
            }
        }
    }

    /**
     * Every label the templates ask for is defined in the French bundle.
     *
     * @throws Exception when the templates cannot be listed
     */
    @Test
    void everyTemplateLabelIsDefined() throws Exception {
        Map<String, String> french = BackOfficeMessages.load("fr");
        URL root = Thread.currentThread().getContextClassLoader().getResource("templates");
        File[] files = new File(root.toURI()).listFiles((dir, name) -> name.endsWith(".html"));
        Pattern call = Pattern.compile("i18n\\.(?:get|js)\\('([^']+)'\\)");
        List<String> missing = new ArrayList<>();
        int used = 0;
        for (File file : files) {
            Matcher matcher = call.matcher(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            while (matcher.find()) {
                used++;
                if (!french.containsKey(matcher.group(1))) {
                    missing.add(file.getName() + ":" + matcher.group(1));
                }
            }
        }
        assertTrue(used > 1000);
        assertEquals(List.of(), missing);
    }

    /**
     * Lists the placeholders of a sentence.
     *
     * @param sentence the sentence
     * @return its placeholders, sorted
     */
    private static Set<String> placeholders(String sentence) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = Pattern.compile("\\{\\d+}").matcher(sentence);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /**
     * The French labels of the account, supervisor-card and supervision
     * screens carry their accents (BO-04-02-09/11/12).
     */
    @Test
    void theSupervisionLabelsAreAccented() {
        Map<String, String> french = BackOfficeMessages.bundle("fr");
        assertEquals("Téléphone", french.get("t.telephone"));
        assertEquals("Date de début", french.get("t.date-de-debut"));
        assertEquals("vide = généré", french.get("t.vide-genere"));
        assertEquals("Clé Dallas", french.get("t.cle-dallas"));
        assertEquals("À traiter en caisse", french.get("t.rc3-dash-a-traiter-en-caisse"));
        assertTrue(french.get("t.sans-code-saisi-le-systeme-genere-le").startsWith("Sans code saisi, le système génère"));
        assertTrue(french.get("t.imprimez-la-carte-et-remettez-la-a").contains("remettez-la à son titulaire"));
    }

    /**
     * The messages of a GIVEN language, for an export written outside the
     * operator's request (BO-06-01-02): a shipped language is taken as given,
     * its regional variant reduced to it, and an unknown, blank or missing one
     * falls back to French — with no operator nor parameter read.
     */
    @Test
    void theMessagesOfAGivenLanguageNeedNoRequest() {
        assertEquals("nl", BackOfficeMessages.in("nl").getLanguage());
        assertEquals("pt", BackOfficeMessages.in("pt-PT").getLanguage());
        assertEquals("fr", BackOfficeMessages.in("de").getLanguage());
        assertEquals("fr", BackOfficeMessages.in(" ").getLanguage());
        assertEquals("fr", BackOfficeMessages.in(null).getLanguage());
        assertEquals(BackOfficeMessages.bundle("nl").get("t.page"), BackOfficeMessages.in("nl").get("t.page"));
    }
}
