package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.PurchaseCompensationRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PurchaseCompensationResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.PurchaseCompensationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/purchase-compensations")
@RequiredArgsConstructor
public class PurchaseCompensationController {

    private final PurchaseCompensationService compensationService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING')")
    public ResponseEntity<PurchaseCompensationResponse> createCompensation(
            @Valid @RequestBody PurchaseCompensationRequest request)
            throws BusinessException, ResourceNotFoundException {
        PurchaseCompensationResponse response = compensationService.createCompensation(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING')")
    public ResponseEntity<Void> deleteCompensation(@PathVariable Long id)
            throws ResourceNotFoundException {
        compensationService.deleteCompensation(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CONSULT')")
    public ResponseEntity<List<PurchaseCompensationResponse>> getAllCompensations() {
        return ResponseEntity.ok(compensationService.getAllCompensations());
    }

    @GetMapping("/by-purchase/{purchaseId}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CONSULT')")
    public ResponseEntity<List<PurchaseCompensationResponse>> getCompensationsByPurchase(
            @PathVariable Long purchaseId) {
        return ResponseEntity.ok(compensationService.getCompensationsByPurchaseId(purchaseId));
    }

    @GetMapping("/available-surplus/{purchaseId}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ACCOUNTING', 'CONSULT')")
    public ResponseEntity<Map<String, BigDecimal>> getAvailableSurplus(@PathVariable Long purchaseId) {
        BigDecimal surplus = compensationService.calculateAvailableSurplus(purchaseId);
        return ResponseEntity.ok(Map.of("availableSurplus", surplus));
    }
}

