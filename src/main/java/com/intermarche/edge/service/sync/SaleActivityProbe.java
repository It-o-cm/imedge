package com.intermarche.edge.service.sync;

/**
 * Tells the referential pull whether a SALE IS IN PROGRESS on this node
 * (BO-10-08-25), so that an update of the articles, prices or promotions
 * arriving during the day never changes what the basket being rung up was
 * started with.
 *
 * <p>An interface beside the pull rather than a dependency of it: only a
 * register sells, and the node that pulls without selling — the store node —
 * simply has no implementation, which the pull reads as "never a sale".
 */
public interface SaleActivityProbe {

    /**
     * Whether an update that would reach the sale must wait.
     *
     * @return true while a ticket is being rung up and the shop protects it
     */
    boolean saleInProgress();
}
