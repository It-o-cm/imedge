package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.sale.TaxRefundForm;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminTaxRefundResource} (BO-06-07-03): the page of the
 * latest forms, the issue of a form — refused or ready to print — the refund
 * recorded or refused, the slip downloaded or unknown, and the operator read
 * off the identity.
 */
class AdminTaxRefundResourceTest {

    /**
     * Builds the resource over a mocked service, template and identity.
     *
     * @param operator the signed-in operator, or null for an anonymous one
     * @return the resource
     */
    private AdminTaxRefundResource resource(String operator) {
        AdminTaxRefundResource resource = new AdminTaxRefundResource();
        resource.adminTaxRefunds = mock(Template.class);
        resource.taxRefundService = mock(TaxRefundService.class);
        resource.identity = mock(SecurityIdentity.class);
        resource.i18n = mock(BackOfficeMessages.class);
        when(resource.i18n.getLanguage()).thenReturn("pt");
        when(resource.identity.isAnonymous()).thenReturn(operator == null);
        Principal principal = () -> operator;
        when(resource.identity.getPrincipal()).thenReturn(principal);
        return resource;
    }

    /**
     * Builds a form.
     *
     * @param pairs the field names and values
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    /**
     * The page lists the latest forms with its notice.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thePageListsTheLatestForms() {
        AdminTaxRefundResource resource = resource("admin");
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminTaxRefunds.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        TaxRefundForm form = new TaxRefundForm();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Object> query = mock(PanacheQuery.class);
            when(query.page(anyInt(), anyInt())).thenReturn(query);
            when(query.list()).thenReturn(List.of(form));
            panache.when(() -> PanacheEntityBase.find("order by createdAt desc")).thenReturn(query);
            assertSame(instance, resource.page(null, "false"));
            verify(resource.adminTaxRefunds).data("forms", List.of(form));
            verify(instance).data("noticeOk", false);
            resource.page("Fait.", null);
            verify(instance).data("noticeOk", true);
        }
    }

    /**
     * An issue refused carries its reason; one issued names the form, the
     * signed-in operator passed along.
     */
    @Test
    void aFormIsIssuedOrRefused() {
        AdminTaxRefundResource resource = resource("admin");
        when(resource.taxRefundService.issue("T9", "John", null, null, "admin"))
                .thenReturn(new TaxRefundService.Outcome(null, "Ticket inconnu ou non clôturé."));
        Response refused = resource.issue(form("ticket", "T9", "customer", "John"));
        assertTrue(refused.getLocation().toString().contains("noticeOk=false"));
        TaxRefundForm issued = new TaxRefundForm();
        issued.number = "BV1";
        when(resource.taxRefundService.issue("T1", "John", "US", "P1", "admin"))
                .thenReturn(new TaxRefundService.Outcome(issued, null));
        Response ok = resource.issue(form("ticket", "T1", "customer", "John", "country", "US", "document", "P1"));
        assertTrue(ok.getLocation().toString().contains("noticeOk=true"));
        assertTrue(ok.getLocation().toString().contains("BV1"));
    }

    /**
     * A refund recorded or refused; an anonymous identity records no operator,
     * nor does a missing one.
     */
    @Test
    void aRefundIsRecordedOrRefused() {
        AdminTaxRefundResource anonymous = resource(null);
        when(anonymous.taxRefundService.refund("BV1", null)).thenReturn(null);
        assertTrue(anonymous.refund(form("number", "BV1")).getLocation().toString().contains("noticeOk=true"));
        when(anonymous.taxRefundService.refund("BV2", null)).thenReturn("Ce bordereau est déjà remboursé.");
        assertTrue(anonymous.refund(form("number", "BV2")).getLocation().toString().contains("noticeOk=false"));
        anonymous.identity = null;
        anonymous.refund(form("number", "BV3"));
        verify(anonymous.taxRefundService).refund("BV3", null);
    }

    /**
     * The slip of a known form is downloaded as a PDF; an unknown one is not
     * found.
     */
    @Test
    void theSlipIsDownloaded() {
        AdminTaxRefundResource resource = resource("admin");
        when(resource.taxRefundService.slip("BV1", "pt")).thenReturn(new byte[]{5});
        Response response = resource.slip("BV1");
        assertEquals(200, response.getStatus());
        assertArrayEquals(new byte[]{5}, (byte[]) response.getEntity());
        assertEquals("attachment; filename=\"BV1.pdf\"", response.getHeaderString("Content-Disposition"));
        assertThrows(NotFoundException.class, () -> resource.slip("BV9"));
    }
}
