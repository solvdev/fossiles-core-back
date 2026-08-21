package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TaxInvoiceRepositoryImpl implements TaxInvoiceRepositoryCustom {

    private static final List<String> UNSIGNED_STATUSES = List.of("FAILED", "SKIPPED", "DRAFT");

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
        appendCommonFilters(jpql, params, sourceType, customerTaxIdPattern, internalNumberPattern, from, to);

        if (status != null) {
            jpql.append(" AND UPPER(TRIM(t.status)) = :status");
            params.put("status", status.trim().toUpperCase(Locale.ROOT));
        }
        if (certificationFilter != null) {
            String filter = certificationFilter.trim().toUpperCase(Locale.ROOT);
            switch (filter) {
                case "SIGNED" -> jpql.append(" AND UPPER(TRIM(t.status)) = 'CERTIFIED'");
                case "UNSIGNED" -> {
                    jpql.append(" AND UPPER(TRIM(t.status)) IN :unsignedStatuses");
                    params.put("unsignedStatuses", UNSIGNED_STATUSES);
                }
                case "ERROR" -> jpql.append(" AND UPPER(TRIM(t.status)) = 'FAILED'");
                case "VOID" -> jpql.append(" AND UPPER(TRIM(t.status)) = 'VOID'");
                default -> { }
            }
        }

        jpql.append(" ORDER BY COALESCE(t.issuedAt, t.createdAt) DESC, t.id DESC");

        TypedQuery<TaxInvoiceEntity> query = entityManager.createQuery(jpql.toString(), TaxInvoiceEntity.class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }

    @Override
    public List<Object[]> countGroupByStatus(
            String sourceType,
            String customerTaxIdPattern,
            String internalNumberPattern,
            LocalDateTime from,
            LocalDateTime to
    ) {
        StringBuilder jpql = new StringBuilder(
                "SELECT UPPER(TRIM(t.status)), COUNT(t) FROM TaxInvoiceEntity t WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        appendCommonFilters(jpql, params, sourceType, customerTaxIdPattern, internalNumberPattern, from, to);
        jpql.append(" GROUP BY UPPER(TRIM(t.status))");

        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }

    private static void appendCommonFilters(
            StringBuilder jpql,
            Map<String, Object> params,
            String sourceType,
            String customerTaxIdPattern,
            String internalNumberPattern,
            LocalDateTime from,
            LocalDateTime to
    ) {
        if (sourceType != null) {
            jpql.append(" AND t.sourceType = :sourceType");
            params.put("sourceType", sourceType);
        }
        if (customerTaxIdPattern != null) {
            jpql.append(" AND UPPER(t.customerTaxId) LIKE :customerTaxIdPattern");
            params.put("customerTaxIdPattern", customerTaxIdPattern);
        }
        if (internalNumberPattern != null) {
            jpql.append(" AND UPPER(t.internalNumber) LIKE :internalNumberPattern");
            params.put("internalNumberPattern", internalNumberPattern);
        }
        // Usar fecha efectiva: drafts/omitidas pueden tener issuedAt null.
        if (from != null) {
            jpql.append(" AND COALESCE(t.issuedAt, t.createdAt) >= :from");
            params.put("from", from);
        }
        if (to != null) {
            jpql.append(" AND COALESCE(t.issuedAt, t.createdAt) <= :to");
            params.put("to", to);
        }
    }
}
