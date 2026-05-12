package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ProductVariantLeatherRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductVariantLeatherResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.ProductVariantLeatherService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/product-variant-leathers")
@RequiredArgsConstructor
public class ProductVariantLeatherController {

    private final ProductVariantLeatherService productVariantLeatherService;

    @GetMapping("/by-product/{productId}")
    public ResponseEntity<List<ProductVariantLeatherResponse>> listByProduct(@PathVariable Long productId)
            throws ResourceNotFoundException {
        return ResponseEntity.ok(productVariantLeatherService.listByProduct(productId));
    }

    @PostMapping
    public ResponseEntity<ProductVariantLeatherResponse> create(@Valid @RequestBody ProductVariantLeatherRequest request)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(productVariantLeatherService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductVariantLeatherResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductVariantLeatherRequest request)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(productVariantLeatherService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        productVariantLeatherService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
