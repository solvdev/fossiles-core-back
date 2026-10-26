package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ProductInventoryLocationRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductInventoryUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CriticalProductInventoryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryKardexResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryLocationResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryOutflowReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductKardexHistoricoResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.ProductInventoryOutflowReportService;
import com.fossiles.fossilescorebackend.application.service.ProductInventoryService;
import com.fossiles.fossilescorebackend.application.service.ProductKardexHistoricoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/product-inventory")
@RequiredArgsConstructor
public class ProductInventoryController {

    private final ProductInventoryService productInventoryService;
    private final ProductInventoryOutflowReportService outflowReportService;
    private final ProductKardexHistoricoService kardexHistoricoService;

    // ========== PRODUCT INVENTORY LOCATION ==========

    @GetMapping
    public ResponseEntity<List<ProductInventoryLocationResponse>> getAllInventory() {
        List<ProductInventoryLocationResponse> inventory = productInventoryService.getAllInventory();
        return ResponseEntity.ok(inventory);
    }

    @GetMapping("/product/{productId}")
    public ResponseEntity<List<ProductInventoryLocationResponse>> getInventoryByProduct(@PathVariable Long productId) {
        List<ProductInventoryLocationResponse> inventory = productInventoryService.getInventoryByProduct(productId);
        return ResponseEntity.ok(inventory);
    }

    @GetMapping("/location/{locationId}")
    public ResponseEntity<List<ProductInventoryLocationResponse>> getInventoryByLocation(@PathVariable Long locationId) {
        List<ProductInventoryLocationResponse> inventory = productInventoryService.getInventoryByLocation(locationId);
        return ResponseEntity.ok(inventory);
    }

    @GetMapping("/location/{locationId}/variants")
    public ResponseEntity<List<ProductInventoryLocationResponse>> getInventoryByLocationVariants(@PathVariable Long locationId) {
        List<ProductInventoryLocationResponse> inventory = productInventoryService.getInventoryByLocationVariants(locationId);
        return ResponseEntity.ok(inventory);
    }

    @GetMapping("/product/{productId}/location/{locationId}")
    public ResponseEntity<ProductInventoryLocationResponse> getInventoryByProductAndLocation(
            @PathVariable Long productId,
            @PathVariable Long locationId,
            @RequestParam(required = false) Long colorId) throws ResourceNotFoundException {
        // SIEMPRE usar el método que considera colorId (incluso si es null)
        // Esto evita errores cuando hay múltiples registros con diferentes colores
        ProductInventoryLocationResponse inventory = productInventoryService.getInventoryByProductAndLocationAndColor(
            productId, locationId, colorId);
        return ResponseEntity.ok(inventory);
    }

    @PostMapping
    public ResponseEntity<ProductInventoryLocationResponse> createOrUpdateInventory(
            @Valid @RequestBody ProductInventoryLocationRequest request) throws ResourceNotFoundException, BusinessException {
        ProductInventoryLocationResponse response = productInventoryService.createOrUpdateInventory(request);
        return ResponseEntity.ok(response);
    }

    @PutMapping
    public ResponseEntity<ProductInventoryLocationResponse> updateInventory(
            @Valid @RequestBody ProductInventoryUpdateRequest request)
            throws ResourceNotFoundException, BusinessException {
        ProductInventoryLocationResponse response = productInventoryService.updateInventory(request);
        return ResponseEntity.ok(response);
    }

    // ========== KARDEX ==========

    @GetMapping("/kardex/product/{productId}")
    public ResponseEntity<List<ProductInventoryKardexResponse>> getKardexByProduct(@PathVariable Long productId) {
        List<ProductInventoryKardexResponse> kardex = productInventoryService.getKardexByProduct(productId);
        return ResponseEntity.ok(kardex);
    }

    @GetMapping("/kardex/location/{locationId}")
    public ResponseEntity<List<ProductInventoryKardexResponse>> getKardexByLocation(@PathVariable Long locationId) {
        List<ProductInventoryKardexResponse> kardex = productInventoryService.getKardexByLocation(locationId);
        return ResponseEntity.ok(kardex);
    }

    @GetMapping("/kardex/product/{productId}/location/{locationId}")
    public ResponseEntity<List<ProductInventoryKardexResponse>> getKardexByProductAndLocation(
            @PathVariable Long productId,
            @PathVariable Long locationId) {
        List<ProductInventoryKardexResponse> kardex = productInventoryService.getKardexByProductAndLocation(productId, locationId);
        return ResponseEntity.ok(kardex);
    }

    @GetMapping("/kardex/movement-type/{movementType}")
    public ResponseEntity<List<ProductInventoryKardexResponse>> getKardexByMovementType(@PathVariable String movementType) {
        List<ProductInventoryKardexResponse> kardex = productInventoryService.getKardexByMovementType(movementType);
        return ResponseEntity.ok(kardex);
    }

    @GetMapping("/kardex/reference/{referenceType}/{referenceId}")
    public ResponseEntity<List<ProductInventoryKardexResponse>> getKardexByReference(
            @PathVariable String referenceType,
            @PathVariable Long referenceId) {
        List<ProductInventoryKardexResponse> kardex = productInventoryService.getKardexByReference(referenceType, referenceId);
        return ResponseEntity.ok(kardex);
    }

    // ========== KARDEX HISTÓRICO (archivado por limpiezas, solo consulta) ==========

    @GetMapping("/kardex-historico")
    public ResponseEntity<ProductKardexHistoricoResponse.Page> searchKardexHistorico(
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String movementType,
            @RequestParam(required = false) String archiveReference,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String reference,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(kardexHistoricoService.search(
                locationId, productId, movementType, archiveReference, from, to, reference, page, size));
    }

    @GetMapping("/kardex-historico/filters")
    public ResponseEntity<ProductKardexHistoricoResponse.Filters> kardexHistoricoFilters() {
        return ResponseEntity.ok(kardexHistoricoService.filters());
    }

    @GetMapping("/kardex/outflows-report")
    public ResponseEntity<ProductInventoryOutflowReportResponse> getOutflowsReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) List<String> sourceCategories,
            @RequestParam(required = false) List<String> orderTypes) throws BusinessException {
        ProductInventoryOutflowReportResponse report = outflowReportService.buildReport(
                startDate, endDate, locationId, productId, sourceCategories, orderTypes);
        return ResponseEntity.ok(report);
    }

    // ========== CRITICAL INVENTORY ==========

    @GetMapping("/critical")
    public ResponseEntity<List<CriticalProductInventoryResponse>> getCriticalInventory(
            @RequestParam(required = false) Long locationId) {
        List<CriticalProductInventoryResponse> critical = productInventoryService.getCriticalInventory(locationId);
        return ResponseEntity.ok(critical);
    }

    // ========== CATEGORY INVENTORY ==========

    @GetMapping("/category/{category}")
    public ResponseEntity<List<ProductInventoryLocationResponse>> getInventoryByCategory(@PathVariable String category) {
        List<ProductInventoryLocationResponse> inventory = productInventoryService.getInventoryByCategory(category);
        return ResponseEntity.ok(inventory);
    }

    @GetMapping("/category/{category}/aggregated")
    public ResponseEntity<List<ProductInventoryLocationResponse>> getAggregatedInventoryByCategory(@PathVariable String category) {
        List<ProductInventoryLocationResponse> inventory = productInventoryService.getAggregatedInventoryByCategory(category);
        return ResponseEntity.ok(inventory);
    }

    // ========== INITIALIZE INVENTORY ==========

    @PostMapping("/initialize")
    public ResponseEntity<java.util.Map<String, Object>> initializeMissingInventory(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long locationId) throws ResourceNotFoundException {
        int createdCount = productInventoryService.initializeMissingInventory(category, locationId);
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("message", "Inventario de productos inicializado correctamente");
        response.put("createdCount", createdCount);
        response.put("category", category);
        response.put("locationId", locationId);
        return ResponseEntity.ok(response);
    }
}

