package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.CostCenterRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CostCenterResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CostCenterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CostCenterRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/cost-centers")
@RequiredArgsConstructor
public class CostCenterController {

    private final CostCenterRepository costCenterRepository;

    @GetMapping
    public ResponseEntity<List<CostCenterResponse>> getAll() {
        List<CostCenterResponse> costCenters = costCenterRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(costCenters);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CostCenterResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        CostCenterEntity entity = costCenterRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<CostCenterResponse> create(@Valid @RequestBody CostCenterRequest request) 
            throws BusinessException {
        if (costCenterRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Cost Center code already exists: " + request.getCode());
        }
        CostCenterEntity entity = toEntity(request);
        CostCenterEntity saved = costCenterRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/cost-centers/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CostCenterResponse> update(@PathVariable Long id, @Valid @RequestBody CostCenterRequest request) 
            throws ResourceNotFoundException, BusinessException {
        CostCenterEntity entity = costCenterRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter", id));
        
        if (!entity.getCode().equals(request.getCode()) 
                && costCenterRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Cost Center code already exists: " + request.getCode());
        }
        
        updateEntity(entity, request);
        CostCenterEntity updated = costCenterRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!costCenterRepository.existsById(id)) {
            throw new ResourceNotFoundException("CostCenter", id);
        }
        costCenterRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private CostCenterResponse toResponse(CostCenterEntity entity) {
        return CostCenterResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .build();
    }

    private CostCenterEntity toEntity(CostCenterRequest request) {
        return CostCenterEntity.builder()
                .code(request.getCode())
                .name(request.getName())
                .description(request.getDescription())
                .build();
    }

    private void updateEntity(CostCenterEntity entity, CostCenterRequest request) {
        if (request.getCode() != null) entity.setCode(request.getCode());
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getDescription() != null) entity.setDescription(request.getDescription());
    }
}

