package io.spoud.kcc.aggregator.olap;

import lombok.Builder;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
@Builder
class FakeOlapConfig implements OlapConfigProperties {
    @Builder.Default
    private final boolean enabled = true;
    @Builder.Default
    private final String databaseUrl = "jdbc:duckdb:";
    @Builder.Default
    private final int databaseFlushIntervalSeconds = 10;
    @Builder.Default
    private final int databaseMaxBufferedRows = 10;
    @Builder.Default
    private final String databaseSeedDataPath = null;
    @Builder.Default
    private final Optional<Integer> totalMemoryLimitMb = Optional.ofNullable(1024);
    @Builder.Default
    private final int databaseMemoryLimitPercent = 50;

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String databaseUrl() {
        return databaseUrl;
    }

    @Override
    public int databaseFlushIntervalSeconds() {
        return databaseFlushIntervalSeconds;
    }

    @Override
    public int databaseMaxBufferedRows() {
        return databaseMaxBufferedRows;
    }

    @Override
    public Optional<Integer> totalMemoryLimitMb() {
        return totalMemoryLimitMb;
    }

    @Override
    public int databaseMemoryLimitPercent() {
        return databaseMemoryLimitPercent;
    }

    @Override
    public Optional<String> databaseSeedDataPath() {
        return Optional.ofNullable(databaseSeedDataPath);
    }

    @Override
    public Optional<Integer> insertSyntheticDays() {
        return Optional.empty();
    }
}
