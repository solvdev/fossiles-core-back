package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsConfigBulkRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsConfigCopyRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsSiteCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsSiteUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsBulkResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsConfigResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsCopyResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsSiteResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.KioskFinancialsConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Sitios y configuracion (costos fijos, tasas, metas) de Finanzas por kiosco. */
@RestController
@RequestMapping("/api/kiosk-financials")
@RequiredArgsConstructor
public class KioskFinancialsConfigController {

    private final KioskFinancialsConfigService configService;

    @GetMapping("/sites")
    public ResponseEntity<List<KioskFinancialsSiteResponse>> getSites() throws BusinessException {
        return ResponseEntity.ok(configService.getSites());
    }

    @PostMapping("/sites")
    public ResponseEntity<KioskFinancialsSiteResponse> createSite(
            @RequestBody KioskFinancialsSiteCreateRequest request) throws BusinessException {
        return ResponseEntity.ok(configService.createSite(request));
    }

    @PutMapping("/sites/{id}")
    public ResponseEntity<KioskFinancialsSiteResponse> updateSite(
            @PathVariable Long id,
            @RequestBody KioskFinancialsSiteUpdateRequest request) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(configService.updateSite(id, request));
    }

    @GetMapping("/config")
    public ResponseEntity<KioskFinancialsConfigResponse> getConfig(
            @RequestParam Integer year,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Integer month) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(configService.getConfig(year, siteId, month));
    }

    @PutMapping("/config/bulk")
    public ResponseEntity<KioskFinancialsBulkResponse> bulkUpdate(
            @RequestBody KioskFinancialsConfigBulkRequest request) throws BusinessException {
        return ResponseEntity.ok(configService.bulkUpdate(request));
    }

    @PostMapping("/config/copy")
    public ResponseEntity<KioskFinancialsCopyResponse> copy(
            @RequestBody KioskFinancialsConfigCopyRequest request) throws BusinessException {
        return ResponseEntity.ok(configService.copy(request));
    }
}
