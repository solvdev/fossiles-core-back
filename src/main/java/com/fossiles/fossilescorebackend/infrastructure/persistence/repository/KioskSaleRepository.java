package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface KioskSaleRepository extends JpaRepository<KioskSaleEntity, Long> {
    long countBySaleDate(LocalDate saleDate);

    Optional<KioskSaleEntity> findByKioskLocationIdAndSaleNumberIgnoreCase(
            Long kioskLocationId,
            String saleNumber
    );
    boolean existsByKioskLocationIdAndSaleNumberIgnoreCase(Long kioskLocationId, String saleNumber);
    List<KioskSaleEntity> findByKioskLocationIdOrderBySoldAtDesc(Long kioskLocationId);

    @Query("""
            SELECT DISTINCT s FROM KioskSaleEntity s
            LEFT JOIN FETCH s.items
            WHERE s.kioskLocationId = :kioskLocationId
            ORDER BY s.soldAt DESC
            """)
    List<KioskSaleEntity> findByKioskLocationIdWithItemsOrderBySoldAtDesc(
            @Param("kioskLocationId") Long kioskLocationId
    );

    List<KioskSaleEntity> findByKioskLocationIdAndSaleDateBetweenOrderBySoldAtDesc(
            Long kioskLocationId,
            LocalDate startDate,
            LocalDate endDate
    );

    @Query("""
            SELECT DISTINCT s FROM KioskSaleEntity s
            LEFT JOIN FETCH s.items
            WHERE s.kioskLocationId = :kioskLocationId
              AND s.saleDate BETWEEN :startDate AND :endDate
            ORDER BY s.soldAt DESC
            """)
    List<KioskSaleEntity> findByKioskLocationIdAndSaleDateBetweenWithItemsOrderBySoldAtDesc(
            @Param("kioskLocationId") Long kioskLocationId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
    List<KioskSaleEntity> findBySaleDateBetweenOrderBySoldAtDesc(LocalDate startDate, LocalDate endDate);
    List<KioskSaleEntity> findByCashSessionIdOrderBySoldAtAsc(Long cashSessionId);

    @Query("""
            SELECT s FROM KioskSaleEntity s
            WHERE s.kioskLocationId = :kioskLocationId
              AND UPPER(TRIM(s.status)) = 'COMPLETED'
              AND s.depositSlipNumber IS NULL
              AND (
                  UPPER(TRIM(s.paymentMethod)) IN ('EFECTIVO', 'CASH')
                  OR UPPER(TRIM(s.paymentMethod)) LIKE '%EFECTIVO%'
                  OR (
                      UPPER(TRIM(s.paymentMethod)) IN ('MIXTO', 'MIXED')
                      AND COALESCE(s.cashAmount, 0) > 0
                  )
              )
            ORDER BY s.soldAt DESC
            """)
    List<KioskSaleEntity> findPendingDepositsByKioskLocationId(@Param("kioskLocationId") Long kioskLocationId);

    @Query("""
            SELECT DISTINCT ks FROM KioskSaleEntity ks
            LEFT JOIN FETCH ks.items
            WHERE NOT EXISTS (
                SELECT 1 FROM TaxInvoiceEntity ti
                WHERE ti.sourceType = 'KIOSK_SALE' AND ti.sourceId = ks.id
            )
            AND (:kioskLocationId IS NULL OR ks.kioskLocationId = :kioskLocationId)
            AND (:fromDate IS NULL OR ks.saleDate >= :fromDate)
            AND (:toDate IS NULL OR ks.saleDate <= :toDate)
            ORDER BY ks.soldAt ASC, ks.id ASC
            """)
    List<KioskSaleEntity> findMissingTaxInvoice(
            @Param("kioskLocationId") Long kioskLocationId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate
    );
}
