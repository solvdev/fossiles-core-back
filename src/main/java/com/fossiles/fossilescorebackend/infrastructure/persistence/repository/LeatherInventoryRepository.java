package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherInventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LeatherInventoryRepository extends JpaRepository<LeatherInventoryEntity, Long> {

    Optional<LeatherInventoryEntity> findByMaterialId(Long materialId);
}

