package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KioskExchangeSlipRepository extends JpaRepository<KioskExchangeSlipEntity, Long> {
    List<KioskExchangeSlipEntity> findByKioskLocationIdOrderByCreatedAtDesc(Long kioskLocationId);

    List<KioskExchangeSlipEntity> findAllByOrderByCreatedAtDesc();

    Optional<KioskExchangeSlipEntity> findByIdAndKioskLocationId(Long id, Long kioskLocationId);

    List<KioskExchangeSlipEntity> findByKioskLocationIdAndStatusOrderByCreatedAtDesc(
            Long kioskLocationId,
            String status
    );

    List<KioskExchangeSlipEntity> findByStatusOrderByCreatedAtDesc(String status);

    boolean existsBySlipNumber(String slipNumber);

    Optional<KioskExchangeSlipEntity> findBySlipNumber(String slipNumber);
}
