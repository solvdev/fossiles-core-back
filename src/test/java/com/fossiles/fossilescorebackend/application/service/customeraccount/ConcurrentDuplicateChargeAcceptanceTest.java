package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.DUPLICATE_ORDER_CHARGE;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two order-level charges at once, on throwaway PostgreSQL, with phase 1 applied and phase 2 not yet applied.
 * Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ConcurrentDuplicateChargeAcceptanceTest {

    private static final String PHASE1 = "scripts/migration-customer-account-lf-phase1.sql";
    private static final String PHASE2 = "scripts/migration-customer-account-lf-phase2.sql";

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
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Phase 1 only: the customer lock leaves one charge; phase 2 then rejects a duplicate insert")
    void concurrentIdenticalChargesAreBothInserted() throws Exception {
        LfMigrationScripts.apply(POSTGRES, PHASE1);
        assertThat(indexCount("uq_cae_one_active_charge_per_order")).isZero();

        LfReceivablesFixture fx = new LfReceivablesFixture(context);
        CustomerEntity customer = fx.customer("CC001-T");
        ProductionOrderEntity order = fx.order(customer, "OPC-T0700", TYPE_OPC, "1968.00");
        ProductionOrderPartialReleaseEntity release = fx.release(order, 1);
        ProductShipmentEntity shipment = fx.shipment(order, release, "ENVP-90700-ENV-00001", "1968.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        int created = 0;
        int rejected = 0;
        List<Throwable> unexpected = new ArrayList<>();
        try {
            Callable<Object> submit = () -> {
                try {
                    return fx.charge(customer, order, null, null, "1968.00");
                } catch (Exception ex) {
                    return ex;
                }
            };
            List<Future<Object>> results = pool.invokeAll(List.of(submit, submit), 30, TimeUnit.SECONDS);
            for (Future<Object> result : results) {
                Object value = result.get();
                if (value instanceof BusinessException business) {
                    assertThat(business).hasMessage(DUPLICATE_ORDER_CHARGE);
                    rejected++;
                } else if (value instanceof Throwable failure) {
                    unexpected.add(failure);
                } else {
                    created++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected).isEmpty();
        assertThat(created).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        assertThat(fx.activeCharges(customer)).hasSize(1);
        assertThat(fx.activeCharges(customer).get(0).getAmount()).isEqualByComparingTo("1968.00");
        assertThat(fx.activeCharges(customer).get(0).getProductionOrderId()).isEqualTo(order.getId());
        assertThatCode(() -> fx.catalogRows(customer)).doesNotThrowAnyException();
        assertThat(fx.catalogRow(customer, shipment).isHasCharge()).isTrue();

        LfMigrationScripts.apply(POSTGRES, PHASE2);
        assertThat(indexCount("uq_cae_one_active_charge_per_order")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO customer_account_entry
                    (customer_id, entry_type, entry_date, amount, status, production_order_id)
                VALUES (?, 'CHARGE', DATE '2026-09-01', 10.00, 'ACTIVE', ?)
                """, customer.getId(), order.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_cae_one_active_charge_per_order");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO customer_account_entry
                    (customer_id, entry_type, entry_date, amount, status)
                VALUES (?, 'CHARGE', DATE '2026-09-01', 10.00, 'ACTIVE')
                """, customer.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_customer_account_entry_charge_order");
        assertThat(fx.activeCharges(customer)).hasSize(1);
    }

    private int indexCount(String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class, indexName);
        return count == null ? 0 : count;
    }
}
