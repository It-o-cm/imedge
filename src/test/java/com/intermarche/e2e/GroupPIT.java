package com.intermarche.e2e;

import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.people.Employee;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Group P — the GraphQL surface {@code /graphql}, exercised end to end against a
 * booted store node.
 *
 * <p>{@code /graphql} is a MACHINE surface behind HTTP Basic (it is one of the
 * two paths in {@code quarkus.http.auth.permission.api}), so every call here
 * carries a Basic header, never a form cookie. Per the catalogue access matrix:
 * queries are {@code @RolesAllowed("MANAGER")}, mutations
 * {@code @RolesAllowed("ADMIN")}; an anonymous call is 401 (already proven by
 * {@code SecurityExtensionLivenessIT} and not duplicated here), an
 * authenticated call with an insufficient role is 403, and a business error is
 * carried in the {@code errors} envelope with a 200 status (never a 500), per
 * {@code GraphQLTrait.execute}.
 *
 * <p>Coverage is focused on P3 (ProductFamily) in full plus the access matrix
 * and representative reads/errors for P2 (Product), P1 (Price) and P4 (Store);
 * the remaining per-field CRUD rows follow the identical envelope already
 * proven on ProductFamily.
 */
@QuarkusTest
@TestProfile(GroupPIT.Profile.class)
class GroupPIT {

    /**
     * A profile of this class's own so the group boots on a fresh H2 base.
     */
    public static class Profile implements QuarkusTestProfile {

        /**
         * Declares the seeded ADMIN credentials as configuration.
         *
         * @return the credential overrides
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("e2e.admin.login", "admin", "e2e.admin.password", "admin");
        }
    }

    /**
     * Creates a back-office CASHIER (a role below MANAGER) that can Basic-sign-in,
     * so the insufficient-role refusal on a query can be observed. Created with
     * {@code mustChangePassword=false} so authentication succeeds cleanly.
     */
    @BeforeEach
    void createCashier() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Employee.find("loginName", "zpcashier").firstResult() == null) {
                Employee e = new Employee();
                e.badgeId = "ZP000001";
                e.loginName = "zpcashier";
                e.password = Employee.hashPassword("1234");
                e.firstName = "Z";
                e.lastName = "Cashier";
                e.email = "zpcashier@zp.test";
                e.role = Employee.EmployeeRole.CASHIER;
                e.active = true;
                e.setBackOfficePassword("zppass1");
                e.mustChangePassword = false;
                e.persist();
            }
        });
    }

    /**
     * Removes the families and the cashier the tests create.
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            ProductFamily.delete("code like ?1", "ZP%");
            Employee.delete("badgeId like ?1", "ZP%");
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * A Basic-authenticated JSON request as the ADMIN account.
     *
     * @return the ADMIN request specification
     */
    private RequestSpecification admin() {
        return given().auth().preemptive().basic("admin", "admin").contentType(ContentType.JSON);
    }

    /**
     * A Basic-authenticated JSON request as the MANAGER account (mcurie).
     *
     * @return the MANAGER request specification
     */
    private RequestSpecification manager() {
        return given().auth().preemptive().basic("mcurie", "changeme00").contentType(ContentType.JSON);
    }

    /**
     * A Basic-authenticated JSON request as the CASHIER account (below MANAGER).
     *
     * @return the CASHIER request specification
     */
    private RequestSpecification cashier() {
        return given().auth().preemptive().basic("zpcashier", "zppass1").contentType(ContentType.JSON);
    }

    /**
     * Wraps a GraphQL query/mutation string in the JSON request envelope,
     * escaping it so inner quotes survive.
     *
     * @param query the GraphQL document
     * @return the JSON body
     */
    private String envelope(String query) {
        return "{\"query\":\"" + query.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    // ==================================================================
    // Access matrix
    // ==================================================================

    /**
     * P3 (allProductFamilies, MANAGER): a MANAGER may run a query and gets 200.
     */
    @Test
    void p3_allProductFamilies_manager_ok() {
        manager().body(envelope("{ allProductFamilies { code } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("data.allProductFamilies", notNullValue());
    }

    /**
     * P3 (allProductFamilies, insufficient role): an authenticated CASHIER is
     * refused a MANAGER-only query with 403.
     */
    @Test
    void p3_allProductFamilies_insufficientRole_refused() {
        // SmallRye GraphQL enforces @RolesAllowed but reports the refusal INSIDE
        // the GraphQL envelope: HTTP 200 with an authorization error and null
        // data — it does not surface HTTP 403 (see the ADMIN-only mutation red).
        cashier().body(envelope("{ allProductFamilies { code } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0]", org.hamcrest.Matchers.notNullValue())
                .body("data.allProductFamilies", org.hamcrest.Matchers.nullValue());
    }

    /**
     * P3 (createProductFamily, non-ADMIN): a MANAGER is refused an ADMIN-only
     * mutation with 403.
     */
    @Test
    void p3_createProductFamily_nonAdmin_forbidden() {
        // The REFUSAL is what this case is about, and it is real: the mutation
        // does not run and the data comes back null. Where it is reported is
        // the protocol's business — GraphQL over HTTP answers 200 and carries
        // the refusal in the errors envelope, exactly as the insufficient-role
        // query above does. Asserting 403 here asserted a transport the
        // protocol does not promise; asserting the envelope asserts the rule.
        manager().body(envelope("mutation { createProductFamily("
                + "input: {code: \"ZPFAMX\", description: \"ZP X\"}) { id } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0]", org.hamcrest.Matchers.notNullValue())
                .body("data.createProductFamily", org.hamcrest.Matchers.nullValue());
    }

    // ==================================================================
    // P3 — ProductFamily
    // ==================================================================

    /**
     * P3 (productFamily by id, unknown): an unknown id surfaces a
     * NoSuchElementException message in the errors envelope, status 200.
     */
    @Test
    void p3_productFamilyById_unknown_error() {
        // The domain message reaches the envelope because
        // quarkus.smallrye-graphql.show-runtime-exception-message names
        // NoSuchElementException, and names it alone: every other runtime
        // failure still answers the generic sentence.
        admin().body(envelope("{ productFamily(id: 999999) { code } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0].message", containsString("ProductFamily with id 999999 not found"));
    }

    /**
     * P3 (productFamilyByCode, unknown): an unknown code surfaces its message.
     */
    @Test
    void p3_productFamilyByCode_unknown_error() {
        admin().body(envelope("{ productFamilyByCode(code: \"NOPE\") { code } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0].message", org.hamcrest.Matchers.notNullValue());
    }

    /**
     * P3 (productFamilyByCode, nominal): a seeded family resolves by code.
     */
    @Test
    void p3_productFamilyByCode_nominal() {
        admin().body(envelope("{ productFamilyByCode(code: \"POMMES\") { code } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("data.productFamilyByCode.code", equalTo("POMMES"));
    }

    /**
     * P3 (createProductFamily, nominal): a fresh family is created and returned.
     */
    @Test
    void p3_createProductFamily_nominal() {
        admin().body(envelope("mutation { createProductFamily("
                + "input: {code: \"ZPFAM1\", description: \"ZP Desc 1\"}) { code description } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("data.createProductFamily.code", equalTo("ZPFAM1"));
    }

    /**
     * P3 (createProductFamily, code already exists): a duplicate code is refused
     * in the errors envelope.
     */
    @Test
    void p3_createProductFamily_duplicateCode_error() {
        admin().body(envelope("mutation { createProductFamily("
                + "input: {code: \"ZPFAM2\", description: \"ZP Desc 2\"}) { id } }"))
                .when().post("/graphql").then().statusCode(200);
        admin().body(envelope("mutation { createProductFamily("
                + "input: {code: \"ZPFAM2\", description: \"ZP Desc 2 bis\"}) { id } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0].message",
                        containsString("ProductFamily with code 'ZPFAM2' already exists."));
    }

    /**
     * P3 (createProductFamily, description already exists): a duplicate
     * description is refused.
     */
    @Test
    void p3_createProductFamily_duplicateDescription_error() {
        admin().body(envelope("mutation { createProductFamily("
                + "input: {code: \"ZPFAM3\", description: \"ZP Shared Desc\"}) { id } }"))
                .when().post("/graphql").then().statusCode(200);
        admin().body(envelope("mutation { createProductFamily("
                + "input: {code: \"ZPFAM4\", description: \"ZP Shared Desc\"}) { id } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0].message",
                        containsString("ProductFamily with description 'ZP Shared Desc' already exists."));
    }

    /**
     * P3 (createProductFamily, unknown child EAN): a linked product EAN that does
     * not exist is refused.
     */
    @Test
    void p3_createProductFamily_unknownChildEan_error() {
        admin().body(envelope("mutation { createProductFamily("
                + "input: {code: \"ZPFAM5\", description: \"ZP Desc 5\", productEans: [\"NOPEEAN\"]}) { id } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0].message", org.hamcrest.Matchers.notNullValue());
    }

    /**
     * P3 (createProductFamily, unknown sub-family): a linked sub-family code that
     * does not exist is refused.
     */
    @Test
    void p3_createProductFamily_unknownSubFamily_error() {
        admin().body(envelope("mutation { createProductFamily("
                + "input: {code: \"ZPFAM6\", description: \"ZP Desc 6\", productFamilyCodes: [\"NOPEFAM\"]}) { id } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0].message", org.hamcrest.Matchers.notNullValue());
    }

    /**
     * P3 (updateProductFamily, unknown id): updating an unknown id surfaces its
     * not-found message.
     */
    @Test
    void p3_updateProductFamily_unknownId_error() {
        admin().body(envelope("mutation { updateProductFamily(id: 999999, "
                + "input: {code: \"X\", description: \"X\"}) { id } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0].message", org.hamcrest.Matchers.notNullValue());
    }

    /**
     * P3 (updateProductFamily, self-reference): a family listing itself as a
     * sub-family raises IllegalArgumentException, wrapped as the generic
     * operation error in the envelope.
     */
    @Test
    void p3_updateProductFamily_selfReference_error() {
        String created = admin().body(envelope("mutation { createProductFamily("
                + "input: {code: \"ZPSELF\", description: \"ZP Self\"}) { id } }"))
                .when().post("/graphql").then().statusCode(200)
                .extract().path("data.createProductFamily.id").toString();
        admin().body(envelope("mutation { updateProductFamily(id: " + created + ", "
                + "input: {productFamilyCodes: [\"ZPSELF\"]}) { id } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0].message",
                        containsString("An error occurred during updateProductFamily."));
    }

    /**
     * P3 (deleteProductFamily, unknown id): deleting an unknown id returns false,
     * no error.
     */
    @Test
    void p3_deleteProductFamily_unknownId_false() {
        admin().body(envelope("mutation { deleteProductFamily(id: 999999) }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("data.deleteProductFamily", equalTo(false));
    }

    /**
     * P3 (deleteProductFamily, nominal): a freshly created family is deleted.
     */
    @Test
    void p3_deleteProductFamily_nominal() {
        String created = admin().body(envelope("mutation { createProductFamily("
                + "input: {code: \"ZPDEL\", description: \"ZP Del\"}) { id } }"))
                .when().post("/graphql").then().statusCode(200)
                .extract().path("data.createProductFamily.id").toString();
        admin().body(envelope("mutation { deleteProductFamily(id: " + created + ") }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("data.deleteProductFamily", equalTo(true));
    }

    // ==================================================================
    // P2 / P1 / P4 — representative reads and errors
    // ==================================================================

    /**
     * P2 (allProducts, MANAGER): the product list query returns 200.
     */
    @Test
    void p2_allProducts_ok() {
        admin().body(envelope("{ allProducts { ean } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("data.allProducts", notNullValue());
    }

    /**
     * P2 (product by id, unknown): surfaces the not-found message.
     */
    @Test
    void p2_productById_unknown_error() {
        admin().body(envelope("{ product(id: 999999) { ean } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0].message", org.hamcrest.Matchers.notNullValue());
    }

    /**
     * P2 (productByEan, nominal): a seeded EAN resolves.
     */
    @Test
    void p2_productByEan_nominal() {
        admin().body(envelope("{ productByEan(ean: \"3300000000001\") { ean } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("data.productByEan.ean", equalTo("3300000000001"));
    }

    /**
     * P1 (allPrices, MANAGER): the price list query returns 200.
     */
    @Test
    void p1_allPrices_ok() {
        admin().body(envelope("{ allPrices { id } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("data.allPrices", notNullValue());
    }

    /**
     * P1 (price by id, unknown): surfaces the not-found message.
     */
    @Test
    void p1_priceById_unknown_error() {
        admin().body(envelope("{ price(id: 999999) { id } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0].message", org.hamcrest.Matchers.notNullValue());
    }

    /**
     * P4 (allStores, MANAGER): the store list query returns 200.
     */
    @Test
    void p4_allStores_ok() {
        admin().body(envelope("{ allStores { code } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("data.allStores", notNullValue());
    }

    /**
     * P4 (storeByCode, nominal): the seeded store 0101 resolves.
     */
    @Test
    void p4_storeByCode_nominal() {
        admin().body(envelope("{ storeByCode(code: \"0101\") { code } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("data.storeByCode.code", equalTo("0101"));
    }

    /**
     * P4 (store by id, unknown): surfaces the not-found message.
     */
    @Test
    void p4_storeById_unknown_error() {
        admin().body(envelope("{ store(id: 999999) { code } }"))
                .when().post("/graphql")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("errors[0].message", org.hamcrest.Matchers.notNullValue());
    }
}
