package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductDistributionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductDistributionRepository extends JpaRepository<ProductDistributionEntity, Long> {
    
    Optional<ProductDistributionEntity> findByDistributionNumber(String distributionNumber);
    
    boolean existsByDistributionNumber(String distributionNumber);
}

