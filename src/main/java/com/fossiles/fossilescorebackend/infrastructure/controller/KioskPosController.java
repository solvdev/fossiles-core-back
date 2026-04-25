package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPromotionRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCustomerProfileResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosContextResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPromotionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosReportsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosSaleResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskProductAvailabilityResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.KioskPosService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/kiosk-pos")
@RequiredArgsConstructor
public class KioskPosController {

    private final KioskPosService kioskPosService;

    @GetMapping("/context")
    public ResponseEntity<KioskPosContextResponse> getContext(
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getCurrentContext(kioskLocationId));
    }

    @PostMapping("/sales")
    public ResponseEntity<KioskPosSaleResponse> createSale(@RequestBody KioskPosSaleRequest request)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.createSale(request));
    }

    @GetMapping("/customers/by-tax-id")
    public ResponseEntity<KioskCustomerProfileResponse> getCustomerByTaxId(@RequestParam String taxId)
            throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getCustomerByTaxId(taxId));
    }

    @GetMapping("/sales/my-kiosk")
    public ResponseEntity<List<KioskPosSaleResponse>> getMyKioskSales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getCurrentKioskSales(startDate, endDate, kioskLocationId));
    }

    @GetMapping("/promotions")
    public ResponseEntity<List<KioskPromotionResponse>> getPromotions(
            @RequestParam(required = false) Boolean activeOnly
    ) {
        return ResponseEntity.ok(kioskPosService.getPromotions(activeOnly));
    }

    @PostMapping("/promotions")
    public ResponseEntity<KioskPromotionResponse> createPromotion(@RequestBody KioskPromotionRequest request)
            throws BusinessException {
        return ResponseEntity.ok(kioskPosService.createPromotion(request));
    }

    @PutMapping("/promotions/{id}")
    public ResponseEntity<KioskPromotionResponse> updatePromotion(
            @PathVariable Long id,
            @RequestBody KioskPromotionRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.updatePromotion(id, request));
    }

    @GetMapping("/reports/my-kiosk")
    public ResponseEntity<KioskPosReportsResponse> getMyKioskReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getCurrentKioskReport(startDate, endDate, kioskLocationId));
    }

    @GetMapping("/reports/general")
    public ResponseEntity<KioskPosReportsResponse> getGeneralReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getGeneralReport(startDate, endDate));
    }

    @GetMapping("/availability")
    public ResponseEntity<List<KioskProductAvailabilityResponse>> getAvailability(
            @RequestParam Long productId,
            @RequestParam(required = false) Long colorId,
            @RequestParam(defaultValue = "false") boolean includeCurrentKiosk,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(
                kioskPosService.findAvailabilityInKiosks(productId, colorId, includeCurrentKiosk, kioskLocationId)
        );
    }
}
