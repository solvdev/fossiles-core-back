package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.QaRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QaRecordRepository extends JpaRepository<QaRecordEntity, Long> {
    List<QaRecordEntity> findByProductionOrderId(Long productionOrderId);
    List<QaRecordEntity> findByTaskId(Long taskId);
    List<QaRecordEntity> findByStatus(String status);

    @Query("SELECT q FROM QaRecordEntity q WHERE q.status IN ('PENDING', 'REWORK') ORDER BY q.createdAt DESC")
    List<QaRecordEntity> findPendingRecords();

    @Query("SELECT q FROM QaRecordEntity q WHERE q.status = 'PENDING' AND q.quantityDelivered > 0 ORDER BY q.deliveredAt ASC")
    List<QaRecordEntity> findReadyForApproval();

    @Query("SELECT q FROM QaRecordEntity q WHERE q.status IN ('REJECTED', 'REWORK') ORDER BY q.createdAt DESC")
    List<QaRecordEntity> findRejectedAndRework();
}

