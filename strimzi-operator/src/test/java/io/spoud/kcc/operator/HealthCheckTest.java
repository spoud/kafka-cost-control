package io.spoud.kcc.operator;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No reconcilers are registered anymore (see KafkaUserReconciler/KafkaTopicReconciler), so
 * quarkus-operator-sdk's own health check would otherwise report DOWN permanently (it considers the
 * operator "not started" when there are no registered controllers), failing readiness/startup probes.
 * Verifies that check is excluded (quarkus.arc.exclude-types in application.yaml) and readiness is UP.
 */
@WithKubernetesTestServer(port = 64446)
@TestProfile(DefaultTestProfile.class)
@QuarkusTestResource(KafkaCompanionResource.class)
@QuarkusTest
class HealthCheckTest {

    @TestHTTPResource("/q/health/ready")
    URI readinessUrl;

    @Test
    void readinessIsUpWithoutOperatorSdkCheck() throws Exception {
        var response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(readinessUrl).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"status\": \"UP\"")
                .doesNotContain("Quarkus Operator SDK health check");
    }
}
