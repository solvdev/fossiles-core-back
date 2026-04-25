package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialReceiptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialReceiptRepository extends JpaRepository<MaterialReceiptEntity, Long> {
    List<MaterialReceiptEntity> findByPurchaseOrderId(Long purchaseOrderId);
    Optional<MaterialReceiptEntity> findById(Long id);
}

