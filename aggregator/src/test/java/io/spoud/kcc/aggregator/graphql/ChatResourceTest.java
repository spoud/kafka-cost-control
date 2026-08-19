package io.spoud.kcc.aggregator.graphql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards two things that are easy to lose silently.
 */
@QuarkusTest
class ChatResourceTest {

    private static final String QUESTION_BODY = """
            {"sessionId": "test-session", "message": "how much data do we have?"}
            """;

    @Test
    void requiresAuthentication() {
        // This project has no quarkus.http.auth.permission.* block and no deny-unannotated, so
        // dropping @Authenticated from ChatResource would silently make the assistant — and the
        // LLM spend behind it — world-accessible. Fail the build if that happens.
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(QUESTION_BODY)
                .when().post("/api/v1/chat")
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void reportsThatTheAssistantIsDisabledRatherThanFailing() {
        // cc.ai.enabled is false in the test profile. The assistant must say so explicitly:
        // every OLAP method returns empty when disabled, so a naive implementation would answer
        // "I found no data", which is a materially different and misleading statement.
        String body = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(QUESTION_BODY)
                .when().post("/api/v1/chat")
                .then().statusCode(200)
                .extract().asString();

        assertThat(body).contains("disabled");
        assertThat(body).doesNotContain("no data");
    }
}
