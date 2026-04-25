package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryLocationTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryLocationTypeRepository extends JpaRepository<InventoryLocationTypeEntity, Long> {
    
    Optional<InventoryLocationTypeEntity> findByCode(String code);
    
    Optional<InventoryLocationTypeEntity> findByCodeAndIsActiveTrue(String code);
    
    java.util.List<InventoryLocationTypeEntity> findByIsActiveTrue();
}

