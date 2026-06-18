package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.SalesDashboardResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardService;
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
@RequestMapping("/api/sales")
@RequiredArgsConstructor
public class SalesDashboardController {

    private final SalesDashboardService salesDashboardService;

    @GetMapping("/dashboard")
    public ResponseEntity<SalesDashboardResponse> getDashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long kioskLocationId,
            @RequestParam(required = false, defaultValue = "all") String scope
    ) throws BusinessException {
        return ResponseEntity.ok(salesDashboardService.getDashboard(startDate, endDate, kioskLocationId, scope));
    }

    @GetMapping("/unified")
    public ResponseEntity<List<SalesDashboardResponse.UnifiedSaleRow>> getUnifiedSales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Long kioskLocationId,
            @RequestParam(required = false, defaultValue = "500") Integer limit
    ) throws BusinessException {
        return ResponseEntity.ok(salesDashboardService.getUnifiedSales(startDate, endDate, channel, kioskLocationId, limit));
    }
}
