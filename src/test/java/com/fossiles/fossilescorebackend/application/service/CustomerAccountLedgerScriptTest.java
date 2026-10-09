package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The advisor's scripts on throwaway PostgreSQL: phase 2 rolls back when duplicates exist,
 * and phase-1 rollback refuses while any CHARGE_ADJUSTMENT row exists.
 */
@Testcontainers
class CustomerAccountLedgerScriptTest {

    private static final Path SCRIPTS = Path.of("scripts");
    private static final Path WORK = Path.of("/tmp/lf-script-tests");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void schema() throws Exception {
        Files.createDirectories(WORK);
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
            statement.execute("""
                    CREATE TABLE customer (
                        id BIGSERIAL PRIMARY KEY,
                        legacy_code VARCHAR(30),
                        name VARCHAR(150)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE customer_account_entry (
                        id BIGSERIAL PRIMARY KEY,
                        customer_id BIGINT NOT NULL REFERENCES customer (id),
                        entry_type VARCHAR(30) NOT NULL,
                        entry_date DATE NOT NULL,
                        amount NUMERIC(15, 2) NOT NULL,
                        production_order_id BIGINT,
                        product_shipment_id BIGINT,
                        applied_to_entry_id BIGINT REFERENCES customer_account_entry (id),
                        status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                        description TEXT,
                        void_reason TEXT,
                        order_kind VARCHAR(10),
                        gross_collected_amount NUMERIC(15, 2),
                        payment_discount_amount NUMERIC(15, 2),
                        CONSTRAINT chk_customer_account_entry_amount CHECK (amount >= 0),
                        CONSTRAINT chk_customer_account_entry_type CHECK (
                            entry_type IN ('CHARGE', 'PAYMENT', 'CREDIT_NOTE', 'OPENING_BALANCE', 'RETURN')
                        ),
                        CONSTRAINT chk_customer_account_entry_status CHECK (status IN ('ACTIVE', 'VOID'))
                    )
                    """);
        }
    }

    @Test
    void phase2ChangesNothingWhenDuplicatesExist() throws Exception {
        Psql phase1 = psql("migration-customer-account-lf-phase1.sql");
        assertThat(phase1.exitCode).as(phase1.output).isZero();

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO customer (legacy_code) VALUES ('CB1')");
            statement.execute("""
                    INSERT INTO customer_account_entry
                        (customer_id, entry_type, entry_date, amount, production_order_id, status)
                    VALUES
                        (1, 'CHARGE', CURRENT_DATE, 10.00, 77, 'ACTIVE'),
                        (1, 'CHARGE', CURRENT_DATE, 20.00, 77, 'ACTIVE')
                    """);
        }

        Psql phase2 = psql("migration-customer-account-lf-phase2.sql");
        assertThat(phase2.exitCode).as(phase2.output).isNotZero();
        assertThat(phase2.output).contains("FASE 2 abortada");
        assertThat(count("SELECT count(*) FROM customer_account_entry WHERE entry_type = 'CHARGE'")).isEqualTo(2);
        assertThat(count("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'chk_customer_account_entry_charge_order'
                """)).isZero();
        assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'uq_cae_one_active_charge_per_order'")).isZero();
        assertThat(constraintDef("chk_customer_account_entry_type")).contains("CHARGE_ADJUSTMENT");
    }

    @Test
    void phase1RollbackRefusesWhileAdjustmentRowsExist() throws Exception {
        Psql phase1 = psql("migration-customer-account-lf-phase1.sql");
        assertThat(phase1.exitCode).as(phase1.output).isZero();

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO customer (legacy_code) VALUES ('CB2')");
            statement.execute("UPDATE customer SET credit_days = 30 WHERE id = 1");
            statement.execute("""
                    INSERT INTO customer_account_entry
                        (customer_id, entry_type, entry_date, amount, production_order_id, status)
                    VALUES (1, 'CHARGE', CURRENT_DATE, 10.00, 88, 'ACTIVE')
                    """);
            statement.execute("""
                    INSERT INTO customer_account_entry
                        (customer_id, entry_type, entry_date, amount, production_order_id,
                         product_shipment_id, applied_to_entry_id, status)
                    VALUES (1, 'CHARGE_ADJUSTMENT', CURRENT_DATE, 5.00, 88, 9, 1, 'ACTIVE')
                    """);
        }
        assertThatThrownBy(() -> {
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO customer_account_entry
                            (customer_id, entry_type, entry_date, amount, status)
                        VALUES (1, 'CHARGE_ADJUSTMENT', CURRENT_DATE, 5.00, 'ACTIVE')
                        """);
            }
        }).hasMessageContaining("chk_customer_account_entry_adjustment_links");
        assertThat(count("SELECT count(*) FROM customer_account_entry WHERE entry_type = 'CHARGE_ADJUSTMENT'")).isEqualTo(1);

        Psql rollback = psql("rollback-customer-account-lf-phase1.sql");
        assertThat(rollback.exitCode).as(rollback.output).isNotZero();
        assertThat(rollback.output).contains("ROLLBACK FASE 1 abortado");
        assertThat(count("SELECT count(*) FROM customer_account_entry WHERE entry_type = 'CHARGE_ADJUSTMENT'")).isEqualTo(1);
        assertThat(count("SELECT credit_days FROM customer WHERE id = 1")).isEqualTo(30);
        assertThat(constraintDef("chk_customer_account_entry_type")).contains("CHARGE_ADJUSTMENT");
        assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'uq_cae_one_active_adjustment_per_shipment'")).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'customer_account_entry' AND column_name = 'reassigned_from_entry_id'
                """)).isEqualTo(1);
    }

    @Test
    void phase1RollbackRefusesWhileReassignedFromIsSetAndSucceedsWhenClear() throws Exception {
        Psql phase1 = psql("migration-customer-account-lf-phase1.sql");
        assertThat(phase1.exitCode).as(phase1.output).isZero();
        assertThat(count("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'fk_customer_account_entry_reassigned_from' AND convalidated
                """)).isEqualTo(1);

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO customer (legacy_code) VALUES ('CB3')");
            statement.execute("UPDATE customer SET credit_days = 15 WHERE id = 1");
            statement.execute("""
                    INSERT INTO customer_account_entry
                        (customer_id, entry_type, entry_date, amount, production_order_id, status)
                    VALUES (1, 'CHARGE', CURRENT_DATE, 10.00, 41, 'ACTIVE')
                    """);
            statement.execute("""
                    INSERT INTO customer_account_entry
                        (customer_id, entry_type, entry_date, amount, production_order_id,
                         applied_to_entry_id, reassigned_from_entry_id, status)
                    VALUES (1, 'PAYMENT', CURRENT_DATE, 4.00, 41, 1, 1, 'ACTIVE')
                    """);
        }

        Psql refused = psql("rollback-customer-account-lf-phase1.sql");
        assertThat(refused.exitCode).as(refused.output).isNotZero();
        assertThat(refused.output).contains("reassigned_from_entry_id");
        assertThat(refused.output).contains("Se revirtieron 0 reenlaces de limpieza");
        assertThat(count("SELECT reassigned_from_entry_id FROM customer_account_entry WHERE entry_type = 'PAYMENT'")).isEqualTo(1);
        assertThat(count("SELECT credit_days FROM customer WHERE id = 1")).isEqualTo(15);
        assertThat(constraintDef("chk_customer_account_entry_type")).contains("CHARGE_ADJUSTMENT");
        assertThat(count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'customer_account_entry' AND column_name = 'reassigned_from_entry_id'
                """)).isEqualTo(1);

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE customer_account_entry SET reassigned_from_entry_id = NULL");
        }
        Psql clean = psql("rollback-customer-account-lf-phase1.sql");
        assertThat(clean.exitCode).as(clean.output).isZero();
        assertThat(count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'customer_account_entry' AND column_name = 'reassigned_from_entry_id'
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'customer' AND column_name = 'credit_days'
                """)).isZero();
        assertThat(constraintDef("chk_customer_account_entry_type")).doesNotContain("CHARGE_ADJUSTMENT");
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Psql psql(String script) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "psql",
                "-h", POSTGRES.getHost(),
                "-p", String.valueOf(POSTGRES.getMappedPort(5432)),
                "-U", POSTGRES.getUsername(),
                "-d", POSTGRES.getDatabaseName(),
                "-v", "ON_ERROR_STOP=1",
                "-f", SCRIPTS.resolve(script).toAbsolutePath().toString());
        builder.environment().put("PGPASSWORD", POSTGRES.getPassword());
        builder.directory(WORK.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        return new Psql(process.waitFor(), output);
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String constraintDef(String name) throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '" + name + "'")) {
            result.next();
            return result.getString(1);
        }
    }

    private record Psql(int exitCode, String output) {}
}
