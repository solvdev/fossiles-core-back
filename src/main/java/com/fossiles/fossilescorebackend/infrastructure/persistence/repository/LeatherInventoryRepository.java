package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherInventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LeatherInventoryRepository extends JpaRepository<LeatherInventoryEntity, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT i FROM LeatherInventoryEntity i WHERE i.materialId = :materialId")
    Optional<LeatherInventoryEntity> findByMaterialIdForUpdate(@org.springframework.data.repository.query.Param("materialId") Long materialId);

    Optional<LeatherInventoryEntity> findByMaterialId(Long materialId);
}

