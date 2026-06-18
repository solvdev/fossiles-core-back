package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderPartialReleaseRepository extends JpaRepository<ProductionOrderPartialReleaseEntity, Long> {

    List<ProductionOrderPartialReleaseEntity> findByProductionOrderIdOrderBySequenceNumAsc(Long productionOrderId);

    Optional<ProductionOrderPartialReleaseEntity> findByIdAndProductionOrderId(Long id, Long productionOrderId);

    Optional<ProductionOrderPartialReleaseEntity> findTopByProductionOrderIdOrderBySequenceNumDesc(Long productionOrderId);
}
