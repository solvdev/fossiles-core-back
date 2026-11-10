package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherMovementEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LeatherMovementRepository extends JpaRepository<LeatherMovementEntity, Long> {
    boolean existsByTaskItemIdIn(java.util.List<Long> taskItemIds);

    /** Ítems con historial de cuero, incluidas salidas anuladas: la llave foránea impide borrarlos. */
    @Query("SELECT DISTINCT m.taskItemId FROM LeatherMovementEntity m WHERE m.taskItemId IN :taskItemIds")
    List<Long> findTaskItemIdsWithMovements(@Param("taskItemIds") java.util.Collection<Long> taskItemIds);
    java.util.Optional<LeatherMovementEntity> findByTaskItemIdAndMovementType(Long taskItemId, String movementType);

    /** Serializa la anulación de un movimiento contra la reversa desde el producto. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM LeatherMovementEntity m WHERE m.id = :id")
    Optional<LeatherMovementEntity> findByIdForUpdate(@Param("id") Long id);

    /** Salida activa de un producto de tarea (a lo sumo una, por índice único). */
    Optional<LeatherMovementEntity> findFirstByTaskItemIdAndMovementType(Long taskItemId, String movementType);

    boolean existsByTaskItemIdInAndMovementType(Collection<Long> taskItemIds, String movementType);

    List<LeatherMovementEntity> findByMaterialIdOrderByCreatedAtDesc(Long materialId);

    List<LeatherMovementEntity> findByMovementDateBetweenOrderByCreatedAtDesc(LocalDate from, LocalDate to);

    List<LeatherMovementEntity> findByMaterialIdAndMovementDateBetweenOrderByMovementDateAscCreatedAtAsc(
            Long materialId, LocalDate from, LocalDate to);

    List<LeatherMovementEntity> findByProductionOrderIdOrderByCreatedAtDesc(Long productionOrderId);

    List<LeatherMovementEntity> findAllByOrderByCreatedAtDesc();
}

