package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentDetailEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductShipmentDetailRepository extends JpaRepository<ProductShipmentDetailEntity, Long> {
    
    List<ProductShipmentDetailEntity> findByShipmentId(Long shipmentId);

    List<ProductShipmentDetailEntity> findByShipmentIdIn(Collection<Long> shipmentIds);

    Optional<ProductShipmentDetailEntity> findByShipmentIdAndProductId(Long shipmentId, Long productId);
    
    @Modifying
    @Transactional
    @Query("DELETE FROM ProductShipmentDetailEntity d WHERE d.shipmentId = :shipmentId")
    void deleteByShipmentId(@Param("shipmentId") Long shipmentId);
}

