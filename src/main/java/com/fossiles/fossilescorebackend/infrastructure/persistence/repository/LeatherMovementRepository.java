package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherMovementEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface LeatherMovementRepository extends JpaRepository<LeatherMovementEntity, Long> {
    boolean existsByTaskItemIdIn(java.util.List<Long> taskItemIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM LeatherMovementEntity m WHERE m.id = :id")
    java.util.Optional<LeatherMovementEntity> findByIdForUpdate(@Param("id") Long id);

    /** Ítems con historial de cuero, incluidas salidas anuladas: la llave foránea impide borrarlos. */
    @Query("SELECT DISTINCT m.taskItemId FROM LeatherMovementEntity m WHERE m.taskItemId IN :taskItemIds")
    List<Long> findTaskItemIdsWithMovements(@Param("taskItemIds") java.util.Collection<Long> taskItemIds);
    java.util.Optional<LeatherMovementEntity> findByTaskItemIdAndMovementType(Long taskItemId, String movementType);

    List<LeatherMovementEntity> findByMaterialIdOrderByCreatedAtDesc(Long materialId);

    List<LeatherMovementEntity> findByMovementDateBetweenOrderByCreatedAtDesc(LocalDate from, LocalDate to);

    List<LeatherMovementEntity> findByMaterialIdAndMovementDateBetweenOrderByMovementDateAscCreatedAtAsc(
            Long materialId, LocalDate from, LocalDate to);

    List<LeatherMovementEntity> findByProductionOrderIdOrderByCreatedAtDesc(Long productionOrderId);

    List<LeatherMovementEntity> findAllByOrderByCreatedAtDesc();
}

