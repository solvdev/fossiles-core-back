package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ShipmentNumberCounterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ShipmentNumberCounterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2 does not prove counter concurrency; Testcontainers would be needed for that.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShipmentNumberCounterServiceTest {

    @Autowired
    private ShipmentNumberCounterService counters;

    @Autowired
    private ShipmentNumberCounterRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void consecutiveAllocationPerCounter() throws Exception {
        long envp1 = counters.nextValue(ShipmentNumberCounterService.LF_ENVP);
        long envp2 = counters.nextValue(ShipmentNumberCounterService.LF_ENVP);
        long opc1 = counters.nextValue(ShipmentNumberCounterService.LF_OPC);
        long opc2 = counters.nextValue(ShipmentNumberCounterService.LF_OPC);
        assertThat(envp2).isEqualTo(envp1 + 1);
        assertThat(opc2).isEqualTo(opc1 + 1);
        assertThat(counters.nextEnvp()).isEqualTo(String.format("ENVP-%05d", envp2 + 1));
    }

    @Test
    void rollbackDoesNotBurnANumber() {
        long before = repository.findById(ShipmentNumberCounterService.LF_ENVP).orElseThrow().getLastValue();
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(() -> inner.execute(status -> {
            try {
                counters.nextValue(ShipmentNumberCounterService.LF_ENVP);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(ShipmentNumberCounterService.LF_ENVP).orElseThrow().getLastValue())
                .isEqualTo(before);
    }

    @Test
    void missingCounterRowGivesClearError() {
        ShipmentNumberCounterEntity row = repository.findById(ShipmentNumberCounterService.LF_ENVP).orElseThrow();
        long lastValue = row.getLastValue() == null ? 0L : row.getLastValue();
        repository.deleteById(ShipmentNumberCounterService.LF_ENVP);
        try {
            assertThatThrownBy(() -> counters.nextValue(ShipmentNumberCounterService.LF_ENVP))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("No existe el correlativo")
                    .hasMessageContaining(ShipmentNumberCounterService.LF_ENVP)
                    .hasMessageContaining("shipment-number-sequence.sql");
        } finally {
            if (!repository.existsById(ShipmentNumberCounterService.LF_ENVP)) {
                repository.save(ShipmentNumberCounterEntity.builder()
                        .type(ShipmentNumberCounterService.LF_ENVP)
                        .lastValue(lastValue)
                        .build());
            }
        }
    }
}
