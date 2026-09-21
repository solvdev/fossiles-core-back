package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabLocationResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabStockResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.ProductLedgerLabService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/product-movements-accounting")
@RequiredArgsConstructor
public class ProductMovementsAccountingController {

    private final ProductLedgerLabService ledgerLabService;

    @GetMapping("/locations")
    public ResponseEntity<List<ProductLedgerLabLocationResponse>> listLocations()
            throws BusinessException {
        return ResponseEntity.ok(ledgerLabService.listAllowedLocations());
    }

    @GetMapping("/stocks")
    public ResponseEntity<List<ProductLedgerLabStockResponse>> listStocks(
            @RequestParam Long locationId,
            @RequestParam(required = false) String productTerm,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long colorId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(ledgerLabService.listStocks(
                locationId, productTerm, productId, colorId, null));
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
            @RequestParam(required = false) String referenceTerm,
            @RequestParam(required = false) String sizeLabel
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(ledgerLabService.listMovements(
                locationId, stockId, productId, colorId, type, from, to, null, referenceTerm,
                null, sizeLabel, null));
    }
}
