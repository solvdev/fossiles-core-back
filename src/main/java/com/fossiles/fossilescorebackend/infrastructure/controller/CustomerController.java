package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.DeliveryRouteCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerRepository customerRepository;

    @GetMapping
    public ResponseEntity<List<CustomerResponse>> getAll() {
        List<CustomerResponse> customers = customerRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(customers);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        CustomerEntity entity = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) 
            throws BusinessException {
        if (request.getNit() != null && customerRepository.existsByNit(request.getNit())) {
            throw new BusinessException("Customer NIT already exists: " + request.getNit());
        }
        validateLegacyCode(null, request.getLegacyCode());
        validateRouteLocationCode(request.getRouteLocationCode());
        CustomerEntity entity = toEntity(request);
        if (entity.getStatus() == null) {
            entity.setStatus("active");
        }
        CustomerEntity saved = customerRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/customers/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CustomerResponse> update(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) 
            throws ResourceNotFoundException, BusinessException {
        CustomerEntity entity = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
        
        if (request.getNit() != null && !entity.getNit().equals(request.getNit()) 
                && customerRepository.existsByNit(request.getNit())) {
            throw new BusinessException("Customer NIT already exists: " + request.getNit());
        }
        validateLegacyCode(entity.getId(), request.getLegacyCode());
        validateRouteLocationCode(request.getRouteLocationCode());
        
        updateEntity(entity, request);
        CustomerEntity updated = customerRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!customerRepository.existsById(id)) {
            throw new ResourceNotFoundException("Customer", id);
        }
        customerRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private CustomerResponse toResponse(CustomerEntity entity) {
        return CustomerResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .nit(entity.getNit())
                .legacyCode(entity.getLegacyCode())
                .phone(entity.getPhone())
                .email(entity.getEmail())
                .address(entity.getAddress())
                .routeLocationCode(entity.getRouteLocationCode())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private CustomerEntity toEntity(CustomerRequest request) {
        return CustomerEntity.builder()
                .name(request.getName())
                .nit(request.getNit())
                .legacyCode(normalizeLegacyCode(request.getLegacyCode()))
                .phone(request.getPhone())
                .email(request.getEmail())
                .address(request.getAddress())
                .routeLocationCode(normalizeRouteCode(request.getRouteLocationCode()))
                .status(request.getStatus())
                .build();
    }

    private void updateEntity(CustomerEntity entity, CustomerRequest request) {
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getNit() != null) entity.setNit(request.getNit());
        if (request.getLegacyCode() != null) {
            entity.setLegacyCode(normalizeLegacyCode(request.getLegacyCode()));
        }
        if (request.getPhone() != null) entity.setPhone(request.getPhone());
        if (request.getEmail() != null) entity.setEmail(request.getEmail());
        if (request.getAddress() != null) entity.setAddress(request.getAddress());
        entity.setRouteLocationCode(normalizeRouteCode(request.getRouteLocationCode()));
        if (request.getStatus() != null) entity.setStatus(request.getStatus());
    }

    private void validateRouteLocationCode(String code) throws BusinessException {
        if (code == null || code.isBlank()) {
            return;
        }
        if (!DeliveryRouteCatalog.isValidRouteLocationCode(code)) {
            throw new BusinessException("Código de ruta inválido: " + code.trim());
        }
    }

    private static String normalizeRouteCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private void validateLegacyCode(Long currentCustomerId, String legacyCode) throws BusinessException {
        String normalized = normalizeLegacyCode(legacyCode);
        if (normalized == null) {
            return;
        }
        Optional<CustomerEntity> existing = customerRepository.findByLegacyCode(normalized);
        if (existing.isPresent() && (currentCustomerId == null || !currentCustomerId.equals(existing.get().getId()))) {
            throw new BusinessException("La clave de cliente ya existe: " + normalized);
        }
    }

    private static String normalizeLegacyCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase(java.util.Locale.ROOT);
    }
}

