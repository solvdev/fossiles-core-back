package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseCompensationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface PurchaseCompensationRepository extends JpaRepository<PurchaseCompensationEntity, Long> {

    /** Compensaciones donde esta compra APORTÓ sobrante */
    List<PurchaseCompensationEntity> findBySourcePurchaseId(Long sourcePurchaseId);

    /** Compensaciones donde esta compra RECIBIÓ compensación */
    List<PurchaseCompensationEntity> findByTargetPurchaseId(Long targetPurchaseId);

    /** Todas las compensaciones relacionadas con una compra (como origen o destino) */
    @Query("SELECT c FROM PurchaseCompensationEntity c WHERE c.sourcePurchaseId = :purchaseId OR c.targetPurchaseId = :purchaseId ORDER BY c.createdAt DESC")
    List<PurchaseCompensationEntity> findByPurchaseId(@Param("purchaseId") Long purchaseId);

    /** Total compensado DESDE esta compra (sobrante cedido) */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM PurchaseCompensationEntity c WHERE c.sourcePurchaseId = :purchaseId")
    BigDecimal sumCompensationsGiven(@Param("purchaseId") Long purchaseId);

    /** Total compensado HACIA esta compra (sobrante recibido) */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM PurchaseCompensationEntity c WHERE c.targetPurchaseId = :purchaseId")
    BigDecimal sumCompensationsReceived(@Param("purchaseId") Long purchaseId);
}

