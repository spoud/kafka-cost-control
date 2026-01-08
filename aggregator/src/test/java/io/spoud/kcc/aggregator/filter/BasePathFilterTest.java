package io.spoud.kcc.aggregator.filter;

import io.quarkus.test.InjectMock;
import io.quarkus.test.Mock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.config.SmallRyeConfig;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;

@QuarkusTest
class BasePathFilterTest {

    @Inject
    SmallRyeConfig smallRyeConfig;

    @Produces
    @ApplicationScoped
    @Mock
    CostControlConfigProperties costControlConfigProperties() {
        return smallRyeConfig.getConfigMapping(CostControlConfigProperties.class);
    }

    @InjectMock
    CostControlConfigProperties costControlConfigProperties;

    @Test
    void noBasePathConfigured_callGraphqlViaRoot() {
        testGraphqlEndpoint("", 200);
    }

    @Test
    void noBasePathConfigured_callRestViaRoot() {
        testRestEndpoint("", 200);
    }

    @Test
    void noBasePathConfigured_callGraphqlViaBasePath() {
        testGraphqlEndpoint("/some/context/path", 404);
    }

    @Test
    void noBasePathConfigured_callRestViaBasePath() {
        testGraphqlEndpoint("/some/context/path", 404);
    }

    @Test
    void basePathConfigured_callGraphqlViaBasePath() {
        Mockito.when(costControlConfigProperties.basePath()).thenReturn(Optional.of("/some/context/path/"));
        testGraphqlEndpoint("/some/context/path", 200);
    }

    @Test
    void basePathConfigured_callRestViaBasePath() {
        Mockito.when(costControlConfigProperties.basePath()).thenReturn(Optional.of("/some/context/path/"));
        testRestEndpoint("/some/context/path", 200);
    }

    @Test
    void basePathConfigured_callWithDifferentBasePath() {
        Mockito.when(costControlConfigProperties.basePath()).thenReturn(Optional.of("/some/context/path/"));
        testGraphqlEndpoint("/some/invalid/context/path", 404);
    }

    private void testGraphqlEndpoint(String path, int expectedStatusCode) {
        given()
                .contentType("application/json")
                .body("""
                         "query": {
                             "unused": "unused"
                         }
                        \s""")
                .when()
                .post(path + "/graphql")
                .then()
                .assertThat()
                .statusCode(expectedStatusCode);
    }

    private void testRestEndpoint(String path, int expectedStatusCode) {
        when()
                .get(path + "/olap/export/aggregated")
                .then()
                .assertThat()
                .statusCode(expectedStatusCode);
    }
}
