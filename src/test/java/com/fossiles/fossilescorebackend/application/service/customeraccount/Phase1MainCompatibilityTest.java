package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.service.CustomerAccountService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderPartialReleaseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * source-commit: 04fc545
 *
 * Phase 1 ships before the receivables fix. Today's main still saves one CHARGE per shipment
 * and sometimes a CHARGE with no production order. After phase 1, both must still save.
 * The classpath script is a byte-identical copy of scripts/migration-customer-account-lf-phase1.sql
 * at that commit and is applied with psql. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class Phase1MainCompatibilityTest {

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

    @Autowired CustomerAccountService accounts;
    @Autowired CustomerRepository customers;
    @Autowired ProductionOrderRepository orders;
    @Autowired ProductionOrderPartialReleaseRepository releases;
    @Autowired ProductShipmentRepository shipments;
    @Autowired JdbcTemplate jdbc;

    @Test
    void phase1AllowsMainPerShipmentChargesAndChargesWithoutAnOrder() throws Exception {
        applyPhase1();
        assertThat(constraintDefinition("chk_customer_account_entry_type")).contains("CHARGE_ADJUSTMENT");
        assertThat(indexCount("uq_cae_one_active_charge_per_order")).isZero();

        CustomerEntity customer = customers.save(CustomerEntity.builder()
                .name("Cliente main")
                .legacyCode("MAIN-P1")
                .status("ACTIVE")
                .build());
        ProductionOrderEntity order = orders.save(ProductionOrderEntity.builder()
                .code("OP-MAIN-P1")
                .orderType("OPV")
                .customerId(customer.getId())
                .sellerName("LUIS FELIPE")
                .status("IN_PROGRESS")
                .build());
        ProductShipmentEntity firstShipment = shipment(order, 1, "ENV-MAIN-P1-1");
        ProductShipmentEntity secondShipment = shipment(order, 2, "ENV-MAIN-P1-2");

        CustomerAccountEntryResponse first = accounts.createEntry(customer.getId(),
                charge(order.getId(), firstShipment, "100.00"));
        CustomerAccountEntryResponse second = accounts.createEntry(customer.getId(),
                charge(order.getId(), secondShipment, "40.00"));
        CustomerAccountEntryResponse withoutOrder = accounts.createEntry(customer.getId(),
                charge(null, null, "25.00"));

        assertThat(List.of(first, second, withoutOrder)).allSatisfy(saved ->
                assertThat(saved.getId()).isNotNull());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM customer_account_entry
                WHERE entry_type = 'CHARGE' AND status = 'ACTIVE' AND production_order_id = ?
                """, Integer.class, order.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT product_shipment_id) FROM customer_account_entry
                WHERE entry_type = 'CHARGE' AND production_order_id = ?
                """, Integer.class, order.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT production_order_id FROM customer_account_entry WHERE id = ?
                """, Long.class, withoutOrder.getId())).isNull();
    }

    private ProductShipmentEntity shipment(ProductionOrderEntity order, int sequence, String number) {
        ProductionOrderPartialReleaseEntity release = releases.save(ProductionOrderPartialReleaseEntity.builder()
                .productionOrderId(order.getId())
                .sequenceNum(sequence)
                .label("Parcial " + sequence)
                .status("RELEASED")
                .build());
        return shipments.save(ProductShipmentEntity.builder()
                .productionOrderId(order.getId())
                .partialReleaseId(release.getId())
                .shipmentNumber(number)
                .status("SENT")
                .build());
    }

    private static CustomerAccountEntryRequest charge(Long orderId, ProductShipmentEntity shipment, String amount) {
        CustomerAccountEntryRequest request = new CustomerAccountEntryRequest();
        request.setEntryType("CHARGE");
        request.setEntryDate(LocalDate.of(2026, 9, 1));
        request.setAmount(new BigDecimal(amount));
        request.setProductionOrderId(orderId);
        if (shipment != null) {
            request.setPartialReleaseId(shipment.getPartialReleaseId());
            request.setProductShipmentId(shipment.getId());
        }
        return request;
    }

    private void applyPhase1() throws Exception {
        Path host = Files.createTempFile("phase1-", ".sql");
        Files.write(host, new ClassPathResource("db/migration-customer-account-lf-phase1.sql").getContentAsByteArray());
        String remote = "/tmp/migration-customer-account-lf-phase1.sql";
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(host), remote);
        String command = "PGPASSWORD=" + shellQuote(POSTGRES.getPassword())
                + " psql -h 127.0.0.1 -v ON_ERROR_STOP=1 -U " + shellQuote(POSTGRES.getUsername())
                + " -d " + shellQuote(POSTGRES.getDatabaseName())
                + " -f " + shellQuote(remote);
        ExecResult result = POSTGRES.execInContainer("sh", "-c", command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(result.getStdout() + result.getStderr());
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String constraintDefinition(String name) {
        return jdbc.query("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?
                """, (rs, row) -> rs.getString(1), name).stream().findFirst().orElse(null);
    }

    private int indexCount(String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class, indexName);
        return count == null ? 0 : count;
    }
}
