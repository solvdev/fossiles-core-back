package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LfPackingBoxLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LfPackingBoxLineRepository extends JpaRepository<LfPackingBoxLineEntity, Long> {

    List<LfPackingBoxLineEntity> findByBoxIdIn(Collection<Long> boxIds);

    List<LfPackingBoxLineEntity> findByBoxId(Long boxId);

    Optional<LfPackingBoxLineEntity> findByBoxIdAndProductionOrderItemIdAndSizeKey(
            Long boxId, Long productionOrderItemId, String sizeKey);

    @Modifying
    @Query("DELETE FROM LfPackingBoxLineEntity l WHERE l.boxId = :boxId")
    void deleteByBoxId(@Param("boxId") Long boxId);

    /** Piezas en cajas abiertas o cerradas (todavía sin despachar) de una orden, por línea y talla. */
    @Query("""
            SELECT l.productionOrderItemId, l.sizeKey, COALESCE(SUM(l.quantity), 0)
            FROM LfPackingBoxLineEntity l, LfPackingBoxEntity b
            WHERE l.boxId = b.id
              AND b.productionOrderId = :orderId
              AND b.status IN ('OPEN', 'CLOSED')
            GROUP BY l.productionOrderItemId, l.sizeKey
            """)
    List<Object[]> sumPendingByOrder(@Param("orderId") Long orderId);

    @Query("""
            SELECT COALESCE(SUM(l.quantity), 0)
            FROM LfPackingBoxLineEntity l, LfPackingBoxEntity b
            WHERE l.boxId = b.id
              AND b.productionOrderId = :orderId
              AND b.status IN ('OPEN', 'CLOSED')
            """)
    long totalPendingPieces(@Param("orderId") Long orderId);
}
