package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskDailySalesHistEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface KioskDailySalesHistRepository
        extends JpaRepository<KioskDailySalesHistEntity, KioskDailySalesHistEntity.Key> {

    @Query("""
            SELECT h FROM KioskDailySalesHistEntity h
            WHERE h.siteId IN :siteIds
              AND h.saleDate BETWEEN :from AND :to
            """)
    List<KioskDailySalesHistEntity> findBySitesAndRange(
            @Param("siteIds") Collection<Long> siteIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("""
            SELECT h FROM KioskDailySalesHistEntity h
            WHERE h.saleDate BETWEEN :from AND :to
            """)
    List<KioskDailySalesHistEntity> findByRange(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
