package com.fossiles.fossilescorebackend.infrastructure.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.Locale;

/**
 * Runs while the environment is prepared, before any application context starts.
 * Tests may use an in-memory H2 database only, and must not carry a Sentry DSN.
 */
public class ProductionDataSourceGuard implements EnvironmentPostProcessor, Ordered {

    static final String H2_MEM_PREFIX = "jdbc:h2:mem";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        verify(environment);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    static void verify(Environment environment) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith(H2_MEM_PREFIX)) {
            throw new IllegalStateException(
                    "Refusing to start tests: spring.datasource.url must start with jdbc:h2:mem");
        }
        String dsn = environment.getProperty("sentry.dsn");
        if (dsn != null && !dsn.isBlank()) {
            throw new IllegalStateException("Refusing to start tests: sentry.dsn must be empty");
        }
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return;
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                String resolved = environment.getProperty(name);
                if (resolved != null && forbidden(resolved)) {
                    throw new IllegalStateException(
                            "Refusing to start tests: property " + name + " references a production database");
                }
            }
        }
    }

    private static boolean forbidden(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("rds.amazonaws.com")
                || lower.contains("jdbc:postgresql:")
                || lower.contains("postgresql://");
    }
}
