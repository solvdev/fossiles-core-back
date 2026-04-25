package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.TaxRequest;
import com.fossiles.fossilescorebackend.application.dto.response.TaxResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/taxes")
@RequiredArgsConstructor
public class TaxController {

    private final TaxRepository taxRepository;

    @GetMapping
    public ResponseEntity<List<TaxResponse>> getAll() {
        List<TaxResponse> taxes = taxRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(taxes);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaxResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        TaxEntity entity = taxRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tax", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<TaxResponse>> getByStatus(@PathVariable String status) {
        List<TaxResponse> taxes = taxRepository.findByStatus(status).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(taxes);
    }

    @PostMapping
    public ResponseEntity<TaxResponse> create(@Valid @RequestBody TaxRequest request) throws BusinessException {
        if (taxRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Tax code already exists: " + request.getCode());
        }
        TaxEntity entity = toEntity(request);
        TaxEntity saved = taxRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/taxes/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TaxResponse> update(@PathVariable Long id, @Valid @RequestBody TaxRequest request)
            throws ResourceNotFoundException, BusinessException {
        TaxEntity entity = taxRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tax", id));

        if (!entity.getCode().equals(request.getCode()) && taxRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Tax code already exists: " + request.getCode());
        }

        updateEntity(entity, request);
        TaxEntity updated = taxRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!taxRepository.existsById(id)) {
            throw new ResourceNotFoundException("Tax", id);
        }
        taxRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private TaxResponse toResponse(TaxEntity entity) {
        return TaxResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .percentage(entity.getPercentage())
                .type(entity.getType())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private TaxEntity toEntity(TaxRequest request) {
        return TaxEntity.builder()
                .code(request.getCode())
                .name(request.getName())
                .percentage(request.getPercentage())
                .type(request.getType())
                .description(request.getDescription())
                .status(request.getStatus() != null && !request.getStatus().isEmpty() ? request.getStatus() : "active")
                .build();
    }

    private void updateEntity(TaxEntity entity, TaxRequest request) {
        if (request.getCode() != null) entity.setCode(request.getCode());
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getPercentage() != null) entity.setPercentage(request.getPercentage());
        if (request.getType() != null) entity.setType(request.getType());
        if (request.getDescription() != null) entity.setDescription(request.getDescription());
        if (request.getStatus() != null) entity.setStatus(request.getStatus());
    }
}

