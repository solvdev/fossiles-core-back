package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;

import java.time.LocalDateTime;
import java.util.List;

public interface TaxInvoiceRepositoryCustom {

    List<TaxInvoiceEntity> search(
            String sourceType,
            String status,
            String customerTaxIdPattern,
            String internalNumberPattern,
            String certificationFilter,
            LocalDateTime from,
            LocalDateTime to
    );

    List<Object[]> countGroupByStatus(
            String sourceType,
            String customerTaxIdPattern,
            String internalNumberPattern,
            LocalDateTime from,
            LocalDateTime to
    );
}
