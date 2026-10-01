package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsCompareResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsCompletenessResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsDailyMatrixResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsPnlResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsSupervisorsResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskFinancialsReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reportes de Finanzas por kiosco (P&amp;L, matriz diaria, comparativo, completitud). */
@RestController
@RequestMapping("/api/kiosk-financials")
@RequiredArgsConstructor
public class KioskFinancialsReportController {

    private final KioskFinancialsReportService reportService;

    @GetMapping("/pnl")
    public ResponseEntity<KioskFinancialsPnlResponse> getPnl(
            @RequestParam Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String siteIds) throws BusinessException {
        return ResponseEntity.ok(reportService.getPnl(year, month, siteIds));
    }

    /** Supervisoras con los sitios de sus kioscos (filtro de los reportes). */
    @GetMapping("/supervisors")
    public ResponseEntity<KioskFinancialsSupervisorsResponse> getSupervisors() throws BusinessException {
        return ResponseEntity.ok(reportService.getSupervisors());
    }

    @GetMapping("/daily-matrix")
    public ResponseEntity<KioskFinancialsDailyMatrixResponse> getDailyMatrix(
            @RequestParam Integer year,
            @RequestParam Integer month,
            @RequestParam(required = false) String siteIds) throws BusinessException {
        return ResponseEntity.ok(reportService.getDailyMatrix(year, month, siteIds));
    }

    @GetMapping("/compare")
    public ResponseEntity<KioskFinancialsCompareResponse> compare(
            @RequestParam Integer year,
            @RequestParam Integer baseYear,
            @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toMonth,
            @RequestParam(required = false, defaultValue = "SAME_PERIOD") String mode,
            @RequestParam(required = false) String siteIds) throws BusinessException {
        return ResponseEntity.ok(reportService.compare(year, baseYear, fromMonth, toMonth, mode, siteIds));
    }

    @GetMapping("/completeness")
    public ResponseEntity<KioskFinancialsCompletenessResponse> getCompleteness(
            @RequestParam Integer year) throws BusinessException {
        return ResponseEntity.ok(reportService.getCompleteness(year));
    }
}
