package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.KioskHeatmapResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OpvShipmentCatalogRowResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesConsolidatedResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesDashboardResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskHeatmapService;
import com.fossiles.fossilescorebackend.application.service.KioskSalesDashboardService;
import com.fossiles.fossilescorebackend.application.service.OnlineSalesDashboardService;
import com.fossiles.fossilescorebackend.application.service.OpvShipmentCatalogService;
import com.fossiles.fossilescorebackend.application.service.SalesConsolidatedService;
import com.fossiles.fossilescorebackend.application.service.SalesUnifiedService;
import com.fossiles.fossilescorebackend.application.service.VendorSalesDashboardService;
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

    private final SalesConsolidatedService salesConsolidatedService;
    private final KioskSalesDashboardService kioskSalesDashboardService;
    private final KioskHeatmapService kioskHeatmapService;
    private final OnlineSalesDashboardService onlineSalesDashboardService;
    private final VendorSalesDashboardService vendorSalesDashboardService;
    private final SalesUnifiedService salesUnifiedService;
    private final OpvShipmentCatalogService opvShipmentCatalogService;

    @GetMapping("/dashboard/consolidated")
    public ResponseEntity<SalesConsolidatedResponse> getConsolidated(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws BusinessException {
        return ResponseEntity.ok(salesConsolidatedService.getConsolidated(startDate, endDate, refresh));
    }

    @GetMapping("/dashboard/kiosks")
    public ResponseEntity<SalesSourceDetailResponse> getKiosks(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Long kioskLocationId,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws BusinessException {
        return ResponseEntity.ok(kioskSalesDashboardService.getDashboard(
                startDate, endDate, siteId, kioskLocationId, refresh));
    }

    /** Mapa de calor de TODOS los kioscos incluidos en reportes (sin filtro por kiosko); máximo 400 días. */
    @GetMapping("/dashboard/kiosks/heatmap")
    public ResponseEntity<KioskHeatmapResponse> getKiosksHeatmap(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws BusinessException {
        return ResponseEntity.ok(kioskHeatmapService.getHeatmap(startDate, endDate, refresh));
    }

    @GetMapping("/dashboard/online")
    public ResponseEntity<SalesSourceDetailResponse> getOnline(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws BusinessException {
        return ResponseEntity.ok(onlineSalesDashboardService.getDashboard(startDate, endDate, refresh));
    }

    @GetMapping("/dashboard/vendor")
    public ResponseEntity<SalesSourceDetailResponse> getVendor(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean refresh
    ) throws BusinessException {
        return ResponseEntity.ok(vendorSalesDashboardService.getDashboard(startDate, endDate, refresh));
    }

    @GetMapping("/unified")
    public ResponseEntity<List<SalesDashboardResponse.UnifiedSaleRow>> getUnifiedSales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Long kioskLocationId,
            @RequestParam(required = false, defaultValue = "500") Integer limit
    ) throws BusinessException {
        return ResponseEntity.ok(salesUnifiedService.getUnifiedSales(startDate, endDate, channel, kioskLocationId, limit));
    }

    @GetMapping("/opv-shipments")
    public ResponseEntity<List<OpvShipmentCatalogRowResponse>> getOpvShipments(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String orderStatus,
            @RequestParam(required = false) String shipmentStatus,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Boolean hasShipment,
            @RequestParam(defaultValue = "300") int limit) {
        return ResponseEntity.ok(opvShipmentCatalogService.search(
                search, orderStatus, shipmentStatus, customerId, from, to, hasShipment, limit));
    }
}
