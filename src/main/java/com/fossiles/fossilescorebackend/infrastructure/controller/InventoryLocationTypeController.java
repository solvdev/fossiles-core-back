package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.InventoryLocationTypeRequest;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryLocationTypeResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryLocationTypeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InventoryLocationTypeRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory-location-types")
@RequiredArgsConstructor
public class InventoryLocationTypeController {

    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;

    @GetMapping
    public ResponseEntity<List<InventoryLocationTypeResponse>> getAll() {
        List<InventoryLocationTypeResponse> types = inventoryLocationTypeRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(types);
    }

    @GetMapping("/active")
    public ResponseEntity<List<InventoryLocationTypeResponse>> getActive() {
        List<InventoryLocationTypeResponse> types = inventoryLocationTypeRepository.findByIsActiveTrue().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(types);
    }

    @GetMapping("/{id}")
    public ResponseEntity<InventoryLocationTypeResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        InventoryLocationTypeEntity entity = inventoryLocationTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLocationType", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<InventoryLocationTypeResponse> create(@Valid @RequestBody InventoryLocationTypeRequest request) 
            throws BusinessException {
        if (inventoryLocationTypeRepository.findByCode(request.getCode()).isPresent()) {
            throw new BusinessException("Inventory location type code already exists: " + request.getCode());
        }
        
        InventoryLocationTypeEntity entity = toEntity(request);
        InventoryLocationTypeEntity saved = inventoryLocationTypeRepository.save(entity);
        return ResponseEntity.ok(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<InventoryLocationTypeResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody InventoryLocationTypeRequest request) 
            throws ResourceNotFoundException, BusinessException {
        InventoryLocationTypeEntity entity = inventoryLocationTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLocationType", id));
        
        // Verificar si el código ya existe en otro registro
        inventoryLocationTypeRepository.findByCode(request.getCode())
                .ifPresent(existing -> {
                    if (!existing.getId().equals(id)) {
                        try {
                            throw new BusinessException("Inventory location type code already exists: " + request.getCode());
                        } catch (BusinessException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
        
        updateEntity(entity, request);
        InventoryLocationTypeEntity saved = inventoryLocationTypeRepository.save(entity);
        return ResponseEntity.ok(toResponse(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        InventoryLocationTypeEntity entity = inventoryLocationTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLocationType", id));
        inventoryLocationTypeRepository.delete(entity);
        return ResponseEntity.noContent().build();
    }

    private InventoryLocationTypeResponse toResponse(InventoryLocationTypeEntity entity) {
        return InventoryLocationTypeResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .isActive(entity.getIsActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private InventoryLocationTypeEntity toEntity(InventoryLocationTypeRequest request) {
        return InventoryLocationTypeEntity.builder()
                .code(request.getCode())
                .name(request.getName())
                .description(request.getDescription())
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .build();
    }

    private void updateEntity(InventoryLocationTypeEntity entity, InventoryLocationTypeRequest request) {
        if (request.getCode() != null) entity.setCode(request.getCode());
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getDescription() != null) entity.setDescription(request.getDescription());
        if (request.getIsActive() != null) entity.setIsActive(request.getIsActive());
    }
}

