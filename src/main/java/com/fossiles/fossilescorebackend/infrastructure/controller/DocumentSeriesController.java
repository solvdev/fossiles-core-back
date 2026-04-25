package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.DocumentSeriesRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DocumentSeriesResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DocumentSeriesEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DocumentSeriesRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/document-series")
@RequiredArgsConstructor
public class DocumentSeriesController {

    private final DocumentSeriesRepository documentSeriesRepository;

    @GetMapping
    public ResponseEntity<List<DocumentSeriesResponse>> getAll() {
        List<DocumentSeriesResponse> series = documentSeriesRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(series);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentSeriesResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        DocumentSeriesEntity entity = documentSeriesRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document Series", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @GetMapping("/type/{documentType}")
    public ResponseEntity<List<DocumentSeriesResponse>> getByDocumentType(@PathVariable String documentType) {
        List<DocumentSeriesResponse> series = documentSeriesRepository.findByDocumentType(documentType).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(series);
    }

    @GetMapping("/next/{documentType}/{series}")
    public ResponseEntity<Long> getNextCorrelative(@PathVariable String documentType, @PathVariable String series)
            throws ResourceNotFoundException {
        DocumentSeriesEntity entity = documentSeriesRepository
                .findByDocumentTypeAndSeriesForUpdate(documentType, series)
                .orElseThrow(() -> new ResourceNotFoundException("Document Series", documentType + "/" + series));
        
        documentSeriesRepository.incrementCorrelative(entity.getId());
        entity.setCurrentCorrelative(entity.getCurrentCorrelative() + 1);
        
        return ResponseEntity.ok(entity.getCurrentCorrelative());
    }

    @PostMapping
    public ResponseEntity<DocumentSeriesResponse> create(@Valid @RequestBody DocumentSeriesRequest request)
            throws BusinessException {
        if (documentSeriesRepository.findByDocumentTypeAndSeries(request.getDocumentType(), request.getSeries()).isPresent()) {
            throw new BusinessException("Document series already exists for type: " + request.getDocumentType() + " and series: " + request.getSeries());
        }
        DocumentSeriesEntity entity = toEntity(request);
        DocumentSeriesEntity saved = documentSeriesRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/document-series/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DocumentSeriesResponse> update(@PathVariable Long id, @Valid @RequestBody DocumentSeriesRequest request)
            throws ResourceNotFoundException, BusinessException {
        DocumentSeriesEntity entity = documentSeriesRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document Series", id));

        if ((!entity.getDocumentType().equals(request.getDocumentType()) || !entity.getSeries().equals(request.getSeries()))
                && documentSeriesRepository.findByDocumentTypeAndSeries(request.getDocumentType(), request.getSeries()).isPresent()) {
            throw new BusinessException("Document series already exists for type: " + request.getDocumentType() + " and series: " + request.getSeries());
        }

        updateEntity(entity, request);
        DocumentSeriesEntity updated = documentSeriesRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PutMapping("/{id}/reset-correlative")
    public ResponseEntity<DocumentSeriesResponse> resetCorrelative(@PathVariable Long id, @RequestParam Long newValue)
            throws ResourceNotFoundException {
        DocumentSeriesEntity entity = documentSeriesRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document Series", id));
        entity.setCurrentCorrelative(newValue);
        DocumentSeriesEntity updated = documentSeriesRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!documentSeriesRepository.existsById(id)) {
            throw new ResourceNotFoundException("Document Series", id);
        }
        documentSeriesRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private DocumentSeriesResponse toResponse(DocumentSeriesEntity entity) {
        return DocumentSeriesResponse.builder()
                .id(entity.getId())
                .documentType(entity.getDocumentType())
                .series(entity.getSeries())
                .currentCorrelative(entity.getCurrentCorrelative())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private DocumentSeriesEntity toEntity(DocumentSeriesRequest request) {
        return DocumentSeriesEntity.builder()
                .documentType(request.getDocumentType())
                .series(request.getSeries())
                .currentCorrelative(request.getCurrentCorrelative() != null ? request.getCurrentCorrelative() : 0L)
                .description(request.getDescription())
                .status(request.getStatus() != null && !request.getStatus().isEmpty() ? request.getStatus() : "active")
                .build();
    }

    private void updateEntity(DocumentSeriesEntity entity, DocumentSeriesRequest request) {
        if (request.getDocumentType() != null) entity.setDocumentType(request.getDocumentType());
        if (request.getSeries() != null) entity.setSeries(request.getSeries());
        if (request.getCurrentCorrelative() != null) entity.setCurrentCorrelative(request.getCurrentCorrelative());
        if (request.getDescription() != null) entity.setDescription(request.getDescription());
        if (request.getStatus() != null) entity.setStatus(request.getStatus());
    }
}

