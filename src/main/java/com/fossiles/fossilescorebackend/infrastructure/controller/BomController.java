package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.BomRequest;
import com.fossiles.fossilescorebackend.application.dto.response.BomItemResponse;
import com.fossiles.fossilescorebackend.application.dto.response.BomResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BomEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BomItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BomItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BomRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/boms")
@RequiredArgsConstructor
public class BomController {

    private final BomRepository bomRepository;
    private final BomItemRepository bomItemRepository;
    private final ProductRepository productRepository;
    private final MaterialRepository materialRepository;

    @GetMapping
    public ResponseEntity<List<BomResponse>> getAll() {
        List<BomResponse> boms = bomRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(boms);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BomResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        BomEntity entity = bomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BOM", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @GetMapping("/product/{productId}")
    public ResponseEntity<List<BomResponse>> getByProductId(@PathVariable Long productId) {
        List<BomResponse> boms = bomRepository.findByProductId(productId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(boms);
    }

    @PostMapping
    public ResponseEntity<BomResponse> create(@Valid @RequestBody BomRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (!productRepository.existsById(request.getProductId())) {
            throw new ResourceNotFoundException("Product", request.getProductId());
        }

        BomEntity entity = toEntity(request);
        if (entity.getStatus() == null || entity.getStatus().isEmpty()) {
            entity.setStatus("A");
        }

        BomEntity saved = bomRepository.save(entity);

        if (request.getItems() != null && !request.getItems().isEmpty()) {
            List<BomItemEntity> items = request.getItems().stream()
                    .map(itemRequest -> {
                        BomItemEntity item = BomItemEntity.builder()
                                .bomId(saved.getId())
                                .materialId(itemRequest.getMaterialId())
                                .quantity(itemRequest.getQuantity())
                                .measurement(itemRequest.getMeasurement())
                                .measurementUnit(itemRequest.getMeasurementUnit())
                                .build();
                        return bomItemRepository.save(item);
                    })
                    .collect(Collectors.toList());
            saved.setItems(items);
        }

        return ResponseEntity.created(URI.create("/api/boms/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BomResponse> update(@PathVariable Long id, @Valid @RequestBody BomRequest request) 
            throws ResourceNotFoundException, BusinessException {
        BomEntity entity = bomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BOM", id));
        
        if (request.getProductId() != null && !productRepository.existsById(request.getProductId())) {
            throw new ResourceNotFoundException("Product", request.getProductId());
        }
        
        updateEntity(entity, request);
        
        if (request.getItems() != null) {
            bomItemRepository.findByBomId(id).forEach(bomItemRepository::delete);
            
            if (!request.getItems().isEmpty()) {
                List<BomItemEntity> items = request.getItems().stream()
                        .map(itemRequest -> {
                            BomItemEntity item = BomItemEntity.builder()
                                    .bomId(entity.getId())
                                    .materialId(itemRequest.getMaterialId())
                                    .quantity(itemRequest.getQuantity())
                                    .measurement(itemRequest.getMeasurement())
                                    .measurementUnit(itemRequest.getMeasurementUnit())
                                    .build();
                            return bomItemRepository.save(item);
                        })
                        .collect(Collectors.toList());
                entity.setItems(items);
            }
        }
        
        BomEntity updated = bomRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!bomRepository.existsById(id)) {
            throw new ResourceNotFoundException("BOM", id);
        }
        bomItemRepository.findByBomId(id).forEach(bomItemRepository::delete);
        bomRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private BomResponse toResponse(BomEntity entity) {
        List<BomItemEntity> bomItems = bomItemRepository.findByBomId(entity.getId());
        List<Long> materialIds = bomItems.stream()
                .map(BomItemEntity::getMaterialId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, MaterialEntity> materialsMap = materialRepository.findAllById(materialIds).stream()
                .collect(Collectors.toMap(MaterialEntity::getId, material -> material));

        BigDecimal totalCost = BigDecimal.ZERO;
        List<BomItemResponse> items = bomItems.stream()
                .map(item -> {
                    MaterialEntity material = materialsMap.get(item.getMaterialId());
                    BigDecimal itemCost = getBigDecimal(item, material);

                    return BomItemResponse.builder()
                            .id(item.getId())
                            .bomId(item.getBomId())
                            .materialId(item.getMaterialId())
                            .quantity(item.getQuantity())
                            .measurement(item.getMeasurement())
                            .measurementUnit(item.getMeasurementUnit())
                            .itemCost(itemCost)
                            .createdAt(item.getCreatedAt())
                            .createdBy(item.getCreatedBy())
                            .updatedAt(item.getUpdatedAt())
                            .updatedBy(item.getUpdatedBy())
                            .build();
                })
                .collect(Collectors.toList());

        totalCost = items.stream()
                .map(BomItemResponse::getItemCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        return BomResponse.builder()
                .id(entity.getId())
                .bomName(entity.getBomName())
                .productId(entity.getProductId())
                .colorId(entity.getColorId())
                .status(entity.getStatus())
                .totalCost(totalCost)
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .items(items)
                .build();
    }

    private static @NonNull BigDecimal getBigDecimal(BomItemEntity item, MaterialEntity material) {
        BigDecimal itemCost = BigDecimal.ZERO;

        if (material != null && material.getCost() != null && item.getQuantity() != null) {
            // Si tiene measurement (no es UOM 3), multiplicar por measurement
            if (item.getMeasurement() != null && item.getMeasurement().compareTo(BigDecimal.ZERO) > 0) {
                itemCost = material.getCost()
                        .multiply(item.getMeasurement())
                        .multiply(item.getQuantity())
                        .setScale(2, RoundingMode.HALF_UP);
            } else {
                // Si no tiene measurement (UOM 3), solo multiplicar por quantity
                itemCost = material.getCost()
                        .multiply(item.getQuantity())
                        .setScale(2, RoundingMode.HALF_UP);
            }
        }
        return itemCost;
    }

    private BomEntity toEntity(BomRequest request) {
        return BomEntity.builder()
                .bomName(request.getBomName())
                .productId(request.getProductId())
                .colorId(request.getColorId())
                .status(request.getStatus())
                .build();
    }

    private void updateEntity(BomEntity entity, BomRequest request) {
        if (request.getBomName() != null) entity.setBomName(request.getBomName());
        if (request.getProductId() != null) entity.setProductId(request.getProductId());
        if (request.getColorId() != null) entity.setColorId(request.getColorId());
        if (request.getStatus() != null) entity.setStatus(request.getStatus());
    }
}

