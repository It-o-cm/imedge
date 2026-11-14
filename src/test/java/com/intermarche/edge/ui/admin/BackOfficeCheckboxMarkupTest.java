package com.intermarche.edge.ui.admin;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the ONE placement rule of the back-office checkboxes: « [x] Libellé »,
 * the box first and its label right after it on the same line, the input
 * inside its {@code <label class="field-check">} so the whole label is
 * clickable. A checkbox is never stacked under a caption like a text input
 * (the defect reported on imedge: {@code <label class="field"><span
 * class="field-label">…</span><input type="checkbox">} or a
 * {@code <label class="field-label" for=…>} above the box). The only boxes
 * without their own label line are table cells, which keep their tabular
 * placement under a column header or carry an {@code aria-label}.
 *
 * <p>Every template of the back office is scanned, and the stylesheets are
 * checked to define the shared class and none of the retired variants.
 */
class BackOfficeCheckboxMarkupTest {

    /** A checkbox input, whatever its attributes. */
    private static final Pattern CHECKBOX = Pattern.compile("<input\\b[^>]*\\btype=\"checkbox\"[^>]*>");

    /** The stacked way: a caption span of a {@code .field} label, then the box. */
    private static final Pattern STACKED = Pattern.compile(
            "<label class=\"field[^\"]*\">\\s*<span class=\"field-label\">(?:(?!</label>).)*?</span>\\s*<input\\b[^>]*\\btype=\"checkbox\"",
            Pattern.DOTALL);

    /** A caption label pointing at a control by its id. */
    private static final Pattern CAPTION_FOR = Pattern.compile("<label class=\"field-label\" for=\"([^\"]+)\">");

    /** The id of a checkbox. */
    private static final Pattern CHECKBOX_ID = Pattern.compile("<input\\b[^>]*\\bid=\"([^\"]+)\"[^>]*\\btype=\"checkbox\"|<input\\b[^>]*\\btype=\"checkbox\"[^>]*\\bid=\"([^\"]+)\"");

    /** The shared line: the label opens on the box, then the text in a span. */
    private static final Pattern LINE = Pattern.compile(
            "<label class=\"field-check\"><input\\b[^>]*\\btype=\"checkbox\"[^>]*> <span>(?:(?!</label>).)*</span></label>",
            Pattern.DOTALL);

    /** The class attributes of the retired checkbox variants. */
    private static final List<String> RETIRED_CLASSES = List.of("class=\"check\"", "class=\"toggle\"",
            "class=\"journal-check\"", "class=\"journal-checks\"", "class=\"bulk-code-toggle\"",
            "class=\"survey-days\"", "class=\"field field-check\"", "class=\"form-row\"");

    /** The selectors of the retired checkbox variants. */
    private static final Pattern RETIRED_SELECTORS = Pattern.compile(
            "\\.(?:toggle|check|journal-check|journal-checks|bulk-code-toggle|survey-days|form-row)\\b(?![-\\w])");

    /**
     * Reads every back-office template, by file name.
     *
     * @return the template sources, sorted by name
     * @throws IOException when a template cannot be read
     * @throws URISyntaxException when the template folder cannot be located
     */
    private Map<String, String> templates() throws IOException, URISyntaxException {
        return folder("templates", ".html");
    }

    /**
     * Reads the files of a classpath folder ending with a suffix.
     *
     * @param path the classpath folder
     * @param suffix the file suffix kept
     * @return the sources by file name, sorted
     * @throws IOException when a file cannot be read
     * @throws URISyntaxException when the folder cannot be located
     */
    private Map<String, String> folder(String path, String suffix) throws IOException, URISyntaxException {
        URL root = Thread.currentThread().getContextClassLoader().getResource(path);
        File[] files = new File(root.toURI()).listFiles((dir, name) -> name.endsWith(suffix));
        Arrays.sort(files);
        Map<String, String> sources = new TreeMap<>();
        for (File file : files) {
            sources.put(file.getName(), Files.readString(file.toPath(), StandardCharsets.UTF_8));
        }
        return sources;
    }

    /**
     * Lists the placement offences of one template.
     *
     * @param name the template name, for the messages
     * @param html the template source
     * @return one message per offence, empty when the template follows the rule
     */
    static List<String> offences(String name, String html) {
        List<String> found = new ArrayList<>();
        Matcher stacked = STACKED.matcher(html);
        while (stacked.find()) {
            found.add(name + ": checkbox stacked under a .field-label caption");
        }
        List<String> boxIds = new ArrayList<>();
        Matcher ids = CHECKBOX_ID.matcher(html);
        while (ids.find()) {
            boxIds.add(ids.group(1) != null ? ids.group(1) : ids.group(2));
        }
        Matcher captions = CAPTION_FOR.matcher(html);
        while (captions.find()) {
            if (boxIds.contains(captions.group(1))) {
                found.add(name + ": caption label above checkbox #" + captions.group(1));
            }
        }
        String lines = LINE.matcher(html).replaceAll("");
        Matcher box = CHECKBOX.matcher(lines);
        while (box.find()) {
            String before = lines.substring(0, box.start()).stripTrailing();
            boolean cell = before.endsWith(">") && before.substring(before.lastIndexOf('<')).startsWith("<td");
            if (!cell && !box.group().contains("aria-label=")) {
                found.add(name + ": checkbox outside a field-check line: " + box.group());
            }
        }
        for (String retired : RETIRED_CLASSES) {
            if (html.contains(retired)) {
                found.add(name + ": retired variant " + retired);
            }
        }
        return found;
    }

    /**
     * No template draws a checkbox the stacked way, puts a caption above it,
     * leaves it outside the shared line (table cells apart) or uses a retired
     * variant.
     *
     * @throws Exception when the templates cannot be read
     */
    @Test
    void everyBackOfficeCheckboxPrecedesItsLabel() throws Exception {
        List<String> found = new ArrayList<>();
        int boxes = 0;
        for (Map.Entry<String, String> template : templates().entrySet()) {
            found.addAll(offences(template.getKey(), template.getValue()));
            Matcher box = CHECKBOX.matcher(template.getValue());
            while (box.find()) {
                boxes++;
            }
        }
        assertEquals(List.of(), found);
        assertTrue(boxes >= 132, "the scan must reach every checkbox of the back office, found " + boxes);
    }

    /**
     * The scan does catch each wrong way: the stacked {@code .field} label, the
     * caption label above the box, a bare box after its text and a retired
     * class; the shared line, a table cell and a labelled box pass.
     */
    @Test
    void theScanCatchesEveryWrongWay() {
        assertEquals(1, offences("t", "<label class=\"field\"><span class=\"field-label\">Actif</span><input type=\"checkbox\" name=\"active\"></label>").stream()
                .filter(m -> m.contains("stacked")).count());
        assertTrue(offences("t", "<label class=\"field-label\" for=\"a\">Actif</label>\n<input id=\"a\" type=\"checkbox\" name=\"active\">").stream()
                .anyMatch(m -> m.contains("caption label above checkbox #a")));
        assertTrue(offences("t", "<input type=\"checkbox\" name=\"active\" id=\"b\"><label class=\"field-label\" for=\"b\">x</label>").stream()
                .anyMatch(m -> m.contains("#b")));
        assertTrue(offences("t", "<label class=\"field-label\" for=\"c\">Code</label><input id=\"c\" type=\"text\">").isEmpty());
        assertTrue(offences("t", "<div class=\"form-row\"><label>Par PDV</label><input type=\"checkbox\" name=\"perStore\"></div>").stream()
                .anyMatch(m -> m.contains("outside a field-check line")));
        assertTrue(offences("t", "<label class=\"check\"><input type=\"checkbox\" name=\"x\"> X</label>").stream()
                .anyMatch(m -> m.contains("retired variant class=\"check\"")));
        assertTrue(offences("t", "<div class=\"field\"><label class=\"field-check\"><input type=\"checkbox\" name=\"active\" checked> <span>Actif</span></label></div>"
                + "<td><input type=\"checkbox\" name=\"pouchId\"></td>"
                + "<span><input type=\"checkbox\" name=\"exempt\" aria-label=\"Exonéré\"></span>").isEmpty());
    }

    /**
     * base.css carries the shared {@code .field-check} rule and no stylesheet
     * keeps a selector of a retired variant.
     *
     * @throws Exception when the stylesheets cannot be read
     */
    @Test
    void theStylesheetsHoldOnlyTheSharedRule() throws Exception {
        Map<String, String> sheets = folder("META-INF/resources/admin-assets", ".css");
        assertTrue(sheets.get("base.css").contains(".field-check {"));
        List<String> retired = new ArrayList<>();
        for (Map.Entry<String, String> sheet : sheets.entrySet()) {
            Matcher selector = RETIRED_SELECTORS.matcher(sheet.getValue().replaceAll("(?s)/\\*.*?\\*/", ""));
            while (selector.find()) {
                retired.add(sheet.getKey() + ": " + selector.group());
            }
        }
        assertEquals(List.of(), retired);
        assertFalse(RETIRED_SELECTORS.matcher(".toggle { }").replaceAll("").contains(".toggle"));
    }
}
