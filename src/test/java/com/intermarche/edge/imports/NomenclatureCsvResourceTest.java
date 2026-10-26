package com.intermarche.edge.imports;

import com.intermarche.edge.service.NomenclatureImportService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NomenclatureCsvResource}.
 * <p>
 * The resource is a plain CDI bean, not a Panache concretion: it reads the
 * pipe-separated NOMENCLATURE stream by header name, turns it into service
 * rows and delegates the loading to a mocked
 * {@link NomenclatureImportService}. No database, no Panache finder and no
 * Quarkus context is booted; the single collaborator is a Mockito mock wired
 * into the package-private injection point, and the private helpers are
 * reached by reflection so every arm of every guard carries an absolute
 * expected value.
 */
class NomenclatureCsvResourceTest {

    /** The resource under test. */
    private final NomenclatureCsvResource resource = new NomenclatureCsvResource();

    /** The mocked loader the resource is the HTTP surface of. */
    private final NomenclatureImportService service = mock(NomenclatureImportService.class);

    /**
     * Wires the mocked loader into the resource's injection point.
     */
    NomenclatureCsvResourceTest() {
        resource.nomenclatureImportService = service;
    }

    /**
     * Wraps a text body as a UTF-8 stream the resource can read.
     *
     * @param body the file body
     * @return the stream over the body
     */
    private InputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Invokes a private method of the resource by reflection.
     *
     * @param name the method name
     * @param types the parameter types
     * @return the accessible method
     * @throws Exception when the method does not exist
     */
    private Method privateMethod(String name, Class<?>... types) throws Exception {
        Method method = NomenclatureCsvResource.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    /**
     * A clean outcome carrying the given counts.
     *
     * @param created the created count
     * @param updated the updated count
     * @param levels the level count
     * @return the outcome
     */
    private NomenclatureImportService.Outcome cleanOutcome(int created, int updated, int levels) {
        NomenclatureImportService.Outcome outcome = new NomenclatureImportService.Outcome();
        outcome.created = created;
        outcome.updated = updated;
        outcome.levels = levels;
        return outcome;
    }

    /**
     * {@code importNomenclature} refuses a null scheme code without reading or
     * delegating (first arm of the disjunction true).
     */
    @Test
    void importNomenclatureRefusesNullSchemeCode() {
        var response = resource.importNomenclature(null, "Label", "ITM",
                stream("NIVEAU|NUMERO|LIBELLE\n"));
        assertEquals(400, response.getStatus());
        assertEquals("{\"errors\":[\"Le paramètre scheme est obligatoire.\"]}",
                String.valueOf(response.getEntity()));
        verifyNoInteractions(service);
    }

    /**
     * {@code importNomenclature} refuses a blank scheme code (first arm false,
     * second arm true).
     */
    @Test
    void importNomenclatureRefusesBlankSchemeCode() {
        var response = resource.importNomenclature("   ", "Label", "ITM",
                stream("NIVEAU|NUMERO|LIBELLE\n"));
        assertEquals(400, response.getStatus());
        assertEquals("{\"errors\":[\"Le paramètre scheme est obligatoire.\"]}",
                String.valueOf(response.getEntity()));
        verifyNoInteractions(service);
    }

    /**
     * A reading that files complaints answers a 400 carrying every complaint,
     * without delegating (non-empty complaints arm; multi-error JSON loop).
     */
    @Test
    void importNomenclatureAnswers400WhenTheReadingComplains() {
        var response = resource.importNomenclature("ITM_FR", "Label", "ITM",
                stream("SOURCE|FOO\nignored\n"));
        assertEquals(400, response.getStatus());
        String body = String.valueOf(response.getEntity());
        assertEquals("{\"createdCount\":0,\"updatedCount\":0,\"levelCount\":0,"
                + "\"errors\":[\"Colonne NIVEAU absente.\",\"Colonne NUMERO absente.\","
                + "\"Colonne LIBELLE absente.\"]}", body);
        verifyNoInteractions(service);
    }

    /**
     * A clean reading whose load is clean answers a 200 with the outcome
     * counts (empty-complaints arm; clean ternary arm).
     */
    @Test
    void importNomenclatureAnswers200OnACleanLoad() {
        when(service.load(eq("ITM_FR"), eq("Label"), eq("ITM"), any()))
                .thenReturn(cleanOutcome(2, 1, 3));
        var response = resource.importNomenclature("ITM_FR", "Label", "ITM",
                stream("SOURCE|NIVEAU|NUMERO|LIBELLE\nNATIONAL|0|01|Frais\n"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":2,\"updatedCount\":1,\"levelCount\":3,\"errors\":[]}",
                String.valueOf(response.getEntity()));
    }

    /**
     * A clean reading whose load refuses at least one node answers a 409 (the
     * conflict ternary arm), the JSON carrying the refusals.
     */
    @Test
    void importNomenclatureAnswers409OnALoadWithErrors() {
        NomenclatureImportService.Outcome outcome = cleanOutcome(1, 0, 2);
        outcome.errors.add("Niveau 0 : code vide.");
        when(service.load(eq("ITM_FR"), any(), any(), any())).thenReturn(outcome);
        var response = resource.importNomenclature("ITM_FR", null, null,
                stream("NIVEAU|NUMERO|LIBELLE\n0|01|Frais\n"));
        assertEquals(409, response.getStatus());
        assertEquals("{\"createdCount\":1,\"updatedCount\":0,\"levelCount\":2,"
                + "\"errors\":[\"Niveau 0 : code vide.\"]}",
                String.valueOf(response.getEntity()));
    }

    /**
     * {@code read} files a single complaint and reads nothing when the stream
     * is absent (null-stream arm true).
     */
    @Test
    void readComplainsWhenTheStreamIsAbsent() {
        List<String> complaints = new ArrayList<>();
        List<NomenclatureImportService.Row> rows = resource.read(null, complaints);
        assertTrue(rows.isEmpty());
        assertEquals(List.of("Fichier absent."), complaints);
    }

    /**
     * {@code read} files a single complaint on an empty stream, whose header
     * line is null (null-header arm true).
     */
    @Test
    void readComplainsWhenTheFileIsEmpty() {
        List<String> complaints = new ArrayList<>();
        List<NomenclatureImportService.Row> rows = resource.read(stream(""), complaints);
        assertTrue(rows.isEmpty());
        assertEquals(List.of("Fichier vide."), complaints);
    }

    /**
     * {@code read} names each required column that the header omits and reads
     * no line (missing-column arm true; non-empty-complaints early return).
     */
    @Test
    void readNamesEveryMissingRequiredColumn() {
        List<String> complaints = new ArrayList<>();
        List<NomenclatureImportService.Row> rows =
                resource.read(stream("SOURCE|OTHER\nX|Y\n"), complaints);
        assertTrue(rows.isEmpty());
        assertEquals(List.of("Colonne NIVEAU absente.", "Colonne NUMERO absente.",
                "Colonne LIBELLE absente."), complaints);
    }

    /**
     * A well-formed body yields one row per data line: the header present arm,
     * the readable-level arm, and the CUSTOM source flag are all exercised.
     */
    @Test
    void readParsesWellFormedRows() {
        List<String> complaints = new ArrayList<>();
        List<NomenclatureImportService.Row> rows = resource.read(
                stream("SOURCE|NIVEAU|NUMERO|LIBELLE\nNATIONAL|0|01|Frais\nCUSTOM|1|0102|Bio\n"),
                complaints);
        assertTrue(complaints.isEmpty());
        assertEquals(2, rows.size());
        assertEquals(0, rows.get(0).level);
        assertEquals("01", rows.get(0).code);
        assertEquals("Frais", rows.get(0).label);
        assertFalse(rows.get(0).custom);
        assertEquals(1, rows.get(1).level);
        assertEquals("0102", rows.get(1).code);
        assertEquals("Bio", rows.get(1).label);
        assertTrue(rows.get(1).custom);
    }

    /**
     * A blank data line is skipped without producing a row (blank-line arm
     * true), while the non-blank line around it is kept.
     */
    @Test
    void readSkipsBlankDataLines() {
        List<String> complaints = new ArrayList<>();
        List<NomenclatureImportService.Row> rows = resource.read(
                stream("NIVEAU|NUMERO|LIBELLE\n   \n0|01|Frais\n"), complaints);
        assertTrue(complaints.isEmpty());
        assertEquals(1, rows.size());
        assertEquals("01", rows.get(0).code);
    }

    /**
     * A line whose level is not a number is refused with a numbered complaint
     * and produces no row (level-null arm true).
     */
    @Test
    void readComplainsAboutAnUnreadableLevel() {
        List<String> complaints = new ArrayList<>();
        List<NomenclatureImportService.Row> rows = resource.read(
                stream("NIVEAU|NUMERO|LIBELLE\nX|01|Frais\n"), complaints);
        assertEquals(List.of("Ligne 2 : niveau illisible."), complaints);
        assertTrue(rows.isEmpty());
    }

    /**
     * A stream that throws while being read leaves the reading with a
     * complaint quoting the failure message (the IOException catch arm).
     */
    @Test
    void readComplainsWhenTheStreamThrows() {
        InputStream boom = new InputStream() {
            /**
             * Fails every single-byte read.
             *
             * @return never
             * @throws IOException always
             */
            @Override
            public int read() throws IOException {
                throw new IOException("cable coupé");
            }

            /**
             * Fails every bulk read.
             *
             * @param b the buffer
             * @param off the offset
             * @param len the length
             * @return never
             * @throws IOException always
             */
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("cable coupé");
            }
        };
        List<String> complaints = new ArrayList<>();
        List<NomenclatureImportService.Row> rows = resource.read(boom, complaints);
        assertTrue(rows.isEmpty());
        assertEquals(List.of("Lecture interrompue : cable coupé"), complaints);
    }

    /**
     * {@code cell} answers the empty string when the column is absent from the
     * header (first arm of the disjunction true).
     */
    @Test
    void cellAnswersEmptyOnANullIndex() throws Exception {
        Method method = privateMethod("cell", String[].class, Integer.class);
        assertEquals("", method.invoke(resource, new String[]{"a", "b"}, (Integer) null));
    }

    /**
     * {@code cell} answers the empty string when the line is shorter than the
     * column position (second arm true).
     */
    @Test
    void cellAnswersEmptyOnAShortLine() throws Exception {
        Method method = privateMethod("cell", String[].class, Integer.class);
        assertEquals("", method.invoke(resource, new String[]{"a"}, 3));
    }

    /**
     * {@code cell} answers the empty string when the addressed cell is null
     * (third arm true).
     */
    @Test
    void cellAnswersEmptyOnANullCell() throws Exception {
        Method method = privateMethod("cell", String[].class, Integer.class);
        assertEquals("", method.invoke(resource, new String[]{null, "b"}, 0));
    }

    /**
     * {@code cell} trims and returns a present cell (every disjunction arm
     * false).
     */
    @Test
    void cellTrimsAPresentCell() throws Exception {
        Method method = privateMethod("cell", String[].class, Integer.class);
        assertEquals("value", method.invoke(resource, new String[]{" value "}, 0));
    }

    /**
     * {@code levelOf} reads a well-formed level (the nominal arm).
     */
    @Test
    void levelOfReadsAWellFormedLevel() throws Exception {
        Method method = privateMethod("levelOf", String[].class, Integer.class);
        assertEquals(2, method.invoke(resource, new String[]{" 2 "}, 0));
    }

    /**
     * {@code levelOf} answers null when the cell is not a number (the catch
     * arm).
     */
    @Test
    void levelOfAnswersNullOnANonNumber() throws Exception {
        Method method = privateMethod("levelOf", String[].class, Integer.class);
        assertNull(method.invoke(resource, new String[]{"abc"}, 0));
    }

    /**
     * {@code columns} maps each header name to its position, upper-cased and
     * trimmed, exercising the mapping loop.
     */
    @Test
    void columnsMapEachNameToItsPosition() throws Exception {
        Method method = privateMethod("columns", String.class);
        @SuppressWarnings("unchecked")
        Map<String, Integer> positions =
                (Map<String, Integer>) method.invoke(resource, " source | Niveau |numero");
        Map<String, Integer> expected = new HashMap<>();
        expected.put("SOURCE", 0);
        expected.put("NIVEAU", 1);
        expected.put("NUMERO", 2);
        assertEquals(expected, positions);
    }

    /**
     * {@code json} answers the empty-errors body when nothing was refused (the
     * error loop is not entered).
     */
    @Test
    void jsonRendersAnEmptyErrorList() throws Exception {
        Method method = privateMethod("json", int.class, int.class, int.class, List.class);
        assertEquals("{\"createdCount\":1,\"updatedCount\":2,\"levelCount\":3,\"errors\":[]}",
                method.invoke(resource, 1, 2, 3, List.of()));
    }

    /**
     * {@code json} writes valid JSON through the JSON library: errors are
     * separated and escaped (backslash, quote, line break), and a real parser
     * reads every field back.
     *
     * @throws Exception never: the answer parses
     */
    @Test
    void jsonSeparatesAndEscapesErrors() throws Exception {
        Method method = privateMethod("json", int.class, int.class, int.class, List.class);
        List<String> errors = List.of("a\\b", "c\"d");
        assertEquals("{\"createdCount\":0,\"updatedCount\":0,\"levelCount\":0,"
                + "\"errors\":[\"a\\\\b\",\"c\\\"d\"]}",
                method.invoke(resource, 0, 0, 0, errors));
        com.fasterxml.jackson.databind.JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree((String) method.invoke(resource, 1, 2, 3, List.of("x\ny")));
        assertEquals(1, parsed.get("createdCount").asInt());
        assertEquals(3, parsed.get("levelCount").asInt());
        assertEquals("x\ny", parsed.get("errors").get(0).asText());
    }

    /**
     * The refusal is built by the JSON library: a quote, a backslash or a line
     * break in the complaint read back unchanged with a real parser.
     *
     * @throws Exception when the answer is not valid JSON
     */
    @Test
    void theRefusalIsAValidDocument() throws Exception {
        com.fasterxml.jackson.databind.JsonNode answer = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(NomenclatureCsvResource.errorsAnswer("a\"b\\c\nd"));
        assertEquals(1, answer.get("errors").size());
        assertEquals("a\"b\\c\nd", answer.get("errors").get(0).asText());
    }
}
