package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductVariantLeatherEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantLeatherRepository extends JpaRepository<ProductVariantLeatherEntity, Long> {

    List<ProductVariantLeatherEntity> findByProductIdOrderByColorIdAsc(Long productId);

    Optional<ProductVariantLeatherEntity> findByProductIdAndColorId(Long productId, Long colorId);

    Optional<ProductVariantLeatherEntity> findByProductIdAndColorIdIsNull(Long productId);
}
