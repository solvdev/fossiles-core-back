package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleSequenceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface KioskSaleSequenceRepository extends JpaRepository<KioskSaleSequenceEntity, LocalDate> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<KioskSaleSequenceEntity> findWithLockBySaleDate(LocalDate saleDate);
}
