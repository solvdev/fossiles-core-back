package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TaxInvoiceRepositoryImpl implements TaxInvoiceRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<TaxInvoiceEntity> search(
            String sourceType,
            String status,
            String customerTaxIdPattern,
            String internalNumberPattern,
            String certificationFilter,
            LocalDateTime from,
            LocalDateTime to
    ) {
        StringBuilder jpql = new StringBuilder("SELECT t FROM TaxInvoiceEntity t WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();

        if (sourceType != null) {
            jpql.append(" AND t.sourceType = :sourceType");
            params.put("sourceType", sourceType);
        }
        if (status != null) {
            jpql.append(" AND t.status = :status");
            params.put("status", status);
        }
        if (customerTaxIdPattern != null) {
            jpql.append(" AND UPPER(t.customerTaxId) LIKE :customerTaxIdPattern");
            params.put("customerTaxIdPattern", customerTaxIdPattern);
        }
        if (internalNumberPattern != null) {
            jpql.append(" AND UPPER(t.internalNumber) LIKE :internalNumberPattern");
            params.put("internalNumberPattern", internalNumberPattern);
        }
        if (certificationFilter != null) {
            switch (certificationFilter) {
                case "SIGNED" -> jpql.append(" AND t.status = 'CERTIFIED'");
                case "UNSIGNED" -> jpql.append(" AND t.status IN ('FAILED', 'SKIPPED', 'DRAFT')");
                case "ERROR" -> jpql.append(" AND t.status = 'FAILED'");
                case "VOID" -> jpql.append(" AND t.status = 'VOID'");
                default -> { }
            }
        }
        if (from != null) {
            jpql.append(" AND t.issuedAt >= :from");
            params.put("from", from);
        }
        if (to != null) {
            jpql.append(" AND t.issuedAt <= :to");
            params.put("to", to);
        }

        jpql.append(" ORDER BY t.issuedAt DESC, t.id DESC");

        TypedQuery<TaxInvoiceEntity> query = entityManager.createQuery(jpql.toString(), TaxInvoiceEntity.class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }
}
