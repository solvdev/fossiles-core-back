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

    /** Busca venta del kiosko por correlativo interno de factura (ej. A45-241). */
    @Query("""
            SELECT s FROM KioskSaleEntity s
            WHERE s.kioskLocationId = :kioskLocationId
              AND EXISTS (
                  SELECT 1 FROM TaxInvoiceEntity t
                  WHERE (
                      t.id = s.invoiceId
                      OR (UPPER(TRIM(t.sourceType)) = 'KIOSK_SALE' AND t.sourceId = s.id)
                  )
                  AND UPPER(TRIM(COALESCE(t.internalNumber, ''))) = UPPER(TRIM(:internalNumber))
              )
            """)
    Optional<KioskSaleEntity> findByKioskLocationIdAndInvoiceInternalNumber(
            @Param("kioskLocationId") Long kioskLocationId,
            @Param("internalNumber") String internalNumber
    );
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

    /**
     * Ventas COMPLETED sin FEL, de los últimos 5 días, excluyendo cambios con diferencia.
     * Las más antiguas no se pueden certificar (FEL-GUI-12) y no deben bloquear el POS.
     */
    @Query("""
            SELECT s FROM KioskSaleEntity s
            WHERE s.kioskLocationId = :kioskLocationId
              AND UPPER(TRIM(s.status)) = 'COMPLETED'
              AND s.saleDate >= :minSaleDate
              AND (s.promotionName IS NULL OR LOWER(TRIM(s.promotionName)) NOT LIKE 'boleta de cambio%')
              AND (s.felUuid IS NULL OR TRIM(s.felUuid) = '')
              AND (
                  s.invoiceId IS NULL
                  OR NOT EXISTS (
                      SELECT 1 FROM TaxInvoiceEntity t
                      WHERE t.id = s.invoiceId
                        AND t.felUuid IS NOT NULL
                        AND TRIM(t.felUuid) <> ''
                  )
              )
              AND NOT EXISTS (
                  SELECT 1 FROM TaxInvoiceEntity t2
                  WHERE UPPER(TRIM(COALESCE(t2.sourceType, ''))) = 'KIOSK_SALE'
                    AND t2.sourceId = s.id
                    AND t2.felUuid IS NOT NULL
                    AND TRIM(t2.felUuid) <> ''
              )
            ORDER BY s.soldAt ASC, s.id ASC
            """)
    List<KioskSaleEntity> findPendingFelCertificationByKioskLocationId(
            @Param("kioskLocationId") Long kioskLocationId,
            @Param("minSaleDate") LocalDate minSaleDate
    );

    /**
     * Depósitos bancarios por día de venta ({@code saleDate}), igual que el resto de reportes de ventas.
     * No filtrar por {@code depositRecordedAt}: la boleta puede registrarse otro día.
     */
    @Query("""
            SELECT s FROM KioskSaleEntity s
            WHERE s.depositSlipNumber IS NOT NULL
              AND TRIM(s.depositSlipNumber) <> ''
              AND UPPER(TRIM(s.status)) = 'COMPLETED'
              AND s.saleDate >= :startDate
              AND s.saleDate <= :endDate
              AND (:kioskLocationId IS NULL OR s.kioskLocationId = :kioskLocationId)
              AND (
                  s.paymentMethod IS NULL
                  OR TRIM(s.paymentMethod) = ''
                  OR UPPER(TRIM(s.paymentMethod)) IN ('EFECTIVO', 'CASH', 'MIXTO', 'MIXED')
                  OR UPPER(TRIM(s.paymentMethod)) LIKE '%EFECTIVO%'
                  OR COALESCE(s.cashAmount, 0) > 0
              )
            ORDER BY s.saleDate ASC, COALESCE(s.depositRecordedAt, s.soldAt) ASC, s.id ASC
            """)
    List<KioskSaleEntity> findForBankDepositReport(
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate,
            @Param("kioskLocationId") Long kioskLocationId
    );

    @Query("""
            SELECT s FROM KioskSaleEntity s
            WHERE UPPER(TRIM(s.status)) = 'COMPLETED'
              AND (
                  UPPER(TRIM(s.paymentMethod)) IN ('TARJETA', 'CARD', 'TRANSFERENCIA')
                  OR UPPER(TRIM(s.paymentMethod)) LIKE '%TARJETA%'
                  OR UPPER(TRIM(s.paymentMethod)) LIKE '%CARD%'
                  OR UPPER(TRIM(s.paymentMethod)) IN ('MIXTO', 'MIXED')
                  OR COALESCE(s.cardAmount, 0) > 0
              )
              AND s.soldAt >= :startAt
              AND s.soldAt < :endAt
              AND (:kioskLocationId IS NULL OR s.kioskLocationId = :kioskLocationId)
            ORDER BY s.soldAt ASC, s.id ASC
            """)
    List<KioskSaleEntity> findForVoucherReport(
            @Param("startAt") java.time.LocalDateTime startAt,
            @Param("endAt") java.time.LocalDateTime endAt,
            @Param("kioskLocationId") Long kioskLocationId
    );

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
