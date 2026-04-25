package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.SupplierRequest;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierRepository supplierRepository;

    @GetMapping
    public ResponseEntity<List<SupplierResponse>> getAll() {
        List<SupplierResponse> suppliers = supplierRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(suppliers);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SupplierResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        SupplierEntity entity = supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) 
            throws BusinessException {
        if (request.getNit() != null && supplierRepository.existsByNit(request.getNit())) {
            throw new BusinessException("Supplier NIT already exists: " + request.getNit());
        }
        SupplierEntity entity = toEntity(request);
        if (entity.getStatus() == null) {
            entity.setStatus("active");
        }
        SupplierEntity saved = supplierRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/suppliers/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SupplierResponse> update(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) 
            throws ResourceNotFoundException, BusinessException {
        SupplierEntity entity = supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", id));
        
        if (request.getNit() != null && !entity.getNit().equals(request.getNit()) 
                && supplierRepository.existsByNit(request.getNit())) {
            throw new BusinessException("Supplier NIT already exists: " + request.getNit());
        }
        
        updateEntity(entity, request);
        SupplierEntity updated = supplierRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!supplierRepository.existsById(id)) {
            throw new ResourceNotFoundException("Supplier", id);
        }
        supplierRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private SupplierResponse toResponse(SupplierEntity entity) {
        return SupplierResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .nit(entity.getNit())
                .phone(entity.getPhone())
                .email(entity.getEmail())
                .address(entity.getAddress())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private SupplierEntity toEntity(SupplierRequest request) {
        return SupplierEntity.builder()
                .name(request.getName())
                .nit(request.getNit())
                .phone(request.getPhone())
                .email(request.getEmail())
                .address(request.getAddress())
                .status(request.getStatus())
                .build();
    }

    private void updateEntity(SupplierEntity entity, SupplierRequest request) {
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getNit() != null) entity.setNit(request.getNit());
        if (request.getPhone() != null) entity.setPhone(request.getPhone());
        if (request.getEmail() != null) entity.setEmail(request.getEmail());
        if (request.getAddress() != null) entity.setAddress(request.getAddress());
        if (request.getStatus() != null) entity.setStatus(request.getStatus());
    }
}

