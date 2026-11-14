package com.intermarche.edge.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * PRINTS a document on a network printer (BO-09-01-10): the raw port of the
 * printer — 9100 by default, the port every office printer opens for a raw
 * job — receives the document's bytes as they are, the PDF an edition
 * produced included, which the printers of the back offices render
 * themselves.
 *
 * <p>The store node has no printer of its own: the printer is one of the
 * shop's network, named by the back office as {@code host:port}.
 */
@ApplicationScoped
public class ReportPrintService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReportPrintService.class);

    /** The raw printing port of a printer named without one. */
    static final int RAW_PORT = 9100;

    /** How long the printer may take to answer, in milliseconds. */
    int timeoutMillis = 5000;

    /**
     * Sends a document to a printer.
     *
     * @param printer the printer, {@code host} or {@code host:port}
     * @param content the document
     * @throws IOException when the printer cannot be reached or refuses the job
     */
    public void print(String printer, byte[] content) throws IOException {
        LOGGER.info("Entering method print with printer: " + printer);
        String target = printer.trim();
        int colon = target.lastIndexOf(':');
        String host = colon < 0 ? target : target.substring(0, colon);
        int port = colon < 0 ? RAW_PORT : port(target.substring(colon + 1));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            OutputStream out = socket.getOutputStream();
            out.write(content);
            out.flush();
        }
        LOGGER.info("Exiting method print");
    }

    /**
     * Reads a port, refusing what is not one.
     *
     * @param text the port as written
     * @return the port
     * @throws IOException when the text is not a port
     */
    static int port(String text) throws IOException {
        try {
            int port = Integer.parseInt(text.trim());
            if (port < 1 || port > 65535) {
                throw new IOException("Port d'imprimante invalide : " + text);
            }
            return port;
        } catch (NumberFormatException notANumber) {
            throw new IOException("Port d'imprimante invalide : " + text, notANumber);
        }
    }
}
