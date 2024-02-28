package io.spoud.kcc;

import io.spoud.kcc.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;


class SchemaTest {


    @Test
    @DisplayName("should extract topic name from subject")
    void shouldExtractTopicName() {
        var schema = new Schema("some-topic-name-value-key", 2);
        assertThat(schema.topic()).isEqualTo("some-topic-name-value");
    }
}
