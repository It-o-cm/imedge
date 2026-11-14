package com.intermarche.edge.service;

import com.intermarche.edge.service.sync.SyncPayloads;
import com.intermarche.edge.service.sync.store.SyncIngestService;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.junit.jupiter.api.Test;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BalanceFtpService} (BO-03-06-56), targeting 100%
 * branch coverage.
 *
 * <p>The FTP client is a Mockito mock handed through the service's supplier;
 * the administered settings and the ingestion are mocks too. Branch
 * enumeration: the loop started on a store node only, stopped with and without
 * an executor; a round skipped for a zero interval, a missing server, a round
 * not yet due, and a failing one swallowed; a refused account, an unknown
 * directory, no directory at all; a directory entry, a file already read, an
 * unreadable file and a readable one; a connection closed or left open; and
 * every line the parser keeps or skips.
 */
class BalanceFtpServiceTest {

    /**
     * A service over mocked settings, ingestion and FTP client.
     *
     * @param ftp the FTP client the supplier hands out
     * @param interval the administered interval, in seconds
     * @param host the administered server
     * @param directory the administered directory
     * @return the service
     */
    private BalanceFtpService service(FTPClient ftp, String interval, String host, String directory) {
        BalanceFtpService service = new BalanceFtpService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.value("balance.ftp-interval-seconds")).thenReturn(interval);
        when(service.posSettingsService.value("balance.ftp-host")).thenReturn(host);
        when(service.posSettingsService.value("balance.ftp-port")).thenReturn("2121");
        when(service.posSettingsService.value("balance.ftp-user")).thenReturn("balance");
        when(service.posSettingsService.value("balance.ftp-password")).thenReturn("secret");
        when(service.posSettingsService.value("balance.ftp-directory")).thenReturn(directory);
        service.syncIngestService = mock(SyncIngestService.class);
        service.clients = () -> ftp;
        service.role = "store";
        return service;
    }

    /**
     * The administered values a round reads, as the service reads them.
     *
     * @param service the service
     * @return the values, by setting key
     */
    private Map<String, String> settings(BalanceFtpService service) {
        return service.settings();
    }

    /**
     * Runs the service's ticks inside a mocked transaction runner that simply
     * calls what it is handed.
     *
     * @param ticks the ticks to run
     */
    @SuppressWarnings("unchecked")
    private void inTransaction(Runnable ticks) {
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        when(runner.call(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return callable.call();
        });
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            ticks.run();
        }
    }

    /**
     * A file entry of the listing.
     *
     * @param name the file name
     * @param type the FTP entry type
     * @return the entry
     */
    private FTPFile entry(String name, int type) {
        FTPFile file = new FTPFile();
        file.setName(name);
        file.setType(type);
        return file;
    }

    /**
     * Reads the loop executor.
     *
     * @param service the service
     * @return the executor, or null
     * @throws Exception if reflection fails
     */
    private ScheduledExecutorService executor(BalanceFtpService service) throws Exception {
        Field field = BalanceFtpService.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(service);
    }

    /**
     * The loop runs on a store node only, and stops with or without having
     * started.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void theLoopRunsOnAStoreNodeOnly() throws Exception {
        BalanceFtpService central = service(mock(FTPClient.class), "0", "", "/");
        central.role = "central";
        central.onStart(null);
        assertNull(executor(central));
        central.onStop();
        BalanceFtpService store = service(mock(FTPClient.class), "0", "", "/");
        store.role = "STORE";
        store.onStart(null);
        ScheduledExecutorService running = executor(store);
        assertFalse(running.isShutdown());
        store.onStop();
        assertTrue(running.isShutdown());
        assertNull(executor(store));
    }

    /**
     * No round runs without an interval or without a server; a due round
     * fetches, the next one waits for its interval; a failing round is
     * swallowed so the loop survives.
     *
     * @throws Exception never, the client is mocked
     */
    @Test
    void aRoundRunsOnlyWhenDue() throws Exception {
        FTPClient idle = mock(FTPClient.class);
        FTPClient ftp = mock(FTPClient.class);
        when(ftp.login("balance", "secret")).thenReturn(true);
        when(ftp.changeWorkingDirectory("/balances")).thenReturn(true);
        when(ftp.listFiles()).thenReturn(new FTPFile[0]);
        BalanceFtpService service = service(ftp, "3600", "ftp.magasin", "/balances");
        FTPClient refused = mock(FTPClient.class);
        when(refused.login(anyString(), anyString())).thenReturn(false);
        inTransaction(() -> {
            service(idle, "0", "ftp.magasin", "/").tick();
            service(idle, "x", "ftp.magasin", "/").tick();
            service(idle, "60", "  ", "/").tick();
            // BO-10-05-01: the API-only and no-ticket modes read no FTP server.
            BalanceFtpService apiOnly = service(idle, "60", "ftp.magasin", "/");
            when(apiOnly.posSettingsService.value("balance.ticket-mode")).thenReturn(" api ");
            apiOnly.tick();
            BalanceFtpService off = service(idle, "60", "ftp.magasin", "/");
            when(off.posSettingsService.value("balance.ticket-mode")).thenReturn("OFF");
            off.tick();
            when(service.posSettingsService.value("balance.ticket-mode")).thenReturn("FTP");
            service.tick();
            service.tick();
            service(refused, "1", "ftp.magasin", "/").tick();
        });
        verifyNoInteractions(idle);
        verify(ftp, times(1)).connect("ftp.magasin", 2121);
        verify(refused).connect("ftp.magasin", 2121);
    }

    /**
     * A refused account and an unknown directory fail the round, the
     * connection being closed when it is open and left alone when it is not.
     *
     * @throws Exception never, the client is mocked
     */
    @Test
    void aRefusedAccountOrAnUnknownDirectoryFailsTheRound() throws Exception {
        FTPClient refused = mock(FTPClient.class);
        when(refused.login("balance", "secret")).thenReturn(false);
        when(refused.isConnected()).thenReturn(true);
        BalanceFtpService first = service(refused, "60", " ftp.magasin ", "/");
        IOException account = assertThrows(IOException.class, () -> first.fetch(settings(first)));
        assertEquals("Compte FTP refusé", account.getMessage());
        verify(refused).connect("ftp.magasin", 2121);
        verify(refused).disconnect();
        FTPClient lost = mock(FTPClient.class);
        when(lost.login("balance", "secret")).thenReturn(true);
        when(lost.changeWorkingDirectory("/nulle-part")).thenReturn(false);
        BalanceFtpService second = service(lost, "60", "ftp.magasin", " /nulle-part ");
        IOException directory = assertThrows(IOException.class, () -> second.fetch(settings(second)));
        assertEquals("Répertoire FTP introuvable : /nulle-part", directory.getMessage());
        verify(lost, never()).disconnect();
    }

    /**
     * A round reads every new file once: a directory and a file already read
     * are passed over, an unreadable file is left for the next round, a
     * readable one is ingested bag by bag and renamed; with no directory the
     * server's own is read.
     *
     * @throws Exception never, the client is mocked
     */
    @Test
    void aRoundIngestsEveryNewFileOnce() throws Exception {
        FTPClient ftp = mock(FTPClient.class);
        when(ftp.login("balance", "secret")).thenReturn(true);
        when(ftp.listFiles()).thenReturn(new FTPFile[]{
                entry("archives", FTPFile.DIRECTORY_TYPE),
                entry("drop1.csv.done", FTPFile.FILE_TYPE),
                entry("broken.csv", FTPFile.FILE_TYPE),
                entry("drop2.csv", FTPFile.FILE_TYPE)});
        when(ftp.retrieveFile(eq("broken.csv"), any(OutputStream.class))).thenReturn(false);
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write(("reference;counter;ean;label;quantity;unit;price\n"
                    + "B1;Boucherie;2001234000000;Entrecôte;0,512;KG;12.80\n"
                    + "B2;Fromagerie;2005678000000;Comté;1;U;4,50\n").getBytes(StandardCharsets.UTF_8));
            return true;
        }).when(ftp).retrieveFile(eq("drop2.csv"), any(OutputStream.class));
        when(ftp.isConnected()).thenReturn(true);
        BalanceFtpService service = service(ftp, "60", "ftp.magasin", "  ");
        assertEquals(2, service.fetch(settings(service)));
        verify(ftp, never()).changeWorkingDirectory(anyString());
        verify(ftp).enterLocalPassiveMode();
        ArgumentCaptor<SyncPayloads.BalanceTicketDto> bags = ArgumentCaptor.forClass(SyncPayloads.BalanceTicketDto.class);
        verify(service.syncIngestService, times(2)).ingestBalanceTicket(bags.capture());
        assertEquals("B1", bags.getAllValues().get(0).reference);
        assertEquals("Fromagerie", bags.getAllValues().get(1).counterLabel);
        verify(ftp).rename("drop2.csv", "drop2.csv.done");
        verify(ftp, never()).rename(eq("broken.csv"), anyString());
        verify(ftp, never()).retrieveFile(eq("drop1.csv.done"), any(OutputStream.class));
        verify(ftp).logout();
        verify(ftp).disconnect();
    }

    /**
     * The parser groups the lines of a bag, keeps the order of the bags, and
     * skips a short line, a line without reference or EAN, and a line whose
     * quantity or price is not a number.
     */
    @Test
    void theParserKeepsTheReadableLines() {
        List<SyncPayloads.BalanceTicketDto> bags = BalanceFtpService.parse(
                "header\r\n"
                + "B1; Boucherie ;2001234000000; Entrecôte ;0,512;KG;12.80\r\n"
                + "short;line\r\n"
                + " ;X;2001234000000;Y;1;U;1\r\n"
                + "B1;X; ;Y;1;U;1\r\n"
                + "B1;X;2001234000000;Y;un;U;1\r\n"
                + "B1;X;2001234000000;Y;1;U;cher\r\n"
                + "B2;Fromagerie;2005678000000;Comté;1;U;4,50\r\n"
                + "B1;Boucherie;2009999000000;Bavette;0.300;KG;5.10");
        assertEquals(2, bags.size());
        SyncPayloads.BalanceTicketDto first = bags.get(0);
        assertEquals("B1", first.reference);
        assertEquals("Boucherie", first.counterLabel);
        assertEquals(2, first.lines.size());
        assertEquals("2001234000000", first.lines.get(0).ean);
        assertEquals("Entrecôte", first.lines.get(0).label);
        assertEquals(new BigDecimal("0.512"), first.lines.get(0).quantity);
        assertEquals(new BigDecimal("12.80"), first.lines.get(0).totalIncludingTax);
        assertEquals("Bavette", first.lines.get(1).label);
        assertEquals("B2", bags.get(1).reference);
        assertEquals(new BigDecimal("4.50"), bags.get(1).lines.get(0).totalIncludingTax);
        assertTrue(BalanceFtpService.parse("header only").isEmpty());
    }

    /**
     * The number readers take a dot or a comma, a padded whole number, and
     * fall back on anything else.
     */
    @Test
    void theNumberReaders() {
        assertEquals(new BigDecimal("1.5"), BalanceFtpService.decimal(" 1,5 "));
        assertNull(BalanceFtpService.decimal("abc"));
        assertEquals(21, BalanceFtpService.number(" 21 ", 0));
        assertEquals(7, BalanceFtpService.number("x", 7));
        assertEquals(7, BalanceFtpService.number(null, 7));
    }
}
