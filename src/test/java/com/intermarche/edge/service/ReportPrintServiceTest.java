package com.intermarche.edge.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ReportPrintService} (BO-09-01-10): a document reaches
 * the raw port of the named printer byte for byte; a printer named without a
 * port is reached on 9100; an unreadable or impossible port, and a printer
 * that does not answer, fail the printing.
 */
class ReportPrintServiceTest {

    /**
     * The document reaches the printer as it is.
     */
    @Test
    void theDocumentReachesThePrinter() throws Exception {
        try (ServerSocket printer = new ServerSocket(0)) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                try (Socket job = printer.accept(); InputStream in = job.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "ERREUR " + e.getMessage();
                }
            });
            new ReportPrintService().print(" localhost:" + printer.getLocalPort() + " ",
                    "%PDF-1.4 état".getBytes(StandardCharsets.UTF_8));
            assertEquals("%PDF-1.4 état", received.get(5, TimeUnit.SECONDS));
        }
    }

    /**
     * A port that is not one is refused before any connection.
     */
    @Test
    void anImpossiblePortIsRefused() {
        assertThrows(IOException.class, () -> ReportPrintService.port("abc"));
        assertThrows(IOException.class, () -> ReportPrintService.port("0"));
        assertThrows(IOException.class, () -> ReportPrintService.port("70000"));
        assertThrows(IOException.class, () -> new ReportPrintService().print("localhost:x", new byte[0]));
    }

    /**
     * A printer named without a port is reached on the raw port, and one
     * that does not answer fails the printing.
     */
    @Test
    void aSilentPrinterFailsThePrinting() {
        ReportPrintService service = new ReportPrintService();
        service.timeoutMillis = 500;
        assertEquals(9100, ReportPrintService.RAW_PORT);
        assertThrows(IOException.class, () -> service.print("printer.invalid", new byte[]{1}));
    }
}
