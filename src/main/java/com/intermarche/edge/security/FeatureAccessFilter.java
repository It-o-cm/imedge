package com.intermarche.edge.security;

import com.intermarche.edge.domain.audit.AdminAuditEntry;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Profile;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.service.AdminAuditService;
import com.intermarche.edge.service.PermissionService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The access control of the whole back office (BO-01-04-01, -02, -03, -05,
 * -06): every handler declaring a {@link FeatureAccess} is reached only when
 * the administered profiles of the signed-in account grant its feature under
 * the option the gesture exercises.
 *
 * <p>Three things happen here, in this order:
 * <ol>
 *   <li>the option is resolved — a saving gesture naming an existing record
 *       modifies, otherwise it creates — and the decision asked of the
 *       {@link PermissionService}; a refusal answers 403 with the reason;</li>
 *   <li>when the grant asks for a validation, the gesture is held on a
 *       confirmation page that re-posts it: the actor's own password, or the
 *       login and password of an account holding the validating profile;</li>
 *   <li>every modifying gesture, and every refusal, is written to the
 *       administration log with how the screen answered it.</li>
 * </ol>
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 20)
public class FeatureAccessFilter implements ContainerRequestFilter, ContainerResponseFilter {

    /** The request property carrying the access decided to the response. */
    static final String ACCESS = FeatureAccessFilter.class.getName() + ".access";

    /** The posted field carrying the validator's login. */
    static final String VALIDATION_LOGIN = "_validation_login";

    /** The posted field carrying the confirming password. */
    static final String VALIDATION_PASSWORD = "_validation_password";

    /** The handler being reached. */
    @Context
    ResourceInfo resourceInfo;

    /** The signed-in identity. */
    @Inject
    SecurityIdentity identity;

    /** The administered profiles. */
    @Inject
    PermissionService permissionService;

    /** The administration log. */
    @Inject
    AdminAuditService auditService;

    /** The refusal page. */
    @Inject
    @Location("admin-denied")
    Template deniedPage;

    /** The confirmation page of a gesture under validation. */
    @Inject
    @Location("admin-validation")
    Template validationPage;

    /** What the filter decided for one request, carried to its response. */
    static final class Access {

        /** The signed-in login. */
        final String login;

        /** The feature reached. */
        final Feature feature;

        /** The option exercised. */
        final CrudOption option;

        /** The HTTP method and path. */
        final String path;

        /** The record named, or null. */
        final String target;

        /** The validating login, or null. */
        String validator;

        /**
         * Builds the access of one request.
         *
         * @param login the signed-in login
         * @param feature the feature reached
         * @param option the option exercised
         * @param path the HTTP method and path
         * @param target the record named, or null
         */
        Access(String login, Feature feature, CrudOption option, String path, String target) {
            this.login = login;
            this.feature = feature;
            this.option = option;
            this.path = path;
            this.target = target;
        }
    }

    /** One posted field re-posted by the confirmation page. */
    public static final class Field {

        /** The field name. */
        public final String name;

        /** The field value. */
        public final String value;

        /**
         * Builds a re-posted field.
         *
         * @param name the field name
         * @param value the field value
         */
        public Field(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /**
     * Decides the request before its handler runs.
     *
     * @param request the request
     * @throws IOException when the posted form cannot be read
     */
    @Override
    public void filter(ContainerRequestContext request) throws IOException {
        FeatureAccess declared = declared();
        if (declared == null || identity == null || identity.isAnonymous()) {
            return;
        }
        String login = identity.getPrincipal().getName();
        MultivaluedMap<String, String> form = readForm(request);
        CrudOption option = resolveOption(declared, form);
        Access access = new Access(login, declared.value(), option,
                request.getMethod() + " " + pathOf(request), target(declared, form));
        PermissionService.Decision decision = permissionService.decide(login, declared.value(), option);
        if (!decision.isAllowed()) {
            if (declared.selfGuarded()) {
                request.setProperty(ACCESS, access);
                return;
            }
            auditService.record(login, access.feature, option, access.path, access.target,
                    AdminAuditEntry.Outcome.DENIED, "Droit refusé", null);
            request.abortWith(denied(access, "Vos profils ne vous accordent pas ce droit sur ce point de vente."));
            return;
        }
        if (option != CrudOption.VIEW && decision.requiresValidation) {
            Response held = validate(request, form, decision, access);
            if (held != null) {
                request.abortWith(held);
                return;
            }
        }
        request.setProperty(ACCESS, access);
    }

    /**
     * Logs the modifying gesture once its handler answered.
     *
     * @param request the request
     * @param response the handler's response
     */
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        Object property = request.getProperty(ACCESS);
        if (!(property instanceof Access access) || access.option == CrudOption.VIEW) {
            return;
        }
        AdminAuditEntry.Outcome outcome = AdminAuditEntry.Outcome.DONE;
        String detail = null;
        if (response.getStatus() >= 400) {
            outcome = AdminAuditEntry.Outcome.REFUSED;
            detail = "HTTP " + response.getStatus();
        } else {
            URI location = response.getLocation();
            String query = location == null ? null : location.getRawQuery();
            detail = queryValue(query, "notice");
            if ("false".equals(queryValue(query, "noticeOk"))) {
                outcome = AdminAuditEntry.Outcome.REFUSED;
            }
        }
        auditService.record(access.login, access.feature, access.option, access.path, access.target,
                outcome, detail, access.validator);
    }

    /**
     * Holds a gesture under validation until it is confirmed.
     *
     * @param request the request
     * @param form the posted form
     * @param decision the decision carrying the validation asked
     * @param access the access being decided
     * @return the confirmation page, or null once the gesture is validated
     */
    Response validate(ContainerRequestContext request, MultivaluedMap<String, String> form,
                      PermissionService.Decision decision, Access access) {
        if (!isForm(request)) {
            auditService.record(access.login, access.feature, access.option, access.path, access.target,
                    AdminAuditEntry.Outcome.VALIDATION, "Validation impossible : envoi de fichier", null);
            return denied(access, "Ce geste demande une validation, impossible sur un envoi de fichier.");
        }
        String password = form.getFirst(VALIDATION_PASSWORD);
        if (password == null) {
            return confirmation(request, form, decision, access, null);
        }
        String validator = permissionService.validate(access.login, form.getFirst(VALIDATION_LOGIN),
                password, decision.validatingProfileId);
        if (validator == null) {
            auditService.record(access.login, access.feature, access.option, access.path, access.target,
                    AdminAuditEntry.Outcome.VALIDATION, "Validation refusée", null);
            return confirmation(request, form, decision, access, "Validation refusée : identifiants incorrects"
                    + (decision.validatingProfileId == null ? "." : " ou compte sans le profil valideur."));
        }
        access.validator = validator;
        return null;
    }

    /**
     * Renders the confirmation page of a held gesture.
     *
     * @param request the request
     * @param form the posted form
     * @param decision the decision carrying the validation asked
     * @param access the access being decided
     * @param error the message of a failed attempt, or null
     * @return the page, 200
     */
    Response confirmation(ContainerRequestContext request, MultivaluedMap<String, String> form,
                          PermissionService.Decision decision, Access access, String error) {
        List<Field> fields = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : form.entrySet()) {
            if (entry.getKey().startsWith("_validation_")) {
                continue;
            }
            for (String value : entry.getValue()) {
                fields.add(new Field(entry.getKey(), value));
            }
        }
        URI uri = request.getUriInfo().getRequestUri();
        String action = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String body = validationPage.data("action", action)
                .data("fields", fields)
                .data("feature", access.feature.getLabel())
                .data("option", access.option.getLabel())
                .data("validatorProfile", validatorProfileName(decision.validatingProfileId))
                .data("thirdParty", decision.validatingProfileId != null)
                .data("back", access.feature.getPath())
                .data("error", error)
                .render();
        return Response.ok(body, MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    /**
     * Renders the refusal page.
     *
     * @param access the access refused
     * @param reason the reason shown
     * @return the page, 403
     */
    Response denied(Access access, String reason) {
        String body = deniedPage.data("feature", access.feature.getLabel())
                .data("option", access.option.getLabel())
                .data("reason", reason)
                .render();
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .entity(body)
                .build();
    }

    /**
     * Names the validating profile for the confirmation page.
     *
     * @param profileId the profile, or null
     * @return its name, or null when none or unknown
     */
    String validatorProfileName(Long profileId) {
        if (profileId == null) {
            return null;
        }
        Profile profile = QuarkusTransaction.requiringNew().call(() -> Profile.<Profile>findById(profileId));
        return profile == null ? null : profile.name;
    }

    /**
     * Resolves the option a gesture exercises.
     *
     * @param declared the handler's declaration
     * @param form the posted form
     * @return the declared option, or UPDATE for a saving gesture naming an existing record
     */
    CrudOption resolveOption(FeatureAccess declared, MultivaluedMap<String, String> form) {
        if (declared.option() != CrudOption.CREATE || declared.updateKey().isEmpty()) {
            return declared.option();
        }
        String value = form.getFirst(declared.updateKey());
        if (value == null || value.isBlank()) {
            return CrudOption.CREATE;
        }
        if (declared.entity() == void.class) {
            return CrudOption.UPDATE;
        }
        String property = declared.entityKey().isEmpty() ? declared.updateKey() : declared.entityKey();
        return permissionService.recordExists(declared.entity(), property, value.trim())
                ? CrudOption.UPDATE : CrudOption.CREATE;
    }

    /**
     * Reads the record a gesture names, for the log.
     *
     * @param declared the handler's declaration
     * @param form the posted form
     * @return the declared key's value, else the posted id, else null
     */
    String target(FeatureAccess declared, MultivaluedMap<String, String> form) {
        String key = declared.updateKey().isEmpty() ? "id" : declared.updateKey();
        String value = form.getFirst(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Returns the declaration of the handler being reached.
     *
     * @return the declaration, or null when the handler declares none
     */
    FeatureAccess declared() {
        Method method = resourceInfo == null ? null : resourceInfo.getResourceMethod();
        return method == null ? null : method.getAnnotation(FeatureAccess.class);
    }

    /**
     * Reads a posted form without consuming it: the bytes are put back for the handler.
     *
     * @param request the request
     * @return the form fields, empty for another kind of body
     * @throws IOException when the body cannot be read
     */
    MultivaluedMap<String, String> readForm(ContainerRequestContext request) throws IOException {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        if (!isForm(request) || request.getEntityStream() == null) {
            return form;
        }
        byte[] body = request.getEntityStream().readAllBytes();
        request.setEntityStream(new ByteArrayInputStream(body));
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            form.add(name, value);
        }
        return form;
    }

    /**
     * Tells whether the request posts a URL-encoded form.
     *
     * @param request the request
     * @return true for an application/x-www-form-urlencoded body
     */
    boolean isForm(ContainerRequestContext request) {
        MediaType type = request.getMediaType();
        return type != null && type.isCompatible(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
    }

    /**
     * Reads the path of a request, with its leading slash.
     *
     * @param request the request
     * @return the path
     */
    private String pathOf(ContainerRequestContext request) {
        String raw = request.getUriInfo().getPath();
        return raw.startsWith("/") ? raw : "/" + raw;
    }

    /**
     * Reads one parameter of a raw query string.
     *
     * @param rawQuery the query, possibly null
     * @param name the parameter
     * @return its decoded value, or null when absent
     */
    static String queryValue(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(pair.substring(0, equals))) {
                return decode(pair.substring(equals + 1));
            }
        }
        return null;
    }

    /**
     * Decodes one URL-encoded part.
     *
     * @param part the encoded text
     * @return the decoded text
     */
    private static String decode(String part) {
        return URLDecoder.decode(part, StandardCharsets.UTF_8);
    }
}
