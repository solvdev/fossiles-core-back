package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.PublicMaterialMovementRequest;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialInventoryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.InventoryService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Locale;

@RestController
@RequestMapping("/api/public/inventory/materials")
@RequiredArgsConstructor
public class PublicInventoryController {

    private final InventoryService inventoryService;
    private final MaterialRepository materialRepository;

    @GetMapping("/{materialId}")
    public ResponseEntity<MaterialInventoryResponse> getMaterialInventory(@PathVariable Long materialId)
            throws ResourceNotFoundException {
        return ResponseEntity.ok(inventoryService.getMaterialInventory(materialId));
    }

    /**
     * Sin {@code page} ni {@code size}: lista completa (compatibilidad con clientes existentes).
     * Con cualquiera de los dos parámetros: respuesta paginada ({@code content}, {@code totalElements}, etc.).
     */
    @GetMapping("/{materialId}/kardex")
    public ResponseEntity<?> getMaterialKardex(
            @PathVariable Long materialId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (page != null || size != null) {
            int p = page != null ? page : 0;
            int s = size != null ? size : 30;
            return ResponseEntity.ok(inventoryService.getMaterialKardexPage(materialId, p, s));
        }
        return ResponseEntity.ok(inventoryService.getMaterialKardex(materialId));
    }

    @PostMapping("/{materialId}/movements")
    public ResponseEntity<MaterialInventoryResponse> createMovement(
            @PathVariable Long materialId,
            @Valid @RequestBody PublicMaterialMovementRequest request)
            throws ResourceNotFoundException, BusinessException {

        BigDecimal normalizedQuantity = resolveBaseQuantity(materialId, request);
        String normalizedType = request.getMovementType().trim().toUpperCase(Locale.ROOT);
        if ("IN".equals(normalizedType)) {
            return ResponseEntity.ok(
                    inventoryService.incrementMaterialInventory(
                            materialId,
                            normalizedQuantity,
                            null,
                            "PUBLIC",
                            null,
                            null,
                            request.getReason()
                    )
            );
        }

        if ("OUT".equals(normalizedType)) {
            String referenceType = request.getReferenceType() != null && !request.getReferenceType().isBlank()
                    ? request.getReferenceType().trim()
                    : "PUBLIC";
            return ResponseEntity.ok(
                    inventoryService.decrementMaterialInventory(
                            materialId,
                            normalizedQuantity,
                            null,
                            referenceType,
                            request.getReferenceId(),
                            request.getReferenceId() != null ? referenceType + "-" + request.getReferenceId() : null,
                            request.getReason()
                    )
            );
        }

        throw new BusinessException("Tipo de movimiento inválido. Use IN o OUT.");
    }

    private BigDecimal resolveBaseQuantity(Long materialId, PublicMaterialMovementRequest request)
            throws ResourceNotFoundException, BusinessException {
        if (request.getInputQuantity() == null) {
            if (request.getQuantity() == null) {
                throw new BusinessException("Debe enviar quantity o inputQuantity.");
            }
            return request.getQuantity();
        }

        MaterialEntity material = materialRepository.findById(materialId)
                .orElseThrow(() -> new ResourceNotFoundException("Material", materialId));

        if (request.getConversionFactorToBase() != null) {
            return request.getInputQuantity().multiply(request.getConversionFactorToBase());
        }

        if (request.getInputUomId() == null) {
            throw new BusinessException("Si envía inputQuantity debe indicar inputUomId o conversionFactorToBase.");
        }

        Long baseUomId = material.getManufacturingUomId() != null
                ? material.getManufacturingUomId()
                : material.getUomId();

        if (baseUomId != null && baseUomId.equals(request.getInputUomId())) {
            return request.getInputQuantity();
        }

        if (material.getPurchaseUomId() != null
                && material.getPurchaseUomId().equals(request.getInputUomId())
                && material.getPurchaseQuantity() != null
                && material.getPurchaseQuantity().compareTo(BigDecimal.ZERO) > 0) {
            return request.getInputQuantity().multiply(material.getPurchaseQuantity());
        }

        throw new BusinessException(
                "No hay conversión configurada para esa unidad. Use la unidad base del material, la de compra o envíe conversionFactorToBase.");
    }
}
