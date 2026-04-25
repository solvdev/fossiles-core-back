package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MaterialInventoryRepository extends JpaRepository<MaterialInventory, Long> {
    
    Optional<MaterialInventory> findByMaterialId(Long materialId);
    
    boolean existsByMaterialId(Long materialId);
}

