package io.spoud.kcc.aggregator.graphql;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the GraphQL surface of the assistant.
 * <p>
 * The frontend's {@code graphql/schema.graphql} is a committed snapshot refreshed by
 * {@code npm run update-schema}, which needs a running aggregator — so in practice it is easy for
 * it to drift from the backend and only fail at runtime. These assertions fail the build instead,
 * the moment the shape changes.
 */
@QuarkusTest
class ChatSchemaTest {

    private String schema() {
        return RestAssured.given()
                .when().get("/graphql/schema.graphql")
                .then().statusCode(200)
                .extract().asString();
    }

    @Test
    void exposesTheChatMutationWithARequestObject() {
        // Not two separate arguments: JAX-RS allows only one body parameter, and this resource is
        // dual-annotated as REST. If someone "simplifies" this back to (sessionId, message) the
        // whole application fails to boot, so pin it here.
        assertThat(schema()).contains("chat(request: ChatRequestInput): ChatAnswer!");
    }

    @Test
    void exposesTheChatRequestInputFields() {
        assertThat(schema())
                .contains("input ChatRequestInput")
                .contains("sessionId: String!")
                .contains("message: String!");
    }

    @Test
    void exposesTheChatAnswerFields() {
        String schema = schema();
        assertThat(schema).contains("type ChatAnswer");
        // The UI renders each of these; a silent rename would break it at runtime only.
        // `rows` and `columns` carry the private-mode result table.
        assertThat(schema.substring(schema.indexOf("type ChatAnswer")))
                .containsSubsequence(
                        "columns: [String!]!",
                        "complete: Boolean!",
                        "error: String",
                        "generatedSql: [String!]!",
                        "rows: [[String]!]!",
                        "text: String!",
                        "truncated: Boolean!");
    }

    @Test
    void exposesTheClearChatMutation() {
        assertThat(schema()).contains("clearChat(sessionId: String!): Boolean!");
    }
}
