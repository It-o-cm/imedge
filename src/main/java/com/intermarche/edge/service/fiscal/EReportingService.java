package com.intermarche.edge.service.fiscal;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.edge.domain.fiscal.EReport;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sale.VatBreakdown;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.service.ExternalRelayService;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * E-REPORTING ({@code BO-05-01-03}): the transaction data that no electronic
 * invoice carries — the sales to consumers (B2C) and the sales to operators
 * established abroad (B2B international) — extracted period by period from what
 * the node consolidated, put in the transmission format and transmitted through
 * the dematerialisation platform, with the follow-up of its acknowledgement.
 *
 * <p>THE PERIOD is the decade (the 1st to the 10th, the 11th to the 20th, the
 * 21st to the end of the month) or the month, as administered in
 * {@code fiscal.ereporting-period}. A period is extracted once it is over, and
 * the scheduler transmits it by itself when {@code fiscal.ereporting-enabled} is
 * on; a failed transmission is tried again, a rejected one is extracted again
 * from the screen.
 *
 * <p>THE DATA: the consumer sales are AGGREGATED by day and by VAT rate — the
 * taxable amount, the VAT and the number of transactions, returns deducted —
 * which is what the reform asks of a till; the international sales are stated
 * invoice by invoice, with the buyer's VAT number and country. The sales a
 * French professional was e-invoiced for are left out, they are the
 * e-invoicing's ({@link EInvoicingService}). Money products (gift cards) are not
 * taxable operations and are left out too. Each summary states its category:
 * the goods ({@value #GOODS}) and the services ({@value #SERVICES}), the nature
 * the back office administers on the article and the register snapshots on the
 * line.
 *
 * <p>THE PAYMENT DATA the reform asks for concerns the services only: the
 * amounts actually collected for the service lines of the same consumer and
 * international sales, by day and by VAT rate — tax included, VAT and taxable
 * amount —, the returns of a service deducted on the day of the return. A till
 * collects at the sale, so the day of collection is the day the ticket closed.
 * No service sold in the period: the section is transmitted empty.
 */
@ApplicationScoped
public class EReportingService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EReportingService.class);

    /** The category of the sales of goods. */
    static final String GOODS = "TLB1";

    /** The category of the services (prestations de services). */
    static final String SERVICES = "TPS1";

    /** The scope of the consumer sales. */
    static final String B2C = "B2C";

    /** The scope of the sales to operators established abroad. */
    static final String INTERNATIONAL = "B2B_INTERNATIONAL";

    /** How many past periods the scheduler extracts at most. */
    static final int LOOKBACK = 3;

    /** The read timeout of a transmission, in milliseconds. */
    static final long TIMEOUT_MILLIS = 5000L;

    /** The activation, the period, the platform and the delay. */
    @Inject
    PosSettingsService posSettingsService;

    /** The platform, shared with the e-invoicing. */
    @Inject
    EInvoicingService eInvoicingService;

    /** The credentialed HTTP call to the platform. */
    @Inject
    ExternalRelayService externalRelayService;

    /**
     * The period a day belongs to.
     *
     * @param day the day
     * @param periodicity {@code DECADE} or {@code MONTH}
     * @return the first and the last day of its period
     */
    public static LocalDate[] periodOf(LocalDate day, String periodicity) {
        if ("MONTH".equals(periodicity)) {
            return new LocalDate[] {day.withDayOfMonth(1), day.withDayOfMonth(day.lengthOfMonth())};
        }
        if (day.getDayOfMonth() <= 10) {
            return new LocalDate[] {day.withDayOfMonth(1), day.withDayOfMonth(10)};
        }
        if (day.getDayOfMonth() <= 20) {
            return new LocalDate[] {day.withDayOfMonth(11), day.withDayOfMonth(20)};
        }
        return new LocalDate[] {day.withDayOfMonth(21), day.withDayOfMonth(day.lengthOfMonth())};
    }

    /**
     * The periods over before a day, the latest first.
     *
     * @param today the day
     * @param periodicity {@code DECADE} or {@code MONTH}
     * @param count how many
     * @return the periods, each its first and last day
     */
    public static List<LocalDate[]> pastPeriods(LocalDate today, String periodicity, int count) {
        List<LocalDate[]> periods = new ArrayList<>();
        LocalDate cursor = periodOf(today, periodicity)[0].minusDays(1);
        for (int index = 0; index < count; index++) {
            LocalDate[] period = periodOf(cursor, periodicity);
            periods.add(period);
            cursor = period[0].minusDays(1);
        }
        return periods;
    }

    /**
     * One tick of the scheduler: the periods over extracted, the transmissions
     * due made — when e-reporting is on.
     *
     * @param now the instant of the tick
     * @return how many transmissions moved
     */
    @Transactional
    @ActivateRequestContext
    public int tick(LocalDateTime now) {
        LOGGER.info("Entering method tick");
        if (!posSettingsService.ereportingEnabled()) {
            LOGGER.info("Exiting method tick: disabled");
            return 0;
        }
        int moved = 0;
        for (LocalDate[] period : pastPeriods(now.toLocalDate(), posSettingsService.ereportingPeriod(), LOOKBACK)) {
            if (EReport.forPeriod(period[0], period[1]) == null) {
                extract(period[0], period[1], now);
                moved++;
            }
        }
        for (EReport report : EReport.due(now)) {
            moved += transmit(report, now) ? 1 : 0;
        }
        LOGGER.info("Exiting method tick with " + moved);
        return moved;
    }

    /**
     * Extracts a period, or extracts it again while it is not accepted.
     *
     * @param start the first day
     * @param end the last day, inclusive
     * @param now the instant of the extraction
     * @return the transmission, or null when the period is already accepted
     */
    @Transactional
    public EReport extract(LocalDate start, LocalDate end, LocalDateTime now) {
        LOGGER.info("Entering method extract with start: " + start + ", end: " + end);
        EReport report = EReport.forPeriod(start, end);
        if (report != null && report.status.isLast()) {
            LOGGER.info("Exiting method extract: already accepted");
            return null;
        }
        if (report == null) {
            report = new EReport();
            report.periodStart = start;
            report.periodEnd = end;
        }
        Data data = collect(start, end);
        report.transactionCount = data.count;
        report.totalExcludingTax = data.net;
        report.totalVat = data.vat;
        report.totalIncludingTax = data.net.add(data.vat);
        report.payload = payload(data, start, end, now);
        report.generatedAt = now;
        report.status = EReport.Status.PENDING;
        report.attempts = 0;
        report.nextAttemptAt = null;
        report.message = null;
        report.persist();
        LOGGER.info("Exiting method extract with " + data.count + " transaction(s)");
        return report;
    }

    /**
     * Transmits an extracted period to the platform.
     *
     * @param report the transmission
     * @param now the instant of the transmission
     * @return true when the platform took it
     */
    @Transactional
    public boolean transmit(EReport report, LocalDateTime now) {
        LOGGER.info("Entering method transmit with " + report.periodStart);
        ServiceProvider platform = eInvoicingService.platform();
        if (platform == null) {
            fail(report, EInvoicingService.NO_PLATFORM, now);
            LOGGER.info("Exiting method transmit: no platform");
            return false;
        }
        String body = FiscalFiles.object().put("siren", sellerSiren()).put("periodStart", report.periodStart.toString())
                .put("periodEnd", report.periodEnd.toString()).put("document", report.payload).toString();
        ExternalRelayService.RelayResult result = externalRelayService.relay(Optional.of(platform.endpoint),
                "POST", "/e-reporting", body, TIMEOUT_MILLIS, platform.authKey);
        if (!result.reached() || result.status() < 200 || result.status() >= 300) {
            fail(report, "Transmission refusée ou impossible (statut " + result.status() + ").", now);
            LOGGER.info("Exiting method transmit: refused");
            return false;
        }
        JsonNode answer = eInvoicingService.read(result.body());
        report.ackId = EInvoicingService.text(answer, "id", report.periodStart + "/" + report.periodEnd);
        report.sentAt = now;
        report.status = EReport.Status.SENT;
        report.message = null;
        acknowledge(report, EInvoicingService.text(answer, "status", null),
                EInvoicingService.text(answer, "message", null), now);
        report.persist();
        LOGGER.info("Exiting method transmit: sent");
        return true;
    }

    /**
     * Applies an acknowledgement the platform pushed
     * ({@code POST /api/fiscal/ereporting/ack}).
     *
     * @param ackId the transmission's acknowledgement identifier
     * @param status {@code ACCEPTED} or {@code REJECTED}
     * @param message what the administration says, or null
     * @return the refusal, or null when the acknowledgement was applied
     */
    @Transactional
    public String receive(String ackId, String status, String message) {
        LOGGER.info("Entering method receive with ackId: " + ackId + ", status: " + status);
        EReport report = ackId == null ? null : EReport.findByAck(ackId);
        if (report == null) {
            LOGGER.info("Exiting method receive: unknown transmission");
            return "Transmission d'e-reporting inconnue.";
        }
        if (!acknowledge(report, status, message, LocalDateTime.now())) {
            LOGGER.info("Exiting method receive: unknown status");
            return "Statut inconnu.";
        }
        report.persist();
        LOGGER.info("Exiting method receive");
        return null;
    }

    /**
     * Applies an acknowledgement's status to a transmission.
     *
     * @param report the transmission
     * @param status {@code ACCEPTED} or {@code REJECTED}, any other value ignored
     * @param message what the administration says, or null
     * @param now the instant of the acknowledgement
     * @return true when the status was an acknowledgement
     */
    static boolean acknowledge(EReport report, String status, String message, LocalDateTime now) {
        String upper = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!"ACCEPTED".equals(upper) && !"REJECTED".equals(upper)) {
            return false;
        }
        report.status = EReport.Status.valueOf(upper);
        report.ackAt = now;
        report.message = FiscalFiles.clip(message, 255);
        return true;
    }

    /**
     * Records a failed transmission, tried again after the administered delay.
     *
     * @param report the transmission
     * @param error what failed
     * @param now the instant of the failure
     */
    private void fail(EReport report, String error, LocalDateTime now) {
        report.attempts++;
        report.message = error;
        report.status = EReport.Status.FAILED;
        report.nextAttemptAt = now.plusSeconds(posSettingsService.fiscalRetrySeconds());
        report.persist();
    }

    /** What a period holds for the e-reporting. */
    static final class Data {

        /** The consumer sales, by day, by category then by rate: count, net, VAT. */
        final Map<LocalDate, Map<String, Map<BigDecimal, BigDecimal[]>>> daily = new TreeMap<>();

        /** The international invoices: the invoice, its category and its VAT breakdown. */
        final List<Object[]> international = new ArrayList<>();

        /** The services collected, by day, by scope then by rate: net, VAT (BO-05-01-03). */
        final Map<LocalDate, Map<String, Map<BigDecimal, BigDecimal[]>>> payments = new TreeMap<>();

        /** How many transactions the period counts. */
        int count;

        /** The total net. */
        BigDecimal net = BigDecimal.ZERO;

        /** The total VAT. */
        BigDecimal vat = BigDecimal.ZERO;
    }

    /**
     * Collects what a period holds for the e-reporting.
     *
     * @param start the first day
     * @param end the last day, inclusive
     * @return the data
     */
    Data collect(LocalDate start, LocalDate end) {
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.plusDays(1).atStartOfDay();
        Data data = new Data();
        Set<Long> invoiced = new HashSet<>();
        for (Invoice invoice : Invoice.<Invoice>list("issueDate >= ?1 and issueDate < ?2 order by issueDate", from, to)) {
            if (EInvoicingService.eligible(invoice)) {
                ticketOf(invoice, invoiced);
            } else if (international(invoice.customerVatNumber, invoice.customerTaxId)) {
                ticketOf(invoice, invoiced);
                international(data, invoice);
            }
        }
        for (Ticket ticket : Ticket.<Ticket>list("status = ?1 and closingDate >= ?2 and closingDate < ?3 "
                + "order by closingDate", Ticket.TicketStatus.CLOSED, from, to)) {
            if (invoiced.contains(ticket.id)) {
                continue;
            }
            LocalDate closed = ticket.closingDate.toLocalDate();
            VatBreakdown services = lines(ticket.lines, true);
            day(data, closed, GOODS, lines(ticket.lines, false), BigDecimal.ONE, BigDecimal.ONE);
            day(data, closed, SERVICES, services, BigDecimal.ONE, BigDecimal.ONE);
            paid(data, closed, B2C, services, BigDecimal.ONE);
            count(data, lines(ticket.lines, null), 1);
        }
        for (Refund refund : Refund.<Refund>list("creationDate >= ?1 and creationDate < ?2 order by creationDate",
                from, to)) {
            VatBreakdown all = new VatBreakdown();
            VatBreakdown goods = new VatBreakdown();
            VatBreakdown services = new VatBreakdown();
            for (RefundLine line : refund.lines) {
                if (line.price != null && line.quantity != null) {
                    BigDecimal rate = line.vatRate == null ? BigDecimal.ZERO : line.vatRate;
                    BigDecimal amount = line.price.multiply(line.quantity).abs();
                    all.add(rate, amount);
                    (service(line) ? services : goods).add(rate, amount);
                }
            }
            LocalDate returned = refund.creationDate.toLocalDate();
            day(data, returned, GOODS, goods, BigDecimal.ZERO, BigDecimal.ONE.negate());
            day(data, returned, SERVICES, services, BigDecimal.ZERO, BigDecimal.ONE.negate());
            paid(data, returned, B2C, services, BigDecimal.ONE.negate());
            count(data, all, -1);
        }
        return data;
    }

    /**
     * States an international invoice: its goods and its services each under
     * their category, the services collected on the day its sale closed
     * (BO-05-01-03); an invoice whose sale the node does not hold is stated by
     * its printed totals, as goods.
     *
     * @param data the data
     * @param invoice the invoice to an operator established abroad
     */
    private static void international(Data data, Invoice invoice) {
        if (invoice.ticket == null) {
            VatBreakdown breakdown = totals(invoice);
            data.international.add(new Object[] {invoice, GOODS, breakdown});
            count(data, breakdown, 1);
            return;
        }
        List<TicketLine> sold = invoice.ticket.lines;
        VatBreakdown goods = lines(sold, false);
        VatBreakdown services = lines(sold, true);
        if (!goods.getBuckets().isEmpty() || services.getBuckets().isEmpty()) {
            data.international.add(new Object[] {invoice, GOODS, goods});
        }
        if (!services.getBuckets().isEmpty()) {
            data.international.add(new Object[] {invoice, SERVICES, services});
        }
        LocalDateTime collected = invoice.ticket.closingDate == null ? invoice.issueDate : invoice.ticket.closingDate;
        paid(data, collected.toLocalDate(), INTERNATIONAL, services, BigDecimal.ONE);
        count(data, lines(sold, null), 1);
    }

    /**
     * Whether a returned line returns a service: the nature of the sold line
     * it returns (BO-05-01-03).
     *
     * @param line the returned line
     * @return true when the original line is known and is a service
     */
    private static boolean service(RefundLine line) {
        TicketLine original = line.originalLineId == null ? null : TicketLine.findById(line.originalLineId);
        return original != null && original.service;
    }

    /**
     * Notes the ticket an invoice states, so the sale is not reported twice.
     *
     * @param invoice the invoice
     * @param invoiced the ids of the tickets already stated by an invoice
     */
    private static void ticketOf(Invoice invoice, Set<Long> invoiced) {
        if (invoice.ticket != null) {
            invoiced.add(invoice.ticket.id);
        }
    }

    /**
     * Whether a customer is established abroad: a VAT number or a tax number
     * prefixed by another country than France.
     *
     * @param vatNumber the intra-community VAT number, possibly null
     * @param taxId the tax number, possibly null
     * @return true for a foreign operator
     */
    static boolean international(String vatNumber, String taxId) {
        String vat = vatNumber == null ? "" : vatNumber.trim().toUpperCase(Locale.ROOT);
        if (vat.length() > 2 && Character.isLetter(vat.charAt(0)) && Character.isLetter(vat.charAt(1))) {
            return !vat.startsWith("FR");
        }
        return taxId != null && !taxId.isBlank() && !FiscalDocument.isFinalConsumer(taxId)
                && !"FR".equals(FiscalDocument.countryOf(taxId)) && !"PT".equals(FiscalDocument.countryOf(taxId));
    }

    /**
     * The VAT buckets of the sold lines of one nature, cancelled lines and
     * money products left out (BO-05-01-03).
     *
     * @param lines the lines
     * @param service true for the services, false for the goods, null for both
     * @return the breakdown
     */
    static VatBreakdown lines(List<TicketLine> lines, Boolean service) {
        VatBreakdown breakdown = new VatBreakdown();
        for (TicketLine line : lines) {
            if (!line.cancelled && !line.moneyProduct && line.totalPrice != null
                    && (service == null || line.service == service)) {
                breakdown.add(line.vatRate == null ? BigDecimal.ZERO : line.vatRate, line.vatNumber, line.totalPrice);
            }
        }
        return breakdown;
    }

    /**
     * The VAT bucket of an invoice whose sale the node does not hold: its
     * printed totals, at the rate they imply.
     *
     * @param invoice the invoice
     * @return the breakdown
     */
    static VatBreakdown totals(Invoice invoice) {
        BigDecimal net = invoice.totalExcludingTax == null ? BigDecimal.ZERO : invoice.totalExcludingTax;
        BigDecimal vat = invoice.totalVat == null ? BigDecimal.ZERO : invoice.totalVat;
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(net.signum() == 0 ? BigDecimal.ZERO : vat.divide(net, 3, RoundingMode.HALF_UP), net.add(vat));
        return breakdown;
    }

    /**
     * Adds a breakdown to the consumer sales of a day, under its category; an
     * empty breakdown adds nothing.
     *
     * @param data the data
     * @param day the day
     * @param category {@value #GOODS} or {@value #SERVICES}
     * @param breakdown the buckets
     * @param transactions the transactions counted per bucket
     * @param sign one for a sale, minus one for a return
     */
    private static void day(Data data, LocalDate day, String category, VatBreakdown breakdown,
            BigDecimal transactions, BigDecimal sign) {
        if (breakdown.getBuckets().isEmpty()) {
            return;
        }
        Map<BigDecimal, BigDecimal[]> rates = data.daily.computeIfAbsent(day, d -> new TreeMap<>())
                .computeIfAbsent(category, c -> new TreeMap<>());
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            BigDecimal[] sums = rates.computeIfAbsent(bucket.rate.stripTrailingZeros(),
                    r -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(transactions);
            sums[1] = sums[1].add(bucket.totalExcludingTax.multiply(sign));
            sums[2] = sums[2].add(bucket.vatAmount.multiply(sign));
        }
    }

    /**
     * Adds the services collected on a day to the payment data
     * (BO-05-01-03); no service, nothing added.
     *
     * @param data the data
     * @param day the day of collection
     * @param scope {@value #B2C} or {@value #INTERNATIONAL}
     * @param services the buckets of the service lines
     * @param sign one for a sale, minus one for a return
     */
    private static void paid(Data data, LocalDate day, String scope, VatBreakdown services, BigDecimal sign) {
        if (services.getBuckets().isEmpty()) {
            return;
        }
        Map<BigDecimal, BigDecimal[]> rates = data.payments.computeIfAbsent(day, d -> new TreeMap<>())
                .computeIfAbsent(scope, c -> new TreeMap<>());
        for (VatBreakdown.Bucket bucket : services.getBuckets()) {
            BigDecimal[] sums = rates.computeIfAbsent(bucket.rate.stripTrailingZeros(),
                    r -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(bucket.totalExcludingTax.multiply(sign));
            sums[1] = sums[1].add(bucket.vatAmount.multiply(sign));
        }
    }

    /**
     * Counts a transaction in the totals of the period.
     *
     * @param data the data
     * @param breakdown its buckets
     * @param sign one for a sale, minus one for a return
     */
    private static void count(Data data, VatBreakdown breakdown, int sign) {
        data.count++;
        BigDecimal factor = BigDecimal.valueOf(sign);
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            data.net = data.net.add(bucket.totalExcludingTax.multiply(factor));
            data.vat = data.vat.add(bucket.vatAmount.multiply(factor));
        }
    }

    /**
     * Writes the transmitted document.
     *
     * @param data the data of the period
     * @param start the first day
     * @param end the last day
     * @param now the instant of the extraction
     * @return the XML document
     */
    String payload(Data data, LocalDate start, LocalDate end, LocalDateTime now) {
        List<Store> stores = Store.listAll();
        Store store = stores.isEmpty() ? null : stores.get(0);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<EReporting xmlns=\"urn:fr:dgfip:ereporting:1.0\" version=\"1.0\">\n  <Header>\n");
        tag(xml, 4, "Siren", sellerSiren());
        tag(xml, 4, "VatNumber", store == null ? "" : store.vatNumber);
        tag(xml, 4, "Name", store == null ? "" : EInvoicingService.firstFilled(store.legalName, store.name));
        tag(xml, 4, "PeriodStart", start.toString());
        tag(xml, 4, "PeriodEnd", end.toString());
        tag(xml, 4, "Periodicity", posSettingsService.ereportingPeriod());
        tag(xml, 4, "Created", now.withNano(0).toString());
        xml.append("  </Header>\n  <TransactionsReport>\n");
        for (Map.Entry<LocalDate, Map<String, Map<BigDecimal, BigDecimal[]>>> day : data.daily.entrySet()) {
            for (Map.Entry<String, Map<BigDecimal, BigDecimal[]>> category : day.getValue().entrySet()) {
                xml.append("    <DailySummary date=\"").append(day.getKey()).append("\" category=\"")
                        .append(category.getKey()).append("\" scope=\"").append(B2C).append("\" currency=\"EUR\">\n");
                for (Map.Entry<BigDecimal, BigDecimal[]> rate : category.getValue().entrySet()) {
                    tax(xml, 6, rate.getKey(), rate.getValue()[1], rate.getValue()[2], rate.getValue()[0]);
                }
                xml.append("    </DailySummary>\n");
            }
        }
        for (Object[] entry : data.international) {
            Invoice invoice = (Invoice) entry[0];
            xml.append("    <InternationalInvoice number=\"").append(EInvoicingService.escape(invoice.documentNumber))
                    .append("\" date=\"").append(invoice.issueDate == null ? "" : invoice.issueDate.toLocalDate())
                    .append("\" buyerVat=\"").append(EInvoicingService.escape(invoice.customerVatNumber))
                    .append("\" buyerTaxId=\"").append(EInvoicingService.escape(invoice.customerTaxId))
                    .append("\" category=\"").append(entry[1]).append("\" scope=\"").append(INTERNATIONAL)
                    .append("\">\n");
            for (VatBreakdown.Bucket bucket : ((VatBreakdown) entry[2]).getBuckets()) {
                tax(xml, 6, bucket.rate, bucket.totalExcludingTax, bucket.vatAmount, BigDecimal.ONE);
            }
            xml.append("    </InternationalInvoice>\n");
        }
        xml.append("  </TransactionsReport>\n");
        payments(xml, data);
        xml.append("  <Totals>\n");
        tag(xml, 4, "TransactionCount", String.valueOf(data.count));
        tag(xml, 4, "TaxableAmount", FiscalFiles.money(data.net));
        tag(xml, 4, "TaxAmount", FiscalFiles.money(data.vat));
        tag(xml, 4, "TotalAmount", FiscalFiles.money(data.net.add(data.vat)));
        xml.append("  </Totals>\n</EReporting>\n");
        return xml.toString();
    }

    /**
     * Writes the payment data (BO-05-01-03): the services collected, by day,
     * scope and rate — tax included, VAT and taxable amount —; an empty
     * element when no service was sold in the period.
     *
     * @param xml the buffer
     * @param data the data of the period
     */
    private static void payments(StringBuilder xml, Data data) {
        if (data.payments.isEmpty()) {
            xml.append("  <PaymentsReport/>\n");
            return;
        }
        xml.append("  <PaymentsReport>\n");
        for (Map.Entry<LocalDate, Map<String, Map<BigDecimal, BigDecimal[]>>> day : data.payments.entrySet()) {
            for (Map.Entry<String, Map<BigDecimal, BigDecimal[]>> scope : day.getValue().entrySet()) {
                xml.append("    <DailyPayments date=\"").append(day.getKey()).append("\" category=\"").append(SERVICES)
                        .append("\" scope=\"").append(scope.getKey()).append("\" currency=\"EUR\">\n");
                for (Map.Entry<BigDecimal, BigDecimal[]> rate : scope.getValue().entrySet()) {
                    BigDecimal net = rate.getValue()[0];
                    BigDecimal vat = rate.getValue()[1];
                    xml.append("      <Payment rate=\"").append(EInvoicingService.percent(rate.getKey())).append("\">\n");
                    tag(xml, 8, "AmountIncludingTax", FiscalFiles.money(net.add(vat)));
                    tag(xml, 8, "TaxAmount", FiscalFiles.money(vat));
                    tag(xml, 8, "TaxableAmount", FiscalFiles.money(net));
                    xml.append("      </Payment>\n");
                }
                xml.append("    </DailyPayments>\n");
            }
        }
        xml.append("  </PaymentsReport>\n");
    }

    /**
     * Writes one rate of a summary.
     *
     * @param xml the buffer
     * @param indent the indentation
     * @param rate the rate, as a fraction
     * @param net the taxable amount
     * @param vat the VAT
     * @param transactions the transactions counted
     */
    private static void tax(StringBuilder xml, int indent, BigDecimal rate, BigDecimal net, BigDecimal vat,
            BigDecimal transactions) {
        String pad = " ".repeat(indent);
        xml.append(pad).append("<Tax rate=\"").append(EInvoicingService.percent(rate)).append("\">\n");
        tag(xml, indent + 2, "TaxableAmount", FiscalFiles.money(net));
        tag(xml, indent + 2, "TaxAmount", FiscalFiles.money(vat));
        tag(xml, indent + 2, "TransactionCount", transactions.toPlainString());
        xml.append(pad).append("</Tax>\n");
    }

    /**
     * Writes one element, its value escaped.
     *
     * @param xml the buffer
     * @param indent the indentation
     * @param name the element
     * @param value the value
     */
    private static void tag(StringBuilder xml, int indent, String name, String value) {
        xml.append(" ".repeat(indent)).append('<').append(name).append('>').append(EInvoicingService.escape(value))
                .append("</").append(name).append(">\n");
    }

    /**
     * The SIREN of the point of sale's company.
     *
     * @return the SIREN, empty when no SIRET is administered
     */
    String sellerSiren() {
        List<Store> stores = Store.listAll();
        return stores.isEmpty() ? "" : EInvoicingService.siren(stores.get(0).siret);
    }
}
