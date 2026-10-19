package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconExportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconExportRepository extends JpaRepository<ReconExportEntity, Long> {
    List<ReconExportEntity> findByBatchIdOrderByIdDesc(Long batchId);

    List<ReconExportEntity> findAllByOrderByIdDesc();
}
