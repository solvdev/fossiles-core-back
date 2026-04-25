package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryLocationRepository extends JpaRepository<InventoryLocation, Long> {
    
    Optional<InventoryLocation> findByMaterialIdAndLocationId(Long materialId, Long locationId);
    
    List<InventoryLocation> findByMaterialId(Long materialId);
    
    List<InventoryLocation> findByLocationId(Long locationId);
    
    @Query("SELECT SUM(il.quantity) FROM InventoryLocation il WHERE il.materialId = :materialId")
    java.math.BigDecimal getTotalQuantityByMaterialId(@Param("materialId") Long materialId);
    
    boolean existsByMaterialIdAndLocationId(Long materialId, Long locationId);
}

