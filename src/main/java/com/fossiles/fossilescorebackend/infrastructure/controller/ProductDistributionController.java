package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ProductDistributionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductDistributionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryLocationResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.ProductDistributionService;
import com.fossiles.fossilescorebackend.application.service.ProductInventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/product-distributions")
@RequiredArgsConstructor
public class ProductDistributionController {

    private final ProductDistributionService distributionService;
    private final ProductInventoryService inventoryService;

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

    @DeleteMapping("/shipments/{id}")
    public ResponseEntity<Void> deleteShipment(@PathVariable Long id) 
            throws ResourceNotFoundException {
        distributionService.deleteShipment(id);
        return ResponseEntity.noContent().build();
    }

    // ========== SHIPMENT TRANSIT & RECEIPT ==========

    @PutMapping("/shipments/{id}/send")
    public ResponseEntity<ProductShipmentResponse> sendShipment(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(distributionService.sendShipment(id));
    }

    @PutMapping("/shipments/{id}/confirm-receipt")
    public ResponseEntity<ProductShipmentResponse> confirmReceipt(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(distributionService.confirmReceipt(id, body));
    }

    @GetMapping("/shipments/in-transit")
    public ResponseEntity<List<ProductShipmentResponse>> getShipmentsInTransit() {
        return ResponseEntity.ok(distributionService.getShipmentsInTransit());
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

