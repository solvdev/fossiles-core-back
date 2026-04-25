package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ProductCategoryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductCategoryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/product-categories")
@RequiredArgsConstructor
public class ProductCategoryController {

    private final ProductCategoryRepository categoryRepository;

    @GetMapping
    public ResponseEntity<List<ProductCategoryResponse>> getAll() {
        List<ProductCategoryResponse> categories = categoryRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(categories);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductCategoryResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        ProductCategoryEntity entity = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductCategory", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<ProductCategoryResponse> create(@Valid @RequestBody ProductCategoryRequest request) 
            throws BusinessException {
        if (categoryRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Product category code already exists: " + request.getCode());
        }
        ProductCategoryEntity entity = toEntity(request);
        ProductCategoryEntity saved = categoryRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/product-categories/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductCategoryResponse> update(@PathVariable Long id, @Valid @RequestBody ProductCategoryRequest request) 
            throws ResourceNotFoundException, BusinessException {
        ProductCategoryEntity entity = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductCategory", id));
        
        if (!entity.getCode().equals(request.getCode()) 
                && categoryRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Product category code already exists: " + request.getCode());
        }
        
        entity.setCode(request.getCode());
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getHourlyCost() != null) entity.setHourlyCost(request.getHourlyCost());
        if (request.getPayrollTotal() != null) entity.setPayrollTotal(request.getPayrollTotal());
        if (request.getAvailableHours() != null) entity.setAvailableHours(request.getAvailableHours());
        if (request.getNumberOfTables() != null) entity.setNumberOfTables(request.getNumberOfTables());
        
        ProductCategoryEntity updated = categoryRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!categoryRepository.existsById(id)) {
            throw new ResourceNotFoundException("ProductCategory", id);
        }
        categoryRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private ProductCategoryResponse toResponse(ProductCategoryEntity entity) {
        return ProductCategoryResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .hourlyCost(entity.getHourlyCost())
                .payrollTotal(entity.getPayrollTotal())
                .availableHours(entity.getAvailableHours())
                .numberOfTables(entity.getNumberOfTables())
                .build();
    }

    private ProductCategoryEntity toEntity(ProductCategoryRequest request) {
        return ProductCategoryEntity.builder()
                .code(request.getCode())
                .name(request.getName())
                .hourlyCost(request.getHourlyCost())
                .payrollTotal(request.getPayrollTotal())
                .availableHours(request.getAvailableHours())
                .numberOfTables(request.getNumberOfTables())
                .build();
    }
}

