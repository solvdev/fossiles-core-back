package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.MaterialRequest;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialResponse;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialStickerResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UomEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UomRepository;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.HashMap;

@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialRepository materialRepository;
    private final UomRepository uomRepository;
    private final SupplierRepository supplierRepository;
    
    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    @GetMapping
    public ResponseEntity<List<MaterialResponse>> getAll() {
        List<MaterialResponse> materials = materialRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(materials);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MaterialResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        MaterialEntity entity = materialRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<MaterialResponse> create(@Valid @RequestBody MaterialRequest request) 
            throws BusinessException {
        if (request.getSku() != null && materialRepository.existsBySku(request.getSku())) {
            throw new BusinessException("Material SKU already exists: " + request.getSku());
        }
        MaterialEntity entity = toEntity(request);
        if (entity.getStatus() == null) {
            entity.setStatus("active");
        }
        // unitCost se calculará automáticamente en @PrePersist
        MaterialEntity saved = materialRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/materials/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MaterialResponse> update(@PathVariable Long id, @Valid @RequestBody MaterialRequest request) 
            throws ResourceNotFoundException, BusinessException {
        MaterialEntity entity = materialRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material", id));
        
        if (request.getSku() != null && !entity.getSku().equals(request.getSku()) 
                && materialRepository.existsBySku(request.getSku())) {
            throw new BusinessException("Material SKU already exists: " + request.getSku());
        }
        
        updateEntity(entity, request);
        // unitCost se calculará automáticamente en @PreUpdate
        MaterialEntity updated = materialRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!materialRepository.existsById(id)) {
            throw new ResourceNotFoundException("Material", id);
        }
        materialRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public ResponseEntity<List<MaterialResponse>> searchMaterials(
            @RequestParam String query,
            @RequestParam(defaultValue = "true") Boolean activeOnly) {
        List<MaterialEntity> materials;
        if (activeOnly) {
            materials = materialRepository.searchActiveBySkuOrName(query);
        } else {
            materials = materialRepository.searchBySkuOrName(query);
        }
        List<MaterialResponse> responses = materials.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}/sticker")
    public ResponseEntity<MaterialStickerResponse> getStickerData(
            @PathVariable Long id,
            HttpServletRequest request) throws ResourceNotFoundException {
        MaterialEntity entity = materialRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material", id));

        // Si la configuración trae localhost, usar el origen real de la petición cuando esté disponible.
        String resolvedFrontendUrl = resolveFrontendBaseUrl(request);

        // El QR contendrá la URL completa para acceder al kardex del material
        String qrUrl = resolvedFrontendUrl + "/admin/materials-kardex/" + entity.getId();
        
        MaterialStickerResponse response = MaterialStickerResponse.builder()
                .materialId(entity.getId())
                .sku(entity.getSku())
                .name(entity.getName())
                .qrData(qrUrl) // URL completa para el QR
                .build();
        
        return ResponseEntity.ok(response);
    }

    private boolean isLocalhostUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("localhost") || lower.contains("127.0.0.1");
    }

    private String resolveFrontendBaseUrl(HttpServletRequest request) {
        if (!isLocalhostUrl(frontendUrl)) {
            return frontendUrl;
        }

        String origin = request.getHeader("Origin");
        if (origin != null && !isLocalhostUrl(origin)) {
            return origin;
        }

        String referer = request.getHeader("Referer");
        String refererBase = extractBaseUrl(referer);
        if (refererBase != null && !isLocalhostUrl(refererBase)) {
            return refererBase;
        }

        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (forwardedProto != null && forwardedHost != null) {
            String forwardedBase = forwardedProto + "://" + forwardedHost;
            if (!isLocalhostUrl(forwardedBase)) {
                return forwardedBase;
            }
        }

        return frontendUrl;
    }

    private String extractBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            StringBuilder base = new StringBuilder();
            base.append(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() != -1) {
                base.append(":").append(uri.getPort());
            }
            return base.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private MaterialResponse toResponse(MaterialEntity entity) {
        // Obtener información de UOMs
        UomEntity purchaseUom = entity.getPurchaseUomId() != null 
            ? uomRepository.findById(entity.getPurchaseUomId()).orElse(null) : null;
        UomEntity manufacturingUom = entity.getManufacturingUomId() != null 
            ? uomRepository.findById(entity.getManufacturingUomId()).orElse(null) : null;
        
        // Obtener información del proveedor
        SupplierEntity supplier = entity.getSupplierId() != null 
            ? supplierRepository.findById(entity.getSupplierId()).orElse(null) : null;
        
        // Construir textos de conversión
        String conversionText = null;
        String priceBreakdown = null;
        if (purchaseUom != null && manufacturingUom != null && entity.getPurchaseQuantity() != null) {
            conversionText = String.format("1 %s = %s %s", 
                purchaseUom.getName(), 
                entity.getPurchaseQuantity().stripTrailingZeros().toPlainString(),
                manufacturingUom.getName());
            
            if (entity.getPurchasePrice() != null && entity.getUnitCost() != null) {
                priceBreakdown = String.format("Q %.2f/%s = Q %.2f/%s",
                    entity.getPurchasePrice(),
                    purchaseUom.getName(),
                    entity.getUnitCost(),
                    manufacturingUom.getName());
            }
        }
        
        return MaterialResponse.builder()
                .id(entity.getId())
                .sku(entity.getSku())
                .name(entity.getName())
                // Información de compra
                .purchaseUomId(entity.getPurchaseUomId())
                .purchaseUomName(purchaseUom != null ? purchaseUom.getName() : null)
                .purchaseUomCode(purchaseUom != null ? purchaseUom.getCode() : null)
                .purchaseQuantity(entity.getPurchaseQuantity())
                .purchasePrice(entity.getPurchasePrice())
                // Información de manufactura
                .manufacturingUomId(entity.getManufacturingUomId())
                .manufacturingUomName(manufacturingUom != null ? manufacturingUom.getName() : null)
                .manufacturingUomCode(manufacturingUom != null ? manufacturingUom.getCode() : null)
                .unitCost(entity.getUnitCost())
                // Textos calculados
                .conversionText(conversionText)
                .priceBreakdown(priceBreakdown)
                // Campos legacy
                .uomId(entity.getUomId())
                .quantity(entity.getQuantity())
                .cost(entity.getCost())
                // Otros campos
                .min(entity.getMin())
                .max(entity.getMax())
                .deliveryDays(entity.getDeliveryDays())
                .materialColorId(entity.getMaterialColorId())
                .supplierId(entity.getSupplierId())
                .supplierName(supplier != null ? supplier.getName() : null)
                .description(entity.getDescription())
                .imageUrl(entity.getImageUrl())
                .status(entity.getStatus())
                .lossPercentage(entity.getLossPercentage())
                .isPrimaryLeather(Boolean.TRUE.equals(entity.getIsPrimaryLeather()))
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private MaterialEntity toEntity(MaterialRequest request) {
        MaterialEntity entity = MaterialEntity.builder()
                .sku(request.getSku())
                .name(request.getName())
                // Nuevos campos
                .purchaseUomId(request.getPurchaseUomId())
                .purchaseQuantity(request.getPurchaseQuantity())
                .purchasePrice(request.getPurchasePrice())
                .manufacturingUomId(request.getManufacturingUomId())
                // unitCost se calculará automáticamente en @PrePersist
                // Campos legacy (mantener por compatibilidad)
                .uomId(request.getUomId())
                .quantity(request.getQuantity())
                .cost(request.getCost())
                // Otros campos
                .min(request.getMin())
                .max(request.getMax())
                .deliveryDays(request.getDeliveryDays())
                .materialColorId(request.getMaterialColorId())
                .supplierId(request.getSupplierId())
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .status(request.getStatus())
                .lossPercentage(request.getLossPercentage())
                .isPrimaryLeather(Boolean.TRUE.equals(request.getIsPrimaryLeather()))
                .build();
        return entity;
    }

    private void updateEntity(MaterialEntity entity, MaterialRequest request) {
        if (request.getSku() != null) entity.setSku(request.getSku());
        if (request.getName() != null) entity.setName(request.getName());
        // Nuevos campos
        if (request.getPurchaseUomId() != null) entity.setPurchaseUomId(request.getPurchaseUomId());
        if (request.getPurchaseQuantity() != null) entity.setPurchaseQuantity(request.getPurchaseQuantity());
        if (request.getPurchasePrice() != null) entity.setPurchasePrice(request.getPurchasePrice());
        if (request.getManufacturingUomId() != null) entity.setManufacturingUomId(request.getManufacturingUomId());
        // unitCost se calculará automáticamente en @PreUpdate
        // Campos legacy
        if (request.getUomId() != null) entity.setUomId(request.getUomId());
        if (request.getQuantity() != null) entity.setQuantity(request.getQuantity());
        if (request.getCost() != null) entity.setCost(request.getCost());
        // Otros campos
        if (request.getMin() != null) entity.setMin(request.getMin());
        if (request.getMax() != null) entity.setMax(request.getMax());
        if (request.getDeliveryDays() != null) entity.setDeliveryDays(request.getDeliveryDays());
        if (request.getMaterialColorId() != null) entity.setMaterialColorId(request.getMaterialColorId());
        if (request.getSupplierId() != null) entity.setSupplierId(request.getSupplierId());
        if (request.getDescription() != null) entity.setDescription(request.getDescription());
        if (request.getImageUrl() != null) entity.setImageUrl(request.getImageUrl());
        if (request.getStatus() != null) entity.setStatus(request.getStatus());
        if (request.getLossPercentage() != null) entity.setLossPercentage(request.getLossPercentage());
        if (request.getIsPrimaryLeather() != null) entity.setIsPrimaryLeather(request.getIsPrimaryLeather());
    }

}

