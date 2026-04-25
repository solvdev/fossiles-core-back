package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.BulkPriceUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BomEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BomRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;
    private final BomRepository bomRepository;

    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAll() {
        List<ProductResponse> products = productRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(products);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        ProductEntity entity = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) 
            throws BusinessException {
        if (productRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Product code already exists: " + request.getCode());
        }
        ProductEntity entity = toEntity(request);
        if (entity.getStatus() == null) {
            entity.setStatus("A");
        }
        if (entity.getRequiresMaterials() == null) {
            entity.setRequiresMaterials(true);
        }
        ProductEntity saved = productRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/products/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) 
            throws ResourceNotFoundException, BusinessException {
        ProductEntity entity = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        
        if (!entity.getCode().equals(request.getCode()) 
                && productRepository.existsByCode(request.getCode())) {
            throw new BusinessException("Product code already exists: " + request.getCode());
        }
        
        updateEntity(entity, request);
        ProductEntity updated = productRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException, BusinessException {
        ProductEntity product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        
        // Verificar si el producto tiene BOMs asociadas
        List<BomEntity> boms = bomRepository.findByProductId(id);
        if (boms != null && !boms.isEmpty()) {
            throw new BusinessException("No se puede eliminar el producto porque tiene " + boms.size() + 
                    " BOM(s) asociada(s). Elimine primero las BOMs asociadas antes de eliminar el producto.");
        }
        
        try {
            productRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        } catch (DataIntegrityViolationException e) {
            // Capturar excepciones de foreign key constraint como respaldo
            if (e.getMessage() != null && e.getMessage().contains("foreign key constraint")) {
                throw new BusinessException("No se puede eliminar el producto porque tiene BOM(s) asociada(s). " +
                        "Elimine primero las BOMs asociadas antes de eliminar el producto.");
            }
            throw e;
        }
    }

    @PutMapping("/bulk-price-update")
    public ResponseEntity<String> bulkUpdatePrices(@Valid @RequestBody BulkPriceUpdateRequest request) {
        List<ProductEntity> products;
        
        if (request.getCategoryId() != null) {
            // Actualizar solo productos de la categoría especificada
            products = productRepository.findByCategoryId(request.getCategoryId());
        } else {
            // Actualizar todos los productos
            products = productRepository.findAll();
        }
        
        int updatedCount = 0;
        BigDecimal multiplier = BigDecimal.ONE.add(request.getPercentage().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        
        for (ProductEntity product : products) {
            if (product.getSalePrice() != null && product.getSalePrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal newPrice = product.getSalePrice().multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
                product.setSalePrice(newPrice);
                productRepository.save(product);
                updatedCount++;
            }
        }
        
        String message = String.format("Se actualizaron %d productos. ", updatedCount);
        if (request.getCategoryId() != null) {
            message += "Categoría específica.";
        } else {
            message += "Todos los productos.";
        }
        
        return ResponseEntity.ok(message);
    }

    @PutMapping("/bulk-discount-apply")
    public ResponseEntity<String> bulkApplyDiscounts(@Valid @RequestBody BulkPriceUpdateRequest request) {
        List<ProductEntity> products;
        
        if (request.getCategoryId() != null) {
            // Aplicar descuento solo a productos de la categoría especificada
            products = productRepository.findByCategoryId(request.getCategoryId());
        } else {
            // Aplicar descuento a todos los productos
            products = productRepository.findAll();
        }
        
        int updatedCount = 0;
        // El porcentaje es el descuento (ej: 10% = 0.10)
        BigDecimal discountMultiplier = BigDecimal.ONE.subtract(
            request.getPercentage().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
        );
        
        for (ProductEntity product : products) {
            if (product.getSalePrice() != null && product.getSalePrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal discountedPrice = product.getSalePrice()
                    .multiply(discountMultiplier)
                    .setScale(2, RoundingMode.HALF_UP);
                product.setDiscountedPrice(discountedPrice);
                productRepository.save(product);
                updatedCount++;
            }
        }
        
        String message = String.format("Se aplicó descuento del %s%% a %d productos. ", 
            request.getPercentage(), updatedCount);
        if (request.getCategoryId() != null) {
            message += "Categoría específica.";
        } else {
            message += "Todos los productos.";
        }
        
        return ResponseEntity.ok(message);
    }

    @PutMapping("/bulk-discount-remove")
    public ResponseEntity<String> bulkRemoveDiscounts(@RequestBody(required = false) BulkPriceUpdateRequest request) {
        List<ProductEntity> products;
        
        if (request != null && request.getCategoryId() != null) {
            // Remover descuentos solo de productos de la categoría especificada
            products = productRepository.findByCategoryId(request.getCategoryId());
        } else {
            // Remover descuentos de todos los productos
            products = productRepository.findAll();
        }
        
        int updatedCount = 0;
        
        for (ProductEntity product : products) {
            if (product.getDiscountedPrice() != null) {
                product.setDiscountedPrice(null);
                productRepository.save(product);
                updatedCount++;
            }
        }
        
        String message = String.format("Se removieron descuentos de %d productos. ", updatedCount);
        if (request != null && request.getCategoryId() != null) {
            message += "Categoría específica.";
        } else {
            message += "Todos los productos.";
        }
        
        return ResponseEntity.ok(message);
    }

    private ProductResponse toResponse(ProductEntity entity) {
        return ProductResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .categoryId(entity.getCategoryId())
                .prdTime(entity.getPrdTime())
                .salePrice(entity.getSalePrice())
                .discountedPrice(entity.getDiscountedPrice())
                .sellerPrice(entity.getSellerPrice())
                .imageUrl(entity.getImageUrl())
                .leatherConsumption(entity.getLeatherConsumption())
                .requiresMaterials(entity.getRequiresMaterials())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private ProductEntity toEntity(ProductRequest request) {
        // Redondear prdTime a 2 decimales si existe
        Double roundedPrdTime = request.getPrdTime() != null 
            ? Math.round(request.getPrdTime() * 100.0) / 100.0 
            : null;
        
        return ProductEntity.builder()
                .code(request.getCode())
                .name(request.getName())
                .categoryId(request.getCategoryId())
                .prdTime(roundedPrdTime)
                .salePrice(request.getSalePrice())
                .discountedPrice(request.getDiscountedPrice())
                .sellerPrice(request.getSellerPrice())
                .imageUrl(request.getImageUrl())
                .leatherConsumption(request.getLeatherConsumption())
                .requiresMaterials(request.getRequiresMaterials() != null ? request.getRequiresMaterials() : true)
                .status(request.getStatus())
                .build();
    }

    private void updateEntity(ProductEntity entity, ProductRequest request) {
        if (request.getCode() != null) entity.setCode(request.getCode());
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getCategoryId() != null) entity.setCategoryId(request.getCategoryId());
        if (request.getPrdTime() != null) {
            // Redondear a 2 decimales
            double roundedTime = Math.round(request.getPrdTime() * 100.0) / 100.0;
            entity.setPrdTime(roundedTime);
        }
        if (request.getSalePrice() != null) entity.setSalePrice(request.getSalePrice());
        if (request.getDiscountedPrice() != null) entity.setDiscountedPrice(request.getDiscountedPrice());
        if (request.getSellerPrice() != null) entity.setSellerPrice(request.getSellerPrice());
        if (request.getImageUrl() != null) entity.setImageUrl(request.getImageUrl());
        if (request.getLeatherConsumption() != null) entity.setLeatherConsumption(request.getLeatherConsumption());
        if (request.getRequiresMaterials() != null) entity.setRequiresMaterials(request.getRequiresMaterials());
        if (request.getStatus() != null) entity.setStatus(request.getStatus());
    }
}

