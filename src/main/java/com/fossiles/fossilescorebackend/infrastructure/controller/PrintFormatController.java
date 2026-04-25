package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.PrintFormatRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PrintFormatResponse;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PrintFormatEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PrintFormatRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/print-formats")
@RequiredArgsConstructor
public class PrintFormatController {

    private final PrintFormatRepository printFormatRepository;

    @GetMapping
    public ResponseEntity<List<PrintFormatResponse>> getAll() {
        List<PrintFormatResponse> formats = printFormatRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(formats);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PrintFormatResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        PrintFormatEntity entity = printFormatRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Print Format", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @GetMapping("/type/{documentType}")
    public ResponseEntity<List<PrintFormatResponse>> getByDocumentType(@PathVariable String documentType) {
        List<PrintFormatResponse> formats = printFormatRepository.findByDocumentType(documentType).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(formats);
    }

    @GetMapping("/type/{documentType}/default")
    public ResponseEntity<PrintFormatResponse> getDefaultByDocumentType(@PathVariable String documentType) {
        PrintFormatEntity entity = printFormatRepository.findByDocumentTypeAndIsDefaultTrue(documentType)
                .orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<PrintFormatResponse> create(@Valid @RequestBody PrintFormatRequest request) {
        // Si se marca como default, desmarcar otros defaults del mismo tipo
        if (Boolean.TRUE.equals(request.getIsDefault())) {
            printFormatRepository.findByDocumentTypeAndIsDefaultTrue(request.getDocumentType())
                    .ifPresent(existing -> {
                        existing.setIsDefault(false);
                        printFormatRepository.save(existing);
                    });
        }
        
        PrintFormatEntity entity = toEntity(request);
        PrintFormatEntity saved = printFormatRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/print-formats/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PrintFormatResponse> update(@PathVariable Long id, @Valid @RequestBody PrintFormatRequest request)
            throws ResourceNotFoundException {
        PrintFormatEntity entity = printFormatRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Print Format", id));

        // Si se marca como default, desmarcar otros defaults del mismo tipo
        if (Boolean.TRUE.equals(request.getIsDefault()) && !Boolean.TRUE.equals(entity.getIsDefault())) {
            printFormatRepository.findByDocumentTypeAndIsDefaultTrue(request.getDocumentType())
                    .ifPresent(existing -> {
                        if (!existing.getId().equals(id)) {
                            existing.setIsDefault(false);
                            printFormatRepository.save(existing);
                        }
                    });
        }

        updateEntity(entity, request);
        PrintFormatEntity updated = printFormatRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!printFormatRepository.existsById(id)) {
            throw new ResourceNotFoundException("Print Format", id);
        }
        printFormatRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private PrintFormatResponse toResponse(PrintFormatEntity entity) {
        return PrintFormatResponse.builder()
                .id(entity.getId())
                .documentType(entity.getDocumentType())
                .formatName(entity.getFormatName())
                .templatePath(entity.getTemplatePath())
                .paperSize(entity.getPaperSize())
                .margins(entity.getMargins())
                .header(entity.getHeader())
                .footer(entity.getFooter())
                .logoPath(entity.getLogoPath())
                .isDefault(entity.getIsDefault())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private PrintFormatEntity toEntity(PrintFormatRequest request) {
        return PrintFormatEntity.builder()
                .documentType(request.getDocumentType())
                .formatName(request.getFormatName())
                .templatePath(request.getTemplatePath())
                .paperSize(request.getPaperSize())
                .margins(request.getMargins())
                .header(request.getHeader())
                .footer(request.getFooter())
                .logoPath(request.getLogoPath())
                .isDefault(request.getIsDefault() != null ? request.getIsDefault() : false)
                .description(request.getDescription())
                .build();
    }

    private void updateEntity(PrintFormatEntity entity, PrintFormatRequest request) {
        if (request.getDocumentType() != null) entity.setDocumentType(request.getDocumentType());
        if (request.getFormatName() != null) entity.setFormatName(request.getFormatName());
        if (request.getTemplatePath() != null) entity.setTemplatePath(request.getTemplatePath());
        if (request.getPaperSize() != null) entity.setPaperSize(request.getPaperSize());
        if (request.getMargins() != null) entity.setMargins(request.getMargins());
        if (request.getHeader() != null) entity.setHeader(request.getHeader());
        if (request.getFooter() != null) entity.setFooter(request.getFooter());
        if (request.getLogoPath() != null) entity.setLogoPath(request.getLogoPath());
        if (request.getIsDefault() != null) entity.setIsDefault(request.getIsDefault());
        if (request.getDescription() != null) entity.setDescription(request.getDescription());
    }
}

