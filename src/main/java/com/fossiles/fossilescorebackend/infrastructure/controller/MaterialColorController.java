package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ColorBatchRequest;
import com.fossiles.fossilescorebackend.application.dto.request.MaterialColorRequest;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialColorResponse;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialColorRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/material-colors")
@RequiredArgsConstructor
public class MaterialColorController {

    private final MaterialColorRepository materialColorRepository;

    @GetMapping
    public ResponseEntity<List<MaterialColorResponse>> getAll() {
        List<MaterialColorResponse> colors = materialColorRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(colors);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MaterialColorResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        MaterialColorEntity entity = materialColorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MaterialColor", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<MaterialColorResponse> create(@Valid @RequestBody MaterialColorRequest request) {
        MaterialColorEntity entity = toEntity(request);
        MaterialColorEntity saved = materialColorRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/material-colors/" + saved.getId())).body(toResponse(saved));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<MaterialColorResponse>> createBatch(@Valid @RequestBody ColorBatchRequest request) {
        List<MaterialColorEntity> entities = request.getNames().stream()
                .map(name -> MaterialColorEntity.builder().name(name.trim()).build())
                .filter(entity -> !entity.getName().isEmpty())
                .collect(Collectors.toList());
        
        List<MaterialColorEntity> saved = materialColorRepository.saveAll(entities);
        List<MaterialColorResponse> responses = saved.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(responses);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MaterialColorResponse> update(@PathVariable Long id, @Valid @RequestBody MaterialColorRequest request) 
            throws ResourceNotFoundException {
        MaterialColorEntity entity = materialColorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MaterialColor", id));
        
        if (request.getName() != null) {
            entity.setName(request.getName());
        }
        
        MaterialColorEntity updated = materialColorRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!materialColorRepository.existsById(id)) {
            throw new ResourceNotFoundException("MaterialColor", id);
        }
        materialColorRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private MaterialColorResponse toResponse(MaterialColorEntity entity) {
        return MaterialColorResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .build();
    }

    private MaterialColorEntity toEntity(MaterialColorRequest request) {
        return MaterialColorEntity.builder()
                .name(request.getName())
                .build();
    }
}

