package com.intermarche.edge.security;

import com.intermarche.edge.domain.audit.AdminAuditEntry;
import com.intermarche.edge.domain.payment.Currency;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Profile;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.service.AdminAuditService;
import com.intermarche.edge.service.PermissionService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FeatureAccessFilter} (BO-01-04-01, -02, -03, -05,
 * BO-01-01-03), targeting 100% branch coverage.
 *
 * <p>Branch enumeration — request: no resource info, no method, no
 * declaration, no identity, an anonymous one; a refusal on a self-guarded and
 * on a guarded handler; a VIEW under validation; a modification without and
 * with validation (held, failed with and without validating profile,
 * succeeded); a file upload under validation. Option: declared, CREATE
 * without key, blank key, key without entity, key with entity found or not,
 * entity key named. Form: not a form, no stream, empty pairs, names without
 * value. Response: no access, a VIEW, an HTTP error, no location, a refusing
 * and an accepting notice. Helpers: target, path, query reading.
 */
class FeatureAccessFilterTest {

    /** Handlers carrying the declarations the filter reads. */
    static class Handlers {

        /** A screen. */
        @FeatureAccess(Feature.SETTINGS)
        public void view() {
        }

        /** A saving gesture keyed on the record id. */
        @FeatureAccess(value = Feature.ACCOUNT, option = CrudOption.CREATE, updateKey = "id")
        public void save() {
        }

        /** A saving gesture keyed on a business code. */
        @FeatureAccess(value = Feature.CURRENCY, option = CrudOption.CREATE, updateKey = "code", entity = Currency.class)
        public void saveByCode() {
        }

        /** A saving gesture whose code is held by another property. */
        @FeatureAccess(value = Feature.CURRENCY, option = CrudOption.CREATE, updateKey = "iso", entity = Currency.class,
                entityKey = "code")
        public void saveByProperty() {
        }

        /** A creation that never modifies. */
        @FeatureAccess(value = Feature.SAFE_MOVEMENT, option = CrudOption.CREATE)
        public void create() {
        }

        /** A deletion. */
        @FeatureAccess(value = Feature.ACCOUNT, option = CrudOption.DELETE)
        public void delete() {
        }

        /** A self-guarded deletion. */
        @FeatureAccess(value = Feature.PROFILE, option = CrudOption.DELETE, selfGuarded = true)
        public void selfGuarded() {
        }

        /** A handler without declaration. */
        public void plain() {
        }
    }

    /**
     * Reads one handler.
     *
     * @param name the handler name
     * @return the method
     * @throws NoSuchMethodException never for the handlers above
     */
    private static Method handler(String name) throws NoSuchMethodException {
        return Handlers.class.getMethod(name);
    }

    /**
     * Builds a permission decision through its private constructor.
     *
     * @param allowed whether the request is authorized
     * @param validation whether the grant asks for a validation
     * @param validatorProfile the validating profile, or null
     * @return the decision
     * @throws Exception never
     */
    private static PermissionService.Decision decision(boolean allowed, boolean validation, Long validatorProfile)
            throws Exception {
        Constructor<PermissionService.Decision> constructor = PermissionService.Decision.class
                .getDeclaredConstructor(boolean.class, boolean.class, Long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(allowed, validation, validatorProfile);
    }

    /**
     * A filter over mocked collaborators, signed in as mcurie, reaching a handler.
     *
     * @param method the handler, or null for none
     * @return the filter
     */
    private FeatureAccessFilter filter(Method method) {
        FeatureAccessFilter filter = new FeatureAccessFilter();
        filter.resourceInfo = mock(ResourceInfo.class);
        when(filter.resourceInfo.getResourceMethod()).thenReturn(method);
        filter.identity = mock(SecurityIdentity.class);
        Principal principal = () -> "mcurie";
        when(filter.identity.getPrincipal()).thenReturn(principal);
        filter.permissionService = mock(PermissionService.class);
        filter.auditService = mock(AdminAuditService.class);
        filter.deniedPage = template("denied");
        filter.validationPage = template("validation");
        return filter;
    }

    /**
     * A template rendering a fixed body.
     *
     * @param body the body
     * @return the template
     */
    private Template template(String body) {
        Template template = mock(Template.class);
        TemplateInstance instance = mock(TemplateInstance.class, RETURNS_SELF);
        when(instance.render()).thenReturn(body);
        when(template.data(anyString(), any())).thenReturn(instance);
        return template;
    }

    /**
     * A request on a path, with a form body or none.
     *
     * @param method the HTTP method
     * @param path the path as the URI info gives it
     * @param body the URL-encoded body, or null for a request without form
     * @param query the raw query, or null
     * @return the request
     */
    private ContainerRequestContext request(String method, String path, String body, String query) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMethod()).thenReturn(method);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        String full = "http://node/" + path.replaceFirst("^/", "") + (query == null ? "" : "?" + query);
        when(uri.getRequestUri()).thenReturn(URI.create(full));
        when(request.getUriInfo()).thenReturn(uri);
        if (body != null) {
            when(request.getMediaType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
            InputStream stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            when(request.getEntityStream()).thenReturn(stream);
        }
        return request;
    }

    /**
     * Reads the access the request filter left for the response.
     *
     * @param request the request
     * @return the access, or null when none was left
     */
    private FeatureAccessFilter.Access access(ContainerRequestContext request) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(request).setProperty(eq(FeatureAccessFilter.ACCESS), captor.capture());
        return (FeatureAccessFilter.Access) captor.getValue();
    }

    /**
     * A handler without declaration, an unknown method, no resource info, no
     * identity and an anonymous one are left to the container.
     *
     * @throws Exception never
     */
    @Test
    void undeclaredOrAnonymousRequestsPass() throws Exception {
        FeatureAccessFilter plain = filter(handler("plain"));
        ContainerRequestContext request = request("GET", "/admin/x", null, null);
        plain.filter(request);
        FeatureAccessFilter noMethod = filter(null);
        noMethod.filter(request);
        FeatureAccessFilter noInfo = filter(handler("view"));
        noInfo.resourceInfo = null;
        noInfo.filter(request);
        FeatureAccessFilter noIdentity = filter(handler("view"));
        noIdentity.identity = null;
        noIdentity.filter(request);
        FeatureAccessFilter anonymous = filter(handler("view"));
        when(anonymous.identity.isAnonymous()).thenReturn(true);
        anonymous.filter(request);
        verify(request, never()).abortWith(any());
        verify(request, never()).setProperty(anyString(), any());
        verifyNoInteractions(plain.permissionService, anonymous.permissionService);
    }

    /**
     * A refusal on a guarded handler is logged and answered 403; on a
     * self-guarded handler the handler answers its own refusal.
     *
     * @throws Exception never
     */
    @Test
    void refusalsAreAnsweredOrLeftToTheHandler() throws Exception {
        FeatureAccessFilter guarded = filter(handler("delete"));
        when(guarded.permissionService.decide("mcurie", Feature.ACCOUNT, CrudOption.DELETE))
                .thenReturn(decision(false, false, null));
        ContainerRequestContext request = request("POST", "admin/accounts/delete", "id=12", null);
        guarded.filter(request);
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        assertEquals(403, response.getValue().getStatus());
        assertEquals("denied", response.getValue().getEntity());
        verify(guarded.auditService).record("mcurie", Feature.ACCOUNT, CrudOption.DELETE,
                "POST /admin/accounts/delete", "12", AdminAuditEntry.Outcome.DENIED, "Droit refusé", null);
        FeatureAccessFilter self = filter(handler("selfGuarded"));
        when(self.permissionService.decide("mcurie", Feature.PROFILE, CrudOption.DELETE))
                .thenReturn(decision(false, false, null));
        ContainerRequestContext own = request("POST", "/admin/profiles/delete", "id=3", null);
        self.filter(own);
        verify(own, never()).abortWith(any());
        assertEquals(CrudOption.DELETE, access(own).option);
    }

    /**
     * A screen under validation is shown without asking, and a modification
     * without validation goes through.
     *
     * @throws Exception never
     */
    @Test
    void grantedGesturesGoThrough() throws Exception {
        FeatureAccessFilter view = filter(handler("view"));
        when(view.permissionService.decide("mcurie", Feature.SETTINGS, CrudOption.VIEW))
                .thenReturn(decision(true, true, null));
        ContainerRequestContext screen = request("GET", "/admin/settings", null, null);
        view.filter(screen);
        verify(screen, never()).abortWith(any());
        assertEquals(CrudOption.VIEW, access(screen).option);
        FeatureAccessFilter save = filter(handler("save"));
        when(save.permissionService.decide("mcurie", Feature.ACCOUNT, CrudOption.UPDATE))
                .thenReturn(decision(true, false, null));
        ContainerRequestContext gesture = request("POST", "/admin/accounts/save", "id=12&loginName=a", null);
        save.filter(gesture);
        verify(gesture, never()).abortWith(any());
        verify(gesture).setEntityStream(any());
        assertEquals("12", access(gesture).target);
    }

    /**
     * A gesture under validation is held on the confirmation page, which
     * re-posts its fields but not the confirmation's; a confirmation
     * accepted lets it through and names the validator.
     *
     * @throws Exception never
     */
    @Test
    void aValidatedGestureIsHeldThenConfirmed() throws Exception {
        FeatureAccessFilter filter = filter(handler("delete"));
        when(filter.permissionService.decide("mcurie", Feature.ACCOUNT, CrudOption.DELETE))
                .thenReturn(decision(true, true, null));
        ContainerRequestContext held = request("POST", "/admin/accounts/delete", "id=12&tag=a&tag=b", "x=1");
        filter.filter(held);
        ArgumentCaptor<Response> page = ArgumentCaptor.forClass(Response.class);
        verify(held).abortWith(page.capture());
        assertEquals(200, page.getValue().getStatus());
        assertEquals("validation", page.getValue().getEntity());
        TemplateInstance instance = filter.validationPage.data("action", "");
        verify(filter.validationPage).data("action", "/admin/accounts/delete?x=1");
        verify(instance).data("thirdParty", false);
        verify(instance).data(eq("validatorProfile"), isNull());
        when(filter.permissionService.validate("mcurie", null, "pw", null)).thenReturn("mcurie");
        ContainerRequestContext confirmed = request("POST", "/admin/accounts/delete",
                "id=12&_validation_password=pw", null);
        filter.filter(confirmed);
        verify(confirmed, never()).abortWith(any());
        assertEquals("mcurie", access(confirmed).validator);
    }

    /**
     * A failed validation is logged and the page asked again with the reason,
     * for a self-confirmation and for a validating profile.
     *
     * @throws Exception never
     */
    @Test
    void aFailedValidationIsAskedAgain() throws Exception {
        FeatureAccessFilter self = filter(handler("delete"));
        when(self.permissionService.decide("mcurie", Feature.ACCOUNT, CrudOption.DELETE))
                .thenReturn(decision(true, true, null));
        ContainerRequestContext wrong = request("POST", "/admin/accounts/delete", "id=12&_validation_password=bad", null);
        self.filter(wrong);
        verify(wrong).abortWith(any());
        verify(self.validationPage.data("action", "")).data("error", "Validation refusée : identifiants incorrects.");
        verify(self.auditService).record("mcurie", Feature.ACCOUNT, CrudOption.DELETE, "POST /admin/accounts/delete",
                "12", AdminAuditEntry.Outcome.VALIDATION, "Validation refusée", null);
        FeatureAccessFilter third = filter(handler("delete"));
        when(third.permissionService.decide("mcurie", Feature.ACCOUNT, CrudOption.DELETE))
                .thenReturn(decision(true, true, 7L));
        Profile chief = new Profile();
        chief.name = "Chef de caisse";
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class,
                org.mockito.Answers.RETURNS_DEEP_STUBS);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            tx.when(() -> QuarkusTransaction.requiringNew().call(any()))
                    .thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(chief);
            ContainerRequestContext refused = request("POST", "/admin/accounts/delete",
                    "id=12&_validation_login=jdupont&_validation_password=pw", null);
            third.filter(refused);
            TemplateInstance instance = third.validationPage.data("action", "");
            verify(instance).data("error",
                    "Validation refusée : identifiants incorrects ou compte sans le profil valideur.");
            verify(instance).data("validatorProfile", "Chef de caisse");
            verify(instance).data("thirdParty", true);
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(null);
            assertNull(third.validatorProfileName(7L));
        }
    }

    /**
     * A file upload cannot carry a validation: it is refused and logged.
     *
     * @throws Exception never
     */
    @Test
    void aFileUploadUnderValidationIsRefused() throws Exception {
        FeatureAccessFilter filter = filter(handler("delete"));
        when(filter.permissionService.decide("mcurie", Feature.ACCOUNT, CrudOption.DELETE))
                .thenReturn(decision(true, true, null));
        ContainerRequestContext upload = request("POST", "/admin/accounts/delete", null, null);
        when(upload.getMediaType()).thenReturn(MediaType.MULTIPART_FORM_DATA_TYPE);
        filter.filter(upload);
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(upload).abortWith(response.capture());
        assertEquals(403, response.getValue().getStatus());
        verify(filter.auditService).record("mcurie", Feature.ACCOUNT, CrudOption.DELETE, "POST /admin/accounts/delete",
                null, AdminAuditEntry.Outcome.VALIDATION, "Validation impossible : envoi de fichier", null);
    }

    /**
     * The option a saving gesture exercises: created without key, modified
     * with a filled key, and with an entity, modified only when the record exists.
     *
     * @throws Exception never
     */
    @Test
    void theOptionIsResolvedFromTheForm() throws Exception {
        FeatureAccessFilter filter = filter(handler("view"));
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        assertEquals(CrudOption.DELETE, filter.resolveOption(handler("delete").getAnnotation(FeatureAccess.class), form));
        assertEquals(CrudOption.CREATE, filter.resolveOption(handler("create").getAnnotation(FeatureAccess.class), form));
        FeatureAccess save = handler("save").getAnnotation(FeatureAccess.class);
        assertEquals(CrudOption.CREATE, filter.resolveOption(save, form));
        form.putSingle("id", " ");
        assertEquals(CrudOption.CREATE, filter.resolveOption(save, form));
        form.putSingle("id", "12");
        assertEquals(CrudOption.UPDATE, filter.resolveOption(save, form));
        FeatureAccess byCode = handler("saveByCode").getAnnotation(FeatureAccess.class);
        form.putSingle("code", " USD ");
        when(filter.permissionService.recordExists(Currency.class, "code", "USD")).thenReturn(true, false);
        assertEquals(CrudOption.UPDATE, filter.resolveOption(byCode, form));
        assertEquals(CrudOption.CREATE, filter.resolveOption(byCode, form));
        FeatureAccess byProperty = handler("saveByProperty").getAnnotation(FeatureAccess.class);
        form.putSingle("iso", "GBP");
        when(filter.permissionService.recordExists(Currency.class, "code", "GBP")).thenReturn(true);
        assertEquals(CrudOption.UPDATE, filter.resolveOption(byProperty, form));
        assertEquals("GBP", filter.target(byProperty, form));
        assertNull(filter.target(handler("delete").getAnnotation(FeatureAccess.class), new MultivaluedHashMap<>()));
        MultivaluedMap<String, String> blank = new MultivaluedHashMap<>();
        blank.putSingle("id", " ");
        assertNull(filter.target(save, blank));
    }

    /**
     * Only a URL-encoded body is read, put back for the handler, empty pairs
     * skipped and names without value read as empty.
     *
     * @throws Exception never
     */
    @Test
    void theFormIsReadWithoutBeingConsumed() throws Exception {
        FeatureAccessFilter filter = filter(handler("view"));
        ContainerRequestContext none = request("GET", "/admin/settings", null, null);
        assertTrue(filter.readForm(none).isEmpty());
        ContainerRequestContext noStream = request("POST", "/admin/settings", null, null);
        when(noStream.getMediaType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        assertTrue(filter.readForm(noStream).isEmpty());
        ContainerRequestContext json = request("POST", "/admin/settings", null, null);
        when(json.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
        assertTrue(filter.readForm(json).isEmpty());
        ContainerRequestContext form = request("POST", "/admin/settings", "a=1&&flag&b=caf%C3%A9+noir", null);
        MultivaluedMap<String, String> read = filter.readForm(form);
        assertEquals("1", read.getFirst("a"));
        assertEquals("", read.getFirst("flag"));
        assertEquals("café noir", read.getFirst("b"));
        verify(form).setEntityStream(any());
    }

    /**
     * The response of a modifying gesture is logged with how the screen
     * answered it; a screen and a request the filter did not decide are not.
     */
    @Test
    void theOutcomeIsLogged() {
        FeatureAccessFilter filter = new FeatureAccessFilter();
        filter.auditService = mock(AdminAuditService.class);
        ContainerRequestContext undecided = mock(ContainerRequestContext.class);
        when(undecided.getProperty(FeatureAccessFilter.ACCESS)).thenReturn("other");
        filter.filter(undecided, mock(ContainerResponseContext.class));
        ContainerRequestContext screen = mock(ContainerRequestContext.class);
        when(screen.getProperty(FeatureAccessFilter.ACCESS)).thenReturn(new FeatureAccessFilter.Access("a",
                Feature.SETTINGS, CrudOption.VIEW, "GET /admin/settings", null));
        filter.filter(screen, mock(ContainerResponseContext.class));
        verifyNoInteractions(filter.auditService);
        FeatureAccessFilter.Access access = new FeatureAccessFilter.Access("a", Feature.SETTINGS, CrudOption.UPDATE,
                "POST /admin/settings", null);
        access.validator = "b";
        ContainerRequestContext gesture = mock(ContainerRequestContext.class);
        when(gesture.getProperty(FeatureAccessFilter.ACCESS)).thenReturn(access);
        ContainerResponseContext error = mock(ContainerResponseContext.class);
        when(error.getStatus()).thenReturn(500);
        filter.filter(gesture, error);
        verify(filter.auditService).record("a", Feature.SETTINGS, CrudOption.UPDATE, "POST /admin/settings", null,
                AdminAuditEntry.Outcome.REFUSED, "HTTP 500", "b");
        ContainerResponseContext page = mock(ContainerResponseContext.class);
        when(page.getStatus()).thenReturn(200);
        filter.filter(gesture, page);
        verify(filter.auditService).record("a", Feature.SETTINGS, CrudOption.UPDATE, "POST /admin/settings", null,
                AdminAuditEntry.Outcome.DONE, null, "b");
        ContainerResponseContext refused = mock(ContainerResponseContext.class);
        when(refused.getStatus()).thenReturn(303);
        when(refused.getLocation()).thenReturn(URI.create("/admin/settings?noticeOk=false&notice=Valeur+invalide"));
        filter.filter(gesture, refused);
        verify(filter.auditService).record("a", Feature.SETTINGS, CrudOption.UPDATE, "POST /admin/settings", null,
                AdminAuditEntry.Outcome.REFUSED, "Valeur invalide", "b");
        ContainerResponseContext done = mock(ContainerResponseContext.class);
        when(done.getStatus()).thenReturn(303);
        when(done.getLocation()).thenReturn(URI.create("/admin/settings?notice=Enregistr%C3%A9&noticeOk=true"));
        filter.filter(gesture, done);
        verify(filter.auditService).record("a", Feature.SETTINGS, CrudOption.UPDATE, "POST /admin/settings", null,
                AdminAuditEntry.Outcome.DONE, "Enregistré", "b");
        ContainerResponseContext bare = mock(ContainerResponseContext.class);
        when(bare.getStatus()).thenReturn(303);
        when(bare.getLocation()).thenReturn(URI.create("/admin/settings"));
        filter.filter(gesture, bare);
        verify(filter.auditService, org.mockito.Mockito.times(2)).record("a", Feature.SETTINGS, CrudOption.UPDATE,
                "POST /admin/settings", null, AdminAuditEntry.Outcome.DONE, null, "b");
    }

    /**
     * A query parameter is read by its exact name; a part without value or
     * without name is skipped.
     */
    @Test
    void queryParametersAreRead() {
        assertNull(FeatureAccessFilter.queryValue(null, "notice"));
        assertNull(FeatureAccessFilter.queryValue("flag&=x&noticeOk=true", "notice"));
        assertEquals("a b", FeatureAccessFilter.queryValue("noticeOk=true&notice=a+b", "notice"));
        FeatureAccessFilter.Field field = new FeatureAccessFilter.Field("n", "v");
        assertSame("n", field.name);
        assertEquals(List.of("v"), List.of(field.value));
    }
}
