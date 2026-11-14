package com.intermarche.edge.service.fiscal;

import com.intermarche.edge.domain.fiscal.FiscalArchive;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sale.VatBreakdown;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.service.NodeEchelonService;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Produces the ARTICLE 88 FISCAL ARCHIVES of the point of sale
 * ({@code BO-05-01-01}): {@code TaxAuditExtracts_XXXXX_yyyymmdd.zip} and
 * {@code TaxAuditReports_XXXXX_yyyymmdd.zip}, from the tickets, returns,
 * invoices, sessions, movements and fiscal documents the node consolidated.
 *
 * <p>THE EXTRACTS are the data, line by line — every ticket with its signature
 * and the signature it chains to, every line, every settlement, every VAT
 * bucket, every return, invoice, session, drawer movement and fiscal document —
 * in semicolon CSV files described by a {@code LISEZMOI.txt}, so a controller
 * reads them without the software. THE REPORTS are what a controller looks at
 * first: the revenue day by day and register by register, the VAT by rate, the
 * settlements by tender, the sessions, and a synthesis that states the totals
 * of the period and the breaks found in the signature chain of each register.
 *
 * <p>Both carry a manifest of the SHA-256 of their files, and the archive itself
 * is kept with its own fingerprint ({@link FiscalArchive}), at the disposal of
 * the management controller on the back-office screen. Named {@code XXXXX} the
 * point of sale's number and {@code yyyymmdd} the last day covered.
 *
 * <p>A general service: the screen produces a period on demand, and the fiscal
 * scheduler produces the month just ended by itself when
 * {@code fiscal.archive-auto} is on — read at every tick, never cached.
 */
@ApplicationScoped
public class FiscalArchiveService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FiscalArchiveService.class);

    /** The day of the file names. */
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;

    /** Who produces the automatic archives. */
    static final String NODE = "Nœud magasin";

    /** The point of sale's number. */
    @Inject
    NodeEchelonService nodeEchelonService;

    /** The automatic production, as administered. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Produces the two archives of a period and keeps them.
     *
     * @param from the first day
     * @param to the last day, inclusive
     * @param generatedBy who asks, or the node itself
     * @param automatic whether the node produces them by itself
     * @return the two archives, extracts first
     */
    @Transactional
    public List<FiscalArchive> generate(LocalDate from, LocalDate to, String generatedBy, boolean automatic) {
        LOGGER.info("Entering method generate with from: " + from + ", to: " + to);
        LocalDateTime now = LocalDateTime.now();
        String pdv = pdvNumber();
        Period period = read(from, to);
        List<FiscalArchive> archives = new ArrayList<>();
        archives.add(keep(FiscalArchive.Kind.TAX_AUDIT_EXTRACTS, pdv, from, to,
                FiscalFiles.zip(extracts(period), now), generatedBy, automatic, now));
        archives.add(keep(FiscalArchive.Kind.TAX_AUDIT_REPORTS, pdv, from, to,
                FiscalFiles.zip(reports(period, from, to), now), generatedBy, automatic, now));
        LOGGER.infof("Archives fiscales du %s au %s produites (%s)", from, to, generatedBy);
        LOGGER.info("Exiting method generate");
        return archives;
    }

    /**
     * Keeps a file the point of sale produced, under its name and fingerprint.
     *
     * @param kind what the file is
     * @param pdv the point of sale's number
     * @param from the first day covered
     * @param to the last day covered
     * @param content the file
     * @param generatedBy who produced it
     * @param automatic whether the node produced it by itself
     * @param now the instant of the production
     * @return the kept file
     */
    @Transactional
    public FiscalArchive keep(FiscalArchive.Kind kind, String pdv, LocalDate from, LocalDate to, byte[] content,
            String generatedBy, boolean automatic, LocalDateTime now) {
        FiscalArchive archive = new FiscalArchive();
        archive.kind = kind;
        archive.pdvNumber = pdv;
        archive.periodFrom = from;
        archive.periodTo = to;
        archive.fileName = fileName(kind, pdv, to);
        archive.content = content;
        archive.sha256 = FiscalFiles.sha256(content);
        archive.sizeBytes = content.length;
        archive.generatedAt = now;
        archive.generatedBy = generatedBy == null || generatedBy.isBlank() ? NODE : FiscalFiles.clip(generatedBy, 60);
        archive.automatic = automatic;
        archive.persist();
        return archive;
    }

    /**
     * The name of a file: its prefix, the point of sale, the last day covered.
     *
     * @param kind what the file is
     * @param pdv the point of sale's number
     * @param to the last day covered
     * @return the file name
     */
    public static String fileName(FiscalArchive.Kind kind, String pdv, LocalDate to) {
        String extension = kind == FiscalArchive.Kind.SAFT_MONTHLY ? ".xml" : ".zip";
        return kind.getPrefix() + "_" + pdv + "_" + to.format(COMPACT) + extension;
    }

    /**
     * The point of sale's number, as the file names state it.
     *
     * @return the number, {@code 00000} when the node knows none
     */
    public String pdvNumber() {
        String pdv = nodeEchelonService.currentPdv();
        return pdv == null || pdv.isBlank() ? "00000" : pdv.trim();
    }

    /**
     * Produces the archives of the month just ended, once, when the back office
     * asks for it — the fiscal scheduler's tick ({@code BO-05-01-01}).
     *
     * @param today the day of the tick
     * @return true when the archives were produced by this tick
     */
    @Transactional
    @ActivateRequestContext
    public boolean autoArchive(LocalDate today) {
        LOGGER.info("Entering method autoArchive");
        if (!posSettingsService.fiscalArchiveAuto()) {
            LOGGER.info("Exiting method autoArchive: disabled");
            return false;
        }
        LocalDate from = today.minusMonths(1).withDayOfMonth(1);
        LocalDate to = today.withDayOfMonth(1).minusDays(1);
        if (!FiscalArchive.forPeriod(FiscalArchive.Kind.TAX_AUDIT_EXTRACTS, from, to).isEmpty()) {
            LOGGER.info("Exiting method autoArchive: already archived");
            return false;
        }
        generate(from, to, NODE, true);
        LOGGER.info("Exiting method autoArchive: archived");
        return true;
    }

    /** What a period holds, read once for both archives. */
    static final class Period {

        /** The tickets created in the period. */
        final List<Ticket> tickets;

        /** The returns of the period. */
        final List<Refund> refunds;

        /** The invoices of the period. */
        final List<Invoice> invoices;

        /** The sessions closed in the period. */
        final List<CashSession> sessions;

        /** The drawer movements of the period. */
        final List<CashMovement> movements;

        /** The fiscal documents of the period. */
        final List<FiscalDocument> documents;

        /**
         * Builds a period.
         *
         * @param tickets the tickets
         * @param refunds the returns
         * @param invoices the invoices
         * @param sessions the sessions
         * @param movements the movements
         * @param documents the fiscal documents
         */
        Period(List<Ticket> tickets, List<Refund> refunds, List<Invoice> invoices, List<CashSession> sessions,
                List<CashMovement> movements, List<FiscalDocument> documents) {
            this.tickets = tickets;
            this.refunds = refunds;
            this.invoices = invoices;
            this.sessions = sessions;
            this.movements = movements;
            this.documents = documents;
        }
    }

    /**
     * Reads what a period holds.
     *
     * @param from the first day
     * @param to the last day, inclusive
     * @return the period
     */
    Period read(LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        return new Period(
                Ticket.<Ticket>list("creationDate >= ?1 and creationDate < ?2 order by terminalId, creationDate",
                        start, end),
                Refund.<Refund>list("creationDate >= ?1 and creationDate < ?2 order by creationDate", start, end),
                Invoice.<Invoice>list("issueDate >= ?1 and issueDate < ?2 order by issueDate", start, end),
                CashSession.<CashSession>list("closingDate >= ?1 and closingDate < ?2 order by terminalId, closingDate",
                        start, end),
                CashMovement.<CashMovement>list("movementDate >= ?1 and movementDate < ?2 order by movementDate",
                        start, end),
                FiscalDocument.issuedBetween(start, end));
    }

    /**
     * Writes the files of the extracts archive.
     *
     * @param period what the period holds
     * @return the file names and their text
     */
    Map<String, String> extracts(Period period) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("LISEZMOI.txt", README);
        StringBuilder tickets = new StringBuilder(FiscalFiles.row("NUMERO", "CAISSE", "SESSION", "DATE_CREATION",
                "DATE_CLOTURE", "STATUT", "CAISSIER", "TOTAL_HT", "TOTAL_TVA", "TOTAL_TTC", "NB_ARTICLES",
                "SIGNATURE", "SIGNATURE_PRECEDENTE"));
        StringBuilder lines = new StringBuilder(FiscalFiles.row("TICKET", "LIGNE", "UID", "EAN", "LIBELLE",
                "QUANTITE", "PRIX_UNITAIRE", "TOTAL_TTC", "TAUX_TVA", "ANNULEE"));
        StringBuilder payments = new StringBuilder(FiscalFiles.row("TICKET", "MOYEN", "MONTANT"));
        StringBuilder vat = new StringBuilder(FiscalFiles.row("TICKET", "TAUX", "BASE_HT", "TVA", "TOTAL_TTC"));
        for (Ticket ticket : period.tickets) {
            tickets.append(FiscalFiles.row(ticket.ticketNumber, ticket.terminalId,
                    ticket.session == null ? null : ticket.session.sessionNumber, ticket.creationDate,
                    ticket.closingDate, ticket.status, ticket.cashier == null ? null : ticket.cashier.loginName,
                    FiscalFiles.money(ticket.totalExcludingTax), FiscalFiles.money(ticket.totalVat),
                    FiscalFiles.money(ticket.totalIncludingTax), ticket.itemCount, ticket.signature,
                    ticket.previousSignature));
            for (TicketLine line : ticket.lines) {
                lines.append(FiscalFiles.row(ticket.ticketNumber, line.lineNumber, line.lineUid, line.ean,
                        line.productLabel, line.quantity, FiscalFiles.money(line.unitPrice),
                        FiscalFiles.money(line.totalPrice), line.vatRate, line.cancelled ? "O" : "N"));
            }
            for (TicketPayment payment : ticket.payments) {
                payments.append(FiscalFiles.row(ticket.ticketNumber, payment.getMethodKey(),
                        FiscalFiles.money(payment.amount)));
            }
            for (VatBreakdown.Bucket bucket : breakdown(ticket).getBuckets()) {
                vat.append(FiscalFiles.row(ticket.ticketNumber, bucket.rate, FiscalFiles.money(bucket.totalExcludingTax),
                        FiscalFiles.money(bucket.vatAmount), FiscalFiles.money(bucket.totalIncludingTax)));
            }
        }
        files.put("tickets.csv", tickets.toString());
        files.put("lignes.csv", lines.toString());
        files.put("reglements.csv", payments.toString());
        files.put("tva.csv", vat.toString());
        StringBuilder refunds = new StringBuilder(FiscalFiles.row("NUMERO", "TICKET_ORIGINE", "CAISSE", "DATE",
                "MODE", "TOTAL_HT", "TOTAL_TVA", "TOTAL_TTC", "MOTIF"));
        for (Refund refund : period.refunds) {
            refunds.append(FiscalFiles.row(refund.refundNumber, refund.originalTicketId, refund.terminalId,
                    refund.creationDate, refund.refundMethod, FiscalFiles.money(refund.totalExcludingTax),
                    FiscalFiles.money(refund.totalVat), FiscalFiles.money(refund.totalAmount), refund.returnReason));
        }
        files.put("retours.csv", refunds.toString());
        StringBuilder invoices = new StringBuilder(FiscalFiles.row("NUMERO", "TYPE", "CAISSE", "DATE", "TICKET",
                "CLIENT", "NUMERO_CLIENT", "SIRET", "TVA_CLIENT", "TOTAL_HT", "TOTAL_TVA", "TOTAL_TTC"));
        for (Invoice invoice : period.invoices) {
            invoices.append(FiscalFiles.row(invoice.documentNumber, invoice.documentType, invoice.terminalId,
                    invoice.issueDate, invoice.ticketNumber, invoice.customerName, invoice.customerAccountNumber,
                    invoice.customerSiret, invoice.customerVatNumber, FiscalFiles.money(invoice.totalExcludingTax),
                    FiscalFiles.money(invoice.totalVat), FiscalFiles.money(invoice.totalIncludingTax)));
        }
        files.put("factures.csv", invoices.toString());
        files.put("sessions.csv", sessions(period.sessions));
        StringBuilder movements = new StringBuilder(FiscalFiles.row("UID", "CAISSE", "SESSION", "TYPE", "MOYEN",
                "VERS", "MONTANT", "MOTIF", "DATE", "VALIDE_PAR"));
        for (CashMovement movement : period.movements) {
            movements.append(FiscalFiles.row(movement.movementUid, movement.terminalId,
                    movement.session == null ? null : movement.session.sessionNumber, movement.type,
                    movement.paymentMethod, movement.transferTo, FiscalFiles.money(movement.amount), movement.reason,
                    movement.movementDate, movement.endorsedBy));
        }
        files.put("mouvements.csv", movements.toString());
        StringBuilder documents = new StringBuilder(FiscalFiles.row("NUMERO", "TYPE", "SERIE", "RANG", "ATCUD",
                "CAISSE", "DATE", "NIF", "TOTAL_TTC", "TVA", "SIGNATURE", "SIGNATURE_PRECEDENTE", "ANNULE"));
        for (FiscalDocument document : period.documents) {
            documents.append(FiscalFiles.row(document.number, document.documentType, document.series, document.rank,
                    document.atcud, document.terminalId, document.issuedAt, document.customerTaxId,
                    FiscalFiles.money(document.grossTotal), FiscalFiles.money(document.taxTotal), document.hash,
                    document.previousHash, document.cancelled ? "O" : "N"));
        }
        files.put("documents-fiscaux.csv", documents.toString());
        return files;
    }

    /**
     * Writes the sessions of a period, their counts and variances.
     *
     * @param sessions the sessions
     * @return the CSV text
     */
    private String sessions(List<CashSession> sessions) {
        StringBuilder text = new StringBuilder(FiscalFiles.row("NUMERO", "CAISSE", "OUVERTURE", "CLOTURE", "STATUT",
                "FOND", "THEORIQUE", "COMPTE", "ECART", "PRELEVE"));
        for (CashSession session : sessions) {
            text.append(FiscalFiles.row(session.sessionNumber, session.terminalId, session.openingDate,
                    session.closingDate, session.status, FiscalFiles.money(session.openingFloat),
                    FiscalFiles.money(session.theoreticalAmount), FiscalFiles.money(session.countedAmount),
                    FiscalFiles.money(session.variance), FiscalFiles.money(session.withdrawnAmount)));
        }
        return text.toString();
    }

    /**
     * Writes the files of the reports archive.
     *
     * @param period what the period holds
     * @param from the first day
     * @param to the last day
     * @return the file names and their text
     */
    Map<String, String> reports(Period period, LocalDate from, LocalDate to) {
        Map<String, BigDecimal[]> daily = new TreeMap<>();
        Map<String, BigDecimal[]> byRate = new TreeMap<>();
        Map<String, BigDecimal[]> byTender = new TreeMap<>();
        Map<String, String> lastSignature = new LinkedHashMap<>();
        Map<String, Integer> breaks = new TreeMap<>();
        BigDecimal[] total = new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        int closed = 0;
        for (Ticket ticket : period.tickets) {
            chain(ticket, lastSignature, breaks);
            if (ticket.status != Ticket.TicketStatus.CLOSED) {
                continue;
            }
            closed++;
            String day = dayOf(ticket.closingDate == null ? ticket.creationDate : ticket.closingDate);
            add(daily, day + ";" + ticket.terminalId, BigDecimal.ONE, ticket.totalExcludingTax, ticket.totalVat,
                    ticket.totalIncludingTax);
            add(total, BigDecimal.ONE, ticket.totalExcludingTax, ticket.totalVat, ticket.totalIncludingTax);
            for (VatBreakdown.Bucket bucket : breakdown(ticket).getBuckets()) {
                add(byRate, day + ";" + bucket.rate, BigDecimal.ONE, bucket.totalExcludingTax, bucket.vatAmount,
                        bucket.totalIncludingTax);
            }
            for (TicketPayment payment : ticket.payments) {
                add(byTender, day + ";" + payment.getMethodKey(), BigDecimal.ONE, payment.amount, null, null);
            }
        }
        BigDecimal refunded = BigDecimal.ZERO;
        for (Refund refund : period.refunds) {
            String day = dayOf(refund.creationDate);
            BigDecimal amount = refund.totalAmount == null ? BigDecimal.ZERO : refund.totalAmount.abs();
            add(daily, day + ";" + refund.terminalId, BigDecimal.ZERO, null, null, null);
            daily.get(day + ";" + refund.terminalId)[4] = daily.get(day + ";" + refund.terminalId)[4].add(amount);
            refunded = refunded.add(amount);
        }
        Map<String, String> files = new LinkedHashMap<>();
        StringBuilder revenue = new StringBuilder(FiscalFiles.row("JOUR", "CAISSE", "NB_TICKETS", "TOTAL_HT",
                "TOTAL_TVA", "TOTAL_TTC", "RETOURS_TTC", "NET_TTC"));
        for (Map.Entry<String, BigDecimal[]> entry : daily.entrySet()) {
            BigDecimal[] sums = entry.getValue();
            revenue.append(entry.getKey()).append(';').append(sums[0].toPlainString()).append(';').append(FiscalFiles.money(sums[1]))
                    .append(';').append(FiscalFiles.money(sums[2])).append(';').append(FiscalFiles.money(sums[3]))
                    .append(';').append(FiscalFiles.money(sums[4])).append(';')
                    .append(FiscalFiles.money(sums[3].subtract(sums[4]))).append('\n');
        }
        files.put("ca-journalier.csv", revenue.toString());
        files.put("tva-par-taux.csv", table("JOUR;TAUX;NB_TICKETS;BASE_HT;TVA;TOTAL_TTC", byRate, 4));
        files.put("reglements-par-moyen.csv", table("JOUR;MOYEN;NB_REGLEMENTS;MONTANT", byTender, 2));
        files.put("sessions.csv", sessions(period.sessions));
        StringBuilder synthesis = new StringBuilder();
        synthesis.append("Point de vente : ").append(pdvNumber()).append('\n')
                .append("Période : du ").append(from).append(" au ").append(to).append('\n')
                .append("Tickets enregistrés : ").append(period.tickets.size()).append('\n')
                .append("Tickets clos : ").append(closed).append('\n')
                .append("Total HT : ").append(FiscalFiles.money(total[1])).append('\n')
                .append("Total TVA : ").append(FiscalFiles.money(total[2])).append('\n')
                .append("Total TTC : ").append(FiscalFiles.money(total[3])).append('\n')
                .append("Retours TTC : ").append(FiscalFiles.money(refunded)).append('\n')
                .append("Net TTC : ").append(FiscalFiles.money(total[3].subtract(refunded))).append('\n')
                .append("Factures : ").append(period.invoices.size()).append('\n')
                .append("Documents fiscaux : ").append(period.documents.size()).append('\n')
                .append("Sessions closes : ").append(period.sessions.size()).append('\n');
        if (breaks.isEmpty()) {
            synthesis.append("Chaînage des signatures : intègre\n");
        }
        for (Map.Entry<String, Integer> entry : breaks.entrySet()) {
            synthesis.append("Chaînage des signatures, caisse ").append(entry.getKey()).append(" : ")
                    .append(entry.getValue()).append(" rupture(s)\n");
        }
        files.put("synthese.txt", synthesis.toString());
        return files;
    }

    /**
     * Follows the signature chain of a register: a signed ticket whose
     * previous signature is not the last one seen at its register is a break.
     *
     * @param ticket the ticket, in its register's order
     * @param lastSignature the last signature seen per register
     * @param breaks the breaks counted per register
     */
    static void chain(Ticket ticket, Map<String, String> lastSignature, Map<String, Integer> breaks) {
        if (ticket.signature == null || ticket.signature.isBlank()) {
            return;
        }
        String terminal = String.valueOf(ticket.terminalId);
        String previous = lastSignature.get(terminal);
        if (previous != null && !previous.equals(ticket.previousSignature)) {
            breaks.merge(terminal, 1, Integer::sum);
        }
        lastSignature.put(terminal, ticket.signature);
    }

    /**
     * Writes an aggregated table.
     *
     * @param header the header, semicolon separated
     * @param rows the sums by key, the key carrying the first columns
     * @param columns how many sums each row states, the count first
     * @return the CSV text
     */
    private static String table(String header, Map<String, BigDecimal[]> rows, int columns) {
        StringBuilder text = new StringBuilder(header).append('\n');
        for (Map.Entry<String, BigDecimal[]> entry : rows.entrySet()) {
            text.append(entry.getKey()).append(';').append(entry.getValue()[0].toPlainString());
            for (int index = 1; index < columns; index++) {
                text.append(';').append(FiscalFiles.money(entry.getValue()[index]));
            }
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * Adds to the sums of a key: the count, then the three amounts.
     *
     * @param sums the sums by key
     * @param key the key
     * @param count the count to add
     * @param first the first amount, null for zero
     * @param second the second amount, null for zero
     * @param third the third amount, null for zero
     */
    private static void add(Map<String, BigDecimal[]> sums, String key, BigDecimal count, BigDecimal first,
            BigDecimal second, BigDecimal third) {
        BigDecimal[] row = sums.computeIfAbsent(key, k -> new BigDecimal[] {
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        add(row, count, first, second, third);
    }

    /**
     * Adds a count and three amounts to a row of sums.
     *
     * @param row the sums
     * @param count the count to add
     * @param first the first amount, null for zero
     * @param second the second amount, null for zero
     * @param third the third amount, null for zero
     */
    private static void add(BigDecimal[] row, BigDecimal count, BigDecimal first, BigDecimal second,
            BigDecimal third) {
        row[0] = row[0].add(count);
        row[1] = row[1].add(first == null ? BigDecimal.ZERO : first);
        row[2] = row[2].add(second == null ? BigDecimal.ZERO : second);
        row[3] = row[3].add(third == null ? BigDecimal.ZERO : third);
    }

    /**
     * The VAT buckets of a ticket, its cancelled lines left out.
     *
     * @param ticket the ticket
     * @return the breakdown
     */
    static VatBreakdown breakdown(Ticket ticket) {
        VatBreakdown breakdown = new VatBreakdown();
        for (TicketLine line : ticket.lines) {
            if (!line.cancelled && line.totalPrice != null) {
                breakdown.add(line.vatRate == null ? BigDecimal.ZERO : line.vatRate, line.vatNumber,
                        line.totalPrice);
            }
        }
        return breakdown;
    }

    /**
     * The ISO day of an instant.
     *
     * @param instant the instant, possibly null
     * @return the day, empty when unknown
     */
    static String dayOf(LocalDateTime instant) {
        return instant == null ? "" : instant.toLocalDate().toString();
    }

    /** The description of the extracts, for a controller reading them without the software. */
    static final String README = """
            Archive fiscale TaxAuditExtracts (article 88 / article L47 A du LPF)
            Fichiers CSV séparés par des points-virgules, encodés en UTF-8, montants en euros avec un point décimal.
            tickets.csv : un ticket par ligne, avec sa signature et la signature du ticket précédent de sa caisse.
            lignes.csv : les lignes de chaque ticket (annulées comprises, marquées O).
            reglements.csv : les règlements de chaque ticket, par moyen de paiement.
            tva.csv : la ventilation de TVA de chaque ticket, par taux.
            retours.csv : les retours, avec leur ticket d'origine.
            factures.csv : les factures et bons de livraison, avec leur client.
            sessions.csv : les sessions de caisse, comptages et écarts.
            mouvements.csv : les mouvements de caisse (apports, prélèvements, dépenses, transferts, déclarations).
            documents-fiscaux.csv : les documents fiscaux numérotés et signés (FS, FT, NC, RC).
            empreintes.txt : l'empreinte SHA-256 de chaque fichier.
            """;
}
