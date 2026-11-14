package com.intermarche.edge.service;

/**
 * A side-specific step of the cash session lifecycle, run by
 * {@link CashSessionService} right after the session row is written — once
 * when a session opens, once when it closes.
 *
 * <p>The service is a copy shared by the register and the store node, but
 * only the register owns a payment terminal (LC-01-02-09, LC-13-04-05) and
 * the instruments it issued (BO-10-08-13/-14/-15). Those steps therefore live
 * behind this interface: the register declares its implementations in its own
 * areas, the store node declares none, and the service iterates whatever CDI
 * finds. A failing hook is contained by the service and never undoes a
 * session already written.
 */
public interface SessionLifecycleHook {

    /**
     * Called after a cash session has been opened and persisted.
     */
    void onSessionOpened();

    /**
     * Called after a cash session has been closed and persisted.
     */
    void onSessionClosed();
}
