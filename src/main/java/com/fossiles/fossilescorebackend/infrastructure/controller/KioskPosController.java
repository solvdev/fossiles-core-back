package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.KioskCashExpenseRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskCashSessionCloseRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskCashSessionOpenRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeRejectRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeCompleteRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangePreviewRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskSimpleReturnRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosDepositSlipUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosPromotionEstimateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleInvoiceContactRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSalePaymentUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRestoreRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskSaleVoidRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPromotionRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCashExpenseResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCashSessionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCashSessionDailySummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCustomerProfileResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangeCompleteResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangePreviewResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangeSlipResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPendingDepositSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosContextResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPromotionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosManagerDashboardResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosPromotionEstimateResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosReportsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosSaleResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskProductAvailabilityResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxpayerLookupResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.KioskExchangeService;
import com.fossiles.fossilescorebackend.application.service.KioskPosService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/kiosk-pos")
@RequiredArgsConstructor
public class KioskPosController {

    private final KioskPosService kioskPosService;
    private final KioskExchangeService kioskExchangeService;

    @GetMapping("/context")
    public ResponseEntity<KioskPosContextResponse> getContext(
            @RequestParam(required = false) Long kioskLocationId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String colorName
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getCurrentContext(kioskLocationId, search, categoryId, colorName));
    }

    @PostMapping("/sales")
    public ResponseEntity<KioskPosSaleResponse> createSale(@Valid @RequestBody KioskPosSaleRequest request)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.createSale(request));
    }

    @PostMapping("/sales/restore")
    public ResponseEntity<KioskPosSaleResponse> restoreSale(@Valid @RequestBody KioskPosSaleRestoreRequest request)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.restoreSale(request));
    }

    @GetMapping("/sales/{id}")
    public ResponseEntity<KioskPosSaleResponse> getSaleById(
            @PathVariable Long id,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.getSaleById(id, kioskLocationId));
    }

    @PostMapping("/sales/{id}/void")
    public ResponseEntity<KioskPosSaleResponse> voidSale(
            @PathVariable Long id,
            @RequestParam(required = false) Long kioskLocationId,
            @Valid @RequestBody KioskSaleVoidRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.voidSale(id, kioskLocationId, request));
    }

    @PatchMapping("/sales/{id}/payment")
    public ResponseEntity<KioskPosSaleResponse> updateSalePayment(
            @PathVariable Long id,
            @RequestParam(required = false) Long kioskLocationId,
            @RequestBody KioskPosSalePaymentUpdateRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.updateSalePayment(id, kioskLocationId, request));
    }

    @PatchMapping("/sales/{id}/invoice-contact")
    public ResponseEntity<KioskPosSaleResponse> updateSaleInvoiceContact(
            @PathVariable Long id,
            @RequestParam(required = false) Long kioskLocationId,
            @RequestBody KioskPosSaleInvoiceContactRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.updateSaleInvoiceContact(id, kioskLocationId, request));
    }

    @PutMapping("/sales/{id}/deposit-slip")
    public ResponseEntity<KioskPosSaleResponse> registerDepositSlip(
            @PathVariable Long id,
            @RequestParam(required = false) Long kioskLocationId,
            @Valid @RequestBody KioskPosDepositSlipUpdateRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.registerDepositSlip(id, kioskLocationId, request));
    }

    @GetMapping("/deposits/pending-summary")
    public ResponseEntity<KioskPendingDepositSummaryResponse> getPendingDepositSummary(
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getPendingDepositSummary(kioskLocationId));
    }

    @GetMapping("/taxpayers/lookup")
    public ResponseEntity<TaxpayerLookupResponse> lookupTaxpayer(@RequestParam String taxId)
            throws BusinessException {
        return ResponseEntity.ok(kioskPosService.lookupTaxpayer(taxId));
    }

    @GetMapping("/customers/by-tax-id")
    public ResponseEntity<KioskCustomerProfileResponse> getCustomerByTaxId(@RequestParam String taxId)
            throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getCustomerByTaxId(taxId));
    }

    @GetMapping("/sales/my-kiosk")
    public ResponseEntity<List<KioskPosSaleResponse>> getMyKioskSales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getCurrentKioskSales(startDate, endDate, kioskLocationId));
    }

    @GetMapping("/promotions")
    public ResponseEntity<List<KioskPromotionResponse>> getPromotions(
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getPromotions(activeOnly, kioskLocationId));
    }

    @PostMapping("/promotions/estimate")
    public ResponseEntity<KioskPosPromotionEstimateResponse> estimatePromotionDiscount(
            @Valid @RequestBody KioskPosPromotionEstimateRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.estimatePromotionDiscount(request));
    }

    @PostMapping("/promotions")
    public ResponseEntity<KioskPromotionResponse> createPromotion(@RequestBody KioskPromotionRequest request)
            throws BusinessException {
        return ResponseEntity.ok(kioskPosService.createPromotion(request));
    }

    @PutMapping("/promotions/{id}")
    public ResponseEntity<KioskPromotionResponse> updatePromotion(
            @PathVariable Long id,
            @RequestBody KioskPromotionRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.updatePromotion(id, request));
    }

    @DeleteMapping("/promotions/{id}")
    public ResponseEntity<Void> deletePromotion(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        kioskPosService.deletePromotion(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dashboard/manager")
    public ResponseEntity<KioskPosManagerDashboardResponse> getManagerDashboard(
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getManagerDashboard(kioskLocationId));
    }

    @GetMapping("/reports/my-kiosk")
    public ResponseEntity<KioskPosReportsResponse> getMyKioskReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getCurrentKioskReport(startDate, endDate, kioskLocationId));
    }

    @GetMapping("/reports/general")
    public ResponseEntity<KioskPosReportsResponse> getGeneralReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getGeneralReport(startDate, endDate));
    }

    @GetMapping("/availability")
    public ResponseEntity<List<KioskProductAvailabilityResponse>> getAvailability(
            @RequestParam Long productId,
            @RequestParam(required = false) Long colorId,
            @RequestParam(defaultValue = "false") boolean includeCurrentKiosk,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(
                kioskPosService.findAvailabilityInKiosks(productId, colorId, includeCurrentKiosk, kioskLocationId)
        );
    }

    @GetMapping("/cash-session/current")
    public ResponseEntity<KioskCashSessionResponse> getCurrentCashSession(
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        KioskCashSessionResponse session = kioskPosService.getCurrentCashSession(kioskLocationId);
        if (session == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(session);
    }

    @PostMapping("/cash-session/open")
    public ResponseEntity<KioskCashSessionResponse> openCashSession(
            @RequestBody(required = false) KioskCashSessionOpenRequest request
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.openCashSession(request));
    }

    @PostMapping("/cash-session/{id}/close")
    public ResponseEntity<KioskCashSessionResponse> closeCashSession(
            @PathVariable Long id,
            @RequestBody KioskCashSessionCloseRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.closeCashSession(id, request));
    }

    @GetMapping("/cash-session/{id}/expenses")
    public ResponseEntity<List<KioskCashExpenseResponse>> listCashExpenses(
            @PathVariable Long id
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.listCashExpenses(id));
    }

    @PostMapping("/cash-session/{id}/expenses")
    public ResponseEntity<KioskCashExpenseResponse> addCashExpense(
            @PathVariable Long id,
            @Valid @RequestBody KioskCashExpenseRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskPosService.addCashExpense(id, request));
    }

    @GetMapping("/cash-session/daily-summary")
    public ResponseEntity<List<KioskCashSessionDailySummaryResponse>> getCashSessionDailySummaries(
            @RequestParam(required = false) Long kioskLocationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) throws BusinessException {
        return ResponseEntity.ok(kioskPosService.getCashSessionDailySummaries(kioskLocationId, startDate, endDate));
    }

    @GetMapping("/exchanges")
    public ResponseEntity<List<KioskExchangeSlipResponse>> listExchanges(
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskExchangeService.listExchanges(kioskLocationId));
    }

    @GetMapping("/exchanges/pending-reintegros")
    public ResponseEntity<List<KioskExchangeSlipResponse>> listPendingReintegros(
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskExchangeService.listPendingReintegros(kioskLocationId));
    }

    @GetMapping("/exchanges/pending-authorizations")
    public ResponseEntity<List<KioskExchangeSlipResponse>> listPendingAuthorizations(
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException {
        return ResponseEntity.ok(kioskExchangeService.listPendingAuthorizations(kioskLocationId));
    }

    @GetMapping("/exchanges/{id}")
    public ResponseEntity<KioskExchangeSlipResponse> getExchangeById(
            @PathVariable Long id,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskExchangeService.getExchangeById(id, kioskLocationId));
    }

    @PostMapping("/exchanges/preview")
    public ResponseEntity<KioskExchangePreviewResponse> previewExchange(
            @Valid @RequestBody KioskExchangePreviewRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskExchangeService.previewExchange(request));
    }

    @PostMapping("/exchanges")
    public ResponseEntity<KioskExchangeCompleteResponse> completeExchange(
            @Valid @RequestBody KioskExchangeCompleteRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskExchangeService.completeExchange(request));
    }

    @PostMapping("/returns")
    public ResponseEntity<KioskExchangeSlipResponse> completeSimpleReturn(
            @Valid @RequestBody KioskSimpleReturnRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskExchangeService.completeSimpleReturn(request));
    }

    @PostMapping("/exchanges/{id}/reintegrate")
    public ResponseEntity<KioskExchangeSlipResponse> reintegrateExchange(
            @PathVariable Long id,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskExchangeService.reintegrate(id, kioskLocationId));
    }

    @PostMapping("/exchanges/{id}/authorize")
    public ResponseEntity<KioskExchangeSlipResponse> authorizeExchange(
            @PathVariable Long id,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskExchangeService.authorizeExchange(id, kioskLocationId));
    }

    @PostMapping("/exchanges/{id}/reject")
    public ResponseEntity<KioskExchangeSlipResponse> rejectExchange(
            @PathVariable Long id,
            @RequestParam(required = false) Long kioskLocationId,
            @Valid @RequestBody KioskExchangeRejectRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskExchangeService.rejectExchange(id, kioskLocationId, request));
    }

    @GetMapping("/sales/lookup")
    public ResponseEntity<KioskPosSaleResponse> lookupSale(
            @RequestParam String query,
            @RequestParam(required = false) Long kioskLocationId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioskExchangeService.lookupSale(kioskLocationId, query));
    }
}
