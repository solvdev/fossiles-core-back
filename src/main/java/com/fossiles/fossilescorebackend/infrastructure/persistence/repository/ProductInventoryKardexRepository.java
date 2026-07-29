package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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

    @Query("""
        SELECT k FROM ProductInventoryKardex k
        WHERE k.quantity < 0
          AND k.movementDate >= :startDate
          AND k.movementDate <= :endDate
        ORDER BY k.movementDate DESC
        """)
    List<ProductInventoryKardex> findOutflowsByDateRange(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate);

    @Query("""
        SELECT COUNT(k) > 0 FROM ProductInventoryKardex k
        WHERE k.referenceType = :referenceType
          AND k.referenceId = :referenceId
          AND k.movementType = :movementType
          AND k.productId = :productId
          AND k.locationId = :locationId
          AND ((:colorId IS NULL AND k.colorId IS NULL) OR k.colorId = :colorId)
        """)
    boolean existsMovement(
            @Param("referenceType") String referenceType,
            @Param("referenceId") Long referenceId,
            @Param("movementType") String movementType,
            @Param("productId") Long productId,
            @Param("locationId") Long locationId,
            @Param("colorId") Long colorId);

    @Query("""
        SELECT COUNT(k) > 0 FROM ProductInventoryKardex k
        WHERE k.referenceType = :referenceType
          AND k.referenceId = :referenceId
          AND k.movementType = :movementType
          AND k.productId = :productId
          AND k.locationId = :locationId
          AND ((:colorId IS NULL AND k.colorId IS NULL) OR k.colorId = :colorId)
          AND k.referenceNumber = :referenceNumber
        """)
    boolean existsMovementWithReferenceNumber(
            @Param("referenceType") String referenceType,
            @Param("referenceId") Long referenceId,
            @Param("movementType") String movementType,
            @Param("productId") Long productId,
            @Param("locationId") Long locationId,
            @Param("colorId") Long colorId,
            @Param("referenceNumber") String referenceNumber);

    /**
     * Suma con signo de los movimientos de una línea de documento: las salidas son negativas y
     * las reversiones positivas, de modo que el neto refleja lo que sigue realmente descargado.
     *
     * @param locationId null para netear sobre todas las ubicaciones
     * @param referenceLineId null equivale a movimientos sin línea (previos a la migración)
     */
    @Query("""
        SELECT coalesce(sum(k.quantity), 0) FROM ProductInventoryKardex k
        WHERE k.referenceType = :referenceType
          AND k.referenceId = :referenceId
          AND k.movementType IN :movementTypes
          AND k.productId = :productId
          AND ((:colorId IS NULL AND k.colorId IS NULL) OR k.colorId = :colorId)
          AND (:locationId IS NULL OR k.locationId = :locationId)
          AND ((:referenceLineId IS NULL AND k.referenceLineId IS NULL)
               OR k.referenceLineId = :referenceLineId)
        """)
    BigDecimal sumSignedQuantityForLine(
            @Param("referenceType") String referenceType,
            @Param("referenceId") Long referenceId,
            @Param("movementTypes") List<String> movementTypes,
            @Param("productId") Long productId,
            @Param("locationId") Long locationId,
            @Param("colorId") Long colorId,
            @Param("referenceLineId") Long referenceLineId);

    @Query("""
        SELECT k FROM ProductInventoryKardex k
        WHERE k.referenceType = :referenceType
          AND k.referenceId = :referenceId
          AND k.movementType IN :movementTypes
        ORDER BY k.id ASC
        """)
    List<ProductInventoryKardex> findByReferenceAndMovementTypes(
            @Param("referenceType") String referenceType,
            @Param("referenceId") Long referenceId,
            @Param("movementTypes") List<String> movementTypes);

    @Query("""
        SELECT k FROM ProductInventoryKardex k
        WHERE k.referenceType = 'SHIPMENT'
          AND k.referenceId = :shipmentId
          AND k.movementType = 'TRANSFER_IN'
          AND k.productId = :productId
          AND k.locationId = :locationId
          AND ((:colorId IS NULL AND k.colorId IS NULL) OR k.colorId = :colorId)
          AND k.referenceNumber = :referenceNumber
        ORDER BY k.id ASC
        """)
    List<ProductInventoryKardex> findShipmentTransferInByLine(
            @Param("shipmentId") Long shipmentId,
            @Param("productId") Long productId,
            @Param("locationId") Long locationId,
            @Param("colorId") Long colorId,
            @Param("referenceNumber") String referenceNumber);
}

