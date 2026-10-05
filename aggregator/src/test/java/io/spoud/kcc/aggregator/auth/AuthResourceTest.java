package io.spoud.kcc.aggregator.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthResourceTest {

    @Test
    void redirectsOnlyWithinThisHost() {
        assertThat(AuthResource.safeRedirect("/costs?from=2026-10-01")).isEqualTo("/costs?from=2026-10-01");
        assertThat(AuthResource.safeRedirect("/kcc/pricing-rules")).isEqualTo("/kcc/pricing-rules");

        assertThat(AuthResource.safeRedirect(null)).isEqualTo("/");
        assertThat(AuthResource.safeRedirect("https://evil.example")).isEqualTo("/");
        assertThat(AuthResource.safeRedirect("//evil.example/x")).isEqualTo("/");
        assertThat(AuthResource.safeRedirect("/\\evil.example")).isEqualTo("/");
        assertThat(AuthResource.safeRedirect("costs")).isEqualTo("/");
        assertThat(AuthResource.safeRedirect("/x\r\nSet-Cookie: a=b")).isEqualTo("/");
    }
}
