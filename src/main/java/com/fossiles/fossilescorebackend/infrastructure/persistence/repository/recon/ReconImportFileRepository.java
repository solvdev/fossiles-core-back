package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconImportFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReconImportFileRepository extends JpaRepository<ReconImportFileEntity, Long> {
    Optional<ReconImportFileEntity> findBySha256(String sha256);

    List<ReconImportFileEntity> findAllByOrderByIdDesc();
}
