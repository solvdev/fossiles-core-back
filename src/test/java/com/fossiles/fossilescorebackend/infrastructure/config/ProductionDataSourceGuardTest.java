package com.fossiles.fossilescorebackend.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionDataSourceGuardTest {

    private final ProductionDataSourceGuard guard = new ProductionDataSourceGuard();

    @Test
    void allowsInMemoryH2WithEmptySentryDsn() {
        assertThatCode(() -> guard.postProcessEnvironment(
                environment("jdbc:h2:mem:fossiles_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", ""),
                null))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsInMemoryH2WhenSentryDsnIsMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:h2:mem:suite");
        assertThatCode(() -> guard.postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingUrl() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment(null, ""), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in-memory H2");
    }

    @Test
    void rejectsFileAndTcpH2() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment("jdbc:h2:file:./local", ""), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in-memory H2");
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment("jdbc:h2:tcp://localhost/db", ""), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in-memory H2");
    }

    @Test
    void rejectsPostgresqlUrl() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(
                environment("jdbc:postgresql://127.0.0.1:5432/local", ""),
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remote database");
    }

    @Test
    void rejectsAmazonRdsHost() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(
                environment("jdbc:h2:mem:hidden;host=example.rds.amazonaws.com", ""),
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remote database");
    }

    @Test
    void rejectsNonEmptySentryDsn() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(
                environment("jdbc:h2:mem:fossiles_test", "https://example.invalid/1"),
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sentry.dsn");
    }

    private static MockEnvironment environment(String url, String sentryDsn) {
        MockEnvironment environment = new MockEnvironment();
        if (url != null) {
            environment.setProperty("spring.datasource.url", url);
        }
        if (sentryDsn != null) {
            environment.setProperty("sentry.dsn", sentryDsn);
        }
        return environment;
    }
}
