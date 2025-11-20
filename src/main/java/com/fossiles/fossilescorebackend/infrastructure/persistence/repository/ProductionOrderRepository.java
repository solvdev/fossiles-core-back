package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductionOrderRepository extends JpaRepository<ProductionOrderEntity, Long> {
    Optional<ProductionOrderEntity> findByCode(String code);
    boolean existsByCode(String code);
}

