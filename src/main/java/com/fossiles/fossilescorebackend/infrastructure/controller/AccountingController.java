package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.AccountingEntryResponse;
import com.fossiles.fossilescorebackend.application.service.AccountingService;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/accounting-entries")
@RequiredArgsConstructor
public class AccountingController {

    private final AccountingService accountingService;

    @GetMapping("/document/{documentType}/{documentId}")
    public ResponseEntity<List<AccountingEntryResponse>> getEntriesByDocument(
            @PathVariable String documentType,
            @PathVariable Long documentId) {
        List<AccountingEntryResponse> entries = accountingService.getEntriesByDocument(documentType, documentId);
        return ResponseEntity.ok(entries);
    }

    @GetMapping("/document-type/{documentType}")
    public ResponseEntity<List<AccountingEntryResponse>> getEntriesByDocumentType(
            @PathVariable String documentType) {
        List<AccountingEntryResponse> entries = accountingService.getEntriesByDocumentType(documentType);
        return ResponseEntity.ok(entries);
    }

    @GetMapping("/account/{accountCode}")
    public ResponseEntity<List<AccountingEntryResponse>> getEntriesByAccount(
            @PathVariable String accountCode) {
        List<AccountingEntryResponse> entries = accountingService.getEntriesByAccount(accountCode);
        return ResponseEntity.ok(entries);
    }

    @GetMapping("/date-range")
    public ResponseEntity<List<AccountingEntryResponse>> getEntriesByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        List<AccountingEntryResponse> entries = accountingService.getEntriesByDateRange(startDate, endDate);
        return ResponseEntity.ok(entries);
    }

    @GetMapping
    public ResponseEntity<List<AccountingEntryResponse>> getAllEntries(
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String accountCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) throws BusinessException {
        
        List<AccountingEntryResponse> entries = accountingService.searchEntries(documentType, accountCode, startDate, endDate);
        
        return ResponseEntity.ok(entries);
    }
}

