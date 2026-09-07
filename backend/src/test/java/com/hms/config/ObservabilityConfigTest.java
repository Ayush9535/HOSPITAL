package com.hms.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the observability wiring: Micrometer is present, a Prometheus registry is auto-configured,
 * and every meter is stamped with the common {@code application} / {@code environment} tags.
 */
@SpringBootTest
@ActiveProfiles("test")
class ObservabilityConfigTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ApplicationContext context;

    @Test
    void meterRegistryIsAvailable() {
        assertThat(meterRegistry).isNotNull();
    }

    @Test
    void prometheusRegistryIsAutoConfigured() {
        boolean hasPrometheus = Arrays.stream(context.getBeanDefinitionNames())
                .map(context::getType)
                .filter(t -> t != null)
                .anyMatch(t -> t.getName().toLowerCase().contains("prometheus"));
        assertThat(hasPrometheus)
                .as("a Prometheus meter registry bean should be auto-configured")
                .isTrue();
    }

    @Test
    void metersCarryCommonTags() {
        meterRegistry.counter("hms.test.counter").increment();
        var counter = meterRegistry.find("hms.test.counter").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.getId().getTag("application")).isNotBlank();
        assertThat(counter.getId().getTag("environment")).isNotBlank();
    }
}
