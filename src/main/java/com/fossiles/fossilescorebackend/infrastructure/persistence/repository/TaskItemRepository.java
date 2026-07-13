package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public interface TaskItemRepository extends JpaRepository<TaskItemEntity, Long> {
    List<TaskItemEntity> findByTaskId(Long taskId);
    List<TaskItemEntity> findByTaskIdIn(List<Long> taskIds);
    boolean existsByProductionOrderItemId(Long productionOrderItemId);

    /**
     * Cantidad ya cubierta por tareas (no CANCELLED) por ítem de OP — base del modelo
     * de "cantidad restante" del organizador. Theta-join porque TaskItemEntity no mapea
     * relación JPA con TaskEntity.
     */
    @Query("""
            SELECT ti.productionOrderItemId, COALESCE(SUM(ti.quantity), 0)
            FROM TaskItemEntity ti, TaskEntity t
            WHERE t.id = ti.taskId
              AND ti.productionOrderItemId IN :itemIds
              AND t.status <> 'CANCELLED'
            GROUP BY ti.productionOrderItemId
            """)
    List<Object[]> sumAssignedQuantityByItemIds(@Param("itemIds") Collection<Long> itemIds);

    default Map<Long, Integer> assignedQuantityMap(Collection<Long> itemIds) {
        Map<Long, Integer> out = new HashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return out;
        }
        for (Object[] row : sumAssignedQuantityByItemIds(itemIds)) {
            Long itemId = (Long) row[0];
            Number sum = (Number) row[1];
            if (itemId != null && sum != null) {
                out.put(itemId, sum.intValue());
            }
        }
        return out;
    }
    boolean existsByProductionOrderItemIdAndDaySaleExtraTrue(Long productionOrderItemId);
    List<TaskItemEntity> findByProductionOrderItemIdInAndDaySaleExtraTrue(List<Long> productionOrderItemIds);
    List<TaskItemEntity> findByDaySaleExtraTrue();

    @Query("SELECT DISTINCT t.taskId FROM TaskItemEntity t WHERE t.productionOrderItemId IN :productionOrderItemIds")
    List<Long> findDistinctTaskIdsByProductionOrderItemIdIn(@Param("productionOrderItemIds") List<Long> productionOrderItemIds);

    @Modifying
    @Transactional
    @Query("DELETE FROM TaskItemEntity t WHERE t.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TaskItemEntity t WHERE t.taskId IN (SELECT tk.id FROM TaskEntity tk WHERE tk.productionOrderId = :poId)")
    void deleteByProductionOrderId(@Param("poId") Long poId);
}

