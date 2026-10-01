package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsForecastResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskFinancialsForecastService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/** Proyecciones de Finanzas por kiosco (sólo lectura). Permiso KIOSCOS.FINANZAS.VER validado en el servicio. */
@RestController
@RequestMapping("/api/kiosk-financials/forecast")
@RequiredArgsConstructor
public class KioskFinancialsForecastController {

    private final KioskFinancialsForecastService forecastService;

    /** Cierre proyectado del mes en curso. {@code asOf} (yyyy-MM-dd) es opcional: simula otro día. */
    @GetMapping("/month-end")
    public ResponseEntity<KioskFinancialsForecastResponse.MonthEnd> monthEnd(
            @RequestParam(required = false) String siteIds,
            @RequestParam(required = false) String asOf) throws BusinessException {
        return ResponseEntity.ok(forecastService.monthEnd(siteIds, asOf));
    }

    /** Proyección del año siguiente. {@code growthPct} reemplaza el crecimiento de todos los kioscos. */
    @GetMapping("/next-year")
    public ResponseEntity<KioskFinancialsForecastResponse.NextYear> nextYear(
            @RequestParam(required = false) String siteIds,
            @RequestParam(required = false) Integer targetYear,
            @RequestParam(required = false) BigDecimal growthPct,
            @RequestParam(required = false) String asOf) throws BusinessException {
        return ResponseEntity.ok(forecastService.nextYear(siteIds, targetYear, growthPct, asOf));
    }
}
