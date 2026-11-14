package com.intermarche.edge.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.sale.CustomerOrder;
import com.intermarche.edge.domain.sale.EcommerceOrder;
import com.intermarche.edge.domain.sale.OrderKind;
import com.intermarche.edge.domain.sale.OrderStatus;
import com.intermarche.edge.domain.sale.SalesChannel;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The UNIFIED READ MODEL of the orders the e-commerce platform deals with
 * (DG-03-02-01 to -04): an order integrated as a closed ticket, an order paid
 * short and left payable at the till, a basket or a mobility transaction
 * deposited for the till — each read as one {@link View}: its status, what is
 * still due, the ticket that settled it and every payment, the platform's and
 * the ones taken at the till (NEMO).
 *
 * <p>Built from what the node already holds: the integration row
 * ({@link EcommerceOrder}) and its report, the recallable document
 * ({@link CustomerOrder}) and the settling {@link Ticket}'s payments. Never
 * filtered by "recallable": a paid, collected or cancelled order stays
 * readable by the platform.
 */
@ApplicationScoped
public class EcommerceOrderViewService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EcommerceOrderViewService.class);

    /** The kinds of documents the platform follows. */
    static final List<OrderKind> PLATFORM_KINDS = List.of(OrderKind.ECOMMERCE, OrderKind.MOBILITY,
            OrderKind.CLICK_COLLECT);

    /** The statuses of a closed basket (DG-03-02-01). */
    static final List<String> CLOSED_STATUSES = List.of(View.INTEGRATED, View.PAID, View.COLLECTED);

    /** Reads the reports kept beside the orders. */
    ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * The view of one order, by the platform's reference or the document's code.
     *
     * @param reference the reference or code
     * @return the view, or null when the node holds no such order
     */
    public View view(String reference) {
        LOGGER.info("Entering method view with reference: " + reference);
        EcommerceOrder integration = integration(reference);
        CustomerOrder document = document(reference);
        if (document != null && !PLATFORM_KINDS.contains(document.kind)) {
            document = null;
        }
        View view = integration == null && document == null ? null : build(integration, document);
        LOGGER.info("Exiting method view");
        return view;
    }

    /**
     * The view of a document whose status just changed (DG-03-02-04).
     *
     * @param document the document
     * @return its view
     */
    public View view(CustomerOrder document) {
        return build(integration(document.code), document);
    }

    /**
     * The closed baskets (DG-03-02-01): the orders integrated as tickets and
     * the documents settled at the till, with their ticket, filtered; an
     * order paid short appears through its document.
     *
     * @param status the status asked for, or null for every closed status
     * @param from the first day, or null
     * @param to the last day, or null
     * @param channel ECOMMERCE or MOBILITY, or null for both
     * @return the views, most recent first
     */
    public List<View> closed(String status, LocalDate from, LocalDate to, String channel) {
        LOGGER.info("Entering method closed");
        List<View> views = new ArrayList<>();
        List<String> wanted = status == null ? CLOSED_STATUSES : List.of(status.trim().toUpperCase(Locale.ROOT));
        for (EcommerceOrder integration : integrations()) {
            if (!integration.isPendingPayment() && !integration.isSettled()) {
                keep(views, build(integration, null), wanted, from, to, channel);
            }
        }
        for (CustomerOrder document : documents()) {
            keep(views, build(integration(document.code), document), wanted, from, to, channel);
        }
        LOGGER.info("Exiting method closed");
        return views;
    }

    /**
     * The baskets waiting for their payment (DG-03-02-02): the e-commerce,
     * mobility and click-and-collect documents open or partially paid.
     *
     * @return the views, most recent first
     */
    public List<View> pending() {
        LOGGER.info("Entering method pending");
        List<View> views = new ArrayList<>();
        for (CustomerOrder document : documents()) {
            if (document.isRecallable()) {
                views.add(build(integration(document.code), document));
            }
        }
        LOGGER.info("Exiting method pending");
        return views;
    }

    /**
     * Keeps a view that passes the filters.
     *
     * @param views the kept views, completed in place
     * @param view the candidate
     * @param wanted the statuses asked for
     * @param from the first day, or null
     * @param to the last day, or null
     * @param channel the kind asked for, or null
     */
    private static void keep(List<View> views, View view, List<String> wanted, LocalDate from, LocalDate to,
                             String channel) {
        LocalDate day = view.updatedAt == null ? null : view.updatedAt.toLocalDate();
        boolean inPeriod = (from == null || day != null && !day.isBefore(from))
                && (to == null || day != null && !day.isAfter(to));
        if (wanted.contains(view.status) && inPeriod && (channel == null || channel.equalsIgnoreCase(view.kind))) {
            views.add(view);
        }
    }

    /**
     * Builds the view of an order from its integration row and its document.
     *
     * @param integration the integration row, or null
     * @param document the recallable document, or null
     * @return the view
     */
    View build(EcommerceOrder integration, CustomerOrder document) {
        View view = new View();
        EcommerceIntegrationService.Report report = integration == null ? null : report(integration);
        view.report = report;
        view.reference = integration != null ? integration.reference : document.code;
        view.code = document == null ? null : document.code;
        boolean mobility = document != null ? document.kind == OrderKind.MOBILITY
                : integration.channel == SalesChannel.MOBILITY;
        view.kind = mobility ? View.MOBILITY : View.ECOMMERCE;
        if (document == null) {
            fromIntegration(view, integration, report);
            return view;
        }
        view.status = status(document.status);
        view.total = document.totalAmount == null ? BigDecimal.ZERO : document.totalAmount;
        view.updatedAt = document.updatedAt;
        for (CustomerOrder.OrderLine line : document.lines) {
            view.lines.add(new ViewLine(line.ean, line.label, line.quantity, line.unitPrice, line.totalPrice));
        }
        List<ViewPayment> platform = new ArrayList<>();
        if (report != null && !report.payments.isEmpty()) {
            // The platform's own means, as its integration report kept them.
            for (EcommerceIntegrationService.ReportPayment payment : report.payments) {
                platform.add(new ViewPayment(payment.method, payment.integratedAs, payment.amount, View.PLATFORM));
            }
        } else {
            for (CustomerOrder.OrderPayment payment : document.payments) {
                platform.add(new ViewPayment(payment.tender, payment.tender, payment.amount, View.PLATFORM));
            }
        }
        view.payments.addAll(platform);
        Ticket settling = settlingTicket(document);
        view.ticketNumber = settling != null ? settling.ticketNumber : document.settledTicketNumber;
        if (settling != null) {
            for (TicketPayment payment : settling.payments) {
                if (!consume(platform, payment)) {
                    view.payments.add(new ViewPayment(payment.tenderCode == null ? payment.getMethodKey()
                            : payment.tenderCode, payment.getMethodKey(), payment.amount, View.NEMO));
                }
            }
        }
        view.remaining = document.remaining();
        return view;
    }

    /**
     * Fills the view of an order integrated (or refused) without a document.
     *
     * @param view the view, completed in place
     * @param integration the integration row
     * @param report its report, or null when unreadable
     */
    private void fromIntegration(View view, EcommerceOrder integration, EcommerceIntegrationService.Report report) {
        view.status = integration.isIntegrated() ? View.INTEGRATED
                : integration.isPendingPayment() ? View.PENDING_PAYMENT
                : integration.isSettled() ? View.PAID : View.REJECTED;
        view.total = integration.totalAmount;
        view.updatedAt = integration.receivedAt;
        view.ticketNumber = integration.ticketNumber;
        view.remaining = BigDecimal.ZERO;
        if (report != null) {
            for (EcommerceIntegrationService.ReportPayment payment : report.payments) {
                view.payments.add(new ViewPayment(payment.method, payment.integratedAs, payment.amount, View.PLATFORM));
            }
            view.remaining = report.remaining == null ? BigDecimal.ZERO : report.remaining;
        }
        Ticket ticket = ticket(integration.ticketNumber);
        if (ticket != null) {
            for (TicketLine line : ticket.lines) {
                view.lines.add(new ViewLine(line.ean, line.productLabel, line.quantity, line.unitPrice, line.totalPrice));
            }
        }
    }

    /**
     * Removes from the platform's payments the one a till payment restates,
     * by tender and amount, at most once.
     *
     * @param platform the platform's payments not yet matched, changed in place
     * @param payment the till payment
     * @return true when the till payment restates a platform payment
     */
    private static boolean consume(List<ViewPayment> platform, TicketPayment payment) {
        for (ViewPayment candidate : platform) {
            if (payment.getMethodKey().equalsIgnoreCase(String.valueOf(candidate.integratedAs))
                    && payment.amount != null && candidate.amount != null
                    && payment.amount.compareTo(candidate.amount) == 0) {
                platform.remove(candidate);
                return true;
            }
        }
        return false;
    }

    /**
     * The status of a document, as the platform reads it.
     *
     * @param status the document's status
     * @return the view status
     */
    static String status(OrderStatus status) {
        return status == OrderStatus.OPEN ? View.PENDING_PAYMENT : status.name();
    }

    /**
     * The ticket that settled a document: the one the till named, else the
     * sale whose recalled orders name it.
     *
     * @param document the document
     * @return the ticket, or null when none is known
     */
    Ticket settlingTicket(CustomerOrder document) {
        Ticket named = ticket(document.settledTicketNumber);
        if (named != null) {
            return named;
        }
        return Ticket.<Ticket>find("recalledOrderCode = ?1 or recalledOrderCode like ?2 or recalledOrderCode like ?3",
                document.code, document.code + ";%", "%;" + document.code).firstResult();
    }

    /**
     * Reads the report kept beside an integration row.
     *
     * @param integration the row
     * @return the report, or null when it cannot be read
     */
    EcommerceIntegrationService.Report report(EcommerceOrder integration) {
        try {
            return objectMapper.readValue(integration.report, EcommerceIntegrationService.Report.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The ticket a number names.
     *
     * @param number the number, possibly null
     * @return the ticket, or null
     */
    Ticket ticket(String number) {
        return number == null ? null : Ticket.<Ticket>find("ticketNumber", number).firstResult();
    }

    /**
     * The integration row of a reference.
     *
     * @param reference the reference
     * @return the row, or null
     */
    EcommerceOrder integration(String reference) {
        return EcommerceOrder.findByReference(reference);
    }

    /**
     * The document of a code.
     *
     * @param code the code
     * @return the document, or null
     */
    CustomerOrder document(String code) {
        return CustomerOrder.findByCode(code);
    }

    /**
     * Every integration row, most recent first.
     *
     * @return the rows
     */
    List<EcommerceOrder> integrations() {
        return EcommerceOrder.listAllOrdered();
    }

    /**
     * Every document of the kinds the platform follows, most recent first.
     *
     * @return the documents
     */
    List<CustomerOrder> documents() {
        return CustomerOrder.listOfKinds(PLATFORM_KINDS);
    }

    /** One order as the e-commerce platform reads it (DG-03-02-01 to -04). */
    public static class View {

        /** Waiting for its payment at the till. */
        public static final String PENDING_PAYMENT = "PENDING_PAYMENT";

        /** Booked as a closed ticket of the virtual register. */
        public static final String INTEGRATED = "INTEGRATED";

        /** Refused at integration. */
        public static final String REJECTED = "REJECTED";

        /** Paid in full. */
        public static final String PAID = "PAID";

        /** Paid and handed over. */
        public static final String COLLECTED = "COLLECTED";

        /** An e-commerce order. */
        public static final String ECOMMERCE = "ECOMMERCE";

        /** A mobility transaction. */
        public static final String MOBILITY = "MOBILITY";

        /** A payment the platform or the device collected. */
        public static final String PLATFORM = "PLATFORM";

        /** A payment taken at the till. */
        public static final String NEMO = "NEMO";

        /** The platform's reference, or the document's code. */
        public String reference;

        /** The code the till recalls it by, or null when it was never payable at the till. */
        public String code;

        /** ECOMMERCE or MOBILITY. */
        public String kind;

        /** PENDING_PAYMENT, PARTIALLY_PAID, PAID, COLLECTED, CANCELLED, INTEGRATED or REJECTED. */
        public String status;

        /** The total, TTC. */
        public BigDecimal total;

        /** What is still due, TTC. */
        public BigDecimal remaining;

        /** The ticket the order was booked as or settled with, or null. */
        public String ticketNumber;

        /** Every payment, the platform's and the till's. */
        public List<ViewPayment> payments = new ArrayList<>();

        /** The article lines. */
        public List<ViewLine> lines = new ArrayList<>();

        /** When the order last changed. */
        public LocalDateTime updatedAt;

        /** The integration report, or null for a document deposited for the till. */
        public EcommerceIntegrationService.Report report;
    }

    /**
     * One payment of an order.
     *
     * @param method the means as the payer named it
     * @param integratedAs the tender it was booked under
     * @param amount the amount, TTC
     * @param source PLATFORM or NEMO
     */
    public record ViewPayment(String method, String integratedAs, BigDecimal amount, String source) {

        /**
         * Builds a payment, its amount stated to the cent.
         *
         * @param method the means as the payer named it
         * @param integratedAs the tender it was booked under
         * @param amount the amount, TTC, or null
         * @param source PLATFORM or NEMO
         */
        public ViewPayment {
            amount = amount == null ? null : amount.setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }

    /**
     * One article line of an order.
     *
     * @param ean the EAN
     * @param label the label
     * @param quantity the quantity
     * @param unitPrice the unit price, TTC
     * @param totalPrice the line total, TTC
     */
    public record ViewLine(String ean, String label, BigDecimal quantity, BigDecimal unitPrice,
                           BigDecimal totalPrice) {
    }
}
