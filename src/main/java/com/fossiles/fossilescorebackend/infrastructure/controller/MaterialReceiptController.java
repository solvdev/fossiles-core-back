package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.MaterialReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialReceiptResponse;
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
@RequestMapping("/api/material-receipts")
@RequiredArgsConstructor
public class MaterialReceiptController {

    private final PurchaseService purchaseService;

    @GetMapping
    public ResponseEntity<List<MaterialReceiptResponse>> getAll() {
        List<MaterialReceiptResponse> receipts = purchaseService.getMaterialReceipts();
        return ResponseEntity.ok(receipts);
    }

    @PostMapping
    public ResponseEntity<MaterialReceiptResponse> create(
            @Valid @RequestBody MaterialReceiptRequest request) throws BusinessException, ResourceNotFoundException {
        MaterialReceiptResponse response = purchaseService.createMaterialReceipt(request);
        return ResponseEntity.created(URI.create("/api/material-receipts/" + response.getId()))
                .body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MaterialReceiptResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody MaterialReceiptRequest request) throws BusinessException, ResourceNotFoundException {
        MaterialReceiptResponse response = purchaseService.updateMaterialReceipt(id, request);
        return ResponseEntity.ok(response);
    }
}

