package com.intermarche.pos.ui.scanner;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The call a barcode range makes to a THIRD-PARTY service at scan time
 * (BO-03-06-41).
 *
 * <p>Some in-store codes are not the register's to interpret: a partner's
 * voucher, a ticketing stub, a service coupon printed by a system this
 * referential knows nothing about. The back office administers the URL of the
 * service that DOES know, the range names it, and this client asks it.
 *
 * <p>Deliberately minimal, and deliberately ignorant of the answer's meaning.
 * A GET, a short timeout, and the body as plain text — no JSON schema is
 * assumed, because no third party's schema is known here. What the register
 * does with that text is {@link RangePurposeScanHandler}'s decision, not this
 * class's.
 *
 * <p>EVERY failure answers null: an unreachable service, a timeout, a refused
 * status, a malformed administered URL. A customer at a register must never
 * wait on somebody else's outage, and the handler turns a null into an
 * operator message rather than into a stack trace.
 */
@ApplicationScoped
public class RangeLookupClient {

    /** The placeholder an administered URL may carry for the scanned code. */
    public static final String CODE_PLACEHOLDER = "{code}";

    /** How long the register waits for the third party, connection included. */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** The largest answer kept; a service that writes a novel is truncated. */
    private static final int MAX_ANSWER = 200;

    /**
     * The HTTP client. A field rather than a local, so a test can replace it:
     * there is no third-party service to reach from a unit test.
     */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /**
     * Asks the administered service about a scanned code.
     *
     * <p>The code is substituted into the URL at {@link #CODE_PLACEHOLDER} when
     * the administered URL carries one, and appended otherwise — a store that
     * states a bare endpoint gets the ordinary query-less shape, and one that
     * needs the code in the middle of a path says where.
     *
     * @param urlTemplate the administered URL, or null
     * @param code the scanned code, or null
     * @return the service's answer, trimmed and truncated, or null when there
     *         was nothing to ask, nobody to ask, or no usable answer
     */
    public String lookup(String urlTemplate, String code) {
        if (urlTemplate == null || urlTemplate.isBlank()) {
            return null;
        }
        if (code == null || code.isBlank()) {
            return null;
        }
        String target = urlTemplate.contains(CODE_PLACEHOLDER)
                ? urlTemplate.replace(CODE_PLACEHOLDER, code.trim())
                : urlTemplate.trim() + code.trim();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                return null;
            }
            String answer = body.trim();
            return answer.length() > MAX_ANSWER ? answer.substring(0, MAX_ANSWER) : answer;
        } catch (InterruptedException e) {
            // The waiting thread was asked to stop: the flag is restored so the
            // register's own shutdown is not swallowed by this call.
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
