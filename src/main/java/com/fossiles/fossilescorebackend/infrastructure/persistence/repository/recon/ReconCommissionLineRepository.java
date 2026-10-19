package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCommissionLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReconCommissionLineRepository extends JpaRepository<ReconCommissionLineEntity, Long> {
    Optional<ReconCommissionLineEntity> findByCaseId(Long caseId);

    List<ReconCommissionLineEntity> findByCaseIdIn(Collection<Long> caseIds);

    List<ReconCommissionLineEntity> findByMonth(String month);
}
