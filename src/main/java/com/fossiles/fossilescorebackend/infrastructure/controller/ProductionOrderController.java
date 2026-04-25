package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderItemRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderRequest;
import com.fossiles.fossilescorebackend.application.dto.request.WarehouseReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.response.*;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.ProductInventoryService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.application.service.CustomerShipmentDispatchService;
import com.fossiles.fossilescorebackend.application.service.ProductionOrderCodeService;
import com.fossiles.fossilescorebackend.application.service.SmartMaterialRequestService;
import com.fossiles.fossilescorebackend.application.service.WarehouseOrderViewAssembler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/production-orders")
@RequiredArgsConstructor
public class ProductionOrderController {
    private static final String OPV_PACKING_TAG = "__OPV_PACKING__:";
    private static final String OPV_SHIPPING_TAG = "__OPV_SHIPPING__:";

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final DocumentSeriesRepository documentSeriesRepository;
    private final SmartMaterialRequestService smartMaterialRequestService;
    private final ProductionOrderCodeService productionOrderCodeService;
    private final WarehouseOrderViewAssembler warehouseOrderViewAssembler;
    private final CustomerShipmentDispatchService customerShipmentDispatchService;
    private final ProductDistributionRepository distributionRepository;
    private final ProductShipmentRepository shipmentRepository;
    private final ProductShipmentDetailRepository shipmentDetailRepository;
    private final LocationRepository locationRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final OnlineSaleItemRepository onlineSaleItemRepository;
    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final MaterialRepository materialRepository;
    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;
    private final ProductInventoryService productInventoryService;
    private final com.fossiles.fossilescorebackend.application.service.MaterialConsumptionService materialConsumptionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    public ResponseEntity<List<ProductionOrderResponse>> getAll() {
        List<ProductionOrderResponse> orders = productionOrderRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductionOrderResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        ProductionOrderEntity entity = productionOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @GetMapping("/type/{orderType}")
    public ResponseEntity<List<ProductionOrderResponse>> getByType(@PathVariable String orderType) {
        List<ProductionOrderResponse> orders = productionOrderRepository.findByOrderType(orderType).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<ProductionOrderResponse>> getByStatus(@PathVariable String status) {
        List<ProductionOrderResponse> orders = productionOrderRepository.findByStatus(status).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(orders);
    }

    @PostMapping
    public ResponseEntity<ProductionOrderResponse> create(@Valid @RequestBody ProductionOrderRequest request)
            throws BusinessException, ResourceNotFoundException {
        String effectiveOrderType = normalizeOrderType(request.getOrderType(), request.getSellerName());

        // Validar que el tipo de orden sea válido
        if (!isValidOrderType(effectiveOrderType)) {
            throw new BusinessException("Invalid order type. Must be one of: CINCHOS, MARCAS(OPV), NORMAL, DISTRIBUTION, VENTA_EN_LINEA");
        }

        // Generar código automáticamente si no se proporciona
        String orderCode = request.getCode();
        if (orderCode == null || orderCode.trim().isEmpty()) {
            orderCode = productionOrderCodeService.generateNextCode(effectiveOrderType);
        }

        if (productionOrderRepository.existsByCode(orderCode)) {
            throw new BusinessException("Production order code already exists: " + orderCode);
        }

        ProductionOrderEntity entity = toEntity(request);
        entity.setOrderType(effectiveOrderType);
        entity.setCode(orderCode);
        ProductionOrderEntity saved = productionOrderRepository.save(entity);

        // Guardar items
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            // Validar items antes de procesarlos
            for (ProductionOrderItemRequest itemRequest : request.getItems()) {
                if (itemRequest.getProductId() != null && !productRepository.existsById(itemRequest.getProductId())) {
                    throw new ResourceNotFoundException("Product", itemRequest.getProductId());
                }
                if (itemRequest.getColorId() != null && !colorRepository.existsById(itemRequest.getColorId())) {
                    throw new ResourceNotFoundException("Color", itemRequest.getColorId());
                }
            }

            List<ProductionOrderItemEntity> items = request.getItems().stream()
                    .map(itemRequest -> {
                        ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                                .productionOrderId(saved.getId())
                                .productId(itemRequest.getProductId())
                                .colorId(itemRequest.getColorId())
                                .quantity(itemRequest.getQuantity())
                                .warehouseReceivedQty(0)
                                .sizesData(itemRequest.getSizes() != null ? 
                                        convertSizesToJson(itemRequest.getSizes()) : null)
                                .observations(itemRequest.getObservations())
                                .build();
                        return productionOrderItemRepository.save(item);
                    })
                    .collect(Collectors.toList());
        }

        // Generar solicitudes de materiales automáticamente si falta stock
        try {
            if (request.getItems() != null && !request.getItems().isEmpty()) {
                for (ProductionOrderItemRequest item : request.getItems()) {
                    if (item.getProductId() != null) {
                        // Calcular cantidad total (quantity + sizes si aplica)
                        int totalQuantity = item.getQuantity() != null ? item.getQuantity() : 0;
                        if (item.getSizes() != null && !item.getSizes().isEmpty()) {
                            totalQuantity += item.getSizes().values().stream()
                                    .mapToInt(Integer::intValue)
                                    .sum();
                        }
                        
                        if (totalQuantity > 0) {
                            smartMaterialRequestService.checkAndGenerateRequestsForProductionOrder(
                                    saved.getId(),
                                    item.getProductId(),
                                    java.math.BigDecimal.valueOf(totalQuantity)
                            );
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log error pero no fallar la creación de la orden
            System.err.println("Error al generar solicitudes automáticas de materiales: " + e.getMessage());
            e.printStackTrace();
        }



        return ResponseEntity.created(URI.create("/api/production-orders/" + saved.getId()))
                .body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductionOrderResponse> update(@PathVariable Long id, 
            @Valid @RequestBody ProductionOrderRequest request)
            throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity entity = productionOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", id));

        String nextSeller = request.getSellerName() != null ? request.getSellerName() : entity.getSellerName();
        String requestedOrderType = request.getOrderType() != null ? request.getOrderType() : entity.getOrderType();
        String effectiveOrderType = normalizeOrderType(requestedOrderType, nextSeller);

        if (!entity.getCode().equals(request.getCode()) 
                && productionOrderRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Production order code already exists: " + request.getCode());
        }

        // Validar tipo de orden
        if (!isValidOrderType(effectiveOrderType)) {
            throw new BusinessException("Invalid order type. Must be one of: CINCHOS, MARCAS(OPV), NORMAL, DISTRIBUTION, VENTA_EN_LINEA");
        }

        updateEntity(entity, request);
        entity.setOrderType(effectiveOrderType);
        ProductionOrderEntity updated = productionOrderRepository.save(entity);

        // Actualizar items: eliminar existentes y crear nuevos
        if (request.getItems() != null) {
            productionOrderItemRepository.deleteByProductionOrderId(id);

            if (!request.getItems().isEmpty()) {
                // Validar items antes de procesarlos
                for (ProductionOrderItemRequest itemRequest : request.getItems()) {
                    if (itemRequest.getProductId() != null && !productRepository.existsById(itemRequest.getProductId())) {
                        throw new ResourceNotFoundException("Product", itemRequest.getProductId());
                    }
                    if (itemRequest.getColorId() != null && !colorRepository.existsById(itemRequest.getColorId())) {
                        throw new ResourceNotFoundException("Color", itemRequest.getColorId());
                    }
                }

                List<ProductionOrderItemEntity> items = request.getItems().stream()
                        .map(itemRequest -> {
                            ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                                    .productionOrderId(updated.getId())
                                    .productId(itemRequest.getProductId())
                                    .colorId(itemRequest.getColorId())
                                    .quantity(itemRequest.getQuantity())
                                    .warehouseReceivedQty(0)
                                    .sizesData(itemRequest.getSizes() != null ? 
                                            convertSizesToJson(itemRequest.getSizes()) : null)
                                    .observations(itemRequest.getObservations())
                                    .build();
                            return productionOrderItemRepository.save(item);
                        })
                        .collect(Collectors.toList());
            }
        }

        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        ProductionOrderEntity entity = productionOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", id));
        
        // Eliminar primero los items asociados
        productionOrderItemRepository.deleteByProductionOrderId(id);
        
        // Luego eliminar la orden de producción
        productionOrderRepository.deleteById(id);
        
        return ResponseEntity.noContent().build();
    }

    // ==================== CUSTOMER SHIPMENTS (Online Sales) ====================

    /**
     * Returns items grouped by customer (online sale) for a VENTA_EN_LINEA order.
     * Each group = one shipment to a customer.
     */
    @GetMapping("/{id}/customer-shipments")
    @Transactional(readOnly = true)
    public ResponseEntity<List<CustomerShipmentResponse>> getCustomerShipments(@PathVariable Long id)
            throws ResourceNotFoundException {
        ProductionOrderEntity po = productionOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", id));

        List<Long> saleIds = productionOrderItemRepository.findDistinctOnlineSaleIdsByProductionOrderId(id);
        List<CustomerShipmentResponse> shipments = new ArrayList<>();

        for (Long saleId : saleIds) {
            OnlineSaleEntity sale = onlineSaleRepository.findById(saleId).orElse(null);
            if (sale == null) continue;

            List<ProductionOrderItemEntity> items = productionOrderItemRepository
                    .findByProductionOrderId(id).stream()
                    .filter(i -> saleId.equals(i.getOnlineSaleId()))
                    .toList();

            // Also fetch sale items for size info
            List<OnlineSaleItemEntity> saleItems = onlineSaleItemRepository
                    .findByOnlineSaleIdOrderByIdAsc(saleId);

            List<CustomerShipmentResponse.ShipmentItem> shipmentItems = items.stream()
                    .map(item -> {
                        ProductEntity product = item.getProductId() != null
                                ? productRepository.findById(item.getProductId()).orElse(null) : null;
                        ColorEntity color = item.getColorId() != null
                                ? colorRepository.findById(item.getColorId()).orElse(null) : null;

                        // Find matching sale item for size info
                        String size = saleItems.stream()
                                .filter(si -> Objects.equals(si.getProductId(), item.getProductId())
                                        && Objects.equals(si.getColorId(), item.getColorId()))
                                .map(OnlineSaleItemEntity::getSize)
                                .findFirst().orElse(null);

                        return CustomerShipmentResponse.ShipmentItem.builder()
                                .productionOrderItemId(item.getId())
                                .productId(item.getProductId())
                                .productCode(product != null ? product.getCode() : null)
                                .productName(product != null ? product.getName() : null)
                                .colorId(item.getColorId())
                                .colorName(color != null ? color.getName() : null)
                                .quantity(item.getQuantity())
                                .size(size)
                                .build();
                    })
                    .collect(Collectors.toList());

            shipments.add(CustomerShipmentResponse.builder()
                    .onlineSaleId(sale.getId())
                    .saleNumber(sale.getSaleNumber())
                    .customerName(sale.getCustomerName())
                    .address(sale.getAddress())
                    .phone(sale.getPhone())
                    .phone2(sale.getPhone2())
                    .shipmentNumber(sale.getShipmentNumber())
                    .shippingCarrier(sale.getShippingCarrier())
                    .guideNumber(sale.getGuideNumber())
                    .paymentMethod(sale.getPaymentMethod())
                    .saleStatus(sale.getStatus())
                    .saleDate(sale.getSaleDate())
                    .totalAmount(sale.getTotalAmount())
                    .shippingCost(sale.getShippingCost())
                    .packaging(sale.getPackaging())
                    .items(shipmentItems)
                    .build());
        }

        return ResponseEntity.ok(shipments);
    }

    /**
     * Mark a customer shipment as dispatched (updates online sale status to ENVIADO).
     */
    @PutMapping("/{id}/dispatch-customer/{onlineSaleId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> dispatchCustomerShipment(
            @PathVariable Long id,
            @PathVariable Long onlineSaleId,
            @RequestBody(required = false) Map<String, String> body)
            throws ResourceNotFoundException, BusinessException {
        Map<String, String> payload = body != null ? body : Map.of();
        return ResponseEntity.ok(customerShipmentDispatchService.dispatchCustomerShipment(id, onlineSaleId, payload));
    }

    // ==================== WAREHOUSE VIEW ====================

    /**
     * Returns production orders for the warehouse team.
     * Includes dispatch destination info (kiosks or customers).
     */
    @GetMapping("/warehouse-view")
    @Transactional(readOnly = true)
    public ResponseEntity<List<WarehouseOrderViewResponse>> getWarehouseView(
            @RequestParam(required = false) String status) {

        List<String> statuses = status != null
                ? List.of(status)
                : List.of("PENDING", "IN_PROGRESS", "COMPLETED");

        List<ProductionOrderEntity> orders = productionOrderRepository.findByStatusIn(statuses);
        List<WarehouseOrderViewResponse> responses = orders.stream()
                .map(warehouseOrderViewAssembler::toWarehouseView)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @PutMapping("/{id}/warehouse-receipt")
    @Transactional
    public ResponseEntity<Map<String, Object>> receiveWarehouseProducts(
            @PathVariable Long id,
            @RequestBody WarehouseReceiptRequest request) throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity po = productionOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", id));

        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Debe enviar al menos un producto para recepción.");
        }

        LocationEntity finishedGoodsLocation = getFinishedGoodsLocation();
        Map<Long, ProductionOrderItemEntity> itemsById = productionOrderItemRepository.findByProductionOrderId(id).stream()
                .collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i));

        int totalApproved = 0;
        int totalRejected = 0;
        int processedRows = 0;

        for (WarehouseReceiptRequest.Item row : request.getItems()) {
            if (row == null || row.getProductionOrderItemId() == null) continue;

            ProductionOrderItemEntity item = itemsById.get(row.getProductionOrderItemId());
            if (item == null) {
                throw new BusinessException("El item " + row.getProductionOrderItemId() + " no pertenece a la orden " + po.getCode());
            }

            int approved = row.getApprovedQuantity() != null ? row.getApprovedQuantity() : 0;
            int rejected = row.getRejectedQuantity() != null ? row.getRejectedQuantity() : 0;
            if (approved < 0 || rejected < 0) {
                throw new BusinessException("Las cantidades aprobada/rechazada no pueden ser negativas.");
            }
            if (approved == 0 && rejected == 0) continue;

            int plannedQty = item.getQuantity() != null ? item.getQuantity() : 0;
            int alreadyReceived = item.getWarehouseReceivedQty() != null ? item.getWarehouseReceivedQty() : 0;
            int pending = Math.max(plannedQty - alreadyReceived, 0);
            if (pending <= 0) continue;
            if (approved + rejected > pending) {
                throw new BusinessException("La suma aprobada+rechazada excede pendiente para item " + item.getId() + ". Pendiente: " + pending);
            }
            if (rejected > 0 && (row.getRejectionReason() == null || row.getRejectionReason().trim().isEmpty())) {
                throw new BusinessException("Debe indicar motivo de rechazo para item " + item.getId());
            }

            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;

            if (approved > 0) {
                if (item.getProductId() == null) {
                    throw new BusinessException("El item " + item.getId() + " no tiene producto válido para ingreso a inventario.");
                }
                BigDecimal qty = BigDecimal.valueOf(approved);
                BigDecimal before = productInventoryService
                        .getInventoryByProductAndLocationAndColor(item.getProductId(), finishedGoodsLocation.getId(), item.getColorId())
                        .getQuantity();

                productInventoryService.incrementInventory(
                        item.getProductId(),
                        finishedGoodsLocation.getId(),
                        item.getColorId(),
                        qty,
                        null,
                        "PRODUCTION_ORDER",
                        po.getId(),
                        po.getCode(),
                        "Ingreso por recepción en bodega PT"
                );
                BigDecimal after = productInventoryService
                        .getInventoryByProductAndLocationAndColor(item.getProductId(), finishedGoodsLocation.getId(), item.getColorId())
                        .getQuantity();

                productInventoryService.recordMovement(
                        item.getProductId(),
                        finishedGoodsLocation.getId(),
                        item.getColorId(),
                        "PRODUCTION_ENTRY",
                        qty,
                        before,
                        after,
                        null,
                        "PRODUCTION_ORDER",
                        po.getId(),
                        po.getCode(),
                        "Recepción bodega PT - " + (product != null ? product.getCode() : "ITEM " + item.getId())
                );
            }

            if (rejected > 0) {
                createReprocessTask(po, item, product, rejected, row.getRejectionReason());
            }

            item.setWarehouseReceivedQty(alreadyReceived + approved);
            productionOrderItemRepository.save(item);

            totalApproved += approved;
            totalRejected += rejected;
            processedRows++;
        }

        if (processedRows == 0) {
            throw new BusinessException("No se procesó ninguna fila (revise cantidades pendientes).");
        }

        if (totalRejected > 0) {
            po.setStatus("IN_PROGRESS");
        }
        productionOrderRepository.save(po);

        return ResponseEntity.ok(Map.of(
                "message", "Recepción procesada correctamente.",
                "approvedQuantity", totalApproved,
                "rejectedQuantity", totalRejected,
                "processedRows", processedRows,
                "productionOrderId", po.getId(),
                "productionOrderCode", po.getCode()
        ));
    }

    // ==================== MATERIAL CONSUMPTION ====================

    @PostMapping("/{id}/consume-materials")
    @Transactional
    public ResponseEntity<Map<String, Object>> consumeMaterials(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(materialConsumptionService.consumeMaterialsForOrder(id));
    }

    @GetMapping("/{id}/validate-materials")
    public ResponseEntity<Map<String, Object>> validateMaterials(@PathVariable Long id)
            throws ResourceNotFoundException {
        return ResponseEntity.ok(materialConsumptionService.validateMaterialAvailability(id));
    }

    @GetMapping("/{id}/consumption-history")
    public ResponseEntity<List<Map<String, Object>>> getConsumptionHistory(@PathVariable Long id) {
        List<MaterialConsumptionEntity> consumptions = materialConsumptionRepository.findByProductionOrderId(id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (MaterialConsumptionEntity c : consumptions) {
            MaterialEntity mat = materialRepository.findById(c.getMaterialId()).orElse(null);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", c.getId());
            map.put("materialId", c.getMaterialId());
            map.put("materialName", mat != null ? mat.getName() : "Material #" + c.getMaterialId());
            map.put("quantityConsumed", c.getQuantityConsumed());
            map.put("status", c.getStatus());
            map.put("consumedAt", c.getConsumedAt());
            map.put("notes", c.getNotes());
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    // ==================== DASHBOARD & REPORTS ====================

    @GetMapping("/dashboard-stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        List<ProductionOrderEntity> allOrders = productionOrderRepository.findAll();
        List<TaskEntity> allTasks = taskRepository.findAll();

        java.time.LocalDate rangeFrom = from != null ? from : java.time.LocalDate.MIN;
        java.time.LocalDate rangeTo = to != null ? to : java.time.LocalDate.MAX;
        java.time.LocalDate referenceDay = to != null ? to : java.time.LocalDate.now();

        List<ProductionOrderEntity> scopedOrders = allOrders.stream()
                .filter(order -> {
                    java.time.LocalDate candidate = order.getStartDate();
                    if (candidate == null && order.getCreatedAt() != null) {
                        candidate = order.getCreatedAt().toLocalDate();
                    }
                    if (candidate == null) return true;
                    return !candidate.isBefore(rangeFrom) && !candidate.isAfter(rangeTo);
                })
                .toList();

        List<TaskEntity> scopedTasks = allTasks.stream()
                .filter(task -> {
                    java.time.LocalDate candidate = task.getScheduledDate();
                    if (candidate == null && task.getCreatedAt() != null) {
                        candidate = task.getCreatedAt().toLocalDate();
                    }
                    if (candidate == null) return true;
                    return !candidate.isBefore(rangeFrom) && !candidate.isAfter(rangeTo);
                })
                .toList();

        long totalOrders = scopedOrders.size();
        long pendingOrders = scopedOrders.stream().filter(o -> "PENDING".equals(o.getStatus())).count();
        long inProgressOrders = scopedOrders.stream().filter(o -> "IN_PROGRESS".equals(o.getStatus())).count();
        long inQaOrders = scopedOrders.stream().filter(o -> "IN_QA".equals(o.getStatus())).count();
        long completedOrders = scopedOrders.stream().filter(o -> "COMPLETED".equals(o.getStatus())).count();

        long totalTasks = scopedTasks.size();
        long pendingTasks = scopedTasks.stream().filter(t -> "PENDING".equals(t.getStatus())).count();
        long inProgressTasks = scopedTasks.stream().filter(t -> "IN_PROGRESS".equals(t.getStatus())).count();
        long completedTasks = scopedTasks.stream().filter(t -> "COMPLETED".equals(t.getStatus())).count();

        // Producción del día (usa "to" como día de referencia si viene filtro)
        long todayCompleted = scopedTasks.stream()
                .filter(t -> "COMPLETED".equals(t.getStatus()) && t.getCompletedAt() != null
                        && t.getCompletedAt().toLocalDate().equals(referenceDay))
                .count();
        int todayQuantity = scopedTasks.stream()
                .filter(t -> "COMPLETED".equals(t.getStatus()) && t.getCompletedAt() != null
                        && t.getCompletedAt().toLocalDate().equals(referenceDay))
                .mapToInt(t -> t.getQuantity() != null ? t.getQuantity() : 0)
                .sum();
        int todayPlanned = scopedTasks.stream()
                .filter(t -> t.getScheduledDate() != null && t.getScheduledDate().equals(referenceDay)
                        && !"CANCELLED".equals(t.getStatus()))
                .mapToInt(t -> t.getQuantity() != null ? t.getQuantity() : 0)
                .sum();

        // Tareas con desperdicio
        int totalWaste = scopedTasks.stream()
                .mapToInt(t -> t.getWasteQuantity() != null ? t.getWasteQuantity() : 0)
                .sum();

        // Tiempo promedio de tareas completadas (en minutos)
        double avgDuration = scopedTasks.stream()
                .filter(t -> t.getActualDurationMinutes() != null && t.getActualDurationMinutes() > 0)
                .mapToInt(TaskEntity::getActualDurationMinutes)
                .average().orElse(0);

        long completedTasksWithEstimate = scopedTasks.stream()
                .filter(t -> "COMPLETED".equals(t.getStatus())
                        && t.getEstimatedHours() != null && t.getEstimatedHours() > 0
                        && t.getActualDurationMinutes() != null && t.getActualDurationMinutes() > 0)
                .count();

        long completedTasksOnTime = scopedTasks.stream()
                .filter(t -> "COMPLETED".equals(t.getStatus())
                        && t.getEstimatedHours() != null && t.getEstimatedHours() > 0
                        && t.getActualDurationMinutes() != null && t.getActualDurationMinutes() > 0)
                .filter(t -> t.getActualDurationMinutes() <= Math.round(t.getEstimatedHours() * 60))
                .count();

        // Órdenes atrasadas
        long overdueOrders = scopedOrders.stream()
                .filter(o -> o.getDeliveryDate() != null && o.getDeliveryDate().isBefore(referenceDay)
                        && !"COMPLETED".equals(o.getStatus()) && !"CANCELLED".equals(o.getStatus()))
                .count();

        // Producción por mes (últimos 6 meses o rango seleccionado)
        List<Map<String, Object>> monthlyProduction = new ArrayList<>();
        java.time.LocalDate monthStartBase = from != null ? from.withDayOfMonth(1) : referenceDay.minusMonths(5).withDayOfMonth(1);
        java.time.LocalDate monthEndBase = to != null ? to.withDayOfMonth(1) : referenceDay.withDayOfMonth(1);
        java.time.YearMonth startYm = java.time.YearMonth.from(monthStartBase);
        java.time.YearMonth endYm = java.time.YearMonth.from(monthEndBase);
        while (!startYm.isAfter(endYm)) {
            java.time.LocalDate monthStart = startYm.atDay(1);
            java.time.LocalDate monthEnd = startYm.atEndOfMonth();
            int monthQty = scopedTasks.stream()
                    .filter(t -> "COMPLETED".equals(t.getStatus()) && t.getCompletedAt() != null
                            && !t.getCompletedAt().toLocalDate().isBefore(monthStart)
                            && !t.getCompletedAt().toLocalDate().isAfter(monthEnd))
                    .mapToInt(t -> t.getQuantity() != null ? t.getQuantity() : 0)
                    .sum();
            monthlyProduction.add(Map.of(
                    "month", monthStart.getMonth().toString(),
                    "year", monthStart.getYear(),
                    "quantity", monthQty
            ));
            startYm = startYm.plusMonths(1);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalOrders", totalOrders);
        stats.put("pendingOrders", pendingOrders);
        stats.put("inProgressOrders", inProgressOrders);
        stats.put("inQaOrders", inQaOrders);
        stats.put("completedOrders", completedOrders);
        stats.put("overdueOrders", overdueOrders);
        stats.put("totalTasks", totalTasks);
        stats.put("pendingTasks", pendingTasks);
        stats.put("inProgressTasks", inProgressTasks);
        stats.put("completedTasks", completedTasks);
        stats.put("todayCompletedTasks", todayCompleted);
        stats.put("todayQuantityProduced", todayQuantity);
        stats.put("todayPlannedQuantity", todayPlanned);
        stats.put("totalWaste", totalWaste);
        stats.put("avgTaskDurationMinutes", Math.round(avgDuration));
        stats.put("monthlyProduction", monthlyProduction);
        stats.put("orderCompletionRate", totalOrders > 0 ? Math.round((completedOrders * 100.0 / totalOrders) * 10.0) / 10.0 : 0.0);
        stats.put("taskCompletionRate", totalTasks > 0 ? Math.round((completedTasks * 100.0 / totalTasks) * 10.0) / 10.0 : 0.0);
        int totalProducedInScope = scopedTasks.stream()
                .mapToInt(t -> t.getQuantity() != null ? t.getQuantity() : 0)
                .sum();
        stats.put("wasteRate", totalProducedInScope > 0
                ? Math.round((totalWaste * 100.0 / totalProducedInScope) * 10.0) / 10.0 : 0.0);
        stats.put("onTimeTaskRate", completedTasksWithEstimate > 0
                ? Math.round((completedTasksOnTime * 100.0 / completedTasksWithEstimate) * 10.0) / 10.0 : 0.0);

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/reports")
    public ResponseEntity<Map<String, Object>> getProductionReports(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {

        if (from == null) from = java.time.LocalDate.now().minusMonths(1);
        if (to == null) to = java.time.LocalDate.now();
        if (type == null) type = "daily";

        List<TaskEntity> allTasks = taskRepository.findAll();
        final java.time.LocalDate fFrom = from;
        final java.time.LocalDate fTo = to;

        // Filtrar tareas por rango
        List<TaskEntity> filtered = allTasks.stream()
                .filter(t -> "COMPLETED".equals(t.getStatus()) && t.getCompletedAt() != null
                        && !t.getCompletedAt().toLocalDate().isBefore(fFrom)
                        && !t.getCompletedAt().toLocalDate().isAfter(fTo))
                .toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", type);
        report.put("from", from);
        report.put("to", to);
        report.put("totalTasks", filtered.size());
        report.put("totalQuantity", filtered.stream().mapToInt(t -> t.getQuantity() != null ? t.getQuantity() : 0).sum());
        report.put("totalWaste", filtered.stream().mapToInt(t -> t.getWasteQuantity() != null ? t.getWasteQuantity() : 0).sum());
        report.put("wasteRate", filtered.stream().mapToInt(t -> t.getQuantity() != null ? t.getQuantity() : 0).sum() > 0
                ? Math.round((filtered.stream().mapToInt(t -> t.getWasteQuantity() != null ? t.getWasteQuantity() : 0).sum() * 100.0
                / filtered.stream().mapToInt(t -> t.getQuantity() != null ? t.getQuantity() : 0).sum()) * 10.0) / 10.0
                : 0.0);

        switch (type) {
            case "daily" -> {
                Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
                for (TaskEntity t : filtered) {
                    String date = t.getCompletedAt().toLocalDate().toString();
                    byDate.computeIfAbsent(date, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("date", k);
                        m.put("tasks", 0);
                        m.put("quantity", 0);
                        m.put("waste", 0);
                        return m;
                    });
                    Map<String, Object> m = byDate.get(date);
                    m.put("tasks", (int) m.get("tasks") + 1);
                    m.put("quantity", (int) m.get("quantity") + (t.getQuantity() != null ? t.getQuantity() : 0));
                    m.put("waste", (int) m.get("waste") + (t.getWasteQuantity() != null ? t.getWasteQuantity() : 0));
                }
                List<Map<String, Object>> data = new ArrayList<>(byDate.values());
                data.sort(Comparator.comparing(m -> String.valueOf(m.get("date"))));
                report.put("data", data);
            }
            case "product" -> {
                Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
                for (TaskEntity t : filtered) {
                    String key = t.getProductName() != null ? t.getProductName() : "Sin producto";
                    byProduct.computeIfAbsent(key, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("product", k);
                        m.put("tasks", 0);
                        m.put("quantity", 0);
                        m.put("waste", 0);
                        return m;
                    });
                    Map<String, Object> m = byProduct.get(key);
                    m.put("tasks", (int) m.get("tasks") + 1);
                    m.put("quantity", (int) m.get("quantity") + (t.getQuantity() != null ? t.getQuantity() : 0));
                    m.put("waste", (int) m.get("waste") + (t.getWasteQuantity() != null ? t.getWasteQuantity() : 0));
                }
                List<Map<String, Object>> data = new ArrayList<>(byProduct.values());
                data.sort((a, b) -> Integer.compare((int) b.get("quantity"), (int) a.get("quantity")));
                report.put("data", data);
            }
            case "efficiency" -> {
                List<Map<String, Object>> effData = new ArrayList<>();
                for (TaskEntity t : filtered) {
                    if (t.getEstimatedHours() != null && t.getActualDurationMinutes() != null) {
                        double estimatedMin = t.getEstimatedHours() * 60;
                        double actualMin = t.getActualDurationMinutes();
                        double efficiency = estimatedMin > 0 ? (estimatedMin / actualMin) * 100 : 0;
                        effData.add(Map.of(
                                "taskCode", t.getCode(),
                                "product", t.getProductName() != null ? t.getProductName() : "",
                                "estimatedMinutes", estimatedMin,
                                "actualMinutes", actualMin,
                                "efficiency", Math.round(efficiency)
                        ));
                    }
                }
                effData.sort((a, b) -> Integer.compare((int) b.get("efficiency"), (int) a.get("efficiency")));
                double avgEfficiency = effData.stream()
                        .mapToInt(m -> (int) m.get("efficiency"))
                        .average().orElse(0);
                report.put("avgEfficiency", Math.round(avgEfficiency * 10.0) / 10.0);
                report.put("data", effData);
            }
            case "stage" -> {
                List<ProductionOrderEntity> orders = productionOrderRepository.findAll().stream()
                        .filter(order -> {
                            java.time.LocalDate candidate = order.getStartDate();
                            if (candidate == null && order.getCreatedAt() != null) {
                                candidate = order.getCreatedAt().toLocalDate();
                            }
                            if (candidate == null) return true;
                            return !candidate.isBefore(fFrom) && !candidate.isAfter(fTo);
                        })
                        .toList();

                Map<String, Long> byStatus = new LinkedHashMap<>();
                for (ProductionOrderEntity order : orders) {
                    byStatus.merge(order.getStatus() != null ? order.getStatus() : "UNKNOWN", 1L, Long::sum);
                }
                List<Map<String, Object>> stageData = new ArrayList<>();
                byStatus.forEach((status, count) -> stageData.add(Map.of("status", status, "count", count)));
                stageData.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));
                report.put("totalOrders", orders.size());
                report.put("data", stageData);
            }
            case "weekly" -> {
                Map<String, Map<String, Object>> byWeek = new LinkedHashMap<>();
                java.time.temporal.WeekFields weekFields = java.time.temporal.WeekFields.ISO;
                for (TaskEntity t : filtered) {
                    java.time.LocalDate d = t.getCompletedAt().toLocalDate();
                    int weekNum = d.get(weekFields.weekOfWeekBasedYear());
                    int yearNum = d.get(weekFields.weekBasedYear());
                    String key = yearNum + "-S" + String.format("%02d", weekNum);
                    byWeek.computeIfAbsent(key, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("week", k);
                        m.put("tasks", 0);
                        m.put("quantity", 0);
                        m.put("waste", 0);
                        return m;
                    });
                    Map<String, Object> m = byWeek.get(key);
                    m.put("tasks", (int) m.get("tasks") + 1);
                    m.put("quantity", (int) m.get("quantity") + (t.getQuantity() != null ? t.getQuantity() : 0));
                    m.put("waste", (int) m.get("waste") + (t.getWasteQuantity() != null ? t.getWasteQuantity() : 0));
                }
                List<Map<String, Object>> data = new ArrayList<>(byWeek.values());
                data.sort(Comparator.comparing(m -> String.valueOf(m.get("week"))));
                report.put("data", data);
            }
            case "monthly" -> {
                Map<String, Map<String, Object>> byMonth = new LinkedHashMap<>();
                for (TaskEntity t : filtered) {
                    String key = t.getCompletedAt().toLocalDate().toString().substring(0, 7);
                    byMonth.computeIfAbsent(key, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("month", k);
                        m.put("tasks", 0);
                        m.put("quantity", 0);
                        m.put("waste", 0);
                        return m;
                    });
                    Map<String, Object> m = byMonth.get(key);
                    m.put("tasks", (int) m.get("tasks") + 1);
                    m.put("quantity", (int) m.get("quantity") + (t.getQuantity() != null ? t.getQuantity() : 0));
                    m.put("waste", (int) m.get("waste") + (t.getWasteQuantity() != null ? t.getWasteQuantity() : 0));
                }
                List<Map<String, Object>> data = new ArrayList<>(byMonth.values());
                data.sort(Comparator.comparing(m -> String.valueOf(m.get("month"))));
                report.put("data", data);
            }
            case "product-stage" -> {
                List<ProductionOrderEntity> orders = productionOrderRepository.findAll().stream()
                        .filter(order -> {
                            java.time.LocalDate candidate = order.getStartDate();
                            if (candidate == null && order.getCreatedAt() != null) {
                                candidate = order.getCreatedAt().toLocalDate();
                            }
                            if (candidate == null) return true;
                            return !candidate.isBefore(fFrom) && !candidate.isAfter(fTo);
                        })
                        .toList();

                Set<Long> scopedOrderIds = orders.stream()
                        .map(ProductionOrderEntity::getId)
                        .collect(Collectors.toSet());
                Map<Long, String> orderStatusMap = orders.stream()
                        .collect(Collectors.toMap(
                                ProductionOrderEntity::getId,
                                o -> o.getStatus() != null ? o.getStatus() : "UNKNOWN"
                        ));
                Map<Long, String> productNameMap = productRepository.findAll().stream()
                        .collect(Collectors.toMap(ProductEntity::getId, ProductEntity::getName));

                Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
                productionOrderItemRepository.findAll().stream()
                        .filter(item -> scopedOrderIds.contains(item.getProductionOrderId()))
                        .forEach(item -> {
                            String productName = item.getProductId() != null
                                    ? productNameMap.getOrDefault(item.getProductId(), "Sin producto")
                                    : "Sin producto";
                            String status = orderStatusMap.getOrDefault(item.getProductionOrderId(), "UNKNOWN");
                            int qty = item.getQuantity() != null ? item.getQuantity() : 0;

                            byProduct.computeIfAbsent(productName, k -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("product", k);
                                m.put("pendingQty", 0);
                                m.put("inProgressQty", 0);
                                m.put("completedQty", 0);
                                m.put("totalQty", 0);
                                return m;
                            });
                            Map<String, Object> m = byProduct.get(productName);
                            m.put("totalQty", (int) m.get("totalQty") + qty);
                            if ("PENDING".equals(status)) {
                                m.put("pendingQty", (int) m.get("pendingQty") + qty);
                            } else if ("IN_PROGRESS".equals(status) || "IN_QA".equals(status)) {
                                m.put("inProgressQty", (int) m.get("inProgressQty") + qty);
                            } else if ("COMPLETED".equals(status)) {
                                m.put("completedQty", (int) m.get("completedQty") + qty);
                            }
                        });

                List<Map<String, Object>> data = new ArrayList<>(byProduct.values());
                data.sort((a, b) -> Integer.compare((int) b.get("totalQty"), (int) a.get("totalQty")));
                report.put("totalOrders", orders.size());
                report.put("data", data);
            }
        }

        return ResponseEntity.ok(report);
    }

    private ProductionOrderResponse toResponse(ProductionOrderEntity entity) {
        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(entity.getId());
        OrderMeta meta = parseOrderMeta(entity.getObservations());
        
        List<ProductionOrderItemResponse> itemResponses = items.stream()
                .map(item -> {
                    ProductEntity product = item.getProductId() != null ? 
                            productRepository.findById(item.getProductId()).orElse(null) : null;
                    ColorEntity color = item.getColorId() != null ? 
                            colorRepository.findById(item.getColorId()).orElse(null) : null;

                    Map<String, Integer> sizes = null;
                    if (item.getSizesData() != null && !item.getSizesData().isEmpty()) {
                        try {
                            sizes = objectMapper.readValue(item.getSizesData(), 
                                    new TypeReference<Map<String, Integer>>() {});
                        } catch (Exception e) {
                            // Si hay error parseando JSON, dejar sizes como null
                        }
                    }

                    java.math.BigDecimal leatherCons = product != null ? product.getLeatherConsumption() : null;
                    java.math.BigDecimal leatherTotal = null;
                    if (leatherCons != null && item.getQuantity() != null) {
                        leatherTotal = leatherCons.multiply(java.math.BigDecimal.valueOf(item.getQuantity()));
                    }

                    return ProductionOrderItemResponse.builder()
                            .id(item.getId())
                            .productionOrderId(item.getProductionOrderId())
                            .onlineSaleId(item.getOnlineSaleId())
                            .productId(item.getProductId())
                            .productName(product != null ? product.getName() : null)
                            .productCode(product != null ? product.getCode() : null)
                            .colorId(item.getColorId())
                            .colorName(color != null ? color.getName() : null)
                            .quantity(item.getQuantity())
                            .warehouseReceivedQty(item.getWarehouseReceivedQty())
                            .leatherConsumption(leatherCons)
                            .leatherTotal(leatherTotal)
                            .sizes(sizes)
                            .observations(item.getObservations())
                            .createdAt(item.getCreatedAt())
                            .createdBy(item.getCreatedBy())
                            .updatedAt(item.getUpdatedAt())
                            .updatedBy(item.getUpdatedBy())
                            .build();
                })
                .collect(Collectors.toList());

        ProductionOrderResponse.ProductionOrderResponseBuilder builder = ProductionOrderResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .orderType(entity.getOrderType())
                .customerId(entity.getCustomerId())
                .customerName(entity.getCustomerName())
                .sellerName(entity.getSellerName())
                .startDate(entity.getStartDate())
                .deliveryDate(entity.getDeliveryDate())
                .observations(meta.baseObservations)
                .materialsConsumed(entity.getMaterialsConsumed())
                .materialsConsumedAt(entity.getMaterialsConsumedAt())
                .status(entity.getStatus())
                .distributionId(entity.getDistributionId())
                .shippingCost(meta.shippingCost)
                .packingItems(meta.packingItems.stream().map(item -> {
                    MaterialEntity material = item.materialId != null
                            ? materialRepository.findById(item.materialId).orElse(null)
                            : null;
                    return ProductionOrderResponse.PackingItemResponse.builder()
                            .materialId(item.materialId)
                            .quantity(item.quantity)
                            .unitPrice(item.unitPrice)
                            .materialCode(material != null ? material.getSku() : null)
                            .materialName(material != null ? material.getName() : null)
                            .build();
                }).collect(Collectors.toList()))
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .items(itemResponses);

        // Si tiene distribución vinculada, incluir detalles
        if (entity.getDistributionId() != null) {
            distributionRepository.findById(entity.getDistributionId()).ifPresent(dist -> {
                builder.distributionNumber(dist.getDistributionNumber());
                builder.distributionDate(dist.getDistributionDate());

                List<ProductShipmentResponse> shipmentResponses = shipmentRepository
                        .findByDistributionId(dist.getId()).stream()
                        .map(warehouseOrderViewAssembler::toShipmentResponse)
                        .collect(Collectors.toList());
                builder.distributionShipments(shipmentResponses);
            });
        }

        return builder.build();
    }

    private ProductionOrderEntity toEntity(ProductionOrderRequest request) {
        String observations = composeOrderObservations(request.getObservations(), request.getPackingItems(), request.getShippingCost());
        return ProductionOrderEntity.builder()
                .code(request.getCode())
                .orderType(request.getOrderType())
                .customerId(request.getCustomerId())
                .customerName(request.getCustomerName())
                .sellerName(request.getSellerName())
                .startDate(request.getStartDate())
                .deliveryDate(request.getDeliveryDate())
                .observations(observations)
                .distributionId(request.getDistributionId())
                // Status is controlled by workflow (task generation, QA and dispatch), not manually by request.
                .status("PENDING")
                .build();
    }

    private void updateEntity(ProductionOrderEntity entity, ProductionOrderRequest request) {
        if (request.getCode() != null) entity.setCode(request.getCode());
        if (request.getCustomerId() != null) entity.setCustomerId(request.getCustomerId());
        if (request.getCustomerName() != null) entity.setCustomerName(request.getCustomerName());
        if (request.getSellerName() != null) entity.setSellerName(request.getSellerName());
        if (request.getStartDate() != null) entity.setStartDate(request.getStartDate());
        if (request.getDeliveryDate() != null) entity.setDeliveryDate(request.getDeliveryDate());
        OrderMeta existingMeta = parseOrderMeta(entity.getObservations());
        String nextBaseObservations = request.getObservations() != null ? request.getObservations() : existingMeta.baseObservations;
        List<ProductionOrderRequest.PackingItemRequest> nextPackingItems = request.getPackingItems() != null
                ? request.getPackingItems()
                : existingMeta.packingItems.stream()
                        .map(item -> ProductionOrderRequest.PackingItemRequest.builder()
                                .materialId(item.materialId)
                                .quantity(item.quantity)
                                .unitPrice(item.unitPrice)
                                .build())
                        .collect(Collectors.toList());
        BigDecimal nextShippingCost = request.getShippingCost() != null ? request.getShippingCost() : existingMeta.shippingCost;
        entity.setObservations(composeOrderObservations(nextBaseObservations, nextPackingItems, nextShippingCost));
        // Status is not editable from generic update to prevent out-of-flow jumps.
    }

    private String composeOrderObservations(
            String baseObservations,
            List<ProductionOrderRequest.PackingItemRequest> packingItems,
            BigDecimal shippingCost
    ) {
        List<String> lines = new ArrayList<>();
        if (baseObservations != null && !baseObservations.trim().isEmpty()) {
            lines.add(baseObservations.trim());
        }

        List<Map<String, Object>> packingPayload = (packingItems == null ? List.<ProductionOrderRequest.PackingItemRequest>of() : packingItems).stream()
                .filter(item -> item != null && item.getMaterialId() != null
                        && item.getQuantity() != null
                        && item.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .map(item -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("materialId", item.getMaterialId());
                    map.put("quantity", item.getQuantity());
                    map.put("unitPrice", item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);
                    return map;
                })
                .collect(Collectors.toList());

        try {
            if (!packingPayload.isEmpty()) {
                lines.add(OPV_PACKING_TAG + objectMapper.writeValueAsString(packingPayload));
            }
            if (shippingCost != null && shippingCost.compareTo(BigDecimal.ZERO) >= 0) {
                lines.add(OPV_SHIPPING_TAG + shippingCost);
            }
        } catch (Exception ignored) {
        }
        return lines.stream().filter(Objects::nonNull).collect(Collectors.joining("\n")).trim();
    }

    private OrderMeta parseOrderMeta(String rawObservations) {
        List<String> lines = String.valueOf(rawObservations == null ? "" : rawObservations).lines().collect(Collectors.toList());
        String packingRaw = "";
        String shippingRaw = "";
        List<String> base = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(OPV_PACKING_TAG)) {
                packingRaw = line.substring(OPV_PACKING_TAG.length()).trim();
            } else if (line.startsWith(OPV_SHIPPING_TAG)) {
                shippingRaw = line.substring(OPV_SHIPPING_TAG.length()).trim();
            } else {
                base.add(line);
            }
        }

        List<OrderMetaPackingItem> packingItems = new ArrayList<>();
        if (!packingRaw.isEmpty()) {
            try {
                List<Map<String, Object>> parsed = objectMapper.readValue(packingRaw, new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> item : parsed) {
                    Long materialId = item.get("materialId") == null ? null : Long.valueOf(String.valueOf(item.get("materialId")));
                    BigDecimal quantity = item.get("quantity") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(item.get("quantity")));
                    BigDecimal unitPrice = item.get("unitPrice") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(item.get("unitPrice")));
                    if (materialId != null && quantity.compareTo(BigDecimal.ZERO) > 0) {
                        packingItems.add(new OrderMetaPackingItem(materialId, quantity, unitPrice));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        BigDecimal shippingCost = BigDecimal.ZERO;
        if (!shippingRaw.isEmpty()) {
            try {
                shippingCost = new BigDecimal(shippingRaw);
            } catch (Exception ignored) {
                shippingCost = BigDecimal.ZERO;
            }
        }
        return new OrderMeta(String.join("\n", base).trim(), shippingCost, packingItems);
    }

    private static class OrderMeta {
        private final String baseObservations;
        private final BigDecimal shippingCost;
        private final List<OrderMetaPackingItem> packingItems;

        private OrderMeta(String baseObservations, BigDecimal shippingCost, List<OrderMetaPackingItem> packingItems) {
            this.baseObservations = baseObservations;
            this.shippingCost = shippingCost;
            this.packingItems = packingItems;
        }
    }

    private static class OrderMetaPackingItem {
        private final Long materialId;
        private final BigDecimal quantity;
        private final BigDecimal unitPrice;

        private OrderMetaPackingItem(Long materialId, BigDecimal quantity, BigDecimal unitPrice) {
            this.materialId = materialId;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }
    }

    private boolean isValidOrderType(String orderType) {
        return orderType != null && 
                (orderType.equals("CINCHOS") || orderType.equals("MARCAS") || orderType.equals("OPV") || orderType.equals("NORMAL") || orderType.equals("DISTRIBUTION") || orderType.equals("VENTA_EN_LINEA"));
    }

    private String normalizeOrderType(String orderType, String sellerName) {
        String normalizedType = String.valueOf(orderType == null ? "" : orderType).trim().toUpperCase();
        String normalizedSeller = String.valueOf(sellerName == null ? "" : sellerName).trim().toUpperCase();
        if (normalizedSeller.contains("LUIS FELIPE")) {
            return "MARCAS";
        }
        return normalizedType;
    }

    private String convertSizesToJson(Map<String, Integer> sizes) {
        try {
            return objectMapper.writeValueAsString(sizes);
        } catch (Exception e) {
            return null;
        }
    }

    private void createReprocessTask(
            ProductionOrderEntity po,
            ProductionOrderItemEntity item,
            ProductEntity product,
            int rejectedQty,
            String rejectionReason) throws BusinessException {
        String taskCode = generateTaskCode();
        String colorName = null;
        if (item.getColorId() != null) {
            colorName = colorRepository.findById(item.getColorId()).map(ColorEntity::getName).orElse(null);
        }
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
                .leatherDeliveredAt(null)
                .materialsDelivered(!requiresMaterials)
                .materialsDeliveredAt(!requiresMaterials ? java.time.LocalDateTime.now() : null)
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
}

