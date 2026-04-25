package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.PurchaseReportResponse;
import com.fossiles.fossilescorebackend.application.service.PurchaseReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/purchase-reports")
@RequiredArgsConstructor
public class PurchaseReportController {

    private final PurchaseReportService purchaseReportService;

    // CP-08-001: Reporte de Órdenes por Estado
    @GetMapping("/orders-by-status")
    public ResponseEntity<PurchaseReportResponse.OrdersByStatusReport> getOrdersByStatusReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        PurchaseReportResponse.OrdersByStatusReport report = purchaseReportService.getOrdersByStatusReport(startDate, endDate);
        return ResponseEntity.ok(report);
    }

    // CP-08-002: Reporte de Compras por Proveedor
    @GetMapping("/purchases-by-supplier")
    public ResponseEntity<PurchaseReportResponse.PurchasesBySupplierReport> getPurchasesBySupplierReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        PurchaseReportResponse.PurchasesBySupplierReport report = purchaseReportService.getPurchasesBySupplierReport(startDate, endDate);
        return ResponseEntity.ok(report);
    }

    // CP-08-003: Reporte de Inventario Actual
    @GetMapping("/current-inventory")
    public ResponseEntity<PurchaseReportResponse.CurrentInventoryReport> getCurrentInventoryReport() {
        PurchaseReportResponse.CurrentInventoryReport report = purchaseReportService.getCurrentInventoryReport();
        return ResponseEntity.ok(report);
    }

    // CP-08-004: Reporte de Materiales Críticos
    @GetMapping("/critical-materials")
    public ResponseEntity<PurchaseReportResponse.CriticalMaterialsReport> getCriticalMaterialsReport() {
        PurchaseReportResponse.CriticalMaterialsReport report = purchaseReportService.getCriticalMaterialsReport();
        return ResponseEntity.ok(report);
    }

    // CP-08-005: Reporte de Asientos Contables
    @GetMapping("/accounting-entries")
    public ResponseEntity<PurchaseReportResponse.AccountingEntriesReport> getAccountingEntriesReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String documentType) {
        PurchaseReportResponse.AccountingEntriesReport report = purchaseReportService.getAccountingEntriesReport(startDate, endDate, documentType);
        return ResponseEntity.ok(report);
    }

    // CP-08-006: Dashboard Ejecutivo
    @GetMapping("/executive-dashboard")
    public ResponseEntity<PurchaseReportResponse.ExecutiveDashboard> getExecutiveDashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        PurchaseReportResponse.ExecutiveDashboard dashboard = purchaseReportService.getExecutiveDashboard(startDate, endDate);
        return ResponseEntity.ok(dashboard);
    }

    // CP-08-007: Reporte de Promedio de Costos por Producto
    @GetMapping("/product-average-costs")
    public ResponseEntity<PurchaseReportResponse.ProductAverageCostReport> getProductAverageCostsReport() {
        PurchaseReportResponse.ProductAverageCostReport report = purchaseReportService.getProductAverageCostReport();
        return ResponseEntity.ok(report);
    }
}

