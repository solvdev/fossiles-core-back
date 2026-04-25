package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseNumberItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseNumberItemRepository extends JpaRepository<PurchaseNumberItemEntity, Long> {
    
    List<PurchaseNumberItemEntity> findByPurchaseNumberId(Long purchaseNumberId);
    
    Optional<PurchaseNumberItemEntity> findByIdAndPurchaseNumberId(Long id, Long purchaseNumberId);
    
    void deleteByPurchaseNumberId(Long purchaseNumberId);
    
    boolean existsByPurchaseNumberId(Long purchaseNumberId);
}


