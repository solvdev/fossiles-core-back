package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.OpcShipmentGenerateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.ProductionOrderPartialReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Operaciones sobre una liberación parcial ya creada (por id de liberación).
 * Listado y alta van en {@link ProductionOrderController} bajo /{orderId}/partial-releases.
 */
@RestController
@RequestMapping("/api/partial-releases")
@RequiredArgsConstructor
public class ProductionOrderPartialReleaseController {

    private final ProductionOrderPartialReleaseService partialReleaseService;

    @PutMapping("/{releaseId}")
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<PartialReleaseResponse> update(
            @PathVariable Long releaseId,
            @RequestBody PartialReleaseUpsertRequest request) throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(partialReleaseService.updateRelease(releaseId, request));
    }

    @DeleteMapping("/{releaseId}")
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<Void> delete(@PathVariable Long releaseId)
            throws ResourceNotFoundException, BusinessException {
        partialReleaseService.deleteDraft(releaseId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{releaseId}/generate-shipment")
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<ProductShipmentResponse> generateShipment(
            @PathVariable Long releaseId,
            @RequestBody(required = false) OpcShipmentGenerateRequest request)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(partialReleaseService.generateShipment(releaseId, request));
    }
}
