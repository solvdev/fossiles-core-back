package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TaxInvoiceRepositoryImpl implements TaxInvoiceRepositoryCustom {

    /** Pendientes de firma (sin error FEL). FAILED va en filtro ERROR. */
    private static final List<String> UNSIGNED_STATUSES = List.of("SKIPPED", "DRAFT");

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<TaxInvoiceEntity> search(TaxInvoiceSearchFilter filter) {
        StringBuilder jpql = new StringBuilder("SELECT t FROM TaxInvoiceEntity t WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        appendCommonFilters(jpql, params, filter);

        if (filter.status() != null) {
            jpql.append(" AND UPPER(TRIM(t.status)) = :status");
            params.put("status", filter.status().trim().toUpperCase(Locale.ROOT));
        }
        if (filter.certificationFilter() != null) {
            String certification = filter.certificationFilter().trim().toUpperCase(Locale.ROOT);
            switch (certification) {
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
    public List<Object[]> countGroupByStatus(TaxInvoiceSearchFilter filter) {
        StringBuilder jpql = new StringBuilder(
                "SELECT UPPER(TRIM(t.status)), COUNT(t) FROM TaxInvoiceEntity t WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        appendCommonFilters(jpql, params, filter);
        jpql.append(" GROUP BY UPPER(TRIM(t.status))");

        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }

    private static void appendCommonFilters(
            StringBuilder jpql,
            Map<String, Object> params,
            TaxInvoiceSearchFilter filter
    ) {
        if (filter.sourceType() != null) {
            jpql.append(" AND t.sourceType = :sourceType");
            params.put("sourceType", filter.sourceType());
        }
        if (filter.documentType() != null) {
            jpql.append(" AND UPPER(t.documentType) = :documentType");
            params.put("documentType", filter.documentType().trim().toUpperCase(Locale.ROOT));
        }
        if (filter.customerTaxIdPattern() != null) {
            jpql.append(" AND UPPER(t.customerTaxId) LIKE :customerTaxIdPattern");
            params.put("customerTaxIdPattern", filter.customerTaxIdPattern());
        }
        if (filter.internalNumberPattern() != null) {
            jpql.append(" AND UPPER(t.internalNumber) LIKE :internalNumberPattern");
            params.put("internalNumberPattern", filter.internalNumberPattern());
        }
        if (filter.textPattern() != null) {
            jpql.append(" AND (UPPER(t.internalNumber) LIKE :text"
                    + " OR UPPER(t.customerTaxId) LIKE :text"
                    + " OR UPPER(t.customerName) LIKE :text"
                    + " OR UPPER(t.felSerie) LIKE :text"
                    + " OR UPPER(t.felNumero) LIKE :text"
                    + " OR UPPER(t.felUuid) LIKE :text)");
            params.put("text", filter.textPattern());
        }
        if (!filter.includeTests()) {
            jpql.append(" AND (t.felSerie IS NULL OR t.felSerie <> :sandboxSerie)")
                    .append(" AND NOT EXISTS (SELECT k.id FROM KioskSaleEntity k")
                    .append(" WHERE t.sourceType = 'KIOSK_SALE' AND k.id = t.sourceId AND k.testSale = true)")
                    .append(" AND NOT EXISTS (SELECT o.id FROM TaxInvoiceEntity o")
                    .append(" WHERE o.id = t.originInvoiceId AND o.felSerie = :sandboxSerie)");
            params.put("sandboxSerie", TaxInvoiceSearchFilter.SANDBOX_SERIE);
        }
        // Usar fecha efectiva: drafts/omitidas pueden tener issuedAt null.
        if (filter.from() != null) {
            jpql.append(" AND COALESCE(t.issuedAt, t.createdAt) >= :from");
            params.put("from", filter.from());
        }
        if (filter.to() != null) {
            jpql.append(" AND COALESCE(t.issuedAt, t.createdAt) <= :to");
            params.put("to", filter.to());
        }
    }
}
