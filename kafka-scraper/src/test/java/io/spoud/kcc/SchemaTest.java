package io.spoud.kcc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;


class SchemaTest {


    @Test
    @DisplayName("should extract topic name from subject")
    void shouldExtractTopicName() {
        var schema = new Schema("some-topic-name-value-key", 2);
        assertThat(schema.isTopicSchema()).isTrue();
        assertThat(schema.topic()).isEqualTo("some-topic-name-value");

        schema = new Schema("some-topic-name-value-value", 1);
        assertThat(schema.isTopicSchema()).isTrue();
        assertThat(schema.topic()).isEqualTo("some-topic-name-value");
    }
}
