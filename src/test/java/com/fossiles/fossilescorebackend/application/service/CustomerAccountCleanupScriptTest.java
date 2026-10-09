package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Duplicate-charge cleanup on throwaway PostgreSQL. No production credentials.
 * The script is dry-run unless {@code -v aplicar=si}.
 */
@Testcontainers
class CustomerAccountCleanupScriptTest {

    private static final Path SCRIPTS = Path.of("scripts");
    private static final Path WORK = Path.of("/tmp/lf-cleanup-tests");
    private static final String VOID_TAG = "LIMPIEZA-CXC-DUPLICADOS-2026-10";
    private static final String CHARGE_TAG = "LIMPIEZA-CXC-CARGO-NUEVO-2026-10";
    private static final String ADJ_TAG = "LIMPIEZA-CXC-AJUSTE-ENVIO-2026-10";

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
                    CREATE TABLE production_order (
                        id BIGSERIAL PRIMARY KEY,
                        code VARCHAR(30) NOT NULL UNIQUE,
                        order_type VARCHAR(20) NOT NULL,
                        customer_id BIGINT,
                        seller_name VARCHAR(150)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE product (
                        id BIGSERIAL PRIMARY KEY,
                        seller_price NUMERIC(12, 2),
                        sale_price NUMERIC(12, 2),
                        discounted_price NUMERIC(12, 2)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE production_order_item (
                        id BIGSERIAL PRIMARY KEY,
                        production_order_id BIGINT NOT NULL,
                        product_id BIGINT,
                        quantity INTEGER,
                        unit_price NUMERIC(12, 2),
                        sizes_data TEXT,
                        unit_prices_json TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE product_shipment (
                        id BIGSERIAL PRIMARY KEY,
                        production_order_id BIGINT,
                        shipment_number VARCHAR(50) NOT NULL UNIQUE,
                        status VARCHAR(50) NOT NULL,
                        shipping_cost NUMERIC(12, 2),
                        sent_at TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE customer_account_entry (
                        id BIGSERIAL PRIMARY KEY,
                        customer_id BIGINT NOT NULL REFERENCES customer (id),
                        entry_type VARCHAR(30) NOT NULL,
                        entry_date DATE NOT NULL,
                        amount NUMERIC(15, 2) NOT NULL,
                        description TEXT,
                        production_order_id BIGINT,
                        product_shipment_id BIGINT,
                        applied_to_entry_id BIGINT REFERENCES customer_account_entry (id),
                        order_kind VARCHAR(10),
                        gross_collected_amount NUMERIC(15, 2),
                        payment_discount_amount NUMERIC(15, 2),
                        status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                        voided_at TIMESTAMP,
                        voided_by BIGINT,
                        void_reason TEXT,
                        created_at TIMESTAMP,
                        updated_at TIMESTAMP,
                        CONSTRAINT chk_customer_account_entry_amount CHECK (amount >= 0),
                        CONSTRAINT chk_customer_account_entry_type CHECK (
                            entry_type IN ('CHARGE', 'PAYMENT', 'CREDIT_NOTE', 'OPENING_BALANCE', 'RETURN')
                        ),
                        CONSTRAINT chk_customer_account_entry_status CHECK (status IN ('ACTIVE', 'VOID'))
                    )
                    """);
        }
        Psql phase1 = psql("migration-customer-account-lf-phase1.sql");
        assertThat(phase1.exitCode).as(phase1.output).isZero();
    }

    @Test
    void dryRunPrintsThePlanAndChangesNothing() throws Exception {
        seed();
        String before = fingerprint();
        Psql dry = cleanup();
        assertThat(dry.exitCode).as(dry.output).isZero();
        assertThat(dry.output).doesNotContain("NOMBRE_SECRETO");
        assertThat(dry.output).doesNotContain("LEG-");
        assertThat(dry.output).contains("SIN_ORDEN", "5100", "PLAN", "CONSERVAR", "NUEVO");
        assertThat(dry.output).contains("REVIEW", "300", "diferencia mayor que el envio");
        assertThat(dry.output).contains("OVERPAID", "400");
        assertThat(dry.output).containsPattern("RESUMEN\\s+\\|\\s+5\\s+\\|\\s+1\\s+\\|\\s+1\\s+\\|\\s+ROLLBACK");
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM customer_account_entry WHERE void_reason = '" + VOID_TAG + "'")).isZero();
        Files.writeString(WORK.resolve("dry-run.txt"), dry.output);
    }

    @Test
    void applyFixesDuplicatesAndLeavesReviewAndNoOrderCharge() throws Exception {
        seed();
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isZero();
        assertThat(applied.output).doesNotContain("NOMBRE_SECRETO");
        assertThat(applied.output).containsPattern("RESUMEN\\s+\\|\\s+5\\s+\\|\\s+1\\s+\\|\\s+1\\s+\\|\\s+COMMIT");
        assertThat(applied.output).contains("OVERPAID");

        assertThat(status(1100)).isEqualTo("ACTIVE");
        assertThat(money(1100)).isEqualByComparingTo("200.00");
        assertThat(text("SELECT void_reason FROM customer_account_entry WHERE id = 1100")).isNull();
        assertThat(status(1101)).isEqualTo("VOID");
        assertThat(money(1101)).isEqualByComparingTo("40.00");
        assertThat(text("SELECT void_reason FROM customer_account_entry WHERE id = 1101")).isEqualTo(VOID_TAG);
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 1102")).isEqualTo("1100");
        assertThat(text("SELECT reassigned_from_entry_id::text FROM customer_account_entry WHERE id = 1102")).isEqualTo("1101");
        assertThat(text("SELECT description FROM customer_account_entry WHERE id = 1102")).isEqualTo("envio-previo");
        assertThat(text("SELECT order_kind FROM customer_account_entry WHERE id = 1102")).isEqualTo("OPV");
        assertThat(count("""
                SELECT count(*) FROM customer_account_entry
                WHERE production_order_id = 100 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo(1);
        assertMovedCredit(1110, "1100", "1101");
        assertMovedCredit(1111, "1100", "1101");
        assertMovedCredit(1112, "1100", "1101");
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 1113")).isEqualTo("1100");
        assertThat(text("SELECT reassigned_from_entry_id::text FROM customer_account_entry WHERE id = 1113")).isNull();
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 1114")).isEqualTo("1100");
        assertThat(text("SELECT reassigned_from_entry_id::text FROM customer_account_entry WHERE id = 1114")).isEqualTo("1100");

        assertThat(status(2100)).isEqualTo("VOID");
        assertThat(money(2100)).isEqualByComparingTo("60.00");
        assertThat(status(2101)).isEqualTo("VOID");
        assertThat(money(2101)).isEqualByComparingTo("55.00");
        String newCharge = text("""
                SELECT id::text FROM customer_account_entry
                WHERE production_order_id = 200 AND entry_type = 'CHARGE' AND status = 'ACTIVE'
                """);
        assertThat(newCharge).isNotBlank();
        assertThat(money(Long.parseLong(newCharge))).isEqualByComparingTo("100.00");
        assertThat(text("SELECT description FROM customer_account_entry WHERE id = " + newCharge)).isEqualTo(CHARGE_TAG);
        assertThat(text("SELECT entry_date::text FROM customer_account_entry WHERE id = " + newCharge)).isEqualTo("2026-01-15");
        assertThat(text("SELECT order_kind FROM customer_account_entry WHERE id = " + newCharge)).isEqualTo("OPV");
        assertThat(text("SELECT customer_id::text FROM customer_account_entry WHERE id = " + newCharge)).isEqualTo("20");
        assertMovedCredit(2110, newCharge, "2101");
        assertThat(text("""
                SELECT amount::text FROM customer_account_entry
                WHERE production_order_id = 200 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo("15.00");
        assertThat(text("""
                SELECT description FROM customer_account_entry
                WHERE production_order_id = 200 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo(ADJ_TAG);
        assertThat(text("""
                SELECT product_shipment_id::text FROM customer_account_entry
                WHERE production_order_id = 200 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo("2001");
        assertThat(text("""
                SELECT applied_to_entry_id::text FROM customer_account_entry
                WHERE production_order_id = 200 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo(newCharge);

        assertThat(status(3100)).isEqualTo("ACTIVE");
        assertThat(status(3101)).isEqualTo("ACTIVE");
        assertThat(text("SELECT void_reason FROM customer_account_entry WHERE id = 3100")).isNull();
        assertThat(money(3100)).isEqualByComparingTo("100.00");
        assertThat(money(3101)).isEqualByComparingTo("500.00");
        assertThat(count("""
                SELECT count(*) FROM customer_account_entry
                WHERE production_order_id = 300 AND entry_type = 'CHARGE_ADJUSTMENT'
                """)).isZero();

        assertThat(status(4100)).isEqualTo("ACTIVE");
        assertThat(money(4100)).isEqualByComparingTo("50.00");
        assertThat(status(4101)).isEqualTo("VOID");
        assertThat(money(4101)).isEqualByComparingTo("10.00");
        assertThat(text("""
                SELECT description FROM customer_account_entry
                WHERE production_order_id = 400 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo(ADJ_TAG);
        assertThat(moneyOf("""
                SELECT amount FROM customer_account_entry
                WHERE production_order_id = 400 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualByComparingTo("10.00");
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 4110")).isEqualTo("4100");
        assertThat(text("SELECT reassigned_from_entry_id::text FROM customer_account_entry WHERE id = 4110")).isNull();

        assertThat(status(5100)).isEqualTo("ACTIVE");
        assertThat(money(5100)).isEqualByComparingTo("999.00");
        assertThat(text("SELECT production_order_id::text FROM customer_account_entry WHERE id = 5100")).isNull();
        assertThat(text("SELECT void_reason FROM customer_account_entry WHERE id = 5100")).isNull();

        assertThat(status(6100)).isEqualTo("ACTIVE");
        assertThat(money(6100)).isEqualByComparingTo("30.00");
        assertThat(text("SELECT description FROM customer_account_entry WHERE id = 6101")).isEqualTo("ya-bien");
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 6101")).isEqualTo("6100");
        assertThat(count("SELECT count(*) FROM customer_account_entry WHERE production_order_id = 600")).isEqualTo(2);

        assertThat(status(7100)).isEqualTo("VOID");
        assertThat(money(7100)).isEqualByComparingTo("48.00");
        assertThat(text("""
                SELECT amount::text FROM customer_account_entry
                WHERE production_order_id = 700 AND entry_type = 'CHARGE' AND status = 'ACTIVE'
                """)).isEqualTo("40.00");
        assertThat(text("""
                SELECT description FROM customer_account_entry
                WHERE production_order_id = 700 AND entry_type = 'CHARGE' AND status = 'ACTIVE'
                """)).isEqualTo(CHARGE_TAG);
        assertThat(text("""
                SELECT entry_date::text FROM customer_account_entry
                WHERE production_order_id = 700 AND entry_type = 'CHARGE' AND status = 'ACTIVE'
                """)).isEqualTo("2026-04-01");
        assertThat(text("""
                SELECT amount::text FROM customer_account_entry
                WHERE production_order_id = 700 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo("8.00");

        assertThat(status(8100)).isEqualTo("ACTIVE");
        assertThat(money(8100)).isEqualByComparingTo("100.00");
        assertThat(status(8101)).isEqualTo("VOID");
        assertThat(money(8101)).isEqualByComparingTo("10.00");
        assertThat(text("""
                SELECT description FROM customer_account_entry
                WHERE production_order_id = 800 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo(ADJ_TAG);
        assertThat(moneyOf("""
                SELECT amount FROM customer_account_entry
                WHERE production_order_id = 800 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualByComparingTo("25.00");

        assertThat(count("""
                SELECT count(*) FROM product_shipment ps
                JOIN customer_account_entry a ON a.product_shipment_id = ps.id
                WHERE ps.id IN (1002, 1003, 1004)
                """)).isZero();
    }

    @Test
    void secondRunIsANoOp() throws Exception {
        seed();
        Psql first = cleanup("aplicar=si");
        assertThat(first.exitCode).as(first.output).isZero();
        String afterFirst = fingerprint();
        int rows = count("SELECT count(*) FROM customer_account_entry");

        Psql second = cleanup("aplicar=si");
        assertThat(second.exitCode).as(second.output).isZero();
        assertThat(second.output).containsPattern("RESUMEN\\s+\\|\\s+0\\s+\\|\\s+1\\s+\\|\\s+0\\s+\\|\\s+COMMIT");
        assertThat(second.output).contains("REVIEW", "300", "SIN_ORDEN", "5100");
        assertThat(second.output).doesNotContain("CONSERVAR");
        assertThat(second.output).doesNotContain("NUEVO");
        assertThat(fingerprint()).isEqualTo(afterFirst);
        assertThat(count("SELECT count(*) FROM customer_account_entry")).isEqualTo(rows);
    }

    @Test
    void reconcileReturnsZeroRows() throws Exception {
        seed();
        Psql before = snapshot("cxc-antes.csv");
        assertThat(before.exitCode).as(before.output).isZero();
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isZero();
        Psql after = snapshot("cxc-despues.csv");
        assertThat(after.exitCode).as(after.output).isZero();

        Psql reconcile = psql("reconcile-cleanup-balances.sql");
        assertThat(reconcile.exitCode).as(reconcile.output).isZero();
        assertThat(reconcile.output).contains("(0 rows)");
        assertThat(reconcile.output).doesNotContain("LEG-");

        assertThat(moneyOf("""
                SELECT round(COALESCE(sum(
                    CASE
                        WHEN status <> 'ACTIVE' THEN 0
                        WHEN entry_type IN ('CHARGE', 'OPENING_BALANCE', 'CHARGE_ADJUSTMENT') THEN amount
                        WHEN entry_type IN ('PAYMENT', 'CREDIT_NOTE', 'RETURN') THEN -amount
                        ELSE 0
                    END
                ), 0), 2)
                FROM customer_account_entry WHERE customer_id = 80
                """)).isEqualByComparingTo("125.00");
    }

    private void assertMovedCredit(long entryId, String survivorId, String originalChargeId) throws Exception {
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = " + entryId))
                .isEqualTo(survivorId);
        assertThat(text("SELECT reassigned_from_entry_id::text FROM customer_account_entry WHERE id = " + entryId))
                .isEqualTo(originalChargeId);
        assertThat(text("SELECT production_order_id::text FROM customer_account_entry WHERE id = " + entryId))
                .isNotBlank();
        assertThat(text("SELECT order_kind FROM customer_account_entry WHERE id = " + entryId)).isEqualTo("OPV");
    }

    private void seed() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO customer (id, legacy_code, name) VALUES
                        (10, 'LEG-10', 'NOMBRE_SECRETO'),
                        (20, 'LEG-20', 'NOMBRE_SECRETO'),
                        (30, 'LEG-30', 'NOMBRE_SECRETO'),
                        (40, 'LEG-40', 'NOMBRE_SECRETO'),
                        (50, 'LEG-50', 'NOMBRE_SECRETO'),
                        (60, 'LEG-60', 'NOMBRE_SECRETO'),
                        (70, 'LEG-70', 'NOMBRE_SECRETO'),
                        (80, 'LEG-80', 'NOMBRE_SECRETO')
                    """);
            statement.execute("""
                    INSERT INTO production_order (id, code, order_type, customer_id, seller_name) VALUES
                        (100, 'OP-100', 'NORMAL', 10, 'LUIS FELIPE'),
                        (200, 'OP-200', 'NORMAL', 20, 'LUIS FELIPE'),
                        (300, 'OP-300', 'NORMAL', 30, 'LUIS FELIPE'),
                        (400, 'OP-400', 'NORMAL', 40, 'LUIS FELIPE'),
                        (600, 'OP-600', 'NORMAL', 60, 'LUIS FELIPE'),
                        (700, 'OP-700', 'NORMAL', 70, 'LUIS FELIPE'),
                        (800, 'OP-800', 'NORMAL', 80, 'LUIS FELIPE')
                    """);
            statement.execute("""
                    INSERT INTO production_order_item (production_order_id, quantity, unit_price) VALUES
                        (100, 4, 50.00),
                        (200, 2, 50.00),
                        (300, 2, 50.00),
                        (400, 1, 50.00),
                        (600, 3, 10.00),
                        (700, 1, 40.00),
                        (800, 2, 50.00)
                    """);
            statement.execute("""
                    INSERT INTO product_shipment (id, production_order_id, shipment_number, status, shipping_cost, sent_at) VALUES
                        (1001, 100, 'S1001', 'SENT', 40.00, TIMESTAMP '2026-01-02'),
                        (1002, 100, 'S1002', 'SENT', NULL, TIMESTAMP '2026-01-03'),
                        (1003, 100, 'S1003', 'SENT', 0.00, TIMESTAMP '2026-01-04'),
                        (1004, 100, 'S1004', 'VOID', 15.00, TIMESTAMP '2026-01-05'),
                        (2001, 200, 'S2001', 'SENT', 15.00, TIMESTAMP '2026-01-16'),
                        (3001, 300, 'S3001', 'SENT', 10.00, TIMESTAMP '2026-01-02'),
                        (4001, 400, 'S4001', 'SENT', 10.00, TIMESTAMP '2026-01-02'),
                        (6001, 600, 'S6001', 'SENT', 5.00, TIMESTAMP '2026-01-02'),
                        (7001, 700, 'S7001', 'SENT', 8.00, TIMESTAMP '2026-04-02'),
                        (8001, 800, 'S8001', 'SENT', 25.00, TIMESTAMP '2026-01-02')
                    """);
            statement.execute("""
                    INSERT INTO customer_account_entry
                        (id, customer_id, entry_type, entry_date, amount, production_order_id, status, order_kind)
                    VALUES
                        (1100, 10, 'CHARGE', DATE '2026-01-01', 200.00, 100, 'ACTIVE', 'OPV'),
                        (1101, 10, 'CHARGE', DATE '2026-02-01', 40.00, 100, 'ACTIVE', 'OPV'),
                        (2100, 20, 'CHARGE', DATE '2026-01-15', 60.00, 200, 'ACTIVE', 'OPV'),
                        (2101, 20, 'CHARGE', DATE '2026-03-01', 55.00, 200, 'ACTIVE', 'OPV'),
                        (3100, 30, 'CHARGE', DATE '2026-01-01', 100.00, 300, 'ACTIVE', 'OPV'),
                        (3101, 30, 'CHARGE', DATE '2026-01-02', 500.00, 300, 'ACTIVE', 'OPV'),
                        (4100, 40, 'CHARGE', DATE '2026-01-01', 50.00, 400, 'ACTIVE', 'OPV'),
                        (4101, 40, 'CHARGE', DATE '2026-01-02', 10.00, 400, 'ACTIVE', 'OPV'),
                        (5100, 50, 'CHARGE', DATE '2026-01-01', 999.00, NULL, 'ACTIVE', NULL),
                        (6100, 60, 'CHARGE', DATE '2026-01-01', 30.00, 600, 'ACTIVE', 'OPV'),
                        (7100, 70, 'CHARGE', DATE '2026-04-01', 48.00, 700, 'ACTIVE', 'OPV'),
                        (8100, 80, 'CHARGE', DATE '2026-01-01', 100.00, 800, 'ACTIVE', 'OPV'),
                        (8101, 80, 'CHARGE', DATE '2026-01-02', 10.00, 800, 'ACTIVE', 'OPV')
                    """);
            statement.execute("""
                    INSERT INTO customer_account_entry
                        (id, customer_id, entry_type, entry_date, amount, description,
                         production_order_id, product_shipment_id, applied_to_entry_id, order_kind, status)
                    VALUES
                        (1102, 10, 'CHARGE_ADJUSTMENT', DATE '2026-01-02', 40.00, 'envio-previo',
                         100, 1001, 1101, 'OPV', 'ACTIVE'),
                        (6101, 60, 'CHARGE_ADJUSTMENT', DATE '2026-01-02', 5.00, 'ya-bien',
                         600, 6001, 6100, 'OPV', 'ACTIVE')
                    """);
            statement.execute("""
                    INSERT INTO customer_account_entry
                        (id, customer_id, entry_type, entry_date, amount, production_order_id,
                         applied_to_entry_id, reassigned_from_entry_id, order_kind, status)
                    VALUES
                        (1110, 10, 'PAYMENT', DATE '2026-02-02', 50.00, 100, 1101, NULL, 'OPV', 'ACTIVE'),
                        (1111, 10, 'CREDIT_NOTE', DATE '2026-02-03', 10.00, 100, 1101, NULL, 'OPV', 'ACTIVE'),
                        (1112, 10, 'RETURN', DATE '2026-02-04', 5.00, 100, 1101, NULL, 'OPV', 'ACTIVE'),
                        (1113, 10, 'PAYMENT', DATE '2026-01-10', 20.00, 100, 1100, NULL, 'OPV', 'ACTIVE'),
                        (1114, 10, 'PAYMENT', DATE '2026-02-05', 7.00, 100, 1101, 1100, 'OPV', 'ACTIVE'),
                        (2110, 20, 'PAYMENT', DATE '2026-03-02', 30.00, 200, 2101, NULL, 'OPC', 'ACTIVE'),
                        (4110, 40, 'PAYMENT', DATE '2026-01-03', 80.00, 400, 4100, NULL, 'OPV', 'ACTIVE')
                    """);
            statement.execute("SELECT setval('customer_account_entry_id_seq', (SELECT max(id) FROM customer_account_entry))");
        }
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Psql cleanup(String... variables) throws Exception {
        return psql("cleanup-customer-account-lf-duplicates.sql", variables);
    }

    private static Psql snapshot(String out) throws Exception {
        return psql(
                "snapshot-saldos-cxc.sql",
                "out=" + out,
                "void_tag=" + VOID_TAG,
                "adj_tag=" + ADJ_TAG,
                "charge_tag=" + CHARGE_TAG);
    }

    private static Psql psql(String script, String... variables) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("psql");
        command.add("-h");
        command.add(POSTGRES.getHost());
        command.add("-p");
        command.add(String.valueOf(POSTGRES.getMappedPort(5432)));
        command.add("-U");
        command.add(POSTGRES.getUsername());
        command.add("-d");
        command.add(POSTGRES.getDatabaseName());
        command.add("-v");
        command.add("ON_ERROR_STOP=1");
        for (String variable : variables) {
            command.add("-v");
            command.add(variable);
        }
        command.add("-f");
        command.add(SCRIPTS.resolve(script).toAbsolutePath().toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PGPASSWORD", POSTGRES.getPassword());
        builder.directory(WORK.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        return new Psql(process.waitFor(), output);
    }

    private static String fingerprint() throws Exception {
        return text("""
                SELECT md5(coalesce(string_agg(
                    id::text || '|' || entry_type || '|' || status || '|' || amount::text
                    || '|' || coalesce(production_order_id::text, '')
                    || '|' || coalesce(applied_to_entry_id::text, '')
                    || '|' || coalesce(reassigned_from_entry_id::text, '')
                    || '|' || coalesce(product_shipment_id::text, '')
                    || '|' || coalesce(description, '')
                    || '|' || coalesce(void_reason, '')
                    || '|' || coalesce(order_kind, ''),
                    E'\\n' ORDER BY id), ''))
                FROM customer_account_entry
                """);
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String status(long id) throws Exception {
        return text("SELECT status FROM customer_account_entry WHERE id = " + id);
    }

    private static BigDecimal money(long id) throws Exception {
        return moneyOf("SELECT amount FROM customer_account_entry WHERE id = " + id);
    }

    private static BigDecimal moneyOf(String sql) throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next() || result.getBigDecimal(1) == null) {
                return null;
            }
            return result.getBigDecimal(1);
        }
    }

    private static String text(String sql) throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                return null;
            }
            return result.getString(1);
        }
    }

    private record Psql(int exitCode, String output) {}
}
