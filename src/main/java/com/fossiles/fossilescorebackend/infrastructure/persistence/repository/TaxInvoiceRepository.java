package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaxInvoiceRepository extends JpaRepository<TaxInvoiceEntity, Long>, TaxInvoiceRepositoryCustom {

    Optional<TaxInvoiceEntity> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    Optional<TaxInvoiceEntity> findFirstByFelTransactionIdIgnoreCase(String felTransactionId);

    List<TaxInvoiceEntity> findBySourceTypeAndSourceIdOrderByIdDesc(String sourceType, Long sourceId);

    boolean existsBySourceTypeAndSourceIdAndStatus(String sourceType, Long sourceId, String status);
}
