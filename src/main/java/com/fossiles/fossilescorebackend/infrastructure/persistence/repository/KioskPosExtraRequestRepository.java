package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPosExtraRequestEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface KioskPosExtraRequestRepository extends JpaRepository<KioskPosExtraRequestEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM KioskPosExtraRequestEntity r WHERE r.id = :id")
    Optional<KioskPosExtraRequestEntity> findByIdForUpdate(@Param("id") Long id);

    List<KioskPosExtraRequestEntity> findByStatusOrderByRequestedAtAsc(String status);

    /** Solicitudes del kiosko del día en los estados indicados (las que el POS necesita ver). */
    List<KioskPosExtraRequestEntity> findByKioskLocationIdAndRequestDateAndStatusInOrderByRequestedAtAsc(
            Long kioskLocationId,
            LocalDate requestDate,
            Collection<String> statuses
    );
}
