package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialRequestRepository extends JpaRepository<MaterialRequestEntity, Long> {
    List<MaterialRequestEntity> findByStatus(String status);
    List<MaterialRequestEntity> findByOriginAndOriginReferenceId(String origin, Long originReferenceId);
    List<MaterialRequestEntity> findByOriginAndOriginReferenceIdAndStatusIn(
            String origin, Long originReferenceId, List<String> statuses);
}

