package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconReceiptApplicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ReconReceiptApplicationRepository extends JpaRepository<ReconReceiptApplicationEntity, Long> {
    List<ReconReceiptApplicationEntity> findByCaseIdAndStatus(Long caseId, Recon.RowStatus status);

    List<ReconReceiptApplicationEntity> findByCaseIdOrderByIdAsc(Long caseId);

    List<ReconReceiptApplicationEntity> findByReceiptIdAndStatus(Long receiptId, Recon.RowStatus status);

    List<ReconReceiptApplicationEntity> findByCaseIdInAndStatus(Collection<Long> caseIds, Recon.RowStatus status);

    List<ReconReceiptApplicationEntity> findByReceiptIdIn(Collection<Long> receiptIds);
}
