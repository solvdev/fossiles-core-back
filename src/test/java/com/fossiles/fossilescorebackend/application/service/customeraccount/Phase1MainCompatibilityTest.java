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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 ships before the receivables fix. Today's main still saves one CHARGE per shipment
 * and sometimes a CHARGE with no production order. After phase 1, both must still save.
 * Skipped when Docker is unavailable.
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
        String sql = new ClassPathResource("db/migration-customer-account-lf-phase1.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String statement : split(sql)) {
            jdbc.execute((Connection connection) -> {
                try (Statement command = connection.createStatement()) {
                    if (command.execute(statement)) {
                        try (ResultSet rows = command.getResultSet()) {
                            while (rows != null && rows.next()) {
                                // Phase 1 ends with a read-only balance query.
                            }
                        }
                    }
                }
                return null;
            });
        }
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

    private static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean dollarQuote = false;
        boolean singleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (!dollarQuote && !singleQuote && c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int start = i;
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                current.append(sql, start, i);
                if (i < sql.length()) {
                    current.append('\n');
                }
                continue;
            }
            if (!singleQuote && c == '$' && i + 1 < sql.length() && sql.charAt(i + 1) == '$') {
                dollarQuote = !dollarQuote;
                current.append("$$");
                i++;
                continue;
            }
            if (!dollarQuote && c == '\'') {
                if (singleQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    current.append("''");
                    i++;
                    continue;
                }
                singleQuote = !singleQuote;
            }
            if (c == ';' && !dollarQuote && !singleQuote) {
                addIfExecutable(statements, current);
                continue;
            }
            current.append(c);
        }
        addIfExecutable(statements, current);
        return statements;
    }

    private static void addIfExecutable(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        current.setLength(0);
        if (statement.isEmpty()) {
            return;
        }
        boolean executable = statement.lines()
                .map(String::trim)
                .anyMatch(line -> !line.isEmpty() && !line.startsWith("--"));
        if (executable) {
            statements.add(statement);
        }
    }
}
