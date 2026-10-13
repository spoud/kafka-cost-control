package io.spoud.kcc.aggregator.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class ClusterContextTest {

    @Test
    void parses_names() {
        assertThat(ClusterContext.parseNames(" lkc-a1b2 = confluent-prod,lkc-c3d4=confluent-dev, "))
                .containsExactly(
                        entry("lkc-a1b2", "confluent-prod"),
                        entry("lkc-c3d4", "confluent-dev"));
    }

    @Test
    void refuses_an_entry_that_is_not_a_pair() {
        assertThatThrownBy(() -> ClusterContext.parseNames("lkc-a1b2=confluent-prod,lkc-c3d4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'lkc-c3d4'");
        assertThatThrownBy(() -> ClusterContext.parseNames("=confluent-prod"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClusterContext.parseNames("lkc-a1b2=a=b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refuses_a_cluster_named_twice() {
        assertThatThrownBy(() -> ClusterContext.parseNames("lkc-a1b2=one,lkc-a1b2=two"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
    }
}
