package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InternalShipmentRequestRepositoryImpl implements InternalShipmentRequestRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<InternalShipmentRequestEntity> findFiltered(String status, String requestType) {
        StringBuilder jpql = new StringBuilder(
                "SELECT DISTINCT r FROM InternalShipmentRequestEntity r LEFT JOIN FETCH r.lines WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (status != null) {
            jpql.append(" AND r.status = :status");
            params.put("status", status);
        }
        if (requestType != null) {
            jpql.append(" AND r.requestType = :requestType");
            params.put("requestType", requestType);
        }
        jpql.append(" ORDER BY r.requestedAt DESC, r.id DESC");
        TypedQuery<InternalShipmentRequestEntity> query =
                entityManager.createQuery(jpql.toString(), InternalShipmentRequestEntity.class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }
}
