package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryKardexEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InventoryKardexRepository extends JpaRepository<InventoryKardexEntity, Long> {
    
    List<InventoryKardexEntity> findByMaterialId(Long materialId);
    
    List<InventoryKardexEntity> findByLocationId(Long locationId);
    
    List<InventoryKardexEntity> findByMaterialIdAndLocationId(Long materialId, Long locationId);
    
    List<InventoryKardexEntity> findByMovementType(String movementType);
    
    List<InventoryKardexEntity> findByReferenceTypeAndReferenceId(String referenceType, Long referenceId);
    
    List<InventoryKardexEntity> findByMovementDateBetween(LocalDateTime startDate, LocalDateTime endDate);
    
    @Query("SELECT k FROM InventoryKardexEntity k WHERE k.materialId = :materialId AND k.movementDate BETWEEN :startDate AND :endDate ORDER BY k.movementDate DESC")
    List<InventoryKardexEntity> findByMaterialIdAndDateRange(
        @Param("materialId") Long materialId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    @Query("SELECT k FROM InventoryKardexEntity k WHERE k.locationId = :locationId AND k.movementDate BETWEEN :startDate AND :endDate ORDER BY k.movementDate DESC")
    List<InventoryKardexEntity> findByLocationIdAndDateRange(
        @Param("locationId") Long locationId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
}

