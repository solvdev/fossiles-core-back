package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.KioskMovementsAccountingResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskMovementsAccountingService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
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
@RequestMapping("/api/kiosk-movements-accounting")
@RequiredArgsConstructor
public class KioskMovementsAccountingController {

    private final KioskMovementsAccountingService service;

    @GetMapping("/movements")
    public ResponseEntity<List<KioskMovementsAccountingResponse>> listMovements(
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) Long stockId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String productTerm,
            @RequestParam(required = false) KioscoMovementType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String referenceTerm,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String sizeKey,
            @RequestParam(required = false) Boolean affectsStockOnly
    ) throws BusinessException {
        return ResponseEntity.ok(service.listMovements(
                locationId, stockId, productId, productTerm, type, from, to,
                referenceTerm, reason, sizeKey, affectsStockOnly));
    }
}
