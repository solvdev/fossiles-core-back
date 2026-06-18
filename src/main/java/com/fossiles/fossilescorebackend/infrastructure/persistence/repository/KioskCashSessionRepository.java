package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCashSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KioskCashSessionRepository extends JpaRepository<KioskCashSessionEntity, Long> {
    Optional<KioskCashSessionEntity> findFirstByKioskLocationIdAndStatusOrderByOpenedAtDesc(
            Long kioskLocationId,
            String status
    );
}
