package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InventoryAdjustmentRepository extends JpaRepository<InventoryAdjustment, Long> {
    List<InventoryAdjustment> findByMaterialId(Long materialId);
    List<InventoryAdjustment> findByProductId(Long productId);
    List<InventoryAdjustment> findByLocationId(Long locationId);
    List<InventoryAdjustment> findByCreatedBy(Long userId);
    
    @Query("SELECT a FROM InventoryAdjustment a WHERE a.adjustmentDate >= :startDate AND a.adjustmentDate <= :endDate")
    List<InventoryAdjustment> findByAdjustmentDateBetween(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);
    
    List<InventoryAdjustment> findByMaterialIdAndLocationId(Long materialId, Long locationId);
    List<InventoryAdjustment> findByProductIdAndLocationId(Long productId, Long locationId);
}

