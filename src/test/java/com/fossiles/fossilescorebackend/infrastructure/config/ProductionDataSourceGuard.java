package com.fossiles.fossilescorebackend.infrastructure.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Locale;

/**
 * Fails before a DataSource is created unless tests are on in-memory H2 with Sentry off.
 * Registered from src/test/resources only, so production startup does not load it.
 */
public class ProductionDataSourceGuard implements EnvironmentPostProcessor {

    static final String H2_MEM_PREFIX = "jdbc:h2:mem";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        assertSafeToStart(
                environment.getProperty("spring.datasource.url"),
                environment.getProperty("sentry.dsn"));
    }

    static void assertSafeToStart(String url, String sentryDsn) {
        if (sentryDsn != null && !sentryDsn.isBlank()) {
            throw new IllegalStateException("Refusing to start: sentry.dsn must be empty.");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "Refusing to start: spring.datasource.url must be an in-memory H2 database.");
        }
        String normalized = url.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("rds.amazonaws.com") || normalized.contains("jdbc:postgresql")) {
            throw new IllegalStateException("Refusing to start: remote database URLs are not allowed.");
        }
        if (!normalized.startsWith(H2_MEM_PREFIX)) {
            throw new IllegalStateException(
                    "Refusing to start: spring.datasource.url must be an in-memory H2 database.");
        }
    }
}
