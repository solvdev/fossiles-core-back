package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoOpeningInventoryItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KioscoOpeningInventoryItemRepository extends JpaRepository<KioscoOpeningInventoryItemEntity, Long> {

    List<KioscoOpeningInventoryItemEntity> findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(Long openingInventoryId);

    Optional<KioscoOpeningInventoryItemEntity> findByOpeningInventoryIdAndProductIdAndColorIdAndHardwareCondition(
            Long openingInventoryId, Long productId, Long colorId, String hardwareCondition);

    void deleteByOpeningInventoryIdAndProductIdAndColorIdAndHardwareCondition(
            Long openingInventoryId, Long productId, Long colorId, String hardwareCondition);
}
