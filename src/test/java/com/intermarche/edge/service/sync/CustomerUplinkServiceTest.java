package com.intermarche.edge.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.payment.AccountCustomer;
import com.intermarche.edge.domain.store.Address;
import com.intermarche.edge.domain.sync.CustomerUplink;
import com.intermarche.edge.service.ExternalRelayService;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerUplinkService} (BO-02-04-13).
 * <p>
 * The relay, the parameters and the JSON writer are mocks; the Panache
 * finders of {@link CustomerUplink} are intercepted with a static mock and its
 * construction neutralised. Branches: the null customer and the customer
 * without a number; the unwritable payload; the creation and the replacement
 * of a pending row; the tick swallowing a failure; the pass due and not due;
 * the missing address; the acknowledged answer, the refused one (below and
 * above the 2xx range) and the unreached host.
 */
class CustomerUplinkServiceTest {

    /**
     * Builds the service over its mocked collaborators.
     *
     * @return the service
     */
    private CustomerUplinkService newService() {
        CustomerUplinkService service = new CustomerUplinkService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.externalRelayService = mock(ExternalRelayService.class);
        service.objectMapper = new ObjectMapper();
        return service;
    }

    /**
     * Builds a customer with a whole file.
     *
     * @return the customer
     */
    private AccountCustomer customer() {
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = "C-1";
        customer.origin = AccountCustomer.Origin.INTEGRATED;
        customer.companyName = "Acme";
        customer.address = new Address();
        customer.address.city = "Lyon";
        customer.taxId = "FR123";
        customer.dueDate = LocalDate.of(2026, 11, 30);
        customer.creditLimit = new BigDecimal("500.00");
        return customer;
    }

    /**
     * Stubs the row of one account.
     *
     * @param panache the active static mock
     * @param account the account number
     * @param row the row held, or null
     */
    @SuppressWarnings("unchecked")
    private void stubRow(MockedStatic<PanacheEntityBase> panache, String account, CustomerUplink row) {
        PanacheQuery<CustomerUplink> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> CustomerUplink.find("accountNumber", account)).thenReturn(query);
    }

    /**
     * Nothing is filed for no customer, nor for a customer without a number.
     */
    @Test
    void nothingIsFiledWithoutAnAccount() {
        CustomerUplinkService service = newService();
        assertNull(service.enqueue(null, CustomerUplink.Source.BACK_OFFICE));
        assertNull(service.enqueue(new AccountCustomer(), CustomerUplink.Source.BACK_OFFICE));
    }

    /**
     * A first correction creates the row, its payload carrying the whole file
     * and the road it came by.
     */
    @Test
    void aFirstCorrectionCreatesThePendingRow() {
        CustomerUplinkService service = newService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<CustomerUplink> rows = mockConstruction(CustomerUplink.class)) {
            stubRow(panache, "C-1", null);
            CustomerUplink row = service.enqueue(customer(), CustomerUplink.Source.REGISTER);
            assertSame(rows.constructed().get(0), row);
            assertEquals("C-1", row.accountNumber);
            assertEquals(CustomerUplink.Source.REGISTER, row.source);
            assertTrue(row.payload.contains("\"companyName\":\"Acme\""));
            assertTrue(row.payload.contains("\"city\":\"Lyon\""));
            assertTrue(row.payload.contains("\"taxId\":\"FR123\""));
            assertTrue(row.payload.contains("\"source\":\"REGISTER\""));
            assertTrue(row.payload.contains("\"origin\":\"INTEGRATED\""));
            assertTrue(row.payload.contains("\"dueDate\":\"2026-11-30\""));
            assertNotNull(row.changedAt);
            verify(row).persist();
        }
    }

    /**
     * A correction of an account already sent up replaces its row: pending
     * again, attempts and error reset; a file without address or origin still
     * writes.
     */
    @Test
    void aNewCorrectionMakesTheRowPendingAgain() {
        CustomerUplinkService service = newService();
        CustomerUplink held = mock(CustomerUplink.class);
        held.attempts = 4;
        held.lastError = "HTTP 503";
        held.acknowledgedAt = LocalDateTime.of(2026, 10, 1, 8, 0);
        AccountCustomer bare = customer();
        bare.address = null;
        bare.origin = null;
        bare.dueDate = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRow(panache, "C-1", held);
            assertSame(held, service.enqueue(bare, CustomerUplink.Source.BACK_OFFICE));
        }
        assertEquals(0, held.attempts);
        assertNull(held.lastError);
        assertNull(held.acknowledgedAt);
        assertTrue(held.payload.contains("\"street\":null"));
        verify(held, never()).persist();
    }

    /**
     * A payload the writer cannot write files nothing.
     *
     * @throws Exception never, the writer being a mock
     */
    @Test
    void anUnwritablePayloadFilesNothing() throws Exception {
        CustomerUplinkService service = newService();
        service.objectMapper = mock(ObjectMapper.class);
        when(service.objectMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonGenerationException("boom", (com.fasterxml.jackson.core.JsonGenerator) null));
        assertNull(service.enqueue(customer(), CustomerUplink.Source.BACK_OFFICE));
    }

    /**
     * Without an administered address nothing is attempted.
     */
    @Test
    void withoutAnAddressNothingIsAttempted() {
        CustomerUplinkService service = newService();
        when(service.posSettingsService.customerUplinkUrl()).thenReturn("");
        assertEquals(0, service.deliverPending());
        verify(service.externalRelayService, never()).relay(any(), anyString(), any(), any(), anyLong());
    }

    /**
     * A pass sends every pending file: a 2xx acknowledges it, a 4xx, a 5xx or a
     * 1xx counts an attempt and keeps the status, an unreached host says so.
     */
    @Test
    void aPassAcknowledgesOrCountsEachFile() {
        CustomerUplinkService service = newService();
        when(service.posSettingsService.customerUplinkUrl()).thenReturn("http://gc/clients");
        CustomerUplink ok = new CustomerUplink();
        ok.payload = "ok";
        ok.attempts = 2;
        ok.lastError = "HTTP 500";
        CustomerUplink refused = new CustomerUplink();
        refused.payload = "refused";
        CustomerUplink early = new CustomerUplink();
        early.payload = "early";
        CustomerUplink down = new CustomerUplink();
        down.payload = "down";
        when(service.externalRelayService.relay(eq(Optional.of("http://gc/clients")), eq("POST"), isNull(),
                eq("ok"), eq(CustomerUplinkService.TIMEOUT_MILLIS)))
                .thenReturn(new ExternalRelayService.RelayResult(true, 204, ""));
        when(service.externalRelayService.relay(any(), anyString(), any(), eq("refused"), anyLong()))
                .thenReturn(new ExternalRelayService.RelayResult(true, 409, ""));
        when(service.externalRelayService.relay(any(), anyString(), any(), eq("early"), anyLong()))
                .thenReturn(new ExternalRelayService.RelayResult(true, 100, ""));
        when(service.externalRelayService.relay(any(), anyString(), any(), eq("down"), anyLong()))
                .thenReturn(new ExternalRelayService.RelayResult(false, 0, ""));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<CustomerUplink> pending = mock(PanacheQuery.class);
            @SuppressWarnings("unchecked")
            PanacheQuery<CustomerUplink> page = mock(PanacheQuery.class);
            when(pending.page(0, CustomerUplinkService.BATCH_SIZE)).thenReturn(page);
            when(page.list()).thenReturn(List.of(ok, refused, early, down));
            panache.when(() -> CustomerUplink.find("acknowledgedAt is null order by id")).thenReturn(pending);
            assertEquals(1, service.deliverPending());
        }
        assertNotNull(ok.acknowledgedAt);
        assertNull(ok.lastError);
        assertFalse(ok.isPending());
        assertEquals(1, refused.attempts);
        assertEquals("HTTP 409", refused.lastError);
        assertEquals("HTTP 100", early.lastError);
        assertEquals("injoignable", down.lastError);
        assertNotNull(down.lastAttemptAt);
        assertTrue(down.isPending());
    }

    /**
     * The first tick runs a pass; a tick before the administered delay runs
     * none; one after it runs the next — a pass reads the address, a skipped
     * tick does not.
     */
    @Test
    void thePassRunsAtTheAdministeredDelay() {
        CustomerUplinkService service = newService();
        when(service.posSettingsService.customerUplinkSeconds()).thenReturn(60);
        when(service.posSettingsService.customerUplinkUrl()).thenReturn("");
        LocalDateTime start = LocalDateTime.of(2026, 10, 25, 10, 0);
        assertEquals(0, service.deliverIfDue(start));
        verify(service.posSettingsService, times(1)).customerUplinkUrl();
        assertEquals(0, service.deliverIfDue(start.plusSeconds(30)));
        verify(service.posSettingsService, times(1)).customerUplinkUrl();
        assertEquals(0, service.deliverIfDue(start.plusSeconds(60)));
        verify(service.posSettingsService, times(2)).customerUplinkUrl();
    }

    /**
     * The tick runs through the bean itself outside a container and swallows a
     * failure of the pass.
     */
    @Test
    void theTickSwallowsAFailure() {
        CustomerUplinkService service = newService();
        when(service.posSettingsService.customerUplinkUrl()).thenThrow(new IllegalStateException("db down"));
        service.tickSafely();
        verify(service.posSettingsService).customerUplinkUrl();
        assertSame(service, service.self());
    }

    /**
     * The loop starts as a daemon thread and the state of an account is read
     * through the finder.
     */
    @Test
    void theLoopStartsAndTheStateIsRead() {
        CustomerUplinkService service = newService();
        service.onStart(null);
        assertNotNull(service.executor);
        service.executor.shutdownNow();
        CustomerUplink held = new CustomerUplink();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRow(panache, "C-1", held);
            assertSame(held, service.stateOf("C-1"));
        }
    }
    /**
     * A nominal tick runs the pass through the bean's proxy without failing.
     */
    @Test
    void aNominalTickRunsThePass() {
        CustomerUplinkService service = newService();
        when(service.posSettingsService.customerUplinkUrl()).thenReturn("");
        service.tickSafely();
        verify(service.posSettingsService).customerUplinkUrl();
    }

    /**
     * The CDI proxy is used when the container hands one, the bean itself when
     * the handle holds none.
     */
    @Test
    @SuppressWarnings("unchecked")
    void selfUsesTheAvailableCdiProxy() {
        CustomerUplinkService service = newService();
        CustomerUplinkService proxy = mock(CustomerUplinkService.class);
        io.quarkus.arc.ArcContainer container = mock(io.quarkus.arc.ArcContainer.class);
        io.quarkus.arc.InstanceHandle<CustomerUplinkService> handle = mock(io.quarkus.arc.InstanceHandle.class);
        when(container.instance(CustomerUplinkService.class)).thenReturn(handle);
        try (MockedStatic<io.quarkus.arc.Arc> arc = mockStatic(io.quarkus.arc.Arc.class)) {
            arc.when(io.quarkus.arc.Arc::container).thenReturn(container);
            assertSame(service, service.self());
            when(handle.get()).thenReturn(proxy);
            assertSame(proxy, service.self());
        }
    }
}
