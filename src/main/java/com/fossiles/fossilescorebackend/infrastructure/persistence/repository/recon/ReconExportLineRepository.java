package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconExportLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconExportLineRepository extends JpaRepository<ReconExportLineEntity, ReconExportLineEntity.Key> {
    List<ReconExportLineEntity> findByExportId(Long exportId);

    List<ReconExportLineEntity> findByCaseId(Long caseId);
}
