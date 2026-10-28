package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderWarehouseUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderWarehouseUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerShipmentDispatchService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductionOrderWarehouseUnitRepository warehouseUnitRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final OnlineSaleItemRepository onlineSaleItemRepository;
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

        // Inventario ya salió en prepare (ONLINE_SALE_PREPARE); aquí solo cambia estado. Si un «Anular envío»
        // regresó esa salida a Devoluciones, la pieza vuelve a salir de ahí.
        redeductPrepareReversedToReturns(sale);
        onlineSaleShipmentNumberService.assignIfMissing(sale);
        String shipmentNumber = sale.getShipmentNumber();
        String guideWarning = markDispatched(sale, body);
        onlineSaleRepository.save(sale);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Envío " + shipmentNumber + " despachado para " + sale.getCustomerName());
        result.put("shipmentNumber", shipmentNumber);
        result.put("saleStatus", sale.getStatus());
        result.put("allDispatched", true);
        putGuideWarning(result, guideWarning);
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

        String guideWarning = markDispatched(sale, body);
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
        putGuideWarning(result, guideWarning);
        return result;
    }

    /** Transportistas que generan guía; sin ella la conciliación no puede ligar lo que liquidan con la venta. */
    static final Set<String> CARRIERS_WITH_GUIDE = Set.of("FORZA_DELIVERY", "GUATEX");

    /**
     * Pasa la venta a ENVIADO, guarda guía y transportista si vienen y registra el momento del despacho.
     * La guía es solo un aviso: el despacho físico nunca se bloquea por ella.
     *
     * @return mensaje de aviso si falta la guía de un transportista que la genera; null si no hay nada que avisar
     */
    String markDispatched(OnlineSaleEntity sale, Map<String, String> body) {
        sale.setStatus("ENVIADO");
        sale.setDispatchedAt(GuatemalaDateTime.now());
        if (body != null && body.get("guideNumber") != null) {
            sale.setGuideNumber(body.get("guideNumber"));
        }
        if (body != null && body.get("shippingCarrier") != null) {
            sale.setShippingCarrier(body.get("shippingCarrier"));
        }
        boolean needsGuide = sale.getShippingCarrier() != null
                && CARRIERS_WITH_GUIDE.contains(sale.getShippingCarrier().trim().toUpperCase());
        boolean hasGuide = sale.getGuideNumber() != null && !sale.getGuideNumber().isBlank();
        if (needsGuide && !hasGuide) {
            return "El envío salió sin número de guía. Regístrela en Ventas Online: sin guía no se puede conciliar el cobro con "
                    + sale.getShippingCarrier().trim() + ".";
        }
        return null;
    }

    private static void putGuideWarning(Map<String, Object> result, String guideWarning) {
        if (guideWarning != null) {
            result.put("warning", guideWarning);
        }
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
        // La pieza se produjo para esta venta y entró a Bodega PT: sale de PT. Si antes se anuló su envío, la
        // pieza quedó en Devoluciones y sale de ahí.
        ProductInventoryService.DispatchSource source = productInventoryService.hasAnyMovementForReference(
                ProductInventoryService.REF_ONLINE_SALE_WH_RETURN, sale.getId())
                ? ProductInventoryService.DispatchSource.RETURNS_FIRST
                : ProductInventoryService.DispatchSource.PT_FIRST;

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
                    unit.getId(),
                    source);
        }
    }

    /**
     * Venta directa preparada desde inventario cuyo envío se anuló: la anulación devolvió la salida
     * {@code ONLINE_SALE_PREPARE} a Devoluciones, así que al despacharla de nuevo hay que descontarla otra
     * vez (Devoluciones primero). Sin esto la pieza salía físicamente y seguía en Devoluciones.
     */
    private void redeductPrepareReversedToReturns(OnlineSaleEntity sale) throws BusinessException {
        if (!productInventoryService.hasAnyMovementForReference(ProductInventoryService.REF_ONLINE_SALE_PREPARE, sale.getId())
                || productInventoryService.hasNetOutboundForReference(ProductInventoryService.REF_ONLINE_SALE_PREPARE, sale.getId())) {
            return;
        }
        String description = "Despacho venta online #" + (sale.getSaleNumber() != null ? sale.getSaleNumber() : sale.getId())
                + " tras anular envío";
        for (OnlineSaleItemEntity item : onlineSaleItemRepository.findByOnlineSaleIdOrderByIdAsc(sale.getId())) {
            if (item.getProductId() == null) {
                continue;
            }
            int qty = item.getQuantity() != null ? item.getQuantity() : 1;
            if (qty <= 0) {
                continue;
            }
            productInventoryService.decrementFromDispatchWarehouses(
                    item.getProductId(),
                    item.getColorId(),
                    item.getSize(),
                    BigDecimal.valueOf(qty),
                    ProductInventoryService.REF_ONLINE_SALE_PREPARE,
                    sale.getId(),
                    sale.getSaleNumber(),
                    description,
                    ProductInventoryService.REF_ONLINE_SALE_PREPARE,
                    item.getId(),
                    false,
                    ProductInventoryService.DispatchSource.RETURNS_FIRST);
        }
    }

    private static String normalizeReceiptStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }
}
