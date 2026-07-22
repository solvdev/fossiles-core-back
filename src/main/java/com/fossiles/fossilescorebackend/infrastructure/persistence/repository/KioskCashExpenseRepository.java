package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCashExpenseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface KioskCashExpenseRepository extends JpaRepository<KioskCashExpenseEntity, Long> {
    List<KioskCashExpenseEntity> findByCashSessionIdOrderByCreatedAtAscIdAsc(Long cashSessionId);

    List<KioskCashExpenseEntity> findByKioskSaleIdOrderByCreatedAtAscIdAsc(Long kioskSaleId);

    @Query("""
            SELECT COALESCE(SUM(e.amount), 0)
            FROM KioskCashExpenseEntity e
            WHERE e.kioskSaleId = :saleId
            """)
    BigDecimal sumAmountByKioskSaleId(@Param("saleId") Long saleId);

    @Query("""
            SELECT e.kioskSaleId, COALESCE(SUM(e.amount), 0)
            FROM KioskCashExpenseEntity e
            WHERE e.kioskSaleId IN :saleIds
            GROUP BY e.kioskSaleId
            """)
    List<Object[]> sumAmountByKioskSaleIds(@Param("saleIds") Collection<Long> saleIds);

    @Query("""
            SELECT COALESCE(SUM(e.amount), 0)
            FROM KioskCashExpenseEntity e
            WHERE e.cashSessionId = :sessionId
            """)
    BigDecimal sumAmountByCashSessionId(@Param("sessionId") Long sessionId);

    @Query("""
            SELECT e FROM KioskCashExpenseEntity e
            WHERE e.createdAt >= :startAt
              AND e.createdAt < :endAt
              AND e.cashSessionId IN (
                  SELECT s.id FROM KioskCashSessionEntity s
                  WHERE (:kioskLocationId IS NULL OR s.kioskLocationId = :kioskLocationId)
              )
            ORDER BY e.createdAt ASC, e.id ASC
            """)
    List<KioskCashExpenseEntity> findForReport(
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt,
            @Param("kioskLocationId") Long kioskLocationId
    );
}
