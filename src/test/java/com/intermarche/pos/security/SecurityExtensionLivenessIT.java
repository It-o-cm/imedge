package com.intermarche.pos.security;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Proves that {@code @RolesAllowed} on the CSV imports and the GraphQL API
 * is actually ENFORCED by the container, not merely present as decoration.
 * <p>
 * A plain Mockito unit test calls a resource method directly and never runs
 * through a CDI interceptor, so it cannot detect the security extension
 * being removed, nor the identity providers being broken — only a real
 * request through the running application can. This is deliberately the
 * ONE test of the lot 0 campaign that boots Quarkus, per
 * backoffice-campaign.md, section 11: an anonymous request to an ADMIN-only
 * CSV import and to a MANAGER-only GraphQL query must both be refused.
 */
@QuarkusTest
class SecurityExtensionLivenessIT {

    /**
     * An anonymous POST to the ADMIN-only product CSV import is refused,
     * proving {@code @RolesAllowed("ADMIN")} on {@code ProductCsvResource}
     * is live.
     */
    @Test
    void anonymousCsvImportIsRefused() {
        given()
                .contentType(ContentType.TEXT)
                .body("EAN;NAME\n")
                .when()
                .post("/products/import")
                .then()
                .log().ifValidationFails()
                .statusCode(401);
    }

    /**
     * An anonymous GraphQL query on a MANAGER-only field is refused,
     * proving {@code @RolesAllowed("MANAGER")} on
     * {@code ProductFamilyResource} is live.
     */
    @Test
    void anonymousGraphQLQueryIsRefused() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"query\":\"{ allProductFamilies { code } }\"}")
                .when()
                .post("/graphql")
                .then()
                .log().ifValidationFails()
                .statusCode(401);
    }
}
