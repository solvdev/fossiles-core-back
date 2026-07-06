package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCashSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KioskCashSessionRepository extends JpaRepository<KioskCashSessionEntity, Long> {
    Optional<KioskCashSessionEntity> findFirstByKioskLocationIdAndStatusOrderByOpenedAtDesc(
            Long kioskLocationId,
            String status
    );

    @Query("""
            SELECT s FROM KioskCashSessionEntity s
            WHERE s.kioskLocationId = :kioskLocationId
              AND s.openedAt >= :startAt
              AND s.openedAt < :endAt
            ORDER BY s.openedAt DESC
            """)
    List<KioskCashSessionEntity> findByKioskLocationIdAndOpenedAtBetween(
            @Param("kioskLocationId") Long kioskLocationId,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );
}
