package com.fossiles.fossilescorebackend.infrastructure.persistence;

import com.fossiles.fossilescorebackend.application.service.ShipmentNumberCounterService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ShipmentNumberCounterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ShipmentNumberCounterRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * H2 de tests no corre scripts/shipment-number-sequence.sql. Si falta la fila, el correlativo
 * lanza BusinessException. Solo perfil test.
 */
@Component
@Profile("test")
public class ShipmentNumberCounterTestSeed implements ApplicationRunner {

    private final ShipmentNumberCounterRepository repository;

    public ShipmentNumberCounterTestSeed(ShipmentNumberCounterRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(ShipmentNumberCounterService.LF_ENVP);
        seed(ShipmentNumberCounterService.LF_OPC);
    }

    private void seed(String type) {
        if (!repository.existsById(type)) {
            repository.save(ShipmentNumberCounterEntity.builder().type(type).lastValue(0L).build());
        }
    }
}
