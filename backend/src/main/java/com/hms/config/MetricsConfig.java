package com.hms.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Observability wiring for Micrometer/Prometheus.
 *
 * <p>Tags every metric with the application name and the active environment so a single
 * Prometheus/Grafana can distinguish staging from production (and future per-tenant views).
 * The auto-configured JVM, HTTP, HikariCP, Tomcat and Flyway meters are all stamped too.
 *
 * <p>Enables {@link io.micrometer.core.annotation.Timed @Timed} on methods for opt-in custom
 * timers without touching business logic. See docs/monitoring/OBSERVABILITY.md.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonMetricTags(
            Environment env,
            @Value("${spring.application.name:hospital-management-system}") String applicationName) {
        String[] profiles = env.getActiveProfiles();
        String environment = profiles.length > 0 ? String.join(",", profiles) : "default";
        return registry -> registry.config().commonTags(
                "application", applicationName,
                "environment", environment);
    }

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
