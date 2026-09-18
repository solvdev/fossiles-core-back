package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    boolean existsBySeriesCodeAndSlipNumber(String seriesCode, String slipNumber);

    Optional<KioskExchangeSlipEntity> findBySlipNumber(String slipNumber);

    Optional<KioskExchangeSlipEntity> findBySeriesCodeAndSlipNumber(String seriesCode, String slipNumber);

    List<KioskExchangeSlipEntity> findByPhysicalCountId(Long physicalCountId);

    List<KioskExchangeSlipEntity> findByKioskLocationIdAndPhysicalCountIdOrderByCreatedAtDesc(
            Long kioskLocationId,
            Long physicalCountId
    );

    @Query("SELECT s FROM KioskExchangeSlipEntity s "
            + "WHERE UPPER(s.slipType) = 'EXCHANGE' "
            + "AND UPPER(s.status) = 'COMPLETED'")
    List<KioskExchangeSlipEntity> findCompletedExchanges();

    @Query("SELECT s FROM KioskExchangeSlipEntity s "
            + "WHERE UPPER(s.slipType) = 'EXCHANGE' "
            + "AND UPPER(s.status) = 'COMPLETED' "
            + "AND s.differenceAmount IS NOT NULL AND s.differenceAmount > 0")
    List<KioskExchangeSlipEntity> findCompletedExchangesWithDifference();

    /** Egresos que el kardex cuenta como Vtas.: solo diferencia a cobrar (a favor de la empresa). */
    @Query("SELECT s FROM KioskExchangeSlipEntity s "
            + "WHERE s.kioskLocationId = :locationId "
            + "AND UPPER(s.slipType) = 'EXCHANGE' "
            + "AND s.differenceAmount IS NOT NULL AND s.differenceAmount > 0")
    List<KioskExchangeSlipEntity> findPricedExchangesByKioskLocationId(
            @Param("locationId") Long locationId
    );
}
