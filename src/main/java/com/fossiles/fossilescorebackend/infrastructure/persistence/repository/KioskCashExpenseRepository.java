package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCashExpenseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface KioskCashExpenseRepository extends JpaRepository<KioskCashExpenseEntity, Long> {
    List<KioskCashExpenseEntity> findByCashSessionIdOrderByCreatedAtAscIdAsc(Long cashSessionId);

    @Query("""
            SELECT COALESCE(SUM(e.amount), 0)
            FROM KioskCashExpenseEntity e
            WHERE e.cashSessionId = :sessionId
            """)
    BigDecimal sumAmountByCashSessionId(@Param("sessionId") Long sessionId);
}
