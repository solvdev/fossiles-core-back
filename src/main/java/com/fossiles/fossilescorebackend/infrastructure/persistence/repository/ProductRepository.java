package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<ProductEntity, Long> {
    Optional<ProductEntity> findByCode(String code);
    boolean existsByCode(String code);
    List<ProductEntity> findByCategoryId(Long categoryId);
    List<ProductEntity> findByCodeStartingWithIgnoreCaseOrderByCodeAsc(String prefix);
}

