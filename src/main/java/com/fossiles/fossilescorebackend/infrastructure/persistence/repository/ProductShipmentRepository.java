package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductShipmentRepository extends JpaRepository<ProductShipmentEntity, Long> {
    
    List<ProductShipmentEntity> findByDistributionId(Long distributionId);
    
    Optional<ProductShipmentEntity> findByShipmentNumber(String shipmentNumber);
    
    boolean existsByShipmentNumber(String shipmentNumber);
    
    Optional<ProductShipmentEntity> findByDistributionIdAndLocationId(Long distributionId, Long locationId);

    List<ProductShipmentEntity> findByDistributionIdAndLocationIdOrderByIdAsc(Long distributionId, Long locationId);

    List<ProductShipmentEntity> findByLocationIdOrderByIdAsc(Long locationId);

    List<ProductShipmentEntity> findByStatusIgnoreCase(String status);

    List<ProductShipmentEntity> findByStatusIgnoreCaseAndLocationId(String status, Long locationId);

    /** Envíos ya recibidos en kiosko (cualquier origen: distribución, OP, standalone, LF). */
    @Query("""
        SELECT s FROM ProductShipmentEntity s
        WHERE s.locationId = :locationId
          AND UPPER(TRIM(COALESCE(s.status, ''))) IN ('DELIVERED', 'RECEIVED', 'COMPLETED')
        ORDER BY s.receivedAt DESC NULLS LAST, s.id DESC
        """)
    List<ProductShipmentEntity> findKioskReceiptShipmentsByLocationId(@Param("locationId") Long locationId);

    List<ProductShipmentEntity> findByStatusIgnoreCaseAndLocationIdIn(String status, Collection<Long> locationIds);

    long countByStatusIgnoreCaseAndLocationId(String status, Long locationId);

    long countByStatusIgnoreCaseAndLocationIdIn(String status, Collection<Long> locationIds);

    List<ProductShipmentEntity> findByProductionOrderId(Long productionOrderId);

    List<ProductShipmentEntity> findByProductionOrderIdAndLocationIdOrderByIdAsc(Long productionOrderId, Long locationId);

    List<ProductShipmentEntity> findByProductionOrderIdAndLocationIdIsNullOrderByIdAsc(Long productionOrderId);

    List<ProductShipmentEntity> findByPartialReleaseId(Long partialReleaseId);

    List<ProductShipmentEntity> findByProductionOrderIdIsNullAndDistributionIdIsNullAndNotesContainingIgnoreCaseOrderByIdDesc(
            String notesFragment);

    /**
     * Envíos internos ENVI sin OP ni distribución (legacy y solicitudes aprobadas).
     */
    @Query("""
        SELECT s FROM ProductShipmentEntity s
        WHERE s.productionOrderId IS NULL
          AND s.distributionId IS NULL
          AND (
            UPPER(COALESCE(s.shipmentNumber, '')) LIKE 'ENVI-%'
            OR LOWER(COALESCE(s.notes, '')) LIKE '%internal_envi%'
            OR LOWER(COALESCE(s.notes, '')) LIKE '%personal interno%'
          )
        ORDER BY s.id DESC
        """)
    List<ProductShipmentEntity> findStandaloneInternalEnviShipments();

    /**
     * Envíos directos a kiosko sin OP ni distribución (replenishment ad-hoc).
     */
    @Query("""
        SELECT s FROM ProductShipmentEntity s
        WHERE s.productionOrderId IS NULL
          AND s.distributionId IS NULL
          AND s.locationId IS NOT NULL
        ORDER BY s.id DESC
        """)
    List<ProductShipmentEntity> findStandaloneKioskShipments();

    /**
     * Todos los envíos internos ENVI: standalone, OPI (INTERNA) y legacy sin correlativo ENVI-*.
     */
    @Query("""
        SELECT DISTINCT s FROM ProductShipmentEntity s
        LEFT JOIN s.productionOrder po
        WHERE UPPER(COALESCE(s.shipmentNumber, '')) LIKE 'ENVI-%'
           OR UPPER(COALESCE(po.orderType, '')) = 'INTERNA'
           OR (
             s.productionOrderId IS NULL
             AND s.distributionId IS NULL
             AND (
               LOWER(COALESCE(s.notes, '')) LIKE '%internal_envi%'
               OR LOWER(COALESCE(s.notes, '')) LIKE '%personal interno%'
             )
           )
        ORDER BY s.id DESC
        """)
    List<ProductShipmentEntity> findAllInternalEnviShipments();

    @Query("SELECT s.shipmentNumber FROM ProductShipmentEntity s WHERE LOWER(s.shipmentNumber) LIKE 'envi-%'")
    List<String> findEnviPrefixShipmentNumbers();

    @Query("SELECT s.shipmentNumber FROM ProductShipmentEntity s WHERE UPPER(s.shipmentNumber) LIKE 'ENVP-%'")
    List<String> findEnvpPrefixShipmentNumbers();

    @Query("""
            SELECT DISTINCT po.customerId FROM ProductShipmentEntity s
            JOIN ProductionOrderEntity po ON po.id = s.productionOrderId
            WHERE po.customerId IS NOT NULL
              AND LOWER(COALESCE(s.shipmentNumber, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    List<Long> findCustomerIdsByShipmentNumber(@Param("q") String q);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductShipmentEntity s WHERE s.id = :id")
    Optional<ProductShipmentEntity> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ProductShipmentEntity s
        SET s.status = 'SENT', s.sentAt = :sentAt, s.sentBy = :sentBy
        WHERE s.id = :id AND UPPER(TRIM(s.status)) = 'CONFIRMED'
        """)
    int markSentIfConfirmed(
            @Param("id") Long id,
            @Param("sentAt") LocalDateTime sentAt,
            @Param("sentBy") Long sentBy);
}

