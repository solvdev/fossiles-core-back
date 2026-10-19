package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.CaseFilter;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCaseEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Filtros combinables de la bandeja, el dashboard y la gestión de diferencias. */
public final class ReconciliationSpecs {

    private ReconciliationSpecs() {
    }

    /** @param caseIdsByReceipt casos cuyos fondos tienen la referencia buscada (la búsqueda rápida también mira referencias). */
    public static Specification<ReconCaseEntity> of(CaseFilter f, Collection<Long> caseIdsByReceipt) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f == null) return cb.conjunction();
            if (f.from() != null) p.add(cb.greaterThanOrEqualTo(root.get("saleDate"), f.from()));
            if (f.to() != null) p.add(cb.lessThanOrEqualTo(root.get("saleDate"), f.to()));
            if (!ReconText.blank(f.method())) p.add(cb.equal(root.get("paymentMethod"), f.method().trim()));
            if (!ReconText.blank(f.carrier())) p.add(cb.equal(root.get("carrier"), f.carrier().trim()));
            if (f.sellerId() != null) p.add(cb.equal(root.get("sellerId"), f.sellerId()));
            if (f.statuses() != null && !f.statuses().isEmpty()) p.add(root.get("status").in(f.statuses()));
            if (f.batchId() != null) p.add(cb.equal(root.get("batchId"), f.batchId()));
            if (f.withoutBatch()) p.add(cb.isNull(root.get("batchId")));
            if (f.problem() != null) p.add(cb.equal(root.get("problemType"), f.problem()));
            if (f.origin() != null) p.add(cb.equal(root.get("origin"), f.origin()));
            if (!ReconText.blank(f.q())) {
                String like = "%" + f.q().trim().toLowerCase(Locale.ROOT) + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("shipmentNumber")), like));
                any.add(cb.like(cb.lower(root.get("guideNumber")), like));
                any.add(cb.like(cb.lower(root.get("saleNumber")), like));
                if (caseIdsByReceipt != null && !caseIdsByReceipt.isEmpty()) any.add(root.get("id").in(caseIdsByReceipt));
                p.add(cb.or(any.toArray(new Predicate[0])));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }
}
