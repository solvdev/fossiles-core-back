package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionCinchoDayStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionCinchoDayStatusRepository extends JpaRepository<ProductionCinchoDayStatusEntity, Long> {

    List<ProductionCinchoDayStatusEntity> findByWorkDate(LocalDate workDate);

    Optional<ProductionCinchoDayStatusEntity> findByWorkDateAndProductionOrderIdAndProductionOrderItemId(
            LocalDate workDate, Long productionOrderId, Long productionOrderItemId);
}
