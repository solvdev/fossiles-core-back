package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialInventoryKardex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MaterialInventoryKardexRepository extends JpaRepository<MaterialInventoryKardex, Long> {
    
    List<MaterialInventoryKardex> findByMaterialId(Long materialId);
    
    List<MaterialInventoryKardex> findByMovementType(String movementType);
    
    List<MaterialInventoryKardex> findByReferenceTypeAndReferenceId(String referenceType, Long referenceId);
    
    List<MaterialInventoryKardex> findByMovementDateBetween(LocalDateTime startDate, LocalDateTime endDate);
    
    @Query("SELECT k FROM MaterialInventoryKardex k WHERE k.materialId = :materialId AND k.movementDate BETWEEN :startDate AND :endDate ORDER BY k.movementDate DESC")
    List<MaterialInventoryKardex> findByMaterialIdAndDateRange(
        @Param("materialId") Long materialId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
}

