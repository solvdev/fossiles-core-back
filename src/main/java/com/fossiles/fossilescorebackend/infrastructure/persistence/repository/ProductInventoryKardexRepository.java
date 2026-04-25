package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ProductInventoryKardexRepository extends JpaRepository<ProductInventoryKardex, Long> {
    
    List<ProductInventoryKardex> findByProductId(Long productId);
    
    List<ProductInventoryKardex> findByLocationId(Long locationId);
    
    List<ProductInventoryKardex> findByProductIdAndLocationId(Long productId, Long locationId);
    
    List<ProductInventoryKardex> findByMovementType(String movementType);
    
    List<ProductInventoryKardex> findByReferenceTypeAndReferenceId(String referenceType, Long referenceId);
    
    List<ProductInventoryKardex> findByMovementDateBetween(LocalDateTime startDate, LocalDateTime endDate);
    
    @Query("SELECT k FROM ProductInventoryKardex k WHERE k.productId = :productId AND k.movementDate BETWEEN :startDate AND :endDate ORDER BY k.movementDate DESC")
    List<ProductInventoryKardex> findByProductIdAndDateRange(
        @Param("productId") Long productId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    @Query("SELECT k FROM ProductInventoryKardex k WHERE k.locationId = :locationId AND k.movementDate BETWEEN :startDate AND :endDate ORDER BY k.movementDate DESC")
    List<ProductInventoryKardex> findByLocationIdAndDateRange(
        @Param("locationId") Long locationId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
}

