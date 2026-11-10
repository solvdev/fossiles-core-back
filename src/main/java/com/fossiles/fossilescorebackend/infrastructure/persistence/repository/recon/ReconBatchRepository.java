package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconBatchEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReconBatchRepository extends JpaRepository<ReconBatchEntity, Long> {
    Optional<ReconBatchEntity> findByCode(String code);

    Optional<ReconBatchEntity> findByIdempotencyKey(String idempotencyKey);

    long countByCodeStartingWith(String prefix);

    List<ReconBatchEntity> findAllByOrderByIdDesc();

    /** Bloquea el lote mientras se cierra o anula (evita cierres simultáneos). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ReconBatchEntity b where b.id = :id")
    Optional<ReconBatchEntity> findByIdForUpdate(@Param("id") Long id);
}
