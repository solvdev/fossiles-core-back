package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseNumberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseNumberRepository extends JpaRepository<PurchaseNumberEntity, Long> {
    Optional<PurchaseNumberEntity> findByPurchaseNumber(String purchaseNumber);
    
    boolean existsByPurchaseNumber(String purchaseNumber);
    
    // Obtener números de compra disponibles (no pagados ni terminados)
    @Query("SELECT p FROM PurchaseNumberEntity p WHERE p.status IN ('PENDIENTE') ORDER BY p.createdAt DESC")
    List<PurchaseNumberEntity> findAvailablePurchaseNumbers();
    
    // Obtener números de compra por estado
    List<PurchaseNumberEntity> findByStatus(String status);
    
    // Obtener el último número de compra para generar el siguiente
    @Query("SELECT p FROM PurchaseNumberEntity p WHERE p.purchaseNumber LIKE :prefix% ORDER BY p.id DESC")
    List<PurchaseNumberEntity> findLastByPrefix(@Param("prefix") String prefix);
}

