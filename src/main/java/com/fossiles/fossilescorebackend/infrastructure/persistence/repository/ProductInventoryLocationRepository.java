package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryLocation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductInventoryLocationRepository extends JpaRepository<ProductInventoryLocation, Long> {
    
    Optional<ProductInventoryLocation> findByProductIdAndLocationId(Long productId, Long locationId);
    
    List<ProductInventoryLocation> findAllByProductIdAndLocationId(Long productId, Long locationId);

    Optional<ProductInventoryLocation> findByProductIdAndLocationIdAndColorId(Long productId, Long locationId, Long colorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pil FROM ProductInventoryLocation pil WHERE pil.productId = :productId "
            + "AND pil.locationId = :locationId "
            + "AND ((:colorId IS NULL AND pil.colorId IS NULL) OR pil.colorId = :colorId)")
    Optional<ProductInventoryLocation> findWithLockByProductIdAndLocationIdAndColorId(
            @Param("productId") Long productId,
            @Param("locationId") Long locationId,
            @Param("colorId") Long colorId);
    
    List<ProductInventoryLocation> findByProductId(Long productId);
    List<ProductInventoryLocation> findByProductIdAndColorId(Long productId, Long colorId);
    List<ProductInventoryLocation> findByProductIdAndColorIdIsNull(Long productId);
    
    List<ProductInventoryLocation> findByLocationId(Long locationId);
    
    @Query("SELECT SUM(pil.quantity) FROM ProductInventoryLocation pil WHERE pil.productId = :productId")
    java.math.BigDecimal getTotalQuantityByProductId(@Param("productId") Long productId);
    
    boolean existsByProductIdAndLocationId(Long productId, Long locationId);
}

