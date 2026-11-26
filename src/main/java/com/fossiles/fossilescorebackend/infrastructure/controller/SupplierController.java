package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.SupplierRequest;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.SupplierCodeService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final SupplierRepository supplierRepository;
    private final SupplierCodeService codeService;

    /** Ordenados por código (PV001, PV002…); los que no tienen código al final, por nombre. */
    @GetMapping
    public ResponseEntity<List<SupplierResponse>> getAll() {
        List<SupplierResponse> suppliers = supplierRepository.findAll().stream()
                .sorted(Comparator.comparingInt((SupplierEntity s) -> {
                            int n = SupplierCodeService.numberOf(s.getCode());
                            return n < 0 ? Integer.MAX_VALUE : n;
                        })
                        .thenComparing(SupplierEntity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(suppliers);
    }

    public record NextCodeResponse(String code) {}

    @GetMapping("/next-code")
    public ResponseEntity<NextCodeResponse> nextCode() {
        return ResponseEntity.ok(new NextCodeResponse(codeService.nextCode()));
    }

    @GetMapping("/codes/template")
    public ResponseEntity<byte[]> codesTemplate() throws BusinessException {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"codigos-proveedores.xlsx\"")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition")
                .contentType(MediaType.parseMediaType(XLSX))
                .body(codeService.buildTemplate());
    }

    /** Solo revisa el archivo: no cambia nada. */
    @PostMapping(value = "/codes/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SupplierCodeService.ImportPreview> previewCodes(@RequestPart("file") MultipartFile file)
            throws BusinessException {
        try {
            return ResponseEntity.ok(codeService.preview(file.getBytes()));
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo subido.");
        }
    }

    @PostMapping("/codes/apply")
    public ResponseEntity<SupplierCodeService.ApplyResult> applyCodes(@RequestBody SupplierCodeService.ApplyRequest request)
            throws BusinessException {
        return ResponseEntity.ok(codeService.apply(request));
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
        entity.setCode(codeService.codeForNew(request.getCode()));
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
        if (request.getCode() != null && !request.getCode().isBlank()) {
            entity.setCode(codeService.validFree(request.getCode(), entity.getId()));
        } else if (entity.getCode() == null) {
            entity.setCode(codeService.nextCode());
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
                .code(entity.getCode())
                .name(entity.getName())
                .nit(entity.getNit())
                .phone(entity.getPhone())
                .email(entity.getEmail())
                .address(entity.getAddress())
                .status(entity.getStatus())
                .payeeName(entity.getPayeeName())
                .creditDays(entity.getCreditDays())
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
                .payeeName(request.getPayeeName())
                .creditDays(request.getCreditDays() != null ? request.getCreditDays() : 30)
                .build();
    }

    private void updateEntity(SupplierEntity entity, SupplierRequest request) {
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getNit() != null) entity.setNit(request.getNit());
        if (request.getPhone() != null) entity.setPhone(request.getPhone());
        if (request.getEmail() != null) entity.setEmail(request.getEmail());
        if (request.getAddress() != null) entity.setAddress(request.getAddress());
        if (request.getStatus() != null) entity.setStatus(request.getStatus());
        if (request.getPayeeName() != null) entity.setPayeeName(request.getPayeeName().isBlank() ? null : request.getPayeeName().trim());
        if (request.getCreditDays() != null) entity.setCreditDays(request.getCreditDays());
    }
}

