package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountDocumentSettlementRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryVoidRequest;
import com.fossiles.fossilescorebackend.application.dto.response.*;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.CustomerAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/customer-accounts")
@RequiredArgsConstructor
public class CustomerAccountController {

    private final CustomerAccountService customerAccountService;

    @GetMapping("/summary")
    public ResponseEntity<List<CustomerAccountSummaryResponse>> getSummary(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "true") boolean luisFelipeOnly,
            @RequestParam(defaultValue = "false") boolean positiveBalanceOnly,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) Integer routeNumber,
            @RequestParam(required = false) String routeLocationCode) {
        return ResponseEntity.ok(customerAccountService.getSummary(
                search, luisFelipeOnly, positiveBalanceOnly, regionCode, routeNumber, routeLocationCode));
    }

    @GetMapping("/print-report")
    public ResponseEntity<CustomerAccountPrintReportResponse> getPrintReport(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "true") boolean luisFelipeOnly,
            @RequestParam(defaultValue = "false") boolean positiveBalanceOnly,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) Integer routeNumber,
            @RequestParam(required = false) String routeLocationCode)
            throws ResourceNotFoundException {
        return ResponseEntity.ok(customerAccountService.getPrintReport(
                search, luisFelipeOnly, positiveBalanceOnly, from, to, regionCode, routeNumber, routeLocationCode));
    }

    @GetMapping("/customers/{customerId}/balance")
    public ResponseEntity<CustomerAccountBalanceResponse> getBalance(@PathVariable Long customerId)
            throws ResourceNotFoundException {
        return ResponseEntity.ok(customerAccountService.getBalance(customerId));
    }

    @GetMapping("/customers/{customerId}/statement")
    public ResponseEntity<CustomerAccountStatementResponse> getStatement(
            @PathVariable Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to)
            throws ResourceNotFoundException {
        return ResponseEntity.ok(customerAccountService.getStatement(customerId, from, to));
    }

    @GetMapping("/customers/{customerId}/lf-documents")
    public ResponseEntity<List<LfSalesDocumentResponse>> getLfDocuments(
            @PathVariable Long customerId,
            @RequestParam(defaultValue = "false") boolean withBalance)
            throws ResourceNotFoundException {
        return ResponseEntity.ok(customerAccountService.getLfDocuments(customerId, withBalance));
    }

    @GetMapping("/customers/{customerId}/receivable-documents")
    public ResponseEntity<List<LfReceivableDocumentResponse>> getReceivableDocuments(
            @PathVariable Long customerId,
            @RequestParam(required = false) String orderKind)
            throws ResourceNotFoundException {
        return ResponseEntity.ok(customerAccountService.getReceivableDocuments(customerId, orderKind));
    }

    @PostMapping("/customers/{customerId}/entries/document-settlement")
    public ResponseEntity<CustomerAccountDocumentSettlementResponse> createDocumentSettlement(
            @PathVariable Long customerId,
            @Valid @RequestBody CustomerAccountDocumentSettlementRequest request)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(customerAccountService.createDocumentSettlement(customerId, request));
    }

    @PostMapping("/customers/{customerId}/entries")
    public ResponseEntity<CustomerAccountEntryResponse> createEntry(
            @PathVariable Long customerId,
            @Valid @RequestBody CustomerAccountEntryRequest request)
            throws ResourceNotFoundException, BusinessException {
        CustomerAccountEntryResponse created = customerAccountService.createEntry(customerId, request);
        return ResponseEntity.created(URI.create("/api/customer-accounts/entries/" + created.getId())).body(created);
    }

    @PutMapping("/entries/{entryId}/void")
    public ResponseEntity<CustomerAccountEntryResponse> voidEntry(
            @PathVariable Long entryId,
            @Valid @RequestBody CustomerAccountEntryVoidRequest request)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(customerAccountService.voidEntry(entryId, request));
    }
}
