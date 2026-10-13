package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface OnlineSaleItemRepository extends JpaRepository<OnlineSaleItemEntity, Long> {

    List<OnlineSaleItemEntity> findByOnlineSaleIdOrderByIdAsc(Long onlineSaleId);

    List<OnlineSaleItemEntity> findByOnlineSaleIdInOrderByIdAsc(java.util.Collection<Long> onlineSaleIds);

    /** Ítems de todas las ventas online del rango en una sola consulta (dashboards). */
    @Query("""
            SELECT i FROM OnlineSaleItemEntity i, OnlineSaleEntity s
            WHERE i.onlineSaleId = s.id
              AND s.saleDate >= :startDate AND s.saleDate <= :endDate
            ORDER BY i.id ASC
            """)
    List<OnlineSaleItemEntity> findBySaleDateBetween(
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate);

    @Modifying
    @Transactional
    @Query("DELETE FROM OnlineSaleItemEntity e WHERE e.onlineSaleId = :saleId")
    void deleteByOnlineSaleId(@Param("saleId") Long saleId);
}

