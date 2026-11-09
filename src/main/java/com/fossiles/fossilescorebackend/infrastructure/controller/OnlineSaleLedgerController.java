package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.BulkUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LedgerResponse;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LedgerRow;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.onlineledger.OnlineSaleLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Libro de ventas en línea (hoja de Contabilidad). Va fuera de {@code /api/online-sales} a propósito: anotar un dato
 * contable no cambia la venta, así que no debe vaciar las cachés de la vista de ventas.
 */
@RestController
@RequestMapping("/api/online-sales-ledger")
@RequiredArgsConstructor
public class OnlineSaleLedgerController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final OnlineSaleLedgerService service;

    @GetMapping
    public ResponseEntity<LedgerResponse> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q) throws BusinessException {
        service.assertCanView();
        return ResponseEntity.ok(service.search(from, to, q));
    }

    @PutMapping("/{saleId}")
    public ResponseEntity<LedgerRow> update(@PathVariable Long saleId, @RequestBody Map<String, Object> changes)
            throws BusinessException, ResourceNotFoundException {
        service.assertCanEdit();
        return ResponseEntity.ok(service.update(saleId, changes));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<LedgerRow>> bulk(@RequestBody BulkUpdateRequest request) throws BusinessException {
        service.assertCanEdit();
        return ResponseEntity.ok(service.bulkUpdate(request));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) throws BusinessException {
        service.assertCanView();
        OnlineSaleLedgerService.ExportFile file = service.export(from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition")
                .contentType(MediaType.parseMediaType(XLSX))
                .body(file.bytes());
    }
}
