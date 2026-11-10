package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconLedgerEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReconLedgerEntryRepository extends JpaRepository<ReconLedgerEntryEntity, Long> {
    Optional<ReconLedgerEntryEntity> findByIdempotencyKey(String idempotencyKey);

    List<ReconLedgerEntryEntity> findByCaseIdOrderByIdAsc(Long caseId);

    List<ReconLedgerEntryEntity> findByCaseIdIn(Collection<Long> caseIds);

    List<ReconLedgerEntryEntity> findByBatchIdAndEntryTypeIn(Long batchId, Collection<Recon.LedgerType> types);
}
