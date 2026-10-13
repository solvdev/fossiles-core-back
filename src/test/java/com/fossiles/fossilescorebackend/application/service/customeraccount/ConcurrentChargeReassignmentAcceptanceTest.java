package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryVoidRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.TYPE_OPV;
import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.creditRequest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two voids with reassignment for one customer at the same time. The customer row lock serializes them.
 * Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ConcurrentChargeReassignmentAcceptanceTest {

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

    private LfReceivablesFixture fx;

    @BeforeEach
    void createFixture() {
        fx = new LfReceivablesFixture(context);
    }

    @Test
    @DisplayName("5g. Concurrent reassignment moves each credit once and does not overfill the target")
    void concurrentReassignmentIsSerializedByTheCustomerLock() throws Exception {
        CustomerEntity roomyCustomer = fx.customer("VOID-5G-ROOM");
        Race roomy = raceOnto(roomyCustomer, "200.00", "60.00");
        assertThat(roomy.unexpected).isEmpty();
        assertThat(roomy.successes).isEqualTo(2);
        assertThat(roomy.rejected).isZero();
        assertMovedOnce(roomy);

        CustomerEntity tightCustomer = fx.customer("VOID-5G-TIGHT");
        Race tight = raceOnto(tightCustomer, "100.00", "60.00");
        assertThat(tight.unexpected).isEmpty();
        assertThat(tight.successes).isEqualTo(1);
        assertThat(tight.rejected).isEqualTo(1);
        assertMovedOnce(tight);
    }

    private void assertMovedOnce(Race race) {
        List<CustomerAccountEntryEntity> rows = fx.entries(race.customer);
        long paymentsOnTarget = rows.stream()
                .filter(row -> "PAYMENT".equals(row.getEntryType()) && "ACTIVE".equals(row.getStatus()))
                .filter(row -> race.targetId.equals(row.getAppliedToEntryId()))
                .count();
        long paymentsOnFirst = rows.stream()
                .filter(row -> race.firstPaymentId.equals(row.getId()))
                .filter(row -> race.firstChargeId.equals(row.getAppliedToEntryId()))
                .count();
        long paymentsOnSecond = rows.stream()
                .filter(row -> race.secondPaymentId.equals(row.getId()))
                .filter(row -> race.secondChargeId.equals(row.getAppliedToEntryId()))
                .count();
        assertThat(paymentsOnTarget + paymentsOnFirst + paymentsOnSecond).isEqualTo(2);
        assertThat(paymentsOnTarget).isEqualTo(race.successes);
        for (CustomerAccountEntryEntity payment : List.of(fx.entry(race.firstPaymentId), fx.entry(race.secondPaymentId))) {
            assertThat(payment.getStatus()).isEqualTo("ACTIVE");
            boolean onTarget = race.targetId.equals(payment.getAppliedToEntryId());
            boolean onSource = race.firstChargeId.equals(payment.getAppliedToEntryId())
                    || race.secondChargeId.equals(payment.getAppliedToEntryId());
            assertThat(onTarget || onSource).isTrue();
            if (onTarget) {
                Long sourceId = payment.getId().equals(race.firstPaymentId) ? race.firstChargeId : race.secondChargeId;
                assertThat(payment.getReassignedFromEntryId()).isEqualTo(sourceId);
            } else {
                assertThat(payment.getReassignedFromEntryId()).isNull();
            }
        }
        long voidedSources = List.of(race.firstChargeId, race.secondChargeId).stream()
                .filter(id -> "VOID".equals(fx.entry(id).getStatus()))
                .count();
        assertThat(voidedSources).isEqualTo(race.successes);
    }

    private Race raceOnto(CustomerEntity customer, String targetAmount, String paymentAmount) throws Exception {
        ProductionOrderEntity targetOrder = fx.order(customer, "OPV-" + customer.getLegacyCode() + "-T", TYPE_OPV, targetAmount);
        ProductionOrderEntity firstOrder = fx.order(customer, "OPV-" + customer.getLegacyCode() + "-A", TYPE_OPV, "100.00");
        ProductionOrderEntity secondOrder = fx.order(customer, "OPV-" + customer.getLegacyCode() + "-B", TYPE_OPV, "100.00");
        CustomerAccountEntryResponse target = fx.charge(customer, targetOrder, null, null, targetAmount);
        CustomerAccountEntryResponse first = fx.charge(customer, firstOrder, null, null, "100.00");
        CustomerAccountEntryResponse second = fx.charge(customer, secondOrder, null, null, "100.00");
        CustomerAccountEntryResponse firstPayment = fx.create(customer, creditRequest("PAYMENT", first.getId(), paymentAmount));
        CustomerAccountEntryResponse secondPayment = fx.create(customer, creditRequest("PAYMENT", second.getId(), paymentAmount));

        Outcome outcome = race(
                () -> fx.accounts.voidEntry(first.getId(), voidRequest(target.getId())),
                () -> fx.accounts.voidEntry(second.getId(), voidRequest(target.getId())));
        return new Race(customer, target.getId(), first.getId(), second.getId(),
                firstPayment.getId(), secondPayment.getId(), outcome.successes, outcome.rejected, outcome.unexpected);
    }

    private static CustomerAccountEntryVoidRequest voidRequest(Long targetId) {
        CustomerAccountEntryVoidRequest request = new CustomerAccountEntryVoidRequest();
        request.setVoidReason("cargo equivocado");
        request.setReassignToChargeId(targetId);
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
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("race did not start");
                }
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

    @FunctionalInterface
    private interface Task {
        void run() throws Exception;
    }

    private record Outcome(int successes, int rejected, List<Throwable> unexpected) {
    }

    private record Race(
            CustomerEntity customer,
            Long targetId,
            Long firstChargeId,
            Long secondChargeId,
            Long firstPaymentId,
            Long secondPaymentId,
            int successes,
            int rejected,
            List<Throwable> unexpected) {
    }
}
