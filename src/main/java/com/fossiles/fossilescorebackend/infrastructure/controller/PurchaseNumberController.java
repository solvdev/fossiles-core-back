package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.PurchaseNumberRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PurchaseNumberItemRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PurchaseNumberResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PurchaseNumberItemResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.PurchaseNumberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/purchase-numbers")
@RequiredArgsConstructor
public class PurchaseNumberController {

    private final PurchaseNumberService purchaseNumberService;
    private final com.fossiles.fossilescorebackend.application.service.MinorExpenseService minorExpenseService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST')")
    public ResponseEntity<PurchaseNumberResponse> createPurchaseNumber(@RequestBody PurchaseNumberRequest request)
            throws BusinessException {
        PurchaseNumberResponse response = purchaseNumberService.createPurchaseNumber(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING')")
    public ResponseEntity<PurchaseNumberResponse> updatePurchaseNumber(
            @PathVariable Long id,
            @RequestBody PurchaseNumberRequest request)
            throws ResourceNotFoundException, BusinessException {
        PurchaseNumberResponse response = purchaseNumberService.updatePurchaseNumber(id, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST', 'CONSULT')")
    public ResponseEntity<PurchaseNumberResponse> getPurchaseNumberById(@PathVariable Long id)
            throws ResourceNotFoundException {
        PurchaseNumberResponse response = purchaseNumberService.getPurchaseNumberById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST', 'CONSULT')")
    public ResponseEntity<List<PurchaseNumberResponse>> getAllPurchaseNumbers() {
        List<PurchaseNumberResponse> response = purchaseNumberService.getAllPurchaseNumbers();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/available")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST', 'CONSULT')")
    public ResponseEntity<List<PurchaseNumberResponse>> getAvailablePurchaseNumbers() {
        List<PurchaseNumberResponse> response = purchaseNumberService.getAvailablePurchaseNumbers();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status/{status}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST', 'CONSULT')")
    public ResponseEntity<List<PurchaseNumberResponse>> getPurchaseNumbersByStatus(@PathVariable String status) {
        List<PurchaseNumberResponse> response = purchaseNumberService.getPurchaseNumbersByStatus(status);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING')")
    public ResponseEntity<Void> deletePurchaseNumber(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        purchaseNumberService.deletePurchaseNumber(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST')")
    public ResponseEntity<PurchaseNumberResponse> closePurchaseNumber(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        PurchaseNumberResponse response = purchaseNumberService.closePurchaseNumber(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/expenses")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST', 'CONSULT')")
    public ResponseEntity<List<com.fossiles.fossilescorebackend.application.dto.response.MinorExpenseResponse>> getPurchaseNumberExpenses(
            @PathVariable Long id) throws ResourceNotFoundException {
        // Verificar que el número de compra existe
        purchaseNumberService.getPurchaseNumberById(id);

        // Obtener gastos asociados
        List<com.fossiles.fossilescorebackend.application.dto.response.MinorExpenseResponse> expenses =
                minorExpenseService.getExpensesByPurchaseNumberId(id);
        return ResponseEntity.ok(expenses);
    }

    // ========== PURCHASE NUMBER ITEMS ==========

    @PostMapping("/{purchaseNumberId}/items")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST')")
    public ResponseEntity<PurchaseNumberItemResponse> createPurchaseNumberItem(
            @PathVariable Long purchaseNumberId,
            @RequestBody PurchaseNumberItemRequest request)
            throws ResourceNotFoundException, BusinessException {
        PurchaseNumberItemResponse response = purchaseNumberService.createPurchaseNumberItem(purchaseNumberId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{purchaseNumberId}/items")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST', 'CONSULT')")
    public ResponseEntity<List<PurchaseNumberItemResponse>> getPurchaseNumberItems(
            @PathVariable Long purchaseNumberId) {
        List<PurchaseNumberItemResponse> response = purchaseNumberService.getPurchaseNumberItems(purchaseNumberId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{purchaseNumberId}/items/{itemId}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST', 'CONSULT')")
    public ResponseEntity<PurchaseNumberItemResponse> getPurchaseNumberItemById(
            @PathVariable Long purchaseNumberId,
            @PathVariable Long itemId)
            throws ResourceNotFoundException {
        PurchaseNumberItemResponse response = purchaseNumberService.getPurchaseNumberItemById(purchaseNumberId, itemId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{purchaseNumberId}/items/{itemId}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST')")
    public ResponseEntity<PurchaseNumberItemResponse> updatePurchaseNumberItem(
            @PathVariable Long purchaseNumberId,
            @PathVariable Long itemId,
            @RequestBody PurchaseNumberItemRequest request)
            throws ResourceNotFoundException, BusinessException {
        PurchaseNumberItemResponse response = purchaseNumberService.updatePurchaseNumberItem(purchaseNumberId, itemId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{purchaseNumberId}/items/{itemId}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CAPTURIST')")
    public ResponseEntity<Void> deletePurchaseNumberItem(
            @PathVariable Long purchaseNumberId,
            @PathVariable Long itemId)
            throws ResourceNotFoundException, BusinessException {
        purchaseNumberService.deletePurchaseNumberItem(purchaseNumberId, itemId);
        return ResponseEntity.noContent().build();
    }
}

