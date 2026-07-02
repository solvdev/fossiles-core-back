package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipSequenceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KioskExchangeSlipSequenceRepository
        extends JpaRepository<KioskExchangeSlipSequenceEntity, KioskExchangeSlipSequenceEntity.Key> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM KioskExchangeSlipSequenceEntity s
            WHERE s.kioskLocationId = :kioskLocationId AND s.sequenceYear = :sequenceYear
            """)
    Optional<KioskExchangeSlipSequenceEntity> findWithLockByKioskAndYear(
            @Param("kioskLocationId") Long kioskLocationId,
            @Param("sequenceYear") Integer sequenceYear
    );
}
