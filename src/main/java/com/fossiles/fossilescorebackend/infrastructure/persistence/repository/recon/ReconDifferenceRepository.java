package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconDifferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReconDifferenceRepository extends JpaRepository<ReconDifferenceEntity, Long> {
    List<ReconDifferenceEntity> findByCaseIdOrderByIdDesc(Long caseId);

    Optional<ReconDifferenceEntity> findFirstByCaseIdAndStatusOrderByIdDesc(Long caseId, Recon.DifferenceStatus status);

    List<ReconDifferenceEntity> findByCaseIdInAndStatus(Collection<Long> caseIds, Recon.DifferenceStatus status);

    List<ReconDifferenceEntity> findByStatus(Recon.DifferenceStatus status);
}
