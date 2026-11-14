package com.intermarche.edge.service;

import com.intermarche.edge.service.sync.SyncPayloads;
import com.intermarche.edge.service.sync.store.SyncIngestService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Fetches the counter tickets the shop's scales drop on an FTP server
 * (BO-03-06-56): the bags the service counters made up — their reference, and
 * the articles in them with their price, quantity and unit — so a register
 * scanning a bag's barcode finds it on the store node.
 *
 * <p>The scales write one file per drop, a header line then one line per
 * article: {@code reference;counter;ean;label;quantity;unit;price}, the price
 * being the line's total including tax. A file may carry several bags; each is
 * ingested exactly as a ticket pushed over the API is, upsert by reference.
 * A file read is renamed with the {@code .done} suffix, so it is never read
 * twice; a file that cannot be read is left in place for the next round.
 *
 * <p>Everything is administered at the back office — server, port, account,
 * directory and interval — and an interval of zero, or no server, leaves the
 * scales to the API alone.
 */
@ApplicationScoped
public class BalanceFtpService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(BalanceFtpService.class);

    /** The suffix a read file is renamed with. */
    static final String DONE = ".done";

    /** How often the loop looks at the administered interval, in seconds. */
    static final long TICK_SECONDS = 5;

    /** The administered server, account, directory and interval. */
    @Inject
    PosSettingsService posSettingsService;

    /** The ingestion shared with the scales' API. */
    @Inject
    SyncIngestService syncIngestService;

    /** The node's role: only a store node holds the scales' tickets. */
    @ConfigProperty(name = "pos.role", defaultValue = "store")
    String role;

    /** Makes the FTP connections. */
    Supplier<FTPClient> clients = FTPClient::new;

    /** When the last round started, in epoch milliseconds. */
    private volatile long lastRound = 0L;

    /** The loop executor, or null when the loop is not running. */
    private ScheduledExecutorService executor;

    /**
     * Starts the loop on a store node.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (!"store".equalsIgnoreCase(role)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "balance-ftp");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the loop.
     */
    @PreDestroy
    void onStop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Runs a round when one is due, never letting a failure stop the loop.
     *
     * <p>The loop's thread carries no request context: the administered values
     * are read in a transaction of their own, which gives them one.
     */
    void tick() {
        try {
            Map<String, String> settings = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .call(this::settings);
            int interval = number(settings.get("balance.ftp-interval-seconds"), 0);
            // BO-10-05-01: a shop whose baskets arrive by the API alone, or that
            // takes no counter ticket at all, has no FTP server to read.
            String mode = settings.get("balance.ticket-mode");
            String retrieval = mode == null ? "" : mode.trim().toUpperCase(java.util.Locale.ROOT);
            if (interval <= 0 || settings.get("balance.ftp-host").isBlank()
                    || "API".equals(retrieval) || "OFF".equals(retrieval)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastRound < interval * 1000L) {
                return;
            }
            lastRound = now;
            fetch(settings);
        } catch (Exception e) {
            LOGGER.warnf("Récupération FTP des tickets balance en erreur : %s", e.getMessage());
        }
    }

    /**
     * Reads the administered server, account, directory and interval, and the
     * retrieval mode of the baskets (BO-10-05-01).
     *
     * @return the values, by setting key
     */
    Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>();
        for (String key : List.of("balance.ftp-host", "balance.ftp-port", "balance.ftp-user",
                "balance.ftp-password", "balance.ftp-directory", "balance.ftp-interval-seconds",
                "balance.ticket-mode")) {
            settings.put(key, posSettingsService.value(key));
        }
        return settings;
    }

    /**
     * Reads every new file of the administered directory once.
     *
     * @param settings the administered server, account and directory, by setting key
     * @return the number of bags ingested
     * @throws java.io.IOException when the server cannot be reached or refuses the account
     */
    public int fetch(Map<String, String> settings) throws java.io.IOException {
        LOGGER.info("Entering method fetch");
        FTPClient ftp = clients.get();
        int ingested = 0;
        try {
            ftp.connect(settings.get("balance.ftp-host").trim(),
                    number(settings.get("balance.ftp-port"), 21));
            if (!ftp.login(settings.get("balance.ftp-user"), settings.get("balance.ftp-password"))) {
                throw new java.io.IOException("Compte FTP refusé");
            }
            ftp.enterLocalPassiveMode();
            String directory = settings.get("balance.ftp-directory").trim();
            if (!directory.isEmpty() && !ftp.changeWorkingDirectory(directory)) {
                throw new java.io.IOException("Répertoire FTP introuvable : " + directory);
            }
            for (FTPFile file : ftp.listFiles()) {
                if (!file.isFile() || file.getName().endsWith(DONE)) {
                    continue;
                }
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                if (!ftp.retrieveFile(file.getName(), content)) {
                    LOGGER.warnf("Fichier balance %s illisible : laissé pour le tour suivant", file.getName());
                    continue;
                }
                for (SyncPayloads.BalanceTicketDto bag : parse(content.toString(StandardCharsets.UTF_8))) {
                    syncIngestService.ingestBalanceTicket(bag);
                    ingested++;
                }
                ftp.rename(file.getName(), file.getName() + DONE);
            }
            ftp.logout();
        } finally {
            if (ftp.isConnected()) {
                ftp.disconnect();
            }
        }
        LOGGER.info("Exiting method fetch with " + ingested + " bag(s)");
        return ingested;
    }

    /**
     * Reads the bags of one file: a header line, then one line per article.
     * A line that cannot be read is skipped, the others are kept.
     *
     * @param content the file's text
     * @return the bags, in the order of their first line
     */
    static List<SyncPayloads.BalanceTicketDto> parse(String content) {
        Map<String, SyncPayloads.BalanceTicketDto> bags = new LinkedHashMap<>();
        String[] lines = content.split("\\r?\\n");
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(";", -1);
            if (cells.length < 7 || cells[0].isBlank() || cells[2].isBlank()) {
                continue;
            }
            BigDecimal quantity = decimal(cells[4]);
            BigDecimal price = decimal(cells[6]);
            if (quantity == null || price == null) {
                continue;
            }
            SyncPayloads.BalanceTicketDto bag = bags.computeIfAbsent(cells[0].trim(), reference -> {
                SyncPayloads.BalanceTicketDto created = new SyncPayloads.BalanceTicketDto();
                created.reference = reference;
                created.counterLabel = cells[1].trim();
                created.lines = new ArrayList<>();
                return created;
            });
            SyncPayloads.BalanceTicketLineDto line = new SyncPayloads.BalanceTicketLineDto();
            line.ean = cells[2].trim();
            line.label = cells[3].trim();
            line.quantity = quantity;
            line.totalIncludingTax = price;
            bag.lines.add(line);
        }
        return new ArrayList<>(bags.values());
    }

    /**
     * Reads a decimal written with a dot or a comma.
     *
     * @param raw the text
     * @return the number, or null when the text is not one
     */
    static BigDecimal decimal(String raw) {
        try {
            return new BigDecimal(raw.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads an administered whole number.
     *
     * @param raw the text, possibly null
     * @param fallback the number when the text is not one
     * @return the number
     */
    static int number(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }
}
