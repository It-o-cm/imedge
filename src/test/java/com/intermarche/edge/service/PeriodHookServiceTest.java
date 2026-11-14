package com.intermarche.edge.service;

import com.intermarche.edge.domain.session.PeriodHookRun;
import com.intermarche.edge.domain.session.TechnicalEvent;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PeriodHookService} (BO-09-02-04): the administered
 * script of each phase runs from the entry-point directory with the phase and
 * the day, its exit code and output are kept and journalled; a disabled or
 * unnamed entry point runs nothing; a refused name, a missing or
 * non-executable script, a script that cannot start, overruns or is
 * interrupted is kept as a failure. Real scripts run from a temporary
 * directory.
 */
class PeriodHookServiceTest {

    /** The day whose end runs the scripts. */
    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    /** The entry-point directory of the test. */
    @TempDir
    Path hooks;

    /**
     * A spied service over the temporary directory, entry points on, the
     * given scripts, a five-second limit and persistence stubbed.
     *
     * @param before the script before
     * @param after the script after
     * @return the service
     */
    private PeriodHookService service(String before, String after) {
        PeriodHookService service = spy(new PeriodHookService());
        service.posSettingsService = mock(PosSettingsService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.hooksDir = hooks.toString();
        when(service.posSettingsService.periodHooksEnabled()).thenReturn(true);
        when(service.posSettingsService.periodHookBefore()).thenReturn(before);
        when(service.posSettingsService.periodHookAfter()).thenReturn(after);
        when(service.posSettingsService.periodHookTimeoutSeconds()).thenReturn(5);
        doNothing().when(service).save(any());
        return service;
    }

    /**
     * Writes an executable script.
     *
     * @param name the file name
     * @param body the script
     * @throws IOException when it cannot be written
     */
    private void script(String name, String body) throws IOException {
        Path file = hooks.resolve(name);
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /**
     * The script before runs with its phase, day and launcher, its output and
     * zero exit code kept, the run persisted and journalled.
     */
    @Test
    void theScriptBeforeRuns() throws IOException {
        script("before.sh", "#!/bin/sh\necho \"$1 $2 $PERIOD_PHASE $PERIOD_DAY $PERIOD_LAUNCHED_BY\"\n");
        PeriodHookService service = service("before.sh", "");
        PeriodHookRun run = service.run(PeriodHookRun.Phase.BEFORE, DAY, "Marie Curie");
        assertTrue(run.succeeded);
        assertEquals(0, run.exitCode);
        assertEquals("BEFORE 2026-10-24 BEFORE 2026-10-24 Marie Curie", run.output);
        assertEquals("before.sh", run.script);
        assertEquals("Marie Curie", run.launchedBy);
        assertEquals(DAY, run.periodDay);
        verify(service).save(run);
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Point d'entrée avant fin de période before.sh : exécuté");
    }

    /**
     * The script after that fails is kept with its exit code and journalled as
     * a failure.
     */
    @Test
    void aFailingScriptAfterIsKeptAsAFailure() throws IOException {
        script("after.sh", "#!/bin/sh\necho oups\nexit 3\n");
        PeriodHookRun run = service("", "after.sh").run(PeriodHookRun.Phase.AFTER, DAY, "auto");
        assertFalse(run.succeeded);
        assertEquals(3, run.exitCode);
        assertEquals("oups", run.output);
    }

    /**
     * Nothing runs while the entry points are off or no script is named.
     */
    @Test
    void nothingRunsWithoutAnEntryPoint() {
        PeriodHookService service = service("", "after.sh");
        assertNull(service.run(PeriodHookRun.Phase.BEFORE, DAY, "auto"));
        when(service.posSettingsService.periodHooksEnabled()).thenReturn(false);
        assertNull(service.run(PeriodHookRun.Phase.AFTER, DAY, "auto"));
        verify(service, never()).save(any());
        verify(service.technicalEventService, never()).log(any(), any());
    }

    /**
     * A name carrying a path or starting with a dot is refused, a script
     * missing or not executable is not found, a too long name is cut; each is
     * kept as a failure, journalled with exit code -1.
     */
    @Test
    void aRefusedOrMissingScriptIsAFailure() throws IOException {
        assertEquals("Nom de script refusé", service("../evil.sh", "").run(PeriodHookRun.Phase.BEFORE, DAY, "a").output);
        assertEquals("Nom de script refusé", service(".hidden", "").run(PeriodHookRun.Phase.BEFORE, DAY, "a").output);
        PeriodHookService missing = service("absent.sh", "");
        PeriodHookRun run = missing.run(PeriodHookRun.Phase.BEFORE, DAY, "a");
        assertEquals("Script introuvable ou non exécutable dans le répertoire des points d'entrée", run.output);
        assertEquals(-1, run.exitCode);
        verify(missing.technicalEventService).log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Point d'entrée avant fin de période absent.sh : en échec (-1)");
        Files.writeString(hooks.resolve("plain.sh"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(hooks.resolve("plain.sh"), PosixFilePermissions.fromString("rw-r--r--"));
        assertFalse(service("plain.sh", "").run(PeriodHookRun.Phase.BEFORE, DAY, "a").succeeded);
        String longName = "x".repeat(130);
        assertEquals(120, service(longName, "").run(PeriodHookRun.Phase.BEFORE, DAY, "a").script.length());
    }

    /**
     * A script past the limit is stopped, one that cannot start or whose wait
     * is interrupted is kept with why.
     */
    @Test
    void aScriptThatCannotFinishIsKeptWithWhy() throws IOException {
        script("slow.sh", "#!/bin/sh\nsleep 5\n");
        PeriodHookService slow = service("slow.sh", "");
        when(slow.posSettingsService.periodHookTimeoutSeconds()).thenReturn(1);
        PeriodHookRun stopped = slow.run(PeriodHookRun.Phase.BEFORE, DAY, "a");
        assertEquals("Arrêté : durée maximale dépassée", stopped.output);
        assertEquals(-1, stopped.exitCode);
        script("broken.sh", "#!/nonexistent/interpreter\n");
        PeriodHookRun broken = service("broken.sh", "").run(PeriodHookRun.Phase.BEFORE, DAY, "a");
        assertTrue(broken.output.startsWith("Exécution impossible : "));
        script("wait.sh", "#!/bin/sh\nsleep 2\n");
        PeriodHookService waiting = service("wait.sh", "");
        Thread.currentThread().interrupt();
        PeriodHookRun interrupted = waiting.run(PeriodHookRun.Phase.BEFORE, DAY, "a");
        assertTrue(Thread.interrupted());
        assertEquals("Exécution interrompue", interrupted.output);
    }

    /**
     * The output kept is the head of what the script printed, trimmed.
     */
    @Test
    void theOutputIsTheHeadOfWhatWasPrinted() throws IOException {
        String printed = " " + "y".repeat(2500);
        String head = PeriodHookService.head(new ByteArrayInputStream(printed.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1999, head.length());
    }

    /**
     * The latest runs are read from the table and a run is persisted.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theRunsAreReadAndWritten() {
        PeriodHookService service = new PeriodHookService();
        PanacheQuery<PanacheEntityBase> all = mock(PanacheQuery.class);
        when(all.page(0, 10)).thenReturn(all);
        when(all.list()).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> PanacheEntityBase.find("order by startedAt desc, id desc")).thenReturn(all);
            assertTrue(service.latest(10).isEmpty());
        }
        PeriodHookRun run = mock(PeriodHookRun.class);
        service.save(run);
        verify(run).persist();
    }
}
