package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.PurchaseOrderRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PurchaseOrderResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.PurchaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseService purchaseService;

    @GetMapping
    public ResponseEntity<List<PurchaseOrderResponse>> getAll(
            @RequestParam(required = false) String status) {
        List<PurchaseOrderResponse> orders = purchaseService.getPurchaseOrdersByStatus(status);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PurchaseOrderResponse> getById(@PathVariable Long id) 
            throws ResourceNotFoundException {
        PurchaseOrderResponse order = purchaseService.getPurchaseOrderById(id);
        return ResponseEntity.ok(order);
    }

    @PostMapping
    public ResponseEntity<PurchaseOrderResponse> create(
            @Valid @RequestBody PurchaseOrderRequest request) 
            throws BusinessException, ResourceNotFoundException {
        PurchaseOrderResponse response = purchaseService.createPurchaseOrder(request);
        return ResponseEntity.created(URI.create("/api/purchase-orders/" + response.getId()))
                .body(response);
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<PurchaseOrderResponse> cancel(@PathVariable Long id) 
            throws ResourceNotFoundException, BusinessException {
        PurchaseOrderResponse response = purchaseService.cancelPurchaseOrder(id);
        return ResponseEntity.ok(response);
    }
}

