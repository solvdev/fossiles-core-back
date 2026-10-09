package com.fossiles.fossilescorebackend.application.service;

import jakarta.persistence.Entity;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent Testcontainers checks for the LF duplicate-charge cleanup.
 * Scripts are read from {@code scripts/} at runtime. No production database.
 *
 * <p>Balances below are hand totals of active debits minus active credits:
 * products charge plus one adjustment per real {@code shipping_cost}, minus
 * payments, credit notes and returns (gross collected when it is set).
 */
@Testcontainers
class CleanupCustomerAccountLfDuplicatesIndependentTest {

    private static final Path SCRIPTS = Path.of("scripts");
    private static final String SELLER = "LUIS FELIPE";
    private static final String VOID_TAG = "LIMPIEZA-CXC-DUPLICADOS-2026-10";
    private static final String CHARGE_TAG = "LIMPIEZA-CXC-CARGO-NUEVO-2026-10";
    private static final String ADJ_TAG = "LIMPIEZA-CXC-AJUSTE-ENVIO-2026-10";

    // 502: 2*400 + (42→100, 46→100+50, 52→100+100) = 1250; shipping 150; payment 200.
    // 1250 + 150 - 200 = 1200.00
    private static final BigDecimal BALANCE_502 = new BigDecimal("1200.00");
    // 116: 4*100 = 400; shipping 25; payments 100 and 150. 400 + 25 - 100 - 150 = 175.00
    private static final BigDecimal BALANCE_116 = new BigDecimal("175.00");
    // 223: 3*80 = 240; shipping 20; credit note 30; return 10; payment 40. 180.00
    private static final BigDecimal BALANCE_223 = new BigDecimal("180.00");
    // 224: products 100; payment 35 still active on an already voided charge. 65.00
    private static final BigDecimal BALANCE_224 = new BigDecimal("65.00");
    // 305: review, charges 100 and 500 stay. 600.00
    private static final BigDecimal BALANCE_305 = new BigDecimal("600.00");
    // 406: products 50; shipping 10; gross collected 80. 50 + 10 - 80 = -20.00
    private static final BigDecimal BALANCE_406 = new BigDecimal("-20.00");
    // 507: active charge with no order. 999.00
    private static final BigDecimal BALANCE_507 = new BigDecimal("999.00");
    // 608: 3*10 = 30; shipping 5; payment 12. 23.00
    private static final BigDecimal BALANCE_608 = new BigDecimal("23.00");

    // 701 packing left out of the products total. Charge 280, quote 200+40. Delta -40.
    private static final BigDecimal BALANCE_701 = new BigDecimal("280.00");
    // 702 charge 120 already has a 20 adjustment the rewrite ignores. Delta -20. Before 140.
    private static final BigDecimal BALANCE_702 = new BigDecimal("140.00");
    // 703 shipping 20 was never billed. A new adjustment would add 20. Before 100.
    private static final BigDecimal BALANCE_703 = new BigDecimal("100.00");

    private static final List<String> LEDGER_TABLES = List.of(
            "customer",
            "production_order",
            "production_order_item",
            "product",
            "product_shipment",
            "customer_account_entry");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private Path work;

    @BeforeAll
    static void schemaFromEntitiesThenPhase1() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
        exportJpaSchema();
        execStatic("""
                INSERT INTO customer (id, legacy_code, credit_days) VALUES (1, '1', 0)
                """);
        execStatic("""
                INSERT INTO customer_account_entry
                    (id, customer_id, entry_type, entry_date, amount, status)
                VALUES (1, 1, 'CHARGE', DATE '2026-01-01', 1.00, 'ACTIVE')
                """);
        Path probe = Files.createTempDirectory("qaco-phase1-");
        String rowsBefore = snapshotTablesStatic();
        String csvBefore = runSnapshot(probe, "cxc-phase1-antes.csv");
        Psql phase1 = psqlStatic(probe, "migration-customer-account-lf-phase1.sql");
        assertThat(phase1.exitCode).as(phase1.output).isZero();
        assertThat(snapshotTablesStatic()).as("phase 1 changed ledger rows").isEqualTo(rowsBefore);
        assertThat(runSnapshot(probe, "cxc-phase1-despues.csv"))
                .as("phase 1 changed the balance snapshot")
                .isEqualTo(csvBefore);
        assertThat(countStatic("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'chk_customer_account_entry_type' AND convalidated
                  AND pg_get_constraintdef(oid) LIKE '%CHARGE_ADJUSTMENT%'
                """)).isEqualTo(1);
        assertThat(countStatic("""
                SELECT count(*) FROM pg_index
                WHERE indexrelid = to_regclass('uq_cae_one_active_adjustment_per_shipment') AND indisvalid
                """)).isEqualTo(1);
        // migration-customer-account-partial-link.sql. The shipment column is left without this FK
        // so a release deleted out from under an old shipment can still be represented.
        execStatic("""
                ALTER TABLE customer_account_entry
                    DROP CONSTRAINT IF EXISTS fk_customer_account_entry_partial_release
                """);
        execStatic("""
                ALTER TABLE customer_account_entry
                    ADD CONSTRAINT fk_customer_account_entry_partial_release
                    FOREIGN KEY (partial_release_id) REFERENCES production_order_partial_release (id)
                """);
        truncateLedger();
    }

    @BeforeEach
    void cleanLedger() throws Exception {
        work = Files.createTempDirectory("qaco-lf-");
        // Phase 2's unique index survives the ledger truncate and blocks the duplicate-charge fixtures.
        execStatic("DROP INDEX IF EXISTS uq_cae_one_active_charge_per_order");
        execStatic("""
                ALTER TABLE customer_account_entry
                    DROP CONSTRAINT IF EXISTS chk_customer_account_entry_charge_order
                """);
        truncateLedger();
    }

    @Test
    void dryRunChangesNothingAndPrintsReviewOverpaidAndBlockers() throws Exception {
        seedMain();
        String before = snapshotTables();
        Psql dry = cleanup();
        assertThat(dry.exitCode).as(dry.output).isZero();
        assertThat(snapshotTables()).isEqualTo(before);
        assertThat(sectionRows(dry.output, "REVIEW")).anyMatch(row -> row.contains("305"));
        assertThat(sectionRows(dry.output, "OVERPAID")).anyMatch(row -> row.contains("406"));
        assertThat(dry.output).contains("BALANCE_CHECK", "saldos sin cambio");
        assertPhase2Blockers(dry.output);
        assertThat(count("SELECT count(*) FROM customer_account_entry WHERE void_reason = '" + VOID_TAG + "'"))
                .isZero();
    }

    @Test
    void applyPreservesHandBalancesAndRepairsDuplicatesOutsideReview() throws Exception {
        seedMain();
        Map<Long, BigDecimal> chargeAmounts = chargeAmounts();
        String shipments = snapshotTables("product_shipment");
        String items = snapshotTables("production_order_item");
        Map<Long, BigDecimal> before = balances();
        assertHandBalances(before);

        Psql beforeSnap = snapshot("cxc-antes.csv");
        assertThat(beforeSnap.exitCode).as(beforeSnap.output).isZero();
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isZero();
        Psql afterSnap = snapshot("cxc-despues.csv");
        assertThat(afterSnap.exitCode).as(afterSnap.output).isZero();
        Psql reconcile = psql("reconcile-cleanup-balances.sql");
        assertThat(reconcile.exitCode).as(reconcile.output).isZero();
        assertThat(reconcile.output).contains("(0 rows)");

        assertHandBalances(balances());
        assertSameBalances(before, balances());
        assertThat(snapshotTables("product_shipment")).isEqualTo(shipments);
        assertThat(snapshotTables("production_order_item")).isEqualTo(items);
        assertOriginalChargeAmounts(chargeAmounts);

        assertThat(activeChargeCount(502)).isEqualTo(1);
        assertThat(activeChargeCount(116)).isEqualTo(1);
        assertThat(activeChargeCount(223)).isEqualTo(1);
        assertThat(activeChargeCount(224)).isEqualTo(1);
        assertThat(activeChargeCount(406)).isEqualTo(1);
        assertThat(activeChargeCount(608)).isEqualTo(1);
        assertThat(activeChargeCount(305)).isEqualTo(2);

        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 5020")).isEqualTo("VOID");
        assertThat(text("SELECT void_reason FROM customer_account_entry WHERE id = 5020")).isEqualTo(VOID_TAG);
        assertThat(text("SELECT voided_by::text FROM customer_account_entry WHERE id = 5020")).isNull();
        assertThat(text("SELECT created_by::text FROM customer_account_entry WHERE id = 5020")).isNull();
        String newCharge = activeChargeId(502);
        assertThat(text("SELECT description FROM customer_account_entry WHERE id = " + newCharge)).isEqualTo(CHARGE_TAG);
        assertThat(text("SELECT created_by::text FROM customer_account_entry WHERE id = " + newCharge)).isNull();
        assertThat(text("SELECT voided_by::text FROM customer_account_entry WHERE id = " + newCharge)).isNull();
        assertThat(money(Long.parseLong(newCharge))).isEqualByComparingTo("1250.00");
        assertThat(text("SELECT entry_date::text FROM customer_account_entry WHERE id = " + newCharge))
                .isEqualTo("2026-02-01");
        assertThat(text("SELECT order_kind FROM customer_account_entry WHERE id = " + newCharge)).isEqualTo("OPV");

        assertRealAdjustments();
        assertThat(adjustmentCount(305)).isZero();
        assertThat(count("""
                SELECT count(*) FROM customer_account_entry
                WHERE product_shipment_id IN (50202, 50203, 50204, 50205, 50206, 50207)
                """)).isZero();

        assertMoved(5021, newCharge, "5020");
        assertMoved(1163, "1160", "1161");
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 1162")).isEqualTo("1160");
        assertThat(text("SELECT reassigned_from_entry_id::text FROM customer_account_entry WHERE id = 1162")).isNull();
        assertMoved(2232, "2230", "2231");
        assertMoved(2233, "2230", "2231");
        assertMoved(2234, "2230", "2239");
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 1161")).isEqualTo("VOID");
        assertThat(text("SELECT void_reason FROM customer_account_entry WHERE id = 1161")).isEqualTo(VOID_TAG);
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 2231")).isEqualTo("VOID");
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 4061")).isEqualTo("VOID");

        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 2241")).isEqualTo("VOID");
        assertThat(text("SELECT void_reason FROM customer_account_entry WHERE id = 2241")).isEqualTo("PREVIO");
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 2242")).isEqualTo("2241");
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 2242")).isEqualTo("ACTIVE");
        assertThat(money(2242)).isEqualByComparingTo("35.00");

        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 3050")).isEqualTo("ACTIVE");
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 3051")).isEqualTo("ACTIVE");
        assertThat(text("SELECT void_reason FROM customer_account_entry WHERE id = 3050")).isNull();
        assertThat(money(3050)).isEqualByComparingTo("100.00");
        assertThat(money(3051)).isEqualByComparingTo("500.00");

        assertThat(text("SELECT production_order_id::text FROM customer_account_entry WHERE id = 5070")).isNull();
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 5070")).isEqualTo("ACTIVE");
        assertThat(money(5070)).isEqualByComparingTo("999.00");
        assertThat(text("SELECT void_reason FROM customer_account_entry WHERE id = 5070")).isNull();

        assertThat(text("SELECT description FROM customer_account_entry WHERE id = 6081")).isEqualTo("previo");
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 6081")).isEqualTo("6080");
        assertThat(count("SELECT count(*) FROM customer_account_entry WHERE production_order_id = 608")).isEqualTo(3);

        assertThat(orderIds(applied.output, "REVIEW")).containsExactly(305L);
        assertThat(orderIds(applied.output, "OVERPAID")).containsExactly(406L);
        assertPhase2Blockers(applied.output);
    }

    @Test
    void secondApplyChangesNothing() throws Exception {
        seedMain();
        Psql first = cleanup("aplicar=si");
        assertThat(first.exitCode).as(first.output).isZero();
        String afterFirst = snapshotTables();
        Psql second = cleanup("aplicar=si");
        assertThat(second.exitCode).as(second.output).isZero();
        assertThat(snapshotTables()).isEqualTo(afterFirst);
        assertThat(orderIds(second.output, "REVIEW")).containsExactly(305L);
        assertThat(orderIds(second.output, "OVERPAID")).containsExactly(406L);
        assertThat(sectionRows(second.output, "SIN_ORDEN")).anyMatch(row -> row.contains("5070"));
        assertPhase2Blockers(second.output);
    }

    @Test
    void phase2AbortsWhileReviewOrderAndNoOrderChargeRemain() throws Exception {
        seedMain();
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isZero();
        assertPhase2Blockers(applied.output);
        Psql phase2 = psql("migration-customer-account-lf-phase2.sql");
        assertThat(phase2.exitCode).as(phase2.output).isNotZero();
        assertThat(phase2.output).contains("FASE 2 abortada");
        assertThat(phase2.output).contains(
                "FASE 2 abortada: 1 ordenes con mas de un CHARGE activo y 1 cargos/ajustes activos sin orden. No se cambio nada.");
        assertPhase2ObjectsAbsent();
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 3050")).isEqualTo("ACTIVE");
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 3051")).isEqualTo("ACTIVE");
        assertThat(text("SELECT production_order_id::text FROM customer_account_entry WHERE id = 5070")).isNull();
    }

    @Test
    void phase2AppliesAfterVoidingExtraAndAssigningTheNoOrderCharge() throws Exception {
        seedMain();
        assertThat(cleanup("aplicar=si").exitCode).isZero();
        exec("""
                INSERT INTO production_order (id, code, order_type, customer_id, seller_name)
                VALUES (507, 'OP-507', 'NORMAL', 507, 'LUIS FELIPE')
                """);
        exec("UPDATE customer_account_entry SET status = 'VOID', void_reason = 'manual' WHERE id = 3051");
        exec("UPDATE customer_account_entry SET production_order_id = 507 WHERE id = 5070");
        Psql phase2 = psql("migration-customer-account-lf-phase2.sql");
        assertThat(phase2.exitCode).as(phase2.output).isZero();
        assertPhase2ObjectsPresent();
        assertThat(text("SELECT production_order_id::text FROM customer_account_entry WHERE id = 5070")).isEqualTo("507");
    }

    @Test
    void phase2AppliesAfterVoidingExtraAndTheNoOrderCharge() throws Exception {
        seedMain();
        assertThat(cleanup("aplicar=si").exitCode).isZero();
        String ledger = snapshotTables("customer_account_entry");
        exec("""
                UPDATE customer_account_entry
                SET status = 'VOID', void_reason = 'manual'
                WHERE id IN (3051, 5070)
                """);
        Psql phase2 = psql("migration-customer-account-lf-phase2.sql");
        assertThat(phase2.exitCode).as(phase2.output).isZero();
        assertPhase2ObjectsPresent();
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 5070")).isEqualTo("VOID");
        assertThat(text("SELECT production_order_id::text FROM customer_account_entry WHERE id = 5070")).isNull();

        Psql rollback2 = psql("rollback-customer-account-lf-phase2.sql");
        assertThat(rollback2.exitCode).as(rollback2.output).isZero();
        assertPhase2ObjectsAbsent();
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 5070")).isEqualTo("VOID");
        Psql rollback1 = psql("rollback-customer-account-lf-phase1.sql");
        assertThat(rollback1.exitCode).as(rollback1.output).isNotZero();
        assertThat(rollback1.output).contains("ROLLBACK FASE 1 abortado");
        assertThat(count("SELECT count(*) FROM customer_account_entry WHERE entry_type = 'CHARGE_ADJUSTMENT'"))
                .isPositive();
        assertThat(ledger).isNotBlank();
    }

    @Test
    void rollbackPhase1RefusesWhileAdjustmentOrMovedRowsExist() throws Exception {
        seedMain();
        assertThat(cleanup("aplicar=si").exitCode).isZero();
        Psql blockedByAdjustment = psql("rollback-customer-account-lf-phase1.sql");
        assertThat(blockedByAdjustment.exitCode).as(blockedByAdjustment.output).isNotZero();
        assertThat(blockedByAdjustment.output).contains("ROLLBACK FASE 1 abortado");
        assertThat(blockedByAdjustment.output).contains("CHARGE_ADJUSTMENT");
        assertThat(count("SELECT count(*) FROM customer_account_entry WHERE entry_type = 'CHARGE_ADJUSTMENT'"))
                .isPositive();

        exec("DELETE FROM customer_account_entry WHERE entry_type = 'CHARGE_ADJUSTMENT'");
        Psql blockedByTrace = psql("rollback-customer-account-lf-phase1.sql");
        assertThat(blockedByTrace.exitCode).as(blockedByTrace.output).isNotZero();
        assertThat(blockedByTrace.output).contains("ROLLBACK FASE 1 abortado");
        assertThat(blockedByTrace.output).contains("reassigned_from_entry_id");
        assertThat(text("SELECT reassigned_from_entry_id::text FROM customer_account_entry WHERE id = 1163"))
                .isEqualTo("1161");
        assertThat(countStatic("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'customer_account_entry' AND column_name = 'reassigned_from_entry_id'
                """)).isEqualTo(1);
    }

    @Test
    void conflictingLockFailsWithinTimeoutWithoutPartialWrites() throws Exception {
        seedMain();
        String before = snapshotTables();
        try (Connection holder = open()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("UPDATE customer_account_entry SET description = '5070' WHERE id = 5070");
            }
            long started = System.nanoTime();
            Psql cleanup = cleanup(20, "aplicar=si");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            holder.rollback();
            assertThat(cleanup.exitCode).as(cleanup.output).isNotZero();
            assertThat(cleanup.output).containsIgnoringCase("lock timeout");
            assertThat(elapsedMs).isBetween(3_000L, 15_000L);
        }
        assertThat(snapshotTables()).isEqualTo(before);
    }

    @Test
    void packingInsideTheChargeGoesToReviewAndBalanceStays() throws Exception {
        // Products 5*40 = 200. Packing 40 is inside the 280 charge and is not in the quote.
        // Shipping 40. Debit would fall from 280 to 240 (delta -40). Balance stays 280.00.
        seedFolded(701, "5", "40.00", "280.00", "40.00", null);
        assertBalanceBug(701, BALANCE_701, "280", "240", "-40");
    }

    @Test
    void foldedChargeWithExistingAdjustmentGoesToReview() throws Exception {
        // Products 100, shipping 20 folded into the 120 charge, plus a live adjustment of 20.
        // Debit would fall from 140 to 120 (delta -20). Balance stays 140.00.
        seedFolded(702, "2", "50.00", "120.00", "20.00", "20.00");
        assertBalanceBug(702, BALANCE_702, "140", "120", "-20");
    }

    @Test
    void unbilledSentShippingGoesToReview() throws Exception {
        // Products 100 already equal the only charge. Shipping 20 on a sent shipment was never billed.
        // Debit would rise from 100 to 120 (delta 20). Balance stays 100.00.
        seedFolded(703, "4", "25.00", "100.00", "20.00", null);
        assertBalanceBug(703, BALANCE_703, "100", "120", "20");
    }

    @Test
    void forcedBalanceChangeAbortsWithNothingApplied() throws Exception {
        // A folded shipment whose debit would stay the same. A trigger then adds 1 to each
        // inserted cleanup row so BALANCE_CHECK must abort the whole transaction.
        insertCustomer(710);
        insertOrder(710, 710, null);
        insertItem(710, 1, "100.00");
        insertShipment(71001, 710, "SENT", "10.00", "2026-05-01");
        insertCharge(7100, 710, 710, "110.00", "2026-05-01", "OPV");
        syncEntrySequence();
        String before = snapshotTables();
        try {
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
            assertThat(applied.output).contains("Limpieza abortada", "No se cambio nada");
            assertThat(snapshotTables()).as(applied.output).isEqualTo(before);
            assertThat(text("SELECT status FROM customer_account_entry WHERE id = 7100")).isEqualTo("ACTIVE");
            assertThat(adjustmentCount(710)).isZero();
        } finally {
            exec("DROP TRIGGER IF EXISTS bump_limpieza ON customer_account_entry");
            exec("DROP FUNCTION IF EXISTS bump_limpieza()");
        }
    }

    @Test
    void overpaidListKeepsEveryOrderAfterApply() throws Exception {
        // 850 is already one charge plus its real adjustment, and gross credits exceed both.
        // 40 + 10 - 70 = -20.00. 406 is overpaid only after this run rewrites it.
        // Both stay on the overpaid list, including the second apply.
        seedOverpaidPair();
        Psql first = cleanup("aplicar=si");
        assertThat(first.exitCode).as(first.output).isZero();
        Psql second = cleanup("aplicar=si");
        assertThat(second.exitCode).as(second.output).isZero();
        assertThat(orderIds(first.output, "OVERPAID")).as(first.output).contains(406L, 850L);
        assertThat(orderIds(second.output, "OVERPAID")).as(second.output).contains(406L, 850L);
        assertThat(balances().get(850L)).isEqualByComparingTo("-20.00");
        assertThat(balances().get(406L)).isEqualByComparingTo(BALANCE_406);
    }

    @Test
    void cancelledMismatchAndDraftShipmentGoToReviewEvenWhenDebitIsUnchanged() throws Exception {
        // 930 is CANCELLED. 940's order belongs to 940 while its charge belongs to 941.
        // 960 has a DRAFT shipment whose 5 was already folded into the charge, so delta is 0.
        // Products 80 + shipping 15 = 95 for the first two. None of them may receive a new charge.
        insertCustomer(930);
        insertOrder(930, 930, "CANCELLED");
        insertItem(930, 1, "80.00");
        insertShipment(93001, 930, "SENT", "15.00", "2026-06-01");
        insertCharge(9300, 930, 930, "95.00", "2026-06-01", "OPV");
        insertCustomer(940);
        insertCustomer(941);
        insertOrder(940, 940, null);
        insertItem(940, 1, "80.00");
        insertShipment(94001, 940, "SENT", "15.00", "2026-06-02");
        insertCharge(9400, 941, 940, "95.00", "2026-06-02", "OPV");
        insertCustomer(960);
        insertOrder(960, 960, null);
        insertItem(960, 1, "30.00");
        insertShipment(96001, 960, "DRAFT", "5.00", "2026-06-03");
        insertCharge(9600, 960, 960, "35.00", "2026-06-03", "OPV");
        syncEntrySequence();
        Map<Long, BigDecimal> before = balances();
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isZero();
        assertThat(orderIds(applied.output, "REVIEW")).as(applied.output).contains(930L, 940L, 960L);
        assertThat(applied.output).contains("orden cancelada", "la orden es de otro cliente", "envio en borrador");
        assertThat(count("SELECT count(*) FROM customer_account_entry WHERE description = '" + CHARGE_TAG + "'"))
                .isZero();
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 9300")).isEqualTo("ACTIVE");
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 9400")).isEqualTo("ACTIVE");
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 9600")).isEqualTo("ACTIVE");
        assertThat(money(9300)).isEqualByComparingTo("95.00");
        assertThat(money(9400)).isEqualByComparingTo("95.00");
        assertThat(money(9600)).isEqualByComparingTo("35.00");
        assertSameBalances(before, balances());
    }

    @Test
    void newRowsKeepDocumentFieldsAndShipmentPartialRelease() throws Exception {
        // Products 100, shipping 30 folded into 130. Balance stays 130.00.
        // The new charge keeps the document fields. The new adjustment also keeps the shipment's release.
        insertCustomer(130);
        insertOrder(130, 130, null);
        insertItem(130, 1, "100.00");
        insertShipment(13001, 130, "SENT", "30.00", "2026-07-01");
        exec("""
                INSERT INTO production_order_partial_release (id, production_order_id, sequence_num, status)
                VALUES (1304, 130, 1, 'DRAFT')
                """);
        exec("UPDATE product_shipment SET partial_release_id = 1304 WHERE id = 13001");
        exec("""
                INSERT INTO customer_account_entry (
                    id, customer_id, entry_type, entry_date, amount, production_order_id, status, order_kind,
                    document_number, invoice_number, vendor_shipment_number
                ) VALUES (
                    1300, 130, 'CHARGE', DATE '2026-07-01', 130.00, 130, 'ACTIVE', 'OPV',
                    '1301', '1302', '1303'
                )
                """);
        syncEntrySequence();
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isZero();
        assertThat(balances().get(130L)).isEqualByComparingTo("130.00");
        String newCharge = activeChargeId(130);
        assertDocumentFields(newCharge);
        assertThat(text("SELECT created_by::text FROM customer_account_entry WHERE id = " + newCharge)).isNull();
        String adjustment = text("""
                SELECT id::text FROM customer_account_entry
                WHERE production_order_id = 130 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """);
        assertThat(adjustment).isNotBlank();
        assertDocumentFields(adjustment);
        assertThat(text("SELECT partial_release_id::text FROM customer_account_entry WHERE id = " + adjustment))
                .isEqualTo("1304");
        assertThat(text("SELECT description FROM customer_account_entry WHERE id = " + adjustment)).isEqualTo(ADJ_TAG);
    }

    @Test
    void adjustmentPointingAtVoidChargeLeavesBalanceUnchanged() throws Exception {
        // Products 40, shipping 10, one active charge of 40. The matching adjustment still
        // points at an older VOID charge. Balance stays 50.00 on this run and the next.
        seedAdjustmentOnVoidCharge();
        assertThat(balances().get(880L)).isEqualByComparingTo("50.00");
        Psql first = cleanup("aplicar=si");
        assertThat(first.exitCode).as(first.output).isZero();
        assertThat(balances().get(880L)).isEqualByComparingTo("50.00");
        Psql second = cleanup("aplicar=si");
        assertThat(second.exitCode).as(second.output).isZero();
        assertThat(balances().get(880L)).isEqualByComparingTo("50.00");
    }

    @Test
    @Disabled("pending Baku follow-up: re-point adjustments from VOID charge")
    void BUG_adjustmentOnVoidChargeStaysToFixEveryRun() throws Exception {
        // Desired: the adjustment is re-pointed to the surviving active charge, so a second
        // run has nothing to fix and the balance stays 50.00. Today the order stays in PLAN
        // as a no-op and every re-run still counts 1 to fix.
        seedAdjustmentOnVoidCharge();
        Psql first = cleanup("aplicar=si");
        assertThat(first.exitCode).as(first.output).isZero();
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = 8802"))
                .isEqualTo("8800");
        assertThat(money(8802)).isEqualByComparingTo("10.00");
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 8800")).isEqualTo("ACTIVE");
        assertThat(text("SELECT status FROM customer_account_entry WHERE id = 8809")).isEqualTo("VOID");
        assertThat(balances().get(880L)).isEqualByComparingTo("50.00");
        Psql second = cleanup("aplicar=si");
        assertThat(second.exitCode).as(second.output).isZero();
        assertThat(second.output).containsPattern("RESUMEN\\s+\\|\\s+0\\s+\\|");
        assertThat(sectionRows(second.output, "PLAN")).noneMatch(row -> row.contains("880"));
        assertThat(balances().get(880L)).isEqualByComparingTo("50.00");
    }

    @Test
    void nullAndNonNumericSizePricesCountAsZero() throws Exception {
        // Sizes 40 and 41 are priced null and "no". Size 42 is 25 twice. Unit price 100 is not a fallback.
        // Products = 50. Charge 60 already includes shipping 10. Balance stays 60.00.
        insertCustomer(131);
        insertOrder(131, 131, null);
        exec("""
                INSERT INTO production_order_item (production_order_id, unit_price, sizes_data, unit_prices_json)
                VALUES (131, 100.00, '{"40": 1, "41": 1, "42": 2}', '{"40": null, "41": "no", "42": 25}')
                """);
        insertShipment(13101, 131, "SENT", "10.00", "2026-07-02");
        insertCharge(1310, 131, 131, "60.00", "2026-07-02", "OPV");
        syncEntrySequence();
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isZero();
        assertThat(money(Long.parseLong(activeChargeId(131)))).isEqualByComparingTo("50.00");
        assertThat(balances().get(131L)).isEqualByComparingTo("60.00");
    }

    @Test
    void dryRunRollsBackInsertedRows() throws Exception {
        // The id sequence is not transactional. A dry run may leave a gap and must leave no rows.
        seedMain();
        syncEntrySequence();
        String rows = snapshotTables();
        int entries = count("SELECT count(*) FROM customer_account_entry");
        Psql dry = cleanup();
        assertThat(dry.exitCode).as(dry.output).isZero();
        assertThat(snapshotTables()).isEqualTo(rows);
        assertThat(count("SELECT count(*) FROM customer_account_entry")).isEqualTo(entries);
    }

    @Test
    void danglingPartialReleaseStoresNullAndBalanceStays() throws Exception {
        // Shipment 98001 still stores partial_release_id 98001 after that release row is gone.
        // The shipment FK is absent, which is how such a row can exist. The ledger FK is not.
        // Products 100 + shipping 25 stay 125.00. The new adjustment stores partial_release_id null.
        dropShipmentPartialReleaseFk();
        insertCustomer(980);
        insertOrder(980, 980, null);
        insertItem(980, 1, "100.00");
        insertShipment(98001, 980, "SENT", "25.00", "2026-09-01");
        exec("UPDATE product_shipment SET partial_release_id = 98001 WHERE id = 98001");
        insertCharge(9800, 980, 980, "125.00", "2026-09-01", "OPV");
        syncEntrySequence();
        BigDecimal before = balances().get(980L);
        String rows = snapshotTables();
        Psql applied = cleanup("aplicar=si");
        assertThat(applied.exitCode).as(applied.output).isZero();
        assertThat(balances().get(980L)).isEqualByComparingTo(before);
        assertThat(balances().get(980L)).isEqualByComparingTo("125.00");
        assertThat(text("""
                SELECT partial_release_id::text FROM customer_account_entry
                WHERE production_order_id = 980 AND entry_type = 'CHARGE_ADJUSTMENT' AND status = 'ACTIVE'
                """)).isNull();
        assertThat(rows).isNotEqualTo(snapshotTables());
    }

    @Test
    void mismatchedAdjustmentGoesToReviewAndSecondRunFixesNothing() throws Exception {
        // Products 40, charge 40, shipping 15, existing adjustment 10. Debit stays 50.00.
        // REVIEW motivo is the exact string the script emits. A second run writes nothing
        // and counts zero orders to fix.
        insertCustomer(970);
        insertOrder(970, 970, null);
        insertItem(970, 1, "40.00");
        insertShipment(97001, 970, "SENT", "15.00", "2026-09-02");
        insertCharge(9700, 970, 970, "40.00", "2026-09-02", "OPV");
        exec("""
                INSERT INTO customer_account_entry (
                    id, customer_id, entry_type, entry_date, amount, description,
                    production_order_id, product_shipment_id, applied_to_entry_id, order_kind, status
                ) VALUES (
                    9701, 970, 'CHARGE_ADJUSTMENT', DATE '2026-09-03', 10.00, 'previo',
                    970, 97001, 9700, 'OPV', 'ACTIVE'
                )
                """);
        syncEntrySequence();
        String before = snapshotTables();
        Psql first = cleanup("aplicar=si");
        assertThat(first.exitCode).as(first.output).isZero();
        assertThat(sectionRows(first.output, "REVIEW")).as(first.output)
                .anyMatch(row -> row.contains("970") && row.contains("ajuste existente distinto al costo de envio"));
        assertThat(balances().get(970L)).isEqualByComparingTo("50.00");
        assertThat(money(9701)).isEqualByComparingTo("10.00");
        assertThat(snapshotTables()).isEqualTo(before);
        Psql second = cleanup("aplicar=si");
        assertThat(second.exitCode).as(second.output).isZero();
        assertThat(snapshotTables()).isEqualTo(before);
        assertThat(second.output).containsPattern("RESUMEN\\s+\\|\\s+0\\s+\\|");
        assertThat(sectionRows(second.output, "PLAN")).noneMatch(row -> row.contains("970"));
    }

    private void assertBalanceBug(long orderId, BigDecimal expectedBalance, String before, String after, String difference)
            throws Exception {
        Map<Long, BigDecimal> balancesBefore = balances();
        String rows = snapshotTables();
        Psql antes = snapshot("cxc-antes.csv");
        assertThat(antes.exitCode).as(antes.output).isZero();
        Psql applied = cleanup("aplicar=si");
        Psql despues = snapshot("cxc-despues.csv");
        Psql reconcile = psql("reconcile-cleanup-balances.sql");
        assertThat(balances().get(orderId))
                .as("customer %s balance. Expected %s unchanged (review before %s, after %s, difference %s). reconcile said:%n%s",
                        orderId, expectedBalance, before, after, difference, reconcile.output)
                .isEqualByComparingTo(expectedBalance);
        assertThat(applied.exitCode).as(applied.output).isZero();
        assertThat(sectionRows(applied.output, "REVIEW")).as(applied.output)
                .anyMatch(row -> row.contains(Long.toString(orderId))
                        && row.contains(before) && row.contains(after) && row.contains(difference)
                        && row.contains("cambiaria el saldo"));
        assertThat(snapshotTables()).as(applied.output).isEqualTo(rows);
        assertSameBalances(balancesBefore, balances());
        assertThat(despues.exitCode).isZero();
    }

    private void assertDocumentFields(String entryId) throws Exception {
        assertThat(text("SELECT document_number FROM customer_account_entry WHERE id = " + entryId)).isEqualTo("1301");
        assertThat(text("SELECT invoice_number FROM customer_account_entry WHERE id = " + entryId)).isEqualTo("1302");
        assertThat(text("SELECT vendor_shipment_number FROM customer_account_entry WHERE id = " + entryId)).isEqualTo("1303");
    }

    private void assertHandBalances(Map<Long, BigDecimal> actual) {
        assertThat(actual.keySet()).containsExactlyInAnyOrder(502L, 116L, 223L, 224L, 305L, 406L, 507L, 608L);
        assertThat(actual.get(502L)).isEqualByComparingTo(BALANCE_502);
        assertThat(actual.get(116L)).isEqualByComparingTo(BALANCE_116);
        assertThat(actual.get(223L)).isEqualByComparingTo(BALANCE_223);
        assertThat(actual.get(224L)).isEqualByComparingTo(BALANCE_224);
        assertThat(actual.get(305L)).isEqualByComparingTo(BALANCE_305);
        assertThat(actual.get(406L)).isEqualByComparingTo(BALANCE_406);
        assertThat(actual.get(507L)).isEqualByComparingTo(BALANCE_507);
        assertThat(actual.get(608L)).isEqualByComparingTo(BALANCE_608);
    }

    private static void assertSameBalances(Map<Long, BigDecimal> before, Map<Long, BigDecimal> after) {
        assertThat(after.keySet()).isEqualTo(before.keySet());
        before.forEach((id, value) -> assertThat(after.get(id)).as("customer " + id).isEqualByComparingTo(value));
    }

    private void assertOriginalChargeAmounts(Map<Long, BigDecimal> original) throws Exception {
        Map<Long, BigDecimal> after = chargeAmounts();
        original.forEach((id, amount) -> assertThat(after.get(id)).as("charge " + id).isEqualByComparingTo(amount));
    }

    private void assertRealAdjustments() throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT ps.production_order_id, ps.id, round(ps.shipping_cost, 2),
                            a.amount, a.applied_to_entry_id, a.production_order_id, a.order_kind, a.description
                     FROM product_shipment ps
                     LEFT JOIN customer_account_entry a
                       ON a.product_shipment_id = ps.id
                      AND a.entry_type = 'CHARGE_ADJUSTMENT'
                      AND a.status = 'ACTIVE'
                     WHERE ps.shipping_cost > 0
                       AND upper(trim(coalesce(ps.status, ''))) NOT IN ('VOID', 'CANCELLED', 'ANULADO', 'ANULADA')
                       AND ps.production_order_id <> 305
                     ORDER BY ps.id
                     """)) {
            int rows = 0;
            while (result.next()) {
                rows++;
                long orderId = result.getLong(1);
                assertThat(result.getBigDecimal(4)).as("shipment " + result.getLong(2)).isEqualByComparingTo(result.getBigDecimal(3));
                assertThat(result.getLong(5)).isPositive();
                assertThat(result.getLong(6)).isEqualTo(orderId);
                assertThat(result.getString(7)).isEqualTo("OPV");
                if (result.getLong(2) != 60801) {
                    assertThat(result.getString(8)).isEqualTo(ADJ_TAG);
                }
            }
            assertThat(rows).isEqualTo(5);
        }
    }

    private void assertMoved(long entryId, String survivorId, String firstOriginal) throws Exception {
        assertThat(text("SELECT applied_to_entry_id::text FROM customer_account_entry WHERE id = " + entryId))
                .isEqualTo(survivorId);
        assertThat(text("SELECT reassigned_from_entry_id::text FROM customer_account_entry WHERE id = " + entryId))
                .isEqualTo(firstOriginal);
        assertThat(text("SELECT production_order_id::text FROM customer_account_entry WHERE id = " + entryId)).isNotBlank();
        assertThat(text("SELECT order_kind FROM customer_account_entry WHERE id = " + entryId)).isEqualTo("OPV");
    }

    private static void assertPhase2Blockers(String output) {
        assertThat(output).contains(
                "Fase 2 sigue bloqueada: 1 orden(es) con mas de un cargo activo y 1 cargo(s) activo(s) sin orden.");
        assertThat(output).containsPattern("PHASE2_BLOCKERS\\s+\\|\\s+1\\s+\\|\\s+1\\s+\\|");
        assertThat(sectionRows(output, "PHASE2_BLOCKERS_ORDEN"))
                .anyMatch(row -> row.contains("305") && row.contains("3050,3051"));
        assertThat(sectionRows(output, "PHASE2_BLOCKERS_SIN_ORDEN")).anyMatch(row -> row.contains("5070"));
    }

    private void assertPhase2ObjectsPresent() throws Exception {
        assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'uq_cae_one_active_charge_per_order'")).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'chk_customer_account_entry_charge_order' AND convalidated
                """)).isEqualTo(1);
    }

    private void assertPhase2ObjectsAbsent() throws Exception {
        assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'uq_cae_one_active_charge_per_order'")).isZero();
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conname = 'chk_customer_account_entry_charge_order'"))
                .isZero();
    }

    private void seedMain() throws Exception {
        for (long id : List.of(502L, 116L, 223L, 224L, 305L, 406L, 507L, 608L)) {
            insertCustomer(id);
        }
        insertOrder(502, 502, null);
        insertOrder(116, 116, null);
        insertOrder(223, 223, null);
        insertOrder(224, 224, null);
        insertOrder(305, 305, null);
        insertOrder(406, 406, null);
        insertOrder(608, 608, null);

        insertItem(502, 2, "400.00");
        insertSizedItem(502, "{\"42\": 1, \"46\": 1, \"52\": 1}", "100.00");
        insertItem(116, 4, "100.00");
        insertItem(223, 3, "80.00");
        insertItem(224, 1, "100.00");
        insertItem(305, 2, "50.00");
        insertItem(406, 1, "50.00");
        insertItem(608, 3, "10.00");

        insertShipment(50201, 502, "SENT", "150.00", "2026-02-02");
        insertShipment(50202, 502, "SENT", null, "2026-02-03");
        insertShipment(50203, 502, "SENT", "0.00", "2026-02-04");
        // Any shipping_cost > 0 is billed, whatever the status. These rows have no cost.
        insertShipment(50204, 502, "VOID", null, "2026-02-05");
        insertShipment(50205, 502, "CANCELLED", null, "2026-02-06");
        insertShipment(50206, 502, "ANULADO", null, "2026-02-07");
        insertShipment(50207, 502, "ANULADA", null, "2026-02-08");
        insertShipment(11601, 116, "SENT", "25.00", "2026-02-01");
        insertShipment(22301, 223, "SENT", "20.00", "2026-03-02");
        insertShipment(30501, 305, "SENT", "10.00", "2026-01-03");
        insertShipment(40601, 406, "SENT", "10.00", "2026-01-03");
        insertShipment(60801, 608, "SENT", "5.00", "2026-01-02");
        insertShipment(60802, 608, "SENT", "0.00", "2026-01-03");
        insertShipment(60803, 608, "VOID", null, "2026-01-04");

        exec("""
                INSERT INTO customer_account_entry
                    (id, customer_id, entry_type, entry_date, amount, production_order_id, status, order_kind)
                VALUES
                    (5020, 502, 'CHARGE', DATE '2026-02-01', 1400.00, 502, 'ACTIVE', 'OPV'),
                    (1160, 116, 'CHARGE', DATE '2026-01-10', 400.00, 116, 'ACTIVE', 'OPV'),
                    (1161, 116, 'CHARGE', DATE '2026-02-01', 25.00, 116, 'ACTIVE', 'OPV'),
                    (2230, 223, 'CHARGE', DATE '2026-03-01', 240.00, 223, 'ACTIVE', 'OPV'),
                    (2231, 223, 'CHARGE', DATE '2026-03-15', 20.00, 223, 'ACTIVE', 'OPV'),
                    (2239, 223, 'CHARGE', DATE '2025-12-01', 15.00, 223, 'VOID', 'OPV'),
                    (2240, 224, 'CHARGE', DATE '2026-01-01', 100.00, 224, 'ACTIVE', 'OPV'),
                    (2241, 224, 'CHARGE', DATE '2026-01-02', 80.00, 224, 'VOID', 'OPV'),
                    (3050, 305, 'CHARGE', DATE '2026-01-01', 100.00, 305, 'ACTIVE', 'OPV'),
                    (3051, 305, 'CHARGE', DATE '2026-01-02', 500.00, 305, 'ACTIVE', 'OPV'),
                    (4060, 406, 'CHARGE', DATE '2026-01-01', 50.00, 406, 'ACTIVE', 'OPV'),
                    (4061, 406, 'CHARGE', DATE '2026-01-02', 10.00, 406, 'ACTIVE', 'OPV'),
                    (5070, 507, 'CHARGE', DATE '2026-01-01', 999.00, NULL, 'ACTIVE', NULL),
                    (6080, 608, 'CHARGE', DATE '2026-01-01', 30.00, 608, 'ACTIVE', 'OPV')
                """);
        exec("UPDATE customer_account_entry SET void_reason = 'PREVIO' WHERE id IN (2239, 2241)");
        exec("""
                INSERT INTO customer_account_entry (
                    id, customer_id, entry_type, entry_date, amount, description,
                    production_order_id, product_shipment_id, applied_to_entry_id, order_kind, status
                ) VALUES (
                    6081, 608, 'CHARGE_ADJUSTMENT', DATE '2026-01-02', 5.00, 'previo',
                    608, 60801, 6080, 'OPV', 'ACTIVE'
                )
                """);
        exec("""
                INSERT INTO customer_account_entry (
                    id, customer_id, entry_type, entry_date, amount, description,
                    production_order_id, applied_to_entry_id, reassigned_from_entry_id,
                    order_kind, status, gross_collected_amount
                ) VALUES
                    (5021, 502, 'PAYMENT', DATE '2026-02-10', 200.00, '5021', 502, 5020, NULL, NULL, 'ACTIVE', NULL),
                    (1162, 116, 'PAYMENT', DATE '2026-02-11', 100.00, '1162', 116, 1160, NULL, 'OPV', 'ACTIVE', NULL),
                    (1163, 116, 'PAYMENT', DATE '2026-02-12', 150.00, '1163', 116, 1161, NULL, NULL, 'ACTIVE', NULL),
                    (2232, 223, 'CREDIT_NOTE', DATE '2026-03-20', 30.00, '2232', 223, 2231, NULL, NULL, 'ACTIVE', NULL),
                    (2233, 223, 'RETURN', DATE '2026-03-21', 10.00, '2233', 223, 2231, NULL, NULL, 'ACTIVE', NULL),
                    (2234, 223, 'PAYMENT', DATE '2026-03-22', 40.00, '2234', 223, 2231, 2239, NULL, 'ACTIVE', NULL),
                    (2242, 224, 'PAYMENT', DATE '2026-01-20', 35.00, '2242', 224, 2241, NULL, 'OPV', 'ACTIVE', NULL),
                    (4062, 406, 'PAYMENT', DATE '2026-01-20', 70.00, '4062', 406, 4060, NULL, 'OPV', 'ACTIVE', 80.00),
                    (6082, 608, 'PAYMENT', DATE '2026-01-20', 12.00, '6082', 608, 6080, NULL, 'OPV', 'ACTIVE', NULL)
                """);
        syncEntrySequence();
    }

    private void seedAdjustmentOnVoidCharge() throws Exception {
        insertCustomer(880);
        insertOrder(880, 880, null);
        insertItem(880, 1, "40.00");
        insertShipment(88001, 880, "SENT", "10.00", "2026-09-04");
        insertCharge(8800, 880, 880, "40.00", "2026-09-04", "OPV");
        exec("""
                INSERT INTO customer_account_entry
                    (id, customer_id, entry_type, entry_date, amount, production_order_id, status, order_kind, void_reason)
                VALUES (8809, 880, 'CHARGE', DATE '2026-08-01', 10.00, 880, 'VOID', 'OPV', 'PREVIO')
                """);
        exec("""
                INSERT INTO customer_account_entry (
                    id, customer_id, entry_type, entry_date, amount, description,
                    production_order_id, product_shipment_id, applied_to_entry_id, order_kind, status
                ) VALUES (
                    8802, 880, 'CHARGE_ADJUSTMENT', DATE '2026-09-04', 10.00, 'previo',
                    880, 88001, 8809, 'OPV', 'ACTIVE'
                )
                """);
        syncEntrySequence();
    }

    private void seedFolded(long id, String qty, String unitPrice, String charge, String shipping, String existingAdjustment)
            throws Exception {
        insertCustomer(id);
        insertOrder(id, id, null);
        insertItem(id, Integer.parseInt(qty), unitPrice);
        insertShipment(id * 100 + 1, id, "SENT", shipping, "2026-08-01");
        insertCharge(id * 10, id, id, charge, "2026-08-01", "OPV");
        if (existingAdjustment != null) {
            exec("""
                    INSERT INTO customer_account_entry (
                        id, customer_id, entry_type, entry_date, amount, description,
                        production_order_id, product_shipment_id, applied_to_entry_id, order_kind, status
                    ) VALUES (%d, %d, 'CHARGE_ADJUSTMENT', DATE '2026-08-02', %s, 'previo', %d, %d, %d, 'OPV', 'ACTIVE')
                    """.formatted(id * 10 + 1, id, existingAdjustment, id, id * 100 + 1, id * 10));
        }
        syncEntrySequence();
    }

    private void seedOverpaidPair() throws Exception {
        insertCustomer(406);
        insertOrder(406, 406, null);
        insertItem(406, 1, "50.00");
        insertShipment(40601, 406, "SENT", "10.00", "2026-01-03");
        insertCharge(4060, 406, 406, "50.00", "2026-01-01", "OPV");
        insertCharge(4061, 406, 406, "10.00", "2026-01-02", "OPV");
        exec("""
                INSERT INTO customer_account_entry (
                    id, customer_id, entry_type, entry_date, amount, production_order_id,
                    applied_to_entry_id, order_kind, status, gross_collected_amount
                ) VALUES (4062, 406, 'PAYMENT', DATE '2026-01-20', 70.00, 406, 4060, 'OPV', 'ACTIVE', 80.00)
                """);
        insertCustomer(850);
        insertOrder(850, 850, null);
        insertItem(850, 1, "40.00");
        insertShipment(85001, 850, "SENT", "10.00", "2026-01-02");
        insertCharge(8500, 850, 850, "40.00", "2026-01-01", "OPV");
        exec("""
                INSERT INTO customer_account_entry (
                    id, customer_id, entry_type, entry_date, amount, description,
                    production_order_id, product_shipment_id, applied_to_entry_id, order_kind, status
                ) VALUES (
                    8501, 850, 'CHARGE_ADJUSTMENT', DATE '2026-01-02', 10.00, 'previo',
                    850, 85001, 8500, 'OPV', 'ACTIVE'
                )
                """);
        exec("""
                INSERT INTO customer_account_entry (
                    id, customer_id, entry_type, entry_date, amount, production_order_id,
                    applied_to_entry_id, order_kind, status, gross_collected_amount
                ) VALUES (8502, 850, 'PAYMENT', DATE '2026-01-20', 60.00, 850, 8500, 'OPV', 'ACTIVE', 70.00)
                """);
        syncEntrySequence();
    }

    private void insertCustomer(long id) throws Exception {
        exec("INSERT INTO customer (id, legacy_code, credit_days) VALUES (" + id + ", '" + id + "', 0)");
    }

    private void insertOrder(long id, long customerId, String status) throws Exception {
        exec("""
                INSERT INTO production_order (id, code, order_type, customer_id, seller_name, status)
                VALUES (%d, 'OP-%d', 'NORMAL', %d, '%s', %s)
                """.formatted(id, id, customerId, SELLER, status == null ? "NULL" : "'" + status + "'"));
    }

    private void insertItem(long orderId, int quantity, String unitPrice) throws Exception {
        exec("""
                INSERT INTO production_order_item (production_order_id, quantity, unit_price)
                VALUES (%d, %d, %s)
                """.formatted(orderId, quantity, unitPrice));
    }

    private void insertSizedItem(long orderId, String sizesJson, String unitPrice) throws Exception {
        exec("""
                INSERT INTO production_order_item (production_order_id, unit_price, sizes_data)
                VALUES (%d, %s, '%s')
                """.formatted(orderId, unitPrice, sizesJson));
    }

    private void insertShipment(long id, long orderId, String status, String cost, String sentAt) throws Exception {
        exec("""
                INSERT INTO product_shipment
                    (id, production_order_id, shipment_number, status, shipping_cost, sent_at)
                VALUES (%d, %d, 'S%d', '%s', %s, TIMESTAMP '%s')
                """.formatted(id, orderId, id, status, cost == null ? "NULL" : cost, sentAt));
    }

    private void insertCharge(long id, long customerId, long orderId, String amount, String entryDate, String kind)
            throws Exception {
        exec("""
                INSERT INTO customer_account_entry
                    (id, customer_id, entry_type, entry_date, amount, production_order_id, status, order_kind)
                VALUES (%d, %d, 'CHARGE', DATE '%s', %s, %d, 'ACTIVE', '%s')
                """.formatted(id, customerId, entryDate, amount, orderId, kind));
    }

    private void dropShipmentPartialReleaseFk() throws Exception {
        exec("""
                DO $$
                DECLARE
                    constraint_name text;
                BEGIN
                    FOR constraint_name IN
                        SELECT c.conname
                        FROM pg_constraint c
                        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                        WHERE c.conrelid = 'product_shipment'::regclass
                          AND c.contype = 'f'
                          AND a.attname = 'partial_release_id'
                    LOOP
                        EXECUTE format('ALTER TABLE product_shipment DROP CONSTRAINT %I', constraint_name);
                    END LOOP;
                END $$
                """);
    }

    private void syncEntrySequence() throws Exception {
        exec("""
                SELECT setval(
                    pg_get_serial_sequence('customer_account_entry', 'id'),
                    (SELECT max(id) FROM customer_account_entry),
                    true)
                """);
    }

    private static void exportJpaSchema() throws Exception {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, POSTGRES.getJdbcUrl())
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, POSTGRES.getUsername())
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, POSTGRES.getPassword())
                .applySetting(AvailableSettings.JAKARTA_JDBC_DRIVER, "org.postgresql.Driver")
                .applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create")
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            int entities = 0;
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource resource : resolver.getResources(
                    "classpath*:com/fossiles/fossilescorebackend/infrastructure/persistence/entity/*.class")) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".class") || filename.contains("$")) {
                    continue;
                }
                Class<?> type = Class.forName(
                        "com.fossiles.fossilescorebackend.infrastructure.persistence.entity."
                                + filename.substring(0, filename.length() - 6));
                if (type.isAnnotationPresent(Entity.class)) {
                    sources.addAnnotatedClass(type);
                    entities++;
                }
            }
            assertThat(entities).isGreaterThan(10);
            Metadata metadata = sources.buildMetadata();
            try (SessionFactory factory = metadata.getSessionFactoryBuilder().build()) {
                assertThat(factory).isNotNull();
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static void truncateLedger() throws Exception {
        execStatic("TRUNCATE TABLE " + String.join(", ", LEDGER_TABLES) + " RESTART IDENTITY CASCADE");
    }

    private static String runSnapshot(Path directory, String out) throws Exception {
        Psql snapshot = psqlStatic(directory, "snapshot-saldos-cxc.sql", "out=" + out, "void_tag=", "adj_tag=", "charge_tag=");
        assertThat(snapshot.exitCode).as(snapshot.output).isZero();
        return Files.readString(directory.resolve(out));
    }

    private Psql snapshot(String out) throws Exception {
        return psql("snapshot-saldos-cxc.sql",
                "out=" + out,
                "void_tag=" + VOID_TAG,
                "adj_tag=" + ADJ_TAG,
                "charge_tag=" + CHARGE_TAG);
    }

    private Psql cleanup(String... variables) throws Exception {
        return cleanup(60, variables);
    }

    private Psql cleanup(long timeoutSeconds, String... variables) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("cleanup-customer-account-lf-duplicates.sql");
        for (String variable : variables) {
            args.add(variable);
        }
        return psql(timeoutSeconds, args.toArray(String[]::new));
    }

    private Psql psql(String script, String... variables) throws Exception {
        List<String> args = new ArrayList<>();
        args.add(script);
        for (String variable : variables) {
            args.add(variable);
        }
        return psql(60, args.toArray(String[]::new));
    }

    private Psql psql(long timeoutSeconds, String[] scriptAndVariables) throws Exception {
        return psqlStatic(work, timeoutSeconds, scriptAndVariables);
    }

    private static Psql psqlStatic(Path directory, String script, String... variables) throws Exception {
        List<String> args = new ArrayList<>();
        args.add(script);
        for (String variable : variables) {
            args.add(variable);
        }
        return psqlStatic(directory, 60, args.toArray(String[]::new));
    }

    private static Psql psqlStatic(Path directory, long timeoutSeconds, String[] scriptAndVariables) throws Exception {
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
        for (int i = 1; i < scriptAndVariables.length; i++) {
            command.add("-v");
            command.add(scriptAndVariables[i]);
        }
        command.add("-f");
        command.add(SCRIPTS.resolve(scriptAndVariables[0]).toAbsolutePath().toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PGPASSWORD", POSTGRES.getPassword());
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            String output = new String(process.getInputStream().readAllBytes());
            return new Psql(-1, output + "\npsql timed out");
        }
        return new Psql(process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }

    private Map<Long, BigDecimal> balances() throws Exception {
        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT customer_id,
                            round(COALESCE(sum(
                                CASE
                                    WHEN upper(coalesce(status, '')) <> 'ACTIVE' THEN 0
                                    WHEN upper(entry_type) IN ('CHARGE', 'OPENING_BALANCE', 'CHARGE_ADJUSTMENT')
                                        THEN COALESCE(amount, 0)
                                    WHEN upper(entry_type) IN ('PAYMENT', 'CREDIT_NOTE', 'RETURN') THEN
                                        -1 * CASE
                                            WHEN gross_collected_amount > 0 THEN gross_collected_amount
                                            WHEN payment_discount_amount > 0
                                                THEN COALESCE(amount, 0) + payment_discount_amount
                                            ELSE COALESCE(amount, 0)
                                        END
                                    ELSE 0
                                END
                            ), 0), 2)
                     FROM customer_account_entry
                     GROUP BY customer_id
                     ORDER BY customer_id
                     """)) {
            while (result.next()) {
                balances.put(result.getLong(1), result.getBigDecimal(2));
            }
        }
        return balances;
    }

    private Map<Long, BigDecimal> chargeAmounts() throws Exception {
        Map<Long, BigDecimal> amounts = new LinkedHashMap<>();
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT id, amount FROM customer_account_entry
                     WHERE entry_type = 'CHARGE' ORDER BY id
                     """)) {
            while (result.next()) {
                amounts.put(result.getLong(1), result.getBigDecimal(2));
            }
        }
        return amounts;
    }

    private String snapshotTables(String... only) throws Exception {
        return snapshotTablesStatic(only);
    }

    private static String snapshotTablesStatic(String... only) throws Exception {
        List<String> tables = only.length == 0 ? LEDGER_TABLES : List.of(only);
        StringBuilder snapshot = new StringBuilder();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            for (String table : tables) {
                snapshot.append('#').append(table).append('\n');
                try (ResultSet result = statement.executeQuery(
                        "SELECT row_to_json(t)::text FROM " + table + " t ORDER BY 1")) {
                    while (result.next()) {
                        snapshot.append(result.getString(1)).append('\n');
                    }
                }
            }
        }
        return snapshot.toString();
    }

    private int activeChargeCount(long orderId) throws Exception {
        return count("""
                SELECT count(*) FROM customer_account_entry
                WHERE production_order_id = %d AND entry_type = 'CHARGE' AND status = 'ACTIVE'
                """.formatted(orderId));
    }

    private int adjustmentCount(long orderId) throws Exception {
        return count("""
                SELECT count(*) FROM customer_account_entry
                WHERE production_order_id = %d AND entry_type = 'CHARGE_ADJUSTMENT'
                """.formatted(orderId));
    }

    private String activeChargeId(long orderId) throws Exception {
        return text("""
                SELECT id::text FROM customer_account_entry
                WHERE production_order_id = %d AND entry_type = 'CHARGE' AND status = 'ACTIVE'
                """.formatted(orderId));
    }

    private static List<Long> orderIds(String output, String section) {
        List<Long> ids = new ArrayList<>();
        Matcher matcher = Pattern.compile(section + "\\s+\\|\\s+(\\d+)").matcher(output);
        while (matcher.find()) {
            ids.add(Long.valueOf(matcher.group(1)));
        }
        return ids;
    }

    private static List<String> sectionRows(String output, String section) {
        return output.lines().filter(line -> line.matches("\\s*" + section + "\\s+\\|.*")).toList();
    }

    private void exec(String sql) throws Exception {
        execStatic(sql);
    }

    private static void execStatic(String sql) throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int count(String sql) throws Exception {
        return countStatic(sql);
    }

    private static int countStatic(String sql) throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private long longOf(String sql) throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private BigDecimal money(long id) throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT amount FROM customer_account_entry WHERE id = " + id)) {
            result.next();
            return result.getBigDecimal(1);
        }
    }

    private String text(String sql) throws Exception {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                return null;
            }
            return result.getString(1);
        }
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record Psql(int exitCode, String output) {}
}
