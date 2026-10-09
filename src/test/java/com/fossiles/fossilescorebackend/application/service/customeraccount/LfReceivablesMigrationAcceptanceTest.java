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
import static org.assertj.core.api.Assertions.catchThrowable;

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

    /**
     * Phase 1 rollback must refuse to run while a CHARGE_ADJUSTMENT row exists.
     * It has to stop with a clear message, roll the transaction back, and leave
     * the row and the phase-1 type check in place.
     */
    @Test
    void phase1RollbackWithAdjustmentRowRollsBackCleanly() throws Exception {
        LfMigrationScripts.apply(jdbc, PHASE1);
        Long customerId = jdbc.queryForObject(
                "INSERT INTO customer (name, status, credit_days) VALUES ('Rollback', 'ACTIVE', 0) RETURNING id",
                Long.class);
        insertEntry(customerId, "CHARGE");
        insertEntry(customerId, "CHARGE_ADJUSTMENT");
        int rowsBefore = entryCount();

        Throwable thrown = catchThrowable(() -> LfMigrationScripts.apply(jdbc, ROLLBACK1));
        String message = messages(thrown);
        String check = constraintDefinition("chk_customer_account_entry_type");
        boolean clearStop = thrown != null
                && message.toUpperCase().contains("CHARGE_ADJUSTMENT")
                && !message.toLowerCase().contains("violat");
        boolean intact = rowsBefore == entryCount()
                && entryCount("CHARGE_ADJUSTMENT") == 1
                && check != null
                && check.contains("CHARGE_ADJUSTMENT")
                && indexCount("uq_cae_one_active_adjustment_per_shipment") == 1
                && columnCount("customer", "credit_days") == 1;

        if (!clearStop || !intact) {
            throw new AssertionError("BUG: phase 1 rollback with a CHARGE_ADJUSTMENT row must stop with a clear "
                    + "error (not a raw CHECK violation halfway through), roll back, and leave every row and "
                    + "chk_customer_account_entry_type in place. "
                    + "thrown=" + (thrown == null ? "none" : thrown.getClass().getName())
                    + " message=" + message.replace('\n', ' ')
                    + " rows=" + entryCount()
                    + " adjustments=" + entryCount("CHARGE_ADJUSTMENT")
                    + " check=" + check
                    + " adjustmentIndex=" + indexCount("uq_cae_one_active_adjustment_per_shipment")
                    + " creditDaysColumn=" + columnCount("customer", "credit_days"));
        }
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

    private int entryCount() {
        return entryCount(null);
    }

    private int entryCount(String entryType) {
        Integer count = entryType == null
                ? jdbc.queryForObject("SELECT count(*) FROM customer_account_entry", Integer.class)
                : jdbc.queryForObject(
                        "SELECT count(*) FROM customer_account_entry WHERE entry_type = ?",
                        Integer.class, entryType);
        return count == null ? 0 : count;
    }

    private String constraintDefinition(String name) {
        return jdbc.query("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?
                """, (rs, row) -> rs.getString(1), name).stream().findFirst().orElse(null);
    }

    private static String messages(Throwable error) {
        StringBuilder text = new StringBuilder();
        while (error != null) {
            if (error.getMessage() != null) {
                text.append(error.getMessage()).append('\n');
            }
            error = error.getCause();
        }
        return text.toString();
    }

    private int columnCount(String table, String column) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return count == null ? 0 : count;
    }
}
