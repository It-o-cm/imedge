package com.intermarche.pos.service.campaign;

import com.intermarche.pos.domain.util.DateTimeProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BudgetStatusClient}.
 * <p>
 * The private final {@link HttpClient} is replaced with a mock through
 * reflection and {@link DateTimeProvider} is frozen for the read instant. Every
 * arm of {@code groupBudgetStatus} — disabled, success, no-data (both legs),
 * server error, exception, and the byStore-null mapping — is exercised, never
 * throwing.
 */
class BudgetStatusClientTest {

    /** A fixed read instant. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 15, 10, 0);

    /**
     * Builds a client with a configured engine and a mock HTTP client injected.
     *
     * @param client the mock HTTP client
     * @return the wired client
     * @throws Exception when the reflection fails
     */
    private BudgetStatusClient clientWith(HttpClient client) throws Exception {
        BudgetStatusClient budgetStatusClient = new BudgetStatusClient();
        budgetStatusClient.url = Optional.of("http://engine.local");
        budgetStatusClient.user = Optional.of("u");
        budgetStatusClient.password = Optional.of("p");
        budgetStatusClient.timeoutMillis = 2500;
        Field field = BudgetStatusClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(budgetStatusClient, client);
        return budgetStatusClient;
    }

    /**
     * Builds a mock HTTP response with the given status and body.
     *
     * @param status the HTTP status
     * @param body the body
     * @return the mock response
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /**
     * With no engine configured the status is unavailable, stamped with the read
     * instant.
     *
     * @throws Exception when the reflection fails
     */
    @Test
    void statusUnavailableWhenDisabled() throws Exception {
        BudgetStatusClient client = clientWith(mock(HttpClient.class));
        client.url = Optional.empty();
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            BudgetStatusClient.BudgetStatus status = client.groupBudgetStatus("PROMO1");
            assertFalse(status.available());
            assertEquals(NOW, status.readAt());
            assertFalse(client.isEnabled());
        }
    }

    /**
     * A configured but blank URL is not enabled, so the status is unavailable
     * (the blank-url leg of the enable guard).
     *
     * @throws Exception when the reflection fails
     */
    @Test
    void statusUnavailableWhenUrlBlank() throws Exception {
        BudgetStatusClient client = clientWith(mock(HttpClient.class));
        client.url = Optional.of("   ");
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            assertFalse(client.groupBudgetStatus("PROMO1").available());
            assertFalse(client.isEnabled());
        }
    }

    /**
     * A sub-200 answer is unavailable (the statusCode &lt; 200 leg).
     *
     * @throws Exception when the send fails
     */
    @Test
    void statusUnavailableOnInformationalStatus() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(response(199, "")).when(http).send(any(HttpRequest.class), any());
        BudgetStatusClient client = clientWith(http);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            assertFalse(client.groupBudgetStatus("PROMO1").available());
        }
    }

    /**
     * An interrupted read is caught, restores the interrupt flag and is
     * unavailable.
     *
     * @throws Exception when the send fails
     */
    @Test
    void statusUnavailableAndRestoresInterruptWhenInterrupted() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new InterruptedException()).when(http).send(any(HttpRequest.class), any());
        BudgetStatusClient client = clientWith(http);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            assertFalse(client.groupBudgetStatus("PROMO1").available());
            assertTrue(Thread.interrupted());
        }
    }

    /**
     * A successful read parses the figures and the per-store breakdown.
     *
     * @throws Exception when the send fails
     */
    @Test
    void statusParsesTheEngineAnswer() throws Exception {
        String body = "{\"data\":{\"groupBudgetStatus\":{\"consumedAmount\":40.00,"
                + "\"maxAmount\":100.00,\"remainingAmount\":60.00,\"consumedCount\":8,"
                + "\"maxApplications\":50,\"remainingCount\":42,"
                + "\"byStore\":[{\"storeCode\":\"C04\",\"consumedAmount\":40.00,\"consumedCount\":8}]}}}";
        HttpClient http = mock(HttpClient.class);
        doReturn(response(200, body)).when(http).send(any(HttpRequest.class), any());
        BudgetStatusClient client = clientWith(http);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            BudgetStatusClient.BudgetStatus status = client.groupBudgetStatus("PROMO1");
            assertTrue(status.available());
            assertEquals(new BigDecimal("40.00"), status.consumedAmount());
            assertEquals(new BigDecimal("100.00"), status.maxAmount());
            assertEquals(8, status.consumedCount());
            assertEquals(1, status.byStore().size());
            assertEquals("C04", status.byStore().get(0).storeCode());
        }
    }

    /**
     * A successful read whose status carries no per-store block yields an empty
     * breakdown (the byStore-null mapping arm).
     *
     * @throws Exception when the send fails
     */
    @Test
    void statusWithoutByStoreYieldsEmptyBreakdown() throws Exception {
        String body = "{\"data\":{\"groupBudgetStatus\":{\"consumedAmount\":1.00}}}";
        HttpClient http = mock(HttpClient.class);
        doReturn(response(200, body)).when(http).send(any(HttpRequest.class), any());
        BudgetStatusClient client = clientWith(http);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            BudgetStatusClient.BudgetStatus status = client.groupBudgetStatus("PROMO1");
            assertTrue(status.available());
            assertTrue(status.byStore().isEmpty());
        }
    }

    /**
     * A 2xx answer whose data block is null is unavailable (the data-null leg).
     *
     * @throws Exception when the send fails
     */
    @Test
    void statusUnavailableWhenDataNull() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(response(200, "{}")).when(http).send(any(HttpRequest.class), any());
        BudgetStatusClient client = clientWith(http);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            assertFalse(client.groupBudgetStatus("PROMO1").available());
        }
    }

    /**
     * A 2xx answer whose group status is null is unavailable (the
     * groupBudgetStatus-null leg).
     *
     * @throws Exception when the send fails
     */
    @Test
    void statusUnavailableWhenGroupNull() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(response(200, "{\"data\":{\"groupBudgetStatus\":null}}"))
                .when(http).send(any(HttpRequest.class), any());
        BudgetStatusClient client = clientWith(http);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            assertFalse(client.groupBudgetStatus("PROMO1").available());
        }
    }

    /**
     * A non-2xx answer is unavailable.
     *
     * @throws Exception when the send fails
     */
    @Test
    void statusUnavailableOnServerError() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(response(500, "boom")).when(http).send(any(HttpRequest.class), any());
        BudgetStatusClient client = clientWith(http);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            assertFalse(client.groupBudgetStatus("PROMO1").available());
        }
    }

    /**
     * A transport failure is caught and the status is unavailable.
     *
     * @throws Exception when the send fails
     */
    @Test
    void statusUnavailableOnException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new java.io.IOException("down")).when(http).send(any(HttpRequest.class), any());
        BudgetStatusClient client = clientWith(http);
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            assertFalse(client.groupBudgetStatus("PROMO1").available());
        }
    }

    /**
     * A configured but blank user sends no authorization header and still reads
     * (the no-auth arm of the request builder).
     *
     * @throws Exception when the send fails
     */
    @Test
    void statusReadsWithoutAuthHeaderWhenUserBlank() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(response(200, "{\"data\":{\"groupBudgetStatus\":{\"consumedAmount\":1.00}}}"))
                .when(http).send(any(HttpRequest.class), any());
        BudgetStatusClient client = clientWith(http);
        client.user = Optional.of("   ");
        try (MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            clock.when(DateTimeProvider::now).thenReturn(NOW);
            assertTrue(client.groupBudgetStatus("PROMO1").available());
        }
    }
}
