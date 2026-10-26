package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPosHeldCartEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface KioskPosHeldCartRepository extends JpaRepository<KioskPosHeldCartEntity, Long> {

    List<KioskPosHeldCartEntity> findByKioskLocationIdAndHeldDateOrderByCreatedAtAsc(
            Long kioskLocationId,
            LocalDate heldDate
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM KioskPosHeldCartEntity c WHERE c.id = :id")
    Optional<KioskPosHeldCartEntity> findByIdForUpdate(@Param("id") Long id);
}
