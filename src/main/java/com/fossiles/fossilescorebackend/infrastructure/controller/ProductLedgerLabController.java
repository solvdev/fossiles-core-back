package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ProductLedgerLabMovementUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductLedgerLabStockUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabLocationResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabReplayAllResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabStockResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.ProductLedgerLabService;
import com.fossiles.fossilescorebackend.application.util.ProductLedgerLabGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/product-ledger-lab")
@RequiredArgsConstructor
public class ProductLedgerLabController {

    private final ProductLedgerLabService ledgerLabService;
    private final ProductLedgerLabGuard guard;

    @GetMapping("/locations")
    public ResponseEntity<List<ProductLedgerLabLocationResponse>> listLocations()
            throws BusinessException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.listAllowedLocations());
    }

    @GetMapping("/stocks")
    public ResponseEntity<List<ProductLedgerLabStockResponse>> listStocks(
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) String productTerm,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long colorId,
            @RequestParam(required = false) Long stockId
    ) throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.listStocks(
                locationId, productTerm, productId, colorId, stockId));
    }

    @GetMapping("/movements")
    public ResponseEntity<List<ProductLedgerLabMovementResponse>> listMovements(
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) Long stockId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long colorId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long referenceId,
            @RequestParam(required = false) String referenceTerm,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String sizeLabel,
            @RequestParam(required = false) Long movementId
    ) throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.listMovements(
                locationId, stockId, productId, colorId, type, from, to, referenceId, referenceTerm,
                description, sizeLabel, movementId));
    }

    @GetMapping("/movements/{id}")
    public ResponseEntity<ProductLedgerLabMovementResponse> getMovement(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.getMovement(id));
    }

    @PostMapping("/movements")
    public ResponseEntity<ProductLedgerLabMovementResponse> createMovement(
            @RequestBody ProductLedgerLabMovementUpsertRequest request
    ) throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.status(HttpStatus.CREATED).body(ledgerLabService.createMovement(request));
    }

    @PutMapping("/movements/{id}")
    public ResponseEntity<ProductLedgerLabMovementResponse> updateMovement(
            @PathVariable Long id,
            @RequestBody ProductLedgerLabMovementUpsertRequest request
    ) throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.updateMovement(id, request));
    }

    @DeleteMapping("/movements/{id}")
    public ResponseEntity<Void> deleteMovement(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        ledgerLabService.deleteMovement(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/stocks/{stockId}")
    public ResponseEntity<ProductLedgerLabStockResponse> updateStock(
            @PathVariable Long stockId,
            @RequestBody ProductLedgerLabStockUpdateRequest request
    ) throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.updateStock(stockId, request));
    }

    @PostMapping("/stocks/{stockId}/replay")
    public ResponseEntity<ProductLedgerLabStockResponse> replayStock(@PathVariable Long stockId)
            throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.replayStock(stockId));
    }

    @PostMapping("/locations/{locationId}/replay-all")
    public ResponseEntity<ProductLedgerLabReplayAllResponse> replayAllStocks(@PathVariable Long locationId)
            throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.replayAllStocks(locationId));
    }
}
