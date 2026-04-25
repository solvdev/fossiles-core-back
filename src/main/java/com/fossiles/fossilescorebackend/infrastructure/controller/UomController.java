package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.UomRequest;
import com.fossiles.fossilescorebackend.application.dto.response.UomResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UomEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UomRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/uoms")
@RequiredArgsConstructor
public class UomController {

    private final UomRepository uomRepository;

    @GetMapping
    public ResponseEntity<List<UomResponse>> getAll() {
        List<UomResponse> uoms = uomRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(uoms);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UomResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        UomEntity entity = uomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UOM", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<UomResponse> create(@Valid @RequestBody UomRequest request) throws BusinessException {
        if (uomRepository.existsByCode(request.getCode())) {
            throw new BusinessException("UOM code already exists: " + request.getCode());
        }
        UomEntity entity = toEntity(request);
        UomEntity saved = uomRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/uoms/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UomResponse> update(@PathVariable Long id, @Valid @RequestBody UomRequest request) 
            throws ResourceNotFoundException, BusinessException {
        UomEntity entity = uomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UOM", id));
        
        if (!entity.getCode().equals(request.getCode()) 
                && uomRepository.existsByCode(request.getCode())) {
            throw new BusinessException("UOM code already exists: " + request.getCode());
        }
        
        entity.setCode(request.getCode());
        if (request.getName() != null) entity.setName(request.getName());
        
        UomEntity updated = uomRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!uomRepository.existsById(id)) {
            throw new ResourceNotFoundException("UOM", id);
        }
        uomRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private UomResponse toResponse(UomEntity entity) {
        return UomResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .build();
    }

    private UomEntity toEntity(UomRequest request) {
        return UomEntity.builder()
                .code(request.getCode())
                .name(request.getName())
                .build();
    }
}

