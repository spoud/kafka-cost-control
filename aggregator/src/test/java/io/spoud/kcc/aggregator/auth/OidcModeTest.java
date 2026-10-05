package io.spoud.kcc.aggregator.auth;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(OidcModeTest.OidcProfile.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class OidcModeTest {

    public static class OidcProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cc.auth.mode", "oidc",
                    "cc.auth.allowed-domains", "example.com",
                    "quarkus.oidc.auth-server-url", "${keycloak.url}/realms/quarkus",
                    "quarkus.oidc.client-id", "kcc",
                    "quarkus.oidc.credentials.secret", "a-secret-of-at-least-32-characters-long");
        }
    }

    @Test
    void aBrowserIsSentToTheProvider() {
        RestAssured.given().redirects().follow(false)
                .when().get("/auth/login?redirect=/costs")
                .then().statusCode(302)
                .header("Location", containsString("/realms/quarkus"))
                .header("Location", containsString("redirect_uri="))
                .header("Location", containsString("%2Fauth%2Fcallback"));
    }

    @Test
    void theUiIsToldToSignInInsteadOfBeingRedirected() {
        RestAssured.given().redirects().follow(false)
                .header("X-Requested-With", "JavaScript")
                .contentType(ContentType.JSON).body(BasicModeTest.QUERY)
                .when().post("/graphql")
                .then().statusCode(499);
    }

    @Test
    void scriptsStillSignInAsAdmin() {
        RestAssured.given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON).body(BasicModeTest.QUERY)
                .when().post("/graphql")
                .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "ana")
    @OidcSecurity(claims = {@Claim(key = "email", value = "ana@example.com"), @Claim(key = "email_verified", value = "true")})
    void anAllowedUserGetsIn() {
        RestAssured.given().contentType(ContentType.JSON).body(BasicModeTest.QUERY)
                .when().post("/graphql")
                .then().statusCode(200);
        RestAssured.when().get("/auth/me")
                .then().statusCode(200)
                .body("mode", equalTo("oidc"))
                .body("allowed", equalTo(true))
                .body("user", equalTo("ana@example.com"));
    }

    @Test
    @TestSecurity(user = "eve")
    @OidcSecurity(claims = {@Claim(key = "email", value = "eve@elsewhere.org"), @Claim(key = "email_verified", value = "true")})
    void anyOtherUserIsTurnedAway() {
        RestAssured.given().contentType(ContentType.JSON).body(BasicModeTest.QUERY)
                .when().post("/graphql")
                .then().statusCode(403);
        RestAssured.when().get("/auth/me")
                .then().statusCode(200)
                .body("authenticated", equalTo(true))
                .body("allowed", equalTo(false));
    }
}
