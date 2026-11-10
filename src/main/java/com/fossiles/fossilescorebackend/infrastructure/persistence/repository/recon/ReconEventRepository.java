package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconEventRepository extends JpaRepository<ReconEventEntity, Long> {
    List<ReconEventEntity> findByCaseIdOrderByIdDesc(Long caseId);

    List<ReconEventEntity> findByBatchIdOrderByIdDesc(Long batchId);
}
