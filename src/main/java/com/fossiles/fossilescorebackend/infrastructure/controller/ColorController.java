package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ColorBatchRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ColorRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ColorResponse;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/colors")
@RequiredArgsConstructor
public class ColorController {

    private final ColorRepository colorRepository;

    @GetMapping
    public ResponseEntity<List<ColorResponse>> getAll() {
        List<ColorResponse> colors = colorRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(colors);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ColorResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        ColorEntity entity = colorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Color", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<ColorResponse> create(@Valid @RequestBody ColorRequest request) {
        ColorEntity entity = toEntity(request);
        ColorEntity saved = colorRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/colors/" + saved.getId())).body(toResponse(saved));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<ColorResponse>> createBatch(@Valid @RequestBody ColorBatchRequest request) {
        List<ColorEntity> entities = request.getNames().stream()
                .map(name -> ColorEntity.builder().name(name.trim()).build())
                .filter(entity -> !entity.getName().isEmpty())
                .collect(Collectors.toList());
        
        List<ColorEntity> saved = colorRepository.saveAll(entities);
        List<ColorResponse> responses = saved.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(responses);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ColorResponse> update(@PathVariable Long id, @Valid @RequestBody ColorRequest request) 
            throws ResourceNotFoundException {
        ColorEntity entity = colorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Color", id));
        
        if (request.getName() != null) {
            entity.setName(request.getName());
        }
        
        ColorEntity updated = colorRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!colorRepository.existsById(id)) {
            throw new ResourceNotFoundException("Color", id);
        }
        colorRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private ColorResponse toResponse(ColorEntity entity) {
        return ColorResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .build();
    }

    private ColorEntity toEntity(ColorRequest request) {
        return ColorEntity.builder()
                .name(request.getName())
                .build();
    }
}

