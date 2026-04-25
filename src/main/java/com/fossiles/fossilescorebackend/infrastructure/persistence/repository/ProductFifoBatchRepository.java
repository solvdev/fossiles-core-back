package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductFifoBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductFifoBatchRepository extends JpaRepository<ProductFifoBatch, Long> {
    
    /**
     * Obtiene todos los lotes FIFO de un producto en una ubicación y color,
     * ordenados por fecha de entrada (más antiguos primero) y que tengan cantidad disponible > 0
     */
    @Query("SELECT b FROM ProductFifoBatch b WHERE b.productId = :productId " +
           "AND b.locationId = :locationId " +
           "AND (b.colorId = :colorId OR (:colorId IS NULL AND b.colorId IS NULL)) " +
           "AND b.quantityAvailable > 0 " +
           "ORDER BY b.entryDate ASC, b.id ASC")
    List<ProductFifoBatch> findAvailableBatchesByProductAndLocationAndColor(
            @Param("productId") Long productId,
            @Param("locationId") Long locationId,
            @Param("colorId") Long colorId);
    
    /**
     * Obtiene todos los lotes FIFO de un producto en una ubicación (incluyendo los agotados)
     */
    List<ProductFifoBatch> findByProductIdAndLocationIdAndColorIdOrderByEntryDateAscIdAsc(
            Long productId, Long locationId, Long colorId);
    
    /**
     * Obtiene lotes por referencia
     */
    List<ProductFifoBatch> findByReferenceTypeAndReferenceId(String referenceType, Long referenceId);
}

