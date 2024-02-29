package io.spoud.kcc;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.Min;

@ConfigMapping(prefix = "cc")
public interface ScraperConfigProperties {

    @WithName("scrape.sr.interval-seconds")
    @Min(60)
    int srScrapeIntervalSeconds();

    @WithName("scrape.sr.enabled")
    @WithDefault("true")
    boolean srEnabled();

    @WithName("scrape.kafka.interval-seconds")
    @Min(60)
    int kafkaScrapeIntervalSeconds();

    @WithName("scrape.kafka.enabled")
    @WithDefault("true")
    boolean kafkaEnabled();
}
