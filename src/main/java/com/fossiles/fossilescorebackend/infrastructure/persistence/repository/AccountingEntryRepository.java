package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.AccountingEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AccountingEntryRepository extends JpaRepository<AccountingEntryEntity, Long> {
    List<AccountingEntryEntity> findByDocumentTypeAndDocumentId(String documentType, Long documentId);
    
    List<AccountingEntryEntity> findByDocumentType(String documentType);
    
    List<AccountingEntryEntity> findByAccountCode(String accountCode);
    
    @Query("SELECT e FROM AccountingEntryEntity e WHERE e.entryDate BETWEEN :startDate AND :endDate")
    List<AccountingEntryEntity> findByEntryDateBetween(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
    
    @Query("SELECT e FROM AccountingEntryEntity e WHERE e.documentType = :documentType AND e.entryDate BETWEEN :startDate AND :endDate")
    List<AccountingEntryEntity> findByDocumentTypeAndEntryDateBetween(
            @Param("documentType") String documentType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
}

