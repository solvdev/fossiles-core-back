package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialReceiptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialReceiptRepository extends JpaRepository<MaterialReceiptEntity, Long> {
    @org.springframework.data.jpa.repository.Query("select r.purchaseOrderId from MaterialReceiptEntity r where r.id = :id")
    Optional<Long> findOrderIdByReceiptId(@org.springframework.data.repository.query.Param("id") Long id);
    List<MaterialReceiptEntity> findByPurchaseOrderId(Long purchaseOrderId);
    Optional<MaterialReceiptEntity> findById(Long id);
}

