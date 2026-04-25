package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.OnlineSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineSaleDailySummaryResponse;
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
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(saleService.getDailySummary(date));
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

    @GetMapping("/returns")
    public ResponseEntity<List<ReturnInventoryEntity>> getReturnInventory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(saleService.getReturnInventory(startDate, endDate));
    }

    // ─── Nuevo flujo: procesar ventas revisando inventario BODEGA_PT primero ────

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

        // Generar tareas y materiales para las OPs creadas
        List<String> tasksErrors = new ArrayList<>();
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
            try {
                productionTaskGenerationService.generateTasks(created.productionOrderId(), false);
            } catch (Exception e) {
                tasksErrors.add(created.productionOrderCode() + ": " + e.getMessage());
                log.error("Error al generar tareas para {}: {}", created.productionOrderCode(), e.getMessage(), e);
            }
        }

        // Construir respuesta detallada
        List<Map<String, Object>> fulfilledRows = fulfillment.fulfilledFromInventory().stream().map(f -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("saleNumber", f.saleNumber());
            row.put("customerName", f.customerName());
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
            msg.append(fulfillment.fulfilledCount()).append(" venta(s) despachada(s) desde inventario. ");
        }
        if (fulfillment.productionCount() > 0) {
            List<String> codes = fulfillment.productionOrdersCreated().stream()
                    .map(OnlineSaleProductionOrderService.CreateResult::productionOrderCode).toList();
            msg.append(fulfillment.productionCount()).append(" OP(s) creada(s): ").append(String.join(", ", codes)).append(". ");
        }
        if (!fulfillment.bodegaPtFound()) {
            msg.append("⚠ No se encontró BODEGA_PT — todas las ventas fueron a producción.");
        }
        if (fulfillment.fulfilledCount() == 0 && fulfillment.productionCount() == 0) {
            msg.append("No se procesó ninguna venta.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", msg.toString().trim());
        body.put("fulfilledFromInventory", fulfilledRows);
        body.put("productionOrdersCreated", productionRows);
        body.put("fulfilledCount", fulfillment.fulfilledCount());
        body.put("productionCount", fulfillment.productionCount());
        body.put("bodegaPtFound", fulfillment.bodegaPtFound());
        body.put("tasksGenerated", tasksErrors.isEmpty());
        if (!tasksErrors.isEmpty()) {
            body.put("tasksError", String.join("; ", tasksErrors));
        }
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
        List<OnlineSaleProductionOrderService.CreateResult> createdList = onlineSaleProductionOrderService.createFromSaleIds(saleIds);

        List<String> tasksErrors = new ArrayList<>();
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
                                totalQuantity += sizes.values().stream().mapToInt(Integer::intValue).sum();
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

            try {
                productionTaskGenerationService.generateTasks(created.productionOrderId(), false);
            } catch (Exception e) {
                tasksErrors.add(created.productionOrderCode() + ": " + e.getMessage());
                log.error("Error al generar tareas para {}: {}", created.productionOrderCode(), e.getMessage(), e);
            }
        }

        List<String> codes = createdList.stream().map(OnlineSaleProductionOrderService.CreateResult::productionOrderCode).toList();
        int totalSales = createdList.stream().mapToInt(OnlineSaleProductionOrderService.CreateResult::salesCount).sum();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", createdList.size() == 1
                ? "Orden de producción " + codes.get(0) + " creada exitosamente"
                : createdList.size() + " órdenes de producción creadas: " + String.join(", ", codes));
        body.put("ordersCreated", createdList.size());
        body.put("productionOrderCodes", codes);
        body.put("salesCount", totalSales);
        body.put("tasksGenerated", tasksErrors.isEmpty());
        if (!tasksErrors.isEmpty()) {
            body.put("tasksError", String.join("; ", tasksErrors));
        }
        return ResponseEntity.ok(body);
    }
}
