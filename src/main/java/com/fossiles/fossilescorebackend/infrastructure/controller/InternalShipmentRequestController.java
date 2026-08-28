package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.InternalShipmentRequestCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.InternalShipmentRequestRejectRequest;
import com.fossiles.fossilescorebackend.application.dto.request.InternalShipmentRequestSlipPrintRequest;
import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentEligibilityResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentRequestResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentRequestSlipPrintResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentRequestSlipSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.InternalShipmentRequestService;
import com.fossiles.fossilescorebackend.application.service.InternalShipmentRequestSlipService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/internal-shipment-requests")
@RequiredArgsConstructor
public class InternalShipmentRequestController {

    private final InternalShipmentRequestService internalShipmentRequestService;
    private final InternalShipmentRequestSlipService internalShipmentRequestSlipService;

    @GetMapping
    public ResponseEntity<List<InternalShipmentRequestResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String requestType) throws BusinessException {
        return ResponseEntity.ok(internalShipmentRequestService.list(status, requestType));
    }

    @GetMapping("/existing-envi")
    public ResponseEntity<List<ProductShipmentResponse>> listExistingEnvi() throws BusinessException {
        return ResponseEntity.ok(internalShipmentRequestService.listExistingEnvi());
    }

    @GetMapping("/{id}")
    public ResponseEntity<InternalShipmentRequestResponse> getById(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(internalShipmentRequestService.getById(id));
    }

    @PostMapping
    public ResponseEntity<InternalShipmentRequestResponse> create(
            @Valid @RequestBody InternalShipmentRequestCreateRequest request)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(internalShipmentRequestService.createRequest(request));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<InternalShipmentRequestResponse> approve(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(internalShipmentRequestService.approve(id));
    }

    @PostMapping("/{id}/authorize-production")
    public ResponseEntity<InternalShipmentRequestResponse> authorizeProduction(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(internalShipmentRequestService.authorizeProduction(id));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<InternalShipmentRequestResponse> reject(
            @PathVariable Long id,
            @Valid @RequestBody InternalShipmentRequestRejectRequest request)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(internalShipmentRequestService.reject(id, request.getReason()));
    }

    @GetMapping("/employees/{employeeId}/internal-shipment-eligibility")
    public ResponseEntity<InternalShipmentEligibilityResponse> getEmployeePlanillaEligibility(
            @PathVariable Long employeeId,
            @RequestParam(required = false) String month)
            throws ResourceNotFoundException {
        return ResponseEntity.ok(internalShipmentRequestService.getEmployeePlanillaEligibility(employeeId, month));
    }

    @PostMapping("/slips/print")
    public ResponseEntity<InternalShipmentRequestSlipPrintResponse> printSlips(
            @Valid @RequestBody(required = false) InternalShipmentRequestSlipPrintRequest request)
            throws BusinessException {
        int qty = (request != null && request.getQuantity() != null) ? request.getQuantity() : 50;
        return ResponseEntity.ok(internalShipmentRequestSlipService.printBatch(qty));
    }

    @GetMapping("/slips/summary")
    public ResponseEntity<InternalShipmentRequestSlipSummaryResponse> getSlipSummary()
            throws BusinessException {
        return ResponseEntity.ok(internalShipmentRequestSlipService.getSummary());
    }
}
