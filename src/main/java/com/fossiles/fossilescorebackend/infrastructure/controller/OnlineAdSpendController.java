package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.OnlineAdSpendBulkRequest;
import com.fossiles.fossilescorebackend.application.dto.request.OnlineAdSpendUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendBulkResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendReportResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.OnlineAdSpendService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Inversión diaria en publicidad vs venta online. Solo requiere sesión (SecurityConfig: anyRequest().authenticated()),
 * igual que {@code OnlineSaleController} y el resto de {@code /api/sales}; el acceso por rol/permiso lo controla el menú del frontend.
 */
@RestController
@RequestMapping("/api/sales/online/ad-spend")
@RequiredArgsConstructor
public class OnlineAdSpendController {

    private final OnlineAdSpendService onlineAdSpendService;

    @GetMapping
    public ResponseEntity<List<OnlineAdSpendEntryResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) throws BusinessException {
        return ResponseEntity.ok(onlineAdSpendService.list(startDate, endDate));
    }

    @PutMapping("/{date}")
    public ResponseEntity<OnlineAdSpendEntryResponse> upsert(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody OnlineAdSpendUpsertRequest request
    ) throws BusinessException {
        return ResponseEntity.ok(onlineAdSpendService.upsert(date, request.getAmount(), request.getNotes()));
    }

    @DeleteMapping("/{date}")
    public ResponseEntity<Void> delete(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) throws BusinessException {
        onlineAdSpendService.delete(date);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bulk")
    public ResponseEntity<OnlineAdSpendBulkResponse> bulk(@RequestBody OnlineAdSpendBulkRequest request)
            throws BusinessException {
        return ResponseEntity.ok(onlineAdSpendService.bulk(request.getEntries()));
    }

    @GetMapping("/report")
    public ResponseEntity<OnlineAdSpendReportResponse> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) throws BusinessException {
        return ResponseEntity.ok(onlineAdSpendService.report(startDate, endDate));
    }
}
