package com.intermarche.edge.imports;

import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PdvHistory;
import com.intermarche.edge.service.PdvRegistryService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PdvCsvResource} (BO-02-05-03).
 * <p>
 * The registry is a mock; {@code Pdv.findByNumber} resolves to
 * {@link PanacheEntityBase} and is intercepted by a static mock. Branches: the
 * line naming a previous number held, not held while the new one is, and held
 * by none; the line already applied skipped; the country and the enseigne
 * present or absent, their language columns present or absent; the active
 * column absent, false and anything else; a refusal of the registry; a
 * creation and an update counted apart.
 */
class PdvCsvResourceTest {

    /** The full header of the feed. */
    private static final List<String> FULL = List.of("PDV_NUMBER", "NAME", "PREVIOUS_NUMBER", "ENSEIGNE_CODE",
            "ENSEIGNE_NAME", "ENSEIGNE_LANGUAGE", "COUNTRY_CODE", "COUNTRY_NAME", "COUNTRY_LANGUAGE",
            "ADHERENT_CODE", "ACTIVE");

    /**
     * Builds the importer over a mocked registry.
     *
     * @return the importer
     */
    private PdvCsvResource newResource() {
        PdvCsvResource resource = new PdvCsvResource();
        resource.pdvRegistry = mock(PdvRegistryService.class);
        return resource;
    }

    /**
     * Builds a parsed line over a header.
     *
     * @param columns the header
     * @param cells the cells, in the header's order
     * @return the line
     */
    private ImporterCsvResource.LineData line(List<String> columns, String... cells) {
        Map<String, Integer> header = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            header.put(columns.get(i), i);
        }
        return new ImporterCsvResource.LineData(1, header, cells, "PDV_NUMBER");
    }

    /**
     * Stubs the point of sale carrying a number.
     *
     * @param panache the active static mock
     * @param number the number
     * @param pdv the point of sale, or null
     */
    @SuppressWarnings("unchecked")
    private void stubPdv(MockedStatic<PanacheEntityBase> panache, String number, Pdv pdv) {
        PanacheQuery<Pdv> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(pdv);
        panache.when(() -> Pdv.find("pdvNumber", number)).thenReturn(query);
    }

    /**
     * A full line builds its country and its enseigne, then creates the point
     * of sale through the registry, stamps it with the line's checksum and
     * counts a creation.
     */
    @Test
    void aFullLineBuildsTheTreeAndCreatesThePointOfSale() {
        PdvCsvResource resource = newResource();
        Pdv created = new Pdv();
        ImporterCsvResource.LineData data = line(FULL, "10001", "Porto", "", "IP", "Intermarché PT", "pt",
                "PT", "Portugal", "pt", "AD1", "true");
        when(resource.pdvRegistry.save("10001", null, "Porto", "IP", "AD1", true, PdvHistory.Source.IMPORT))
                .thenReturn(new PdvRegistryService.Result(true, "ok", created, true));
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "10001", null);
            resource.processLineLogic(data, Map.of(), counters);
        }
        verify(resource.pdvRegistry).upsertCountry("PT", "Portugal", "pt");
        verify(resource.pdvRegistry).upsertEnseigne("IP", "Intermarché PT", "PT", "pt");
        assertEquals(PdvCsvResource.fingerprint(data), created.importChecksum);
        assertEquals(1, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * A minimal line touches no country and no enseigne, reads the absent
     * active column as active and counts an update.
     */
    @Test
    void aMinimalLineUpdatesThePointOfSale() {
        PdvCsvResource resource = newResource();
        Pdv held = new Pdv();
        held.importChecksum = 1;
        when(resource.pdvRegistry.save("10001", null, "Porto", null, null, true, PdvHistory.Source.IMPORT))
                .thenReturn(new PdvRegistryService.Result(true, "ok", held, false));
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "10001", held);
            resource.processLineLogic(line(List.of("PDV_NUMBER", "NAME"), "10001", "Porto"), Map.of(), counters);
        }
        verify(resource.pdvRegistry, never()).upsertCountry(anyString(), any(), any());
        verify(resource.pdvRegistry, never()).upsertEnseigne(anyString(), any(), any(), any());
        assertEquals(0, counters[0]);
        assertEquals(1, counters[1]);
    }

    /**
     * The very line last applied to a point of sale is skipped: a replay never
     * undoes a correction made since on the screen.
     */
    @Test
    void theLineLastAppliedIsSkipped() {
        PdvCsvResource resource = newResource();
        ImporterCsvResource.LineData data = line(List.of("PDV_NUMBER", "NAME"), "10001", "Porto");
        Pdv held = new Pdv();
        held.importChecksum = PdvCsvResource.fingerprint(data);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "10001", held);
            resource.processLineLogic(data, Map.of(), counters);
        }
        verifyNoInteractions(resource.pdvRegistry);
        assertEquals(0, counters[0] + counters[1]);
    }

    /**
     * A line naming a held previous number renumbers it, the language columns
     * present but empty, the active column false.
     */
    @Test
    void aPreviousNumberHeldIsRenumbered() {
        PdvCsvResource resource = newResource();
        List<String> columns = List.of("PDV_NUMBER", "NAME", "PREVIOUS_NUMBER", "COUNTRY_CODE", "COUNTRY_LANGUAGE",
                "ENSEIGNE_CODE", "ENSEIGNE_LANGUAGE", "ACTIVE");
        Pdv held = new Pdv();
        when(resource.pdvRegistry.save("20002", "10001", "Porto", "IP", null, false, PdvHistory.Source.IMPORT))
                .thenReturn(new PdvRegistryService.Result(true, "ok", held, false));
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "10001", held);
            resource.processLineLogic(line(columns, "20002", "Porto", "10001", "PT", "", "IP", "", "FALSE"),
                    Map.of(), counters);
        }
        verify(resource.pdvRegistry).upsertCountry("PT", null, "");
        verify(resource.pdvRegistry).upsertEnseigne("IP", null, "PT", "");
        assertEquals(1, counters[1]);
    }

    /**
     * A previous number no longer held while the new one is: the renumbering
     * is done, the line updates the point of sale under its new number; a
     * previous number nobody holds goes to the registry, which refuses it, and
     * the refusal costs the line.
     */
    @Test
    void aRenumberingAlreadyDoneUpdatesAndAnUnknownOneIsRefused() {
        PdvCsvResource resource = newResource();
        List<String> columns = List.of("PDV_NUMBER", "NAME", "PREVIOUS_NUMBER", "ACTIVE");
        Pdv renumbered = new Pdv();
        when(resource.pdvRegistry.save("20002", null, "Porto", null, null, true, PdvHistory.Source.IMPORT))
                .thenReturn(new PdvRegistryService.Result(true, "ok", renumbered, false));
        when(resource.pdvRegistry.save("30003", "10003", "Faro", null, null, true, PdvHistory.Source.IMPORT))
                .thenReturn(new PdvRegistryService.Result(false, "PDV à renommer introuvable.", null, false));
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "10001", null);
            stubPdv(panache, "20002", renumbered);
            stubPdv(panache, "10003", null);
            stubPdv(panache, "30003", null);
            resource.processLineLogic(line(columns, "20002", "Porto", "10001", "oui"), Map.of(), counters);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> resource.processLineLogic(line(columns, "30003", "Faro", "10003", "1"),
                            Map.of(), counters));
            assertEquals("PDV à renommer introuvable.", refused.getMessage());
        }
        assertEquals(1, counters[1]);
    }

    /**
     * The chunk prepares nothing, the fallback finds the point of sale by its
     * number, and the entry point reads the stream with the number as key.
     */
    @Test
    void theBaseHooksAreWired() {
        PdvCsvResource resource = newResource();
        assertTrue(resource.processChunkWithFallback(List.of(), Set.of(), new int[2], List.of()).isEmpty());
        Pdv held = new Pdv();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "10001", held);
            assertSame(held, resource.findEntityForLine(line(List.of("PDV_NUMBER", "NAME"), "10001", "x")));
        }
        Response response = resource.importPdvs(new ByteArrayInputStream("NAME\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(400, response.getStatus());
        verify(resource.pdvRegistry, never()).save(anyString(), isNull(), anyString(), any(), any(), anyBoolean(),
                any());
    }
    /**
     * A line naming a country and an enseigne without their language columns
     * leaves both languages as they are held.
     */
    @Test
    void absentLanguageColumnsKeepTheLanguagesHeld() {
        PdvCsvResource resource = newResource();
        List<String> columns = List.of("PDV_NUMBER", "NAME", "COUNTRY_CODE", "ENSEIGNE_CODE");
        when(resource.pdvRegistry.save("10001", null, "Porto", "IP", null, true, PdvHistory.Source.IMPORT))
                .thenReturn(new PdvRegistryService.Result(true, "ok", new Pdv(), true));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "10001", null);
            resource.processLineLogic(line(columns, "10001", "Porto", "PT", "IP"), Map.of(), new int[2]);
        }
        verify(resource.pdvRegistry).upsertCountry("PT", null, null);
        verify(resource.pdvRegistry).upsertEnseigne("IP", null, "PT", null);
    }
}
