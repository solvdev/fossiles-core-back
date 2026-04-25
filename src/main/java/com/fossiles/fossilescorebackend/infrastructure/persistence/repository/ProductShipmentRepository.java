package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductShipmentRepository extends JpaRepository<ProductShipmentEntity, Long> {
    
    List<ProductShipmentEntity> findByDistributionId(Long distributionId);
    
    Optional<ProductShipmentEntity> findByShipmentNumber(String shipmentNumber);
    
    boolean existsByShipmentNumber(String shipmentNumber);
    
    Optional<ProductShipmentEntity> findByDistributionIdAndLocationId(Long distributionId, Long locationId);

    List<ProductShipmentEntity> findByDistributionIdAndLocationIdOrderByIdAsc(Long distributionId, Long locationId);

    List<ProductShipmentEntity> findByLocationIdOrderByIdAsc(Long locationId);
}

