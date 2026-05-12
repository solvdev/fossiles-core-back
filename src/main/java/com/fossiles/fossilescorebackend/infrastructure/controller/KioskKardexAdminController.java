package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.KioskKardexBackfillResponse;
import com.fossiles.fossilescorebackend.application.service.KioskKardexBackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/kiosk-kardex")
@RequiredArgsConstructor
public class KioskKardexAdminController {

    private final KioskKardexBackfillService backfillService;

    @PostMapping("/backfill")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public ResponseEntity<KioskKardexBackfillResponse> backfill(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ResponseEntity.ok(backfillService.backfill(startDate, endDate));
    }
}

