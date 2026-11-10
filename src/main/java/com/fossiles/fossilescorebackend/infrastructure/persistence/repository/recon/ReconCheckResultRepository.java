package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCheckResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ReconCheckResultRepository extends JpaRepository<ReconCheckResultEntity, Long> {
    List<ReconCheckResultEntity> findByCaseIdOrderByIdAsc(Long caseId);

    List<ReconCheckResultEntity> findByCaseIdIn(Collection<Long> caseIds);

    @Modifying
    @Query("delete from ReconCheckResultEntity r where r.caseId = :caseId")
    void deleteByCaseId(@Param("caseId") Long caseId);
}
