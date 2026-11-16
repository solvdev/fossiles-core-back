package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.ImportPreview;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.ImportResult;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.ImportRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.LiquidationGenerateResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLiquidationDtos.LiquidationWeekResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskCountImportService;
import com.fossiles.fossilescorebackend.application.service.KioskLiquidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Carga del Excel de liquidaciones y liquidación semanal del calendario de conteos. */
@RestController
@RequestMapping("/api/kiosk-count-calendar")
@RequiredArgsConstructor
public class KioskLiquidationController {

    private final KioskCountImportService importService;
    private final KioskLiquidationService liquidationService;

    public record ImportRequest(List<ImportRow> rows) {
    }

    public record GenerateWeekRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
                                      List<Long> supervisorUserIds) {
    }

    public record UpdateLiquidationRequest(String status, Integer areasToFix, Boolean clearAreas, List<Long> accountantUserIds,
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate correctionsDue,
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate liquidationDate,
                                           Integer liquidationDays, String greenNote, String note,
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate viewWeek) {
    }

    public record FixLiquidationRequest(Long locationId,
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate countDate,
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate liquidationDate,
                                        Integer liquidationDays, List<Long> accountantUserIds, String note,
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate viewWeek) {
    }

    @PostMapping("/import/preview")
    public ResponseEntity<ImportPreview> preview(@RequestBody ImportRequest request) throws BusinessException {
        return ResponseEntity.ok(importService.preview(request.rows()));
    }

    @PostMapping("/import/apply")
    public ResponseEntity<ImportResult> apply(@RequestBody ImportRequest request) throws BusinessException {
        return ResponseEntity.ok(importService.apply(request.rows()));
    }

    @GetMapping("/liquidations")
    public ResponseEntity<LiquidationWeekResponse> week(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
            @RequestParam(required = false) Long supervisorUserId) throws BusinessException {
        return ResponseEntity.ok(liquidationService.getWeek(week, supervisorUserId));
    }

    @PostMapping("/liquidations/generate")
    public ResponseEntity<LiquidationGenerateResponse> generate(@RequestBody GenerateWeekRequest request)
            throws BusinessException {
        return ResponseEntity.ok(liquidationService.generateWeek(request.weekStart(), request.supervisorUserIds()));
    }

    /** Fija a mano la fecha de liquidación de un conteo (y lo registra si el sistema no lo tiene). */
    @PostMapping("/liquidations/manual")
    public ResponseEntity<LiquidationGenerateResponse> fix(@RequestBody FixLiquidationRequest request)
            throws BusinessException {
        return ResponseEntity.ok(liquidationService.fixManually(new KioskLiquidationService.ManualRequest(
                request.locationId(), request.countDate(), request.liquidationDate(), request.liquidationDays(),
                request.accountantUserIds(), request.note()), request.viewWeek()));
    }

    @PatchMapping("/liquidations/{id}")
    public ResponseEntity<LiquidationWeekResponse> update(@PathVariable Long id,
                                                          @RequestBody UpdateLiquidationRequest request)
            throws BusinessException {
        return ResponseEntity.ok(liquidationService.update(id, new KioskLiquidationService.UpdateRequest(
                request.status(), request.areasToFix(), request.clearAreas(), request.accountantUserIds(),
                request.correctionsDue(), request.liquidationDate(), request.liquidationDays(), request.greenNote(),
                request.note()), request.viewWeek()));
    }
}
