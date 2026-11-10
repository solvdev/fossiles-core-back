package com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCaseEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReconCaseRepository extends JpaRepository<ReconCaseEntity, Long>, JpaSpecificationExecutor<ReconCaseEntity> {

    Optional<ReconCaseEntity> findByOnlineSaleId(Long onlineSaleId);

    List<ReconCaseEntity> findByOnlineSaleIdIn(Collection<Long> onlineSaleIds);

    List<ReconCaseEntity> findByBatchId(Long batchId);

    /** Casos del lote bloqueados para el cierre. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ReconCaseEntity c where c.batchId = :batchId order by c.id")
    List<ReconCaseEntity> findByBatchIdForUpdate(@Param("batchId") Long batchId);

    List<ReconCaseEntity> findByGuideNumberIgnoreCase(String guideNumber);

    List<ReconCaseEntity> findByShipmentNumberIgnoreCase(String shipmentNumber);

    List<ReconCaseEntity> findByShipmentNumberIn(Collection<String> shipmentNumbers);

    List<ReconCaseEntity> findByGuideNumberIn(Collection<String> guides);

    @Query("select c from ReconCaseEntity c where c.saleDate between :from and :to and c.status in :statuses and c.batchId is null order by c.saleDate, c.id")
    List<ReconCaseEntity> findAvailableForBatch(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                                @Param("statuses") Collection<Recon.Status> statuses);

    List<ReconCaseEntity> findBySaleDateBetween(LocalDate from, LocalDate to);

    long countByStatus(Recon.Status status);

    /** Casos abiertos con ese monto cobrado: solo sirven como candidatos para revisión humana, nunca para aplicar solos. */
    @Query("select c from ReconCaseEntity c where c.expectedAmount = :amount and c.excludedReason is null and c.closedAt is null "
            + "and c.receivedAmount = 0 order by c.saleDate desc")
    List<ReconCaseEntity> findOpenWithoutFundsByExpectedAmount(@Param("amount") BigDecimal amount, Pageable pageable);
}
