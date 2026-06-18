package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderWarehouseUnitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductionOrderWarehouseUnitRepository extends JpaRepository<ProductionOrderWarehouseUnitEntity, Long> {
    List<ProductionOrderWarehouseUnitEntity> findByProductionOrderIdOrderByProductionOrderItemIdAscSizeKeyAscUnitSeqAsc(
            Long productionOrderId);

    long countByProductionOrderId(Long productionOrderId);

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
}
