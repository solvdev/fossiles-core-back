package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.*;

import static com.fossiles.fossilescorebackend.application.service.customeraccount.LfReceivablesFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Case 5: two identical charges for one shipment submitted concurrently, on a throwaway Testcontainers PostgreSQL
 * ({@code @ServiceConnection} replaces the datasource). Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ConcurrentDuplicateChargePostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean
    SecurityUtil securityUtil;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("CURRENT BEHAVIOR (bug): two concurrent identical charges for one shipment are both inserted and the "
            + "shipments list then throws — fix should insert exactly one")
    void concurrentIdenticalChargesAreBothInserted() throws Exception {
        LfReceivablesFixture fx = new LfReceivablesFixture(context);
        CustomerEntity customer = fx.customer("CC001-T");
        ProductionOrderEntity order = fx.order(customer, "OPC-T0700", TYPE_OPC, "1968.00");
        ProductionOrderPartialReleaseEntity release = fx.release(order, 1);
        ProductShipmentEntity shipment = fx.shipment(order, release, "ENVP-90700-ENV-00001", "1968.00");

        // createEntry calls getCurrentUserId() after preventDuplicateCharge and before the insert, so the barrier
        // holds both requests past the duplicate check; it times out instead of deadlocking if a lock serializes them.
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        when(securityUtil.getCurrentUserId()).thenAnswer(inv -> {
            try {
                bothChecked.await(5, TimeUnit.SECONDS);
            } catch (TimeoutException | BrokenBarrierException ignored) {
                // serialized requests: let them proceed one by one
            }
            return 1L;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> submit = () -> fx.charge(customer, order, release, shipment, "1783.00");
            List<Future<Object>> results = pool.invokeAll(List.of(submit, submit), 30, TimeUnit.SECONDS);
            for (Future<Object> result : results) {
                assertThat(result.get()).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(fx.activeCharges(customer)).hasSize(2)
                .allSatisfy(c -> assertThat(c.getProductShipmentId()).isEqualTo(shipment.getId()));
        assertThatThrownBy(() -> fx.catalogRows(customer))
                .isInstanceOf(IncorrectResultSizeDataAccessException.class);
    }
}
