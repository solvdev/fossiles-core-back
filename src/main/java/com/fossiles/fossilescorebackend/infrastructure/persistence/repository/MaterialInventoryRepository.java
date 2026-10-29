package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MaterialInventoryRepository extends JpaRepository<MaterialInventory, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select i from MaterialInventory i where i.materialId = :materialId")
    Optional<MaterialInventory> findByMaterialIdForUpdate(@org.springframework.data.repository.query.Param("materialId") Long materialId);
    
    Optional<MaterialInventory> findByMaterialId(Long materialId);
    
    boolean existsByMaterialId(Long materialId);
}

