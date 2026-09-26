package com.ratelimiter.gateway.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public MeterBinder customMeters() {
        return registry -> {
            // These will just define the counters so they are available, but they will be dynamically tagged later
        };
    }
}
