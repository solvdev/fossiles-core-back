package com.fossiles.fossilescorebackend.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionDataSourceGuardTest {

    @Test
    void acceptsInMemoryH2AndEmptySentryDsn() {
        assertThatCode(() -> ProductionDataSourceGuard.verify(environment(
                "spring.datasource.url", "jdbc:h2:mem:fossiles;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sentry.dsn", ""
        ))).doesNotThrowAnyException();
    }

    @Test
    void acceptsMissingSentryDsn() {
        assertThatCode(() -> ProductionDataSourceGuard.verify(environment(
                "spring.datasource.url", "jdbc:h2:mem:fossiles"
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingDatasourceUrl() {
        assertThatThrownBy(() -> ProductionDataSourceGuard.verify(environment("sentry.dsn", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc:h2:mem");
    }

    @Test
    void rejectsFileH2() {
        assertThatThrownBy(() -> ProductionDataSourceGuard.verify(environment(
                "spring.datasource.url", "jdbc:h2:file:./data"
        ))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPostgresqlUrl() {
        assertThatThrownBy(() -> ProductionDataSourceGuard.verify(environment(
                "spring.datasource.url", "jdbc:postgresql://127.0.0.1/db"
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc:h2:mem");
    }

    @Test
    void rejectsPostgresqlUrlInAnotherProperty() {
        assertThatThrownBy(() -> ProductionDataSourceGuard.verify(environment(
                "spring.datasource.url", "jdbc:h2:mem:fossiles",
                "app.extra", "postgresql://127.0.0.1/db"
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.extra");
    }

    @Test
    void rejectsAmazonRdsHost() {
        assertThatThrownBy(() -> ProductionDataSourceGuard.verify(environment(
                "spring.datasource.url", "jdbc:h2:mem:fossiles",
                "app.note", "example.rds.amazonaws.com"
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.note");
    }

    @Test
    void rejectsNonEmptySentryDsn() {
        assertThatThrownBy(() -> ProductionDataSourceGuard.verify(environment(
                "spring.datasource.url", "jdbc:h2:mem:fossiles",
                "sentry.dsn", "set"
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sentry.dsn");
    }

    private static ConfigurableEnvironment environment(String... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return new AbstractEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources sources) {
                sources.addLast(new MapPropertySource("test", values));
            }
        };
    }
}
