package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface KioscoPhysicalCountRepository extends JpaRepository<KioscoPhysicalCountEntity, Long> {

    Optional<KioscoPhysicalCountEntity> findByLocationIdAndPeriodFromAndPeriodTo(
            Long locationId, LocalDate periodFrom, LocalDate periodTo);

    List<KioscoPhysicalCountEntity> findByLocationIdOrderByGeneratedAtDesc(Long locationId);

    /** Conteos revisados con diferencias pendientes (para el panel de alertas), opcionalmente por kiosko. */
    List<KioscoPhysicalCountEntity> findByStatusAndMaxAbsDiffGreaterThanEqualAndLocationIdOrderByReviewedAtAsc(
            KioscoPhysicalCountStatus status, int minAbsDiff, Long locationId);

    List<KioscoPhysicalCountEntity> findByStatusAndMaxAbsDiffGreaterThanEqualOrderByReviewedAtAsc(
            KioscoPhysicalCountStatus status, int minAbsDiff);

    /** Conteos revisados con diferencias pendientes, sin notificar aun, cuya revision ya cumplio el plazo de gracia. */
    List<KioscoPhysicalCountEntity> findByStatusAndMaxAbsDiffGreaterThanEqualAndDiffNotifiedAtIsNullAndReviewedAtLessThanEqual(
            KioscoPhysicalCountStatus status, int minAbsDiff, LocalDateTime reviewedBefore);
}
