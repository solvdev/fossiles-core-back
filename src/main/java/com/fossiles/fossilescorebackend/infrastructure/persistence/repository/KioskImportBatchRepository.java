package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskImportBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KioskImportBatchRepository extends JpaRepository<KioskImportBatchEntity, Long> {

    List<KioskImportBatchEntity> findAllByOrderByCreatedAtDesc();

    Optional<KioskImportBatchEntity> findFirstByFileSha256AndStatusOrderByCreatedAtDesc(String fileSha256, String status);
}
