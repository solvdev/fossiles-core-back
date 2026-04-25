package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryTransferRepository extends JpaRepository<InventoryTransfer, Long> {
    List<InventoryTransfer> findByFromLocationId(Long fromLocationId);
    List<InventoryTransfer> findByToLocationId(Long toLocationId);
    List<InventoryTransfer> findByMaterialId(Long materialId);
    List<InventoryTransfer> findByProductId(Long productId);
    List<InventoryTransfer> findByStatus(String status);
    List<InventoryTransfer> findByFromLocationIdAndToLocationId(Long fromLocationId, Long toLocationId);
}

