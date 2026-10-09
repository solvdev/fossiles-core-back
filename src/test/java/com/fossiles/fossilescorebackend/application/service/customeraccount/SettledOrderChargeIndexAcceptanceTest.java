package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
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

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPV;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * After phase 1 and phase 2, a second active charge on a settled order cannot be inserted.
 * Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class SettledOrderChargeIndexAcceptanceTest {

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
    @DisplayName("17d. settledOrder_secondChargeInsertBlockedByUniqueIndex")
    void settledOrder_secondChargeInsertBlockedByUniqueIndex() throws Exception {
        LfMigrationScripts.apply(POSTGRES, PHASE1);
        LfMigrationScripts.apply(POSTGRES, PHASE2);

        LfReceivablesFixture fx = new LfReceivablesFixture(context);
        CustomerEntity customer = fx.customer("SETTLED-17D");
        ProductionOrderEntity order = fx.order(customer, "OPV-17D", TYPE_OPV, "100.00");
        ProductionOrderPartialReleaseEntity release = fx.release(order, 1);
        ProductShipmentEntity shipment = fx.shipment(order, release, "ENV-17D", "100.00");
        ProductionOrderPartialReleaseEntity otherRelease = fx.release(order, 2);
        ProductShipmentEntity otherShipment = fx.shipment(order, otherRelease, "ENV-17D-2", "100.00");
        CustomerAccountEntryResponse charge = fx.charge(customer, order, null, null, "100.00");
        fx.create(customer, creditRequest("PAYMENT", charge.getId(), "100.00"));
        assertThat(fx.balance(customer)).isEqualByComparingTo("0");
        assertThat(fx.chargeBalance(customer, charge.getId())).isEqualByComparingTo("0");

        rejectInsert(customer.getId(), order.getId(), null, null);
        rejectInsert(customer.getId(), order.getId(), otherRelease.getId(), otherShipment.getId());
        rejectInsert(customer.getId(), order.getId(), null, otherShipment.getId());
        rejectInsert(customer.getId(), order.getId(), otherRelease.getId(), null);

        assertThat(fx.activeCharges(customer)).extracting(entry -> entry.getId()).containsExactly(charge.getId());
        assertThat(shipment.getId()).isNotNull();
    }

    private void rejectInsert(Long customerId, Long orderId, Long partialId, Long shipmentId) {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO customer_account_entry (
                    customer_id, entry_type, entry_date, amount, status,
                    production_order_id, partial_release_id, product_shipment_id)
                VALUES (?, 'CHARGE', DATE '2026-09-01', 100.00, 'ACTIVE', ?, ?, ?)
                """, customerId, orderId, partialId, shipmentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_cae_one_active_charge_per_order");
    }
}
