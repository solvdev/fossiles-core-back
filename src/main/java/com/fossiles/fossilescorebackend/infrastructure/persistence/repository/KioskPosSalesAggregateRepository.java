package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Agregados de ventas POS reales para finanzas de kioscos.
 * Regla canonica de venta real (KioskPosService.countsForProductionMetrics):
 * test_sale = false y status no VOID/CANCELLED (status nulo cuenta).
 */
@Repository
public interface KioskPosSalesAggregateRepository extends JpaRepository<KioskSaleEntity, Long> {

    /** Filas: [kioskLocationId (Long), saleDate (LocalDate), SUM(totalAmount) (BigDecimal)]. */
    @Query("""
            SELECT s.kioskLocationId, s.saleDate, SUM(s.totalAmount)
            FROM KioskSaleEntity s
            WHERE s.kioskLocationId IN :locationIds
              AND s.saleDate BETWEEN :from AND :to
              AND s.testSale = false
              AND (s.status IS NULL OR UPPER(TRIM(s.status)) NOT IN ('VOID', 'CANCELLED'))
            GROUP BY s.kioskLocationId, s.saleDate
            """)
    List<Object[]> sumRealSalesByLocationAndDate(
            @Param("locationIds") Collection<Long> locationIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Filas: [kioskLocationId (Long), MIN(saleDate) (LocalDate)] sobre ventas reales. */
    @Query("""
            SELECT s.kioskLocationId, MIN(s.saleDate)
            FROM KioskSaleEntity s
            WHERE s.kioskLocationId IN :locationIds
              AND s.testSale = false
              AND (s.status IS NULL OR UPPER(TRIM(s.status)) NOT IN ('VOID', 'CANCELLED'))
            GROUP BY s.kioskLocationId
            """)
    List<Object[]> findFirstRealSaleDateByLocation(@Param("locationIds") Collection<Long> locationIds);
}
