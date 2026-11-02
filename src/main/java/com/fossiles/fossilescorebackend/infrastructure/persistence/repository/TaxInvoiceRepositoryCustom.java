package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;

import java.util.List;

public interface TaxInvoiceRepositoryCustom {

    List<TaxInvoiceEntity> search(TaxInvoiceSearchFilter filter);

    /** Conteo por estado con los mismos filtros del listado, sin estado ni pestaña de certificación. */
    List<Object[]> countGroupByStatus(TaxInvoiceSearchFilter filter);
}
