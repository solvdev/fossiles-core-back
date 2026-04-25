package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.MinorExpenseRequest;
import com.fossiles.fossilescorebackend.application.dto.response.MinorExpenseResponse;
import com.fossiles.fossilescorebackend.application.dto.response.MinorExpenseSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.MinorExpenseService;
import com.fossiles.fossilescorebackend.infrastructure.service.S3StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/minor-expenses")
@RequiredArgsConstructor
public class MinorExpenseController {

    private final MinorExpenseService minorExpenseService;
    private final S3StorageService s3StorageService;

    @PostMapping
    public ResponseEntity<MinorExpenseResponse> create(
            @Valid @RequestBody MinorExpenseRequest request) throws BusinessException, ResourceNotFoundException {
        MinorExpenseResponse response = minorExpenseService.createMinorExpense(request);
        return ResponseEntity.created(URI.create("/api/minor-expenses/" + response.getId()))
                .body(response);
    }

    @GetMapping
    public ResponseEntity<List<MinorExpenseResponse>> getAll(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String supplier,
            @RequestParam(required = false) String purchaserName,
            @RequestParam(required = false) String reimbursementStatus,
            @RequestParam(required = false) String invoiceNumber,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Long purchaseNumberId) {
        List<MinorExpenseResponse> expenses = minorExpenseService.getAllMinorExpenses(
                startDate, endDate, supplier, purchaserName, 
                reimbursementStatus, invoiceNumber, description, purchaseNumberId);
        return ResponseEntity.ok(expenses);
    }
    
    @GetMapping("/by-purchase-number/{purchaseNumberId}")
    public ResponseEntity<List<MinorExpenseResponse>> getByPurchaseNumber(@PathVariable Long purchaseNumberId) {
        List<MinorExpenseResponse> expenses = minorExpenseService.getExpensesByPurchaseNumberId(purchaseNumberId);
        return ResponseEntity.ok(expenses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MinorExpenseResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        MinorExpenseResponse response = minorExpenseService.getMinorExpenseById(id);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MinorExpenseResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody MinorExpenseRequest request) throws BusinessException, ResourceNotFoundException {
        MinorExpenseResponse response = minorExpenseService.updateMinorExpense(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws BusinessException, ResourceNotFoundException {
        minorExpenseService.deleteMinorExpense(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/reimbursements/pending")
    public ResponseEntity<List<MinorExpenseResponse>> getPendingReimbursements() {
        List<MinorExpenseResponse> expenses = minorExpenseService.getPendingReimbursements();
        return ResponseEntity.ok(expenses);
    }

    @PutMapping("/reimbursements/{id}/mark-paid")
    public ResponseEntity<MinorExpenseResponse> markReimbursementAsPaid(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDate,
            @RequestParam(required = false) String paymentMethod) throws BusinessException, ResourceNotFoundException {
        MinorExpenseResponse response = minorExpenseService.markReimbursementAsPaid(id, paymentDate, paymentMethod);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reimbursements/history/{personName}")
    public ResponseEntity<List<MinorExpenseResponse>> getReimbursementHistory(@PathVariable String personName) {
        List<MinorExpenseResponse> expenses = minorExpenseService.getReimbursementHistoryByPerson(personName);
        return ResponseEntity.ok(expenses);
    }

    @GetMapping("/summary")
    public ResponseEntity<MinorExpenseSummaryResponse> getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        MinorExpenseSummaryResponse summary = minorExpenseService.getSummary(startDate, endDate);
        return ResponseEntity.ok(summary);
    }

    @PostMapping("/{id}/upload-invoice")
    public ResponseEntity<MinorExpenseResponse> uploadInvoice(
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file) throws IOException, ResourceNotFoundException, BusinessException {
        // Validar que el gasto existe
        MinorExpenseResponse expense = minorExpenseService.getMinorExpenseById(id);
        
        // Subir archivo a S3 (acepta PDF o imágenes)
        S3StorageService.UploadResult result;
        String contentType = file.getContentType();
        
        if (contentType != null && contentType.equals("application/pdf")) {
            result = s3StorageService.uploadPDF(file);
        } else if (contentType != null && contentType.startsWith("image/")) {
            result = s3StorageService.uploadImage(file);
        } else {
            throw new BusinessException("El archivo debe ser un PDF o una imagen");
        }
        
        // Actualizar el gasto con la URL del archivo
        MinorExpenseRequest updateRequest = MinorExpenseRequest.builder()
                .invoiceNumber(expense.getInvoiceNumber())
                .purchaseDate(expense.getPurchaseDate())
                .description(expense.getDescription())
                .supplier(expense.getSupplier())
                .totalAmount(expense.getTotalAmount())
                .purchaserName(expense.getPurchaserName())
                .authorizerName(expense.getAuthorizerName())
                .companyAmount(expense.getCompanyAmount())
                .messengerAmount(expense.getMessengerAmount())
                .initialAmountGiven(expense.getInitialAmountGiven())
                .returnedAmount(expense.getReturnedAmount())
                .reimbursementStatus(expense.getReimbursementStatus())
                .reimbursementDate(expense.getReimbursementDate())
                .reimbursementPaymentMethod(expense.getReimbursementPaymentMethod())
                .initialPaymentMethod(expense.getInitialPaymentMethod())
                .observations(expense.getObservations())
                .invoiceFileUrl(result.getUrl())
                .build();
        
        MinorExpenseResponse updated = minorExpenseService.updateMinorExpense(id, updateRequest);
        return ResponseEntity.ok(updated);
    }
}

