package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ProductVariantLeatherRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductVariantLeatherResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductVariantLeatherEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductVariantLeatherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductVariantLeatherService {

    private final ProductVariantLeatherRepository repository;
    private final ProductRepository productRepository;
    private final MaterialRepository materialRepository;

    public List<ProductVariantLeatherResponse> listByProduct(Long productId) throws ResourceNotFoundException {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product", productId);
        }
        return repository.findByProductIdOrderByColorIdAsc(productId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public ProductVariantLeatherResponse create(ProductVariantLeatherRequest req) throws ResourceNotFoundException, BusinessException {
        validateRefs(req);
        BigDecimal qpu = req.getQtyPerUnit() != null ? req.getQtyPerUnit() : BigDecimal.ONE;
        if (qpu.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("qtyPerUnit debe ser mayor a 0");
        }
        boolean exists = req.getColorId() != null
                ? repository.findByProductIdAndColorId(req.getProductId(), req.getColorId()).isPresent()
                : repository.findByProductIdAndColorIdIsNull(req.getProductId()).isPresent();
        if (exists) {
            throw new BusinessException("Ya existe configuración de cuero para esta combinación producto/color");
        }
        ProductVariantLeatherEntity saved = repository.save(ProductVariantLeatherEntity.builder()
                .productId(req.getProductId())
                .colorId(req.getColorId())
                .leatherMaterialId(req.getLeatherMaterialId())
                .qtyPerUnit(qpu)
                .build());
        return toResponse(saved);
    }

    public ProductVariantLeatherResponse update(Long id, ProductVariantLeatherRequest req)
            throws ResourceNotFoundException, BusinessException {
        ProductVariantLeatherEntity entity = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariantLeather", id));
        validateRefs(req);
        BigDecimal qpu = req.getQtyPerUnit() != null ? req.getQtyPerUnit() : BigDecimal.ONE;
        if (qpu.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("qtyPerUnit debe ser mayor a 0");
        }
        entity.setProductId(req.getProductId());
        entity.setColorId(req.getColorId());
        entity.setLeatherMaterialId(req.getLeatherMaterialId());
        entity.setQtyPerUnit(qpu);
        return toResponse(repository.save(entity));
    }

    public void delete(Long id) throws ResourceNotFoundException {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("ProductVariantLeather", id);
        }
        repository.deleteById(id);
    }

    private void validateRefs(ProductVariantLeatherRequest req) throws ResourceNotFoundException {
        if (!productRepository.existsById(req.getProductId())) {
            throw new ResourceNotFoundException("Product", req.getProductId());
        }
        if (!materialRepository.existsById(req.getLeatherMaterialId())) {
            throw new ResourceNotFoundException("Material", req.getLeatherMaterialId());
        }
    }

    private ProductVariantLeatherResponse toResponse(ProductVariantLeatherEntity e) {
        MaterialEntity mat = materialRepository.findById(e.getLeatherMaterialId()).orElse(null);
        return ProductVariantLeatherResponse.builder()
                .id(e.getId())
                .productId(e.getProductId())
                .colorId(e.getColorId())
                .leatherMaterialId(e.getLeatherMaterialId())
                .leatherMaterialSku(mat != null ? mat.getSku() : null)
                .leatherMaterialName(mat != null ? mat.getName() : null)
                .qtyPerUnit(e.getQtyPerUnit())
                .build();
    }
}
