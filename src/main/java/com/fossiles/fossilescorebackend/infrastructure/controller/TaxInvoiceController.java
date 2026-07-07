package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ManualTaxInvoiceRequest;
import com.fossiles.fossilescorebackend.application.dto.request.UpdateTaxInvoiceFelMetadataRequest;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceAttemptResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceCertifiedXmlDownload;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceBackfillResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.TaxInvoiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

@RestController
@RequestMapping("/api/tax-invoices")
@RequiredArgsConstructor
public class TaxInvoiceController {

    private final TaxInvoiceService taxInvoiceService;

    @GetMapping
    public ResponseEntity<List<TaxInvoiceResponse>> list(
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerTaxId,
            @RequestParam(required = false) String certificationFilter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        return ResponseEntity.ok(taxInvoiceService.list(
                sourceType, status, customerTaxId, certificationFilter, fromDate, toDate));
    }

    @GetMapping("/summary")
    public ResponseEntity<TaxInvoiceSummaryResponse> summary() {
        return ResponseEntity.ok(taxInvoiceService.getSummary());
    }

    @PostMapping("/manual")
    public ResponseEntity<TaxInvoiceResponse> createManual(@Valid @RequestBody ManualTaxInvoiceRequest request)
            throws BusinessException {
        return ResponseEntity.ok(taxInvoiceService.issueManual(request));
    }

    @PostMapping("/from-kiosk-sale/{saleId}")
    public ResponseEntity<TaxInvoiceResponse> fromKioskSale(@PathVariable Long saleId)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(taxInvoiceService.issueFromKioskSaleId(saleId));
    }

    @PostMapping("/draft-from-kiosk-sale/{saleId}")
    public ResponseEntity<TaxInvoiceResponse> draftFromKioskSale(@PathVariable Long saleId)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(taxInvoiceService.createDraftFromKioskSaleId(saleId));
    }

    @GetMapping("/kiosk-sales/missing-count")
    public ResponseEntity<TaxInvoiceBackfillResponse> countMissingKioskSaleInvoices(
            @RequestParam(required = false) Long kioskLocationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) throws BusinessException {
        return ResponseEntity.ok(taxInvoiceService.backfillMissingKioskSaleDrafts(
                kioskLocationId,
                fromDate,
                toDate,
                true
        ));
    }

    @PostMapping("/kiosk-sales/backfill")
    public ResponseEntity<TaxInvoiceBackfillResponse> backfillKioskSales(
            @RequestParam(required = false) Long kioskLocationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) throws BusinessException {
        return ResponseEntity.ok(taxInvoiceService.backfillMissingKioskSaleDrafts(
                kioskLocationId,
                fromDate,
                toDate,
                false
        ));
    }

    /** @deprecated usar GET /kiosk-sales/missing-count y POST /kiosk-sales/backfill */
    @PostMapping("/backfill-kiosk-sales")
    public ResponseEntity<TaxInvoiceBackfillResponse> backfillKioskSalesLegacy(
            @RequestParam(required = false) Long kioskLocationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) throws BusinessException {
        return ResponseEntity.ok(taxInvoiceService.backfillMissingKioskSaleDrafts(
                kioskLocationId,
                fromDate,
                toDate,
                dryRun
        ));
    }

    @PostMapping("/from-online-sale/{saleId}")
    public ResponseEntity<TaxInvoiceResponse> fromOnlineSale(@PathVariable Long saleId)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(taxInvoiceService.issueFromOnlineSale(saleId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaxInvoiceResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        return ResponseEntity.ok(taxInvoiceService.getById(id));
    }

    @GetMapping("/{id}/attempts")
    public ResponseEntity<List<TaxInvoiceAttemptResponse>> getAttempts(@PathVariable Long id)
            throws ResourceNotFoundException {
        return ResponseEntity.ok(taxInvoiceService.getAttempts(id));
    }

    @GetMapping("/{id}/certified-xml")
    public ResponseEntity<byte[]> downloadCertifiedXml(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        TaxInvoiceCertifiedXmlDownload download = taxInvoiceService.getCertifiedXmlDownload(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.getFilename() + "\"")
                .contentType(MediaType.parseMediaType(download.getContentType()))
                .body(download.getContent());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<TaxInvoiceResponse> retry(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(taxInvoiceService.retry(id));
    }

    @PatchMapping("/{id}/fel-metadata")
    public ResponseEntity<TaxInvoiceResponse> updateFelMetadata(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTaxInvoiceFelMetadataRequest request)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(taxInvoiceService.updateFelMetadata(id, request));
    }
}
