package io.spoud.kcc.aggregator.ai;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The UI hides the assistant based on this status, so it has to agree with what actually happens
 * when a question is asked. If the two drift, the feature is either offered when it cannot work or
 * hidden when it can.
 */
@QuarkusTest
class AssistantStatusTest {

    @Inject
    ChatService chatService;

    @Test
    void reportsUnavailableWhenTheAssistantIsDisabled() {
        // cc.ai.enabled is false in the test profile.
        var status = chatService.status();

        assertThat(status.available()).isFalse();
        assertThat(status.reason()).contains("disabled");
    }

    @Test
    void theStatusReasonIsExactlyWhatAskingWouldReturn() {
        // ask() delegates to status(), so a user who bypasses the hidden nav gets the same
        // explanation the UI would have shown - not a different or vaguer message.
        var status = chatService.status();
        var answer = chatService.ask(UUID.randomUUID().toString(), "anything at all");

        assertThat(answer.error()).isEqualTo(status.reason());
    }

    @Test
    void isReadableWithoutAuthenticationSoTheNavCanDecideEarly() {
        // Unlike the chat mutation itself, which is @Authenticated. This exposes only whether a
        // feature is switched on.
        RestAssured.given()
                .when().get("/api/v1/chat/status")
                .then().statusCode(200)
                .body("available", org.hamcrest.Matchers.is(false));
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void isAlsoReadableWhenAuthenticated() {
        RestAssured.given()
                .when().get("/api/v1/chat/status")
                .then().statusCode(200);
    }
}
