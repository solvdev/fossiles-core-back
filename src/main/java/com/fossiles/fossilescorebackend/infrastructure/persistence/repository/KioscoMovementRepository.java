package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface KioscoMovementRepository extends JpaRepository<KioscoMovementEntity, Long> {

    List<KioscoMovementEntity> findByKioscoStockIdOrderByCreatedAtDescIdDesc(Long kioscoStockId);

    List<KioscoMovementEntity> findByKioscoStockIdOrderByCreatedAtAscIdAsc(Long kioscoStockId);

    /**
     * Exact line-token match (not substring LIKE): avoids L1 matching L10.
     * Token must appear at end of reason or be followed by a non-digit delimiter.
     */
    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND m.referenceId = :shipmentId "
            + "AND m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.ENTRADA "
            + "AND ("
            + "  m.reason LIKE CONCAT('%', :lineKey) "
            + "  OR m.reason LIKE CONCAT('%', :lineKey, ' %') "
            + "  OR m.reason LIKE CONCAT('%', :lineKey, '·%') "
            + "  OR m.reason LIKE CONCAT('%', :lineKey, '|%') "
            + "  OR m.reason LIKE CONCAT('%', :lineKey, '/%') "
            + ") "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findShipmentEntradaMovements(
            @Param("locationId") Long locationId,
            @Param("shipmentId") Long shipmentId,
            @Param("lineKey") String lineKey);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND s.productId = :productId "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR s.colorId = :colorId) "
            + "AND m.referenceId = :shipmentId "
            + "AND m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.ENTRADA "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findShipmentEntradaMovementsByProduct(
            @Param("locationId") Long locationId,
            @Param("shipmentId") Long shipmentId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND s.productId = :productId "
            + "AND m.referenceId = :shipmentId "
            + "AND (m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.ENTRADA "
            + "OR m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.TRASLADO_ENTRADA) "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findShipmentEntradaMovementsByProductAnyColor(
            @Param("locationId") Long locationId,
            @Param("shipmentId") Long shipmentId,
            @Param("productId") Long productId);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND s.productId = :productId "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR s.colorId = :colorId) "
            + "AND (m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.ENTRADA "
            + "OR m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.TRASLADO_ENTRADA) "
            + "AND (m.referenceId = :shipmentId "
            + "OR (:shipmentToken IS NOT NULL AND :shipmentToken <> '' AND ("
            + "     (m.referenceId IS NULL AND LOWER(m.reason) LIKE LOWER(CONCAT('%', :shipmentToken, '%'))) "
            + "     OR (:lineKey IS NOT NULL AND :lineKey <> '' AND ("
            + "          m.reason LIKE CONCAT('%', :lineKey) "
            + "          OR m.reason LIKE CONCAT('%', :lineKey, ' %') "
            + "          OR m.reason LIKE CONCAT('%', :lineKey, '·%') "
            + "          OR m.reason LIKE CONCAT('%', :lineKey, '|%')"
            + "     )) "
            + "     OR (:lineReasonKey IS NOT NULL AND :lineReasonKey <> '' AND ("
            + "          m.reason LIKE CONCAT('%', :lineReasonKey) "
            + "          OR m.reason LIKE CONCAT('%', :lineReasonKey, ' %') "
            + "          OR m.reason LIKE CONCAT('%', :lineReasonKey, '·%') "
            + "          OR m.reason LIKE CONCAT('%', :lineReasonKey, '|%')"
            + "     ))"
            + "))) "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findShipmentEntradaMovementsByProductLoose(
            @Param("locationId") Long locationId,
            @Param("shipmentId") Long shipmentId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId,
            @Param("shipmentToken") String shipmentToken,
            @Param("lineKey") String lineKey,
            @Param("lineReasonKey") String lineReasonKey);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "JOIN ProductEntity p ON p.id = s.productId "
            + "WHERE s.locationId = :locationId "
            + "AND UPPER(p.code) LIKE 'SUM%' "
            + "AND m.referenceId IN :shipmentIds "
            + "AND (m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.ENTRADA "
            + "OR m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.TRASLADO_ENTRADA) "
            + "ORDER BY m.referenceId ASC, s.productId ASC, s.colorId ASC, m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findSumPackagingEntradasForShipments(
            @Param("locationId") Long locationId,
            @Param("shipmentIds") List<Long> shipmentIds);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "JOIN ProductEntity p ON p.id = s.productId "
            + "WHERE s.locationId = :locationId "
            + "AND UPPER(p.code) LIKE 'SUM%' "
            + "AND m.referenceId IS NULL "
            + "AND (m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.ENTRADA "
            + "OR m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.TRASLADO_ENTRADA) "
            + "AND (LOWER(m.reason) LIKE '%recepc%env%' OR m.reason LIKE '%SHIPMENT_RCPT:%') "
            + "ORDER BY s.productId ASC, s.colorId ASC, m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findSumPackagingEntradasWithoutReference(
            @Param("locationId") Long locationId);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "WHERE m.kioscoStockId = :stockId "
            + "AND m.referenceId = :shipmentId "
            + "AND (m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.ENTRADA "
            + "OR m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.TRASLADO_ENTRADA) "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findShipmentEntradasByStockAndShipment(
            @Param("stockId") Long stockId,
            @Param("shipmentId") Long shipmentId);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "WHERE m.kioscoStockId = :stockId "
            + "AND (m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.ENTRADA "
            + "OR m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.TRASLADO_ENTRADA) "
            + "AND LOWER(m.reason) LIKE LOWER(CONCAT('%', :token, '%')) "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findShipmentEntradasByStockAndReasonToken(
            @Param("stockId") Long stockId,
            @Param("token") String token);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND s.productId = :productId "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR s.colorId = :colorId) "
            + "AND m.referenceId = :shipmentId "
            + "AND m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.MERMA "
            + "AND (LOWER(m.reason) LIKE '%cuadre recepc%' OR LOWER(m.reason) LIKE '%cuadre recepcion%') "
            + "AND ("
            + "  m.reason LIKE CONCAT('%', :lineKey) "
            + "  OR m.reason LIKE CONCAT('%', :lineKey, ' %') "
            + "  OR m.reason LIKE CONCAT('%', :lineKey, '·%') "
            + "  OR m.reason LIKE CONCAT('%', :lineKey, '|%') "
            + ") "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findShipmentReconcileMermaMovements(
            @Param("locationId") Long locationId,
            @Param("shipmentId") Long shipmentId,
            @Param("lineKey") String lineKey,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId);

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

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND m.physicalCountId = :physicalCountId "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findByLocationAndPhysicalCountId(
            @Param("locationId") Long locationId,
            @Param("physicalCountId") Long physicalCountId
    );

    /** Movimientos previos al inicio del periodo, orden cronologico ascendente (saldo inicial kardex). */
    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND m.createdAt < :before "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<KioscoMovementEntity> findByLocationAndCreatedAtBeforeAsc(
            @Param("locationId") Long locationId,
            @Param("before") LocalDateTime before);

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

    /**
     * Exact line-token match (not substring LIKE): avoids L1 matching L10.
     */
    @Query("SELECT COUNT(m) > 0 FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND m.referenceId = :shipmentId "
            + "AND m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.ENTRADA "
            + "AND ("
            + "  m.reason LIKE CONCAT('%', :lineKey) "
            + "  OR m.reason LIKE CONCAT('%', :lineKey, ' %') "
            + "  OR m.reason LIKE CONCAT('%', :lineKey, '·%') "
            + "  OR m.reason LIKE CONCAT('%', :lineKey, '|%') "
            + "  OR m.reason LIKE CONCAT('%', :lineKey, '/%') "
            + ")")
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

    /**
     * POS / factura idempotency: same sale line (location+product+color+size) already wrote VENTA or ANULACION.
     */
    @Query("SELECT COUNT(m) > 0 FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND s.productId = :productId "
            + "AND m.referenceId = :referenceId "
            + "AND m.movementType = :movementType "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR s.colorId = :colorId) "
            + "AND ((:sizeKey IS NULL AND (m.sizeKey IS NULL OR m.sizeKey = '')) "
            + "OR m.sizeKey = :sizeKey)")
    boolean existsPosReferenceMovement(
            @Param("locationId") Long locationId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId,
            @Param("referenceId") Long referenceId,
            @Param("movementType") KioscoMovementType movementType,
            @Param("sizeKey") String sizeKey
    );

    @Query("SELECT COUNT(m) > 0 FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE m.physicalSlipNumber = :slip "
            + "AND m.movementType = com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType.TRASLADO_SALIDA "
            + "AND s.productId = :productId "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR s.colorId = :colorId) "
            + "AND ((:sizeKey IS NULL AND (m.sizeKey IS NULL OR m.sizeKey = '')) "
            + "OR m.sizeKey = :sizeKey) "
            + "AND m.quantity = :quantity")
    boolean existsTrasladoBoletaDuplicateLine(
            @Param("slip") String slip,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId,
            @Param("sizeKey") String sizeKey,
            @Param("quantity") Integer quantity
    );

    boolean existsByPhysicalSlipNumber(String physicalSlipNumber);

    List<KioscoMovementEntity> findByReferenceIdAndMovementType(
            Long referenceId,
            KioscoMovementType movementType
    );

    List<KioscoMovementEntity> findByPhysicalSlipNumber(String physicalSlipNumber);

    @Modifying
    @Query("UPDATE KioscoMovementEntity m SET m.kioscoStockId = :toStockId "
            + "WHERE m.kioscoStockId = :fromStockId")
    int reassignKioscoStockId(
            @Param("fromStockId") Long fromStockId,
            @Param("toStockId") Long toStockId
    );
}
