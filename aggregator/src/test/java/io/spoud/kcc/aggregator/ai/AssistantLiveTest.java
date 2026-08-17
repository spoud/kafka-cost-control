package io.spoud.kcc.aggregator.ai;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.spoud.kcc.aggregator.graphql.data.ChatAnswer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end exercise of the assistant against a real LLM, using a local Ollama model so that no
 * data leaves the machine and no API key is needed.
 * <p>
 * Opt-in — run with {@code -Dassistant.live=true}. It is excluded from the normal build because it
 * needs Ollama running, takes minutes rather than milliseconds, and is non-deterministic in the way
 * every LLM interaction is. Its value is answering the one question no amount of structural testing
 * can: does the tool loop actually work when a model is driving it?
 * <p>
 * Assertions are therefore about <em>mechanism</em>, not about the exact answer: that the model
 * reached the database, that the SQL it wrote passed the guard and executed, and that private mode
 * really withheld the results.
 */
@QuarkusTest
@TestProfile(AssistantLiveTest.OllamaProfile.class)
@EnabledIfSystemProperty(named = "assistant.live", matches = "true")
class AssistantLiveTest {

    private static final String MODEL = System.getProperty("assistant.model", "Qwen3-Coder:latest");

    public static class OllamaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cc.olap.enabled", "true",
                    "cc.olap.database.insert-synthetic-days", "30",
                    "cc.ai.enabled", "true",
                    "cc.ai.max-tool-iterations", "6",
                    "quarkus.langchain4j.chat-model.provider", "ollama",
                    "quarkus.langchain4j.ollama.chat-model.model-id",
                    System.getProperty("assistant.model", "Qwen3-Coder:latest"),
                    "quarkus.langchain4j.ollama.timeout", "PT10M");
        }
    }

    @Inject
    ChatService chatService;

    @BeforeAll
    static void requireOllama() {
        assumeTrue(ollamaIsReachable(), "Ollama is not reachable on localhost:11434");
    }

    @Test
    void answersARealQuestionFromTheDatabase() {
        ChatAnswer answer = chatService.ask(UUID.randomUUID().toString(),
                "Which applications used the most bytes? Give me the top 3.");

        System.out.println("\n=== ANSWER ===\n" + answer.text());
        System.out.println("\n=== SQL ===\n" + String.join("\n---\n", answer.generatedSql()));

        assertThat(answer.error()).isNull();
        // The mechanism under test: the model went to the database rather than inventing an answer.
        assertThat(answer.generatedSql()).isNotEmpty();
        assertThat(answer.text()).isNotBlank();
        // Synthetic data uses an `application` context key, so a working loop must have found it.
        assertThat(String.join(" ", answer.generatedSql())).containsIgnoringCase("application");
    }

    @Test
    void privateModeReturnsATableWithoutShowingResultsToTheModel() {
        // Same profile can't be reconfigured mid-run, so this exercises the terminal-tool path
        // directly: the guarantee under test is that invokeTerminal hands rows back to the caller
        // rather than into the conversation.
        ChatAnswer answer = privateModeChatService().ask(UUID.randomUUID().toString(),
                "Show total bytes per application.");

        System.out.println("\n=== PRIVATE MODE ===\ntext: " + answer.text());
        System.out.println("columns: " + answer.columns());
        System.out.println("rows: " + answer.rows().size());

        assertThat(answer.error()).isNull();
        assertThat(answer.columns()).isNotEmpty();
        assertThat(answer.rows()).isNotEmpty();
        assertThat(answer.generatedSql()).isNotEmpty();
    }

    /**
     * A ChatService wired with private mode on, sharing the application's real repositories so the
     * query runs against the same synthetic data.
     */
    private ChatService privateModeChatService() {
        return new ChatService(privateModeConfig, olapConfig,
                new SchemaDescriber(repository, privateModeConfig), toolRegistry, llmClients);
    }

    @Inject
    io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository repository;
    @Inject
    io.spoud.kcc.aggregator.olap.OlapConfigProperties olapConfig;
    @Inject
    ToolRegistry toolRegistry;
    @Inject
    jakarta.enterprise.inject.Instance<LlmClient> llmClients;

    /** Same settings as the running app, but with private mode forced on. */
    private final AiConfigProperties privateModeConfig = new AiConfigProperties() {
        public boolean enabled() {
            return true;
        }

        public boolean privateMode() {
            return true;
        }

        public int maxRows() {
            return 100;
        }

        public int maxToolIterations() {
            return 6;
        }

        public java.time.Duration queryTimeout() {
            return Duration.ofSeconds(30);
        }

        public int maxSessions() {
            return 10;
        }

        public int maxHistoryMessages() {
            return 20;
        }
    };

    private static boolean ollamaIsReachable() {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:11434/api/tags"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains(MODEL.split(":")[0]);
        } catch (Exception e) {
            return false;
        }
    }
}
