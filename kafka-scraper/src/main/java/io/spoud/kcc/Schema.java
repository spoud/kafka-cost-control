package io.spoud.kcc;

public record Schema(
        String subject,
        int version
) {

    public String topic() {
        return subject.replaceAll("-value$", "").replaceAll("-key$", "");
    }

    public boolean isKey() {
        return subject.endsWith("-key");
    }

    public boolean isTopicSchema() {
        return subject.endsWith("-value") || subject.endsWith("-key");
    }
}
