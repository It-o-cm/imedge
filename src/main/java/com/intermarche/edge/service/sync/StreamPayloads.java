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

        /**
         * The language the ticket was printed in — the customer's, chosen or
         * retrieved from the loyalty base (LC-13-06-14) — or null when it ran in
         * the register's language.
         */
        public String language;

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

    /**
     * A closed sale as the Catalina servers receive it (BO-11-01-07): the store,
     * the lane, the transaction, the loyalty card, the items and the tenders.
     */
    public static class CatalinaTransaction {

        /** The store identifier: this node's point of sale number. */
        public String storeId;

        /** The lane: the register that rang the sale. */
        public String laneId;

        /** The transaction number: the ticket number. */
        public String transactionId;

        /** When the sale was closed, ISO-8601. */
        public String transactionDateTime;

        /** The cashier's login, or null. */
        public String cashierId;

        /** The loyalty card presented, or null. */
        public String loyaltyCardNumber;

        /** The tax-included total. */
        public BigDecimal totalAmount;

        /** The tax. */
        public BigDecimal taxAmount;

        /** The items sold, cancelled ones left out. */
        public List<CatalinaItem> items = new ArrayList<>();

        /** The tenders that settled the sale. */
        public List<CatalinaTender> tenders = new ArrayList<>();
    }

    /** One item of a Catalina transaction. */
    public static class CatalinaItem {

        /** The UPC/EAN of the item. */
        public String upc;

        /** The item description. */
        public String description;

        /** The department: the product family code. */
        public String departmentCode;

        /** The quantity sold. */
        public BigDecimal quantity;

        /** The unit price charged. */
        public BigDecimal unitPrice;

        /** The extended price: the line total. */
        public BigDecimal extendedPrice;

        /** The discount the line carries, or null. */
        public BigDecimal discountAmount;
    }

    /** One tender of a Catalina transaction. */
    public static class CatalinaTender {

        /** The tender type: the payment method key. */
        public String tenderType;

        /** The amount tendered. */
        public BigDecimal amount;

        /** The card authorisation, or null for a non-card tender. */
        public String authorizationCode;
    }

    /**
     * A cash event as the TFREMA stream carries it to the centralised gestion
     * commerciale (BO-11-02-01): the transaction and the articles it moved.
     */
    public static class TfremaEvent {

        /** The kind of cash event. */
        public String eventType;

        /** The point of sale number. */
        public String pdv;

        /** The register. */
        public String caisse;

        /** The ticket number. */
        public String transaction;

        /** When the sale was closed, ISO-8601. */
        public String dateHeure;

        /** The tax-included total. */
        public BigDecimal montantTtc;

        /** The articles sold, cancelled ones left out. */
        public List<TfremaLine> lignes = new ArrayList<>();
    }

    /** One article of a TFREMA cash event. */
    public static class TfremaLine {

        /** The article EAN. */
        public String article;

        /** The article label. */
        public String libelle;

        /** The family code. */
        public String famille;

        /** The quantity, negative for a returned article. */
        public BigDecimal quantite;

        /** The tax-included amount of the line. */
        public BigDecimal montantTtc;
    }

    /**
     * The integration status of one e-commerce order, returned to its platform
     * at the scale of the point of sale (BO-11-04-03).
     */
    public static class EcommerceStatus {

        /** The point of sale that integrated the order. */
        public String pdvNumber;

        /** The order reference. */
        public String reference;

        /** INTEGRATED, REJECTED or DISABLED. */
        public String status;

        /** The ticket the order was booked as, or null. */
        public String ticketNumber;

        /** The total booked, or null. */
        public BigDecimal total;

        /** The outcome in words. */
        public String message;

        /** How many lines carried an unknown article. */
        public int unknownLines;

        /** How many lines carried an article forbidden to sale. */
        public int forbiddenLines;
    }
}
