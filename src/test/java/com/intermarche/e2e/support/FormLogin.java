package com.intermarche.e2e.support;

import io.restassured.RestAssured;

/**
 * Form-authentication helper for the back-office screen surfaces
 * ({@code /admin/**}, {@code /dashboard}), shared by every {@code Group<X>IT}
 * that drives a screen.
 *
 * <p>Those surfaces are NOT behind HTTP Basic (only {@code /feeds/import/*} and
 * {@code /graphql} are): they carry {@code @RolesAllowed} and rely on Quarkus
 * form authentication. A machine test therefore signs in the way a browser does
 * — it posts the credentials to {@code /j_security_check} and reuses the
 * {@code quarkus-credential} cookie the server hands back — rather than sending
 * a Basic header.
 *
 * <p>The credentials come from the calling class's test-profile configuration,
 * never hard-coded here.
 */
public final class FormLogin {

    /** The session cookie name Quarkus form authentication issues. */
    public static final String COOKIE = "quarkus-credential";

    /** Non-instantiable helper. */
    private FormLogin() {
    }

    /**
     * Signs in through the form login and returns the session cookie, or null
     * when the credentials are refused (no cookie issued).
     *
     * @param username the login name
     * @param password the back-office password
     * @return the {@code quarkus-credential} cookie value, or null on refusal
     */
    public static String cookie(String username, String password) {
        return RestAssured.given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", username)
                .formParam("j_password", password)
                .redirects().follow(false)
                .when().post("/j_security_check")
                .then().extract().cookie(COOKIE);
    }
}
