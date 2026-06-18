package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TaxInvoiceRepository extends JpaRepository<TaxInvoiceEntity, Long>, TaxInvoiceRepositoryCustom {

    Optional<TaxInvoiceEntity> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    List<TaxInvoiceEntity> findBySourceTypeAndSourceIdOrderByIdDesc(String sourceType, Long sourceId);

    @Query("SELECT t.status, COUNT(t) FROM TaxInvoiceEntity t GROUP BY t.status")
    List<Object[]> countGroupByStatus();

    boolean existsBySourceTypeAndSourceIdAndStatus(String sourceType, Long sourceId, String status);
}
