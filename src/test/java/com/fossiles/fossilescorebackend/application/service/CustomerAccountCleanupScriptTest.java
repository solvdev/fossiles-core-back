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
                        seller_name VARCHAR(150),
                        status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
                        vendor_shipment_number VARCHAR(30)
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
                        partial_release_id BIGINT,
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
                        partial_release_id BIGINT,
                        applied_to_entry_id BIGINT REFERENCES customer_account_entry (id),
                        order_kind VARCHAR(10),
                        document_number VARCHAR(50),
                        invoice_number VARCHAR(50),
                        vendor_shipment_number VARCHAR(30),
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
            statement.execute("""
                    CREATE TABLE production_order_partial_release (
                        id BIGINT PRIMARY KEY
                    )
                    """);
            statement.execute("""
                    ALTER TABLE customer_account_entry
                        ADD CONSTRAINT fk_cae_partial_release
                        FOREIGN KEY (partial_release_id) REFERENCES production_order_partial_release (id)
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
        assertThat(dry.output).contains("REVIEW", "300", "cambiaria el saldo en -490.00");
        assertThat(dry.output).contains("OVERPAID", "400");
        assertThat(dry.output).containsPattern("RESUMEN\\s+\\|\\s+5\\s+\\|\\s+9\\s+\\|\\s+1\\s+\\|\\s+ROLLBACK");
        assertThat(dry.output).contains("estado después de aplicar", "REENLACE", "8802", "8801", "8800");
        assertThat(dry.output).contains("ajuste existente distinto al costo de envio");
        assertThat(dry.output).contains("cambiaria el saldo en -40.00", "cambiaria el saldo en -20.00", "cambiaria el saldo en 20.00");
        assertThat(dry.output).contains("BALANCE_CHECK", "saldos sin cambio");
        assertPhase2Blockers(dry.output);
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
        assertThat(applied.output).containsPattern("RESUMEN\\s+\\|\\s+5\\s+\\|\\s+9\\s+\\|\\s+1\\s+\\|\\s+COMMIT");
        assertThat(applied.output).contains("OVERPAID");
        assertPhase2Blockers(applied.output);

        assertThat(status(1100)).isEqualTo("ACTIVE");
        assertThat(money(1100)).isEqualByComparingTo("200.00");
        assertThat(text("SELECT void_reason FROM customer_account_entry WHERE id = 1100")).isNull();
        assertThat(status(1101)).isEqualTo("VOID");
        assertThat(money(1101)).isEqualByComparingTo("40.00");
        assertThat(text("SELECT void_reason FROM customer_account_entry WHERE id = 1101")).isEqualTo(VOID_TAG);
        assertThat(text("""
                SELECT description FROM customer_account_entry
                WHERE production_order_id = 100 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo(ADJ_TAG);
        assertThat(text("""
                SELECT applied_to_entry_id::text FROM customer_account_entry
                WHERE production_order_id = 100 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo("1100");
        assertThat(moneyOf("""
                SELECT amount FROM customer_account_entry
                WHERE production_order_id = 100 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualByComparingTo("40.00");
        assertThat(text("""
                SELECT partial_release_id::text FROM customer_account_entry
                WHERE production_order_id = 100 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isNull();
        assertThat(moneyOf(customerDebit(10))).isEqualByComparingTo("148.00");
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
        assertThat(text("SELECT document_number FROM customer_account_entry WHERE id = " + newCharge)).isEqualTo("OP-200");
        assertThat(text("SELECT invoice_number FROM customer_account_entry WHERE id = " + newCharge)).isEqualTo("FAC-200");
        assertThat(text("SELECT vendor_shipment_number FROM customer_account_entry WHERE id = " + newCharge)).isEqualTo("ENVP-200");
        assertThat(text("""
                SELECT partial_release_id::text FROM customer_account_entry
                WHERE production_order_id = 200 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo("77");
        assertThat(text("""
                SELECT invoice_number FROM customer_account_entry
                WHERE production_order_id = 200 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo("FAC-ENVIO");

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
        assertThat(status(8101)).isEqualTo("ACTIVE");
        assertThat(money(8101)).isEqualByComparingTo("10.00");
        assertThat(count("""
                SELECT count(*) FROM customer_account_entry
                WHERE production_order_id = 800 AND entry_type = 'CHARGE_ADJUSTMENT'
                """)).isZero();
        assertThat(status(9000)).isEqualTo("ACTIVE");
        assertThat(status(9001)).isEqualTo("ACTIVE");
        assertThat(moneyOf(customerDebit(90))).isEqualByComparingTo("150.00");
        assertThat(status(9100)).isEqualTo("ACTIVE");
        assertThat(status(9101)).isEqualTo("ACTIVE");
        assertThat(text("SELECT description FROM customer_account_entry WHERE id = 9102")).isEqualTo("ajuste-120");
        assertThat(moneyOf(customerDebit(91))).isEqualByComparingTo("140.00");
        assertThat(status(9200)).isEqualTo("ACTIVE");
        assertThat(money(9200)).isEqualByComparingTo("100.00");
        assertThat(count("""
                SELECT count(*) FROM customer_account_entry
                WHERE production_order_id = 920 AND entry_type = 'CHARGE_ADJUSTMENT'
                """)).isZero();
        assertThat(moneyOf("""
                SELECT round(coalesce(sum(CASE WHEN status = 'ACTIVE' AND entry_type IN ('CHARGE', 'CHARGE_ADJUSTMENT') THEN amount END), 0), 2)
                FROM customer_account_entry WHERE customer_id = 92
                """)).isEqualByComparingTo("100.00");
        assertThat(status(9300)).isEqualTo("ACTIVE");
        assertThat(status(9301)).isEqualTo("ACTIVE");
        assertThat(count("""
                SELECT count(*) FROM customer_account_entry
                WHERE production_order_id = 930 AND description = '""" + CHARGE_TAG + "'")).isZero();
        assertThat(status(9400)).isEqualTo("ACTIVE");
        assertThat(status(9401)).isEqualTo("ACTIVE");
        assertThat(status(9500)).isEqualTo("ACTIVE");
        assertThat(status(9501)).isEqualTo("ACTIVE");
        assertThat(count("""
                SELECT count(*) FROM customer_account_entry
                WHERE production_order_id = 950 AND entry_type = 'CHARGE_ADJUSTMENT'
                """)).isZero();
        assertThat(applied.output).contains("ajuste existente distinto al costo de envio");
        assertThat(status(9600)).isEqualTo("ACTIVE");
        assertThat(money(9600)).isEqualByComparingTo("80.00");
        assertThat(money(9601)).isEqualByComparingTo("5.00");
        assertThat(count("""
                SELECT count(*) FROM customer_account_entry
                WHERE production_order_id = 960 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo(1);
        assertThat(moneyOf(customerDebit(97))).isEqualByComparingTo("85.00");

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
        assertThat(second.output).containsPattern("RESUMEN\\s+\\|\\s+0\\s+\\|\\s+9\\s+\\|\\s+1\\s+\\|\\s+COMMIT");
        assertThat(second.output).contains("ajuste existente distinto al costo de envio");
        assertThat(status(9600)).isEqualTo("ACTIVE");
        assertThat(money(9601)).isEqualByComparingTo("5.00");
        assertPhase2Blockers(second.output);
        assertThat(second.output).contains("REVIEW", "300", "SIN_ORDEN", "5100", "OVERPAID", "400");
        assertThat(second.output).doesNotContain("CONSERVAR");
        assertThat(second.output).doesNotContain("NUEVO");
        assertThat(fingerprint()).isEqualTo(afterFirst);
        assertThat(count("SELECT count(*) FROM customer_account_entry")).isEqualTo(rows);
    }

    @Test
    void BUG_adjustmentOnVoidChargeStaysToFixEveryRun() throws Exception {
        seed();
        Psql first = cleanup("aplicar=si");
        assertThat(first.exitCode).as(first.output).isZero();
        assertThat(first.output).contains("REENLACE", "estado después de aplicar", "8802", "8801", "8800");
        assertThat(first.output).contains("LIMPIEZA-CXC-2026-10", "reenlace de ajuste al cargo sobreviviente");
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 8802")).isEqualTo("8800");
        assertThat(text("SELECT reassigned_from_entry_id::text FROM customer_account_entry WHERE id = 8802")).isEqualTo("8801");
        assertThat(text("SELECT entry_date::text FROM customer_account_entry WHERE id = 8802")).isEqualTo("2026-01-03");
        assertThat(text("SELECT description FROM customer_account_entry WHERE id = 8802"))
                .isEqualTo("ajuste-original\nLIMPIEZA-CXC-2026-10 cargo:8801");
        assertThat(money(8802)).isEqualByComparingTo("20.00");
        assertThat(moneyOf(customerDebit(88))).isEqualByComparingTo("120.00");
        assertThat(count("""
                SELECT count(*) FROM customer_account_entry
                WHERE production_order_id = 880 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isEqualTo(1);

        Psql second = cleanup("aplicar=si");
        assertThat(second.exitCode).as(second.output).isZero();
        assertThat(second.output).containsPattern("RESUMEN\\s+\\|\\s+0\\s+\\|");
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 8802")).isEqualTo("8800");
        assertThat(text("SELECT entry_date::text FROM customer_account_entry WHERE id = 8802")).isEqualTo("2026-01-03");

        Psql rollback = psql("rollback-customer-account-lf-phase1.sql");
        assertThat(rollback.exitCode).as(rollback.output).isNotZero();
        assertThat(rollback.output).contains("ROLLBACK FASE 1 abortado");
        assertThat(rollback.output).contains("Se revirtieron 1 reenlaces de limpieza");
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 8802")).isEqualTo("8801");
        assertThat(text("SELECT reassigned_from_entry_id::text FROM customer_account_entry WHERE id = 8802")).isNull();
        assertThat(text("SELECT description FROM customer_account_entry WHERE id = 8802")).isEqualTo("ajuste-original");
        assertThat(text("SELECT entry_date::text FROM customer_account_entry WHERE id = 8802")).isEqualTo("2026-01-03");
        assertThat(money(8802)).isEqualByComparingTo("20.00");
        assertThat(moneyOf(customerDebit(88))).isEqualByComparingTo("120.00");
        assertThat(count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'customer_account_entry' AND column_name = 'reassigned_from_entry_id'
                """)).isEqualTo(1);
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
                """)).isEqualByComparingTo("110.00");
    }

    @Test
    void phase2AbortsWhileReviewOrderAndNoOrderChargeRemain() throws Exception {
        seed();
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isZero();
        assertPhase2Blockers(applied.output);
        assertThat(count("""
                SELECT count(*) FROM (
                    SELECT production_order_id
                    FROM customer_account_entry
                    WHERE entry_type = 'CHARGE' AND status <> 'VOID' AND production_order_id IS NOT NULL
                    GROUP BY production_order_id
                    HAVING count(*) > 1
                ) d
                """)).isEqualTo(7);
        assertThat(count("""
                SELECT count(*) FROM customer_account_entry
                WHERE entry_type IN ('CHARGE', 'CHARGE_ADJUSTMENT')
                  AND status <> 'VOID' AND production_order_id IS NULL
                """)).isEqualTo(1);

        Psql phase2 = psql("migration-customer-account-lf-phase2.sql");
        assertThat(phase2.exitCode).as(phase2.output).isNotZero();
        assertThat(phase2.output).contains(
                "FASE 2 abortada: 7 ordenes con mas de un CHARGE activo y 1 cargos/ajustes activos sin orden. No se cambio nada.");
        assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'uq_cae_one_active_charge_per_order'")).isZero();
        assertThat(count("""
                SELECT count(*) FROM pg_constraint WHERE conname = 'chk_customer_account_entry_charge_order'
                """)).isZero();
        assertThat(status(3100)).isEqualTo("ACTIVE");
        assertThat(status(3101)).isEqualTo("ACTIVE");
        assertThat(status(5100)).isEqualTo("ACTIVE");
        assertThat(text("SELECT production_order_id::text FROM customer_account_entry WHERE id = 5100")).isNull();
    }

    @Test
    void phase2AppliesAfterVoidingTheExtraAndAssigningTheNoOrderCharge() throws Exception {
        seed();
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isZero();
        exec("""
                INSERT INTO production_order (id, code, order_type, customer_id, seller_name)
                VALUES (500, 'OP-500', 'NORMAL', 50, 'LUIS FELIPE')
                """);
        exec("UPDATE customer_account_entry SET status = 'VOID', void_reason = 'manual' WHERE id IN (3101, 8101, 9001, 9101, 9301, 9401, 9501)");
        exec("UPDATE customer_account_entry SET production_order_id = 500 WHERE id = 5100");

        Psql phase2 = psql("migration-customer-account-lf-phase2.sql");
        assertThat(phase2.exitCode).as(phase2.output).isZero();
        assertPhase2Applied();
        assertThat(status(3100)).isEqualTo("ACTIVE");
        assertThat(status(3101)).isEqualTo("VOID");
        assertThat(status(5100)).isEqualTo("ACTIVE");
        assertThat(text("SELECT production_order_id::text FROM customer_account_entry WHERE id = 5100")).isEqualTo("500");
    }

    @Test
    void phase2AppliesAfterVoidingTheExtraAndTheNoOrderCharge() throws Exception {
        seed();
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isZero();
        exec("""
                UPDATE customer_account_entry
                SET status = 'VOID', void_reason = 'manual'
                WHERE id IN (3101, 8101, 9001, 9101, 9301, 9401, 9501, 5100)
                """);

        Psql phase2 = psql("migration-customer-account-lf-phase2.sql");
        assertThat(phase2.exitCode).as(phase2.output).isZero();
        assertPhase2Applied();
        assertThat(status(3100)).isEqualTo("ACTIVE");
        assertThat(status(3101)).isEqualTo("VOID");
        assertThat(status(5100)).isEqualTo("VOID");
        assertThat(text("SELECT production_order_id::text FROM customer_account_entry WHERE id = 5100")).isNull();
    }

    private static void assertPhase2Blockers(String output) {
        assertThat(output).contains(
                "Fase 2 sigue bloqueada: 7 orden(es) con mas de un cargo activo y 1 cargo(s) activo(s) sin orden.");
        assertThat(output).containsPattern("PHASE2_BLOCKERS\\s+\\|\\s+7\\s+\\|\\s+1\\s+\\|");
        assertThat(output).contains("PHASE2_BLOCKERS_ORDEN");
        assertThat(output).contains("3100,3101");
        assertThat(output).contains("PHASE2_BLOCKERS_SIN_ORDEN");
        assertThat(output).contains("5100");
        assertThat(output).doesNotContain("NOMBRE_SECRETO");
    }

    private static void assertPhase2Applied() throws Exception {
        assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'uq_cae_one_active_charge_per_order'")).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'chk_customer_account_entry_charge_order' AND convalidated
                """)).isEqualTo(1);
    }

    private static void exec(String sql) throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    void forcedBalanceChangeAbortsWithNothingApplied() throws Exception {
        seed();
        String before = fingerprint();
        exec("""
                CREATE OR REPLACE FUNCTION bump_limpieza() RETURNS trigger AS $fn$
                BEGIN
                    IF NEW.description IN ('LIMPIEZA-CXC-CARGO-NUEVO-2026-10', 'LIMPIEZA-CXC-AJUSTE-ENVIO-2026-10') THEN
                        NEW.amount := NEW.amount + 1;
                    END IF;
                    RETURN NEW;
                END
                $fn$ LANGUAGE plpgsql
                """);
        exec("""
                CREATE TRIGGER bump_limpieza
                BEFORE INSERT ON customer_account_entry
                FOR EACH ROW EXECUTE FUNCTION bump_limpieza()
                """);
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isNotZero();
        assertThat(applied.output).contains("Limpieza abortada", "No se cambio nada", "cliente 10:");
        assertThat(applied.output).doesNotContain("NOMBRE_SECRETO");
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM customer_account_entry WHERE void_reason = '" + VOID_TAG + "'")).isZero();
    }

    private static String customerDebit(long customerId) {
        return """
                SELECT round(coalesce(sum(
                    CASE
                        WHEN status = 'ACTIVE' AND entry_type IN ('CHARGE', 'CHARGE_ADJUSTMENT') THEN amount
                        WHEN status = 'ACTIVE' AND entry_type IN ('PAYMENT', 'CREDIT_NOTE', 'RETURN') THEN -amount
                        ELSE 0
                    END
                ), 0), 2)
                FROM customer_account_entry WHERE customer_id = %d
                """.formatted(customerId);
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
                        (80, 'LEG-80', 'NOMBRE_SECRETO'),
                        (90, 'LEG-90', 'NOMBRE_SECRETO'),
                        (91, 'LEG-91', 'NOMBRE_SECRETO'),
                        (92, 'LEG-92', 'NOMBRE_SECRETO'),
                        (93, 'LEG-93', 'NOMBRE_SECRETO'),
                        (94, 'LEG-94', 'NOMBRE_SECRETO'),
                        (95, 'LEG-95', 'NOMBRE_SECRETO'),
                        (96, 'LEG-96', 'NOMBRE_SECRETO'),
                        (97, 'LEG-97', 'NOMBRE_SECRETO'),
                        (88, 'LEG-88', 'NOMBRE_SECRETO')
                    """);
            statement.execute("""
                    INSERT INTO production_order (id, code, order_type, customer_id, seller_name) VALUES
                        (100, 'OP-100', 'NORMAL', 10, 'LUIS FELIPE'),
                        (200, 'OP-200', 'NORMAL', 20, 'LUIS FELIPE'),
                        (300, 'OP-300', 'NORMAL', 30, 'LUIS FELIPE'),
                        (400, 'OP-400', 'NORMAL', 40, 'LUIS FELIPE'),
                        (600, 'OP-600', 'NORMAL', 60, 'LUIS FELIPE'),
                        (700, 'OP-700', 'NORMAL', 70, 'LUIS FELIPE'),
                        (800, 'OP-800', 'NORMAL', 80, 'LUIS FELIPE'),
                        (900, 'OP-900', 'NORMAL', 90, 'LUIS FELIPE'),
                        (910, 'OP-910', 'NORMAL', 91, 'LUIS FELIPE'),
                        (920, 'OP-920', 'NORMAL', 92, 'LUIS FELIPE'),
                        (930, 'OP-930', 'NORMAL', 93, 'LUIS FELIPE'),
                        (940, 'OP-940', 'NORMAL', 95, 'LUIS FELIPE'),
                        (950, 'OP-950', 'NORMAL', 96, 'LUIS FELIPE'),
                        (960, 'OP-960', 'NORMAL', 97, 'LUIS FELIPE'),
                        (880, 'OP-880', 'NORMAL', 88, 'LUIS FELIPE')
                    """);
            statement.execute("UPDATE production_order SET status = 'CANCELLED' WHERE id = 930");
            statement.execute("UPDATE production_order SET vendor_shipment_number = 'ENVP-200' WHERE id = 200");
            statement.execute("""
                    INSERT INTO production_order_item (production_order_id, quantity, unit_price) VALUES
                        (100, 4, 50.00),
                        (200, 2, 50.00),
                        (300, 2, 50.00),
                        (400, 1, 50.00),
                        (600, 3, 10.00),
                        (700, 1, 40.00),
                        (800, 2, 50.00),
                        (900, 2, 50.00),
                        (910, 2, 50.00),
                        (920, 2, 50.00),
                        (930, 1, 50.00),
                        (940, 1, 40.00),
                        (950, 1, 30.00),
                        (960, 1, 80.00),
                        (880, 2, 50.00)
                    """);
            statement.execute("""
                    INSERT INTO product_shipment (id, production_order_id, shipment_number, status, shipping_cost, sent_at) VALUES
                        (1001, 100, 'S1001', 'SENT', 40.00, TIMESTAMP '2026-01-02'),
                        (1002, 100, 'S1002', 'SENT', NULL, TIMESTAMP '2026-01-03'),
                        (1003, 100, 'S1003', 'SENT', 0.00, TIMESTAMP '2026-01-04'),
                        (1004, 100, 'S1004', 'SENT', NULL, TIMESTAMP '2026-01-05'),
                        (2001, 200, 'S2001', 'SENT', 15.00, TIMESTAMP '2026-01-16'),
                        (3001, 300, 'S3001', 'SENT', 10.00, TIMESTAMP '2026-01-02'),
                        (4001, 400, 'S4001', 'SENT', 10.00, TIMESTAMP '2026-01-02'),
                        (6001, 600, 'S6001', 'SENT', 5.00, TIMESTAMP '2026-01-02'),
                        (7001, 700, 'S7001', 'SENT', 8.00, TIMESTAMP '2026-04-02'),
                        (8001, 800, 'S8001', 'SENT', 25.00, TIMESTAMP '2026-01-02'),
                        (9010, 900, 'S9010', 'SENT', 10.00, TIMESTAMP '2026-01-02'),
                        (9110, 910, 'S9110', 'SENT', 20.00, TIMESTAMP '2026-01-02'),
                        (9210, 920, 'S9210', 'SENT', 20.00, TIMESTAMP '2026-01-02'),
                        (9310, 930, 'S9310', 'SENT', 10.00, TIMESTAMP '2026-01-02'),
                        (9510, 950, 'S9510', 'DRAFT', 5.00, NULL),
                        (9610, 960, 'S9610', 'SENT', 12.00, TIMESTAMP '2026-01-02'),
                        (8810, 880, 'S8810', 'SENT', 20.00, TIMESTAMP '2026-01-04')
                    """);
            statement.execute("INSERT INTO production_order_partial_release (id) VALUES (77)");
            statement.execute("UPDATE product_shipment SET partial_release_id = 77 WHERE id = 2001");
            statement.execute("UPDATE product_shipment SET partial_release_id = 99999 WHERE id = 1001");
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
                        (8101, 80, 'CHARGE', DATE '2026-01-02', 10.00, 800, 'ACTIVE', 'OPV'),
                        (9000, 90, 'CHARGE', DATE '2026-01-01', 90.00, 900, 'ACTIVE', 'OPV'),
                        (9001, 90, 'CHARGE', DATE '2026-01-02', 60.00, 900, 'ACTIVE', 'OPV'),
                        (9100, 91, 'CHARGE', DATE '2026-01-01', 100.00, 910, 'ACTIVE', 'OPV'),
                        (9101, 91, 'CHARGE', DATE '2026-01-02', 20.00, 910, 'ACTIVE', 'OPV'),
                        (9200, 92, 'CHARGE', DATE '2026-01-01', 100.00, 920, 'ACTIVE', 'OPV'),
                        (9300, 93, 'CHARGE', DATE '2026-01-01', 50.00, 930, 'ACTIVE', 'OPV'),
                        (9301, 93, 'CHARGE', DATE '2026-01-02', 10.00, 930, 'ACTIVE', 'OPV'),
                        (9400, 94, 'CHARGE', DATE '2026-01-01', 20.00, 940, 'ACTIVE', 'OPV'),
                        (9401, 94, 'CHARGE', DATE '2026-01-02', 20.00, 940, 'ACTIVE', 'OPV'),
                        (9500, 96, 'CHARGE', DATE '2026-01-01', 30.00, 950, 'ACTIVE', 'OPV'),
                        (9501, 96, 'CHARGE', DATE '2026-01-02', 5.00, 950, 'ACTIVE', 'OPV'),
                        (9600, 97, 'CHARGE', DATE '2026-01-01', 80.00, 960, 'ACTIVE', 'OPV'),
                        (8800, 88, 'CHARGE', DATE '2026-01-01', 100.00, 880, 'ACTIVE', 'OPV'),
                        (8801, 88, 'CHARGE', DATE '2026-01-02', 20.00, 880, 'VOID', 'OPV')
                    """);
            statement.execute("""
                    UPDATE customer_account_entry
                    SET document_number = 'OP-200', invoice_number = 'FAC-200', vendor_shipment_number = 'ENVP-200'
                    WHERE id = 2100
                    """);
            statement.execute("""
                    UPDATE customer_account_entry
                    SET product_shipment_id = 2001, invoice_number = 'FAC-ENVIO'
                    WHERE id = 2101
                    """);
            statement.execute("""
                    INSERT INTO customer_account_entry
                        (id, customer_id, entry_type, entry_date, amount, description,
                         production_order_id, product_shipment_id, applied_to_entry_id, order_kind, status)
                    VALUES
                        (6101, 60, 'CHARGE_ADJUSTMENT', DATE '2026-01-02', 5.00, 'ya-bien',
                         600, 6001, 6100, 'OPV', 'ACTIVE'),
                        (9102, 91, 'CHARGE_ADJUSTMENT', DATE '2026-01-02', 20.00, 'ajuste-120',
                         910, 9110, 9101, 'OPV', 'ACTIVE'),
                        (9601, 97, 'CHARGE_ADJUSTMENT', DATE '2026-01-02', 5.00, 'ajuste-distinto',
                         960, 9610, 9600, 'OPV', 'ACTIVE'),
                        (8802, 88, 'CHARGE_ADJUSTMENT', DATE '2026-01-03', 20.00, 'ajuste-original',
                         880, 8810, 8801, 'OPV', 'ACTIVE')
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
