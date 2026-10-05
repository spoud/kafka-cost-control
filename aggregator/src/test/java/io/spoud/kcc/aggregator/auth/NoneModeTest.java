package io.spoud.kcc.aggregator.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/** Nothing is closed, mutations included: a proxy in front is expected to authenticate. */
@QuarkusTest
@TestProfile(NoneModeTest.NoneProfile.class)
class NoneModeTest {

    public static class NoneProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cc.auth.mode", "none");
        }
    }

    @Test
    void everythingIsOpen() {
        RestAssured.given().contentType(ContentType.JSON).body(BasicModeTest.QUERY)
                .when().post("/graphql")
                .then().statusCode(200);
        RestAssured.given().contentType(ContentType.JSON)
                .body("""
                        {"query": "mutation { reprocess(areYouSure: \\"no\\") }"}
                        """)
                .when().post("/graphql")
                .then().statusCode(200)
                .body("data.reprocess", containsString("Please write 'yes'"));
        RestAssured.when().get("/auth/me")
                .then().statusCode(200)
                .body("mode", equalTo("none"))
                .body("allowed", equalTo(true));
    }
}
