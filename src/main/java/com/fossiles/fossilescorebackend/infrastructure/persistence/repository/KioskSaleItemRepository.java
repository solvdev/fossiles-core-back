package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface KioskSaleItemRepository extends JpaRepository<KioskSaleItemEntity, Long> {
    List<KioskSaleItemEntity> findByKioskSaleIdOrderByIdAsc(Long kioskSaleId);

    java.util.Optional<KioskSaleItemEntity> findByIdAndKioskSale_Id(Long id, Long kioskSaleId);

    /** Ítems de todas las ventas del rango en una sola consulta (dashboards). */
    @Query("""
            SELECT new com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow(
                i.kioskSale.id, i.productId, i.productCode, i.productName, i.quantity, i.lineTotal)
            FROM KioskSaleItemEntity i
            WHERE i.kioskSale.saleDate >= :startDate AND i.kioskSale.saleDate <= :endDate
            ORDER BY i.id ASC
            """)
    List<com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow>
            findRowsBySaleDateBetween(
                    @Param("startDate") LocalDate startDate,
                    @Param("endDate") LocalDate endDate
            );

    /** Ítems de un conjunto acotado de ventas en una sola consulta. */
    @Query("""
            SELECT new com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow(
                i.kioskSale.id, i.productId, i.productCode, i.productName, i.quantity, i.lineTotal)
            FROM KioskSaleItemEntity i
            WHERE i.kioskSale.id IN :saleIds
            ORDER BY i.id ASC
            """)
    List<com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow>
            findRowsByKioskSaleIdIn(@Param("saleIds") java.util.Collection<Long> saleIds);

    /**
     * Ventas reales por producto/color/kiosko. Excluye anuladas y ventas de piloto.
     * Agrupa por color_id; el nombre se usa solo como respaldo si el id viene nulo.
     */
    @Query("""
            SELECT i.productId,
                   i.colorId,
                   i.colorName,
                   s.kioskLocationId,
                   COALESCE(SUM(i.quantity), 0),
                   COALESCE(SUM(i.lineTotal), 0),
                   COUNT(DISTINCT s.id)
            FROM KioskSaleItemEntity i
            JOIN i.kioskSale s
            WHERE (s.testSale IS NULL OR s.testSale = false)
              AND (s.status IS NULL OR UPPER(TRIM(s.status)) <> 'VOID')
              AND s.saleDate >= :startDate
              AND s.saleDate <= :endDate
              AND s.kioskLocationId IN :kioskLocationIds
            GROUP BY i.productId, i.colorId, i.colorName, s.kioskLocationId
            """)
    List<Object[]> aggregateCompletedSalesByProductColor(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("kioskLocationIds") List<Long> kioskLocationIds
    );
}
