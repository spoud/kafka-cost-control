package io.spoud.kcc.aggregator.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/** The default mode: everything but health and metrics needs the admin user. */
@QuarkusTest
class BasicModeTest {

    static final String QUERY = """
            {"query": "{ __typename }"}
            """;

    @Test
    void queriesNeedASignIn() {
        RestAssured.given().contentType(ContentType.JSON).body(QUERY)
                .when().post("/graphql")
                .then().statusCode(401);
        RestAssured.when().get("/api/v1/pricing-rules")
                .then().statusCode(401);
        RestAssured.when().get("/graphql-ui")
                .then().statusCode(401);
    }

    @Test
    void theAdminGetsIn() {
        RestAssured.given().auth().preemptive().basic("admin", "admin")
                .contentType(ContentType.JSON).body(QUERY)
                .when().post("/graphql")
                .then().statusCode(200);
    }

    @Test
    void aWrongPasswordDoesNot() {
        RestAssured.given().auth().preemptive().basic("admin", "wrong")
                .contentType(ContentType.JSON).body(QUERY)
                .when().post("/graphql")
                .then().statusCode(401)
                .header("WWW-Authenticate", notNullValue());
    }

    @Test
    void theUiGetsNoChallengeThatWouldOpenTheBrowsersPasswordPrompt() {
        // while that prompt is open, every request to the host hangs
        RestAssured.given().auth().preemptive().basic("admin", "wrong")
                .header("X-Requested-With", "JavaScript")
                .when().get("/auth/me")
                .then().statusCode(401)
                .header("WWW-Authenticate", nullValue());
        RestAssured.given().header("X-Requested-With", "JavaScript")
                .contentType(ContentType.JSON).body(QUERY)
                .when().post("/graphql")
                .then().statusCode(401)
                .header("WWW-Authenticate", nullValue());
    }

    @Test
    void healthAndMetricsStayOpen() {
        RestAssured.when().get("/q/health/live").then().statusCode(not(equalTo(401)));
        RestAssured.when().get("/q/metrics").then().statusCode(200);
    }

    @Test
    void tellsTheUiWhichSignInToOffer() {
        RestAssured.when().get("/auth/me")
                .then().statusCode(200)
                .body("mode", equalTo("basic"))
                .body("authenticated", equalTo(false))
                .body("allowed", equalTo(false))
                .body("user", nullValue())
                .body("provider", nullValue())
                .body("domains", empty());
        RestAssured.given().auth().preemptive().basic("admin", "admin")
                .when().get("/auth/me")
                .then().statusCode(200)
                .body("authenticated", equalTo(true))
                .body("allowed", equalTo(true))
                .body("user", equalTo("admin"));
    }
}
