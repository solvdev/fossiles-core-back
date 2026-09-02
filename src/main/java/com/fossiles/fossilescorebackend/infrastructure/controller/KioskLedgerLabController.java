package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.KioskLedgerLabMovementUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskLedgerLabStockUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLedgerLabMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLedgerLabReplayAllKiosksResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLedgerLabReplayAllResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLedgerLabSplitSizesResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLedgerLabStockResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.KioskLedgerLabService;
import com.fossiles.fossilescorebackend.application.util.KioskLedgerLabGuard;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
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
@RequestMapping("/api/kiosk-ledger-lab")
@RequiredArgsConstructor
public class KioskLedgerLabController {

    private final KioskLedgerLabService ledgerLabService;
    private final KioskLedgerLabGuard guard;

    @GetMapping("/stocks")
    public ResponseEntity<List<KioskLedgerLabStockResponse>> listStocks(
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) String productTerm,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long colorId,
            @RequestParam(required = false) Long stockId,
            @RequestParam(required = false) String hardwareCondition
    ) throws BusinessException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.listStocks(
                locationId, productTerm, productId, colorId, stockId, hardwareCondition));
    }

    @GetMapping("/movements")
    public ResponseEntity<List<KioskLedgerLabMovementResponse>> listMovements(
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) Long stockId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) KioscoMovementType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long referenceId,
            @RequestParam(required = false) String referenceTerm,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String sizeKey,
            @RequestParam(required = false) Boolean affectsStockOnly,
            @RequestParam(required = false) Long movementId
    ) throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.listMovements(
                locationId, stockId, productId, type, from, to, referenceId, referenceTerm, reason,
                sizeKey, affectsStockOnly, movementId));
    }

    @GetMapping("/movements/{id}")
    public ResponseEntity<KioskLedgerLabMovementResponse> getMovement(@PathVariable Long id)
            throws ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.getMovement(id));
    }

    @PostMapping("/movements")
    public ResponseEntity<KioskLedgerLabMovementResponse> createMovement(
            @RequestBody KioskLedgerLabMovementUpsertRequest request
    ) throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.status(HttpStatus.CREATED).body(ledgerLabService.createMovement(request));
    }

    @PutMapping("/movements/{id}")
    public ResponseEntity<KioskLedgerLabMovementResponse> updateMovement(
            @PathVariable Long id,
            @RequestBody KioskLedgerLabMovementUpsertRequest request
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
    public ResponseEntity<KioskLedgerLabStockResponse> updateStock(
            @PathVariable Long stockId,
            @RequestBody KioskLedgerLabStockUpdateRequest request
    ) throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.updateStock(stockId, request));
    }

    @PostMapping("/stocks/{stockId}/replay")
    public ResponseEntity<KioskLedgerLabStockResponse> replayStock(@PathVariable Long stockId)
            throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.replayStock(stockId));
    }

    @PostMapping("/locations/{locationId}/replay-all")
    public ResponseEntity<KioskLedgerLabReplayAllResponse> replayAllStocks(@PathVariable Long locationId)
            throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.replayAllStocks(locationId));
    }

    /** Replay de stock_before/after y current_stock de TODOS los kioscos (uno por uno). */
    @PostMapping("/replay-all-kiosks")
    public ResponseEntity<KioskLedgerLabReplayAllKiosksResponse> replayAllKiosks()
            throws BusinessException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.replayAllKiosks());
    }

    @PostMapping("/stocks/{stockId}/split-opening-by-sizes")
    public ResponseEntity<KioskLedgerLabSplitSizesResponse> splitOpeningBySizes(@PathVariable Long stockId)
            throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        return ResponseEntity.ok(ledgerLabService.splitOpeningBySizes(stockId));
    }
}
