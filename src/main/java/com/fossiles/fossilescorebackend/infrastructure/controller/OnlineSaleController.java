package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.OnlineSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.OnlineSaleExchangeRequest;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineSaleDailySummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineSaleReturnPrintResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineSaleResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.OnlineSaleProductionOrderService;
import com.fossiles.fossilescorebackend.application.service.OnlineSaleService;
import com.fossiles.fossilescorebackend.application.service.ProductionTaskGenerationService;
import com.fossiles.fossilescorebackend.application.service.SmartMaterialRequestService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ReturnInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/online-sales")
@RequiredArgsConstructor
public class OnlineSaleController {

    private final OnlineSaleService saleService;
    private final OnlineSaleProductionOrderService onlineSaleProductionOrderService;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final SmartMaterialRequestService smartMaterialRequestService;
    private final ProductionTaskGenerationService productionTaskGenerationService;

    // ─── CRUD ───────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<OnlineSaleResponse> create(@RequestBody OnlineSaleRequest request) {
        return ResponseEntity.ok(saleService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<OnlineSaleResponse>> getAll() {
        return ResponseEntity.ok(saleService.getAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OnlineSaleResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        return ResponseEntity.ok(saleService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OnlineSaleResponse> update(@PathVariable Long id,
                                                     @RequestBody OnlineSaleRequest request) throws ResourceNotFoundException {
        return ResponseEntity.ok(saleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        saleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ─── Filtros ────────────────────────────────────────────────────

    @GetMapping("/by-date")
    public ResponseEntity<List<OnlineSaleResponse>> getByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(saleService.getByDate(date));
    }

    @GetMapping("/by-salesperson")
    public ResponseEntity<List<OnlineSaleResponse>> getBySalesperson(
            @RequestParam String salesperson) {
        return ResponseEntity.ok(saleService.getBySalesperson(salesperson));
    }

    @GetMapping("/by-date-and-salesperson")
    public ResponseEntity<List<OnlineSaleResponse>> getByDateAndSalesperson(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String salesperson) {
        return ResponseEntity.ok(saleService.getByDateAndSalesperson(date, salesperson));
    }

    @GetMapping("/eligible-for-production")
    public ResponseEntity<List<OnlineSaleResponse>> getEligibleForProduction(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(saleService.getEligibleForProduction(startDate, endDate));
    }

    @GetMapping("/by-date-range")
    public ResponseEntity<List<OnlineSaleResponse>> getByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(saleService.getByDateRange(startDate, endDate));
    }

    // ─── Importación masiva CSV ──────────────────────────────────────

    @PostMapping("/import")
    public ResponseEntity<?> importSales(@RequestBody List<OnlineSaleRequest> requests) {
        return ResponseEntity.ok(saleService.importBatch(requests));
    }

    // ─── Resumen Diario ─────────────────────────────────────────────

    @GetMapping("/daily-summary")
    public ResponseEntity<OnlineSaleDailySummaryResponse> getDailySummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate)
            throws BusinessException {
        if (startDate != null || endDate != null) {
            LocalDate from = startDate != null ? startDate : endDate;
            LocalDate to = endDate != null ? endDate : startDate;
            return ResponseEntity.ok(saleService.getSummaryForDateRange(from, to));
        }
        LocalDate target = date != null ? date : java.time.LocalDate.now();
        return ResponseEntity.ok(saleService.getDailySummary(target));
    }

    // ─── Devolver / Anular ──────────────────────────────────────────

    @PutMapping("/{id}/return")
    public ResponseEntity<OnlineSaleResponse> markAsReturn(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) throws ResourceNotFoundException, BusinessException {
        String reason = body.get("reason");
        String condition = body.get("itemCondition");
        return ResponseEntity.ok(saleService.markAsReturn(id, reason, condition));
    }

    @PutMapping("/{id}/void")
    public ResponseEntity<OnlineSaleResponse> markAsVoid(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) throws ResourceNotFoundException, BusinessException {
        String reason = body.get("reason");
        return ResponseEntity.ok(saleService.markAsVoid(id, reason));
    }

    @PutMapping("/{id}/shipment")
    public ResponseEntity<OnlineSaleResponse> registerShipment(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) throws ResourceNotFoundException, BusinessException {
        String guideNumber = body != null ? body.get("guideNumber") : null;
        String shippingCarrier = body != null ? body.get("shippingCarrier") : null;
        String observations = body != null ? body.get("observations") : null;
        return ResponseEntity.ok(saleService.registerShipment(id, guideNumber, shippingCarrier, observations));
    }

    @PostMapping("/{id}/exchange")
    public ResponseEntity<OnlineSaleResponse> createExchange(
            @PathVariable Long id,
            @RequestBody OnlineSaleExchangeRequest request) throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(saleService.createExchange(id, request));
    }

    @GetMapping("/returns")
    public ResponseEntity<List<ReturnInventoryEntity>> getReturnInventory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(saleService.getReturnInventory(startDate, endDate));
    }

    @GetMapping("/return-events")
    public ResponseEntity<List<Map<String, Object>>> getReturnEvents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        // Respuesta ligera para listado (evita exponer entidad completa)
        var events = saleService.getReturnEvents(startDate, endDate);
        var rows = events.stream().map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("onlineSaleId", e.getOnlineSaleId());
            row.put("relatedShipmentNumber", e.getRelatedShipmentNumber());
            row.put("returnReason", e.getReturnReason());
            row.put("itemCondition", e.getItemCondition());
            row.put("createdAt", e.getCreatedAt());
            row.put("createdBy", e.getCreatedBy());
            return row;
        }).sorted(Comparator.comparing(r -> (Long) r.get("id"), Comparator.reverseOrder())).toList();
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/returns/{returnId}")
    public ResponseEntity<OnlineSaleReturnPrintResponse> getReturnForPrint(@PathVariable Long returnId)
            throws ResourceNotFoundException {
        return ResponseEntity.ok(saleService.getReturnForPrint(returnId));
    }

    // ─── Nuevo flujo: procesar ventas revisando inventario BODEGA_PT primero ────

    @PostMapping("/fulfillment-preview")
    public ResponseEntity<Map<String, Object>> previewFulfillment(@RequestBody Map<String, Object> request)
            throws BusinessException {
        @SuppressWarnings("unchecked")
        List<Integer> saleIdInts = (List<Integer>) request.get("saleIds");
        if (saleIdInts == null || saleIdInts.isEmpty()) {
            throw new BusinessException("Debe seleccionar al menos una venta");
        }
        List<Long> saleIds = saleIdInts.stream().map(Integer::longValue).toList();
        return ResponseEntity.ok(onlineSaleProductionOrderService.previewFulfillment(saleIds));
    }

    @GetMapping("/{id}/items-preview")
    public ResponseEntity<OnlineSaleProductionOrderService.SaleItemsPreview> getItemsPreview(@PathVariable Long id)
            throws BusinessException {
        return ResponseEntity.ok(onlineSaleProductionOrderService.previewSaleItems(id));
    }

    @PostMapping("/{id}/resolve-mixed")
    public ResponseEntity<Map<String, Object>> resolveMixedSale(@PathVariable Long id,
                                                                @RequestBody Map<String, Object> request)
            throws BusinessException {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems = (List<Map<String, Object>>) request.get("items");
        if (rawItems == null || rawItems.isEmpty()) {
            throw new BusinessException("Debe enviar la lista de items");
        }
        List<OnlineSaleProductionOrderService.ItemResolution> resolutions = rawItems.stream()
                .map(it -> {
                    Object idObj = it.get("saleItemId");
                    Long itemId = idObj instanceof Number ? ((Number) idObj).longValue() : null;
                    String action = it.get("action") != null ? it.get("action").toString() : null;
                    return new OnlineSaleProductionOrderService.ItemResolution(itemId, action);
                })
                .toList();

        OnlineSaleProductionOrderService.ResolveMixedResult result =
                onlineSaleProductionOrderService.resolveMixedSale(id, resolutions);

        if (result.productionOrderId() != null) {
            try {
                List<ProductionOrderItemEntity> opItems = productionOrderItemRepository.findByProductionOrderId(result.productionOrderId());
                for (ProductionOrderItemEntity item : opItems) {
                    if (item.getProductId() != null) {
                        int totalQuantity = item.getQuantity() != null ? item.getQuantity() : 0;
                        if (totalQuantity > 0) {
                            smartMaterialRequestService.checkAndGenerateRequestsForProductionOrder(
                                    result.productionOrderId(), item.getProductId(),
                                    BigDecimal.valueOf(totalQuantity));
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error al generar materiales para {}: {}", result.productionOrderCode(), e.getMessage());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalSaleId", result.originalSaleId());
        body.put("originalSaleNumber", result.originalSaleNumber());
        body.put("childSaleId", result.childSaleId());
        body.put("childSaleNumber", result.childSaleNumber());
        body.put("productionOrderId", result.productionOrderId());
        body.put("productionOrderCode", result.productionOrderCode());
        body.put("dispatchedItems", result.dispatchedItems());
        body.put("producedItems", result.producedItems());
        body.put("message", result.message());
        body.put("kioskOutflows", result.kioskOutflows() != null
                ? result.kioskOutflows().stream().map(k -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticketNumber", k.ticketNumber());
            row.put("materialId", k.materialId());
            row.put("materialName", k.materialName());
            row.put("kioskLocationId", k.kioskLocationId());
            row.put("kioskName", k.kioskName());
            row.put("quantity", k.quantity());
            row.put("saleNumber", k.saleNumber());
            row.put("onlineSaleId", k.onlineSaleId());
            return row;
        }).toList()
                : List.of());
        body.put("tasksGenerated", false);
        body.put("nextStep", "Genera tareas desde Centro (Tareas por mesa) si hay OP creada.");
        return ResponseEntity.ok(body);
    }

    /**
     * Nuevo flujo correcto:
     * 1. Bodega PT revisa inventario para cada venta seleccionada.
     * 2. Las ventas con stock disponible se despachan directo (no van a producción).
     * 3. Solo las ventas sin stock generan órdenes de producción.
     *
     * Retorna un desglose completo de qué fue despachado vs qué fue a producción.
     */
    @PostMapping("/process-fulfillment")
    public ResponseEntity<Map<String, Object>> processFulfillment(@RequestBody Map<String, Object> request)
            throws BusinessException {
        @SuppressWarnings("unchecked")
        List<Integer> saleIdInts = (List<Integer>) request.get("saleIds");
        if (saleIdInts == null || saleIdInts.isEmpty()) {
            throw new BusinessException("Debe seleccionar al menos una venta");
        }

        List<Long> saleIds = saleIdInts.stream().map(Integer::longValue).toList();
        OnlineSaleProductionOrderService.FulfillmentResult fulfillment =
                onlineSaleProductionOrderService.processWithInventoryCheck(saleIds);

        // Generar materiales para las OPs creadas (tareas se generan aparte desde Centro / plan del dia)
        // Regla: crear OP no debe asignar mesas automáticamente.
        for (OnlineSaleProductionOrderService.CreateResult created : fulfillment.productionOrdersCreated()) {
            try {
                List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(created.productionOrderId());
                for (ProductionOrderItemEntity item : items) {
                    if (item.getProductId() != null) {
                        int totalQuantity = item.getQuantity() != null ? item.getQuantity() : 0;
                        if (totalQuantity > 0) {
                            smartMaterialRequestService.checkAndGenerateRequestsForProductionOrder(
                                    created.productionOrderId(), item.getProductId(),
                                    BigDecimal.valueOf(totalQuantity));
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error al generar materiales para {}: {}", created.productionOrderCode(), e.getMessage());
            }
        }

        // Construir respuesta detallada
        List<Map<String, Object>> fulfilledRows = fulfillment.fulfilledFromInventory().stream().map(f -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("saleNumber", f.saleNumber());
            row.put("customerName", f.customerName());
            row.put("shipmentNumber", f.shipmentNumber());
            row.put("message", f.message());
            return row;
        }).toList();

        List<Map<String, Object>> productionRows = fulfillment.productionOrdersCreated().stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productionOrderCode", p.productionOrderCode());
            row.put("customerName", p.customerName());
            row.put("salesCount", p.salesCount());
            return row;
        }).toList();

        // Mensaje resumen
        StringBuilder msg = new StringBuilder();
        if (fulfillment.fulfilledCount() > 0) {
            msg.append(fulfillment.fulfilledCount()).append(" venta(s) lista(s) para despacho desde inventario. ");
        }
        if (fulfillment.productionCount() > 0) {
            List<String> codes = fulfillment.productionOrdersCreated().stream()
                    .map(OnlineSaleProductionOrderService.CreateResult::productionOrderCode).toList();
            msg.append(fulfillment.productionCount()).append(" OP(s) creada(s): ").append(String.join(", ", codes)).append(". ");
        }
        if (!fulfillment.bodegaPtFound()) {
            msg.append("⚠ No se encontró BODEGA_PT ni Bodega Devoluciones — todas las ventas fueron a producción.");
        }
        if (fulfillment.fulfilledCount() == 0 && fulfillment.productionCount() == 0) {
            msg.append("No se procesó ninguna venta.");
        }

        List<Map<String, Object>> kioskOutflowRows = fulfillment.kioskOutflows().stream().map(k -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticketNumber", k.ticketNumber());
            row.put("materialId", k.materialId());
            row.put("materialName", k.materialName());
            row.put("kioskLocationId", k.kioskLocationId());
            row.put("kioskName", k.kioskName());
            row.put("quantity", k.quantity());
            row.put("saleNumber", k.saleNumber());
            row.put("onlineSaleId", k.onlineSaleId());
            return row;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", msg.toString().trim());
        body.put("fulfilledFromInventory", fulfilledRows);
        body.put("productionOrdersCreated", productionRows);
        body.put("fulfilledCount", fulfillment.fulfilledCount());
        body.put("productionCount", fulfillment.productionCount());
        body.put("bodegaPtFound", fulfillment.bodegaPtFound());
        body.put("kioskOutflows", kioskOutflowRows);
        body.put("tasksGenerated", false);
        body.put("nextStep", "Genera tareas desde Centro (Tareas por mesa) para asignar mesas segun el plan del dia.");
        return ResponseEntity.ok(body);
    }

    // ─── Crear Orden de Producción desde ventas seleccionadas (flujo legado) ────

    @PostMapping("/create-production-order")
    public ResponseEntity<Map<String, Object>> createProductionOrderFromSales(@RequestBody Map<String, Object> request)
            throws BusinessException {
        @SuppressWarnings("unchecked")
        List<Integer> saleIdInts = (List<Integer>) request.get("saleIds");
        if (saleIdInts == null || saleIdInts.isEmpty()) {
            throw new BusinessException("Debe seleccionar al menos una venta");
        }

        List<Long> saleIds = saleIdInts.stream().map(Integer::longValue).toList();
        OnlineSaleProductionOrderService.OplCreationResult oplBatch =
                onlineSaleProductionOrderService.createOplFromSales(saleIds);
        List<OnlineSaleProductionOrderService.CreateResult> createdList = oplBatch.productionOrders();

        for (OnlineSaleProductionOrderService.CreateResult created : createdList) {
            try {
                List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(created.productionOrderId());
                for (ProductionOrderItemEntity item : items) {
                    if (item.getProductId() != null) {
                        int totalQuantity = item.getQuantity() != null ? item.getQuantity() : 0;
                        if (item.getSizesData() != null && !item.getSizesData().isEmpty()) {
                            try {
                                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                Map<String, Integer> sizes = mapper.readValue(item.getSizesData(),
                                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Integer>>() {});
                                int fromSizes = sizes.values().stream()
                                        .mapToInt(v -> v != null ? Math.max(v, 0) : 0)
                                        .sum();
                                if (fromSizes > 0) {
                                    totalQuantity = fromSizes;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        if (totalQuantity > 0) {
                            smartMaterialRequestService.checkAndGenerateRequestsForProductionOrder(
                                    created.productionOrderId(),
                                    item.getProductId(),
                                    BigDecimal.valueOf(totalQuantity));
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error al generar solicitudes de materiales para {}: {}", created.productionOrderCode(), e.getMessage());
            }
        }

        List<String> codes = createdList.stream().map(OnlineSaleProductionOrderService.CreateResult::productionOrderCode).toList();
        int totalSales = createdList.stream().mapToInt(OnlineSaleProductionOrderService.CreateResult::salesCount).sum();

        List<Map<String, Object>> kioskRows = oplBatch.kioskOutflows().stream().map(k -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticketNumber", k.ticketNumber());
            row.put("materialId", k.materialId());
            row.put("materialName", k.materialName());
            row.put("kioskLocationId", k.kioskLocationId());
            row.put("kioskName", k.kioskName());
            row.put("quantity", k.quantity());
            row.put("saleNumber", k.saleNumber());
            row.put("onlineSaleId", k.onlineSaleId());
            return row;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", createdList.size() == 1
                ? "Orden de producción " + codes.get(0) + " creada exitosamente"
                : createdList.size() + " órdenes de producción creadas: " + String.join(", ", codes));
        body.put("ordersCreated", createdList.size());
        body.put("productionOrderCodes", codes);
        body.put("salesCount", totalSales);
        body.put("kioskOutflows", kioskRows);
        body.put("tasksGenerated", false);
        body.put("nextStep", "Genera tareas desde Centro (Tareas por mesa) para asignar mesas segun el plan del dia.");
        return ResponseEntity.ok(body);
    }
}
