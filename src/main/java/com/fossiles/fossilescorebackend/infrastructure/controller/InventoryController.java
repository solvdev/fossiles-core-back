package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.InventoryLocationRequest;
import com.fossiles.fossilescorebackend.application.dto.request.InventoryUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.InventoryTransferRequest;
import com.fossiles.fossilescorebackend.application.dto.request.BulkInventoryTransferRequest;
import com.fossiles.fossilescorebackend.application.dto.request.InventoryAdjustmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CriticalInventoryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryKardexResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryLocationResponse;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialInventoryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialInventoryKardexResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryTransferResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryAdjustmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    // ========== INVENTORY LOCATION ==========

    /**
     * Obtiene el inventario agregado de materiales (sin ubicación)
     * Devuelve el stock total de cada material sumando todas las ubicaciones
     */
    @GetMapping("/materials/aggregated")
    public ResponseEntity<List<MaterialInventoryResponse>> getAggregatedMaterialInventory() {
        List<MaterialInventoryResponse> inventory = inventoryService.getAggregatedMaterialInventory();
        return ResponseEntity.ok(inventory);
    }

    @GetMapping
    public ResponseEntity<List<InventoryLocationResponse>> getAllInventory() {
        List<InventoryLocationResponse> inventory = inventoryService.getAllInventory();
        return ResponseEntity.ok(inventory);
    }

    @GetMapping("/material/{materialId}")
    public ResponseEntity<List<InventoryLocationResponse>> getInventoryByMaterial(@PathVariable Long materialId) {
        List<InventoryLocationResponse> inventory = inventoryService.getInventoryByMaterial(materialId);
        return ResponseEntity.ok(inventory);
    }

    @GetMapping("/location/{locationId}")
    public ResponseEntity<List<InventoryLocationResponse>> getInventoryByLocation(@PathVariable Long locationId) {
        List<InventoryLocationResponse> inventory = inventoryService.getInventoryByLocation(locationId);
        return ResponseEntity.ok(inventory);
    }

    @GetMapping("/material/{materialId}/location/{locationId}")
    public ResponseEntity<InventoryLocationResponse> getInventoryByMaterialAndLocation(
            @PathVariable Long materialId,
            @PathVariable Long locationId) throws ResourceNotFoundException {
        InventoryLocationResponse inventory = inventoryService.getInventoryByMaterialAndLocation(materialId, locationId);
        return ResponseEntity.ok(inventory);
    }

    @PostMapping
    public ResponseEntity<InventoryLocationResponse> createOrUpdateInventory(
            @Valid @RequestBody InventoryLocationRequest request) throws ResourceNotFoundException {
        InventoryLocationResponse response = inventoryService.createOrUpdateInventory(request);
        return ResponseEntity.ok(response);
    }

    @PutMapping
    public ResponseEntity<InventoryLocationResponse> updateInventory(
            @Valid @RequestBody InventoryUpdateRequest request) throws ResourceNotFoundException {
        InventoryLocationResponse response = inventoryService.updateInventory(request);
        return ResponseEntity.ok(response);
    }

    // ========== KARDEX ==========

    @GetMapping("/kardex/material/{materialId}")
    public ResponseEntity<List<InventoryKardexResponse>> getKardexByMaterial(@PathVariable Long materialId) {
        List<InventoryKardexResponse> kardex = inventoryService.getKardexByMaterial(materialId);
        return ResponseEntity.ok(kardex);
    }

    @GetMapping("/kardex/location/{locationId}")
    public ResponseEntity<List<InventoryKardexResponse>> getKardexByLocation(@PathVariable Long locationId) {
        List<InventoryKardexResponse> kardex = inventoryService.getKardexByLocation(locationId);
        return ResponseEntity.ok(kardex);
    }

    @GetMapping("/kardex/material/{materialId}/location/{locationId}")
    public ResponseEntity<List<InventoryKardexResponse>> getKardexByMaterialAndLocation(
            @PathVariable Long materialId,
            @PathVariable Long locationId) {
        List<InventoryKardexResponse> kardex = inventoryService.getKardexByMaterialAndLocation(materialId, locationId);
        return ResponseEntity.ok(kardex);
    }

    @GetMapping("/kardex/movement-type/{movementType}")
    public ResponseEntity<List<InventoryKardexResponse>> getKardexByMovementType(@PathVariable String movementType) {
        List<InventoryKardexResponse> kardex = inventoryService.getKardexByMovementType(movementType);
        return ResponseEntity.ok(kardex);
    }

    @GetMapping("/kardex/reference/{referenceType}/{referenceId}")
    public ResponseEntity<List<InventoryKardexResponse>> getKardexByReference(
            @PathVariable String referenceType,
            @PathVariable Long referenceId) {
        List<InventoryKardexResponse> kardex = inventoryService.getKardexByReference(referenceType, referenceId);
        return ResponseEntity.ok(kardex);
    }

    // ========== CRITICAL INVENTORY ==========

    @GetMapping("/critical")
    public ResponseEntity<List<CriticalInventoryResponse>> getCriticalInventory(
            @RequestParam(required = false) Long locationId) {
        List<CriticalInventoryResponse> critical = inventoryService.getCriticalInventory(locationId);
        return ResponseEntity.ok(critical);
    }

    // ========== CATEGORY INVENTORY ==========

    @GetMapping("/category/{category}")
    public ResponseEntity<List<InventoryLocationResponse>> getInventoryByCategory(@PathVariable String category) {
        List<InventoryLocationResponse> inventory = inventoryService.getInventoryByCategory(category);
        return ResponseEntity.ok(inventory);
    }

    @GetMapping("/category/{category}/aggregated")
    public ResponseEntity<List<InventoryLocationResponse>> getAggregatedInventoryByCategory(@PathVariable String category) {
        List<InventoryLocationResponse> inventory = inventoryService.getAggregatedInventoryByCategory(category);
        return ResponseEntity.ok(inventory);
    }

    // ========== MATERIAL INVENTORY (SIN UBICACIÓN) ==========

    @GetMapping("/materials/{materialId}")
    public ResponseEntity<MaterialInventoryResponse> getMaterialInventory(@PathVariable Long materialId) 
            throws ResourceNotFoundException {
        MaterialInventoryResponse inventory = inventoryService.getMaterialInventory(materialId);
        return ResponseEntity.ok(inventory);
    }

    @PostMapping("/materials/{materialId}")
    public ResponseEntity<MaterialInventoryResponse> createOrUpdateMaterialInventory(
            @PathVariable Long materialId,
            @RequestParam BigDecimal quantity) throws ResourceNotFoundException {
        MaterialInventoryResponse response = inventoryService.createOrUpdateMaterialInventory(materialId, quantity);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/materials/{materialId}/increment")
    public ResponseEntity<MaterialInventoryResponse> incrementMaterialInventory(
            @PathVariable Long materialId,
            @RequestParam BigDecimal quantity,
            @RequestParam(required = false) BigDecimal unitCost,
            @RequestParam(required = false) String referenceType,
            @RequestParam(required = false) Long referenceId,
            @RequestParam(required = false) String referenceNumber,
            @RequestParam(required = false) String description) throws ResourceNotFoundException {
        MaterialInventoryResponse response = inventoryService.incrementMaterialInventory(
                materialId, quantity, unitCost, referenceType, referenceId, referenceNumber, description);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/materials/{materialId}/decrement")
    public ResponseEntity<MaterialInventoryResponse> decrementMaterialInventory(
            @PathVariable Long materialId,
            @RequestParam BigDecimal quantity,
            @RequestParam(required = false) BigDecimal unitCost,
            @RequestParam(required = false) String referenceType,
            @RequestParam(required = false) Long referenceId,
            @RequestParam(required = false) String referenceNumber,
            @RequestParam(required = false) String description) 
            throws ResourceNotFoundException, BusinessException {
        MaterialInventoryResponse response = inventoryService.decrementMaterialInventory(
                materialId, quantity, unitCost, referenceType, referenceId, referenceNumber, description);
        return ResponseEntity.ok(response);
    }

    // ========== MATERIAL KARDEX (SIN UBICACIÓN) ==========

    @GetMapping("/materials/{materialId}/kardex")
    public ResponseEntity<List<MaterialInventoryKardexResponse>> getMaterialKardex(@PathVariable Long materialId) {
        List<MaterialInventoryKardexResponse> kardex = inventoryService.getMaterialKardex(materialId);
        return ResponseEntity.ok(kardex);
    }

    @GetMapping("/materials/kardex/movement-type/{movementType}")
    public ResponseEntity<List<MaterialInventoryKardexResponse>> getMaterialKardexByMovementType(
            @PathVariable String movementType) {
        List<MaterialInventoryKardexResponse> kardex = inventoryService.getMaterialKardexByMovementType(movementType);
        return ResponseEntity.ok(kardex);
    }

    @GetMapping("/materials/kardex/reference/{referenceType}/{referenceId}")
    public ResponseEntity<List<MaterialInventoryKardexResponse>> getMaterialKardexByReference(
            @PathVariable String referenceType,
            @PathVariable Long referenceId) {
        List<MaterialInventoryKardexResponse> kardex = inventoryService.getMaterialKardexByReference(referenceType, referenceId);
        return ResponseEntity.ok(kardex);
    }

    // ========== INITIALIZE INVENTORY ==========

    @PostMapping("/initialize")
    public ResponseEntity<java.util.Map<String, Object>> initializeMissingInventory(
            @RequestParam(required = false) Long locationId) throws ResourceNotFoundException {
        int createdCount = inventoryService.initializeMissingInventory(locationId);
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("message", "Inventario de materiales inicializado correctamente");
        response.put("createdCount", createdCount);
        return ResponseEntity.ok(response);
    }

    // ========== INVENTORY TRANSFERS ==========

    @PostMapping("/transfers")
    public ResponseEntity<InventoryTransferResponse> createTransfer(
            @Valid @RequestBody InventoryTransferRequest request) 
            throws ResourceNotFoundException, BusinessException {
        InventoryTransferResponse response = inventoryService.createTransfer(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/transfers/bulk")
    public ResponseEntity<List<InventoryTransferResponse>> createBulkTransfer(
            @Valid @RequestBody BulkInventoryTransferRequest request) 
            throws ResourceNotFoundException, BusinessException {
        List<InventoryTransferResponse> responses = inventoryService.createBulkTransfer(request);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/transfers")
    public ResponseEntity<List<InventoryTransferResponse>> getTransfers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long fromLocationId,
            @RequestParam(required = false) Long toLocationId,
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) Long productId) {
        List<InventoryTransferResponse> transfers = inventoryService.getTransfers(
                status, fromLocationId, toLocationId, materialId, productId);
        return ResponseEntity.ok(transfers);
    }

    @GetMapping("/transfers/{id}")
    public ResponseEntity<InventoryTransferResponse> getTransferById(@PathVariable Long id) 
            throws ResourceNotFoundException {
        InventoryTransferResponse transfer = inventoryService.getTransferById(id);
        return ResponseEntity.ok(transfer);
    }

    // ========== INVENTORY ADJUSTMENTS ==========

    @PostMapping("/adjustments")
    public ResponseEntity<InventoryAdjustmentResponse> createAdjustment(
            @Valid @RequestBody InventoryAdjustmentRequest request) 
            throws ResourceNotFoundException, BusinessException {
        InventoryAdjustmentResponse response = inventoryService.createAdjustment(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/adjustments")
    public ResponseEntity<List<InventoryAdjustmentResponse>> getAdjustments(
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        List<InventoryAdjustmentResponse> adjustments = inventoryService.getAdjustments(
                materialId, productId, locationId, userId, startDate, endDate);
        return ResponseEntity.ok(adjustments);
    }

    @GetMapping("/adjustments/{id}")
    public ResponseEntity<InventoryAdjustmentResponse> getAdjustmentById(@PathVariable Long id) 
            throws ResourceNotFoundException {
        InventoryAdjustmentResponse adjustment = inventoryService.getAdjustmentById(id);
        return ResponseEntity.ok(adjustment);
    }
}

