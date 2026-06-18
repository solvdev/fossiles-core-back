package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ConfirmReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductDistributionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.request.StandaloneInternalShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.request.StandaloneKioskShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DispatchStockPreviewResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentRequestResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductDistributionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryLocationResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.InternalShipmentRequestService;
import com.fossiles.fossilescorebackend.application.service.InternalShipmentRequestService;
import com.fossiles.fossilescorebackend.application.service.ProductDistributionService;
import com.fossiles.fossilescorebackend.application.service.ProductInventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/product-distributions")
@RequiredArgsConstructor
public class ProductDistributionController {

    private final ProductDistributionService distributionService;
    private final ProductInventoryService inventoryService;
    private final InternalShipmentRequestService internalShipmentRequestService;

    // ========== DISTRIBUTION ==========

    @GetMapping
    public ResponseEntity<List<ProductDistributionResponse>> getAllDistributions() {
        List<ProductDistributionResponse> distributions = distributionService.getAllDistributions();
        return ResponseEntity.ok(distributions);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDistributionResponse> getDistributionById(@PathVariable Long id) 
            throws ResourceNotFoundException {
        ProductDistributionResponse distribution = distributionService.getDistributionById(id);
        return ResponseEntity.ok(distribution);
    }

    @PostMapping
    public ResponseEntity<ProductDistributionResponse> createDistribution(
            @Valid @RequestBody ProductDistributionRequest request) {
        ProductDistributionResponse distribution = distributionService.createDistribution(request);
        return ResponseEntity.ok(distribution);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductDistributionResponse> updateDistribution(
            @PathVariable Long id,
            @Valid @RequestBody ProductDistributionRequest request) 
            throws ResourceNotFoundException {
        ProductDistributionResponse distribution = distributionService.updateDistribution(id, request);
        return ResponseEntity.ok(distribution);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDistribution(@PathVariable Long id) 
            throws ResourceNotFoundException, BusinessException {
        distributionService.deleteDistribution(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<ProductDistributionResponse> completeDistribution(
            @PathVariable Long id,
            @RequestParam(name = "generateProductionOrder", defaultValue = "false") boolean generateProductionOrder)
            throws ResourceNotFoundException, BusinessException {
        ProductDistributionResponse distribution = distributionService.completeDistribution(id, generateProductionOrder);
        return ResponseEntity.ok(distribution);
    }

    // ========== SHIPMENT ==========

    @GetMapping("/{distributionId}/shipments")
    public ResponseEntity<List<ProductShipmentResponse>> getShipmentsByDistribution(
            @PathVariable Long distributionId) {
        List<ProductShipmentResponse> shipments = distributionService.getShipmentsByDistribution(distributionId);
        return ResponseEntity.ok(shipments);
    }

    @GetMapping("/shipments/{id}")
    public ResponseEntity<ProductShipmentResponse> getShipmentById(@PathVariable Long id) 
            throws ResourceNotFoundException {
        ProductShipmentResponse shipment = distributionService.getShipmentById(id);
        return ResponseEntity.ok(shipment);
    }

    @PostMapping("/{distributionId}/shipments")
    public ResponseEntity<ProductShipmentResponse> createOrUpdateShipment(
            @PathVariable Long distributionId,
            @Valid @RequestBody ProductShipmentRequest request) 
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentResponse shipment = distributionService.createOrUpdateShipment(distributionId, request);
        return ResponseEntity.ok(shipment);
    }

    @PutMapping("/shipments/{id}/products")
    public ResponseEntity<ProductShipmentResponse> updateShipmentProducts(
            @PathVariable Long id,
            @Valid @RequestBody List<ProductShipmentRequest.ProductShipmentDetailRequest> products) 
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentResponse shipment = distributionService.updateShipmentProducts(id, products);
        return ResponseEntity.ok(shipment);
    }

    @PutMapping("/shipments/{id}/packing-items")
    public ResponseEntity<ProductShipmentResponse> updateShipmentPackingItems(
            @PathVariable Long id,
            @RequestBody List<ProductShipmentRequest.PackingItemRequest> packingItems)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(distributionService.updateShipmentPackingItems(id, packingItems));
    }

    @DeleteMapping("/shipments/{id}")
    public ResponseEntity<Void> deleteShipment(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        distributionService.deleteShipment(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/shipments/{id}/cancel")
    public ResponseEntity<ProductShipmentResponse> cancelShipment(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(distributionService.cancelShipment(id));
    }

    // ========== SHIPMENT TRANSIT & RECEIPT ==========

    @PutMapping("/shipments/{id}/send")
    public ResponseEntity<ProductShipmentResponse> sendShipment(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(distributionService.sendShipment(id));
    }

    /** Revierte un envío en tránsito (SENT) a confirmado y devuelve stock a Bodega PT / Devoluciones. */
    @PutMapping("/shipments/{id}/revert-send")
    public ResponseEntity<ProductShipmentResponse> revertSentShipment(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(distributionService.revertSentShipment(id));
    }

    @PutMapping("/shipments/{id}/confirm-draft")
    public ResponseEntity<ProductShipmentResponse> confirmShipmentDraft(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(distributionService.confirmShipmentDraft(id));
    }

    @PutMapping("/shipments/{id}/confirm-receipt")
    public ResponseEntity<ProductShipmentResponse> confirmReceipt(
            @PathVariable Long id,
            @RequestBody(required = false) ConfirmReceiptRequest body)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(distributionService.confirmReceipt(id, body));
    }

    @PostMapping("/shipments/standalone-internal")
    public ResponseEntity<InternalShipmentRequestResponse> createStandaloneInternalShipment(
            @Valid @RequestBody StandaloneInternalShipmentRequest request)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(internalShipmentRequestService.createRequestFromLegacy(request));
    }

    @GetMapping("/shipments/standalone-internal")
    public ResponseEntity<List<ProductShipmentResponse>> listStandaloneInternalShipments() {
        return ResponseEntity.ok(distributionService.listStandaloneInternalShipments());
    }

    @PostMapping("/shipments/standalone-kiosk")
    public ResponseEntity<ProductShipmentResponse> createStandaloneKioskShipment(
            @Valid @RequestBody StandaloneKioskShipmentRequest request)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(distributionService.createStandaloneKioskShipment(request));
    }

    @GetMapping("/shipments/standalone-kiosk")
    public ResponseEntity<List<ProductShipmentResponse>> listStandaloneKioskShipments() {
        return ResponseEntity.ok(distributionService.listStandaloneKioskShipments());
    }

    @GetMapping("/dispatch-stock-preview")
    public ResponseEntity<DispatchStockPreviewResponse> previewDispatchStock(
            @RequestParam Long productId,
            @RequestParam(required = false) Long colorId,
            @RequestParam(required = false) String size) throws BusinessException {
        return ResponseEntity.ok(distributionService.previewDispatchStock(productId, colorId, size));
    }

    @PostMapping("/validate-dispatch-stock")
    public ResponseEntity<Void> validateDispatchStock(
            @Valid @RequestBody List<ProductShipmentRequest.ProductShipmentDetailRequest> products)
            throws BusinessException, ResourceNotFoundException {
        distributionService.validateDispatchStock(products);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/shipments/in-transit")
    public ResponseEntity<List<ProductShipmentResponse>> getShipmentsInTransit(
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(distributionService.getShipmentsInTransit(kioskLocationId));
    }

    @GetMapping("/shipments/in-transit/count")
    public ResponseEntity<Map<String, Long>> countShipmentsInTransit(
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(Map.of("count", distributionService.countShipmentsInTransit(kioskLocationId)));
    }

    @GetMapping("/shipments/by-status/{status}")
    public ResponseEntity<List<ProductShipmentResponse>> getShipmentsByStatus(@PathVariable String status) {
        return ResponseEntity.ok(distributionService.getShipmentsByStatus(status));
    }

    // ========== INVENTORY FOR SHIPMENT ==========

    @GetMapping("/shipments/{shipmentId}/inventory")
    public ResponseEntity<List<ProductInventoryLocationResponse>> getInventoryForShipment(
            @PathVariable Long shipmentId) throws ResourceNotFoundException {
        // Obtener el envío para saber el kiosko
        ProductShipmentResponse shipment = distributionService.getShipmentById(shipmentId);
        
        // Obtener inventario del kiosko
        List<ProductInventoryLocationResponse> inventory = inventoryService.getInventoryByLocation(shipment.getLocationId());
        return ResponseEntity.ok(inventory);
    }
}

