package com.intermarche.edge.service.sync;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The bodies the AT-THE-FLOW stream posts to third parties (BO-11-02-02/03/04).
 *
 * <p>A SEPARATE vocabulary from {@link SyncPayloads}, deliberately. That one is
 * the register-to-node contract between two halves of this suite, which may be
 * changed on both sides at once; this one is published to systems nobody here
 * owns — video surveillance, single queue, the monetics collector — and a field
 * renamed to tidy the internal payload would silently break them. Two contracts,
 * two files.
 *
 * <p>Flat and small on purpose: a stream consumer reads events at the rate the
 * shop makes them, and the ticket IMAGE is the only large thing in them. It
 * travels as the register printed it, plain or compressed according to the
 * administered choice the requirement asks for, with the encoding named in the
 * body so the reader never has to guess.
 */
public final class StreamPayloads {

    /**
     * Not instantiated: a bag of payload shapes.
     */
    private StreamPayloads() {
    }

    /** The encoding of a ticket image travelling as it was printed. */
    public static final String IMAGE_PLAIN = "plain";

    /** The encoding of a ticket image gzipped then Base64-encoded. */
    public static final String IMAGE_GZIP_BASE64 = "gzip+base64";

    /**
     * A ticket as a third party receives it: its technical identity, its totals,
     * its lines, its settlements and the printed image.
     */
    public static class TicketEvent {

        /** The event kind, so one endpoint can serve several. */
        public String kind = "TICKET";

        /** The ticket number. */
        public String ticketNumber;

        /** The register that rang it. */
        public String terminalId;

        /** The point of vente, when the node knows its own. */
        public String pdvNumber;

        /** The lifecycle status: OPEN, PARKED, CLOSED or CANCELLED. */
        public String status;

        /** When the cart was opened, ISO-8601. */
        public String creationDate;

        /** When it was closed, ISO-8601, null for a cart still open or parked. */
        public String closingDate;

        /** The cashier's login. */
        public String cashierLogin;

        /** The cash session number, null when the ticket carries none. */
        public String sessionNumber;

        /** The number of items. */
        public int itemCount;

        /** The tax-excluded total. */
        public BigDecimal totalExcludingTax;

        /** The tax-included total. */
        public BigDecimal totalIncludingTax;

        /** The tax. */
        public BigDecimal totalVat;

        /** The fidelity card presented, null when none was. */
        public String fidelityCard;

        /** The fiscal signature of the ticket. */
        public String signature;

        /** The printed image, as the customer read it, or null when none travelled. */
        public String image;

        /** How {@link #image} is encoded: plain, or gzip then Base64. */
        public String imageEncoding;

        /** The lines, in ring order, cancelled ones marked. */
        public List<LineEvent> lines = new ArrayList<>();

        /** The settlements, in registration order. */
        public List<PaymentEvent> payments = new ArrayList<>();
    }

    /** One line of a streamed ticket. */
    public static class LineEvent {

        /** The 1-based rank of the line on the ticket. */
        public int lineNumber;

        /** The EAN, null when the article was rung by PLU. */
        public String ean;

        /** The internal PLU, null when the article was rung by EAN. */
        public String plu;

        /** The article label as the register printed it. */
        public String label;

        /** The nomenclature family code, as snapshot on the sale. */
        public String familyCode;

        /** The quantity sold. */
        public BigDecimal quantity;

        /** The unit price charged. */
        public BigDecimal unitPrice;

        /** The VAT rate, as a fraction. */
        public BigDecimal vatRate;

        /** The tax-included line total. */
        public BigDecimal totalPrice;

        /** Whether the line was cancelled on the ticket. */
        public boolean cancelled;
    }

    /** One settlement of a streamed ticket. */
    public static class PaymentEvent {

        /** The 1-based registration order. */
        public int paymentIndex;

        /** The settlement method key: CASH, CARD, CHEQUE, FIDELITY… */
        public String method;

        /** The amount settled. */
        public BigDecimal amount;
    }

    /** A refund as a third party receives it. */
    public static class RefundEvent {

        /** The event kind. */
        public String kind = "REFUND";

        /** The refund number. */
        public String refundNumber;

        /** The register that issued it. */
        public String terminalId;

        /** The point of vente, when the node knows its own. */
        public String pdvNumber;

        /** Where the refund stands. */
        public String status;

        /** How it was paid back. */
        public String method;

        /** When it was created, ISO-8601. */
        public String creationDate;

        /** The refunded amount. */
        public BigDecimal totalAmount;

        /** The tax of the refunded amount. */
        public BigDecimal totalVat;

        /** The cash session number, null when the refund carries none. */
        public String sessionNumber;
    }

    /**
     * The MONETICS slip of a ticket (BO-11-02-03): what the card terminal
     * returned, and nothing of the basket.
     *
     * <p>The basket is deliberately absent. A monetics collector reconciles
     * authorisations, and sending it the articles would be handing a third party
     * customer purchase detail it never asked for.
     */
    public static class MoneticsEvent {

        /** The event kind. */
        public String kind = "MONETICS";

        /** The ticket the settlements belong to. */
        public String ticketNumber;

        /** The register that rang it. */
        public String terminalId;

        /** The point of vente, when the node knows its own. */
        public String pdvNumber;

        /** When the ticket was closed, ISO-8601. */
        public String closingDate;

        /** The card settlements of the ticket. */
        public List<CardEvent> cards = new ArrayList<>();
    }

    /** One card settlement of a monetics slip. */
    public static class CardEvent {

        /** The 1-based registration order on the ticket. */
        public int paymentIndex;

        /** The amount authorised. */
        public BigDecimal amount;

        /** The authorisation number returned by the terminal, null when none was. */
        public String authorizationNumber;

        /** Whether the terminal answered in degraded mode. */
        public boolean degradedMode;
    }
}
