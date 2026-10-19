package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.service.TicketNumberService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Answers whether an article may be rung ON THIS REGISTER (BO-03-01-18).
 *
 * <p>The question has one answer and five askers: the catalog EAN, the internal
 * code, the typed PLU, the weighed scale label and the GS1 payload all reach
 * the same articles by different roads, and an article a store took out of one
 * island must not come back in through the scale. So the decision lives here,
 * once, and every entry path consults it.
 *
 * <p>What it needs is this register's IDENTITY — its terminal and the island it
 * stands on — which is exactly what the back office distributed on the
 * {@link CheckoutPoint}. A register the back office cannot describe keeps
 * selling: a till missing from the referential is a referential problem, not a
 * reason to refuse a customer.
 */
@ApplicationScoped
public class ArticleScopeService {

    /** This register's own identity, as the ticket numbering knows it. */
    @Inject
    TicketNumberService ticketNumberService;

    /**
     * Indicates whether the article may be rung on this register
     * (BO-03-01-18).
     *
     * @param product the article, or null
     * @return true unless the referential excludes it from this register or
     *         from the island this register stands on
     */
    public boolean soldHere(Product product) {
        if (product == null) {
            return true;
        }
        String terminalId = ticketNumberService == null
                ? null : ticketNumberService.getTerminalId();
        CheckoutPoint point = null;
        try {
            point = CheckoutPoint.findByTerminal(terminalId);
        } catch (Exception e) {
            // A referential this service cannot reach is not an answer the
            // customer should pay for: the article stays sellable.
            return true;
        }
        return ProductAttributes.soldOn(product, terminalId,
                point == null ? null : point.islandCode);
    }
}
