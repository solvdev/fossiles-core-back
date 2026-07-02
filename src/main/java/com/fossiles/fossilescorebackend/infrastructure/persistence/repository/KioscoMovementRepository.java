package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface KioscoMovementRepository extends JpaRepository<KioscoMovementEntity, Long> {

    List<KioscoMovementEntity> findByKioscoStockIdOrderByCreatedAtDescIdDesc(Long kioscoStockId);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "ORDER BY m.createdAt DESC, m.id DESC")
    List<KioscoMovementEntity> findByLocationIdOrderByCreatedAtDesc(@Param("locationId") Long locationId);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND (:productId IS NULL OR s.productId = :productId) "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR :colorId IS NULL OR s.colorId = :colorId) "
            + "ORDER BY m.createdAt DESC, m.id DESC")
    List<KioscoMovementEntity> findByLocationAndFilters(
            @Param("locationId") Long locationId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId
    );

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND m.createdAt >= :from AND m.createdAt < :to "
            + "AND (:productId IS NULL OR s.productId = :productId) "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR :colorId IS NULL OR s.colorId = :colorId) "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findByLocationAndFiltersAndCreatedAtBetween(
            @Param("locationId") Long locationId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /** Movimientos del periodo (reporte kardex), orden cronologico ascendente. */
    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND m.createdAt >= :from AND m.createdAt < :to "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findByLocationAndCreatedAtBetween(
            @Param("locationId") Long locationId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /** Movimientos previos al inicio del periodo, mas reciente primero (para saldo inicial del kardex). */
    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND m.createdAt < :before "
            + "ORDER BY m.createdAt DESC, m.id DESC")
    List<KioscoMovementEntity> findByLocationAndCreatedAtBefore(
            @Param("locationId") Long locationId,
            @Param("before") LocalDateTime before
    );

    boolean existsByKioscoStockIdAndMovementTypeAndReferenceIdAndUserIdAndQuantityAndAffectsStock(
            Long kioscoStockId,
            KioscoMovementType movementType,
            Long referenceId,
            Long userId,
            Integer quantity,
            Boolean affectsStock
    );

    @Query("SELECT COUNT(m) > 0 FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND m.referenceId = :shipmentId "
            + "AND m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.ENTRADA "
            + "AND m.reason LIKE CONCAT('%', :lineKey, '%')")
    boolean existsShipmentReceiptLine(
            @Param("locationId") Long locationId,
            @Param("shipmentId") Long shipmentId,
            @Param("lineKey") String lineKey
    );

    @Query("SELECT COUNT(m) > 0 FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND s.productId = :productId "
            + "AND m.referenceId = :transferId "
            + "AND m.movementType = :movementType "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR s.colorId = :colorId)")
    boolean existsInventoryTransferMovement(
            @Param("locationId") Long locationId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId,
            @Param("transferId") Long transferId,
            @Param("movementType") KioscoMovementType movementType
    );

    boolean existsByPhysicalSlipNumber(String physicalSlipNumber);

    List<KioscoMovementEntity> findByReferenceIdAndMovementType(
            Long referenceId,
            KioscoMovementType movementType
    );

    List<KioscoMovementEntity> findByPhysicalSlipNumber(String physicalSlipNumber);
}
