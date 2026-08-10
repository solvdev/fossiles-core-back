package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderWarehouseUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderWarehouseUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerShipmentDispatchService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductionOrderWarehouseUnitRepository warehouseUnitRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final OnlineSaleShipmentNumberService onlineSaleShipmentNumberService;
    private final ProductionOrderWarehouseUnitService productionOrderWarehouseUnitService;
    private final ProductInventoryService productInventoryService;
    private final SecurityUtil securityUtil;

    @Transactional
    public Map<String, Object> dispatchDirectOnlineSale(long onlineSaleId, Map<String, String> body)
            throws ResourceNotFoundException, BusinessException {
        OnlineSaleEntity sale = onlineSaleRepository.findById(onlineSaleId)
                .orElseThrow(() -> new ResourceNotFoundException("Online Sale", onlineSaleId));

        if (Boolean.TRUE.equals(sale.getInProductionOrder())) {
            throw new BusinessException("Esta venta está vinculada a una orden de producción. Despáchala desde su OP.");
        }
        if ("ENVIADO".equals(sale.getStatus()) || "ENTREGADO".equals(sale.getStatus())) {
            throw new BusinessException("Esta venta ya fue despachada. Estado actual: " + sale.getStatus());
        }
        if (!"PRODUCIDO".equals(sale.getStatus())) {
            throw new BusinessException("Solo se pueden despachar ventas directas en estado PRODUCIDO. Estado actual: " + sale.getStatus());
        }

        // Inventario ya salió en prepare (ONLINE_SALE_PREPARE); aquí solo cambia estado.
        onlineSaleShipmentNumberService.assignIfMissing(sale);
        String shipmentNumber = sale.getShipmentNumber();
        sale.setStatus("ENVIADO");
        if (body != null && body.get("guideNumber") != null) {
            sale.setGuideNumber(body.get("guideNumber"));
        }
        if (body != null && body.get("shippingCarrier") != null) {
            sale.setShippingCarrier(body.get("shippingCarrier"));
        }
        onlineSaleRepository.save(sale);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Envío " + shipmentNumber + " despachado para " + sale.getCustomerName());
        result.put("shipmentNumber", shipmentNumber);
        result.put("saleStatus", sale.getStatus());
        result.put("allDispatched", true);
        return result;
    }

    @Transactional
    public Map<String, Object> dispatchCustomerShipment(
            long productionOrderId,
            long onlineSaleId,
            Map<String, String> body)
            throws ResourceNotFoundException, BusinessException {

        ProductionOrderEntity po = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));

        if (!"VENTA_EN_LINEA".equals(po.getOrderType())) {
            throw new BusinessException("Solo se pueden despachar envíos a cliente en órdenes de tipo VENTA_EN_LINEA");
        }

        List<Long> linkedSaleIds = productionOrderItemRepository
                .findDistinctOnlineSaleIdsByProductionOrderId(productionOrderId);
        if (!linkedSaleIds.contains(onlineSaleId)) {
            throw new BusinessException("La venta no pertenece a esta orden de producción");
        }

        OnlineSaleEntity sale = onlineSaleRepository.findById(onlineSaleId)
                .orElseThrow(() -> new ResourceNotFoundException("Online Sale", onlineSaleId));

        if ("ENVIADO".equals(sale.getStatus()) || "ENTREGADO".equals(sale.getStatus())) {
            throw new BusinessException("Esta venta ya fue despachada. Estado actual: " + sale.getStatus());
        }

        // Sin validar recepción PT completa: el despacho operativo no debe bloquearse por piezas pendientes.
        onlineSaleShipmentNumberService.assignIfMissing(sale);
        String shipmentNumber = sale.getShipmentNumber();

        deductInventoryForReceivedUnits(productionOrderId, sale);

        sale.setStatus("ENVIADO");
        if (body != null && body.get("guideNumber") != null) {
            sale.setGuideNumber(body.get("guideNumber"));
        }
        if (body != null && body.get("shippingCarrier") != null) {
            sale.setShippingCarrier(body.get("shippingCarrier"));
        }
        onlineSaleRepository.save(sale);

        productionOrderWarehouseUnitService.markUnitsShippedForOnlineSale(
                productionOrderId,
                onlineSaleId,
                securityUtil.getCurrentUserId());

        List<Long> saleIds = productionOrderItemRepository.findDistinctOnlineSaleIdsByProductionOrderId(productionOrderId);
        boolean allDispatched = saleIds.stream().allMatch(sId -> {
            OnlineSaleEntity s = onlineSaleRepository.findById(sId).orElse(null);
            return s != null && ("ENVIADO".equals(s.getStatus()) || "ENTREGADO".equals(s.getStatus()));
        });

        if (allDispatched && !"COMPLETED".equals(po.getStatus())) {
            po.setStatus("COMPLETED");
            productionOrderRepository.save(po);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Envío " + shipmentNumber + " despachado para " + sale.getCustomerName());
        result.put("shipmentNumber", shipmentNumber);
        result.put("saleStatus", sale.getStatus());
        result.put("allDispatched", allDispatched);
        return result;
    }

    /**
     * Baja PT/Devoluciones por cada pieza RECEIVED aún no despachada (idempotente por unit id).
     * Si la venta ya salió en prepare ({@code ONLINE_SALE_PREPARE}), no vuelve a descontar.
     */
    private void deductInventoryForReceivedUnits(long productionOrderId, OnlineSaleEntity sale)
            throws BusinessException {
        if (productInventoryService.hasNetOutboundForReference(
                ProductInventoryService.REF_ONLINE_SALE_PREPARE, sale.getId())) {
            return;
        }

        List<ProductionOrderItemEntity> items = productionOrderItemRepository
                .findByProductionOrderId(productionOrderId).stream()
                .filter(i -> Objects.equals(i.getOnlineSaleId(), sale.getId()))
                .collect(Collectors.toList());
        if (items.isEmpty()) {
            return;
        }

        Map<Long, ProductionOrderItemEntity> itemsById = items.stream()
                .collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i, (a, b) -> a));
        List<Long> itemIds = items.stream().map(ProductionOrderItemEntity::getId).collect(Collectors.toList());
        List<ProductionOrderWarehouseUnitEntity> units = warehouseUnitRepository
                .findByProductionOrderIdAndProductionOrderItemIdIn(productionOrderId, itemIds);

        String referenceNumber = sale.getShipmentNumber() != null && !sale.getShipmentNumber().isBlank()
                ? sale.getShipmentNumber()
                : sale.getSaleNumber();
        String description = "Despacho venta online a cliente #"
                + (referenceNumber != null ? referenceNumber : sale.getId());

        for (ProductionOrderWarehouseUnitEntity unit : units) {
            if (!ProductionOrderWarehouseUnitService.STATUS_RECEIVED.equals(
                    normalizeReceiptStatus(unit.getReceiptStatus()))) {
                continue;
            }
            if (unit.getShippedAt() != null) {
                continue;
            }
            ProductionOrderItemEntity item = itemsById.get(unit.getProductionOrderItemId());
            if (item == null || item.getProductId() == null) {
                continue;
            }
            String sizeLabel = unit.getSizeKey() == null || unit.getSizeKey().isBlank()
                    ? null
                    : unit.getSizeKey();
            Long colorId = item.getColorId() != null ? item.getColorId() : unit.getColorId();

            productInventoryService.decrementFromDispatchWarehouses(
                    item.getProductId(),
                    colorId,
                    sizeLabel,
                    BigDecimal.ONE,
                    ProductInventoryService.MOVEMENT_ONLINE_SALE_DISPATCH,
                    sale.getId(),
                    referenceNumber,
                    description,
                    ProductInventoryService.MOVEMENT_ONLINE_SALE_DISPATCH,
                    unit.getId());
        }
    }

    private static String normalizeReceiptStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }
}
