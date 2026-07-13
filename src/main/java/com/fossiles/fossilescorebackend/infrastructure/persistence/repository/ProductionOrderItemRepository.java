package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ProductionOrderItemRepository extends JpaRepository<ProductionOrderItemEntity, Long> {
    List<ProductionOrderItemEntity> findByProductionOrderId(Long productionOrderId);

    /**
     * Lock pesimista para crear tareas desde el organizador sin sobre-asignar cantidades
     * en concurrencia. ORDER BY id evita deadlocks entre transacciones simultáneas.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ProductionOrderItemEntity i WHERE i.id IN :ids ORDER BY i.id")
    List<ProductionOrderItemEntity> findAllByIdForUpdate(@Param("ids") List<Long> ids);

    List<ProductionOrderItemEntity> findByOnlineSaleId(Long onlineSaleId);

    @Query("SELECT DISTINCT i.onlineSaleId FROM ProductionOrderItemEntity i WHERE i.productionOrderId = :poId AND i.onlineSaleId IS NOT NULL")
    List<Long> findDistinctOnlineSaleIdsByProductionOrderId(@Param("poId") Long poId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ProductionOrderItemEntity i WHERE i.productionOrderId = :productionOrderId")
    void deleteByProductionOrderId(@Param("productionOrderId") Long productionOrderId);
}
