package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoOpeningInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoOpeningInventoryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KioscoOpeningInventoryRepository extends JpaRepository<KioscoOpeningInventoryEntity, Long> {

    Optional<KioscoOpeningInventoryEntity> findByLocationIdAndStatus(
            Long locationId, KioscoOpeningInventoryStatus status);

    List<KioscoOpeningInventoryEntity> findByLocationIdOrderByCreatedAtDesc(Long locationId);

    boolean existsByLocationIdAndStatus(Long locationId, KioscoOpeningInventoryStatus status);
}
