package io.spoud.kcc.aggregator.stream;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
class AppStartsTestIT {
    @Test
    void should_start_in_container() {
        // no assertion, just make sure the application is able to launch inside the container
    }
}
