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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1, phase 2, and both rollbacks on throwaway PostgreSQL.
 * Scripts run with {@code psql -v ON_ERROR_STOP=1 -f} inside the container, so the first
 * error aborts the script and an open transaction rolls back with the session.
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
        deleteEntries();
        LfMigrationScripts.apply(POSTGRES, ROLLBACK2);
        LfMigrationScripts.apply(POSTGRES, PHASE1);
        LfMigrationScripts.apply(POSTGRES, PHASE1);

        long customerId = insertCustomer("Mig", 0);
        long chargeId = insertEntry(customerId, "CHARGE", "10.00", 501L, null, null, null, null, "ACTIVE");
        insertEntry(customerId, "CHARGE_ADJUSTMENT", "5.00", 501L, 9001L, chargeId, null, null, "ACTIVE");
        assertThatThrownBy(() -> insertEntry(customerId, "NOT_A_TYPE", "5.00", null, null, null, null, null, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(messages(error)).contains("chk_customer_account_entry_type"));
        deleteEntries();
        assertThat(indexCount("uq_cae_one_active_charge_per_order")).isZero();
        assertThat(indexCount("uq_cae_one_active_adjustment_per_shipment")).isEqualTo(1);

        LfMigrationScripts.apply(POSTGRES, PHASE2);
        LfMigrationScripts.apply(POSTGRES, PHASE2);
        assertThat(indexCount("uq_cae_one_active_charge_per_order")).isEqualTo(1);

        LfMigrationScripts.apply(POSTGRES, ROLLBACK2);
        LfMigrationScripts.apply(POSTGRES, ROLLBACK2);
        assertThat(indexCount("uq_cae_one_active_charge_per_order")).isZero();
        assertThat(constraintDefinition("chk_customer_account_entry_charge_order")).isNull();

        chargeId = insertEntry(customerId, "CHARGE", "10.00", 502L, null, null, null, null, "ACTIVE");
        insertEntry(customerId, "CHARGE_ADJUSTMENT", "5.00", 502L, 9002L, chargeId, null, null, "ACTIVE");
        deleteEntries();

        LfMigrationScripts.apply(POSTGRES, ROLLBACK1);
        LfMigrationScripts.apply(POSTGRES, ROLLBACK1);
        assertThat(indexCount("uq_cae_one_active_adjustment_per_shipment")).isZero();
        assertThat(columnCount("customer", "credit_days")).isZero();
        assertThat(constraintDefinition("chk_customer_account_entry_type")).doesNotContain("CHARGE_ADJUSTMENT");
        assertThatThrownBy(() -> insertEntry(customerId, "CHARGE_ADJUSTMENT", "5.00", 502L, 9002L, null, null, null, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(messages(error)).contains("chk_customer_account_entry_type"));
    }

    /**
     * Phase 1 rollback must refuse to run while a CHARGE_ADJUSTMENT row exists.
     * It stops with a clear message, rolls the transaction back, and leaves the row
     * and the phase-1 checks in place.
     */
    @Test
    void phase1RollbackWithAdjustmentRowRollsBackCleanly() throws Exception {
        deleteEntries();
        LfMigrationScripts.apply(POSTGRES, ROLLBACK2);
        LfMigrationScripts.apply(POSTGRES, PHASE1);

        long customerId = insertCustomer("Rollback", 30);
        long chargeId = insertEntry(customerId, "CHARGE", "10.00", 88L, null, null, null, null, "ACTIVE");
        insertEntry(customerId, "CHARGE_ADJUSTMENT", "5.00", 88L, 9L, chargeId, null, null, "ACTIVE");
        List<String> rowsBefore = ledgerRows();
        String typeCheck = constraintDefinition("chk_customer_account_entry_type");
        String linksCheck = constraintDefinition("chk_customer_account_entry_adjustment_links");

        LfMigrationScripts.PsqlResult rollback = LfMigrationScripts.run(POSTGRES, ROLLBACK1);

        assertThat(rollback.exitCode()).as(rollback.output()).isNotZero();
        assertThat(rollback.output())
                .contains("ROLLBACK FASE 1 abortado")
                .contains("CHARGE_ADJUSTMENT")
                .contains("No se cambio nada");
        assertThat(rollback.output().toLowerCase()).doesNotContain("violat");
        assertThat(ledgerRows()).isEqualTo(rowsBefore);
        assertThat(entryCount("CHARGE_ADJUSTMENT")).isEqualTo(1);
        assertThat(constraintDefinition("chk_customer_account_entry_type")).isEqualTo(typeCheck).contains("CHARGE_ADJUSTMENT");
        assertThat(constraintDefinition("chk_customer_account_entry_adjustment_links")).isEqualTo(linksCheck);
        assertThat(indexCount("uq_cae_one_active_adjustment_per_shipment")).isEqualTo(1);
        assertThat(columnCount("customer", "credit_days")).isEqualTo(1);
        assertThat(creditDays(customerId)).isEqualTo(30);
        assertThat(columnCount("customer_account_entry", "reassigned_from_entry_id")).isEqualTo(1);
    }

    @Test
    void phase1RollbackWithReassignedTraceChangesNothing() throws Exception {
        deleteEntries();
        LfMigrationScripts.apply(POSTGRES, ROLLBACK2);
        LfMigrationScripts.apply(POSTGRES, PHASE1);

        long customerId = insertCustomer("Trace", 12);
        long chargeId = insertEntry(customerId, "CHARGE", "40.00", 41L, null, null, null, null, "ACTIVE");
        long paymentId = insertEntry(customerId, "PAYMENT", "4.00", 41L, null, chargeId, "4.00", null, "ACTIVE");
        jdbc.update(
                "UPDATE customer_account_entry SET reassigned_from_entry_id = ? WHERE id = ?",
                chargeId, paymentId);
        List<String> rowsBefore = ledgerRows();
        String fk = constraintDefinition("fk_customer_account_entry_reassigned_from");
        String typeCheck = constraintDefinition("chk_customer_account_entry_type");

        LfMigrationScripts.PsqlResult rollback = LfMigrationScripts.run(POSTGRES, ROLLBACK1);

        assertThat(rollback.exitCode()).as(rollback.output()).isNotZero();
        assertThat(rollback.output())
                .contains("ROLLBACK FASE 1 abortado")
                .contains("reassigned_from_entry_id")
                .contains("No se cambio nada");
        assertThat(rollback.output().toLowerCase()).doesNotContain("violat");
        assertThat(entryCount("CHARGE_ADJUSTMENT")).isZero();
        assertThat(ledgerRows()).isEqualTo(rowsBefore);
        assertThat(reassignedFrom(paymentId)).isEqualTo(chargeId);
        assertThat(columnCount("customer_account_entry", "reassigned_from_entry_id")).isEqualTo(1);
        assertThat(constraintDefinition("fk_customer_account_entry_reassigned_from")).isEqualTo(fk).contains("reassigned_from_entry_id");
        assertThat(constraintDefinition("chk_customer_account_entry_type")).isEqualTo(typeCheck);
        assertThat(columnCount("customer", "credit_days")).isEqualTo(1);
        assertThat(creditDays(customerId)).isEqualTo(12);
    }

    @Test
    void phase1RollbackRemovesReassignedColumnWhenNothingPointsAtIt() throws Exception {
        deleteEntries();
        LfMigrationScripts.apply(POSTGRES, ROLLBACK2);
        LfMigrationScripts.apply(POSTGRES, PHASE1);

        long customerId = insertCustomer("Clear", 0);
        long chargeId = insertEntry(customerId, "CHARGE", "15.00", 42L, null, null, null, null, "ACTIVE");
        long paymentId = insertEntry(customerId, "PAYMENT", "3.00", 42L, null, chargeId, "3.00", null, "ACTIVE");
        assertThat(entryCount("CHARGE_ADJUSTMENT")).isZero();
        assertThat(reassignedFrom(paymentId)).isNull();

        LfMigrationScripts.apply(POSTGRES, ROLLBACK1);

        assertThat(columnCount("customer_account_entry", "reassigned_from_entry_id")).isZero();
        assertThat(constraintDefinition("fk_customer_account_entry_reassigned_from")).isNull();
        assertThat(entryCount("CHARGE")).isEqualTo(1);
        assertThat(entryCount("PAYMENT")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM customer_account_entry WHERE id = ?", BigDecimal.class, chargeId))
                .isEqualByComparingTo("15.00");
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM customer_account_entry WHERE id = ?", BigDecimal.class, paymentId))
                .isEqualByComparingTo("3.00");
    }

    @Test
    void phase2WithDuplicateActiveChargesChangesNothing() throws Exception {
        deleteEntries();
        LfMigrationScripts.apply(POSTGRES, ROLLBACK2);
        LfMigrationScripts.apply(POSTGRES, PHASE1);
        assertThat(constraintDefinition("chk_customer_account_entry_charge_order")).isNull();
        assertThat(indexCount("uq_cae_one_active_charge_per_order")).isZero();

        long customerId = insertCustomer("Duplicates", 0);
        insertEntry(customerId, "CHARGE", "10.00", 77L, null, null, null, null, "ACTIVE");
        insertEntry(customerId, "CHARGE", "20.00", 77L, null, null, null, null, "ACTIVE");
        List<String> rowsBefore = ledgerRows();
        String typeCheck = constraintDefinition("chk_customer_account_entry_type");

        LfMigrationScripts.PsqlResult phase2 = LfMigrationScripts.run(POSTGRES, PHASE2);

        assertThat(phase2.exitCode()).as(phase2.output()).isNotZero();
        assertThat(phase2.output()).contains("FASE 2 abortada").contains("No se cambio nada");
        assertThat(ledgerRows()).isEqualTo(rowsBefore);
        assertThat(entryCount("CHARGE")).isEqualTo(2);
        assertThat(constraintDefinition("chk_customer_account_entry_charge_order")).isNull();
        assertThat(indexCount("uq_cae_one_active_charge_per_order")).isZero();
        assertThat(constraintDefinition("chk_customer_account_entry_type")).isEqualTo(typeCheck);
        assertThat(indexCount("uq_cae_one_active_adjustment_per_shipment")).isEqualTo(1);
    }

    @Test
    void phase1AdjustmentMustLinkOrderShipmentAndParentCharge() throws Exception {
        deleteEntries();
        LfMigrationScripts.apply(POSTGRES, ROLLBACK2);
        LfMigrationScripts.apply(POSTGRES, PHASE1);

        long customerId = insertCustomer("Links", 0);
        long chargeId = insertEntry(customerId, "CHARGE", "40.00", 15L, null, null, null, null, "ACTIVE");

        assertThatThrownBy(() -> insertEntry(
                customerId, "CHARGE_ADJUSTMENT", "1.00", null, 1L, chargeId, null, null, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(messages(error)).contains("chk_customer_account_entry_adjustment_links"));
        assertThatThrownBy(() -> insertEntry(
                customerId, "CHARGE_ADJUSTMENT", "1.00", 15L, null, chargeId, null, null, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(messages(error)).contains("chk_customer_account_entry_adjustment_links"));
        assertThatThrownBy(() -> insertEntry(
                customerId, "CHARGE_ADJUSTMENT", "1.00", 15L, 3L, null, null, null, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(messages(error)).contains("chk_customer_account_entry_adjustment_links"));

        long adjustmentId = insertEntry(
                customerId, "CHARGE_ADJUSTMENT", "7.50", 15L, 4L, chargeId, null, null, "ACTIVE");
        assertThat(entryCount("CHARGE_ADJUSTMENT")).isEqualTo(1);
        assertThat(appliedTo(adjustmentId)).isEqualTo(chargeId);
        assertThat(jdbc.queryForObject(
                "SELECT production_order_id FROM customer_account_entry WHERE id = ?",
                Long.class, adjustmentId)).isEqualTo(15L);
        assertThat(jdbc.queryForObject(
                "SELECT product_shipment_id FROM customer_account_entry WHERE id = ?",
                Long.class, adjustmentId)).isEqualTo(4L);
    }

    @Test
    void phase1LeavesPerCustomerBalancesUnchanged() throws Exception {
        deleteEntries();
        LfMigrationScripts.apply(POSTGRES, ROLLBACK2);
        LfMigrationScripts.apply(POSTGRES, ROLLBACK1);
        jdbc.update("DELETE FROM customer");

        long grossCustomer = insertCustomer("Gross", null);
        insertEntry(grossCustomer, "CHARGE", "1000.00", 1L, null, null, null, null, "ACTIVE");
        insertEntry(grossCustomer, "OPENING_BALANCE", "500.00", null, null, null, null, null, "ACTIVE");
        insertEntry(grossCustomer, "PAYMENT", "80.00", null, null, null, "100.00", "20.00", "ACTIVE");
        insertEntry(grossCustomer, "CREDIT_NOTE", "30.00", null, null, null, null, null, "ACTIVE");
        insertEntry(grossCustomer, "RETURN", "20.00", null, null, null, "0.00", "0.00", "ACTIVE");
        insertEntry(grossCustomer, "PAYMENT", "999.00", null, null, null, "999.00", null, "VOID");

        long discountCustomer = insertCustomer("Discount", null);
        insertEntry(discountCustomer, "CHARGE", "400.00", 2L, null, null, null, null, "ACTIVE");
        insertEntry(discountCustomer, "PAYMENT", "50.00", null, null, null, null, "10.00", "ACTIVE");
        insertEntry(discountCustomer, "PAYMENT", "25.00", null, null, null, "0.00", "5.00", "ACTIVE");

        long emptyCustomer = insertCustomer("Empty", null);
        long voidCustomer = insertCustomer("VoidOnly", null);
        insertEntry(voidCustomer, "CHARGE", "700.00", 3L, null, null, null, null, "VOID");

        Map<Long, BigDecimal> expected = new LinkedHashMap<>();
        expected.put(grossCustomer, money("1350.00"));
        expected.put(discountCustomer, money("310.00"));
        expected.put(emptyCustomer, money("0.00"));
        expected.put(voidCustomer, money("0.00"));

        Map<Long, BigDecimal> before = balances();
        assertThat(before).isEqualTo(expected);

        LfMigrationScripts.apply(POSTGRES, PHASE1);

        assertThat(balances()).isEqualTo(expected).isEqualTo(before);
        assertThat(constraintDefinition("chk_customer_account_entry_type")).contains("CHARGE_ADJUSTMENT");
    }

    private void deleteEntries() {
        if (columnCount("customer_account_entry", "reassigned_from_entry_id") == 1) {
            jdbc.update("UPDATE customer_account_entry SET reassigned_from_entry_id = NULL");
        }
        jdbc.update("DELETE FROM customer_account_entry");
    }

    private long insertCustomer(String name, Integer creditDays) {
        if (columnCount("customer", "credit_days") == 1) {
            return jdbc.queryForObject(
                    "INSERT INTO customer (name, status, credit_days) VALUES (?, 'ACTIVE', ?) RETURNING id",
                    Long.class, name, creditDays == null ? 0 : creditDays);
        }
        return jdbc.queryForObject(
                "INSERT INTO customer (name, status) VALUES (?, 'ACTIVE') RETURNING id",
                Long.class, name);
    }

    private long insertEntry(
            long customerId,
            String entryType,
            String amount,
            Long productionOrderId,
            Long productShipmentId,
            Long appliedToEntryId,
            String grossCollected,
            String paymentDiscount,
            String status) {
        Long id = jdbc.queryForObject("""
                INSERT INTO customer_account_entry (
                    customer_id, entry_type, entry_date, amount, status,
                    production_order_id, product_shipment_id, applied_to_entry_id,
                    gross_collected_amount, payment_discount_amount)
                VALUES (?, ?, DATE '2026-09-01', ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class,
                customerId, entryType, new BigDecimal(amount), status,
                productionOrderId, productShipmentId, appliedToEntryId,
                grossCollected == null ? null : new BigDecimal(grossCollected),
                paymentDiscount == null ? null : new BigDecimal(paymentDiscount));
        return id;
    }

    /**
     * Per-customer balance using the same rule as
     * {@code CustomerAccountService.resolveAppliedCreditAmount}: gross collected when
     * positive, otherwise amount plus a positive payment discount, otherwise amount.
     * Debits are CHARGE, OPENING_BALANCE, and CHARGE_ADJUSTMENT. Void rows are skipped.
     */
    private Map<Long, BigDecimal> balances() {
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        for (Long customerId : jdbc.queryForList("SELECT id FROM customer ORDER BY id", Long.class)) {
            totals.put(customerId, money("0.00"));
        }
        jdbc.query("""
                SELECT customer_id, entry_type, status, amount,
                       gross_collected_amount, payment_discount_amount
                FROM customer_account_entry
                ORDER BY customer_id, id
                """, rs -> {
            if (!"ACTIVE".equalsIgnoreCase(rs.getString("status"))) {
                return;
            }
            long customerId = rs.getLong("customer_id");
            BigDecimal signed = signedAmount(
                    rs.getString("entry_type"),
                    rs.getBigDecimal("amount"),
                    rs.getBigDecimal("gross_collected_amount"),
                    rs.getBigDecimal("payment_discount_amount"));
            totals.put(customerId, totals.getOrDefault(customerId, money("0.00")).add(signed));
        });
        totals.replaceAll((id, balance) -> balance.setScale(2, RoundingMode.HALF_UP));
        return totals;
    }

    private static BigDecimal signedAmount(
            String entryType, BigDecimal amount, BigDecimal gross, BigDecimal discount) {
        if (isDebit(entryType)) {
            return money(amount);
        }
        if (isCredit(entryType)) {
            return appliedCredit(amount, gross, discount).negate();
        }
        return money("0.00");
    }

    private static BigDecimal appliedCredit(BigDecimal amount, BigDecimal gross, BigDecimal discount) {
        if (gross != null && gross.compareTo(BigDecimal.ZERO) > 0) {
            return money(gross);
        }
        BigDecimal net = amount == null ? BigDecimal.ZERO : amount;
        BigDecimal discountAmount = discount == null ? BigDecimal.ZERO : discount;
        if (discountAmount.compareTo(BigDecimal.ZERO) > 0) {
            return money(net.add(discountAmount));
        }
        return money(net);
    }

    private static boolean isDebit(String entryType) {
        return "CHARGE".equalsIgnoreCase(entryType)
                || "OPENING_BALANCE".equalsIgnoreCase(entryType)
                || "CHARGE_ADJUSTMENT".equalsIgnoreCase(entryType);
    }

    private static boolean isCredit(String entryType) {
        return "PAYMENT".equalsIgnoreCase(entryType)
                || "CREDIT_NOTE".equalsIgnoreCase(entryType)
                || "RETURN".equalsIgnoreCase(entryType);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private int indexCount(String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class, indexName);
        return count == null ? 0 : count;
    }

    private int entryCount(String entryType) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM customer_account_entry WHERE entry_type = ?",
                Integer.class, entryType);
        return count == null ? 0 : count;
    }

    private String constraintDefinition(String name) {
        return jdbc.query("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?
                """, (rs, row) -> rs.getString(1), name).stream().findFirst().orElse(null);
    }

    private List<String> ledgerRows() {
        return jdbc.query("""
                SELECT id || '|' || entry_type || '|' || amount || '|'
                       || COALESCE(production_order_id::text, '') || '|' || status
                FROM customer_account_entry
                ORDER BY id
                """, (rs, row) -> rs.getString(1));
    }

    private int creditDays(long customerId) {
        Integer days = jdbc.queryForObject(
                "SELECT credit_days FROM customer WHERE id = ?", Integer.class, customerId);
        return days == null ? -1 : days;
    }

    private Long reassignedFrom(long entryId) {
        return jdbc.queryForObject(
                "SELECT reassigned_from_entry_id FROM customer_account_entry WHERE id = ?",
                Long.class, entryId);
    }

    private long appliedTo(long entryId) {
        Long parent = jdbc.queryForObject(
                "SELECT applied_to_entry_id FROM customer_account_entry WHERE id = ?",
                Long.class, entryId);
        return parent == null ? -1 : parent;
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
