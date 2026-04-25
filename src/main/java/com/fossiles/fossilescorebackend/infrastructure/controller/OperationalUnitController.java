package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.OperationalUnitRequest;
import com.fossiles.fossilescorebackend.application.dto.response.OperationalUnitResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OperationalUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OperationalUnitRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/operational-units")
@RequiredArgsConstructor
public class OperationalUnitController {

    private final OperationalUnitRepository operationalUnitRepository;

    @GetMapping
    public ResponseEntity<List<OperationalUnitResponse>> getAll() {
        List<OperationalUnitResponse> units = operationalUnitRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(units);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OperationalUnitResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        OperationalUnitEntity entity = operationalUnitRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OperationalUnit", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<OperationalUnitResponse> create(@Valid @RequestBody OperationalUnitRequest request) 
            throws BusinessException {
        if (operationalUnitRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Operational Unit code already exists: " + request.getCode());
        }
        OperationalUnitEntity entity = toEntity(request);
        OperationalUnitEntity saved = operationalUnitRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/operational-units/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OperationalUnitResponse> update(@PathVariable Long id, @Valid @RequestBody OperationalUnitRequest request) 
            throws ResourceNotFoundException, BusinessException {
        OperationalUnitEntity entity = operationalUnitRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OperationalUnit", id));
        
        if (!entity.getCode().equals(request.getCode()) 
                && operationalUnitRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Operational Unit code already exists: " + request.getCode());
        }
        
        updateEntity(entity, request);
        OperationalUnitEntity updated = operationalUnitRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!operationalUnitRepository.existsById(id)) {
            throw new ResourceNotFoundException("OperationalUnit", id);
        }
        operationalUnitRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private OperationalUnitResponse toResponse(OperationalUnitEntity entity) {
        return OperationalUnitResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .build();
    }

    private OperationalUnitEntity toEntity(OperationalUnitRequest request) {
        return OperationalUnitEntity.builder()
                .code(request.getCode())
                .name(request.getName())
                .description(request.getDescription())
                .build();
    }

    private void updateEntity(OperationalUnitEntity entity, OperationalUnitRequest request) {
        if (request.getCode() != null) entity.setCode(request.getCode());
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getDescription() != null) entity.setDescription(request.getDescription());
    }
}

