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

    /**
     * Ventas reales por producto/color/kiosko. Excluye anuladas y ventas de piloto.
     */
    @Query("""
            SELECT i.productId,
                   i.colorId,
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
            GROUP BY i.productId, i.colorId, s.kioskLocationId
            """)
    List<Object[]> aggregateCompletedSalesByProductColor(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("kioskLocationIds") List<Long> kioskLocationIds
    );
}
