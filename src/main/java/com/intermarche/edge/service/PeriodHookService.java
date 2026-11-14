package com.intermarche.edge.service;

import com.intermarche.edge.domain.session.PeriodHookRun;
import com.intermarche.edge.domain.session.TechnicalEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the TECHNICAL ENTRY POINTS of the end of period (BO-09-02-04): the
 * scripts a store administers to meet its specific needs before or after its
 * end-of-period treatment, automatic or launched by hand.
 *
 * <p>The back office names a script, it never types a command: the name is
 * looked up in the entry-point directory of the node
 * ({@code edge.period.hooks-dir}), where only the people running the node can
 * place a file, and a name carrying a path, a space or anything but letters,
 * digits, dots, dashes and underscores is refused. The script receives the
 * phase and the day as its arguments and in {@code PERIOD_PHASE},
 * {@code PERIOD_DAY} and {@code PERIOD_LAUNCHED_BY}; it is stopped past the
 * administered duration. Every run — succeeded, failed, refused or stopped —
 * is kept with the head of its output and written to the electronic journal,
 * and none of them stops the treatment: an entry point serves the treatment,
 * it does not hold it hostage.
 */
@ApplicationScoped
public class PeriodHookService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PeriodHookService.class);

    /** The longest output kept for a run. */
    static final int OUTPUT_LENGTH = 2000;

    /** The administered scripts, activation and duration. */
    @Inject
    PosSettingsService posSettingsService;

    /** The electronic journal. */
    @Inject
    TechnicalEventService technicalEventService;

    /** The entry-point directory of the node. */
    @ConfigProperty(name = "edge.period.hooks-dir", defaultValue = "hooks")
    String hooksDir;

    /**
     * Runs the script administered for a phase, when the entry points are on
     * and one is named.
     *
     * @param phase before or after the treatment
     * @param day the day whose period ends
     * @param launchedBy who launched the end of period
     * @return the run, or null when nothing was to run
     */
    @Transactional
    public PeriodHookRun run(PeriodHookRun.Phase phase, LocalDate day, String launchedBy) {
        LOGGER.info("Entering method run with phase: " + phase + ", day: " + day);
        String script = phase == PeriodHookRun.Phase.BEFORE
                ? posSettingsService.periodHookBefore() : posSettingsService.periodHookAfter();
        if (!posSettingsService.periodHooksEnabled() || script.isEmpty()) {
            LOGGER.info("Exiting method run: no entry point");
            return null;
        }
        PeriodHookRun run = new PeriodHookRun();
        run.periodDay = day;
        run.phase = phase;
        run.script = script.length() > 120 ? script.substring(0, 120) : script;
        run.launchedBy = launchedBy;
        run.startedAt = LocalDateTime.now();
        run.exitCode = -1;
        long started = System.nanoTime();
        if (!script.matches("[A-Za-z0-9._-]+") || script.startsWith(".")) {
            run.output = "Nom de script refusé";
        } else {
            Path file = Path.of(hooksDir).toAbsolutePath().normalize().resolve(script);
            if (!Files.isRegularFile(file) || !Files.isExecutable(file)) {
                run.output = "Script introuvable ou non exécutable dans le répertoire des points d'entrée";
            } else {
                execute(run, file, launchedBy);
            }
        }
        run.durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        run.succeeded = run.exitCode == 0;
        save(run);
        technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Point d'entrée " + (phase == PeriodHookRun.Phase.BEFORE ? "avant" : "après")
                        + " fin de période " + run.script + " : "
                        + (run.succeeded ? "exécuté" : "en échec (" + run.exitCode + ")"));
        LOGGER.info("Exiting method run with exit code " + run.exitCode);
        return run;
    }

    /**
     * Executes a script and writes its outcome on the run.
     *
     * @param run the run, completed in place
     * @param file the script
     * @param launchedBy who launched the end of period
     */
    void execute(PeriodHookRun run, Path file, String launchedBy) {
        try {
            ProcessBuilder builder = new ProcessBuilder(file.toString(), run.phase.name(), run.periodDay.toString())
                    .redirectErrorStream(true)
                    .directory(file.getParent().toFile());
            builder.environment().put("PERIOD_PHASE", run.phase.name());
            builder.environment().put("PERIOD_DAY", run.periodDay.toString());
            builder.environment().put("PERIOD_LAUNCHED_BY", launchedBy);
            Process process = builder.start();
            if (process.waitFor(posSettingsService.periodHookTimeoutSeconds(), TimeUnit.SECONDS)) {
                run.exitCode = process.exitValue();
                run.output = head(process.getInputStream());
            } else {
                process.destroyForcibly();
                run.output = "Arrêté : durée maximale dépassée";
            }
        } catch (IOException failure) {
            run.output = "Exécution impossible : " + failure.getMessage();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            run.output = "Exécution interrompue";
        }
    }

    /**
     * The head of what a script printed.
     *
     * @param output the script's output
     * @return the text, at most {@link #OUTPUT_LENGTH} characters
     * @throws IOException when the output cannot be read
     */
    static String head(InputStream output) throws IOException {
        return new String(output.readNBytes(OUTPUT_LENGTH), StandardCharsets.UTF_8).strip();
    }

    /**
     * The latest runs, for the end-of-day screen.
     *
     * @param limit how many
     * @return the runs, most recent first
     */
    public List<PeriodHookRun> latest(int limit) {
        LOGGER.info("Entering method latest with limit: " + limit);
        LOGGER.info("Exiting method latest");
        return PeriodHookRun.latest(limit);
    }

    /**
     * Persists a run.
     *
     * @param run the run, its mandatory columns set
     */
    void save(PeriodHookRun run) {
        run.persist();
    }
}
