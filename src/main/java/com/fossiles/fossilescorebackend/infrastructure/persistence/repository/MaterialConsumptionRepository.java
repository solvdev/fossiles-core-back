package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialConsumptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaterialConsumptionRepository extends JpaRepository<MaterialConsumptionEntity, Long> {
    List<MaterialConsumptionEntity> findByProductionOrderId(Long productionOrderId);
    List<MaterialConsumptionEntity> findByProductionOrderIdAndStatus(Long productionOrderId, String status);
    List<MaterialConsumptionEntity> findByMaterialId(Long materialId);
}

