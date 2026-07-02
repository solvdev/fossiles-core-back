package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderPartialReleaseRepository extends JpaRepository<ProductionOrderPartialReleaseEntity, Long> {

    List<ProductionOrderPartialReleaseEntity> findByProductionOrderIdOrderBySequenceNumAsc(Long productionOrderId);

    Optional<ProductionOrderPartialReleaseEntity> findByIdAndProductionOrderId(Long id, Long productionOrderId);

    Optional<ProductionOrderPartialReleaseEntity> findTopByProductionOrderIdOrderBySequenceNumDesc(Long productionOrderId);

    @Query(value = """
            SELECT r.*
            FROM production_order_partial_release r
            INNER JOIN production_order o ON o.id = r.production_order_id
            LEFT JOIN product_shipment ps ON ps.partial_release_id = r.id
                AND UPPER(COALESCE(ps.status, '')) <> 'CANCELLED'
            WHERE UPPER(COALESCE(r.status, '')) <> 'DRAFT'
              AND (
                :q = ''
                OR LOWER(COALESCE(r.label, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(o.code, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(o.customer_name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(ps.shipment_number, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(r.notes, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR CAST(r.sequence_num AS TEXT) LIKE CONCAT('%', :q, '%')
              )
            ORDER BY r.updated_at DESC NULLS LAST, r.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ProductionOrderPartialReleaseEntity> searchForPrepare(
            @Param("q") String q,
            @Param("limit") int limit
    );
}
