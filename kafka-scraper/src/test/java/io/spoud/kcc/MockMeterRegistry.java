package io.spoud.kcc;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;

import static org.mockito.Mockito.mock;

abstract class MockMeterRegistry extends MeterRegistry {
    protected MockMeterRegistry() {
        super(mock(Clock.class));
    }
}
