package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PrintFormatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrintFormatRepository extends JpaRepository<PrintFormatEntity, Long> {
    List<PrintFormatEntity> findByDocumentType(String documentType);
    Optional<PrintFormatEntity> findByDocumentTypeAndIsDefaultTrue(String documentType);
    List<PrintFormatEntity> findByIsDefaultTrue();
}

