package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryVoidRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerAccountEntryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderPartialReleaseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent creates on PostgreSQL. The service lock rejects the duplicate even before phase 2's
 * unique index exists; the index is applied afterwards and checked on its own.
 */
@SpringBootTest
@Testcontainers
class CustomerAccountLedgerConcurrencyTest {

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
    @Autowired ProductionOrderItemRepository items;
    @Autowired ProductionOrderPartialReleaseRepository releases;
    @Autowired ProductShipmentRepository shipments;
    @Autowired ProductRepository products;
    @Autowired CustomerAccountEntryRepository entries;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void twoConcurrentChargesAndAdjustmentsLeaveOneOfEach() throws Exception {
        CustomerEntity customer = customers.save(CustomerEntity.builder().name("Concurrente").status("active").build());
        ProductionOrderEntity order = orders.save(ProductionOrderEntity.builder()
                .code("OP-CONC")
                .orderType("OPV")
                .customerId(customer.getId())
                .sellerName("LUIS FELIPE")
                .status("IN_PROGRESS")
                .build());
        items.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .quantity(1)
                .unitPrice(new BigDecimal("100.00"))
                .build());
        ProductionOrderPartialReleaseEntity release = releases.save(ProductionOrderPartialReleaseEntity.builder()
                .productionOrderId(order.getId())
                .sequenceNum(1)
                .label("Parcial 1")
                .status("RELEASED")
                .build());
        products.save(ProductEntity.builder().code("LF-CONC").name("Producto").requiresMaterials(false).build());
        ProductShipmentEntity shipment = shipments.save(ProductShipmentEntity.builder()
                .productionOrderId(order.getId())
                .partialReleaseId(release.getId())
                .shipmentNumber("ENV-CONC")
                .status("SENT")
                .shippingCost(new BigDecimal("15.00"))
                .build());

        CustomerAccountEntryRequest charge = request("CHARGE", "100.00");
        charge.setProductionOrderId(order.getId());
        Outcome charges = race(() -> accounts.createEntry(customer.getId(), charge));
        assertThat(charges.successes).isEqualTo(1);
        assertThat(charges.rejected).isEqualTo(1);
        assertThat(charges.unexpected).isEmpty();
        assertThat(entries.findNonVoidChargesByProductionOrderId(order.getId())).hasSize(1);

        CustomerAccountEntryRequest adjustment = request("CHARGE_ADJUSTMENT", "1.00");
        adjustment.setProductShipmentId(shipment.getId());
        Outcome adjustments = race(() -> accounts.createEntry(customer.getId(), adjustment));
        assertThat(adjustments.successes).isEqualTo(1);
        assertThat(adjustments.rejected).isEqualTo(1);
        assertThat(adjustments.unexpected).isEmpty();
        assertThat(entries.findNonVoidAdjustmentsByProductShipmentId(shipment.getId())).hasSize(1);

        jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_cae_one_active_charge_per_order
                ON customer_account_entry (production_order_id)
                WHERE entry_type = 'CHARGE' AND status <> 'VOID'
                """);
        Long customerId = customer.getId();
        Long orderId = order.getId();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO customer_account_entry
                    (customer_id, entry_type, entry_date, amount, production_order_id, status)
                VALUES (?, 'CHARGE', CURRENT_DATE, 100.00, ?, 'ACTIVE')
                """, customerId, orderId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void voidLoadsTheChargeAfterTheCustomerLock() throws Exception {
        CustomerEntity customer = customers.save(CustomerEntity.builder().name("Lock").status("active").build());
        Long chargeId = chargeOf(customer, "OP-LOCK", "40.00");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            customers.findByIdForUpdate(customer.getId()).orElseThrow();
            CustomerAccountEntryEntity row = entries.findById(chargeId).orElseThrow();
            row.setInvoiceNumber("KEEP-ME");
            entries.saveAndFlush(row);
            holding.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("lock holder timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }));
        assertThat(holding.await(15, TimeUnit.SECONDS)).isTrue();
        Future<?> voided = pool.submit(() -> {
            accounts.voidEntry(chargeId, voidOf(null));
            return null;
        });
        Thread.sleep(1000);
        assertThat(voided.isDone()).isFalse();
        release.countDown();
        holder.get(20, TimeUnit.SECONDS);
        voided.get(20, TimeUnit.SECONDS);
        pool.shutdown();
        CustomerAccountEntryEntity after = entries.findById(chargeId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("VOID");
        assertThat(after.getInvoiceNumber()).isEqualTo("KEEP-ME");
    }

    @Test
    void concurrentReassignCannotOverfillTheTarget() throws Exception {
        CustomerEntity customer = customers.save(CustomerEntity.builder().name("Reasignar").status("active").build());
        Long target = chargeOf(customer, "OP-VOID-T", "100.00");
        Long first = chargeOf(customer, "OP-VOID-A", "100.00");
        Long second = chargeOf(customer, "OP-VOID-B", "100.00");
        accounts.createEntry(customer.getId(), payment(first, "60.00"));
        accounts.createEntry(customer.getId(), payment(second, "60.00"));

        Outcome outcome = race(
                () -> accounts.voidEntry(first, voidOf(target)),
                () -> accounts.voidEntry(second, voidOf(target)));
        assertThat(outcome.unexpected).isEmpty();
        assertThat(outcome.successes).isEqualTo(1);
        assertThat(outcome.rejected).isEqualTo(1);
        long voided = List.of(first, second).stream()
                .filter(id -> "VOID".equals(entries.findStatusById(id).orElseThrow()))
                .count();
        assertThat(voided).isEqualTo(1);
        assertThat(entries.findByAppliedToEntryIdAndStatus(target, "ACTIVE")).hasSize(1);
    }

    private Long chargeOf(CustomerEntity customer, String code, String price) throws Exception {
        ProductionOrderEntity order = orders.save(ProductionOrderEntity.builder()
                .code(code)
                .orderType("OPV")
                .customerId(customer.getId())
                .sellerName("LUIS FELIPE")
                .status("IN_PROGRESS")
                .build());
        items.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .quantity(1)
                .unitPrice(new BigDecimal(price))
                .build());
        CustomerAccountEntryRequest charge = request("CHARGE", price);
        charge.setProductionOrderId(order.getId());
        return accounts.createEntry(customer.getId(), charge).getId();
    }

    private static CustomerAccountEntryRequest payment(Long chargeId, String amount) {
        CustomerAccountEntryRequest request = request("PAYMENT", amount);
        request.setAppliedToEntryId(chargeId);
        request.setGrossCollectedAmount(new BigDecimal(amount));
        request.setMovementConceptCode("11");
        request.setReceiptNumber("REC-" + chargeId);
        request.setCollectionDate(LocalDate.of(2026, 9, 1));
        return request;
    }

    private static CustomerAccountEntryVoidRequest voidOf(Long targetId) {
        CustomerAccountEntryVoidRequest request = new CustomerAccountEntryVoidRequest();
        request.setVoidReason("cargo equivocado");
        request.setReassignToChargeId(targetId);
        return request;
    }

    private static CustomerAccountEntryRequest request(String type, String amount) {
        CustomerAccountEntryRequest request = new CustomerAccountEntryRequest();
        request.setEntryType(type);
        request.setEntryDate(LocalDate.of(2026, 9, 1));
        request.setAmount(new BigDecimal(amount));
        return request;
    }

    private Outcome race(Task first, Task second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        for (Task task : List.of(first, second)) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    task.run();
                    successes.incrementAndGet();
                } catch (BusinessException ex) {
                    rejected.incrementAndGet();
                } catch (Throwable ex) {
                    synchronized (unexpected) {
                        unexpected.add(ex);
                    }
                }
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return new Outcome(successes.get(), rejected.get(), unexpected);
    }

    private Outcome race(Task task) throws Exception {
        return race(task, task);
    }

    @FunctionalInterface
    private interface Task {
        void run() throws Exception;
    }

    private record Outcome(int successes, int rejected, List<Throwable> unexpected) {}
}
