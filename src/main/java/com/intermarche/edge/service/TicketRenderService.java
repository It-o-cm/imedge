package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sale.TicketLineValuation;
import com.intermarche.edge.domain.sale.VatBreakdown;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.ui.hardware.ReceiptLogo;
import com.intermarche.edge.ui.hardware.TicketLineOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders a sale ticket as 42-column text, from the persisted entities alone.
 * <p>
 * This is the store node's own renderer. The node holds a copy of every sale
 * made in the shop, and a register that does not hold a sale asks the node for
 * its duplicata (LC-08-05-05); what comes back must read exactly like the paper
 * the customer got at the other till, so the rendering rules here are the
 * register's own, character for character.
 * <p>
 * It renders and nothing else: no printer, no counter, no journal entry. The
 * print counter and its journal entry belong to the register that made the
 * sale, not to the node that keeps a copy.
 * <p>
 * The loyalty section of the day is rendered here too, from the columns the
 * selling register froze on the row at closing and pushed with the sale
 * (BO-03-03-25). It used to be absent by construction — the projection lived in
 * the register's live state and was never persisted, so a node had nothing to
 * print. Frozen, it is part of the sale like its lines, and a duplicata issued
 * here is the same document as the original again.
 * <p>
 * Format contract: 42 columns (WIDTH), French decimal comma (DF), administered
 * layout first (BO-03-03) and the built-in rendering below when the shop
 * administers none.
 */
@ApplicationScoped
public class TicketRenderService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TicketRenderService.class);

    /** The back-office parameters (EAN on paper, LC-02-04-04). */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * The administered layouts (BO-03-03). Null when this renderer was built by
     * hand in a unit test, which simply means "no template, render it our way" —
     * the same answer the service itself gives for an unadministered document.
     */
    @Inject
    DocumentTemplateService documentTemplateService;

    /** French display format for amounts on the receipt. */
    private static final DecimalFormat DF = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRENCH));

    /** Standard 80mm receipt width, in characters. */
    private static final int WIDTH = 42;

    /**
     * Renders a document through its ADMINISTERED layout, when there is one
     * (BO-03-03).
     *
     * @param type the document being printed
     * @param data the already-formatted values the layout may read
     * @return the laid-out document, or null when this printer must render it
     */
    private String administeredLayout(
            com.intermarche.edge.domain.setting.DocumentTemplate.DocumentType type,
            java.util.Map<String, Object> data) {
        if (documentTemplateService == null) {
            return null;
        }
        return documentTemplateService.render(type, data);
    }

    /**
     * Renders a sale ticket as 42-column text, WITHOUT printing it and without
     * touching a single row.
     *
     * <p>This is what the node answers a register with (LC-08-05-05): a
     * duplicata of a sale made on ANOTHER register must look exactly like the
     * original, and the only way to guarantee that is to run the same rendering
     * rules over the very same entity rather than a second rendering of a
     * payload.
     *
     * @param ticket the ticket to render
     * @param duplicata whether the DUPLICATA banner is printed
     * @param duplicataNumber the duplicata's rank, meaningless when not a duplicata
     * @return the ticket as printable text
     */
    public String renderTicket(Ticket ticket, boolean duplicata, int duplicataNumber) {
        LOGGER.info("Entering method renderTicket with ticket: " + ticket + ", duplicata: " + duplicata + ", duplicataNumber: " + duplicataNumber);
        // BO-03-03: the store's own layout first. A shop that administers none —
        // or one whose layout does not hold together — gets the receipt below,
        // character for character as before.
        String administered = administeredLayout(
                com.intermarche.edge.domain.setting.DocumentTemplate.DocumentType.SALE_RECEIPT,
                saleDocumentData(ticket, duplicata, duplicataNumber));
        if (administered != null) {
            LOGGER.info("Exiting method renderTicket");
            return administered;
        }
        StringBuilder sb = new StringBuilder();
        // Header. The logo prints above the store name when this till has one; the
        // text line stays regardless, so a receipt is readable even on a printer that
        // ignores images or a deployment with no logo file.
        String logo = ReceiptLogo.directive();
        if (!logo.isEmpty()) {
            sb.append(logo).append("\n");
        }
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append(center(ticket.store.name, WIDTH)).append("\n");
        sb.append(center(ticket.store.address.city, WIDTH)).append("\n");
        // BO-03-08-03: an administered header message, printed under the store
        // address; absent or blank, nothing is printed.
        String headerMessage = posSettingsService.ticketHeaderMessage();
        if (headerMessage != null && !headerMessage.isBlank()) {
            sb.append(center(headerMessage, WIDTH)).append("\n");
        }
        if (duplicata) {
            sb.append("-".repeat(WIDTH)).append("\n");
            sb.append(center(String.format("*** DUPLICATA N°%d ***", duplicataNumber), WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Ticket info
        sb.append(String.format("Ticket: %s%n", ticket.ticketNumber));
        sb.append(String.format("Date   : %s%n", ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append(String.format("Vendeur: %s%n", ticket.cashier.getFullName()));
        sb.append("-".repeat(WIDTH)).append("\n");
        // Lines
        // Phase 7: per-line valuation traces (engine advantages), printed as
        // deltas under their article — loaded once, keyed by lineUid
        java.util.Map<String, TicketLineValuation> valuations = new java.util.HashMap<>();
        for (TicketLineValuation v : TicketLineValuation.<TicketLineValuation>list("ticket.id", ticket.id)) {
            valuations.put(v.lineUid, v);
        }
        // LC-08-01-07: the store administers the order the articles print in, and
        // whether they are grouped under a family heading. The stored lines keep
        // their own order — this is a rendering, not the record.
        String lineOrder = TicketLineOrder.normalize(posSettingsService.ticketLineOrder());
        boolean groupByFamily = TicketLineOrder.groupsByFamily(lineOrder);
        String printedFamily = null;
        for (TicketLine line : TicketLineOrder.apply(ticket.lines, lineOrder)) {
            // A cancelled article (lot C4, BO-04-01-16) is kept in the ticket
            // only as a journal witness; it never prints on the receipt.
            if (line.cancelled) continue;
            if (groupByFamily) {
                String family = TicketLineOrder.familyOf(line);
                if (!family.equals(printedFamily)) {
                    sb.append(center(family, WIDTH)).append("\n");
                    printedFamily = family;
                }
            }
            // Product label (possibly truncated)
            String label = line.productLabel.length() > 20 ? line.productLabel.substring(0, 20) : line.productLabel;
            // Quantity and unit price
            String qtyStr = DF.format(line.quantity);
            String unitPriceStr = DF.format(line.unitPrice);
            // LC-02-03-02 and LC-02-13-18: quantity, unit price and line total on the
            // receipt as on the two screens. An article sold by a unit of measure
            // states that unit — "2,360 m x 4,99" — because the figure alone does not
            // say what was bought.
            String unit = line.unitName == null || line.unitName.isBlank()
                    ? "" : line.unitName.trim();
            sb.append(unit.isEmpty()
                    ? String.format("%-20s %5s x %6s%n", label, qtyStr, unitPriceStr)
                    : String.format("%-20s %5s %s x %6s%n", label, qtyStr, unit, unitPriceStr));
            if (posSettingsService.showEan() && line.ean != null && !line.ean.isEmpty()) {
                // LC-02-04-04: the EAN printed under the label when configured.
                sb.append("  ").append(line.ean).append("\n");
            }
            // Line total (right-aligned)
            String lineTotal = DF.format(line.totalPrice);
            sb.append(String.format("%" + WIDTH + "s%n", lineTotal));
            // Engine advantage on this line: printed as a signed delta
            TicketLineValuation valuation = valuations.get(line.lineUid);
            if (valuation != null && valuation.valuedTotal.compareTo(valuation.localTotal) != 0) {
                java.math.BigDecimal delta = valuation.valuedTotal.subtract(valuation.localTotal);
                String advLabel = valuation.advantageLabel != null ? valuation.advantageLabel
                        : (valuation.offerLabel != null ? valuation.offerLabel : "AVANTAGE");
                if (advLabel.length() > 26) advLabel = advLabel.substring(0, 26);
                sb.append(formatLine("  " + advLabel, DF.format(delta) + " E"));
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Totals
        if (ticket.globalDiscountApplied != null) {
            sb.append(formatLine("REMISE TICKET",
                    "-" + DF.format(ticket.globalDiscountApplied) + " E"));
        }
        sb.append(formatLine("TOTAL TTC", DF.format(ticket.totalIncludingTax) + " E"));
        // Loyalty section, from what the register froze on the row at closing.
        // The node holds no live projection and never will; before the freeze it
        // simply could not restate this zone, so a duplicata issued here came
        // out shorter than the original. Same guard and same wording as the
        // register's own printer — the two renderers must agree character for
        // character, that is the whole point of rendering from the entity.
        if (posSettingsService.fidelityAdvantagesEnabled()
                && ticket.fidelityEarnTotal != null
                && ticket.fidelityEarnTotal.signum() > 0) {
            sb.append(formatLine("CAGNOTTE DU JOUR",
                    "+" + DF.format(ticket.fidelityEarnTotal) + " E"));
            appendFidelityAdvantages(sb, ticket);
            // imfid response B: the forecast balance after the sale, under the
            // administered label, marked PREVISIONNEL — nothing is credited
            // before the ingestion (BO-03-03-31). Absent when no card resolved.
            if (ticket.fidelityProjectedAfterEarn != null) {
                sb.append(formatLine(posSettingsService.fidelityProjectedLabel(),
                        DF.format(ticket.fidelityProjectedAfterEarn) + " E"));
                sb.append(center("(PREVISIONNEL)", WIDTH)).append("\n");
            }
        }
        sb.append(formatLine("Dont TVA", DF.format(ticket.totalVat) + " E"));
        // Per-rate VAT ventilation (same rule as the persisted totals)
        VatBreakdown breakdown = new VatBreakdown();
        for (TicketLine line : ticket.lines) {
            // Cancelled articles (lot C4) are outside the sale: excluded from
            // the per-rate VAT ventilation exactly as from the totals.
            if (line.cancelled) continue;
            breakdown.add(line.vatRate, line.totalPrice);
        }
        // BO-10-06-04: the per-rate VAT ventilation is printed only when the
        // back office asks for it; disabled, the ticket carries no breakdown.
        if (posSettingsService.vatBreakdownEnabled()) {
            for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
                sb.append(formatLine("  TVA " + bucket.getRateFormatted(),
                        "HT " + DF.format(bucket.totalExcludingTax) + "  TVA " + DF.format(bucket.vatAmount)));
            }
        }
        // LC-06-03-08: the ticket of a sale that settles an order states, next
        // to its own total VAT printed above, the VAT already collected on the
        // deposit — a DISTINCT statement, ventilation by ventilation. Printed
        // from the detail carried by the deposit recovery, never recomputed:
        // the ventilation was fixed when the deposit was taken. Nothing when
        // the sale recovers no deposit or carried no detail.
        for (TicketPayment payment : ticket.payments) {
            if (payment instanceof com.intermarche.edge.domain.payment.DepositPayment acompte
                    && acompte.vatDetail != null && !acompte.vatDetail.isBlank()) {
                java.util.List<com.intermarche.edge.domain.payment.DepositVat.Row> depositRows =
                        com.intermarche.edge.domain.payment.DepositVat.rows(acompte.vatDetail);
                if (!depositRows.isEmpty()) {
                    sb.append("-".repeat(WIDTH)).append("\n");
                    sb.append(center("TVA DEJA PERCUE SUR L'ACOMPTE", WIDTH)).append("\n");
                    for (com.intermarche.edge.domain.payment.DepositVat.Row row : depositRows) {
                        sb.append(formatLine("  " + row.getRateFormatted(), row.getAmountFormatted() + " E")).append("\n");
                    }
                }
                break;
            }
        }
        sb.append("\n");
        // Payments
        sb.append(center("REGLEMENT", WIDTH)).append("\n");
        for (TicketPayment payment : ticket.payments) {
            // LC-06-03-07: the settlement of a recovered deposit names itself,
            // like every other tender of the receipt.
            if (payment instanceof com.intermarche.edge.domain.payment.DepositPayment) {
                sb.append(formatLine("REPRISE ACOMPTE", DF.format(payment.amount) + " E"));
                continue;
            }
            String method = payment.getClass().getSimpleName().replace("Payment", "").toUpperCase();
            // LC-07-03-06: the rounding is stored with the sign the ledger needs
            // (so the settlements sum to the total) and PRINTED with the opposite
            // one, because on paper it reads as the adjustment to what the customer
            // hands over: "A PAYER 24,62 / ESPECES 24,60 / ARRONDI -0,02". This is
            // the single place that inversion happens.
            // LC-07-14-05: a foreign settlement states the three figures the
            // customer and the accounts both need — what was handed over, what it
            // was worth, and the rate that connects them.
            if (payment instanceof com.intermarche.edge.domain.payment.ForeignCurrencyPayment devise) {
                sb.append(formatLine("DEVISE " + safe(devise.currencyCode),
                        DF.format(payment.amount) + " E"));
                sb.append("  ").append(DF.format(devise.foreignAmount)).append(" ")
                        .append(safe(devise.currencyCode)).append("  TAUX ")
                        .append(devise.exchangeRate == null ? "" : devise.exchangeRate.toPlainString())
                        .append("\n");
                continue;
            }
            if (payment instanceof com.intermarche.edge.domain.payment.RoundingPayment) {
                sb.append(formatLine("ARRONDI", DF.format(payment.amount.negate()) + " E"));
                continue;
            }
            sb.append(formatLine(method, DF.format(payment.amount) + " E"));
            // LC-08-04-06 [sic LC-07-09-06]: a credit settlement names its debtor
            // under its own line — date, account number, account name. A month
            // later this receipt is what the account is reconciled against, and an
            // amount owed by nobody cannot be reconciled against anything.
            if (payment instanceof com.intermarche.edge.domain.payment.CreditPayment credit) {
                String saleDay = ticket.creationDate == null ? ""
                        : ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                sb.append("  ").append(saleDay).append("  COMPTE ")
                        .append(credit.accountNumber == null ? "" : credit.accountNumber)
                        .append("\n");
                sb.append("  ").append(credit.accountName == null ? "" : credit.accountName)
                        .append("\n");
            }
        }
        // Footer, with the online digital receipt link
        sb.append("\n");
        if (ticket.digitalKey != null) {
            sb.append(center("Votre ticket en ligne :", WIDTH)).append("\n");
            sb.append(center("/t/" + ticket.id + "/" + ticket.digitalKey, WIDTH)).append("\n");
            sb.append("\n");
        }
        // GB-01-07-05/06: the engine offer messages, in the ticket footer, each
        // wrapped to the paper width — the register's own footer does the same,
        // and a duplicata must read exactly like the paper the customer got. No
        // messages — the general case — prints nothing here.
        if (ticket.messages != null) {
            for (com.intermarche.edge.domain.sale.TicketMessage message : ticket.messages) {
                if (message.text == null || message.text.isBlank()) {
                    continue;
                }
                for (String messageLine : new DocumentLayout(WIDTH).wrap(message.text)) {
                    sb.append(center(messageLine, WIDTH)).append("\n");
                }
            }
        }
        sb.append(center("MERCI DE VOTRE VISITE", WIDTH)).append("\n");
        sb.append(center("A BIENTOT", WIDTH)).append("\n");
        // BO-03-08-03 / BO-03-08-05: an administered footer message, printed
        // after the courtesy line; absent or blank, nothing is printed.
        String footerMessage = posSettingsService.ticketFooterMessage();
        if (footerMessage != null && !footerMessage.isBlank()) {
            sb.append(center(footerMessage, WIDTH)).append("\n");
        }
        // The ticket number as a barcode, last thing on the receipt: it is what a
        // return, a duplicate or a claim is looked up by, and typing it back by hand
        // is where the mistakes happen. The hardware bridge turns the directive into
        // the printer's own barcode command; the POS stays text.
        sb.append("\n").append(barcode(ticket.ticketNumber)).append("\n");
        LOGGER.info("Exiting method renderTicket");
        return sb.toString();
    }

    /**
     * Builds the SALE as an administered layout sees it (BO-03-03).
     *
     * <p>Maps, lists and strings, every figure already written — the shape the
     * back office's preview pane advertises, built here from a real sale. A
     * layout therefore reads exactly what the paramétreur was shown, and cannot
     * reach anything else.
     *
     * <p>Every accessor is null-tolerant on purpose: this printer is also built
     * by hand in unit tests and reached from the store node, where a sale may
     * carry no store, no cashier or no payment, and a layout must not be the
     * thing that fails a print.
     *
     * @param ticket the sale to describe
     * @param duplicata whether this print is a duplicate
     * @param duplicataNumber the duplicate's rank, meaningless when not one
     * @return the document values
     */
    java.util.Map<String, Object> saleDocumentData(Ticket ticket, boolean duplicata,
            int duplicataNumber) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("store", storeData(ticket));
        data.put("terminal", safe(ticket.terminalId));
        data.put("operator", ticket.cashier == null ? "" : safe(ticket.cashier.getFullName()));
        data.put("date", ticket.creationDate == null ? ""
                : ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", ticket.creationDate == null ? ""
                : ticket.creationDate.format(DateTimeFormatter.ofPattern("HH:mm")));
        data.put("document", java.util.Map.of(
                "number", safe(ticket.ticketNumber),
                "duplicate", duplicata,
                "duplicateNumber", String.valueOf(duplicataNumber),
                "digitalPath", ticket.digitalKey == null ? ""
                        : "/t/" + ticket.id + "/" + ticket.digitalKey));
        data.put("lines", saleLines(ticket));
        data.put("totals", saleTotals(ticket));
        data.put("vatRows", saleVatRows(ticket));
        data.put("payments", salePayments(ticket));
        data.put("fidelity", fidelityData(ticket));
        return data;
    }

    /**
     * Describes the LOYALTY ZONE of a document, from what the closing froze on
     * the sale (BO-03-03-25, -29, -30, -31, -32).
     *
     * <p>The same zone the register builds, built here from the same columns:
     * an administered layout is a single row shared by both nodes, so a key
     * present on one side and absent on the other would render a duplicata
     * differently from its original.
     *
     * @param ticket the sale to describe
     * @return the loyalty values, every one of them already formatted
     */
    /**
     * Appends the frozen advantage lines of a sale, GROUPED by advantage type
     * and the groups ordered by {@code advantageTypeOrder} ascending (imfid
     * response A, BO-03-03-25/-33). The stored labels are printed verbatim and
     * no code is ever translated; the order is the received one, never any
     * evaluation priority.
     * <p>
     * When NO line carries a type (rules predating the referential), the output
     * is the flat list exactly as before this grouping existed — so an
     * old-ticket duplicata is byte-identical. When some lines carry a type they
     * are grouped and ordered, and any remaining untyped lines are appended flat
     * after the groups.
     *
     * @param sb the receipt buffer
     * @param ticket the sale whose frozen advantage lines are printed
     */
    private void appendFidelityAdvantages(StringBuilder sb, Ticket ticket) {
        java.util.List<com.intermarche.edge.domain.sale.TicketFidelityLine> allLines =
                ticket.fidelityLines == null ? java.util.List.of() : ticket.fidelityLines;
        boolean anyTyped = false;
        for (com.intermarche.edge.domain.sale.TicketFidelityLine line : allLines) {
            if (line.advantageType != null) {
                anyTyped = true;
                break;
            }
        }
        if (!anyTyped) {
            for (com.intermarche.edge.domain.sale.TicketFidelityLine line : allLines) {
                sb.append(formatLine("  " + line.getLabel(), "+" + DF.format(line.amount) + " E"));
            }
            return;
        }
        java.util.LinkedHashMap<String,
                java.util.List<com.intermarche.edge.domain.sale.TicketFidelityLine>> groups =
                new java.util.LinkedHashMap<>();
        java.util.List<com.intermarche.edge.domain.sale.TicketFidelityLine> untyped =
                new java.util.ArrayList<>();
        for (com.intermarche.edge.domain.sale.TicketFidelityLine line : allLines) {
            if (line.advantageType == null) {
                untyped.add(line);
            } else {
                groups.computeIfAbsent(line.advantageType, key -> new java.util.ArrayList<>()).add(line);
            }
        }
        java.util.List<String> orderedKeys = new java.util.ArrayList<>(groups.keySet());
        orderedKeys.sort(java.util.Comparator.comparingInt(key -> groupOrder(groups.get(key))));
        for (String key : orderedKeys) {
            java.util.List<com.intermarche.edge.domain.sale.TicketFidelityLine> lines = groups.get(key);
            String header = lines.get(0).advantageTypeLabel;
            sb.append(header == null ? key : header).append("\n");
            for (com.intermarche.edge.domain.sale.TicketFidelityLine line : lines) {
                sb.append(formatLine("  " + line.getLabel(), "+" + DF.format(line.amount) + " E"));
            }
        }
        for (com.intermarche.edge.domain.sale.TicketFidelityLine line : untyped) {
            sb.append(formatLine("  " + line.getLabel(), "+" + DF.format(line.amount) + " E"));
        }
    }

    /**
     * The print order of an advantage-type group: the {@code advantageTypeOrder}
     * of its first line, or {@link Integer#MAX_VALUE} when the group carries
     * none (so unordered groups fall to the end).
     *
     * @param lines the group's lines, never empty
     * @return the ordering key
     */
    private int groupOrder(java.util.List<com.intermarche.edge.domain.sale.TicketFidelityLine> lines) {
        Integer order = lines.get(0).advantageTypeOrder;
        return order == null ? Integer.MAX_VALUE : order;
    }

    /**
     * Builds the loyalty zone's template data: the same columns the register
     * exposes, so an administered layout renders a duplicata identically to its
     * original — including the advantage grouping (imfid response A) and the
     * projected/after balances (imfid response B/C) frozen on the ticket.
     *
     * @param ticket the sale to describe
     * @return the loyalty values, every one of them already formatted
     */
    private java.util.Map<String, Object> fidelityData(Ticket ticket) {
        boolean present = ticket.fidelityCard != null && !ticket.fidelityCard.isBlank();
        java.util.List<java.util.Map<String, Object>> lines = new java.util.ArrayList<>();
        for (com.intermarche.edge.domain.sale.TicketFidelityLine line
                : ticket.fidelityLines == null
                        ? java.util.List.<com.intermarche.edge.domain.sale.TicketFidelityLine>of()
                        : ticket.fidelityLines) {
            java.util.Map<String, Object> lineData = new java.util.HashMap<>();
            lineData.put("ruleCode", safe(line.ruleCode));
            lineData.put("label", line.getLabel());
            lineData.put("amount", line.getAmountFormatted());
            lineData.put("advantageType", safe(line.advantageType));
            lineData.put("advantageTypeLabel", safe(line.advantageTypeLabel));
            lineData.put("advantageTypeOrder", line.advantageTypeOrder == null
                    ? "" : String.valueOf(line.advantageTypeOrder));
            lineData.put("advantageCategory", safe(line.advantageCategory));
            lineData.put("advantageCategoryLabel", safe(line.advantageCategoryLabel));
            lines.add(lineData);
        }
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("card", safe(ticket.fidelityCard));
        data.put("present", present);
        data.put("earnTotal", ticket.fidelityEarnTotal == null
                ? "" : DF.format(ticket.fidelityEarnTotal));
        data.put("lines", lines);
        data.put("availableBalance", ticket.fidelityAvailableBalance == null
                ? "" : DF.format(ticket.fidelityAvailableBalance));
        data.put("projectedAfterEarn", ticket.fidelityProjectedAfterEarn == null
                ? "" : DF.format(ticket.fidelityProjectedAfterEarn));
        data.put("projectedLabel", posSettingsService.fidelityProjectedLabel());
        data.put("balanceAfter", ticket.fidelityBalanceAfter == null
                ? "" : DF.format(ticket.fidelityBalanceAfter));
        data.put("anonymousProjection", ticket.fidelityAnonymousProjection);
        data.put("usedAmount", DF.format(loyaltyPaid(ticket)));
        data.put("unavailable", ticket.fidelityUnavailable);
        data.put("message", unavailableMessage(ticket, present));
        return data;
    }

    /**
     * The amount this sale settled on the loyalty balance.
     *
     * @param ticket the sale
     * @return the settled amount, zero when none was taken on the balance
     */
    private BigDecimal loyaltyPaid(Ticket ticket) {
        BigDecimal used = BigDecimal.ZERO;
        if (ticket.payments == null) {
            return used;
        }
        for (TicketPayment payment : ticket.payments) {
            if (payment instanceof com.intermarche.edge.domain.payment.FidelityPayment
                    && payment.amount != null) {
                used = used.add(payment.amount);
            }
        }
        return used;
    }

    /**
     * The administered message a receipt carries when the loyalty service did
     * not answer during the sale (BO-03-03-29, BO-03-03-30).
     *
     * @param ticket the sale
     * @param present whether a card was presented
     * @return the resolved message, empty when the service answered or the shop
     *         administers none
     */
    private String unavailableMessage(Ticket ticket, boolean present) {
        if (!ticket.fidelityUnavailable) {
            return "";
        }
        String message = present
                ? posSettingsService.fidelityOfflineMessage()
                : posSettingsService.fidelityOfflineMessageNoCard();
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.replace("{carte}", safe(ticket.fidelityCard));
    }

    /**
     * Describes the point of sale a document is emitted by.
     *
     * @param ticket the sale
     * @return the store values, empty strings when the sale carries no store
     */
    private java.util.Map<String, Object> storeData(Ticket ticket) {
        if (ticket.store == null) {
            return java.util.Map.of("name", "", "street", "", "postalCode", "",
                    "city", "", "siret", "");
        }
        com.intermarche.edge.domain.store.Address address = ticket.store.address;
        return java.util.Map.of(
                "name", safe(ticket.store.name),
                "street", address == null ? "" : safe(address.streetLine1),
                "postalCode", address == null ? "" : safe(address.postalCode),
                "city", address == null ? "" : safe(address.city),
                "siret", safe(ticket.store.siret));
    }

    /**
     * Describes the articles a layout prints, in the administered print order
     * and without the cancelled ones (LC-08-01-07, BO-04-01-16).
     *
     * @param ticket the sale
     * @return one map per printed article
     */
    private java.util.List<java.util.Map<String, Object>> saleLines(Ticket ticket) {
        java.util.List<java.util.Map<String, Object>> lines = new java.util.ArrayList<>();
        if (ticket.lines == null) {
            return lines;
        }
        String order = TicketLineOrder.normalize(posSettingsService.ticketLineOrder());
        for (TicketLine line : TicketLineOrder.apply(ticket.lines, order)) {
            if (line.cancelled) continue;
            lines.add(java.util.Map.of(
                    "label", safe(line.productLabel),
                    "ean", safe(line.ean),
                    "family", safe(TicketLineOrder.familyOf(line)),
                    "unit", safe(line.unitName),
                    "quantity", line.quantity == null ? "" : DF.format(line.quantity),
                    "unitPrice", line.unitPrice == null ? "" : DF.format(line.unitPrice),
                    "total", line.totalPrice == null ? "" : DF.format(line.totalPrice),
                    "vatRate", line.vatRate == null ? "" : line.vatRate.toPlainString()));
        }
        return lines;
    }

    /**
     * Describes what the sale comes to.
     *
     * @param ticket the sale
     * @return the totals, already written
     */
    private java.util.Map<String, Object> saleTotals(Ticket ticket) {
        BigDecimal includingTax = ticket.totalIncludingTax == null
                ? BigDecimal.ZERO : ticket.totalIncludingTax;
        BigDecimal vat = ticket.totalVat == null ? BigDecimal.ZERO : ticket.totalVat;
        return java.util.Map.of(
                "excludingTax", DF.format(includingTax.subtract(vat)),
                "vat", DF.format(vat),
                "includingTax", DF.format(includingTax),
                "discount", ticket.globalDiscountApplied == null ? ""
                        : DF.format(ticket.globalDiscountApplied));
    }

    /**
     * Describes the per-rate VAT ventilation, on the same rule as the persisted
     * totals.
     *
     * @param ticket the sale
     * @return one map per VAT rate
     */
    private java.util.List<java.util.Map<String, Object>> saleVatRows(Ticket ticket) {
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        VatBreakdown breakdown = new VatBreakdown();
        if (ticket.lines != null) {
            for (TicketLine line : ticket.lines) {
                if (line.cancelled) continue;
                breakdown.add(line.vatRate, line.totalPrice);
            }
        }
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            rows.add(java.util.Map.of(
                    "rate", bucket.getRateFormatted(),
                    "base", DF.format(bucket.totalExcludingTax),
                    "vat", DF.format(bucket.vatAmount)));
        }
        return rows;
    }

    /**
     * Describes how the sale was settled, the rounding carried with the sign
     * the paper reads rather than the one the ledger stores (LC-07-03-06).
     *
     * @param ticket the sale
     * @return one map per settlement
     */
    private java.util.List<java.util.Map<String, Object>> salePayments(Ticket ticket) {
        java.util.List<java.util.Map<String, Object>> payments = new java.util.ArrayList<>();
        if (ticket.payments == null) {
            return payments;
        }
        for (TicketPayment payment : ticket.payments) {
            BigDecimal amount = payment.amount == null ? BigDecimal.ZERO : payment.amount;
            boolean rounding =
                    payment instanceof com.intermarche.edge.domain.payment.RoundingPayment;
            payments.add(java.util.Map.of(
                    "label", payment.getClass().getSimpleName()
                            .replace("Payment", "").toUpperCase(),
                    "key", safe(payment.getMethodKey()),
                    "amount", DF.format(rounding ? amount.negate() : amount)));
        }
        return payments;
    }

    /**
     * Writes the directive asking the hardware bridge for a barcode.
     *
     * <p>The print contract is plain text and stays that way: a line of its own
     * carrying this directive is the whole extension, and the bridge is what knows
     * ESC/POS. A value the bridge cannot encode prints as the directive line itself,
     * which is visible on the receipt rather than silently missing.
     *
     * @param value the value to encode
     * @return the directive line
     */
    private static String barcode(String value) {
        return "[[BARCODE " + value + "]]";
    }

    // --------------------------------------------------
    // Formatting helpers
    // --------------------------------------------------

    /**
     * Returns a printable string for a value that may be absent.
     *
     * @param value the value, possibly null
     * @return the value, or an empty string
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Centers a text within the given width by left-padding.
     *
     * @param text the text to center
     * @param width the receipt width in characters
     * @return the padded text
     */
    private String center(String text, int width) {
        if (text.length() >= width) return text;
        int pad = (width - text.length()) / 2;
        return " ".repeat(pad) + text;
    }

    /**
     * Formats a label / value pair on one receipt line, value right-aligned.
     *
     * @param left the left label
     * @param right the right value
     * @return the formatted line, newline included
     */
    private String formatLine(String left, String right) {
        int space = WIDTH - left.length() - right.length();
        if (space < 1) space = 1;
        return left + " ".repeat(space) + right + "\n";
    }
}
