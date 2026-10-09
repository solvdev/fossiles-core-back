package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1, phase 2, and both rollbacks on throwaway PostgreSQL. Each script is applied twice.
 * Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class LfReceivablesMigrationAcceptanceTest {

    private static final String PHASE1 = "scripts/migration-customer-account-lf-phase1.sql";
    private static final String PHASE2 = "scripts/migration-customer-account-lf-phase2.sql";
    private static final String ROLLBACK2 = "scripts/rollback-customer-account-lf-phase2.sql";
    private static final String ROLLBACK1 = "scripts/rollback-customer-account-lf-phase1.sql";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @MockitoBean
    SecurityUtil securityUtil;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void phaseScriptsAndRollbacksAreRerunnable() throws Exception {
        LfMigrationScripts.apply(jdbc, PHASE1);
        LfMigrationScripts.apply(jdbc, PHASE1);
        Long customerId = jdbc.queryForObject(
                "INSERT INTO customer (name, status, credit_days) VALUES ('Mig', 'ACTIVE', 0) RETURNING id",
                Long.class);
        insertEntry(customerId, "CHARGE_ADJUSTMENT");
        assertThatThrownBy(() -> insertEntry(customerId, "NOT_A_TYPE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_customer_account_entry_type");
        jdbc.update("DELETE FROM customer_account_entry");
        assertThat(indexCount("uq_cae_one_active_charge_per_order")).isZero();
        assertThat(indexCount("uq_cae_one_active_adjustment_per_shipment")).isEqualTo(1);

        LfMigrationScripts.apply(jdbc, PHASE2);
        LfMigrationScripts.apply(jdbc, PHASE2);
        assertThat(indexCount("uq_cae_one_active_charge_per_order")).isEqualTo(1);

        LfMigrationScripts.apply(jdbc, ROLLBACK2);
        LfMigrationScripts.apply(jdbc, ROLLBACK2);
        assertThat(indexCount("uq_cae_one_active_charge_per_order")).isZero();
        insertEntry(customerId, "CHARGE_ADJUSTMENT");
        jdbc.update("DELETE FROM customer_account_entry");

        LfMigrationScripts.apply(jdbc, ROLLBACK1);
        LfMigrationScripts.apply(jdbc, ROLLBACK1);
        assertThat(indexCount("uq_cae_one_active_adjustment_per_shipment")).isZero();
        assertThat(columnCount("customer", "credit_days")).isZero();
        assertThatThrownBy(() -> insertEntry(customerId, "CHARGE_ADJUSTMENT"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_customer_account_entry_type");
    }

    private void insertEntry(Long customerId, String entryType) {
        jdbc.update("""
                INSERT INTO customer_account_entry (customer_id, entry_type, entry_date, amount, status)
                VALUES (?, ?, DATE '2026-09-01', 5.00, 'ACTIVE')
                """, customerId, entryType);
    }

    private int indexCount(String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class, indexName);
        return count == null ? 0 : count;
    }

    private int columnCount(String table, String column) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return count == null ? 0 : count;
    }
}
