package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaxInvoiceAttemptRepository extends JpaRepository<TaxInvoiceAttemptEntity, Long> {

    List<TaxInvoiceAttemptEntity> findByTaxInvoiceIdOrderByAttemptNumberDesc(Long taxInvoiceId);

    Optional<TaxInvoiceAttemptEntity> findTopByTaxInvoiceIdOrderByAttemptNumberDesc(Long taxInvoiceId);

    int countByTaxInvoiceId(Long taxInvoiceId);
}
