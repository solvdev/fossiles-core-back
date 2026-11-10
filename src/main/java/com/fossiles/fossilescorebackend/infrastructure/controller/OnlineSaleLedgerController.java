package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.BulkUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LedgerResponse;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LedgerRow;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LockRequest;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.PeriodRequest;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.PeriodRow;
import com.fossiles.fossilescorebackend.application.service.onlineledger.OnlineLedgerPeriodService;
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
    private final OnlineLedgerPeriodService periodService;

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

    /** Bloquea o desbloquea líneas. Bloquear: quien edita el libro. Desbloquear: solo administradores. */
    @PostMapping("/lock")
    public ResponseEntity<List<LedgerRow>> lock(@RequestBody LockRequest request) throws BusinessException {
        return ResponseEntity.ok(service.setLocked(request));
    }

    // ── Periodos de revisión: abrir, cerrar (bloquea el rango), reabrir (solo admin) ──

    @GetMapping("/periods")
    public ResponseEntity<List<PeriodRow>> periods() throws BusinessException {
        service.assertCanView();
        return ResponseEntity.ok(periodService.list());
    }

    @PostMapping("/periods")
    public ResponseEntity<PeriodRow> openPeriod(@RequestBody PeriodRequest request) throws BusinessException {
        return ResponseEntity.ok(periodService.open(request));
    }

    @PostMapping("/periods/{id}/close")
    public ResponseEntity<PeriodRow> closePeriod(@PathVariable Long id) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(periodService.close(id));
    }

    @PostMapping("/periods/{id}/reopen")
    public ResponseEntity<PeriodRow> reopenPeriod(@PathVariable Long id) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(periodService.reopen(id));
    }

    @DeleteMapping("/periods/{id}")
    public ResponseEntity<Void> deletePeriod(@PathVariable Long id) throws BusinessException, ResourceNotFoundException {
        periodService.delete(id);
        return ResponseEntity.noContent().build();
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
