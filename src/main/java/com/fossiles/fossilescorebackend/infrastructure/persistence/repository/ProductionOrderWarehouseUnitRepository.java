package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderWarehouseUnitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ProductionOrderWarehouseUnitRepository extends JpaRepository<ProductionOrderWarehouseUnitEntity, Long> {
    boolean existsByProductionOrderItemId(Long productionOrderItemId);
    List<ProductionOrderWarehouseUnitEntity> findByProductionOrderIdOrderByProductionOrderItemIdAscSizeKeyAscUnitSeqAsc(
            Long productionOrderId);

    long countByProductionOrderId(Long productionOrderId);

    /** Piezas recibidas en reserva por orden (para la lista de Bodega PT). */
    @Query("""
            SELECT u.productionOrderId, COUNT(u)
            FROM ProductionOrderWarehouseUnitEntity u
            WHERE u.productionOrderId IN :orderIds AND u.receiptStatus = 'RECEIVED'
            GROUP BY u.productionOrderId
            """)
    List<Object[]> countReceivedByOrderIds(@Param("orderIds") Collection<Long> orderIds);

    List<ProductionOrderWarehouseUnitEntity> findByProductionOrderItemIdOrderBySizeKeyAscUnitSeqAsc(
            Long productionOrderItemId);

    long countByProductionOrderIdAndReceiptStatus(Long productionOrderId, String receiptStatus);

    List<ProductionOrderWarehouseUnitEntity> findByProductionOrderIdAndProductionOrderItemIdIn(
            Long productionOrderId,
            List<Long> productionOrderItemIds);

    List<ProductionOrderWarehouseUnitEntity> findByProductionOrderIdAndShipmentRefTypeAndShipmentRefId(
            Long productionOrderId,
            String shipmentRefType,
            Long shipmentRefId);

    /** Líneas de OP que ya tienen al menos una unidad de bodega. */
    @Query("""
            SELECT DISTINCT u.productionOrderItemId
            FROM ProductionOrderWarehouseUnitEntity u
            WHERE u.productionOrderItemId IN :itemIds
            """)
    List<Long> findReferencedItemIds(@Param("itemIds") Collection<Long> itemIds);
}
