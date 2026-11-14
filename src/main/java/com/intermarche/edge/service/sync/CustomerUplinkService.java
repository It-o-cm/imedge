package com.intermarche.edge.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.payment.AccountCustomer;
import com.intermarche.edge.domain.sync.CustomerUplink;
import com.intermarche.edge.service.ExternalRelayService;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The UPLINK of the customer files to the commercial management
 * (BO-02-04-13): every customer in account created or corrected in the back
 * office, or at a register and consolidated here, is sent up to the
 * commercial management, and sent again until it ACKNOWLEDGES it.
 *
 * <p>The acknowledgment is the commercial management's 2xx answer to the POST
 * of the file. Anything else — no address administered yet, a host that does
 * not answer, a 4xx or a 5xx — leaves the file pending, counts the attempt and
 * keeps the error, and the loop replays it at the administered delay. There is
 * no ceiling and no discard path: the acceptance criterion is that the call is
 * replayed until the update is done.
 *
 * <p>What the commercial management itself sends down — the customer feed —
 * is never sent back up: only the two roads a HOSTESS corrects a file by, the
 * back-office screen and the register, enqueue anything.
 *
 * <p>The loop reads the database and the administered parameters inside a
 * transaction of its own, through the CDI proxy: the scheduler thread carries
 * neither a transaction nor a request context, and the delay and the address
 * are read again at every tick, so a value changed in the back office applies
 * from the next one.
 */
@ApplicationScoped
public class CustomerUplinkService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CustomerUplinkService.class);

    /** How often the loop looks whether a delivery is due, in seconds. */
    static final long TICK_SECONDS = 5L;

    /** How many files one delivery pass sends at most. */
    static final int BATCH_SIZE = 50;

    /** The read timeout of one call to the commercial management, in milliseconds. */
    static final long TIMEOUT_MILLIS = 3000L;

    /** The administered address and delay. */
    @Inject
    PosSettingsService posSettingsService;

    /** The tolerant HTTP forwarder. */
    @Inject
    ExternalRelayService externalRelayService;

    /** Writes the frozen payloads. */
    @Inject
    ObjectMapper objectMapper;

    /** The delivery loop, started with the application. */
    ScheduledExecutorService executor;

    /** When the last delivery pass ran, or null before the first one. */
    LocalDateTime lastPass;

    /**
     * Files the current state of a customer for the commercial management,
     * replacing any state of the same account still pending.
     *
     * <p>Called in the transaction of the correction itself, so a file saved is
     * a file queued: the two are committed together or not at all.
     *
     * @param customer the customer as just saved, possibly null
     * @param source where the correction was made
     * @return the pending row, or null when there was nothing to send
     */
    public CustomerUplink enqueue(AccountCustomer customer, CustomerUplink.Source source) {
        LOGGER.info("Entering method enqueue with source: " + source);
        if (customer == null || customer.accountNumber == null) {
            LOGGER.info("Exiting method enqueue: nothing to send");
            return null;
        }
        String body = serialize(customer, source);
        if (body == null) {
            LOGGER.info("Exiting method enqueue: unwritable payload");
            return null;
        }
        CustomerUplink row = CustomerUplink.findByAccount(customer.accountNumber);
        boolean created = row == null;
        if (created) {
            row = new CustomerUplink();
            row.accountNumber = customer.accountNumber;
        }
        row.source = source;
        row.payload = body;
        row.changedAt = LocalDateTime.now();
        row.attempts = 0;
        row.lastError = null;
        row.lastAttemptAt = null;
        row.acknowledgedAt = null;
        if (created) {
            row.persist();
        }
        LOGGER.infof("Fiche client %s mise en attente de remontée (%s)", customer.accountNumber, source);
        LOGGER.info("Exiting method enqueue");
        return row;
    }

    /**
     * Writes the payload of a customer: its identity, its whole file and where
     * the correction was made.
     *
     * @param customer the customer
     * @param source where the correction was made
     * @return the JSON body, or null when it cannot be written
     */
    String serialize(AccountCustomer customer, CustomerUplink.Source source) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("accountNumber", customer.accountNumber);
        file.put("origin", customer.origin == null ? null : customer.origin.name());
        file.put("source", source.name());
        file.put("companyName", customer.companyName);
        file.put("civility", customer.civility);
        file.put("lastName", customer.lastName);
        file.put("firstName", customer.firstName);
        file.put("street", customer.address == null ? null : customer.address.streetLine1);
        file.put("postalCode", customer.address == null ? null : customer.address.postalCode);
        file.put("city", customer.address == null ? null : customer.address.city);
        file.put("siret", customer.siret);
        file.put("vatNumber", customer.vatNumber);
        file.put("taxId", customer.taxId);
        file.put("phone", customer.phone);
        file.put("email", customer.email);
        file.put("blocked", customer.blocked);
        file.put("dueDate", customer.dueDate == null ? null : customer.dueDate.toString());
        file.put("discountPercent", customer.discountPercent);
        file.put("creditLimit", customer.creditLimit);
        file.put("changedAt", LocalDateTime.now().toString());
        try {
            return objectMapper.writeValueAsString(file);
        } catch (Exception failure) {
            LOGGER.warnf("Fiche client %s non sérialisable (%s)", customer.accountNumber,
                    failure.getMessage());
            return null;
        }
    }

    /**
     * Starts the delivery loop: one daemon thread, a short fixed tick, the
     * administered delay deciding at each tick whether a pass is due.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Entering method onStart");
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "customer-uplink");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tickSafely, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        LOGGER.info("Exiting method onStart");
    }

    /**
     * Runs one tick, swallowing whatever it fails on: a commercial management
     * answering nonsense must not cancel the loop for the rest of the day.
     */
    void tickSafely() {
        try {
            self().deliverIfDue(LocalDateTime.now());
        } catch (Exception failure) {
            LOGGER.warnf("Remontée des clients impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Runs a delivery pass when the administered delay has elapsed since the
     * last one.
     *
     * @param now the moment of the tick
     * @return the number of files acknowledged, zero when no pass was due
     */
    @Transactional
    public int deliverIfDue(LocalDateTime now) {
        if (lastPass != null && now.isBefore(lastPass.plusSeconds(posSettingsService.customerUplinkSeconds()))) {
            return 0;
        }
        lastPass = now;
        return deliverPending();
    }

    /**
     * Sends the pending files, oldest first, and returns how many the
     * commercial management acknowledged.
     *
     * <p>A failure does NOT stop the pass: each file counts its own attempt.
     * Without an administered address nothing is attempted — the files wait,
     * which is not the commercial management failing.
     *
     * @return the number of files acknowledged
     */
    @Transactional
    public int deliverPending() {
        LOGGER.info("Entering method deliverPending");
        String url = posSettingsService.customerUplinkUrl();
        if (url.isEmpty()) {
            LOGGER.info("Exiting method deliverPending: no address administered");
            return 0;
        }
        int acknowledged = 0;
        for (CustomerUplink row : CustomerUplink.nextPending(BATCH_SIZE)) {
            ExternalRelayService.RelayResult result = externalRelayService.relay(
                    Optional.of(url), "POST", null, row.payload, TIMEOUT_MILLIS);
            row.lastAttemptAt = LocalDateTime.now();
            if (result.reached() && result.status() >= 200 && result.status() < 300) {
                row.acknowledgedAt = row.lastAttemptAt;
                row.lastError = null;
                acknowledged++;
                continue;
            }
            row.attempts++;
            row.lastError = result.reached() ? "HTTP " + result.status() : "injoignable";
        }
        LOGGER.infof("Remontée des clients : %d fiche(s) acquittée(s), %d en attente.",
                acknowledged, CustomerUplink.countPending());
        LOGGER.info("Exiting method deliverPending");
        return acknowledged;
    }

    /**
     * Returns the state of one account's uplink, for the customer file.
     *
     * @param accountNumber the account number, possibly null
     * @return the row, or null when the account was never sent up
     */
    public CustomerUplink stateOf(String accountNumber) {
        LOGGER.info("Entering method stateOf with accountNumber: " + accountNumber);
        LOGGER.info("Exiting method stateOf");
        return CustomerUplink.findByAccount(accountNumber);
    }

    /**
     * Returns this bean through its CDI client proxy, or {@code this} when there
     * is no container — a plain unit test.
     *
     * @return the proxied bean, or this instance
     */
    CustomerUplinkService self() {
        try {
            CustomerUplinkService proxy = io.quarkus.arc.Arc.container()
                    .instance(CustomerUplinkService.class).get();
            return proxy == null ? this : proxy;
        } catch (Exception unavailable) {
            return this;
        }
    }
}
