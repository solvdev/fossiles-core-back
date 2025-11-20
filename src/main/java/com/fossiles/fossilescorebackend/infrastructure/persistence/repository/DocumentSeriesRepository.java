package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DocumentSeriesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DocumentSeriesRepository extends JpaRepository<DocumentSeriesEntity, Long> {
    Optional<DocumentSeriesEntity> findByDocType(String docType);
}

