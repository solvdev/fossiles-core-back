package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherMovementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface LeatherMovementRepository extends JpaRepository<LeatherMovementEntity, Long> {

    List<LeatherMovementEntity> findByMaterialIdOrderByCreatedAtDesc(Long materialId);

    List<LeatherMovementEntity> findByMovementDateBetweenOrderByCreatedAtDesc(LocalDate from, LocalDate to);

    List<LeatherMovementEntity> findByMaterialIdAndMovementDateBetweenOrderByMovementDateAscCreatedAtAsc(
            Long materialId, LocalDate from, LocalDate to);

    List<LeatherMovementEntity> findByProductionOrderIdOrderByCreatedAtDesc(Long productionOrderId);

    List<LeatherMovementEntity> findAllByOrderByCreatedAtDesc();
}

