package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface TaskItemRepository extends JpaRepository<TaskItemEntity, Long> {
    List<TaskItemEntity> findByTaskId(Long taskId);
    List<TaskItemEntity> findByTaskIdIn(List<Long> taskIds);
    boolean existsByProductionOrderItemId(Long productionOrderItemId);
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

