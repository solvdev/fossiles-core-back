package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DocumentSeriesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentSeriesRepository extends JpaRepository<DocumentSeriesEntity, Long> {
    Optional<DocumentSeriesEntity> findByDocumentTypeAndSeries(String documentType, String series);
    List<DocumentSeriesEntity> findByDocumentType(String documentType);
    List<DocumentSeriesEntity> findByStatus(String status);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DocumentSeriesEntity d WHERE d.documentType = :documentType AND d.series = :series")
    Optional<DocumentSeriesEntity> findByDocumentTypeAndSeriesForUpdate(@Param("documentType") String documentType, @Param("series") String series);
    
    @Modifying
    @Query("UPDATE DocumentSeriesEntity d SET d.currentCorrelative = d.currentCorrelative + 1 WHERE d.id = :id")
    int incrementCorrelative(@Param("id") Long id);
}

