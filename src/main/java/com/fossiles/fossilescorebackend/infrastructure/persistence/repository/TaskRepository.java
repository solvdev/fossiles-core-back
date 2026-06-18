package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, Long> {
    Optional<TaskEntity> findByCode(String code);
    boolean existsByCode(String code);
    List<TaskEntity> findByProductionOrderId(Long productionOrderId);
    List<TaskEntity> findByDesk(Integer desk);
    @Query("SELECT t FROM TaskEntity t WHERE t.scheduledDate = :scheduledDate ORDER BY COALESCE(t.priority, 9999), t.deliveryDate, t.desk, t.id")
    List<TaskEntity> findByScheduledDate(@Param("scheduledDate") LocalDate scheduledDate);
    List<TaskEntity> findByStatus(String status);
    List<TaskEntity> findByDeskAndScheduledDate(Integer desk, LocalDate scheduledDate);

    /** Tareas sin mesa pero con fecha (cola / pendiente de asignar). */
    List<TaskEntity> findByDeskIsNullAndScheduledDate(LocalDate scheduledDate);

    @Query("SELECT t FROM TaskEntity t WHERE t.status IN ('PENDING', 'IN_PROGRESS') ORDER BY t.scheduledDate, t.priority, t.deliveryDate")
    List<TaskEntity> findActiveTasksOrdered();

    @Query("SELECT t FROM TaskEntity t WHERE t.desk = :desk AND t.scheduledDate = :date AND t.status NOT IN ('CANCELLED')")
    List<TaskEntity> findByDeskAndDateNotCancelled(@Param("desk") Integer desk, @Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(t.estimatedHours), 0) FROM TaskEntity t WHERE t.desk = :desk AND t.scheduledDate = :date AND t.status NOT IN ('CANCELLED')")
    Double sumEstimatedHoursByDeskAndDate(@Param("desk") Integer desk, @Param("date") LocalDate date);

    @Query("SELECT t FROM TaskEntity t WHERE t.status NOT IN ('COMPLETED', 'CANCELLED') ORDER BY t.scheduledDate, t.desk, t.priority")
    List<TaskEntity> findPendingAndInProgressOrdered();

    @Query("SELECT DISTINCT t.scheduledDate FROM TaskEntity t WHERE t.status NOT IN ('COMPLETED', 'CANCELLED') ORDER BY t.scheduledDate")
    List<LocalDate> findDistinctScheduledDates();

    @Modifying
    @Transactional
    @Query("DELETE FROM TaskEntity t WHERE t.productionOrderId = :productionOrderId")
    void deleteByProductionOrderId(@Param("productionOrderId") Long productionOrderId);

    /**
     * Cuando se reduce la cantidad de mesas activas desde una fecha efectiva, se limpian las tareas
     * que quedaron en mesas fuera de rango para que vuelvan a \"Sin asignar\" (desk = null).
     *
     * @return cantidad de tareas actualizadas
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE TaskEntity t
            SET t.desk = NULL
            WHERE t.scheduledDate >= :effectiveDate
            AND t.desk IS NOT NULL
            AND t.desk > :maxDesk
            AND t.status IN ('PENDING', 'IN_PROGRESS')
            """)
    int clearDeskOutOfRangeFromDate(@Param("effectiveDate") LocalDate effectiveDate, @Param("maxDesk") int maxDesk);

    @Query("""
            SELECT DISTINCT t FROM TaskEntity t
            WHERE t.status <> 'CANCELLED'
            AND (
              (t.materialsDeliveredAt IS NOT NULL AND t.materialsDeliveredAt >= :start AND t.materialsDeliveredAt < :end)
              OR EXISTS (
                SELECT 1 FROM TaskItemEntity ti
                WHERE ti.taskId = t.id
                AND ti.materialsDeliveredAt IS NOT NULL
                AND ti.materialsDeliveredAt >= :start
                AND ti.materialsDeliveredAt < :end
              )
            )
            ORDER BY t.id DESC
            """)
    List<TaskEntity> findTasksWithMaterialsDeliveredBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
