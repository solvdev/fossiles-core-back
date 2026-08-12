package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.WarehouseUnitReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.response.WarehouseUnitResponse;
import com.fossiles.fossilescorebackend.application.dto.response.WarehouseWorkspaceResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductionOrderWarehouseUnitService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String REF_ONLINE_SALE = "ONLINE_SALE";
    public static final String REF_PRODUCT_SHIPMENT = "PRODUCT_SHIPMENT";

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductionOrderWarehouseUnitRepository unitRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final ProductInventoryService productInventoryService;
    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;
    private final LocationRepository locationRepository;
    private final WarehouseOrderViewAssembler warehouseOrderViewAssembler;
    private final SecurityUtil securityUtil;
    private final ProductionTaskLifecycleService productionTaskLifecycleService;
    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final DocumentSeriesRepository documentSeriesRepository;
    private final ProductShipmentDetailRepository shipmentDetailRepository;
    private final OnlineSaleItemRepository onlineSaleItemRepository;
    private final OnlineSaleRepository onlineSaleRepository;

    @Transactional
    public WarehouseWorkspaceResponse getWorkspace(Long productionOrderId) throws ResourceNotFoundException {
        ProductionOrderEntity po = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));
        ensureUnitsForOrder(po);
        List<ProductionOrderWarehouseUnitEntity> units = unitRepository
                .findByProductionOrderIdOrderByProductionOrderItemIdAscSizeKeyAscUnitSeqAsc(productionOrderId);
        Map<Long, ProductionOrderItemEntity> itemsById = productionOrderItemRepository.findByProductionOrderId(productionOrderId)
                .stream()
                .collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i, (a, b) -> a));
        List<WarehouseUnitResponse> unitResponses = units.stream()
                .map(u -> toUnitResponse(u, itemsById.get(u.getProductionOrderItemId())))
                .collect(Collectors.toList());
        return WarehouseWorkspaceResponse.builder()
                .order(warehouseOrderViewAssembler.toWarehouseView(po))
                .units(unitResponses)
                .summary(buildSummary(po, units))
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateUnitsReceipt(Long productionOrderId, WarehouseUnitReceiptRequest request)
            throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity po = productionOrderRepository.findByIdForUpdate(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));
        if (po.getWarehouseReceiptClosedAt() != null) {
            throw new BusinessException("La recepción en bodega de esta orden ya fue cerrada.");
        }
        if (request == null || request.getUnits() == null || request.getUnits().isEmpty()) {
            throw new BusinessException("Debe indicar al menos una pieza para actualizar.");
        }

        ensureUnitsForOrder(po);
        LocationEntity bodega = getFinishedGoodsLocation();
        Long userId = securityUtil.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();

        Map<Long, ProductionOrderItemEntity> itemsById = productionOrderItemRepository.findByProductionOrderId(productionOrderId)
                .stream()
                .collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i, (a, b) -> a));

        int receivedCount = 0;
        int rejectedCount = 0;

        for (WarehouseUnitReceiptRequest.UnitUpdate update : request.getUnits()) {
            if (update == null || update.getUnitId() == null) {
                continue;
            }
            ProductionOrderWarehouseUnitEntity unit = unitRepository.findById(update.getUnitId())
                    .orElseThrow(() -> new BusinessException("Pieza no encontrada: " + update.getUnitId()));
            if (!Objects.equals(unit.getProductionOrderId(), productionOrderId)) {
                throw new BusinessException("La pieza no pertenece a esta orden de producción.");
            }
            if (unit.getShippedAt() != null) {
                throw new BusinessException("No se puede modificar la pieza " + unit.getUnitLabel() + " porque ya fue enviada.");
            }

            String target = normalizeReceiptStatus(update.getReceiptStatus());
            String previous = normalizeReceiptStatus(unit.getReceiptStatus());

            if (target.equals(previous)) {
                continue;
            }

            ProductionOrderItemEntity item = itemsById.get(unit.getProductionOrderItemId());
            if (item == null) {
                throw new BusinessException("Ítem de orden no encontrado para la pieza.");
            }
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;

            if (STATUS_RECEIVED.equals(target)) {
                if (!STATUS_PENDING.equals(previous)) {
                    throw new BusinessException("Solo se puede recibir una pieza en estado pendiente.");
                }
                receiveSingleUnit(po, item, product, unit, bodega, userId, now);
                receivedCount++;
            } else if (STATUS_REJECTED.equals(target)) {
                if (!STATUS_PENDING.equals(previous)) {
                    throw new BusinessException("Solo se puede rechazar una pieza en estado pendiente.");
                }
                String reason = trimToNull(update.getRejectionReason());
                if (reason == null) {
                    throw new BusinessException("Indique motivo de rechazo para " + unit.getUnitLabel());
                }
                unit.setReceiptStatus(STATUS_REJECTED);
                unit.setRejectionReason(reason);
                unit.setReceivedAt(null);
                unit.setReceivedBy(null);
                unitRepository.save(unit);
                createReprocessTask(po, item, product, 1, reason);
                rejectedCount++;
            } else if (STATUS_PENDING.equals(target)) {
                throw new BusinessException("No se puede revertir una pieza a pendiente desde esta pantalla.");
            }

            syncItemReceivedQty(item);
            productionOrderItemRepository.save(item);
            if (STATUS_RECEIVED.equals(target)) {
                productionTaskLifecycleService.tryCompleteTasksAfterWarehouseReceipt(
                        productionOrderId, item.getId(), now);
            }
        }

        if (receivedCount == 0 && rejectedCount == 0) {
            throw new BusinessException("No hubo cambios en las piezas indicadas.");
        }
        if (rejectedCount > 0 && !"COMPLETED".equals(po.getStatus())) {
            po.setStatus("IN_PROGRESS");
            productionOrderRepository.save(po);
        }

        List<ProductionOrderWarehouseUnitEntity> allUnits = unitRepository
                .findByProductionOrderIdOrderByProductionOrderItemIdAscSizeKeyAscUnitSeqAsc(productionOrderId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Piezas actualizadas en bodega PT.");
        result.put("receivedCount", receivedCount);
        result.put("rejectedCount", rejectedCount);
        result.put("summary", buildSummary(po, allUnits));
        return result;
    }

    /**
     * Recepción en Bodega PT de todas las piezas pendientes vinculadas a una venta online (OPL).
     * Idempotente: si ya están recibidas, no falla y reporta conteos.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> receivePendingUnitsForOnlineSale(Long onlineSaleId)
            throws ResourceNotFoundException, BusinessException {
        if (onlineSaleId == null) {
            throw new BusinessException("Debes indicar la venta online.");
        }
        List<ProductionOrderItemEntity> saleItems = productionOrderItemRepository.findByOnlineSaleId(onlineSaleId);
        if (saleItems == null || saleItems.isEmpty()) {
            throw new BusinessException(
                    "Esta venta no tiene piezas de orden de producción para recepción en bodega.");
        }
        Long productionOrderId = saleItems.stream()
                .map(ProductionOrderItemEntity::getProductionOrderId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new BusinessException("No se encontró la orden de producción de esta venta."));

        ProductionOrderEntity po = productionOrderRepository.findByIdForUpdate(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));
        if (po.getWarehouseReceiptClosedAt() != null) {
            throw new BusinessException("La recepción en bodega de esta orden ya fue cerrada.");
        }

        ensureUnitsForOrder(po);
        LocationEntity bodega = getFinishedGoodsLocation();
        Long userId = securityUtil.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();

        Set<Long> saleItemIds = saleItems.stream()
                .map(ProductionOrderItemEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, ProductionOrderItemEntity> itemsById = saleItems.stream()
                .collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i, (a, b) -> a, LinkedHashMap::new));

        List<ProductionOrderWarehouseUnitEntity> units = unitRepository
                .findByProductionOrderIdAndProductionOrderItemIdIn(productionOrderId, new ArrayList<>(saleItemIds));

        int receivedNow = 0;
        int alreadyReceived = 0;
        int rejected = 0;
        int shipped = 0;
        int pendingBefore = 0;

        for (ProductionOrderWarehouseUnitEntity unit : units) {
            String status = normalizeReceiptStatus(unit.getReceiptStatus());
            if (unit.getShippedAt() != null) {
                shipped++;
                continue;
            }
            if (STATUS_REJECTED.equals(status)) {
                rejected++;
                continue;
            }
            if (STATUS_RECEIVED.equals(status)) {
                alreadyReceived++;
                continue;
            }
            pendingBefore++;
            ProductionOrderItemEntity item = itemsById.get(unit.getProductionOrderItemId());
            if (item == null) {
                throw new BusinessException("Ítem de orden no encontrado para la pieza.");
            }
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            receiveSingleUnit(po, item, product, unit, bodega, userId, now);
            syncItemReceivedQty(item);
            productionOrderItemRepository.save(item);
            productionTaskLifecycleService.tryCompleteTasksAfterWarehouseReceipt(
                    productionOrderId, item.getId(), now);
            receivedNow++;
        }

        List<ProductionOrderWarehouseUnitEntity> allSaleUnits = unitRepository
                .findByProductionOrderIdAndProductionOrderItemIdIn(productionOrderId, new ArrayList<>(saleItemIds));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", receivedNow > 0
                ? ("Recepción en bodega: " + receivedNow + " pieza(s) de la venta OPL.")
                : (alreadyReceived > 0
                        ? "Las piezas de esta venta ya estaban recibidas en bodega."
                        : "No hay piezas pendientes de recepción para esta venta."));
        result.put("onlineSaleId", onlineSaleId);
        result.put("productionOrderId", productionOrderId);
        result.put("receivedCount", receivedNow);
        result.put("alreadyReceivedCount", alreadyReceived);
        result.put("rejectedCount", rejected);
        result.put("shippedCount", shipped);
        result.put("pendingBefore", pendingBefore);
        result.put("summary", buildSummary(po, allSaleUnits));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> closeWarehouseReceipt(Long productionOrderId) throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity po = productionOrderRepository.findByIdForUpdate(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));
        ensureUnitsForOrder(po);
        long pending = unitRepository.countByProductionOrderIdAndReceiptStatus(productionOrderId, STATUS_PENDING);
        if (pending > 0) {
            throw new BusinessException("Aún hay " + pending + " pieza(s) pendientes de recepción. No se puede cerrar la orden en bodega.");
        }
        po.setWarehouseReceiptClosedAt(LocalDateTime.now());
        productionOrderRepository.save(po);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Recepción en bodega cerrada para la orden " + po.getCode());
        result.put("warehouseReceiptClosedAt", po.getWarehouseReceiptClosedAt());
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public int markUnitsShippedForOnlineSale(Long productionOrderId, Long onlineSaleId, Long userId) {
        List<Long> itemIds = productionOrderItemRepository.findByProductionOrderId(productionOrderId).stream()
                .filter(i -> Objects.equals(i.getOnlineSaleId(), onlineSaleId))
                .map(ProductionOrderItemEntity::getId)
                .collect(Collectors.toList());
        if (itemIds.isEmpty()) {
            return 0;
        }
        List<ProductionOrderWarehouseUnitEntity> units = unitRepository.findByProductionOrderIdAndProductionOrderItemIdIn(
                productionOrderId, itemIds);
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (ProductionOrderWarehouseUnitEntity unit : units) {
            if (STATUS_RECEIVED.equals(normalizeReceiptStatus(unit.getReceiptStatus())) && unit.getShippedAt() == null) {
                unit.setShipmentRefType(REF_ONLINE_SALE);
                unit.setShipmentRefId(onlineSaleId);
                unit.setShippedAt(now);
                unit.setShippedBy(userId);
                unitRepository.save(unit);
                count++;
            }
        }
        return count;
    }

    @Transactional(rollbackFor = Exception.class)
    public void validateUnitsReadyForProductShipment(Long productionOrderId, Long shipmentId)
            throws BusinessException, ResourceNotFoundException {
        ProductionOrderEntity po = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));
        ensureUnitsForOrder(po);
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipmentId);
        if (details.isEmpty()) {
            throw new BusinessException("El envío no tiene productos.");
        }
        Map<Long, ProductionOrderItemEntity> itemsById = productionOrderItemRepository.findByProductionOrderId(productionOrderId)
                .stream()
                .collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i, (a, b) -> a));
        for (ProductShipmentDetailEntity detail : details) {
            String sizeKey = normalizeUnitSizeKey(detail.getSizeLabel());
            int needed = detail.getQuantity() != null ? detail.getQuantity().intValue() : 0;
            if (needed <= 0) {
                continue;
            }
            List<ProductionOrderItemEntity> matchingItems = itemsById.values().stream()
                    .filter(i -> Objects.equals(i.getProductId(), detail.getProductId()))
                    .filter(i -> Objects.equals(i.getColorId(), detail.getColorId()))
                    .collect(Collectors.toList());
            int available = countAvailableForShipmentLine(matchingItems, sizeKey);
            if (available < needed) {
                throw new BusinessException(buildInsufficientPtUnitsForShipmentMessage(
                        detail.getProductId(), sizeKey, available, needed, matchingItems));
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public int markUnitsShippedForProductShipment(Long productionOrderId, Long shipmentId, Long userId) {
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipmentId);
        Map<Long, ProductionOrderItemEntity> itemsById = productionOrderItemRepository.findByProductionOrderId(productionOrderId)
                .stream()
                .collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i, (a, b) -> a));
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (ProductShipmentDetailEntity detail : details) {
            List<ProductionOrderItemEntity> matchingItems = itemsById.values().stream()
                    .filter(i -> Objects.equals(i.getProductId(), detail.getProductId()))
                    .filter(i -> Objects.equals(i.getColorId(), detail.getColorId()))
                    .collect(Collectors.toList());
            if (matchingItems.isEmpty()) {
                continue;
            }
            String sizeKey = normalizeUnitSizeKey(detail.getSizeLabel());
            int needed = detail.getQuantity() != null ? detail.getQuantity().intValue() : 0;
            for (ProductionOrderItemEntity item : matchingItems) {
                List<ProductionOrderWarehouseUnitEntity> candidates = unitRepository
                        .findByProductionOrderItemIdOrderBySizeKeyAscUnitSeqAsc(item.getId())
                        .stream()
                        .filter(u -> STATUS_RECEIVED.equals(normalizeReceiptStatus(u.getReceiptStatus())))
                        .filter(u -> u.getShippedAt() == null)
                        .filter(u -> unitMatchesShipmentSize(item, u, sizeKey))
                        .collect(Collectors.toList());
                for (ProductionOrderWarehouseUnitEntity unit : candidates) {
                    if (needed <= 0) {
                        break;
                    }
                    unit.setShipmentRefType(REF_PRODUCT_SHIPMENT);
                    unit.setShipmentRefId(shipmentId);
                    unit.setShippedAt(now);
                    unit.setShippedBy(userId);
                    unitRepository.save(unit);
                    count++;
                    needed--;
                }
            }
        }
        return count;
    }

    /**
     * Revierte marcas de envío en piezas PT al anular un envío confirmado (antes de SENT).
     */
    @Transactional(rollbackFor = Exception.class)
    public int clearUnitsShippedForProductShipment(Long productionOrderId, Long shipmentId) {
        if (productionOrderId == null || shipmentId == null) {
            return 0;
        }
        List<ProductionOrderWarehouseUnitEntity> units = unitRepository
                .findByProductionOrderIdAndShipmentRefTypeAndShipmentRefId(
                        productionOrderId, REF_PRODUCT_SHIPMENT, shipmentId);
        int count = 0;
        for (ProductionOrderWarehouseUnitEntity unit : units) {
            unit.setShipmentRefType(null);
            unit.setShipmentRefId(null);
            unit.setShippedAt(null);
            unit.setShippedBy(null);
            unitRepository.save(unit);
            count++;
        }
        return count;
    }

    private void receiveSingleUnit(
            ProductionOrderEntity po,
            ProductionOrderItemEntity item,
            ProductEntity product,
            ProductionOrderWarehouseUnitEntity unit,
            LocationEntity bodega,
            Long userId,
            LocalDateTime now
    ) throws BusinessException, ResourceNotFoundException {
        if (item.getProductId() == null) {
            throw new BusinessException("La pieza no tiene producto válido para inventario.");
        }
        String sizeKey = resolveReceiptSizeKey(item, unit, product);
        if (CinchoProductUtils.isFossCinchoProduct(product) && (sizeKey == null || sizeKey.isBlank())) {
            throw new BusinessException(
                    "La pieza " + unit.getUnitLabel()
                            + " es cincho FOSS sin talla. Indique la talla en la venta/OP antes de recibir en bodega.");
        }
        // Persistir talla resuelta para no volver a fallar en siguientes movimientos.
        if ((unit.getSizeKey() == null || unit.getSizeKey().isBlank()) && sizeKey != null && !sizeKey.isBlank()) {
            unit.setSizeKey(sizeKey);
        }

        String receiptRefNumber = po.getCode() + "-WH-UNIT-" + unit.getId();
        boolean alreadyRecorded = productInventoryService.hasProductKardexMovement(
                "PRODUCTION_ORDER_WH_UNIT",
                unit.getId(),
                "PRODUCTION_ENTRY",
                item.getProductId(),
                bodega.getId(),
                item.getColorId(),
                receiptRefNumber);

        if (!alreadyRecorded) {
            BigDecimal before = productInventoryService
                    .getInventoryByProductAndLocationAndColor(item.getProductId(), bodega.getId(), item.getColorId())
                    .getQuantity();
            productInventoryService.incrementInventory(
                    item.getProductId(),
                    bodega.getId(),
                    item.getColorId(),
                    BigDecimal.ONE,
                    null,
                    "PRODUCTION_ORDER_WH_UNIT",
                    unit.getId(),
                    receiptRefNumber,
                    "Ingreso pieza bodega PT",
                    sizeKey != null && !sizeKey.isBlank() ? sizeKey : null
            );
            BigDecimal after = productInventoryService
                    .getInventoryByProductAndLocationAndColor(item.getProductId(), bodega.getId(), item.getColorId())
                    .getQuantity();
            productInventoryService.recordMovement(
                    item.getProductId(),
                    bodega.getId(),
                    item.getColorId(),
                    "PRODUCTION_ENTRY",
                    BigDecimal.ONE,
                    before,
                    after,
                    null,
                    "PRODUCTION_ORDER_WH_UNIT",
                    unit.getId(),
                    receiptRefNumber,
                    "Recepción pieza " + unit.getUnitLabel()
            );
        }
        unit.setReceiptStatus(STATUS_RECEIVED);
        unit.setRejectionReason(null);
        unit.setReceivedAt(now);
        unit.setReceivedBy(userId);
        unitRepository.save(unit);
    }

    /**
     * Talla efectiva para ingreso a inventario: pieza → talla única del ítem OP → talla de la línea de venta online.
     */
    private String resolveReceiptSizeKey(
            ProductionOrderItemEntity item,
            ProductionOrderWarehouseUnitEntity unit,
            ProductEntity product
    ) {
        String fromUnit = normalizeUnitSizeKey(unit != null ? unit.getSizeKey() : null);
        if (!fromUnit.isEmpty()) {
            return fromUnit;
        }

        Map<String, Integer> breakdown = expectedUnitsBySize(item);
        List<String> sizes = breakdown.keySet().stream()
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (sizes.size() == 1) {
            return sizes.get(0);
        }

        if (item.getOnlineSaleId() != null) {
            List<OnlineSaleItemEntity> saleItems =
                    onlineSaleItemRepository.findByOnlineSaleIdOrderByIdAsc(item.getOnlineSaleId());
            List<String> saleSizes = (saleItems == null ? List.<OnlineSaleItemEntity>of() : saleItems).stream()
                    .filter(si -> Objects.equals(si.getProductId(), item.getProductId()))
                    .filter(si -> Objects.equals(si.getColorId(), item.getColorId())
                            || (si.getColorId() == null && item.getColorId() == null))
                    .map(OnlineSaleItemEntity::getSize)
                    .map(this::normalizeUnitSizeKey)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());
            if (saleSizes.size() == 1) {
                return saleSizes.get(0);
            }
            // Si la venta tiene una sola talla en todas sus líneas del mismo producto.
            List<String> anySaleSizes = (saleItems == null ? List.<OnlineSaleItemEntity>of() : saleItems).stream()
                    .filter(si -> Objects.equals(si.getProductId(), item.getProductId()))
                    .map(OnlineSaleItemEntity::getSize)
                    .map(this::normalizeUnitSizeKey)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());
            if (anySaleSizes.size() == 1) {
                return anySaleSizes.get(0);
            }
            return onlineSaleRepository.findById(item.getOnlineSaleId())
                    .map(OnlineSaleEntity::getSize)
                    .map(this::normalizeUnitSizeKey)
                    .filter(s -> !s.isEmpty())
                    .orElse("");
        }

        // No forzar talla inventada en FOSS; el caller valida.
        if (product != null && CinchoProductUtils.isFossCinchoProduct(product)) {
            return "";
        }
        return "";
    }

    private void syncItemReceivedQty(ProductionOrderItemEntity item) {
        List<ProductionOrderWarehouseUnitEntity> itemUnits = unitRepository
                .findByProductionOrderItemIdOrderBySizeKeyAscUnitSeqAsc(item.getId());
        int received = (int) itemUnits.stream()
                .filter(u -> STATUS_RECEIVED.equals(normalizeReceiptStatus(u.getReceiptStatus())))
                .count();
        item.setWarehouseReceivedQty(received);
    }

    @Transactional(rollbackFor = Exception.class)
    public void ensureUnitsForOrder(ProductionOrderEntity po) {
        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(po.getId());
        for (ProductionOrderItemEntity item : items) {
            reconcileUnitsForItem(po, item);
        }
    }

    private void generateUnitsForItem(ProductionOrderEntity po, ProductionOrderItemEntity item) {
        Map<String, Integer> sizeBreakdown = parseSizesToIntMap(item.getSizesData());
        List<ProductionOrderWarehouseUnitEntity> toSave = new ArrayList<>();
        String colorName = resolveColorName(item.getColorId());
        int globalIndex = 0;

        if (!sizeBreakdown.isEmpty()) {
            for (Map.Entry<String, Integer> entry : sizeBreakdown.entrySet()) {
                String sizeKey = normalizeUnitSizeKey(entry.getKey());
                int qty = entry.getValue() != null ? Math.max(0, entry.getValue()) : 0;
                for (int seq = 1; seq <= qty; seq++) {
                    globalIndex++;
                    toSave.add(buildUnit(po, item, globalIndex, colorName, sizeKey, seq));
                }
            }
        } else {
            int qty = item.getQuantity() != null ? Math.max(0, item.getQuantity()) : 0;
            for (int seq = 1; seq <= qty; seq++) {
                globalIndex++;
                toSave.add(buildUnit(po, item, globalIndex, colorName, "", seq));
            }
        }
        if (!toSave.isEmpty()) {
            unitRepository.saveAll(toSave);
        }
    }

    private ProductionOrderWarehouseUnitEntity buildUnit(
            ProductionOrderEntity po,
            ProductionOrderItemEntity item,
            int globalIndex,
            String colorName,
            String sizeKey,
            int unitSeq
    ) {
        ProductEntity product = item.getProductId() != null
                ? productRepository.findById(item.getProductId()).orElse(null)
                : null;
        StringBuilder label = new StringBuilder("#").append(globalIndex);
        if (colorName != null && !colorName.isBlank()) {
            label.append(" · ").append(colorName);
        }
        if (sizeKey != null && !sizeKey.isBlank()) {
            label.append(" · Talla ").append(sizeKey);
        }
        if (product != null && product.getCode() != null) {
            label.append(" · ").append(product.getCode());
        }
        return ProductionOrderWarehouseUnitEntity.builder()
                .productionOrderId(po.getId())
                .productionOrderItemId(item.getId())
                .unitLabel(label.toString())
                .colorId(item.getColorId())
                .sizeKey(sizeKey != null ? sizeKey : "")
                .unitSeq(unitSeq)
                .receiptStatus(STATUS_PENDING)
                .build();
    }

    private Map<String, Integer> parseSizesToIntMap(String sizesData) {
        Map<String, Integer> result = new LinkedHashMap<>();
        ProductInventorySizesJson.parse(sizesData).forEach((k, v) -> {
            if (v != null && v.compareTo(BigDecimal.ZERO) > 0) {
                result.put(k, v.intValue());
            }
        });
        return result;
    }

    private WarehouseWorkspaceResponse.Summary buildSummary(ProductionOrderEntity po, List<ProductionOrderWarehouseUnitEntity> units) {
        int pending = 0;
        int received = 0;
        int rejected = 0;
        int shipped = 0;
        for (ProductionOrderWarehouseUnitEntity u : units) {
            String st = normalizeReceiptStatus(u.getReceiptStatus());
            if (STATUS_RECEIVED.equals(st)) {
                received++;
            } else if (STATUS_REJECTED.equals(st)) {
                rejected++;
            } else {
                pending++;
            }
            if (u.getShippedAt() != null) {
                shipped++;
            }
        }
        return WarehouseWorkspaceResponse.Summary.builder()
                .totalUnits(units.size())
                .pendingUnits(pending)
                .receivedUnits(received)
                .rejectedUnits(rejected)
                .shippedUnits(shipped)
                .receiptClosed(po.getWarehouseReceiptClosedAt() != null)
                .warehouseReceiptClosedAt(po.getWarehouseReceiptClosedAt())
                .build();
    }

    private WarehouseUnitResponse toUnitResponse(ProductionOrderWarehouseUnitEntity unit, ProductionOrderItemEntity item) {
        ProductEntity product = item != null && item.getProductId() != null
                ? productRepository.findById(item.getProductId()).orElse(null)
                : null;
        return WarehouseUnitResponse.builder()
                .id(unit.getId())
                .productionOrderId(unit.getProductionOrderId())
                .productionOrderItemId(unit.getProductionOrderItemId())
                .unitLabel(unit.getUnitLabel())
                .productId(item != null ? item.getProductId() : null)
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(unit.getColorId())
                .colorName(resolveColorName(unit.getColorId()))
                .sizeKey(unit.getSizeKey())
                .unitSeq(unit.getUnitSeq())
                .receiptStatus(normalizeReceiptStatus(unit.getReceiptStatus()))
                .rejectionReason(unit.getRejectionReason())
                .receivedAt(unit.getReceivedAt())
                .shipmentRefType(unit.getShipmentRefType())
                .shipmentRefId(unit.getShipmentRefId())
                .shippedAt(unit.getShippedAt())
                .shipped(unit.getShippedAt() != null)
                .build();
    }

    private String resolveColorName(Long colorId) {
        if (colorId == null) {
            return "";
        }
        return colorRepository.findById(colorId).map(ColorEntity::getName).orElse("");
    }

    private int countAvailableForShipmentLine(List<ProductionOrderItemEntity> matchingItems, String shipmentSizeKey) {
        int available = 0;
        for (ProductionOrderItemEntity item : matchingItems) {
            available += (int) unitRepository.findByProductionOrderItemIdOrderBySizeKeyAscUnitSeqAsc(item.getId())
                    .stream()
                    .filter(u -> STATUS_RECEIVED.equals(normalizeReceiptStatus(u.getReceiptStatus())))
                    .filter(u -> u.getShippedAt() == null)
                    .filter(u -> unitMatchesShipmentSize(item, u, shipmentSizeKey))
                    .count();
        }
        return available;
    }

    /**
     * Alineado con {@link ProductDistributionService#normalizeSize}: trim + mayúsculas.
     */
    private String normalizeUnitSizeKey(String size) {
        if (size == null || size.isBlank()) {
            return "";
        }
        return size.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Coincide talla del envío con la pieza. Si las piezas se generaron sin talla pero el ítem
     * solo tiene una talla en sizes_data, las piezas recibidas sin size_key cuentan para esa talla.
     */
    private boolean unitMatchesShipmentSize(
            ProductionOrderItemEntity item,
            ProductionOrderWarehouseUnitEntity unit,
            String shipmentSizeKey) {
        String required = normalizeUnitSizeKey(shipmentSizeKey);
        String unitSize = normalizeUnitSizeKey(unit.getSizeKey());
        if (required.isEmpty()) {
            return true;
        }
        if (required.equals(unitSize)) {
            return true;
        }
        if (!unitSize.isEmpty()) {
            return false;
        }
        Map<String, Integer> breakdown = expectedUnitsBySize(item);
        if (breakdown.isEmpty()) {
            return true;
        }
        return breakdown.size() == 1 && breakdown.containsKey(required);
    }

    private Map<String, Integer> expectedUnitsBySize(ProductionOrderItemEntity item) {
        Map<String, Integer> raw = parseSizesToIntMap(item.getSizesData());
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            String key = normalizeUnitSizeKey(entry.getKey());
            int qty = entry.getValue() != null ? Math.max(0, entry.getValue()) : 0;
            if (qty > 0) {
                expected.merge(key, qty, Integer::sum);
            }
        }
        if (expected.isEmpty()) {
            int qty = item.getQuantity() != null ? Math.max(0, item.getQuantity()) : 0;
            if (qty > 0) {
                expected.put("", qty);
            }
        }
        return expected;
    }

    private void reconcileUnitsForItem(ProductionOrderEntity po, ProductionOrderItemEntity item) {
        List<ProductionOrderWarehouseUnitEntity> existing = unitRepository
                .findByProductionOrderItemIdOrderBySizeKeyAscUnitSeqAsc(item.getId());
        Map<String, Integer> expected = expectedUnitsBySize(item);
        if (existing.isEmpty()) {
            generateUnitsForItem(po, item);
            return;
        }
        if (!needsUnitResync(existing, expected)) {
            return;
        }
        boolean anyAccounted = existing.stream()
                .anyMatch(u -> !STATUS_PENDING.equals(normalizeReceiptStatus(u.getReceiptStatus()))
                        || u.getShippedAt() != null);
        if (anyAccounted) {
            return;
        }
        unitRepository.deleteAll(existing);
        generateUnitsForItem(po, item);
    }

    private boolean needsUnitResync(
            List<ProductionOrderWarehouseUnitEntity> existing,
            Map<String, Integer> expected) {
        long expectedTotal = expected.values().stream().mapToInt(Integer::intValue).sum();
        if (existing.size() != expectedTotal) {
            return true;
        }
        Map<String, Long> actual = existing.stream()
                .collect(Collectors.groupingBy(
                        u -> normalizeUnitSizeKey(u.getSizeKey()),
                        Collectors.counting()));
        Map<String, Long> expectedCounts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            expectedCounts.put(entry.getKey(), entry.getValue().longValue());
        }
        if (actual.equals(expectedCounts)) {
            return false;
        }
        // Piezas creadas sin talla pero el ítem ya tiene desglose por talla: no borrar al abrir bodega.
        if (actual.size() == 1 && actual.containsKey("") && expectedCounts.size() == 1) {
            return false;
        }
        return true;
    }

    private String buildInsufficientPtUnitsForShipmentMessage(
            Long productId,
            String sizeKey,
            int available,
            int needed,
            List<ProductionOrderItemEntity> matchingItems) {
        final String fallbackLabel = "producto " + productId;
        String productLabel = fallbackLabel;
        if (productId != null) {
            productLabel = productRepository.findById(productId)
                    .map(p -> p.getCode() != null && !p.getCode().isBlank() ? p.getCode() : fallbackLabel)
                    .orElse(fallbackLabel);
        }
        String normalizedSize = normalizeUnitSizeKey(sizeKey);
        String sizePart = normalizedSize.isEmpty() ? "" : " talla " + normalizedSize;
        int receivedOtherSize = 0;
        int pendingForSize = 0;
        for (ProductionOrderItemEntity item : matchingItems) {
            for (ProductionOrderWarehouseUnitEntity unit : unitRepository
                    .findByProductionOrderItemIdOrderBySizeKeyAscUnitSeqAsc(item.getId())) {
                if (unit.getShippedAt() != null) {
                    continue;
                }
                String status = normalizeReceiptStatus(unit.getReceiptStatus());
                if (STATUS_RECEIVED.equals(status) && !unitMatchesShipmentSize(item, unit, normalizedSize)) {
                    receivedOtherSize++;
                } else if (STATUS_PENDING.equals(status) && unitMatchesShipmentSize(item, unit, normalizedSize)) {
                    pendingForSize++;
                }
            }
        }
        StringBuilder hint = new StringBuilder();
        if (receivedOtherSize > 0) {
            hint.append(" Hay ").append(receivedOtherSize)
                    .append(" pieza(s) recibida(s) con otra talla o sin talla en el registro; verifique en Bodega PT.");
        }
        if (pendingForSize > 0) {
            hint.append(" Faltan marcar como recibidas ").append(pendingForSize)
                    .append(" pieza(s) de").append(sizePart).append(".");
        }
        return "No se puede confirmar el envío: en Bodega PT hay " + available
                + " pieza(s) marcada(s) como recibida(s) y sin despachar de " + productLabel
                + sizePart + ", pero el envío requiere " + needed + "."
                + hint
                + " Reciba las piezas correctas en Bodega PT (cada talla por separado) antes de confirmar."
                + " No es un fallo de stock en inventario general.";
    }

    private String normalizeReceiptStatus(String value) {
        if (value == null || value.isBlank()) {
            return STATUS_PENDING;
        }
        String n = value.trim().toUpperCase(Locale.ROOT);
        if (STATUS_RECEIVED.equals(n) || STATUS_REJECTED.equals(n)) {
            return n;
        }
        return STATUS_PENDING;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private LocationEntity getFinishedGoodsLocation() throws BusinessException {
        Optional<InventoryLocationTypeEntity> bodegaType = inventoryLocationTypeRepository.findByCodeAndIsActiveTrue("BODEGA_PT");
        if (bodegaType.isEmpty()) {
            throw new BusinessException("No existe el tipo de ubicacion BODEGA_PT.");
        }
        return locationRepository.findAll().stream()
                .filter(loc -> bodegaType.get().getCode().equalsIgnoreCase(loc.getCode()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("No existe una ubicacion configurada para BODEGA_PT."));
    }

    private void createReprocessTask(
            ProductionOrderEntity po,
            ProductionOrderItemEntity item,
            ProductEntity product,
            int rejectedQty,
            String rejectionReason
    ) throws BusinessException {
        String taskCode = generateTaskCode();
        String colorName = resolveColorName(item.getColorId());
        double estimated = (product != null && product.getPrdTime() != null && product.getPrdTime() > 0)
                ? product.getPrdTime() * rejectedQty
                : Math.max(0.25, rejectedQty * 0.25);

        TaskEntity task = TaskEntity.builder()
                .code(taskCode)
                .productionOrderId(po.getId())
                .productionOrderCode(po.getCode())
                .productionOrderItemId(item.getId())
                .productId(item.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(item.getColorId())
                .colorName(colorName)
                .quantity(rejectedQty)
                .estimatedHours(Math.round(estimated * 100.0) / 100.0)
                .deliveryDate(po.getDeliveryDate())
                .priority(1)
                .status("PENDING")
                .observations("REPROCESO por rechazo en bodega PT: " + rejectionReason)
                .build();
        TaskEntity savedTask = taskRepository.save(task);

        boolean requiresMaterials = product == null || !Boolean.FALSE.equals(product.getRequiresMaterials());
        taskItemRepository.save(TaskItemEntity.builder()
                .taskId(savedTask.getId())
                .productionOrderItemId(item.getId())
                .productId(item.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(item.getColorId())
                .colorName(colorName)
                .quantity(rejectedQty)
                .estimatedHours(Math.round(estimated * 100.0) / 100.0)
                .observations("REPROCESO por rechazo en bodega PT")
                .leatherDelivered(false)
                .materialsDelivered(!requiresMaterials)
                .materialsDeliveredAt(!requiresMaterials ? LocalDateTime.now() : null)
                .build());
    }

    private String generateTaskCode() throws BusinessException {
        String documentType = "TASK";
        String series = "TK";
        DocumentSeriesEntity seriesEntity = documentSeriesRepository
                .findByDocumentTypeAndSeriesForUpdate(documentType, series)
                .orElseGet(() -> documentSeriesRepository.save(DocumentSeriesEntity.builder()
                        .documentType(documentType)
                        .series(series)
                        .currentCorrelative(0L)
                        .status("active")
                        .description("Serie automática para tareas")
                        .build()));
        documentSeriesRepository.incrementCorrelative(seriesEntity.getId());
        seriesEntity.setCurrentCorrelative(seriesEntity.getCurrentCorrelative() + 1);
        documentSeriesRepository.save(seriesEntity);
        return String.format("%s-%05d", series, seriesEntity.getCurrentCorrelative());
    }
}
