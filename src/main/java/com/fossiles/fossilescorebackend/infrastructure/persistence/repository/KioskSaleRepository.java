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
    List<KioskSaleEntity> findByKioskLocationIdOrderBySoldAtDesc(Long kioskLocationId);
    List<KioskSaleEntity> findByKioskLocationIdAndSaleDateBetweenOrderBySoldAtDesc(
            Long kioskLocationId,
            LocalDate startDate,
            LocalDate endDate
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
            SELECT
                COALESCE(SUM(CASE WHEN s.saleDate = :today THEN s.totalAmount ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN s.saleDate = :today THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN s.saleDate = :todayLastYear THEN s.totalAmount ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN s.saleDate = :todayLastYear THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN s.saleDate >= :lastMonthStart AND s.saleDate <= :lastMonthEnd THEN s.totalAmount ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN s.saleDate >= :lastMonthStart AND s.saleDate <= :lastMonthEnd THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN s.saleDate >= :monthToDateStart AND s.saleDate <= :today THEN s.totalAmount ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN s.saleDate >= :monthToDateStart AND s.saleDate <= :today THEN 1 ELSE 0 END), 0)
            FROM KioskSaleEntity s
            WHERE s.kioskLocationId = :kioskLocationId
              AND s.saleDate >= :rangeStart
              AND s.saleDate <= :rangeEnd
              AND (s.status IS NULL OR UPPER(s.status) <> 'VOID')
            """)
    Object[] aggregateManagerDashboardMetrics(
            @Param("kioskLocationId") Long kioskLocationId,
            @Param("today") LocalDate today,
            @Param("todayLastYear") LocalDate todayLastYear,
            @Param("lastMonthStart") LocalDate lastMonthStart,
            @Param("lastMonthEnd") LocalDate lastMonthEnd,
            @Param("monthToDateStart") LocalDate monthToDateStart,
            @Param("rangeStart") LocalDate rangeStart,
            @Param("rangeEnd") LocalDate rangeEnd
    );
}
