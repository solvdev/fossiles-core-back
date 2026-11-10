package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconReceiptEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReconReceiptRepository extends JpaRepository<ReconReceiptEntity, Long> {
    Optional<ReconReceiptEntity> findByRowHash(String rowHash);

    List<ReconReceiptEntity> findByImportFileId(Long importFileId);

    List<ReconReceiptEntity> findByRowHashIn(Collection<String> rowHashes);

    List<ReconReceiptEntity> findByStatusOrderByIdDesc(Recon.RowStatus status, Pageable pageable);

    /** Fondos activos sin aplicar por completo (lo aplicado se suma de las aplicaciones activas). */
    @Query("select r from ReconReceiptEntity r where r.status = :active "
            + "and r.amount > coalesce((select sum(a.amountApplied) from ReconReceiptApplicationEntity a "
            + "where a.receiptId = r.id and a.status = :active), 0) order by r.id desc")
    List<ReconReceiptEntity> findUnappliedByStatus(@Param("active") Recon.RowStatus active, Pageable pageable);

    default List<ReconReceiptEntity> findUnapplied(Pageable pageable) {
        return findUnappliedByStatus(Recon.RowStatus.ACTIVE, pageable);
    }

    List<ReconReceiptEntity> findByIdIn(Collection<Long> ids);

    @Query("select r from ReconReceiptEntity r where lower(r.reference) = lower(:ref) or lower(r.authorizationNumber) = lower(:ref)")
    List<ReconReceiptEntity> findByReferenceOrAuthorization(@Param("ref") String ref);
}
